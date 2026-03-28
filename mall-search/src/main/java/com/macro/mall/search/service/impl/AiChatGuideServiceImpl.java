package com.macro.mall.search.service.impl;

import com.macro.mall.search.domain.EsProduct;
import com.macro.mall.search.domain.SemanticCache;
import com.macro.mall.search.service.AiChatGuideService;
import com.macro.mall.search.service.EsProductService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.mapper.PmsBrandMapper;
import com.macro.mall.model.PmsBrand;
import com.macro.mall.model.PmsBrandExample;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.message.AiMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.util.DigestUtils;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.index.query.QueryBuilders;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class AiChatGuideServiceImpl implements AiChatGuideService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiChatGuideServiceImpl.class);

    @Autowired
    private EsProductService esProductService; // 注入商品搜索服务

    @Autowired
    private PmsBrandMapper pmsBrandMapper; // 注入品牌Mapper

    @Value("${ai.llm.api-key}")
    private String apiKey;

    @Value("${ai.llm.base-url}")
    private String baseUrl;

    @Value("${ai.llm.model-name}")
    private String modelName;

    private ChatLanguageModel chatLanguageModel;

    private StreamingChatLanguageModel streamingChatModel;

    // 注入 ObjectMapper 用于解析 JSON
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ElasticsearchRestTemplate elasticsearchRestTemplate; // 用于操作 ES 缓存库

    @Autowired
    private EmbeddingModel embeddingModel; // 注入本地的 all-minilm-l6-v2 模型


    // 【架构核心组件】：Singleflight 正在飞行（处理中）的任务登记簿
    // Key: 查询的 MD5 Hash, Value: 存放完整回复的“未来承诺”
    private final ConcurrentHashMap<String, CompletableFuture<String>> inFlightRequests = new ConcurrentHashMap<>();

    // 内部类用于承载意图
    private static class IntentMapping {
        String keyword;
        String brandName;
    }

    @PostConstruct
    public void init() {
        // 初始化大语言模型客户端
        LOGGER.info("正在初始化 LangChain4j OpenAiChatModel, base-url: {}", baseUrl);
        this.chatLanguageModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(60)) // 给大模型多一点思考时间
                .temperature(0.7) // 增加适当的发散性，适合导购口吻
                .build();

        this.streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(60))
                .temperature(0.7)
                .build();
    }

    @Override
    public String generateShoppingGuide(String userQuery) {
        // 第一步：Retrieve (检索) 
        // 调用混合搜索接口
        // 参数说明：keyword=用户提问, brandId=null, productCategoryId=null, pageNum=0 (第一页), pageSize=5 (取Top5)
        Page<EsProduct> productPage = esProductService.hybridSearch(userQuery, null, null, 0, 5);
        
        List<EsProduct> relevantProducts = productPage.getContent();

        // 兜底校验
        if (relevantProducts == null || relevantProducts.isEmpty()) {
            return "抱歉，目前商城里没有找到符合您描述的商品。您可以换个说法试试，例如品牌或具体功能？";
        }

        // 第二步：Augment (增强) -> 构建上下文
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < relevantProducts.size(); i++) {
            EsProduct p = relevantProducts.get(i);
            contextBuilder.append(String.format("%d. 商品名称: %s | 价格: ￥%.2f | 核心卖点: %s\n",
                    i + 1, p.getName(), p.getPrice(), p.getSubTitle()));
        }
        String productContext = contextBuilder.toString();
        LOGGER.info("====== RAG 组装的上下文 ======\n{}", productContext);

        // 第三步：Prompt Engineering (提示词工程)
        String prompt = buildPrompt(userQuery, productContext);

        // 第四步：Generate (生成) -> 同步调用 LLM 获取结果
        LOGGER.info("====== 正在请求 DeepSeek 大模型生成导购建议... ======");
        long startTime = System.currentTimeMillis();
        String response = chatLanguageModel.generate(prompt);
        LOGGER.info("====== 大模型回复完成，耗时: {} ms ======", (System.currentTimeMillis() - startTime));
        
        return response;
    }

    @Override
    public SseEmitter generateStreamShoppingGuide(String userQuery) {
        // 设置 SSE 超时时间，通常大模型生成不会超过 2 分钟 (120000毫秒)
        SseEmitter emitter = new SseEmitter(120000L);

        // 使用 MD5 对用户的查询词进行摘要，确保 Key 长度固定且唯一
        String queryHash = DigestUtils.md5DigestAsHex(userQuery.getBytes());
        String redisCacheKey = "lumina_v5:ai:cache:" + queryHash;

        // ================== 【L1 防线：Redis 精确缓】 ==================
        String cachedResponse = null;
        try {
            cachedResponse = redisTemplate.opsForValue().get(redisCacheKey);
        } catch (Exception e) {
            LOGGER.error("Redis 缓存读取异常，降级穿透到大模型", e);
        }

        if (cachedResponse != null) {
            LOGGER.info("====== 命中 Redis 语义缓存！极速响应！ ======");
            sendSingleChunkAndComplete(emitter, cachedResponse);
            // 命中缓存直接 return，下面的逻辑全都不走！
            return emitter;
        }

        // ================== 【并发防线：Singleflight 拦截】 ==================
        // 1. 创建一个代表“未来结果”的空承诺
        CompletableFuture<String> newPromise = new CompletableFuture<>();
        // 2. 尝试把这个承诺登记到并发字典里（putIfAbsent 是原子操作，绝对线程安全）
        CompletableFuture<String> existingPromise = inFlightRequests.putIfAbsent(redisCacheKey, newPromise);

        if (existingPromise != null) {
            // 发现字典里已经有别人的承诺了，说明此时此刻已经有别的线程在请求大模型了！
            LOGGER.warn("====== 触发 Singleflight 防击穿！当前请求原地等待先锋请求的结果... ======");
            
            // 为了不阻塞当前 Tomcat 主线程返回 SseEmitter，我们开一个异步线程去“等”
            CompletableFuture.runAsync(() -> {
                try {
                    // 🌟 这里的 .get() 会优雅地阻塞，直到先锋把结果装进去！
                    String sharedResult = existingPromise.get(); 
                    LOGGER.info("====== 拿到先锋请求共享的结果，瞬间推给前端！ ======");
                    sendSingleChunkAndComplete(emitter, sharedResult);
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            });
            // 瞬间返回 emitter，让前端保持连接并等待
            return emitter;
        }

        LOGGER.info("====== L1 缓存未命中，当前为先锋请求，准备进入 L2 语义防线 ======");

        // ================== 【L2 防线：ES 混合语义缓存 (双重锁)】 ==================
        // 1. 将用户的新问题瞬间向量化 (耗时约 10-20ms)
        float[] queryVectorFloat = embeddingModel.embed(userQuery).content().vector();
        List<Double> queryVector = new ArrayList<>();
        for (float f : queryVectorFloat) {
            queryVector.add((double) f);
        }

        // 2. 构造 ES 余弦相似度查询
        Map<String, Object> params = new HashMap<>();
        params.put("query_vector", queryVector);
        // ES 中的 cosineSimilarity 范围是[-1, 1]，加 1 后范围是 [0, 2]
        // 阈值 0.85 对应的 script score 是 1.85
        Script script = new Script(ScriptType.INLINE, "painless",
            "cosineSimilarity(params.query_vector, 'queryVector') + 1.0", params);

        // 【核心改造】：引入 BoolQuery，将文本匹配与向量打分结合
        // 1. 词法防线：利用 IK 分词器，要求新老问题Operator.AND（必须 100% 包含本次用户提问提取出的所有实词）
        org.elasticsearch.index.query.MatchQueryBuilder textMatch = 
                QueryBuilders.matchQuery("queryText", userQuery)
                             .operator(org.elasticsearch.index.query.Operator.AND); // 开启全词汇强制锁
                
        // 2. 语义防线：计算向量得分
        org.elasticsearch.index.query.QueryBuilder vectorScore =
                QueryBuilders.scriptScoreQuery(QueryBuilders.matchAllQuery(), script);

        org.elasticsearch.index.query.BoolQueryBuilder dualLockQuery = QueryBuilders.boolQuery()
                .must(textMatch)    // 必须满足词汇重合 (拦截"电视"和"手机"的混淆)
                .must(vectorScore); // 必须进行向量算分
            
        NativeSearchQuery semanticSearchQuery = new NativeSearchQueryBuilder()
                .withQuery(dualLockQuery)
                .withMinScore(1.85f) // 阈值可以保持 1.85f（即相似度0.85），因为有上面的词法锁兜底了！
                .withMaxResults(1) // 只需要最相似的一条
                .build();

        SearchHits<SemanticCache> cacheHits = elasticsearchRestTemplate.search(semanticSearchQuery, SemanticCache.class);
        
        if (cacheHits.getTotalHits() > 0) {
            SemanticCache topCache = cacheHits.getSearchHit(0).getContent();
            LOGGER.info("====== 🧠 命中 L2 ES 语义缓存！相似度极高，拦截成功！历史提问: [{}] ======", topCache.getQueryText());
            
            String llmResponse = topCache.getLlmResponse();
            
            // 命中 L2 后，不仅要返回给前端，还要把这个新问题“回写”到 L1 Redis 中，方便下次 7ms 极速命中！
            redisTemplate.opsForValue().set(redisCacheKey, llmResponse, 24, TimeUnit.HOURS);
            newPromise.complete(llmResponse); // 唤醒可能的并发跟随者
            inFlightRequests.remove(redisCacheKey);
            
            sendSingleChunkAndComplete(emitter, llmResponse);
            return emitter;
        }

        // ================== 【防线全面击穿：走真实 LLM 大闭环】 ==================
        LOGGER.info("====== L2 缓存未命中，启动真实 LLM 意图提取与混合检索... ======");

        // 第一步：意图提取 (Query Rewriting) -> 将罗嗦的话变成精准搜索词
        // 1. 调用新版意图提取
        IntentMapping intent = extractIntent(userQuery);
    
        // 2. 将提取到的品牌名称转化为 ID (ID 映射)
        Long targetBrandId = null;

        if (intent.brandName != null && !intent.brandName.isEmpty()) {
            // 去 MySQL 中模糊查询品牌名称对应的 ID
            PmsBrandExample brandExample = new PmsBrandExample();
            brandExample.createCriteria().andNameLike("%" + intent.brandName + "%");
            List<PmsBrand> brands = pmsBrandMapper.selectByExample(brandExample);
            if (brands != null && !brands.isEmpty()) {
                targetBrandId = brands.get(0).getId();
                LOGGER.info("品牌匹配成功: {} -> ID: {}", intent.brandName, targetBrandId);
            } else {
                LOGGER.warn("未找到匹配的品牌: {}", intent.brandName);
            }
        }

        // 第二步：复用混合搜索逻辑
        // 1. 第一次尝试：完美的精准混合搜索（强约束）
        LOGGER.info("====== 发起第一次精确混合检索 (带ID过滤) ======");
        Page<EsProduct> productPage = esProductService.hybridSearch(
            intent.keyword, 
            targetBrandId,   // 动态传入真实匹配的 ID
            null, // 针对分类树庞大且极其复杂的特点,不让大模型凭借一句话去猜中分类的名称，减轻了匹配出错的概率
            0, 5);
        List<EsProduct> relevantProducts = productPage.getContent();

        // 2. 降级兜底策略
        // 如果发现带了 ID 过滤后啥也没查到（过度约束发生），立刻抛弃 ID，只用文本/向量做兜底搜索
        if ((relevantProducts == null || relevantProducts.isEmpty()) 
            && (targetBrandId != null)) {
        
        LOGGER.warn("====== 精确过滤未命中任何商品！触发【优雅降级】机制，退回纯语义混合搜索 ======");
        
        // 把 brandId 和 categoryId 置为 null，重新查一次
        productPage = esProductService.hybridSearch(intent.keyword, null, null, 0, 5);
        relevantProducts = productPage.getContent();
        }

        // 3. 最终的彻底兜底：如果连纯语义都没查到，那说明库里真没有
        if (relevantProducts == null || relevantProducts.isEmpty()) {
            try {
                // 如果没有数据，直接发送兜底话术并完成
                emitter.send("抱歉，目前商城里没有找到符合您描述的商品。您可以换个说法试试？");
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < relevantProducts.size(); i++) {
            EsProduct p = relevantProducts.get(i);
            contextBuilder.append(String.format("%d. 商品名称: %s | 价格: ￥%.2f | 核心卖点: %s\n",
                    i + 1, p.getName(), p.getPrice(), p.getSubTitle()));
        }
        String productContext = contextBuilder.toString();
        
        // 第三步：构建给前端对话的 Prompt (依然传入用户的原始问题 userQuery 保证态度亲切)
        String prompt = buildPrompt(userQuery, productContext);

        // 【关键修复】：为了让匿名内部类能够使用，我们创建一个 final 的副本
        final List<EsProduct> finalRelevantProducts = relevantProducts;

        // 核心逻辑三：准备一个 StringBuffer 充当“收集篮”，收集大模型吐出的所有碎片
        StringBuffer responseCollector = new StringBuffer();

        LOGGER.info("====== 正在请求大模型流式生成导购建议... ======");

        // 第四步：触发大模型流式生成 (异步回调)
        streamingChatModel.generate(prompt, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                try {
                    // 每当大模型吐出一个字或词，立刻通过 SSE 推给前端
                    emitter.send(token); // 实时推给前端 (打字机效果)
                    responseCollector.append(token); // 悄悄把字存入收集篮
                } catch (Exception e) {
                    LOGGER.error("SSE 推送异常", e);
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                LOGGER.info("====== 大模型流式回复输出完毕 ======");
                // 核心逻辑四：【闭环关键】在结束的一瞬间，把收集篮里攒好的完整回复存入 Redis！
                try {
                    String fullText = responseCollector.toString();
                    // 1. 回写 L1: 写入 Redis 缓存，设置缓存过期时间为 1 小时 
                    redisTemplate.opsForValue().set(redisCacheKey, fullText, 1, TimeUnit.HOURS);

                    // 2. 回写 L2: ES 语义缓存 (沉淀核心资产)
                    SemanticCache newCache = new SemanticCache();
                    newCache.setQueryText(userQuery);
                    newCache.setQueryVector(queryVector); // 存入之前算好的向量
                    newCache.setLlmResponse(fullText);
                    newCache.setCreateTime(System.currentTimeMillis());

                    // 【核心新增】：提取本次参考的商品 ID，作为“血缘依赖”存入缓存
                    if (finalRelevantProducts != null && !finalRelevantProducts.isEmpty()) {
                        List<Long> pIds = finalRelevantProducts.stream()
                                        .map(EsProduct::getId)
                                        .collect(Collectors.toList());
                        newCache.setRefProductIds(pIds);
                    }
                    
                    elasticsearchRestTemplate.save(newCache);
                    LOGGER.info("====== 导购结果已成功沉淀至 L2 ES 语义知识库 ======");

                    // 3. 把完整结果装入承诺中，瞬间唤醒所有等待在这个承诺上的“跟随者”！
                    newPromise.complete(fullText);
                    // 功成身退，把这个任务从登记簿里划掉
                    inFlightRequests.remove(redisCacheKey);
                    LOGGER.info("====== 导购结果已成功写入 Redis 缓存，Key: {} ======", redisCacheKey);
                } catch (Exception e) {
                    LOGGER.error("写入 Redis 缓存失败", e);
                }

                // 告诉前端：流传输结束了
                emitter.complete();
            }

            @Override
            public void onError(Throwable error) {
                LOGGER.error("大模型流式调用发生错误", error);
                // 如果出错了，也要告诉跟随者们“抱歉任务失败了”，并清理登记簿
                newPromise.completeExceptionally(error);
                inFlightRequests.remove(redisCacheKey);
                emitter.completeWithError(error);
            }
        });

        // 立即返回 emitter 对象给前端建立连接，此时大模型在后台慢慢吐字
        return emitter;
    }

    /**
     * 架构升级：结构化意图提取 (Text-to-JSON)
     * 返回一个包含 keyword, brandName, categoryName 的自定义对象或数组
     */

    private IntentMapping extractIntent(String userQuery) {
        String extractPrompt = String.format(
            "你是一个电商意图分析引擎。请从用户的描述中提取核心搜索条件，并严格按照以下 JSON 格式返回，不要有任何多余的字符或 Markdown 标记：\n" +
            "{\n" +
            "  \"keyword\": \"核心检索词（例如：平板电脑，若无则为空字符串）\",\n" +
            "  \"brandName\": \"品牌名称（例如：苹果、小米，若无则为空字符串）\"\n" +
            "}\n\n" +
            "用户描述: \"%s\"", userQuery
        );
        
        long startTime = System.currentTimeMillis();
        String jsonResult = chatLanguageModel.generate(extractPrompt).trim();
        
        // 清理大模型可能带有的 Markdown 代码块标记
        if (jsonResult.startsWith("```json")) {
        jsonResult = jsonResult.substring(7).trim();
            if (jsonResult.endsWith("```")) {
                jsonResult = jsonResult.substring(0, jsonResult.length() - 3).trim();
            }
        }

        IntentMapping intent = new IntentMapping();
        try {
            JsonNode node = objectMapper.readTree(jsonResult);
            intent.keyword = node.path("keyword").asText("");
            intent.brandName = node.path("brandName").asText("");
            LOGGER.info("====== 结构化意图提取成功, 耗时: {}ms | 解析结果: {} ======", 
                    (System.currentTimeMillis() - startTime), jsonResult);
        } catch (Exception e) {
            LOGGER.error("意图 JSON 解析失败，降级使用原句作为 keyword", e);
            intent.keyword = userQuery; // 兜底策略
        }
        return intent;
    }

    private String buildPrompt(String userQuery, String productContext) {
        return String.format(
            "你是一个名为'Lumina'的专业电商智能导购。你的语气亲切、热情且专业。\n" +
            "请仔细阅读以下【候选商品列表】，并以此为依据回答用户的购物需求。\n\n" +
            "【候选商品列表】:\n" +
            "%s\n\n" +
            "【用户的需求】: \"%s\"\n\n" +
            "【执行步骤与回复要求】:\n" +
            "第一步（意图对齐）：深刻理解用户真正想要购买的商品核心品类（例如：用户要买“平板电脑”，则绝不能是“平板电视”，这两者存在本质区别）。\n" +
            "第二步（宁缺毋滥的过滤）：逐一评估候选商品。**坚决剔除**与用户真实核心品类不符的商品。**不要为了凑数而推荐！** 如果列表中只有1款完全符合，就只推荐1款；如果没有符合的，就委婉致歉。\n" +
            "第三步（生成回复）：向用户展示通过过滤的商品。结合用户提到的具体使用场景（例如用来送人等），发挥你的专业导购能力，将推荐理由写得丰满、热情且极具说服力，不要干巴巴地只罗列参数！\n"+
            "【最高红线】：\n" +
            "1. 绝对不要向用户解释你的过滤过程！\n" +
            "2. 如果最终只推荐了 1 款商品，【绝对不要】在商品前加 '1.' 等数字序号！\n" +
            "3. 绝对不要在回复中提及被你淘汰的劣质/无关商品！\n" +
            "4. 严禁推荐列表中不存在的商品！",
            productContext, userQuery
        );
    }

    // 辅助方法：一次性把巨量文本通过 SSE 推给前端并断开
    private void sendSingleChunkAndComplete(SseEmitter emitter, String text) {
        try {
            // 直接把从 Redis 拿到的整段超长文本，作为一次 SSE 事件推给前端
            emitter.send(text);
            emitter.complete(); // 立刻关闭连接，释放 Tomcat 线程资源
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
