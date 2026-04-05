package com.macro.mall.search.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 智能导购服务
 * 结合 ES 混合检索与 LangChain4j 大模型实现 RAG
 */
public interface AiChatGuideService {

    /**
     * 根据用户自然语言提问直接查询ES，生成导购建议 (RAG闭环)
     * @param userQuery 用户提问内容
     * @return 大模型的导购回复
     */
    String generateShoppingGuide(String userQuery);

    /**
     * 解析用户自然语言提问精准查询ES，流式生成智能导购建议 (SSE 打字机效果)
     * @param userQuery 用户提问内容
     * @return SseEmitter 流式发送器
     */
    SseEmitter generateStreamShoppingGuide(String userQuery);
    
}