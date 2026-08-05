package com.cg.yangaiagent.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class WebScrapeTool {

    private static final int TIMEOUT = 10000;           // 10秒超时
    private static final int MAX_CONTENT_LENGTH = 8000; // 最大返回字符数

    @Tool(description = """
            抓取指定网页的纯文本内容。适用于获取新闻、文章、文档等网页信息。
            如果网页是动态渲染的（如 SPA），可能无法获取完整内容。
            """)
    public String scrapeWebPage(@ToolParam(description = "要抓取的网页 URL，必须包含 http:// 或 https://") String url) {
        if (url == null || url.trim().isEmpty()) {
            return "错误：URL 不能为空";
        }

        // 简单验证 URL 格式
        if (!url.matches("^https?://.*")) {
            return "错误：URL 必须以 http:// 或 https:// 开头";
        }

        try {
            // 1. 连接并获取 HTML
            Document doc = Jsoup.connect(url)
                    .timeout(TIMEOUT)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .get();

            // 2. 移除脚本、样式、导航等无关元素（选择性地清理）
            doc.select("script, style, nav, footer, header, aside, .ad, .ads, [role='banner']").remove();

            // 3. 提取纯文本（保留段落结构）
            String text = doc.body().text();

            // 4. 清理多余空白（多个空格、换行合并）
            text = text.replaceAll("\\s+", " ").trim();

            // 5. 截断过长的内容（避免 Token 爆炸）
            if (text.length() > MAX_CONTENT_LENGTH) {
                text = text.substring(0, MAX_CONTENT_LENGTH) + "\n... (内容过长，已截断)";
            }

            // 6. 如果文本为空，提示可能无内容
            if (text.isEmpty()) {
                return "提示：该页面没有可抓取的文本内容。";
            }

            // 7. 返回结果，附带标题信息
            String title = doc.title();
            if (title != null && !title.isEmpty()) {
                return "📄 页面标题：" + title + "\n\n" + text;
            } else {
                return "📄 页面内容：\n\n" + text;
            }

        } catch (IOException e) {
            // 网络异常、404 等
            return "抓取失败（网络/服务器错误）：" + e.getMessage();
        } catch (Exception e) {
            // 其他异常（如超时、解析错误）
            return "抓取失败：" + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    // 可选：额外提供一个抓取指定 CSS 选择器的内容（更灵活）
    @Tool(description = "抓取网页中符合 CSS 选择器的元素文本，如 'article p' 或 '#content'")
    public String scrapeWebPageWithSelector(
            @ToolParam(description = "URL") String url,
            @ToolParam(description = "CSS 选择器，如 'article p' 或 '#main-content'") String selector) {

        if (url == null || url.trim().isEmpty() || selector == null || selector.trim().isEmpty()) {
            return "错误：URL 和选择器都不能为空";
        }

        try {
            Document doc = Jsoup.connect(url)
                    .timeout(TIMEOUT)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .get();

            // 提取选择器匹配的元素文本
            String text = doc.select(selector).text();
            text = text.replaceAll("\\s+", " ").trim();

            if (text.isEmpty()) {
                return "未找到指定选择器的内容。";
            }

            if (text.length() > MAX_CONTENT_LENGTH) {
                text = text.substring(0, MAX_CONTENT_LENGTH) + "\n... (内容过长，已截断)";
            }

            return "📄 选择器 '" + selector + "' 提取的内容：\n\n" + text;

        } catch (IOException e) {
            return "抓取失败（网络/服务器错误）：" + e.getMessage();
        } catch (Exception e) {
            return "抓取失败：" + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }
}