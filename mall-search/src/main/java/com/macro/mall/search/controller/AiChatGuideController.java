package com.macro.mall.search.controller;

import com.macro.mall.common.api.CommonResult;
import com.macro.mall.search.service.AiChatGuideService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 智能导购 AI 助手 Controller
 */
@Controller
@Api(tags = "AiChatGuideController")
@RequestMapping("/ai/guide")
public class AiChatGuideController {

    @Autowired
    private AiChatGuideService aiChatGuideService;

    @ApiOperation(value = "与大模型对话获取智能导购建议")
    @RequestMapping(value = "/chat", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<String> chat(
            @ApiParam(value = "用户的自然语言购物需求，例如：预算两千买个适合玩游戏的手机", required = true)
            @RequestParam("query") String query) {
        
        // 调用 Service 获取大模型结果
        String aiResponse = aiChatGuideService.generateShoppingGuide(query);
        
        // 使用 mall 框架统一的 CommonResult 包装返回
        return CommonResult.success(aiResponse);
    }

    @ApiOperation(value = "流式获取智能导购建议 (SSE 打字机效果)")
    @GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter streamChat(
            @ApiParam(value = "用户的自然语言购物需求", required = true)
            @RequestParam("query") String query) {
        
        // 注意：SSE 接口不需要用 CommonResult 包装，否则会破坏流式数据协议！
        return aiChatGuideService.generateStreamShoppingGuide(query);
    }
}