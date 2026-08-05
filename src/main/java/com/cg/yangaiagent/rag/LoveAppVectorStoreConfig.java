package com.cg.yangaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

@Configuration
@Slf4j
public class LoveAppVectorStoreConfig {

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Bean
    @Primary
    public VectorStore loveAppVectorStore(LoveAppDocumentLoader documentLoader) {
        log.info("================================================");
        log.info("开始初始化 RAG 向量存储...");

        // 尝试 PgVectorStore（需要阿里云 RDS PostgreSQL），失败则回退到内存存储
        if (jdbcTemplate != null) {
            try {
                VectorStore pgStore = initPgVectorStore(documentLoader);
                if (pgStore != null) return pgStore;
            } catch (Exception e) {
                log.error("PostgreSQL RDS 不可用（可能已停机），回退到内存向量存储: {}", e.getMessage());
            }
        } else {
            log.warn("未配置 DataSource，将使用内存向量存储");
        }

        // 回退：使用内存向量存储（数据重启后丢失，但应用可正常运行）
        log.info("================================================");
        log.warn("使用 SimpleVectorStore（内存存储）");
        log.warn("RAG 检索可用，但重启后文档需重新加载");
        log.info("================================================");
        return initMemoryVectorStore(documentLoader);
    }

    /**
     * 初始化 PostgreSQL 向量存储
     */
    private VectorStore initPgVectorStore(LoveAppDocumentLoader documentLoader) {
        // 验证 PG 连接是否可用
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            log.info("PostgreSQL RDS 连接正常");
        } catch (Exception e) {
            log.error("PostgreSQL RDS 连接验证失败: {}", e.getMessage());
            throw new RuntimeException("PG不可用", e);
        }

        EmbeddingModel effectiveModel = embeddingModel;
        if (effectiveModel == null) {
            log.warn("EmbeddingModel 未配置 (无 API Key)，使用 NoOpEmbeddingModel");
            effectiveModel = new NoOpEmbeddingModel();
        }

        log.info("Embedding Model: {}", effectiveModel.getClass().getSimpleName());
        log.info("使用 PgVectorStore (PostgreSQL 持久化存储)");

        PgVectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, effectiveModel)
                .vectorTableName("vector_store")
                .dimensions(effectiveModel.dimensions())
                .build();
        try {
            ((org.springframework.beans.factory.InitializingBean) vectorStore).afterPropertiesSet();
        } catch (Exception e) {
            log.warn("PgVectorStore schema 初始化失败 (可能已存在): {}", e.getMessage());
        }

        log.info("正在加载知识文档...");
        List<Document> documentList = documentLoader.loadAllMarkdowns();

        if (!documentList.isEmpty()) {
            log.info("正在将 {} 个文档添加到向量库 (调用 Embedding API)...", documentList.size());
            long startTime = System.currentTimeMillis();

            try {
                vectorStore.add(documentList);
                long elapsed = System.currentTimeMillis() - startTime;
                double elapsedSec = elapsed / 1000.0;
                double avgPerDoc = (double) elapsed / documentList.size();
                log.info("PgVectorStore 初始化完成!");
                log.info("  - 文档数量: {}", documentList.size());
                log.info("  - 耗时: {} ms ({}s)", elapsed, String.format("%.1f", elapsedSec));
                log.info("  - 平均每条: {} ms", String.format("%.1f", avgPerDoc));
                log.info("================================================");
            } catch (Exception e) {
                log.error("文档写入 PgVectorStore 失败: {}", e.getMessage());
                log.warn("PG 读写异常，回退到内存向量存储");
                throw new RuntimeException("PG写入失败", e);
            }
        } else {
            log.warn("没有加载到任何文档，请检查 resources/document/ 目录");
        }

        return vectorStore;
    }

    /**
     * 初始化内存向量存储（降级方案）
     */
    private VectorStore initMemoryVectorStore(LoveAppDocumentLoader documentLoader) {
        EmbeddingModel effectiveModel = embeddingModel;
        if (effectiveModel == null) {
            log.warn("EmbeddingModel 未配置，使用 NoOpEmbeddingModel (向量无意义，RAG 检索不可用)");
            effectiveModel = new NoOpEmbeddingModel();
        }

        SimpleVectorStore vectorStore = SimpleVectorStore.builder(effectiveModel).build();

        log.info("正在加载知识文档到内存...");
        List<Document> documentList = documentLoader.loadAllMarkdowns();

        if (!documentList.isEmpty()) {
            try {
                vectorStore.add(documentList);
                log.info("SimpleVectorStore 初始化完成: {} 个文档已加载到内存", documentList.size());
            } catch (Exception e) {
                log.error("内存向量存储初始化失败: {}", e.getMessage(), e);
                log.warn("文档加载失败，RAG 检索功能将不可用");
            }
        } else {
            log.warn("没有加载到任何文档");
        }

        return vectorStore;
    }
}
