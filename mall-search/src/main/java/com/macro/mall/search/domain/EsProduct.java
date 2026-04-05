package com.macro.mall.search.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 搜索商品的信息
 * Created by macro on 2018/6/19.
 */
@Data
@EqualsAndHashCode
@Document(indexName = "pms")
@Setting(shards = 1,replicas = 0)
public class EsProduct implements Serializable {
    private static final long serialVersionUID = -1L;
    @Id
    private Long id;
    @Field(type = FieldType.Keyword)
    private String productSn;
    private Long brandId;
    @Field(type = FieldType.Keyword)
    private String brandName;
    private Long productCategoryId;
    @Field(type = FieldType.Keyword)
    private String productCategoryName;
    private String pic;
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String name;
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String subTitle;
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String keywords;
    private BigDecimal price;
    private Integer sale;
    private Integer newStatus;
    private Integer recommandStatus;
    private Integer stock;
    private Integer promotionType;
    private Integer sort;
    @Field(type = FieldType.Nested, fielddata = true)
    private List<EsProductAttributeValue> attrValueList;
    // 【新增】向量字段
    // type = Dense_Vector (稠密向量)
    // dims = 384 (因为我们用的 all-minilm-l6-v2 模型输出就是 384 维)
    @Field(type = FieldType.Dense_Vector, dims = 384) // 维度必须匹配 all-minilm-l6-v2
    private List<Double> vector;
}
