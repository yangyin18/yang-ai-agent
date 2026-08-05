package com.cg.yangaiagent.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
public class WebSearchTool {

    private static final RestTemplate REST_TEMPLATE = new RestTemplate();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${search.api.key:}")
    private String apiKey;

    @Value("${search.api.endpoint:https://api.tavily.com/search}")
    private String endpoint;

    @Value("${search.max-results:5}")
    private int maxResults;

    @Tool(description = "搜索互联网获取最新信息，适用于实时信息、新闻、当前事件、知识查询等场景。")
    public String webSearch(@ToolParam(description = "搜索关键词") String query) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "错误：未配置 search.api.key，请在 application.yml 或环境变量中设置。";
        }
        try {
            TavilyRequest request = new TavilyRequest();
            request.setApiKey(apiKey);
            request.setQuery(query);
            request.setSearchDepth("basic");
            request.setIncludeAnswer(true);
            request.setMaxResults(maxResults);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            org.springframework.http.HttpEntity<TavilyRequest> entity =
                    new org.springframework.http.HttpEntity<>(request, headers);

            String responseJson = REST_TEMPLATE.postForObject(endpoint, entity, String.class);
            TavilyResponse response = OBJECT_MAPPER.readValue(responseJson, TavilyResponse.class);

            StringBuilder result = new StringBuilder();
            if (response.getAnswer() != null && !response.getAnswer().isEmpty()) {
                result.append("📝 摘要：").append(response.getAnswer()).append("\n\n");
            }
            if (response.getResults() == null || response.getResults().isEmpty()) {
                result.append("未找到相关搜索结果。");
            } else {
                result.append("🔍 搜索结果：\n\n");
                for (int i = 0; i < response.getResults().size(); i++) {
                    TavilyResult r = response.getResults().get(i);
                    result.append(i + 1).append(". ").append(r.getTitle()).append("\n");
                    result.append("   📎 ").append(r.getUrl()).append("\n");
                    result.append("   📄 ").append(r.getContent()).append("\n\n");
                }
            }
            return result.toString();

        } catch (Exception e) {
            return "搜索失败：" + e.getMessage();
        }
    }

    // ---- 内部模型类 ----
    private static class TavilyRequest {
        @JsonProperty("api_key") private String apiKey;
        @JsonProperty("query") private String query;
        @JsonProperty("search_depth") private String searchDepth;
        @JsonProperty("include_answer") private boolean includeAnswer;
        @JsonProperty("max_results") private int maxResults;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public String getSearchDepth() { return searchDepth; }
        public void setSearchDepth(String searchDepth) { this.searchDepth = searchDepth; }
        public boolean isIncludeAnswer() { return includeAnswer; }
        public void setIncludeAnswer(boolean includeAnswer) { this.includeAnswer = includeAnswer; }
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TavilyResponse {
        private String answer;
        private List<TavilyResult> results;

        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
        public List<TavilyResult> getResults() { return results; }
        public void setResults(List<TavilyResult> results) { this.results = results; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)   // ← 关键修复
    private static class TavilyResult {
        private String title;
        private String url;
        private String content;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }
}