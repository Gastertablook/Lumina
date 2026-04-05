# 🚀 Lumina AI - 智能电商语义搜索与 Agentic RAG 导购平台

![Java](https://img.shields.io/badge/Java-17-blue.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.5-brightgreen.svg)
![LangChain4j](https://img.shields.io/badge/LangChain4j-0.31.0-orange.svg)
![Elasticsearch](https://img.shields.io/badge/Elasticsearch-8.11.1-blue.svg)
![Kafka](https://img.shields.io/badge/Kafka-3.x-black.svg)
![Redis](https://img.shields.io/badge/Redis-6.x-red.svg)

Lumina AI 是一个基于开源项目 `macrozheng/mall` 深度二次开发的**新一代电商智能搜索与导购架构**。
本项目摒弃了传统死板的“Keyword”文本匹配引擎，通过引入 **Agentic RAG（智能体化检索增强生成）** 理念，结合大语言模型（LLM）与稠密向量检索（Dense Vector Search），为 C 端用户提供极速、精准、具有共情能力的智能导购体验。

## 🎯 业务痛点与解决方案

在将大模型落地于真实电商场景时，我们重点攻克了以下四大业界级痛点：

1. **响应延迟过高 (Latency)**：传统 RAG 动辄 5-10 秒响应。本项目通过 `SSE 打字机流式输出` 与 `L1/L2 多级语义缓存`，将首字响应时间 (TTFT) 从 7000ms 压降至 **50ms** 以内。
2. **高并发击穿 (Cache Breakdown)**：面对大促爆款查询，大模型 API 极易触发限流或高昂计费。本项目引入 `Singleflight (单飞)` 模式，实现同语义请求并发合并，10万并发仅消耗 1 次 API 额度。
3. **“凑数”逻辑幻觉 (Hallucination)**：解决由于小模型向量泛化导致的跨品类混淆（如搜“手机”却推荐“电视”）。采用 `词法(IK)+语义(Vector) 双重锁混合检索` 与 `CoT (思维链) 提示词护城河`，实现 100% 幻觉阻断。
4. **缓存一致性灾难 (Cache Invalidation)**：商品变价/下架导致 AI 推荐落后。本项目采用基于 `Kafka EDA (事件驱动)` 的血缘主动清理机制，彻底解决 RAG 系统的脏缓存问题。

---

## 🏗️ 核心架构演进设计 (Architecture)

### 1. Dual-LLM Synergy (双层大模型协同路由)
摒弃“一波流”的粗糙 RAG，采用智能体思想：
*   **Pass 1 (高速前额叶)**：使用快速 LLM 异步进行 NLU 意图提取，剥离口语噪音，强制输出结构化 JSON 并完成底层数据库 ID 映射。
*   **Pass 2 (共情沟通者)**：结合底层引擎召回的精确数据，使用强大的 LLM 进行场景化包装，通过 SSE 流式推给前端。

### 2. Multi-Level Semantic Cache (多级语义缓存体系)
*   **L1 防线 (Redis 精确缓存)**：基于用户 Query 的 MD5 摘要，拦截完全重复的死水请求 (耗时 7ms)。
*   **L2 防线 (ES 混合语义缓存)**：提取新问题为 384 维稠密向量。利用 Elasticsearch 的 `Operator.AND` 词法强校验 + `HNSW` 向量余弦相似度（得分 > 0.85）进行模糊泛化拦截 (耗时 50ms)。

### 3. Singleflight Concurrency Protection (并发防击穿)
放弃低效的阻塞锁，基于 `ConcurrentHashMap` 与 `CompletableFuture` 实现极其优雅的事件驱动挂起模型。跟随者线程原地搭便车共享先锋线程的 LLM 结果，最大限度榨干 CPU 性能。

### 4. Event-Driven Cache Invalidation (基于血缘的缓存爆破)
大模型生成导购话术时，将参考的 `refProductIds`（商品依赖池）同时存入 L2 缓存。
当 CMS 后台触发商品变价时，投递 Kafka 消息 -> 监听器逆向揪出受污染的缓存记录 -> 瞬间清理对应的 Redis L1 与 ES L2 缓存，实现数据强一致性。

---

## 🛠️ 核心技术栈

*   **底层引擎**：Java 17, Spring Boot 2.7.5
*   **AI 代理网关**：LangChain4j 0.31.0
*   **大语言模型**：智谱 GLM-4-Flash (可无缝切换 OpenAI / DeepSeek / 通义千问)
*   **Embedding 模型**：本地离线模型 `all-minilm-l6-v2` (毫秒级免费向量化)
*   **中间件**：Elasticsearch 8.11.1 (带 IK 分词器), Kafka 3.x, Redis 6.x

---

## 🚀 快速启动 (Quick Start)

### 1. 环境准备
确保本机或 Docker 已安装并运行以下组件：
- MySQL 5.7+
- Redis
- Elasticsearch 8.11.1 (安装 `elasticsearch-analysis-ik` 插件)
- Kafka & Zookeeper / KRaft

### 2. 配置文件修改
修改 `application.yml` 中的环境依赖配置，并填入大模型 API-KEY：
```yaml
ai:
  llm:
    api-key: "你的 API_KEY"
    base-url: "https://open.bigmodel.cn/api/paas/v4/"
    model-name: "glm-4-flash"
```

### 3. Elasticsearch 初始化
通过 Kibana Dev Tools 建立语义缓存护城河索引：
```json
PUT /ai_semantic_cache
{
  "mappings": {
    "properties": {
      "queryText": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "queryVector": { "type": "dense_vector", "dims": 384, "index": true, "similarity": "cosine" },
      "llmResponse": { "type": "text", "index": false },
      "refProductIds": { "type": "long" },
      "createTime": { "type": "long" }
    }
  }
}
```

### 4. 接口体验
启动项目后，访问 Swagger UI：`http://localhost:8081/swagger-ui/index.html`
*   **智能导购测试**: `GET /ai/guide/stream` (推荐在浏览器中直接访问以体验 SSE 打字机流式效果)
*   **缓存血缘清理测试**: `POST /mockUpdatePrice` (模拟降价，触发 Kafka 清理缓存)

---

## 💡 未来展望 (TODO)
本项目架构已预留极强的通用扩展性。未来计划：
- [ ] **泛化业务抽象**：将电商导购 RAG 逻辑抽取为 `spring-boot-starter-agentic-rag` 通用组件，开发者只需实现 `DataProvider` 接口即可为任意业务赋能多级缓存与防击穿能力。
- [ ] **异步增量索引**：完善商品上架的 Kafka 消费者，实现亿级数据的异步平滑向量化与重排。

---

*Lumina AI - 让每一次搜索都拥有温度。*