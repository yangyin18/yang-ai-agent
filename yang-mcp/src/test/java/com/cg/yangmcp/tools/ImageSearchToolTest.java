package com.cg.yangmcp.tools;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实调用 Pexels API 的测试（不启动 Spring Boot）
 */
public class ImageSearchToolTest {

    @Test
    void realSearch() {
        // 1. 手动创建工具实例
        ImageSearchTool tool = new ImageSearchTool();

        // 2. 通过反射设置真实的 API Key（从 https://www.pexels.com/api/ 获取）
        String apiKey = "你的真实PEXELS_API_KEY";   // 替换成你自己的
        ReflectionTestUtils.setField(tool, "apiKey", apiKey);

        // 3. 调用真实 API
        String result = tool.searchImageSimple("sunset");

        // 4. 打印结果到控制台
        System.out.println("===== 真实搜索结果 =====");
        System.out.println(result);
        System.out.println("===== 结束 =====");

        // 5. 断言结果不为空
        assertNotNull(result);
        // 如果返回的内容包含“未找到”或“搜索失败”或图片链接，都算正常响应
        // 你可以根据实际情况调整断言
    }
}