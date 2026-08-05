package com.cg.yangaiagent.tools;

import com.cg.yangaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件操作工具类
 */
public class FileOperationTool {

    private final String FILE_DIR = FileConstant.FILE_SAVE_DIR + "/file";

    @Tool(description = "读文件")
    public String readFile(@ToolParam(description = "读取文件名称") String fileName) {
        // 路径安全校验：禁止相对路径绕过
        if (fileName == null || fileName.isEmpty() || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return "错误：非法文件名，不允许包含路径分隔符或 '..'";
        }
        try {
            Path filePath = Paths.get(FILE_DIR, fileName);
            if (!Files.exists(filePath)) {
                return "错误：文件不存在 - " + fileName;
            }
            String content = Files.readString(filePath);
            return "读取成功：\n" + content;
        } catch (IOException e) {
            return "读取文件失败：" + e.getMessage();
        }
    }

    @Tool(description = "写文件")
    public String writeFile(@ToolParam(description = "写文件名称") String fileName,
                            @ToolParam(description = "文件内容") String content) {
        // 路径安全校验
        if (fileName == null || fileName.isEmpty() || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return "错误：非法文件名，不允许包含路径分隔符或 '..'";
        }
        try {
            Path dirPath = Paths.get(FILE_DIR);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath); // 确保目录存在
            }
            Path filePath = dirPath.resolve(fileName);
            Files.writeString(filePath, content == null ? "" : content);
            return "写入成功：" + fileName;
        } catch (IOException e) {
            return "写入文件失败：" + e.getMessage();
        }
    }
}