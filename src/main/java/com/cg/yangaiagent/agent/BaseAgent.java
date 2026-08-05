package com.cg.yangaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.cg.yangaiagent.agent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

@Data
@Slf4j
public abstract class BaseAgent {
    // 核心属性
    private String name;

    // 提示词
    private String systemPrompt;
    private String nextStepPrompt;

    // 代理状态
    private AgentState state = AgentState.IDLE;

    // 执行步骤控制
    private int currentStep = 0;
    private int maxSteps = 10;

    // LLM 大模型
    private ChatClient chatClient;

    // 执行过程监听器（可选，用于流式推送智能体的思考/工具调用/回答）
    private AgentListener listener;

    // Memory 记忆（需要自主维护会话上下文）
    // 消息类型包括 UserMessage、AssistantMessage、ToolResponseMessage
    private List<Message> messageList = new ArrayList<>();

    /**
     *
     * @return
     */
    public String run(String userPrompt){
        if(this.state != AgentState.IDLE) {
            throw new RuntimeException("Cannot run agent from state" + this.state);
        }
        if(StrUtil.isBlank(userPrompt)) {
            throw new RuntimeException("Cannot run agent with empty user prompt");
        }
        //执行更改配置
        this.state = AgentState.RUNNING;
        //记录上下文
        messageList.add(new UserMessage(userPrompt));
        //保存结果列表
        List<String> results = new ArrayList<>();
        long startTime = System.currentTimeMillis();
        try {
            log.info("┌─────────────────────────────────────────────┐");
            log.info("│ [{}] 开始执行，最大步数: {}                     │", getName(), maxSteps);
            log.info("│ 用户输入: {}...                                │", 
                    userPrompt.length() > 40 ? userPrompt.substring(0, 40) : userPrompt);
            log.info("└─────────────────────────────────────────────┘");
            
            for(int i = 0;i < maxSteps && state != AgentState.FINISHED;i ++) {
                int stepNumber = i + 1;
                currentStep = stepNumber;
                if (listener != null) {
                    listener.onStepStart(stepNumber, maxSteps);
                }
                long stepStart = System.currentTimeMillis();
                log.info("┌─[{}] Step {}/{} ──────────────────────────────┐", getName(), stepNumber, maxSteps);
                //单步执行（think → act 循环）
                String stepResult = step();
                long stepElapsed = System.currentTimeMillis() - stepStart;
                String result = "Step " + stepNumber + ": " + stepResult;
                results.add(result);
                log.info("└─[{}] Step {}/{} 完成，耗时 {}ms ──────────────┘", getName(), stepNumber, maxSteps, stepElapsed);
            }
            if(currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
            long totalElapsed = System.currentTimeMillis() - startTime;
            log.info("[{}] 执行完毕，状态: {}，总耗时: {}ms", getName(), state, totalElapsed);
            return String.join("\n",results);
        }catch (Exception e) {
            state = AgentState.ERROR;
            log.error("error executing agent",e);
            return "执行错误" + e.getMessage();
        }finally {
            this.cleanup();
        }
    }

    /**
     *
     * @return
     */
    public abstract String step();

    /**
     * 清理资源
     */
    public void cleanup(){

    }
}
