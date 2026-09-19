package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.entity.McpConfig;
import com.synapseai.service.McpService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户端 MCP 功能接口：仅暴露已启用功能的使用入口（功能启停由管理端维护）。
 * 前端悬浮窗「AI 小助手」通过 /api/mcp/functions 拿到当前可用的功能列表，
 * 再按需调用对应功能的 preview / send。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    @Autowired
    private McpService mcpService;

    /** 统一异常：业务异常以 Result.error 返回 */
    @ExceptionHandler(RuntimeException.class)
    public Result<?> handleRuntimeException(RuntimeException e) {
        return Result.error(e.getMessage() == null ? "操作失败" : e.getMessage());
    }

    /**
     * 列出当前已启用的 MCP 功能（前端据此决定展示哪些能力）。
     * 返回 [{code,name,description}]，如含 code=message_sender 才显示「发消息」Tab。
     */
    @GetMapping("/functions")
    public Result<?> functions() {
        List<McpConfig> enabled = mcpService.listEnabled();
        List<Map<String, Object>> data = enabled.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.getCode());
            m.put("name", c.getName());
            m.put("description", c.getDescription());
            return m;
        }).collect(Collectors.toList());
        return Result.ok(data);
    }

    /** message_sender 预览：解析收件人 + 正文，供用户确认 */
    @PostMapping("/message-sender/preview")
    public Result<?> messageSenderPreview(@RequestAttribute("uid") Long uid,
                                          @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(mcpService.messageSenderPreview(uid, body));
    }

    /** message_sender 发送：逐条落库并实时推送 */
    @PostMapping("/message-sender/send")
    public Result<?> messageSenderSend(@RequestAttribute("uid") Long uid,
                                       @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(mcpService.messageSenderSend(uid, body));
    }
}
