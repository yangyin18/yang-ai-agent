package com.cg.yangaiagent.config;

import com.cg.yangaiagent.tools.*;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolRegistration {

    @Value("${search.api.key:}")
    private String searchApiKey;

    @Bean
    public ToolCallback[] allTools() {
        FileOperationTool fileOperationTool = new FileOperationTool();
        WebSearchTool webSearchTool = new WebSearchTool();
        WebScrapeTool webScrapeTool = new WebScrapeTool();     // 改：与文件名匹配
        DownloadTool downloadTool = new DownloadTool();        // 改：与文件名匹配
        TerminalTool terminalTool = new TerminalTool();        // 改：与文件名匹配
        PdfTool pdfTool = new PdfTool();                      // 改：与文件名匹配（如果你有）
        TerminateTool terminateTool = new TerminateTool();
        return ToolCallbacks.from(
                fileOperationTool,
                webSearchTool,
                webScrapeTool,
                downloadTool,
                terminalTool,
                pdfTool,
                terminateTool
        );
    }
}