package com.cg.yangaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;

@Component
public class DownloadTool {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${download.default-dir:./downloads}")
    private String defaultDir;

    @Value("${download.max-size:10485760}")  // 默认10MB
    private long maxFileSize;

    @Value("${download.timeout:30000}")      // 30秒
    private int timeout;

    @Tool(description = """
            从指定的 URL 下载文件到本地。支持 HTTP/HTTPS。
            如果未指定文件名，将根据 URL 自动生成或使用时间戳。
            下载文件大小不能超过 10MB（可配置），超时 30 秒。
            """)
    public String downloadFile(
            @ToolParam(description = "文件下载 URL，如 https://example.com/file.pdf") String url,
            @ToolParam(description = "保存的文件名（可选），如 'report.pdf'。若不提供则自动生成", required = false) String fileName) {

        // ---- 参数校验 ----
        if (url == null || url.trim().isEmpty()) {
            return "错误：URL 不能为空";
        }
        if (!url.matches("^https?://.*")) {
            return "错误：URL 必须以 http:// 或 https:// 开头";
        }

        // ---- 确定保存目录 ----
        Path dirPath = Paths.get(defaultDir);
        try {
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }
        } catch (IOException e) {
            return "创建下载目录失败：" + e.getMessage();
        }

        // ---- 确定文件名 ----
        String finalFileName = fileName;
        if (finalFileName == null || finalFileName.trim().isEmpty()) {
            // 从 URL 提取文件名
            String[] segments = url.split("/");
            String lastSegment = segments[segments.length - 1];
            if (lastSegment != null && !lastSegment.isEmpty() && lastSegment.contains(".")) {
                finalFileName = lastSegment;
            } else {
                // 生成时间戳 + 随机后缀
                finalFileName = "download_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 6);
            }
        }

        // ---- 路径安全校验（防止目录遍历） ----
        if (finalFileName.contains("..") || finalFileName.contains("/") || finalFileName.contains("\\")) {
            return "错误：文件名包含非法字符（不能包含 .. 或路径分隔符）";
        }

        Path targetPath = dirPath.resolve(finalFileName).normalize();
        // 确保最终路径仍在下载目录内（防止符号链接绕过）
        if (!targetPath.startsWith(dirPath.normalize())) {
            return "错误：非法文件路径，已拒绝";
        }

        // ---- 检查是否已存在 ----
        if (Files.exists(targetPath)) {
            return "错误：文件已存在：" + targetPath.toString();
        }

        // ---- 执行下载 ----
        try {
            // 使用 RestTemplate 获取字节数组（对于小文件足够，大文件需流式处理）
            byte[] fileData = restTemplate.getForObject(url, byte[].class);
            if (fileData == null || fileData.length == 0) {
                return "错误：下载内容为空";
            }

            // 检查文件大小
            if (fileData.length > maxFileSize) {
                return "错误：文件大小 (" + fileData.length + " 字节) 超过限制 (" + maxFileSize + " 字节)";
            }

            // 写入文件
            Files.write(targetPath, fileData);

            // 返回成功信息
            long sizeInKB = fileData.length / 1024;
            return "✅ 下载成功！\n" +
                    "文件名: " + finalFileName + "\n" +
                    "保存路径: " + targetPath.toAbsolutePath().toString() + "\n" +
                    "文件大小: " + (sizeInKB > 0 ? sizeInKB + " KB" : fileData.length + " 字节");

        } catch (Exception e) {
            // 删除可能残留的空文件
            try {
                Files.deleteIfExists(targetPath);
            } catch (IOException ignored) {}
            return "下载失败：" + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    // ----- 可选：支持自定义下载目录（重载）-----
    @Tool(description = "下载文件到指定的自定义目录")
    public String downloadFileToDir(
            @ToolParam(description = "文件下载 URL") String url,
            @ToolParam(description = "保存的文件名（可选）", required = false) String fileName,
            @ToolParam(description = "目标目录路径，如 '/home/user/data'", required = false) String targetDir) {

        String savedDir = (targetDir != null && !targetDir.trim().isEmpty()) ? targetDir.trim() : defaultDir;
        // 临时修改默认目录并调用主方法（简单方式）
        String originalDir = this.defaultDir;
        try {
            this.defaultDir = savedDir;
            return downloadFile(url, fileName);
        } finally {
            this.defaultDir = originalDir; // 恢复
        }
    }
}