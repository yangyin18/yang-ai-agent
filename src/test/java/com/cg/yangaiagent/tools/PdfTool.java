package com.cg.yangaiagent.tools;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

@Component
public class PdfTool {

    @Autowired(required = false)
    private WebScrapeTool webScrapeTool;

    @Value("${pdf.save-dir:./pdfs}")
    private String pdfDir;

    private static final int DEFAULT_FONT_SIZE = 12;
    private static final int TITLE_FONT_SIZE = 18;

    // 缓存字体，避免重复加载
    private BaseFont cachedFont;

    private synchronized BaseFont getFont() throws DocumentException, IOException {
        if (cachedFont != null) {
            return cachedFont;
        }

        // 尝试加载中文字体，若失败则使用默认字体（仅英文）
        try {
            // 1. 尝试系统字体（Windows）
            cachedFont = BaseFont.createFont("SimSun", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        } catch (Exception e1) {
            try {
                // 2. 尝试 Linux 常用字体
                cachedFont = BaseFont.createFont("WenQuanYi Micro Hei", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            } catch (Exception e2) {
                try {
                    // 3. 尝试从 classpath 加载（如果有字体文件）
                    cachedFont = BaseFont.createFont("/fonts/simsun.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                } catch (Exception e3) {
                    // 4. 最终回退：使用 Helvetica（不支持中文，但保证英文正常）
                    cachedFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
                    System.out.println("⚠️ 警告：未找到中文字体，PDF 中文将显示为方框，建议安装 SimSun 或 WenQuanYi 字体。");
                }
            }
        }
        return cachedFont;
    }

    @Tool(description = "将纯文本内容保存为 PDF 文件，可指定标题和文件名。")
    public String saveTextAsPdf(
            @ToolParam(description = "要保存的文本内容") String content,
            @ToolParam(description = "PDF 文档标题（可选）", required = false) String title,
            @ToolParam(description = "保存的文件名（不含扩展名，可选）", required = false) String fileName) {

        if (content == null || content.trim().isEmpty()) {
            return "错误：内容不能为空";
        }

        // ---- 目录处理 ----
        Path dirPath = Paths.get(pdfDir);
        try {
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }
        } catch (IOException e) {
            return "创建 PDF 目录失败：" + e.getMessage();
        }

        // ---- 文件名处理 ----
        String finalFileName = (fileName != null && !fileName.trim().isEmpty()) ? fileName.trim() : "document_" + Instant.now().toEpochMilli();
        finalFileName = finalFileName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        if (!finalFileName.toLowerCase().endsWith(".pdf")) {
            finalFileName += ".pdf";
        }

        // 安全校验
        if (finalFileName.contains("..") || finalFileName.contains("/") || finalFileName.contains("\\")) {
            return "错误：文件名包含非法字符";
        }

        Path targetPath = dirPath.resolve(finalFileName).normalize();
        if (!targetPath.startsWith(dirPath.normalize())) {
            return "错误：非法文件路径";
        }

        if (Files.exists(targetPath)) {
            return "错误：文件已存在：" + targetPath.toString();
        }

        // ---- 生成 PDF ----
        try (FileOutputStream fos = new FileOutputStream(targetPath.toFile())) {
            Document document = new Document(PageSize.A4);
            PdfWriter.getInstance(document, fos);
            document.open();

            // 获取字体
            BaseFont bf = getFont();
            Font titleFont = new Font(bf, TITLE_FONT_SIZE, Font.BOLD);
            Font contentFont = new Font(bf, DEFAULT_FONT_SIZE, Font.NORMAL);

            // 添加标题（若提供）
            if (title != null && !title.trim().isEmpty()) {
                Paragraph titlePara = new Paragraph(title.trim(), titleFont);
                titlePara.setAlignment(Element.ALIGN_CENTER);
                titlePara.setSpacingAfter(20);
                document.add(titlePara);
            }

            // 添加内容（按段落分割）
            String[] paragraphs = content.split("\n\n");
            for (String para : paragraphs) {
                if (para.trim().isEmpty()) continue;
                Paragraph p = new Paragraph(para.trim(), contentFont);
                p.setSpacingAfter(10);
                document.add(p);
            }

            document.close();

            long size = Files.size(targetPath);
            return "✅ PDF 保存成功！\n" +
                    "文件名: " + finalFileName + "\n" +
                    "保存路径: " + targetPath.toAbsolutePath().toString() + "\n" +
                    "文件大小: " + (size / 1024) + " KB";

        } catch (Exception e) {
            // 删除可能残留的文件
            try {
                Files.deleteIfExists(targetPath);
            } catch (IOException ignored) {}
            return "生成 PDF 失败：" + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    @Tool(description = "抓取网页内容并保存为 PDF 文件（需要 WebScrapeTool 支持）。")
    public String saveUrlAsPdf(
            @ToolParam(description = "要抓取的网页 URL") String url,
            @ToolParam(description = "保存的文件名（不含扩展名，可选）", required = false) String fileName) {

        if (webScrapeTool == null) {
            return "错误：WebScrapeTool 未启用，无法抓取网页内容。请确保已注入 WebScrapeTool。";
        }

        String content = webScrapeTool.scrapeWebPage(url);
        if (content == null || content.startsWith("错误") || content.startsWith("抓取失败")) {
            return "网页抓取失败：" + content;
        }

        // 提取标题
        String title = null;
        String cleanContent = content;
        if (content.startsWith("📄 页面标题：")) {
            int titleEnd = content.indexOf("\n\n");
            if (titleEnd > 0) {
                title = content.substring("📄 页面标题：".length(), titleEnd).trim();
                cleanContent = content.substring(titleEnd + 2);
            }
        }
        if (title == null || title.isEmpty()) {
            title = url;
        }

        return saveTextAsPdf(cleanContent, title, fileName);
    }
}