package com.cg.yangmcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Service
public class ImageSearchTool {

    private static final String PEXELS_API_URL = "https://api.pexels.com/v1/search";

    @Value("${pexels.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 搜索图片（支持分页）
     */
    @Tool
    public String searchImage(
            @ToolParam(description = "搜索关键词") String query,
            @ToolParam(description = "页码，默认1") Integer page,
            @ToolParam(description = "每页数量，默认15") Integer perPage) {

        log.info("收到图片搜索请求: query={}, page={}, perPage={}", query, page, perPage);

        try {
            // 构建 URL
            StringBuilder url = new StringBuilder(PEXELS_API_URL);
            url.append("?query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
            if (page != null && page > 0) url.append("&page=").append(page);
            if (perPage != null && perPage > 0) url.append("&per_page=").append(Math.min(perPage, 80));

            log.debug("请求 URL: {}", url);

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", apiKey);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // 发送请求
            ResponseEntity<String> response = restTemplate.exchange(
                    url.toString(), HttpMethod.GET, entity, String.class);

            log.debug("响应状态码: {}", response.getStatusCode());

            // 解析 JSON
            JsonNode root = objectMapper.readTree(response.getBody());
            int total = root.path("total_results").asInt(0);
            JsonNode photos = root.path("photos");

            if (total == 0 || !photos.isArray() || photos.size() == 0) {
                log.info("未找到与 '{}' 相关的图片", query);
                return "未找到相关图片";
            }

            // 构建返回结果
            StringBuilder result = new StringBuilder("找到 " + total + " 张图片：\n");
            for (JsonNode photo : photos) {
                String urlStr = photo.path("src").path("medium").asText();
                if (urlStr.isEmpty()) urlStr = photo.path("src").path("small").asText();
                result.append("- ").append(urlStr).append("\n");
            }
            log.info("搜索成功，返回 {} 条图片链接", photos.size());
            return result.toString();

        } catch (Exception e) {
            log.error("搜索图片失败", e);
            return "搜索失败：" + e.getMessage();
        }
    }

    /**
     * 简化搜索（仅关键词）
     */
    @Tool
    public String searchImageSimple(@ToolParam(description = "搜索关键词") String query) {
        return searchImage(query, null, null);
    }

    // ==================== 独立测试入口（隐藏在这里） ====================
    public static void main(String[] args) {
        try {
            // 读取 application.yml 中的 pexels.api.key
            Yaml yaml = new Yaml();
            try (InputStream in = ImageSearchTool.class.getClassLoader().getResourceAsStream("application.yml")) {
                Map<String, Object> config = yaml.load(in);
                Map<String, Object> pexels = (Map<String, Object>) config.get("pexels");
                Map<String, Object> api = (Map<String, Object>) pexels.get("api");
                String apiKeyFromFile = (String) api.get("key");

                System.out.println("✅ 已从 application.yml 读取 API Key: " + apiKeyFromFile);

                // 手动创建工具实例，并通过反射注入 key
                ImageSearchTool tool = new ImageSearchTool();
                Field field = ImageSearchTool.class.getDeclaredField("apiKey");
                field.setAccessible(true);
                field.set(tool, apiKeyFromFile);

                // 执行搜索（关键词可自行修改）
                String result = tool.searchImageSimple("sunset");

                System.out.println("\n===== 真实搜索结果 =====");
                System.out.println(result);
                System.out.println("===== 结束 =====");
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("❌ 执行失败，请检查 application.yml 是否存在且格式正确。");
        }
    }
}