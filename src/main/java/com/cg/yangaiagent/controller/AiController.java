package com.cg.yangaiagent.controller;

import com.alibaba.fastjson2.JSON;
import com.cg.yangaiagent.agent.AgentService;
import com.cg.yangaiagent.app.LoveApp;
import com.cg.yangaiagent.model.LoveReport;
import com.cg.yangaiagent.model.RagStreamResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 恋爱助手统一入口。
 * <p>
 * 对话类接口（/chat、/rag/chat、/bailian/chat、/tools/chat）均为 SSE 流式，
 * 通过 Server-Sent Events 逐步下发 AI 回答；工具类接口（报告、文档摄入、历史、状态）返回 JSON。
 * <p>
 * SSE 事件约定：
 * <ul>
 *   <li>{@code meta}  — 首事件，携带检索明细（chatId / sources / retrievedDocs / metadata），仅 RAG、百炼接口有</li>
 *   <li>{@code token} — 每次 AI 输出片段，data 为 {"content":"..."}</li>
 *   <li>{@code done}  — 结束事件，data 为 {"success":true,...}</li>
 *   <li>{@code error} — 异常事件，data 为 {"success":false,"error":"..."}</li>
 * </ul>
 */
@RestController
@RequestMapping("/ai")
@Tag(name = "AI 助手接口", description = "SSE 流式对话：普通对话 / RAG 检索 / 百炼知识库 / 工具对话；JSON 工具接口：报告 / 文档摄入 / 历史 / 状态")
@Slf4j
public class AiController {

    private final LoveApp loveApp;
    private final AgentService agentService;

    public AiController(LoveApp loveApp, AgentService agentService) {
        this.loveApp = loveApp;
        this.agentService = agentService;
    }

    // =============== 普通对话（SSE） ===============

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "普通对话（SSE 流式）",
            description = "流式返回 AI 回答，事件：token(内容) / done(结束) / error(异常)。通过 chatId 延续上下文（缺省 default_global_session）")
    public Flux<ServerSentEvent<String>> chat(@RequestBody Map<String, String> request) {
        String chatId = request.get("chatId");
        String message = request.get("message");
        if (!StringUtils.hasText(message)) {
            return sseError("参数 message 不能为空");
        }
        Map<String, Object> done = new HashMap<>();
        done.put("success", true);
        done.put("chatId", chatId != null ? chatId : "default_global_session");
        return toSse(loveApp.chatStream(chatId, message), null, done);
    }

    // =============== 结构化恋爱报告（JSON） ===============

    @PostMapping("/chat-report")
    @Operation(summary = "生成结构化恋爱报告",
            description = "返回包含 userName/coreIssue/advice/riskWarning 字段的 JSON 报告")
    public Map<String, Object> chatReport(@RequestBody Map<String, String> request) {
        String chatId = request.get("chatId");
        String userName = request.get("userName");
        String message = request.get("message");
        if (!StringUtils.hasText(message)) {
            return error("参数 message 不能为空");
        }
        try {
            LoveReport report = loveApp.chatReport(chatId, userName, message);
            Map<String, Object> result = success();
            result.put("chatId", chatId != null ? chatId : "default_global_session");
            result.put("report", report);
            return result;
        } catch (Exception e) {
            log.error("报告生成失败 chatId={}", chatId, e);
            return error("报告生成失败: " + e.getMessage());
        }
    }

    // =============== RAG 增强对话（SSE） ===============

    @PostMapping(value = "/rag/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "RAG 增强对话（SSE 流式）",
            description = "基于本地向量库/百炼知识库检索后流式回答，事件：meta(检索明细) / token(内容) / done / error")
    public Flux<ServerSentEvent<String>> ragChat(@RequestBody Map<String, Object> request) {
        String chatId = (String) request.get("chatId");
        String message = (String) request.get("message");
        Integer topK = (Integer) request.get("topK");
        if (!StringUtils.hasText(message)) {
            return sseError("参数 message 不能为空");
        }
        RagStreamResponse response = (topK != null && topK > 0)
                ? loveApp.chatWithRagStreamDetailed(chatId, message, topK)
                : loveApp.chatWithRagStreamDetailed(chatId, message);

        Map<String, Object> meta = new HashMap<>();
        meta.put("chatId", chatId != null ? chatId : "default_global_session");
        meta.put("sources", response.getSources());
        meta.put("retrievedDocs", response.getRetrievedDocs());
        meta.put("metadata", response.getMetadata());

        Map<String, Object> done = new HashMap<>();
        done.put("success", true);
        return toSse(response.getTokens(), meta, done);
    }

    // =============== 百炼知识库对话（SSE） ===============

    @PostMapping(value = "/bailian/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "百炼知识库对话（SSE 流式）",
            description = "使用百炼应用回答（检索+生成），不可用时自动回退本地 RAG。百炼无原生流式，完整答案以单个 token 事件返回")
    public Flux<ServerSentEvent<String>> bailianChat(@RequestBody Map<String, Object> request) {
        String chatId = (String) request.get("chatId");
        String message = (String) request.get("message");
        Integer topK = (Integer) request.get("topK");
        if (!StringUtils.hasText(message)) {
            return sseError("参数 message 不能为空");
        }
        RagStreamResponse response = (topK != null && topK > 0)
                ? loveApp.chatWithBailianStream(chatId, message, topK)
                : loveApp.chatWithBailianStream(chatId, message);

        Map<String, Object> meta = new HashMap<>();
        meta.put("chatId", chatId != null ? chatId : "default_global_session");
        meta.put("sources", response.getSources());
        meta.put("retrievedDocs", response.getRetrievedDocs());
        meta.put("metadata", response.getMetadata());

        Map<String, Object> done = new HashMap<>();
        done.put("success", true);
        return toSse(response.getTokens(), meta, done);
    }

    // =============== RAG 文档摄入（JSON） ===============

    @PostMapping("/rag/ingest")
    @Operation(summary = "摄入单条文档", description = "将一段文本写入知识库，source 用于标注来源")
    public Map<String, Object> ingestDocument(@RequestBody Map<String, String> request) {
        String text = request.get("text");
        String source = request.get("source");
        if (!StringUtils.hasText(text)) {
            return error("参数 text 不能为空");
        }
        try {
            loveApp.ingestDocument(text, source);
            Map<String, Object> result = success();
            result.put("message", "文档摄入成功");
            result.put("textLength", text.length());
            result.put("source", source != null ? source : "manual_input");
            return result;
        } catch (Exception e) {
            log.error("文档摄入失败", e);
            return error("文档摄入失败: " + e.getMessage());
        }
    }

    @PostMapping("/rag/ingest-batch")
    @Operation(summary = "批量摄入文档", description = "请求体为数组：[{text, source?}, ...]")
    public Map<String, Object> ingestBatch(@RequestBody List<Map<String, String>> documents) {
        if (documents == null || documents.isEmpty()) {
            return error("请求体不能为空，需为 [{text, source?}, ...] 数组");
        }
        try {
            List<Document> docs = new ArrayList<>();
            for (Map<String, String> doc : documents) {
                String text = doc.get("text");
                if (!StringUtils.hasText(text)) {
                    return error("文档 text 不能为空");
                }
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source", doc.getOrDefault("source", "batch_input"));
                metadata.put("timestamp", System.currentTimeMillis());
                docs.add(new Document(text, metadata));
            }
            loveApp.ingestDocuments(docs);
            Map<String, Object> result = success();
            result.put("message", "批量摄入成功");
            result.put("count", docs.size());
            return result;
        } catch (Exception e) {
            log.error("批量文档摄入失败", e);
            return error("批量文档摄入失败: " + e.getMessage());
        }
    }

    // =============== 工具调用对话（SSE） ===============

    @PostMapping(value = "/tools/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "工具调用对话（SSE 流式）",
            description = "AI 可自主调用 webSearch / executeCommand / readFile / writeFile 等工具完成任务，流式返回最终回答")
    public Flux<ServerSentEvent<String>> toolsChat(@RequestBody Map<String, String> request) {
        String chatId = request.get("chatId");
        String message = request.get("message");
        if (!StringUtils.hasText(message)) {
            return sseError("参数 message 不能为空");
        }
        Map<String, Object> done = new HashMap<>();
        done.put("success", true);
        done.put("chatId", chatId != null ? chatId : "default_tools_session");
        done.put("toolsAvailable", loveApp.getToolCount());
        return toSse(loveApp.chatWithToolsStream(chatId, message), null, done);
    }

    // =============== 超级智能体（SSE） ===============

    @PostMapping(value = "/agent/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "超级智能体（SSE 流式）",
            description = "运行 YangManus 多步智能体：思考 → 调用工具 → 最终回答。事件：step / think / tool_call / tool_result / token(最终回答流) / note / done / error")
    public Flux<ServerSentEvent<String>> agentRun(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        if (!StringUtils.hasText(message)) {
            return sseError("参数 message 不能为空");
        }
        return agentService.runAgent(message);
    }

    // =============== 对话历史管理（JSON） ===============

    @DeleteMapping("/history/{chatId}")
    @Operation(summary = "清除对话历史", description = "删除指定 chatId 的内存会话与磁盘持久化文件")
    public Map<String, Object> clearHistory(@PathVariable String chatId) {
        try {
            loveApp.clearHistory(chatId);
            Map<String, Object> result = success();
            result.put("message", "对话历史已清除");
            result.put("chatId", chatId);
            return result;
        } catch (Exception e) {
            log.error("清除历史失败 chatId={}", chatId, e);
            return error("清除历史失败: " + e.getMessage());
        }
    }

    // =============== 状态查询（JSON） ===============

    @GetMapping("/status")
    @Operation(summary = "系统状态", description = "返回当前活跃会话数、持久化会话数、RAG/向量库可用性等信息")
    public Map<String, Object> getStatus() {
        Map<String, Object> result = success();
        result.put("status", loveApp.getStatus());
        return result;
    }

    // =============== 公共方法 ===============

    /**
     * 将 token 流包装为 SSE 事件序列：可选 meta 首事件 → token 事件 → done 事件；异常时转为 error 事件。
     */
    private static Flux<ServerSentEvent<String>> toSse(Flux<String> tokens, Map<String, Object> meta, Map<String, Object> done) {
        Flux<ServerSentEvent<String>> events = Flux.concat(
                meta == null ? Flux.empty() : Flux.just(sseEvent("meta", json(meta))),
                tokens.map(t -> sseEvent("token", tokenJson(t))),
                Flux.just(sseEvent("done", json(done)))
        );
        return events.onErrorResume(e -> {
            log.error("SSE 流式响应异常", e);
            return Flux.just(sseEvent("error", json(Map.of("success", false, "error", String.valueOf(e.getMessage())))));
        });
    }

    private static ServerSentEvent<String> sseEvent(String name, String data) {
        return ServerSentEvent.builder(data).event(name).build();
    }

    private static String json(Object obj) {
        return JSON.toJSONString(obj);
    }

    private static String tokenJson(String content) {
        Map<String, Object> m = new HashMap<>();
        m.put("content", content);
        return JSON.toJSONString(m);
    }

    private static Flux<ServerSentEvent<String>> sseError(String message) {
        return Flux.just(sseEvent("error", json(Map.of("success", false, "error", message))));
    }

    private static Map<String, Object> success() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("error", message);
        return result;
    }
}
