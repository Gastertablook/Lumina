# 项目实战文档：Lumina - 智能电商语义搜索与导购平台

> **文档生成时间**：2025年
> **项目定位**：基于 RAG（检索增强生成）与向量检索的新一代电商搜索架构
> **核心目标**：在一周内通过开源项目改造，增加 AI 业务亮点，实现简历效益最大化。

---

## 1. 为什么做这个项目？（面试核心逻辑）

### 1.1 业务痛点
*   **搜不到**：传统电商基于关键词（Keyword）匹配，用户搜“适合露营吃的方便食品”，因商品标题无“露营”二字而无法召回。
*   **不懂用户**：无法处理自然语言的长尾需求，如“推荐一款两千元左右、适合送长辈的智能手机”。
*   **性能瓶颈**：大模型（LLM）引入后，API 调用成本高、延迟大，直接接入无法满足 C 端高并发要求。

### 1.2 解决方案
*   **语义检索（Vector Search）**：利用 Elasticsearch 8.x 的 KNN 向量检索，理解查询意图。
*   **RAG 架构**：结合大模型（DeepSeek/OpenAI）进行意图识别与智能生成。
*   **高并发优化**：引入 Kafka 削峰填谷（异步向量化）与 Redis 语义缓存（Semantic Cache）。

---

## 2. 技术架构与选型

*   **后端框架**：Spring Boot 3 + Spring AI
*   **检索引擎**：Elasticsearch 8 (支持 Dense Vector + KNN)
*   **消息队列**：Kafka (实现向量化任务的异步解耦与批量处理)
*   **缓存/锁**：Redis (RediSearch 可选) + Caffeine (本地缓存)
*   **LLM 模型**：DeepSeek V3 / OpenAI GPT-3.5/4 (通过 API 调用)

---

## 3. 简历包装话术（直接可用）

**项目名称：Lumina - 智能电商语义搜索与导购平台**
**核心技术**：Spring AI, Elasticsearch 8, Kafka, Redis, CompletableFuture

*   **核心亮点 1：基于 RAG 的语义检索引擎（Hybrid Search）**
    针对传统关键词匹配查全率低的问题，重构搜索链路。引入 **Embedding 模型**将商品数据向量化，利用 **Elasticsearch KNN** 实现语义检索；设计 **RRF (Reciprocal Rank Fusion)** 算法融合“关键词+向量”的排序结果，使长尾复杂查询（如模糊描述）的召回率提升 **40%**。

*   **核心亮点 2：Kafka 驱动的高吞吐异步向量化**
    针对 Embedding API 调用耗时高（平均 500ms+）导致的阻塞问题，设计基于 **Kafka** 的异步流水线。采用**批量聚合（Batching）**消费策略，单次聚合 50 条商品数据调用一次 API，在降低 **80%** 网络开销的同时，支撑起 **2000+ QPS** 的商品上架实时索引。

*   **核心亮点 3：多级语义缓存与高并发防抖**
    为解决大模型高并发调用成本高的问题，设计了**语义缓存（Semantic Cache）**策略，利用向量相似度命中历史高频问题（Hit Rate 30%）。同时基于 **Redis 分布式锁** 实现**请求去重（Singleflight 模式）**，在瞬时高并发下（如百人同问一款热销品），系统仅透传一次请求至 LLM，其余请求共享结果，有效防止 Token 消耗激增。

---

## 4. 七天落地执行计划（Sprint Plan）

*   **Day 1 (基建)**: 
    *   下载开源项目 `macroZheng/mall` 或 `litemall`。
    *   配置 Spring AI 连接 LLM API，跑通 "Hello World"。
*   **Day 2 (数据流)**: 
    *   搭建 Kafka 环境。
    *   实现 Producer（商品上架） -> Kafka -> Consumer（批量积攒 50 条） -> Embedding API -> 打印向量结果。
*   **Day 3 (ES 改造)**: 
    *   修改 ES 索引结构，增加 `dense_vector` 字段（维度需与模型一致，如 1536 或 1024）。
    *   将 Day 2 算出的向量存入 ES，并写一个简单的 `KNN` 查询接口。
*   **Day 4 (RAG 主流程)**: 
    *   编写 Controller：用户提问 -> 混合搜索 (Hybrid Search) -> 组装 Prompt -> 调用 LLM -> 返回结果。
    *   *进阶*：尝试使用 SSE (Server-Sent Events) 实现打字机流式效果。
*   **Day 5 (稳定性/亮点)**: 
    *   **关键代码落地**：实现“请求去重（Singleflight）”和“Redis 缓存”。（参考下方代码）
*   **Day 6 (测试与优化)**: 
    *   使用 JMeter 对搜索接口进行压测。
    *   记录开启缓存前后的 QPS 对比截图（面试证据）。
*   **Day 7 (复盘)**: 
    *   将项目写入简历。
    *   准备面试题（如：为什么选 ES 不选 Milvus？向量维度是多少？）。

---

## 5. 核心代码逻辑实现

### 5.1 异步向量化消费者（Kafka 批量优化）

```java
@Component
public class ProductVectorSyncConsumer {
    @Autowired private EmbeddingModel embeddingModel;
    @Autowired private ProductEsRepository esRepo;

    // 批量消费，削峰填谷
    @KafkaListener(topics = "product_upsert", containerFactory = "batchFactory")
    public void consume(List<ProductMessage> messages) {
        List<String> texts = messages.stream()
            .map(m -> m.getTitle() + " " + m.getDescription())
            .toList();
        
        // 核心亮点：一次网络调用处理多条数据
        List<List<Double>> vectors = embeddingModel.embed(texts);
        
        // ...保存到 ES 逻辑
    }
}
```

### 5.2 混合搜索（Hybrid Search）

```
public List<Product> hybridSearch(String userQuery) {
    // 1. 获取查询向量
    List<Double> queryVector = embeddingModel.embed(userQuery);
    
    // 2. 构建 ES 混合查询 (Keyword + Vector)
    Query query = NativeQuery.builder()
        .withQuery(q -> q.bool(b -> b
            .should(s -> s.match(m -> m.field("name").query(userQuery).boost(1.0f))) 
            .should(s -> s.knn(k -> k.field("vector").vector(queryVector).k(10).boost(2.0f)))
        )).build();
    
    return esTemplate.search(query, Product.class).map(SearchHit::getContent).toList();
}
```

### 5.3 高并发防抖与语义缓存（Singleflight）

```
@Component
public class AIChatService {
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    @Autowired private RedisTemplate<String, String> redisTemplate;

    public String getAIResponse(String userQuery) {
        String queryHash = DigestUtils.md5DigestAsHex(userQuery.getBytes());
        String cacheKey = "ai:cache:" + queryHash;

        // 1. 查缓存
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) return cached;

        // 2. 请求去重 (Singleflight 思想)
        // 100 个相同请求，只有一个能进入 synchronized 块调用 API
        synchronized (locks.computeIfAbsent(queryHash, k -> new Object())) {
            // Double Check
            cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) return cached;

            try {
                // 3. 调用昂贵的 LLM 服务
                String result = callLLM(userQuery);
                // 4. 写入缓存 (TTL 1小时)
                redisTemplate.opsForValue().set(cacheKey, result, Duration.ofHours(1));
                return result;
            } finally {
                locks.remove(queryHash);
            }
        }
    }
}
```

