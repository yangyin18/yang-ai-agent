package com.cg.yangaiagent.agent;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest
class YangManusTest {
    @Resource
    private YangManus yangManus;

    @Test
    public void run() {
        String userPrompt = """
                帮我搜索今天上海的天气，然后根据天气推荐一个适合的约会活动。
                保存成pdf
                """;
        long start = System.currentTimeMillis();
        String answer = yangManus.run(userPrompt);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("==================== 最终回答 ====================");
        System.out.println(answer);
        System.out.println("==================== 总耗时: " + elapsed + "ms ====================");
        Assertions.assertNotNull(answer);
    }
}