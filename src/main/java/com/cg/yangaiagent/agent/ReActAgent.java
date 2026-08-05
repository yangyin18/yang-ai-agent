package com.cg.yangaiagent.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

/**
 * 实现了思考-行动的循环模式
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public abstract class ReActAgent extends BaseAgent{
    /**
     * 处理当前状态并决定下一步行动
     */
    public abstract boolean think();


    /**
     * 执行决定的行动
     */
    public abstract String act();

    @Override
    public String step() {
        try {
            // 先思考：AI 分析当前上下文，决定是否需要调用工具
            long thinkStart = System.currentTimeMillis();
            boolean shouldAct = think();
            long thinkElapsed = System.currentTimeMillis() - thinkStart;
            
            if (!shouldAct) {
                // 无需工具调用，AI 已给出最终回答 → 设置状态为 FINISHED 终止循环
                log.info("[{}] 思考完成 ({}ms) → 无需工具，最终回答已给出，终止执行", getName(), thinkElapsed);
                setState(com.cg.yangaiagent.agent.model.AgentState.FINISHED);
                return "思考完成 - 无需行动（AI 已给出最终回答）";
            }
            
            // 再行动：执行 AI 选择的工具并获取结果
            long actStart = System.currentTimeMillis();
            String actResult = act();
            long actElapsed = System.currentTimeMillis() - actStart;
            log.info("[{}] think={}ms | act={}ms | 本轮总={}ms", 
                    getName(), thinkElapsed, actElapsed, thinkElapsed + actElapsed);
            
            return actResult;
        } catch (Exception e) {
            log.error("{} 执行 think-act 步骤失败: {}", getName(), e.getMessage(), e);
            return "步骤执行失败: " + e.getMessage();
        }
    }
}
