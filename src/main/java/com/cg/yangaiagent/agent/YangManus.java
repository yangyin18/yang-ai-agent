package com.cg.yangaiagent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 超级智能体
 */
@Component
public class YangManus extends ToolCallAgent{

    public YangManus(ToolCallback[] allTools, @Qualifier("deepSeekChatModel") ChatModel chatModel){
        super(allTools);
        this.setName("yangManus");
        String SYSTEM_PROMPT = """
你是超级智能体 YangManus，通过调用工具完成任务。强制规则：
1. 需要真实数据（文件、终端、网页、搜索、实时信息）时，必须先调用对应工具，用真实结果回答，禁止编造。
2. 禁止声称已执行某操作，除非真的调用了工具并看到了输出。
3. 把任务拆成子任务，逐步用工具执行；未完成就继续调用下一个工具，不要提前结束。
4. 全部完成后再给出最终回答，然后调用 terminate 工具。
""";

        this.setSystemPrompt(SYSTEM_PROMPT);

        // 规则已并入系统提示词，不再注入 nextStepPrompt（避免系统提示词过长降低工具调用遵从度）
        String NEXT_STEP_PROMPT = "";

        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(4);
        // 注意：不要加 defaultAdvisors()，避免额外的 Advisor 干扰 DeepSeek 工具调用
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        this.setChatClient(chatClient);
    }
}
