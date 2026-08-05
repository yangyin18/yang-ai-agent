package com.cg.yangaiagent.app;

import com.cg.yangaiagent.rag.BailianKnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * LoveApp 单元测试
 * 使用 Mock 替代外部依赖，测试核心逻辑
 */
@ExtendWith(MockitoExtension.class)
class LoveAppTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private BailianKnowledgeService bailianService;

    private LoveApp loveApp;

    @BeforeEach
    void setUp() {
        loveApp = new LoveApp(chatModel, vectorStore, bailianService, null);
        
        // 设置 @Value 字段
        ReflectionTestUtils.setField(loveApp, "maxHistorySize", 20);
        ReflectionTestUtils.setField(loveApp, "memoryDir", "./test_chat_memories/");
        ReflectionTestUtils.setField(loveApp, "similarityThreshold", 0.3);
        ReflectionTestUtils.setField(loveApp, "defaultTopK", 3);
        ReflectionTestUtils.setField(loveApp, "systemPrompt", "");
        ReflectionTestUtils.setField(loveApp, "ragSystemPrompt", "");
        ReflectionTestUtils.setField(loveApp, "bailianEnabled", false);
    }

    @Test
    @DisplayName("初始化 - 正常创建实例")
    void testInit() {
        assertNotNull(loveApp);
    }

    @Test
    @DisplayName("初始化 - null bailianService 不报错")
    void testInitWithNullBailianService() {
        LoveApp appWithoutBailian = new LoveApp(chatModel, vectorStore, null, null);
        assertNotNull(appWithoutBailian);
    }

    @Test
    @DisplayName("文档摄入 - 单文档")
    void testIngestDocument() {
        assertDoesNotThrow(() -> {
            loveApp.ingestDocument("测试文本", "测试来源");
        });
        verify(vectorStore, times(1)).add(anyList());
    }

    @Test
    @DisplayName("文档摄入 - 批量")
    void testIngestDocuments() {
        List<Document> docs = List.of(
                new Document("文本1", Map.of("source", "来源1")),
                new Document("文本2", Map.of("source", "来源2"))
        );

        assertDoesNotThrow(() -> {
            loveApp.ingestDocuments(docs);
        });
        verify(vectorStore, times(1)).add(docs);
    }

    @Test
    @DisplayName("清除历史 - 不抛异常")
    void testClearHistory() {
        assertDoesNotThrow(() -> {
            loveApp.clearHistory("test-session");
        });
    }

    @Test
    @DisplayName("获取状态 - 返回正确结构")
    void testGetStatus() {
        Map<String, Object> status = loveApp.getStatus();

        assertNotNull(status);
        assertTrue(status.containsKey("activeSessions"));
        assertTrue(status.containsKey("ragAvailable"));
        assertTrue(status.containsKey("embeddingModel"));
    }

    @Test
    @DisplayName("获取状态 - activeSessions 初始为0")
    void testGetStatusInitial() {
        Map<String, Object> status = loveApp.getStatus();
        assertEquals(0, status.get("activeSessions"));
    }

    @Test
    @DisplayName("VectorStore - 摄入文档验证调用")
    void testIngestDocumentVerification() {
        loveApp.ingestDocument("测试文本", "测试来源");
        
        verify(vectorStore).add(argThat(list -> 
            list.size() == 1 && 
            list.get(0).getText().equals("测试文本")
        ));
    }

    @Test
    @DisplayName("VectorStore - 批量摄入验证调用次数")
    void testIngestDocumentsCallCount() {
        List<Document> docs = List.of(
                new Document("文本1", Map.of("source", "来源1")),
                new Document("文本2", Map.of("source", "来源2"))
        );

        loveApp.ingestDocuments(docs);
        loveApp.ingestDocuments(docs);
        
        verify(vectorStore, times(2)).add(docs);
    }

}
