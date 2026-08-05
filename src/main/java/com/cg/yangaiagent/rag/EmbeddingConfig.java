package com.cg.yangaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@Slf4j
public class EmbeddingConfig {

    @Value("${dashscope.api-key:}")
    private String apiKey;

    @Value("${dashscope.embedding.model:text-embedding-v2}")
    private String modelName;

    @Value("${dashscope.embedding.dimensions:1536}")
    private int dimensions;

    @Bean
    public EmbeddingModel embeddingModel() {
        if (!StringUtils.hasText(apiKey)) {
            log.warn("================================================");
            log.warn("未配置 dashscope.api-key，EmbeddingModel 未启用");
            log.warn("RAG 向量检索功能将不可用");
            log.warn("请设置环境变量 DASHSCOPE_API_KEY");
            log.warn("================================================");
            return null;
        }

        log.info("初始化 DashScope Embedding Model: model={}, dimensions={}", modelName, dimensions);
        log.info("API Key: {}...{}",
                apiKey.substring(0, Math.min(6, apiKey.length())),
                apiKey.substring(Math.max(0, apiKey.length() - 4)));

        return new DashScopeEmbeddingModel(apiKey, modelName, dimensions);
    }
}
