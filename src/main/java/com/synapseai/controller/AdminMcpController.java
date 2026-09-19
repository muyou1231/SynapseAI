package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.entity.McpConfig;
import com.synapseai.entity.User;
import com.synapseai.mapper.McpConfigMapper;
import com.synapseai.mapper.UserMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理端 MCP 功能配置接口（仿 AdminController，统一校验 ADMIN 角色）。
 * 提供功能列表 / 新增 / 编辑 / 删除 / 启停，以及首次运行自动补齐默认功能（message_sender）。
 */
@RestController
@RequestMapping("/api/admin/mcp")
public class AdminMcpController {

    @Autowired
    private McpConfigMapper mcpConfigMapper;
    @Autowired
    private UserMapper userMapper;

    /** 统一异常 */
    @ExceptionHandler(RuntimeException.class)
    public Result<?> handleRuntimeException(RuntimeException e) {
        return Result.error(e.getMessage() == null ? "操作失败" : e.getMessage());
    }

    /** 校验管理员 */
    private void assertAdmin(Long uid) {
        User u = userMapper.selectById(uid);
        if (u == null || !"ADMIN".equals(u.getRole())) {
            throw new RuntimeException("无权限：需要管理员身份");
        }
    }

    /** 启动时确保默认功能存在（仅 message_sender，默认停用，由管理员开启） */
    @PostConstruct
    public void ensureDefaults() {
        try {
            if (mcpConfigMapper.selectByCode("message_sender") == null) {
                McpConfig c = new McpConfig();
                c.setCode("message_sender");
                c.setName("消息群发助手");
                c.setDescription("AI 小助手悬浮窗：用一句话让 AI 帮忙给指定账号/用户名的多个用户群发消息。");
                c.setEnabled(false);
                c.setConfigJson("{\"maxRecipients\":50}");
                c.setSort(10);
                c.setCreateTime(LocalDateTime.now());
                c.setUpdateTime(LocalDateTime.now());
                mcpConfigMapper.insert(c);
            }
        } catch (Exception ignored) {
            // 表尚未迁移等情况下不阻塞启动
        }
    }

    /** 列出全部 MCP 功能（含停用），供管理端配置页使用 */
    @GetMapping
    public Result<?> list(@RequestAttribute("uid") Long uid) {
        assertAdmin(uid);
        List<McpConfig> all = mcpConfigMapper.selectAll();
        List<Map<String, Object>> data = all.stream().map(this::toMap).collect(Collectors.toList());
        return Result.ok(data);
    }

    /** 新增功能 */
    @PostMapping
    public Result<?> create(@RequestAttribute("uid") Long uid, @RequestBody(required = false) McpConfig body) {
        assertAdmin(uid);
        if (body == null || body.getCode() == null || body.getCode().isBlank()) {
            return Result.error("请填写功能编码(code)");
        }
        if (mcpConfigMapper.selectByCode(body.getCode()) != null) {
            return Result.error("功能编码已存在：" + body.getCode());
        }
        McpConfig c = new McpConfig();
        c.setCode(body.getCode().trim());
        c.setName(body.getName() == null ? body.getCode() : body.getName());
        c.setDescription(body.getDescription());
        c.setEnabled(Boolean.TRUE.equals(body.getEnabled()));
        c.setConfigJson(body.getConfigJson());
        c.setSort(body.getSort() == null ? 0 : body.getSort());
        c.setCreateTime(LocalDateTime.now());
        c.setUpdateTime(LocalDateTime.now());
        mcpConfigMapper.insert(c);
        return Result.ok(toMap(c));
    }

    /** 编辑功能（名称/简介/配置/排序/启停均可改；code 不可改） */
    @PutMapping("/{id}")
    public Result<?> update(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id,
                            @RequestBody(required = false) McpConfig body) {
        assertAdmin(uid);
        McpConfig c = mcpConfigMapper.selectById(id);
        if (c == null) return Result.error("功能不存在");
        if (body == null) return Result.error("缺少参数");
        if (body.getName() != null) c.setName(body.getName());
        if (body.getDescription() != null) c.setDescription(body.getDescription());
        if (body.getConfigJson() != null) c.setConfigJson(body.getConfigJson());
        if (body.getSort() != null) c.setSort(body.getSort());
        if (body.getEnabled() != null) c.setEnabled(body.getEnabled());
        c.setUpdateTime(LocalDateTime.now());
        mcpConfigMapper.updateById(c);
        return Result.ok(toMap(c));
    }

    /** 启停切换 */
    @PostMapping("/{id}/toggle")
    public Result<?> toggle(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id,
                            @RequestBody(required = false) Map<String, Boolean> body) {
        assertAdmin(uid);
        McpConfig c = mcpConfigMapper.selectById(id);
        if (c == null) return Result.error("功能不存在");
        Boolean enabled = body != null ? body.get("enabled") : null;
        if (enabled == null) enabled = !Boolean.TRUE.equals(c.getEnabled());
        c.setEnabled(enabled);
        c.setUpdateTime(LocalDateTime.now());
        mcpConfigMapper.updateEnabled(id, enabled, LocalDateTime.now());
        return Result.ok(toMap(c));
    }

    /** 删除功能 */
    @DeleteMapping("/{id}")
    public Result<?> delete(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        assertAdmin(uid);
        McpConfig c = mcpConfigMapper.selectById(id);
        if (c == null) return Result.error("功能不存在");
        // 保护内置功能 code=message_sender 不被误删（仍可通过停用关闭）
        if ("message_sender".equals(c.getCode())) {
            return Result.error("内置功能 message_sender 不允许删除（可停用）");
        }
        mcpConfigMapper.deleteById(id);
        return Result.ok("已删除");
    }

    private Map<String, Object> toMap(McpConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("code", c.getCode());
        m.put("name", c.getName());
        m.put("description", c.getDescription());
        m.put("enabled", Boolean.TRUE.equals(c.getEnabled()));
        m.put("configJson", c.getConfigJson());
        m.put("sort", c.getSort());
        m.put("createTime", c.getCreateTime());
        m.put("updateTime", c.getUpdateTime());
        return m;
    }
}
