package com.macro.mall.search.domain;

/**
 * 商品变更 Kafka 消息体
 */
public class ProductChangeMessage {
    
    private Long productId; // 发生变更的商品 ID
    private String action;  // 动作类型 (例如：UPDATE_PRICE, OFFLINE)

    public ProductChangeMessage() {}

    public ProductChangeMessage(Long productId, String action) {
        this.productId = productId;
        this.action = action;
    }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
}