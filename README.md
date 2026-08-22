# Yang AI Agent — RAG 检索增强 · 工具调用 · 多轮推理智能体

一个基于 **Spring AI 2.0** 的智能体系统，以「恋爱情感咨询助手」为业务落地场景，完整实现 **RAG 检索增强问答、自主工具调用、ReAct 多步智能体、多轮对话记忆持久化**，前端 SSE 流式逐字输出。

```
┌────────────┐   SSE 流式   ┌────────────────────────────────────────────────┐
│  前端 Vue3  │  ──────────► │  Spring Boot 4.1 + Spring AI 2.0 (端口 8123)    │
│  /chat      │  meta/token │                                                │
│  /report    │  done/error  │  AiController(/api/ai/**)   LoveAppController  │
│  /knowledge │             │        │                                        │
│  /status    │             │        ├─ RAG：文档加载 → 切分 → 向量化 → 检索 → 问答 │
└────────────┘             │        │    ├ pgvector(hnsw,1536维)              │
                           │        │    └ 阿里云百炼知识库(双通道+降级回退)      │
                           │        ├─ 工具：webSearch/scrape/terminal/PDF...  │
                           │        ├─ 智能体 YangManus：think → act 循环       │
                           │        └─ 记忆：Kryo 序列化持久化 chat_memories/   │
                           └────────────────────────────────────────────────┘
```

## 功能特性

| 能力 | 说明 |
|---|---|
| **RAG 检索增强问答** | 恋爱知识文档（话术策略 / 心理学基础 / 挽回红线）加载 → 切分 → 向量化 → 检索 → 引用片段作答 |
| **双通道检索 + 降级** | 阿里云**百炼知识库**优先，不可用时自动回退本地 pgvector 向量库，一路降级到底不崩 |
| **自主工具调用** | DeepSeek function calling，7 个工具：网页搜索、网页抓取、终端命令、读写文件、下载、生成 PDF、终止 |
| **ReAct 多步智能体** | `YangManus`：think（解析 tool_calls）→ act（执行工具）循环，最多 4 步，过程实时流式展示 |
| **多轮记忆持久化** | 每个会话 Kryo 序列化到 `chat_memories/<chatId>.dat`，跨请求延续上下文，重启不丢 |
| **SSE 流式输出** | 事件流 `meta / token / done / error`，智能体模式额外带 `step / think / tool_call / tool_result` |
| **结构化报告** | 输入感情状况生成结构化恋爱报告（核心症结 / 建议 / 风险提醒） |
| **优雅降级** | PG 不可用→内存向量库；无 embedding key→零向量；百炼不可用→本地 RAG；后端不可用前端照常启动 |

## 技术栈

- **框架**：Spring Boot **4.1** · Java 17 · Spring AI **2.0**
- **LLM**：DeepSeek（`deepseek-chat`）· 阿里云 DashScope embedding（`text-embedding-v4`，1536 维）
- **向量库**：PostgreSQL + **pgvector**（hnsw 索引）
- **工具/记忆**：Spring AI `@Tool` 注解 · Kryo 序列化 · openpdf 生成 PDF · jsoup 网页解析
- **API 文档**：Knife4j（OpenAPI3）
- **前端**：Vue 3.4 · Vite 5 · vue-router 4（手写 CSS，SSE 逐字渲染）

## 核心接口（SSE 流式，`/api/ai/**`）

| 接口 | 说明 |
|---|---|
| `POST /ai/chat` | 普通多轮对话 |
| `POST /ai/chat-report` | 结构化恋爱报告（JSON） |
| `POST /ai/rag/chat` | RAG 检索增强对话（meta 带检索来源/片段） |
| `POST /ai/bailian/chat` | 百炼知识库对话（不可用自动回退本地 RAG） |
| `POST /ai/rag/ingest` · `ingest-batch` | 知识文档摄入 |
| `POST /ai/tools/chat` | 工具调用对话（模型自主决定调用工具） |
| `POST /ai/agent/run` | 超级智能体 YangManus（think→act 循环） |
| `DELETE /ai/history/{chatId}` | 清除对话历史（内存 + 磁盘文件） |
| `GET /ai/status` | 系统状态（向量库/记忆/RAG 可用性） |

## 智能体框架（`agent/`）

```
BaseAgent.run() 主循环 → ReActAgent.step() → ToolCallAgent.think() 调 DeepSeek 解析 tool_calls
                                        → act() 逐个执行工具并回填 ToolResponseMessage
AgentService 通过 AgentListener 把每一步转成 SSE 事件，前端实时展示"思考→调用→结果"
```

修复的 DeepSeek 工程坑：连续两条用户消息会抑制工具调用（需合并）、工具定义需经 `DeepSeekChatOptions` 传播等（详见测试）。

## 目录结构

```
├── src/main/java/com/cg/yangaiagent/
│   ├── controller/AiController.java     # SSE 流式统一入口
│   ├── app/LoveApp.java                 # 核心业务（对话/RAG/工具/记忆）
│   ├── agent/                           # 智能体框架 BaseAgent/ReActAgent/ToolCallAgent/YangManus
│   ├── rag/                             # RAG 全套（加载/向量化/向量库/百炼双通道）
│   ├── tools/                           # 7 个 Spring AI @Tool
│   ├── chatmemory/ · advisor/ · model/
│   └── config/                          # CORS / 工具注册
├── frontend/                            # Vue3 前端（对话/报告/知识库/状态页）
├── chat_memories/ · pdfs/ · downloads/  # 运行时数据（记忆/生成PDF/下载）
├── Dockerfile · docker-compose.yml
└── pom.xml
```

## 快速开始

**本地开发**（需 DeepSeek、DashScope、Tavily API Key 和 PostgreSQL+pgvector）：

```bash
# 后端（端口 8123）
mvn spring-boot:run

# 前端（端口 5173，/api 代理到 8123）
cd frontend && npm install && npm run dev
```

**Docker 部署**（backend + frontend 两个服务）：

```bash
docker compose up -d --build
# 前端 http://localhost:8080 · 后端状态 http://localhost:8123/api/ai/status
```

## 配置（环境变量注入，勿提交密钥）

| 配置项 | 说明 |
|---|---|
| `spring.ai.deepseek.api-key` | DeepSeek API Key（`deepseek-chat`） |
| `dashscope.api-key` | DashScope（embedding `text-embedding-v4` + 百炼） |
| `spring.ai.vectorstore.search.api.key` | Tavily 网页搜索 |
| `spring.datasource.*` | PostgreSQL + pgvector 连接 |
| `spring.ai.vectorstore.pgvector.*` | 向量库（维度 1536 / hnsw） |
| `loveapp.*` | 记忆目录 / 历史条数 / 系统提示词 / RAG 阈值 |

密钥一律通过 `application-local.yml`（已 gitignore）或环境变量提供，**严禁提交到仓库**。

## 关于 MCP

项目命名中提及 MCP，当前代码里的定位是**设计探索方向**：`spring-ai-mcp-client` 依赖因与 Spring AI 2.0 版本不兼容暂被注释，`mcp-servers.json` 为空。实际工具调用能力由 **Spring AI 原生 `@Tool` 注解 + DeepSeek function calling** 实现；MCP 相关代码沉淀在独立的 `yang-mcp` 项目。

## 合规声明

- 本项目为学习型 AI 应用，对话内容由大模型生成，仅供娱乐与参考，不构成专业建议
- 工具调用默认仅限本机沙箱目录（`temp/` 下读写、命令 15s 超时），生产环境需自行收紧权限白名单
