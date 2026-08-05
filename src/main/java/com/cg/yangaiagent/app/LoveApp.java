package com.cg.yangaiagent.app;

import com.alibaba.fastjson2.JSON;
import com.cg.yangaiagent.model.LoveReport;
import com.cg.yangaiagent.model.RagChatResponse;
import com.cg.yangaiagent.model.RagStreamResponse;
import com.cg.yangaiagent.rag.BailianKnowledgeService;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import lombok.extern.slf4j.Slf4j;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class LoveApp {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final BailianKnowledgeService bailianService;
    private final ToolCallback[] toolCallbacks;
    private final Map<String, List<Message>> conversationHistory = new ConcurrentHashMap<>();

    @Value("${loveapp.history.max-size:20}")
    private int maxHistorySize;

    @Value("${loveapp.memory.dir:./chat_memories/}")
    private String memoryDir;

    @Value("${loveapp.rag.similarity-threshold:0.3}")
    private double similarityThreshold;

    @Value("${loveapp.rag.top-k:3}")
    private int defaultTopK;

    @Value("${loveapp.system.prompt:}")
    private String systemPrompt;

    @Value("${loveapp.rag.system.prompt:}")
    private String ragSystemPrompt;

    @Value("${bailian.enabled:false}")
    private boolean bailianEnabled;

    private static final ThreadLocal<Kryo> kryoThreadLocal = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
        return kryo;
    });

    public LoveApp(@Qualifier("deepSeekChatModel") ChatModel chatModel, VectorStore vectorStore, 
                   @Autowired(required = false) BailianKnowledgeService bailianService,
                   @Autowired(required = false) ToolCallback[] toolCallbacks) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.vectorStore = vectorStore;
        this.bailianService = bailianService;
        this.toolCallbacks = toolCallbacks != null ? toolCallbacks : new ToolCallback[0];
    }

    private String getSystemPrompt() {
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            return systemPrompt;
        }
        return """
                角色定位：顶级实战派恋爱情感大师，拥有十年情感咨询经验，精通异性心理、男女思维差异、聊天话术、约会推进、矛盾化解、暧昧拉升、关系边界把控、挽回修复、择偶筛选全套体系。
                核心原则：
                1. 三观正向，拒绝舔狗、PUA、套路欺骗、养鱼等不良价值观，所有方案以互相尊重、双向奔赴、真诚长久为底层逻辑；
                2. 不搞玄学鸡汤，全部给出可直接复制使用的话术、分步执行动作、风险点提醒、对方反馈预判；

                回答格式要求：
                1. 先一句话点明当前问题核心症结；
                2. 给出2-3套不同风格可直接复制话术（稳妥版/幽默版/走心版）；
                3. 附带执行注意事项+对方不同回复的应对方案。
                """;
    }

    private String getRagSystemPrompt() {
        if (ragSystemPrompt != null && !ragSystemPrompt.isBlank()) {
            return ragSystemPrompt;
        }
        return """
                你是恋爱情感专家AI助手。用户会提供参考知识片段，请你严格基于参考知识来回答用户问题。
                要求：
                1. 优先使用参考知识中的信息进行回答，不要编造参考知识中没有的内容；
                2. 如果参考知识不足以回答，坦诚告知用户，并给出一般性建议；
                3. 回答时标注引用的知识片段编号（如 [知识1]、[知识2]）；
                4. 回答结构：先点明核心症结，再给出具体话术和步骤，最后提醒风险点。
                """;
    }

    private String getToolsSystemPrompt() {
        return """
                你是一个智能助手，可以调用以下工具来完成任务：
                - webSearch: 搜索互联网获取最新信息
                - executeCommand: 在终端执行系统命令（如 ls, dir, echo, ping 等）
                - readFile: 读取文件内容
                - writeFile: 写入文件
                - scrapeUrl: 抓取网页内容
                - downloadFile: 下载文件
                - generatePdf: 生成 PDF 文件

                当用户的问题需要实时信息、执行操作或读取文件时，请主动调用相应工具。
                调用工具后，基于工具返回的结果给用户一个清晰、有帮助的回答。
                如果不需要工具就能回答，直接回答即可。
                """;
    }

    // =============== 持久化方法 ===============
    private void loadHistory(String chatId) {
        File file = new File(memoryDir + chatId + ".dat");
        if (!file.exists()) return;
        try (Input input = new Input(new FileInputStream(file))) {
            Kryo kryo = kryoThreadLocal.get();
            @SuppressWarnings("unchecked")
            List<Message> messages = kryo.readObject(input, ArrayList.class);
            conversationHistory.put(chatId, messages);
        } catch (IOException e) {
            log.error("加载历史失败: {}", chatId, e);
        }
    }

    private void saveHistory(String chatId) {
        List<Message> history = conversationHistory.get(chatId);
        if (history == null || history.isEmpty()) return;
        File file = new File(memoryDir + chatId + ".dat");
        try (Output output = new Output(new FileOutputStream(file))) {
            Kryo kryo = kryoThreadLocal.get();
            kryo.writeObject(output, history);
        } catch (IOException e) {
            log.error("保存历史失败: {}", chatId, e);
        }
    }

    private void ensureMemoryDir() {
        File dir = new File(memoryDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    // =============== 普通对话 ===============
    public String chat(String chatId, String userMessage) {
        ensureMemoryDir();
        String finalChatId = (chatId == null || chatId.isBlank()) ? "default_global_session" : chatId;
        loadHistory(finalChatId);

        List<Message> history = conversationHistory.getOrDefault(finalChatId, new ArrayList<>());
        int start = Math.max(0, history.size() - maxHistorySize);
        List<Message> recentHistory = history.subList(start, history.size());

        List<Message> messages = new ArrayList<>(recentHistory);
        messages.add(new UserMessage(userMessage));

        String assistantMessage = chatClient.prompt()
                .messages(messages)
                .system(getSystemPrompt())
                .call()
                .content();

        history.add(new UserMessage(userMessage));
        history.add(new AssistantMessage(assistantMessage));
        if (history.size() > maxHistorySize) {
            history.subList(0, history.size() - maxHistorySize).clear();
        }
        conversationHistory.put(finalChatId, history);
        saveHistory(finalChatId);

        return assistantMessage;
    }

    // =============== 恋爱报告（结构化输出） ===============
    public LoveReport chatReport(String chatId, String userName, String userMessage) {
        ensureMemoryDir();
        String finalChatId = (chatId == null || chatId.isBlank()) ? "default_global_session" : chatId;
        loadHistory(finalChatId);

        List<Message> history = conversationHistory.getOrDefault(finalChatId, new ArrayList<>());
        int start = Math.max(0, history.size() - maxHistorySize);
        List<Message> recentHistory = history.subList(start, history.size());

        List<Message> messages = new ArrayList<>(recentHistory);
        messages.add(new UserMessage(userMessage));

        String systemText = String.format(
                "每次对话生成恋爱报告结果，标题为「%s的恋爱报告」，并以JSON格式输出。\n" +
                        "报告必须包含以下字段：\n" +
                        "  - userName: 用户名（字符串）\n" +
                        "  - coreIssue: 当前问题核心症结（字符串）\n" +
                        "  - advice: 具体建议（字符串）\n" +
                        "  - riskWarning: 风险提醒（字符串）\n" +
                        "只输出JSON，不要其他内容。",
                userName
        );

        String jsonResponse = chatClient.prompt()
                .messages(messages)
                .system(systemText)
                .call()
                .content();

        LoveReport report = JSON.parseObject(jsonResponse, LoveReport.class);

        history.add(new UserMessage(userMessage));
        history.add(new AssistantMessage(jsonResponse));
        if (history.size() > maxHistorySize) {
            history.subList(0, history.size() - maxHistorySize).clear();
        }
        conversationHistory.put(finalChatId, history);
        saveHistory(finalChatId);

        return report;
    }

    // =============== RAG 增强对话 ===============
    public String chatWithRag(String chatId, String userMessage) {
        return chatWithRag(chatId, userMessage, defaultTopK);
    }

    public String chatWithRag(String chatId, String userMessage, int topK) {
        RagChatResponse response = chatWithRagDetailed(chatId, userMessage, topK);
        return response.getAnswer();
    }

    public RagChatResponse chatWithRagDetailed(String chatId, String userMessage) {
        return chatWithRagDetailed(chatId, userMessage, defaultTopK);
    }

    public RagChatResponse chatWithRagDetailed(String chatId, String userMessage, int topK) {
        ensureMemoryDir();
        String finalChatId = (chatId == null || chatId.isBlank()) ? "default_global_session" : chatId;

        RetrievalOutcome retrieval = retrieve(userMessage, topK);
        List<Document> docs = retrieval.docs;
        List<String> sources = retrieval.sources;
        String retrievalSource = retrieval.retrievalSource;
        String context = retrieval.context;

        loadHistory(finalChatId);
        List<Message> history = conversationHistory.getOrDefault(finalChatId, new ArrayList<>());
        int start = Math.max(0, history.size() - maxHistorySize);
        List<Message> recentHistory = history.subList(start, history.size());

        List<Message> messages = new ArrayList<>(recentHistory);
        String finalUserMessage;
        if (!docs.isEmpty()) {
            finalUserMessage = "请参考以下知识片段回答用户问题。\n" +
                    context +
                    "用户问题：" + userMessage;
        } else {
            finalUserMessage = "（暂无相关参考知识，请基于通用恋爱心理学回答）\n用户问题：" + userMessage;
        }
        messages.add(new UserMessage(finalUserMessage));

        String ragPrompt = getRagSystemPrompt();
        String assistantMessage = chatClient.prompt()
                .messages(messages)
                .system(ragPrompt)
                .call()
                .content();

        history.add(new UserMessage(userMessage));
        history.add(new AssistantMessage(assistantMessage));
        if (history.size() > maxHistorySize) {
            history.subList(0, history.size() - maxHistorySize).clear();
        }
        conversationHistory.put(finalChatId, history);
        saveHistory(finalChatId);

        RagChatResponse response = new RagChatResponse();
        response.setAnswer(assistantMessage);
        response.setSources(sources);
        response.setRetrievedDocs(docs.size());
        response.setQuery(userMessage);
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("retrievalSource", retrievalSource);
        response.setMetadata(metadata);
        
        return response;
    }

    /**
     * 检索知识库（百炼优先，回退本地向量库），并组装提示上下文
     */
    private RetrievalOutcome retrieve(String userMessage, int topK) {
        List<Document> docs = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        String retrievalSource = "local_vector_store";

        // 优先使用百炼知识库
        if (bailianEnabled && bailianService != null && bailianService.isAvailable()) {
            log.info("使用百炼知识库检索: query='{}', topK={}", userMessage, topK);
            try {
                BailianKnowledgeService.BailianSearchResult bailianResult =
                        bailianService.search(userMessage, topK);
                docs = bailianResult.getDocuments();

                log.info("百炼检索结果: query='{}', 命中={} 条", userMessage, docs.size());
                if (!docs.isEmpty()) {
                    retrievalSource = "bailian_knowledge_base";
                    for (int i = 0; i < docs.size(); i++) {
                        Document doc = docs.get(i);
                        String source = (String) doc.getMetadata().getOrDefault("source", "百炼知识库");
                        double score = doc.getMetadata().containsKey("score") ?
                                ((Number) doc.getMetadata().get("score")).doubleValue() : 0.0;
                        sources.add(source);
                        log.debug("  百炼命中[{}]: source={}, score={}, 内容预览={}",
                                i, source, score, doc.getText().substring(0, Math.min(100, doc.getText().length())));
                    }
                } else {
                    // 百炼未命中，回退到本地向量检索
                    log.info("百炼未命中，回退到本地向量检索");
                    docs = localVectorSearch(userMessage, topK);
                    sources = extractSources(docs);
                    retrievalSource = "local_vector_store_fallback";
                }
            } catch (Exception e) {
                log.error("百炼检索失败，回退到本地向量检索: {}", e.getMessage());
                docs = localVectorSearch(userMessage, topK);
                sources = extractSources(docs);
                retrievalSource = "local_vector_store_fallback";
            }
        } else {
            // 使用本地向量检索
            docs = localVectorSearch(userMessage, topK);
            sources = extractSources(docs);
        }

        StringBuilder contextBuilder = new StringBuilder();
        if (!docs.isEmpty()) {
            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                contextBuilder.append("【知识片段").append(i + 1).append("】\n");
                contextBuilder.append(doc.getText()).append("\n\n");
            }
        }

        RetrievalOutcome outcome = new RetrievalOutcome();
        outcome.docs = docs;
        outcome.sources = sources;
        outcome.retrievalSource = retrievalSource;
        outcome.context = contextBuilder.toString();
        return outcome;
    }

    /**
     * RAG 检索结果载体
     */
    private static class RetrievalOutcome {
        List<Document> docs;
        List<String> sources;
        String retrievalSource;
        String context;
    }

    /**
     * 使用本地向量存储检索
     */
    private List<Document> localVectorSearch(String query, int topK) {
        List<Document> docs = new ArrayList<>();
        try {
            docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(similarityThreshold)
                            .build()
            );
            log.info("本地向量检索: query='{}', threshold={}, topK={}, 命中={} 条",
                    query, similarityThreshold, topK, docs.size());
        } catch (Exception e) {
            log.error("本地向量检索失败: query='{}', error={}", query, e.getMessage(), e);
        }
        return docs;
    }

    /**
     * 从文档列表提取来源信息
     */
    private List<String> extractSources(List<Document> docs) {
        List<String> sources = new ArrayList<>();
        for (Document doc : docs) {
            String source = (String) doc.getMetadata().getOrDefault("source",
                    doc.getMetadata().getOrDefault("fileName", "未知"));
            sources.add(source);
        }
        return sources;
    }

    // =============== 百炼直接对话模式 ===============
    
    /**
     * 使用百炼应用直接回答（包含检索+生成）
     */
    public RagChatResponse chatWithBailian(String chatId, String userMessage) {
        return chatWithBailian(chatId, userMessage, defaultTopK);
    }

    public RagChatResponse chatWithBailian(String chatId, String userMessage, int topK) {
        ensureMemoryDir();
        String finalChatId = (chatId == null || chatId.isBlank()) ? "default_global_session" : chatId;

        if (bailianService == null || !bailianService.isAvailable()) {
            log.warn("百炼服务不可用，回退到本地 RAG");
            return chatWithRagDetailed(chatId, userMessage, topK);
        }

        log.info("百炼直接对话: query='{}', topK={}", userMessage, topK);
        
        BailianKnowledgeService.BailianAnswerResult result = 
                bailianService.answer(userMessage, topK);

        String answer = result.getAnswer();
        List<String> sources = result.getSources();
        List<Document> docs = result.getDocuments();

        // 如果百炼没有返回答案，回退到本地 LLM
        if (answer == null || answer.isBlank()) {
            log.warn("百炼未返回答案，回退到本地 LLM");
            return chatWithRagDetailed(chatId, userMessage, topK);
        }

        // 保存历史
        loadHistory(finalChatId);
        List<Message> history = conversationHistory.getOrDefault(finalChatId, new ArrayList<>());
        history.add(new UserMessage(userMessage));
        history.add(new AssistantMessage(answer));
        if (history.size() > maxHistorySize) {
            history.subList(0, history.size() - maxHistorySize).clear();
        }
        conversationHistory.put(finalChatId, history);
        saveHistory(finalChatId);

        RagChatResponse response = new RagChatResponse();
        response.setAnswer(answer);
        response.setSources(sources);
        response.setRetrievedDocs(docs.size());
        response.setQuery(userMessage);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("retrievalSource", "bailian_direct");
        response.setMetadata(metadata);

        return response;
    }

    // =============== RAG 文档管理 ===============
    public void ingestDocument(String text, String source) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", source != null ? source : "manual_input");
        metadata.put("timestamp", System.currentTimeMillis());
        Document doc = new Document(text, metadata);
        vectorStore.add(List.of(doc));
        log.info("成功摄入文档: source={}, 文本长度={}", source, text.length());
    }

    public void ingestDocuments(List<Document> documents) {
        vectorStore.add(documents);
        log.info("批量摄入文档: {} 个", documents.size());
    }

    // =============== Tool-Enabled 对话（AI 可调用工具） ===============

    /**
     * 启用工具调用的 AI 对话模式。
     * AI 可以自主决定是否调用 webSearch、executeCommand、readFile、writeFile 等工具。
     */
    public String chatWithTools(String chatId, String userMessage) {
        ensureMemoryDir();
        String finalChatId = (chatId == null || chatId.isBlank()) ? "default_tools_session" : chatId;
        loadHistory(finalChatId);

        List<Message> history = conversationHistory.getOrDefault(finalChatId, new ArrayList<>());
        int start = Math.max(0, history.size() - maxHistorySize);
        List<Message> recentHistory = history.subList(start, history.size());

        List<Message> messages = new ArrayList<>(recentHistory);
        messages.add(new UserMessage(userMessage));

        String systemPrompt = getToolsSystemPrompt();

        try {
            String assistantMessage = chatClient.prompt()
                    .messages(messages)
                    .system(systemPrompt)
                    .tools(toolCallbacks)
                    .call()
                    .content();

            history.add(new UserMessage(userMessage));
            history.add(new AssistantMessage(assistantMessage));
            if (history.size() > maxHistorySize) {
                history.subList(0, history.size() - maxHistorySize).clear();
            }
            conversationHistory.put(finalChatId, history);
            saveHistory(finalChatId);

            return assistantMessage;
        } catch (Exception e) {
            log.error("工具对话失败: {}", e.getMessage(), e);
            return "抱歉，处理您的请求时出错了：" + e.getMessage();
        }
    }

    /**
     * 获取当前已注册的工具数量
     */
    public int getToolCount() {
        return toolCallbacks.length;
    }

    // =============== 流式对话（SSE） ===============

    /**
     * 普通对话流式版本，返回 token 流，结束后持久化对话历史
     */
    public Flux<String> chatStream(String chatId, String userMessage) {
        ensureMemoryDir();
        String finalChatId = (chatId == null || chatId.isBlank()) ? "default_global_session" : chatId;
        loadHistory(finalChatId);

        List<Message> history = conversationHistory.getOrDefault(finalChatId, new ArrayList<>());
        int start = Math.max(0, history.size() - maxHistorySize);
        List<Message> recentHistory = history.subList(start, history.size());

        List<Message> messages = new ArrayList<>(recentHistory);
        messages.add(new UserMessage(userMessage));

        StringBuilder fullText = new StringBuilder();
        return chatClient.prompt()
                .messages(messages)
                .system(getSystemPrompt())
                .stream()
                .content()
                .doOnNext(fullText::append)
                .doOnComplete(() -> {
                    history.add(new UserMessage(userMessage));
                    history.add(new AssistantMessage(fullText.toString()));
                    if (history.size() > maxHistorySize) {
                        history.subList(0, history.size() - maxHistorySize).clear();
                    }
                    conversationHistory.put(finalChatId, history);
                    saveHistory(finalChatId);
                    log.info("流式对话完成 chatId={}", finalChatId);
                })
                .doOnError(e -> log.error("流式对话异常 chatId={}", finalChatId, e));
    }

    /**
     * RAG 流式对话（带检索明细），结束后持久化对话历史
     */
    public RagStreamResponse chatWithRagStreamDetailed(String chatId, String userMessage) {
        return chatWithRagStreamDetailed(chatId, userMessage, defaultTopK);
    }

    public RagStreamResponse chatWithRagStreamDetailed(String chatId, String userMessage, int topK) {
        ensureMemoryDir();
        String finalChatId = (chatId == null || chatId.isBlank()) ? "default_global_session" : chatId;

        RetrievalOutcome retrieval = retrieve(userMessage, topK);
        List<Document> docs = retrieval.docs;

        loadHistory(finalChatId);
        List<Message> history = conversationHistory.getOrDefault(finalChatId, new ArrayList<>());
        int start = Math.max(0, history.size() - maxHistorySize);
        List<Message> recentHistory = history.subList(start, history.size());

        List<Message> messages = new ArrayList<>(recentHistory);
        String finalUserMessage;
        if (!docs.isEmpty()) {
            finalUserMessage = "请参考以下知识片段回答用户问题。\n" +
                    retrieval.context +
                    "用户问题：" + userMessage;
        } else {
            finalUserMessage = "（暂无相关参考知识，请基于通用恋爱心理学回答）\n用户问题：" + userMessage;
        }
        messages.add(new UserMessage(finalUserMessage));

        StringBuilder fullText = new StringBuilder();
        Flux<String> tokens = chatClient.prompt()
                .messages(messages)
                .system(getRagSystemPrompt())
                .stream()
                .content()
                .doOnNext(fullText::append)
                .doOnComplete(() -> {
                    history.add(new UserMessage(userMessage));
                    history.add(new AssistantMessage(fullText.toString()));
                    if (history.size() > maxHistorySize) {
                        history.subList(0, history.size() - maxHistorySize).clear();
                    }
                    conversationHistory.put(finalChatId, history);
                    saveHistory(finalChatId);
                    log.info("RAG 流式对话完成 chatId={}", finalChatId);
                })
                .doOnError(e -> log.error("RAG 流式对话异常 chatId={}", finalChatId, e));

        RagStreamResponse response = new RagStreamResponse();
        response.setQuery(userMessage);
        response.setSources(retrieval.sources);
        response.setRetrievedDocs(docs.size());
        response.setTokens(tokens);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("retrievalSource", retrieval.retrievalSource);
        response.setMetadata(metadata);
        return response;
    }

    /**
     * 工具调用对话流式版本，AI 可自主调用工具，结束后持久化对话历史
     */
    public Flux<String> chatWithToolsStream(String chatId, String userMessage) {
        ensureMemoryDir();
        String finalChatId = (chatId == null || chatId.isBlank()) ? "default_tools_session" : chatId;
        loadHistory(finalChatId);

        List<Message> history = conversationHistory.getOrDefault(finalChatId, new ArrayList<>());
        int start = Math.max(0, history.size() - maxHistorySize);
        List<Message> recentHistory = history.subList(start, history.size());

        List<Message> messages = new ArrayList<>(recentHistory);
        messages.add(new UserMessage(userMessage));

        StringBuilder fullText = new StringBuilder();
        return chatClient.prompt()
                .messages(messages)
                .system(getToolsSystemPrompt())
                .tools(toolCallbacks)
                .stream()
                .content()
                .doOnNext(fullText::append)
                .doOnComplete(() -> {
                    history.add(new UserMessage(userMessage));
                    history.add(new AssistantMessage(fullText.toString()));
                    if (history.size() > maxHistorySize) {
                        history.subList(0, history.size() - maxHistorySize).clear();
                    }
                    conversationHistory.put(finalChatId, history);
                    saveHistory(finalChatId);
                    log.info("工具流式对话完成 chatId={}", finalChatId);
                })
                .doOnError(e -> log.error("工具流式对话异常 chatId={}", finalChatId, e));
    }

    /**
     * 百炼流式对话。百炼应用接口无原生流式能力，同步获取完整答案后以单个 token 事件下发。
     */
    public RagStreamResponse chatWithBailianStream(String chatId, String userMessage) {
        return chatWithBailianStream(chatId, userMessage, defaultTopK);
    }

    public RagStreamResponse chatWithBailianStream(String chatId, String userMessage, int topK) {
        ensureMemoryDir();
        String finalChatId = (chatId == null || chatId.isBlank()) ? "default_global_session" : chatId;

        if (bailianService == null || !bailianService.isAvailable()) {
            log.warn("百炼服务不可用，回退到本地 RAG 流式");
            return chatWithRagStreamDetailed(chatId, userMessage, topK);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("retrievalSource", "bailian_direct");

        RagStreamResponse response = new RagStreamResponse();
        response.setQuery(userMessage);
        response.setMetadata(metadata);

        loadHistory(finalChatId);
        List<Message> history = conversationHistory.getOrDefault(finalChatId, new ArrayList<>());

        Flux<String> tokens = Flux.defer(() -> {
            BailianKnowledgeService.BailianAnswerResult result = bailianService.answer(userMessage, topK);
            String answer = result.getAnswer();
            if (answer == null || answer.isBlank()) {
                log.warn("百炼未返回答案，回退到本地 RAG 流式");
                return chatWithRagStreamDetailed(chatId, userMessage, topK).getTokens();
            }
            response.setSources(result.getSources());
            response.setRetrievedDocs(result.getDocuments().size());

            history.add(new UserMessage(userMessage));
            history.add(new AssistantMessage(answer));
            if (history.size() > maxHistorySize) {
                history.subList(0, history.size() - maxHistorySize).clear();
            }
            conversationHistory.put(finalChatId, history);
            saveHistory(finalChatId);
            return Flux.just(answer);
        }).subscribeOn(Schedulers.boundedElastic());

        response.setTokens(tokens);
        return response;
    }

    // =============== 对话管理 ===============
    public void clearHistory(String chatId) {
        String finalChatId = (chatId == null || chatId.isBlank()) ? "default_global_session" : chatId;
        conversationHistory.remove(finalChatId);
        File file = new File(memoryDir + finalChatId + ".dat");
        if (file.exists()) {
            file.delete();
        }
        log.info("已清除对话历史: {}", finalChatId);
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("activeSessions", conversationHistory.size());
        status.put("memoryDir", memoryDir);

        File dir = new File(memoryDir);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".dat"));
            status.put("persistedSessions", files != null ? files.length : 0);
        } else {
            status.put("persistedSessions", 0);
        }

        status.put("ragAvailable", true);
        status.put("embeddingModel", vectorStore != null ? vectorStore.getClass().getSimpleName() : "unknown");
        return status;
    }
}
