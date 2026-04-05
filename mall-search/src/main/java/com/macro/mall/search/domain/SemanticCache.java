package com.macro.mall.search.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.List;

/**
 * AI 语义缓存实体类
 */
@Document(indexName = "ai_semantic_cache")
public class SemanticCache {

    @Id
    private String id; // ES 自动生成的 ID

    // 指定 Text 类型和 IK 分词器
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String queryText; // 用户原始提问

    // 注意：dense_vector 字段在 Spring Data ES 实体类中通常用 List<Double> 表示
    @Field(type = FieldType.Dense_Vector, dims = 384)
    private List<Double> queryVector; // 提问的向量

    // index = false，因为我们只存不搜
    @Field(type = FieldType.Text, index = false)
    private String llmResponse; // 大模型的完整回复

    // 【新增架构字段】：记录大模型本次导购参考了哪些商品的 ID
    @Field(type = FieldType.Long)
    private List<Long> refProductIds;

    @Field(type = FieldType.Long)
    private Long createTime; // 缓存创建时间 (毫秒时间戳)

    // --- 请在此处生成 getter 和 setter 方法 ---
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getQueryText() { return queryText; }
    public void setQueryText(String queryText) { this.queryText = queryText; }

    public List<Double> getQueryVector() { return queryVector; }
    public void setQueryVector(List<Double> queryVector) { this.queryVector = queryVector; }

    public String getLlmResponse() { return llmResponse; }
    public void setLlmResponse(String llmResponse) { this.llmResponse = llmResponse; }

    public Long getCreateTime() { return createTime; }
    public void setCreateTime(Long createTime) { this.createTime = createTime; }

    public List<Long> getRefProductIds() { return refProductIds; }
    public void setRefProductIds(List<Long> refProductIds) { this.refProductIds = refProductIds; }
}