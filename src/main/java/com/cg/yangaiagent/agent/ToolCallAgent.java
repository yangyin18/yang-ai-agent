package com.cg.yangaiagent.agent;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 处理工具调用代理类（适配 DeepSeek）
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    // 可用的工具类
    private final ToolCallback[] availableTools;

    // 保存工具调用信息的响应结果
    private ChatResponse toolCallChatResponse;
    // 工具调用管理
    private final ToolCallingManager toolCallingManager;

    // DeepSeek 专用 Options（ToolCallingChatOptions，确保工具定义传播到请求）
    private final org.springframework.ai.deepseek.DeepSeekChatOptions chatOptions;

    public ToolCallAgent(ToolCallback[] availableTools) {
        super();
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.chatOptions = org.springframework.ai.deepseek.DeepSeekChatOptions.builder().build();
    }

    @Override
    public boolean think() {
        // 1、如果存在下一步提示词，合并进系统提示词（仅一次）。
        //    注意：不能作为第二条用户消息注入 —— DeepSeek 面对连续两条用户消息时
        //    倾向于直接回答而不触发工具调用（会导致 think-act 循环退化为单轮直接回答）。
        if (StrUtil.isNotBlank(getNextStepPrompt())) {
            String nextPrompt = getNextStepPrompt();
            // 清理已使用的提示词，防止后续轮次重复添加
            setNextStepPrompt(null);
            String sp = getSystemPrompt();
            setSystemPrompt((sp == null || sp.isBlank() ? "" : sp + "\n\n") + nextPrompt);
        }

        // 2、调用 AI 大模型，获取工具调用结果
        List<Message> messageList = getMessageList();
        if (messageList.isEmpty()) {
            log.warn("{} 消息列表为空，无法执行 think()", getName());
            return false;
        }

        try {
            // 调试：打印即将调用的工具列表
            log.info("[{}] 本轮携带 {} 个工具: {}", getName(), availableTools.length,
                    java.util.Arrays.stream(availableTools)
                            .map(t -> t.getToolDefinition().name())
                            .collect(java.util.stream.Collectors.joining(", ")));
            log.info("[{}] 消息列表共 {} 条 (最后一条类型: {})", getName(), messageList.size(),
                    messageList.isEmpty() ? "空" : messageList.get(messageList.size() - 1).getMessageType());

            ChatResponse chatResponse = getChatClient()
                    .prompt(new Prompt(messageList, this.chatOptions))
                    .system(getSystemPrompt())
                    .tools(availableTools)
                    .call()
                    .chatResponse();

            // 记录响应，供 act() 使用
            this.toolCallChatResponse = chatResponse;

            // 3、解析工具调用结果
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();

            // 调试：打印 API 元数据（metadata 可能为 null，如测试 mock）
            var metadata = chatResponse.getResult().getMetadata();
            log.info("[{}] API 返回 — finishReason={}, metadata={}",
                    getName(),
                    metadata != null ? metadata.getFinishReason() : "unknown",
                    metadata);

            // 记录思考内容（text 可能为 null，当 AI 仅返回 tool_calls 时）
            String text = assistantMessage.getText();
            String thinkingLog = text != null ? text : "(AI 仅返回工具调用，无文本内容)";
            log.info("[{}] 思考内容: {}", getName(), thinkingLog);
            log.info("[{}] 本轮选择 {} 个工具", getName(), toolCallList.size());

            // 打印工具调用详情
            if (!toolCallList.isEmpty()) {
                StringBuilder toolInfoBuilder = new StringBuilder();
                for (int i = 0; i < toolCallList.size(); i++) {
                    AssistantMessage.ToolCall tc = toolCallList.get(i);
                    toolInfoBuilder.append(String.format("  [%d] 工具: %s | 参数: %s",
                            i + 1, tc.name(), tc.arguments()));
                    if (i < toolCallList.size() - 1) {
                        toolInfoBuilder.append("\n");
                    }
                }
                log.info("[{}] 工具调用详情:\n{}", getName(), toolInfoBuilder.toString());
            }

            // 4、根据工具调用列表决定返回值
            if (toolCallList.isEmpty()) {
                // 无需调用工具，AI 已给出最终回答 → 记录助手消息到对话历史
                getMessageList().add(assistantMessage);
                log.info("[{}] 无需工具调用，AI 已给出最终回答", getName());
                notifyListeners(text, toolCallList, true);
                return false;
            } else {
                // 需要调用工具 → 返回 true，由 act() 执行工具
                // 注意：此处不将 assistantMessage 加入消息列表，act() 中会统一加入
                notifyListeners(text, toolCallList, false);
                return true;
            }

        } catch (Exception e) {
            log.error("[{}] 思考过程异常: {}", getName(), e.getMessage(), e);
            // 发生异常时，添加错误消息到对话历史，返回 false 终止本轮循环
            getMessageList().add(new AssistantMessage(
                    "思考时遇到错误: " + e.getMessage()));
            return false;
        }
    }

    @Override
    public String act() {
        // 1、验证 think() 是否已经成功获取了工具调用响应
        if (this.toolCallChatResponse == null) {
            log.warn("[{}] 没有工具调用响应（think() 未成功执行或已完成），跳过 act()", getName());
            return "没有工具调用信息";
        }

        // 2、从助手消息中提取工具调用列表
        AssistantMessage assistantMessage = this.toolCallChatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();

        if (toolCallList.isEmpty()) {
            log.info("[{}] 没有需要执行的工具调用", getName());
            return "没有需要执行的工具";
        }

        // 3、将助手消息（含 toolCall）加入对话历史
        // 下一轮 think() 时 LLM 会看到这些 toolCall 和对应的执行结果
        getMessageList().add(assistantMessage);

        // 4、逐个执行工具调用
        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
        StringBuilder resultLog = new StringBuilder();
        int successCount = 0;
        int failCount = 0;

        for (AssistantMessage.ToolCall toolCall : toolCallList) {
            String toolName = toolCall.name();
            String toolInput = toolCall.arguments();

            log.info("[{}] 执行工具 [{}/{}]: {} | 参数: {}",
                    getName(), toolResponses.size() + 1, toolCallList.size(),
                    toolName, toolInput);

            // 在 availableTools 中按名称查找匹配的 ToolCallback
            String execResult = null;
            for (ToolCallback callback : availableTools) {
                if (callback.getToolDefinition().name().equals(toolName)) {
                    try {
                        execResult = callback.call(toolInput);
                        successCount++;
                        log.info("[{}] 工具 {} 执行成功", getName(), toolName);
                    } catch (Exception e) {
                        execResult = "工具执行失败: " + e.getMessage();
                        failCount++;
                        log.error("[{}] 工具 {} 执行异常: {}", getName(), toolName, e.getMessage(), e);
                    }
                    break;
                }
            }

            if (execResult == null) {
                execResult = "错误: 未找到工具 " + toolName;
                failCount++;
                log.warn("[{}] 未找到匹配的工具: {}", getName(), toolName);
            }

            // 5、收集工具响应（格式化为 LLM 易读的结构）
            if (getListener() != null) {
                getListener().onToolResult(toolName, execResult);
            }
            toolResponses.add(new ToolResponseMessage.ToolResponse(
                    toolCall.id(), toolName, execResult));

            // 记录执行日志（截断过长结果）
            String shortResult = execResult.length() > 300
                    ? execResult.substring(0, 300) + "..."
                    : execResult;
            resultLog.append(String.format("[%s] %s\n", toolName, shortResult));
        }

        // 6、将工具执行结果加入对话历史，供下一轮 think() 使用
        getMessageList().add(new ToolResponseMsg(toolResponses));

        // 7、清空响应引用，准备下一轮 think() → act() 循环
        this.toolCallChatResponse = null;

        log.info("[{}] 本轮工具执行完成: 总计 {} 个, 成功 {} 个, 失败 {} 个",
                getName(), toolCallList.size(), successCount, failCount);
        return resultLog.toString().trim();
    }

    /**
     * 向监听器推送本轮的思考文本、工具调用（或最终回答）
     */
    private void notifyListeners(String text, List<AssistantMessage.ToolCall> toolCalls, boolean isFinal) {
        AgentListener listener = getListener();
        if (listener == null) {
            return;
        }
        if (isFinal) {
            // 无工具调用的回合：think 文本即最终回答，只通过 onFinal 流式下发，避免重复
            listener.onFinal(text);
        } else {
            // 工具调用回合：简短思考文本 + 各工具调用
            if (text != null && !text.isBlank()) {
                listener.onThink(text);
            }
            for (AssistantMessage.ToolCall tc : toolCalls) {
                listener.onToolCall(tc.name(), tc.arguments());
            }
        }
    }

    /**
     * 辅助子类：暴露 ToolResponseMessage 的 protected 构造器
     */
    private static class ToolResponseMsg extends ToolResponseMessage {
        ToolResponseMsg(List<ToolResponse> responses) {
            super(responses, Map.of());
        }
    }
}