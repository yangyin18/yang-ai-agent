package com.cg.yangaiagent.demo.invoke;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;

/**
 * Spring AI 框架调用 DeepSeek 大模型（演示用，已禁用 @Component，不会在应用启动时自动执行）
 */
public class SpringAi implements CommandLineRunner {

    // 指定使用 DeepSeek ChatModel（避免与 DashScope 冲突）
    @Resource
    @Qualifier("deepSeekChatModel")
    private ChatModel chatModel;

    @Override
    public void run(String... args) throws Exception {
        AssistantMessage assistantMessage = chatModel.call(new Prompt("你好，我是杨银"))
                .getResult()
                .getOutput();
        System.out.println("DeepSeek返回结果：" + assistantMessage.getText());
    }
}