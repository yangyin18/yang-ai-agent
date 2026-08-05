package com.cg.yangaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class LoveAppDocumentLoader {

    private final ResourcePatternResolver resourcePatternResolver;

    public LoveAppDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    public List<Document> loadAllMarkdowns() {
        List<Document> allDocuments = new ArrayList<>();
        Map<String, Integer> fileStats = new HashMap<>();
        long totalTextLength = 0;

        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");
            log.info("发现 {} 个 Markdown 文件待加载", resources.length);

            int fileIndex = 0;
            for (Resource resource : resources) {
                fileIndex++;
                String filename = resource.getFilename();
                log.info("[{}/{}] 正在加载: {}", fileIndex, resources.length, filename);

                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("fileName", filename)
                        .build();

                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
                List<Document> documents = reader.get();

                for (Document doc : documents) {
                    doc.getMetadata().put("source", filename);
                    doc.getMetadata().put("fileIndex", fileIndex);
                    doc.getMetadata().put("totalFiles", resources.length);
                    totalTextLength += doc.getText().length();
                }

                allDocuments.addAll(documents);
                fileStats.put(filename, documents.size());
                log.info("[{}/{}] {} 加载完成, 拆分为 {} 个 Document", fileIndex, resources.length, filename, documents.size());
            }

            log.info("========== 文档加载汇总 ==========");
            log.info("文件总数: {}", resources.length);
            log.info("Document 总数: {}", allDocuments.size());
            log.info("总文本长度: {} 字符", totalTextLength);
            for (Map.Entry<String, Integer> entry : fileStats.entrySet()) {
                log.info("  - {}: {} 个 Document", entry.getKey(), entry.getValue());
            }
            log.info("====================================");

        } catch (IOException e) {
            log.error("加载 Markdown 文档失败", e);
        }

        return allDocuments;
    }

    public List<Document> loadSingleMarkdown(String filePath) {
        try {
            Resource resource = resourcePatternResolver.getResource(filePath);
            if (!resource.exists()) {
                log.error("文件不存在: {}", filePath);
                return new ArrayList<>();
            }

            log.info("加载单个文档: {}", filePath);
            MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                    .withHorizontalRuleCreateDocument(true)
                    .withAdditionalMetadata("fileName", resource.getFilename())
                    .build();

            MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
            List<Document> documents = reader.get();
            
            for (Document doc : documents) {
                doc.getMetadata().put("source", resource.getFilename());
            }
            
            log.info("文档 {} 加载完成, 拆分为 {} 个 Document", resource.getFilename(), documents.size());
            return documents;
        } catch (Exception e) {
            log.error("加载单个文档失败: {}", filePath, e);
            return new ArrayList<>();
        }
    }
}
