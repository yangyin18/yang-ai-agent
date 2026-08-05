package com.cg.yangaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

@Component
public class TerminalTool {

    private static final long DEFAULT_TIMEOUT_SECONDS = 15;
    private static final int MAX_OUTPUT_LENGTH = 20000;

    @Tool(description = """
            在本地终端执行系统命令，并返回命令的输出结果。
            支持任何可执行命令，如 'ls -la', 'dir', 'echo Hello', 'ping baidu.com -c 4' 等。
            注意：某些耗时命令可能会超时（默认15秒）。
            """)
    public String executeCommand(@ToolParam(description = "要执行的完整命令，如 'ls -la' 或 'dir'") String command) {
        if (command == null || command.trim().isEmpty()) {
            return "错误：命令不能为空";
        }

        // ----- 安全警告：生产环境请限制命令白名单！-----
        // if (!isCommandAllowed(command)) {
        //     return "错误：该命令不在允许执行的白名单中。";
        // }

        Process process = null;
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder builder;
            if (os.contains("win")) {
                builder = new ProcessBuilder("cmd", "/c", command);
            } else {
                builder = new ProcessBuilder("sh", "-c", command);
            }

            builder.redirectErrorStream(true);
            process = builder.start();

            // 🔥 关键修复：根据操作系统选择正确的字符集
            Charset charset = os.contains("win") ? Charset.forName("GBK") : Charset.forName("UTF-8");

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (output.length() > MAX_OUTPUT_LENGTH) {
                        output.append("... (输出过长，已截断)");
                        break;
                    }
                }
            }

            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "错误：命令执行超时（超过 " + DEFAULT_TIMEOUT_SECONDS + " 秒）";
            }

            int exitCode = process.exitValue();
            String result = output.toString().trim();
            if (result.isEmpty()) {
                result = "(无输出)";
            }

            return "退出码: " + exitCode + "\n输出:\n" + result;

        } catch (IOException e) {
            return "执行命令失败（I/O错误）：" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "执行命令被中断：" + e.getMessage();
        } catch (Exception e) {
            return "执行命令失败：" + e.getClass().getSimpleName() + " - " + e.getMessage();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    // 可选白名单方法（实现略，可参考之前版本）
}