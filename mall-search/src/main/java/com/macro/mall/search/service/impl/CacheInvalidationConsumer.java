package com.macro.mall.search.service.impl;

import com.macro.mall.search.domain.ProductChangeMessage;
import com.macro.mall.search.domain.SemanticCache;
import org.elasticsearch.index.query.QueryBuilders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.util.List;

@Component
public class CacheInvalidationConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheInvalidationConsumer.class);

    @Autowired
    private ElasticsearchRestTemplate elasticsearchRestTemplate;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    // 监听名为 product_change_topic 的通道
    @KafkaListener(topics = "product_change_topic", groupId = "lumina-search-group")
    public void onProductChange(ProductChangeMessage message) {
        Long changedProductId = message.getProductId();
        LOGGER.warn("====== 收到 Kafka 商品变更事件！触发缓存血缘失效机制！变更商品ID: [{}] ======", changedProductId);

        try {
            // 1. 去 ES 中揪出所有依赖了该商品的 L2 缓存记录
            // termQuery 可以直接在 List 类型的字段中精确匹配是否存在某个元素
            NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
                    .withQuery(QueryBuilders.termQuery("refProductIds", changedProductId))
                    .build();

            SearchHits<SemanticCache> hits = elasticsearchRestTemplate.search(searchQuery, SemanticCache.class);
            List<SearchHit<SemanticCache>> searchHits = hits.getSearchHits();

            if (searchHits.isEmpty()) {
                LOGGER.info("未发现依赖该商品的 AI 导购缓存，无需清理。");
                return;
            }

            // 2. 开始精准爆破
            for (SearchHit<SemanticCache> hit : searchHits) {
                SemanticCache cacheDoc = hit.getContent();
                
                // 爆破 L1 Redis 缓存 (极其巧妙：用原始提问逆向推导出 MD5 Key)
                String queryHash = DigestUtils.md5DigestAsHex(cacheDoc.getQueryText().getBytes());
                String redisCacheKey = "lumina_v6:ai:cache:" + queryHash; // 注意这里的前缀要和你 Service 里用的一致！
                redisTemplate.delete(redisCacheKey);
                LOGGER.info("已清理 L1 Redis 缓存: [{}]", cacheDoc.getQueryText());

                // 爆破 L2 ES 缓存
                elasticsearchRestTemplate.delete(cacheDoc.getId(), SemanticCache.class);
                LOGGER.info("已清理 L2 ES 语义缓存: [{}]", cacheDoc.getQueryText());
            }

            LOGGER.warn("====== 缓存血缘清理完毕，受影响的 AI 导购将被迫重新生成！ ======");

        } catch (Exception e) {
            LOGGER.error("处理缓存失效时发生异常", e);
        }
    }
}
