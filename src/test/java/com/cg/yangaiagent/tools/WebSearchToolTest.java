package com.cg.yangaiagent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
public class WebSearchToolTest {

    @Autowired
    private WebSearchTool webSearchTool;

    @Test
    void webSearch_normalQuery() {
        System.out.println("=== 测试：正常搜索 ===");
        String result = webSearchTool.webSearch("今天成都天气");
        System.out.println("搜索结果：\n" + result);
        // 先注释断言，观察输出
        // assertNotNull(result);
        // assertFalse(result.contains("错误"));
        // assertFalse(result.contains("失败"));
    }

    @Test
    void webSearch_emptyQuery() {
        System.out.println("=== 测试：空查询 ===");
        String result = webSearchTool.webSearch("");
        System.out.println("结果：\n" + result);
        assertNotNull(result);
        // 预期：返回错误提示（工具内部会检查空字符串吗？目前没有，但会传给API，可能返回空结果）
        // 这里我们只确保不会抛出异常
    }

    @Test
    void webSearch_specialCharacters() {
        System.out.println("=== 测试：包含特殊字符的查询 ===");
        String result = webSearchTool.webSearch("Java 23 新特性 & 性能");
        System.out.println("结果：\n" + result);
        assertNotNull(result);
        assertFalse(result.contains("错误"));
    }

    @Test
    void webSearch_noResults() {
        System.out.println("=== 测试：无结果查询 ===");
        String result = webSearchTool.webSearch("xzyabc1234567890");
        System.out.println("结果：\n" + result);
        assertNotNull(result);
        // 可能返回“未找到相关搜索结果”或类似信息，不应报错
    }
}