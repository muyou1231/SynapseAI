package com.synapseai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapseai.common.Result;
import com.synapseai.dto.WsMessage;
import com.synapseai.entity.McpConfig;
import com.synapseai.entity.User;
import com.synapseai.mapper.McpConfigMapper;
import com.synapseai.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP 功能服务：管理端可配置并启停各项 MCP 功能；这里实现第一个功能 message_sender（群发助手）。
 *
 * 设计要点：
 * - 所有功能都有唯一 code（如 message_sender），前端通过 /api/mcp/functions 仅拿到 enabled=1 的功能，
 *   据此决定界面上展示哪些「MCP 功能」入口（如悬浮窗里是否显示「发消息」Tab）。
 * - message_sender 支持两种用法：
 *   1) 直接传 recipients（ACCOUNT/USERNAME/UID/KEYWORD）+ content，由后端精确解析后发送；
 *   2) 仅传一句自然语言 instruction（如「给 6530823031 和小明发消息：周末一起爬山」），
 *      由 AI 解析出收件人列表与正文，先 preview 给用户确认，再 send 真正落库推送。
 */
@Service
public class McpService {

    private static final String CODE_MESSAGE_SENDER = "message_sender";

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private McpConfigMapper mcpConfigMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private MessageService messageService;
    @Autowired
    private AiService aiService;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /* ============ 通用：功能列表 / 启停校验 ============ */

    /** 返回所有已启用的功能（前端据此决定展示哪些能力） */
    public List<McpConfig> listEnabled() {
        return mcpConfigMapper.selectEnabled();
    }

    /** 校验某功能是否启用，未启用/不存在则抛异常（供各功能入口统一拦截） */
    public McpConfig requireEnabled(String code) {
        McpConfig c = mcpConfigMapper.selectByCode(code);
        if (c == null || !Boolean.TRUE.equals(c.getEnabled())) {
            throw new RuntimeException("MCP 功能未启用：" + code);
        }
        return c;
    }

    /* ============ 功能一：message_sender ============ */

    /**
     * 解析收件人指令 / 列表，返回可确认预览。
     * 入参 body：{ instruction?, recipients?:[{type,value}], content? }
     * 返回：{ recipients:[解析后收件人], content, parsedByAi }
     */
    public Map<String, Object> messageSenderPreview(Long operatorUid, Map<String, Object> body) {
        requireEnabled(CODE_MESSAGE_SENDER);
        String instruction = body == null ? null : asString(body.get("instruction"));
        List<Map<String, Object>> recipientsIn = toRecipientList(body == null ? null : body.get("recipients"));
        String content = body == null ? null : asString(body.get("content"));

        boolean parsedByAi = false;
        if ((recipientsIn == null || recipientsIn.isEmpty()) && instruction != null && !instruction.isBlank()) {
            // 自然语言解析：AI 输出 {recipients:[{type,value}], content}
            Map<String, Object> ai = aiParseInstruction(instruction);
            if (ai != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> aiRecipients = (List<Map<String, Object>>) ai.get("recipients");
                if (aiRecipients != null && !aiRecipients.isEmpty()) recipientsIn = aiRecipients;
                if (content == null || content.isBlank()) content = asString(ai.get("content"));
                parsedByAi = true;
            }
        }
        if (recipientsIn == null || recipientsIn.isEmpty()) {
            throw new RuntimeException("未提供收件人：请在 instruction 中说明，或显式传入 recipients");
        }
        if (content == null || content.isBlank()) {
            throw new RuntimeException("未提供消息正文：请在 instruction 中说明，或显式传入 content");
        }

        List<Map<String, Object>> resolved = resolveRecipients(recipientsIn);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recipients", resolved);
        out.put("content", content);
        out.put("parsedByAi", parsedByAi);
        out.put("resolvedCount", resolved.stream().filter(r -> Boolean.TRUE.equals((Boolean) r.get("resolved"))).count());
        return out;
    }

    /**
     * 真正发送消息：解析收件人 -> 逐条落库 + 实时推送（发送者为当前登录用户）。
     * 入参 body：{ recipients:[{type,value}], content }
     * 返回：{ sent:[{type,value,userId,nickname}], failed:[...], skipped:[...], total }
     */
    public Map<String, Object> messageSenderSend(Long operatorUid, Map<String, Object> body) {
        requireEnabled(CODE_MESSAGE_SENDER);
        List<Map<String, Object>> recipientsIn = toRecipientList(body == null ? null : body.get("recipients"));
        String content = asString(body == null ? null : body.get("content"));
        if (recipientsIn == null || recipientsIn.isEmpty()) {
            throw new RuntimeException("未提供收件人");
        }
        if (content == null || content.isBlank()) {
            throw new RuntimeException("消息正文不能为空");
        }

        int maxRecipients = maxRecipients();
        List<Map<String, Object>> resolved = resolveRecipients(recipientsIn);
        List<Map<String, Object>> valid = resolved.stream()
                .filter(r -> Boolean.TRUE.equals((Boolean) r.get("resolved")) && r.get("userId") != null)
                .collect(Collectors.toList());
        if (valid.size() > maxRecipients) {
            throw new RuntimeException("收件人数量超过上限（" + maxRecipients + "），请分批发送");
        }

        List<Map<String, Object>> sent = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();

        for (Map<String, Object> r : valid) {
            Long userId = (Long) r.get("userId");
            if (userId.equals(operatorUid)) {
                skipped.add(summary(r, "不能发送给自己"));
                continue;
            }
            try {
                WsMessage wm = messageService.save(operatorUid, "TEXT", content, "USER", userId, false);
                // 推送给接收方与发送者本人（与 ChatController.handle 单聊推送一致）
                messagingTemplate.convertAndSend("/topic/user/" + userId, wm);
                messagingTemplate.convertAndSend("/topic/user/" + operatorUid, wm);
                sent.add(summary(r, null));
            } catch (RuntimeException e) {
                failed.add(summary(r, e.getMessage()));
            }
        }
        // 未成功解析的收件人归入 failed
        for (Map<String, Object> r : resolved) {
            if (!Boolean.TRUE.equals((Boolean) r.get("resolved"))) {
                failed.add(summary(r, "找不到匹配的用户"));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", sent);
        out.put("failed", failed);
        out.put("skipped", skipped);
        out.put("total", valid.size());
        out.put("successCount", sent.size());
        return out;
    }

    /* ============ 内部工具 ============ */

    /** 调用 AI 解析自然语言指令为 {recipients:[{type,value}], content} */
    private Map<String, Object> aiParseInstruction(String instruction) {
        String system = "你是消息群发助手的指令解析器。用户会用一句话描述要发给谁、发什么。" +
                "请只输出一个 JSON 对象，不要包含任何解释或 markdown，格式严格如下：\n" +
                "{\n" +
                "  \"recipients\": [ { \"type\": \"ACCOUNT|USERNAME|UID\", \"value\": \"账号/用户名/数字id\" } ],\n" +
                "  \"content\": \"要发送的消息正文（只保留消息内容，去掉收件人称谓）\"\n" +
                "}\n" +
                "说明：\n" +
                "- 若用户给出 10 位数字账号（如 6530823031），type 用 ACCOUNT；\n" +
                "- 若给出用户名/昵称（如「小明」），type 用 USERNAME；\n" +
                "- 若给出纯数字 id（非账号），type 用 UID。\n" +
                "- recipients 可以是多个（一次性群发）。若无法识别任何收件人，recipients 返回空数组。";
        String user = "请解析这条指令：\n" + instruction;
        try {
            String raw = aiService.chat(system, user);
            if (raw == null || raw.isBlank()) return null;
            // 容错：截取第一个 { 到最后一个 } 之间的 JSON
            int s = raw.indexOf('{');
            int e = raw.lastIndexOf('}');
            if (s < 0 || e < 0 || e <= s) return null;
            JsonNode node = json.readTree(raw.substring(s, e + 1));
            Map<String, Object> map = new LinkedHashMap<>();
            JsonNode rec = node.get("recipients");
            List<Map<String, Object>> list = new ArrayList<>();
            if (rec != null && rec.isArray()) {
                for (JsonNode n : rec) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", nodeText(n.get("type")));
                    item.put("value", nodeText(n.get("value")));
                    list.add(item);
                }
            }
            map.put("recipients", list);
            map.put("content", node.has("content") ? node.get("content").asText("") : "");
            return map;
        } catch (RuntimeException re) {
            // AI 不可用（未配置 key 等）时返回 null，由上层提示用户改用显式收件人
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    /** 把入参 recipients 统一成 List<Map{type,value}>（兼容数组 / 单对象） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toRecipientList(Object obj) {
        if (obj == null) return null;
        List<Map<String, Object>> list = new ArrayList<>();
        if (obj instanceof List) {
            for (Object o : (List<?>) obj) {
                if (o instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) o;
                    String type = asString(m.get("type"));
                    String value = asString(m.get("value"));
                    if (type != null && value != null) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("type", type.toUpperCase(Locale.ROOT));
                        item.put("value", value);
                        list.add(item);
                    }
                }
            }
        } else if (obj instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) obj;
            String type = asString(m.get("type"));
            String value = asString(m.get("value"));
            if (type != null && value != null) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", type.toUpperCase(Locale.ROOT));
                item.put("value", value);
                list.add(item);
            }
        }
        return list.isEmpty() ? null : list;
    }

    /** 解析收件人列表（ACCOUNT/USERNAME/UID/KEYWORD）为可发送的用户清单，支持 KEYWORD 展开多人 */
    private List<Map<String, Object>> resolveRecipients(List<Map<String, Object>> recipients) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : recipients) {
            String type = asString(r.get("type"));
            String value = asString(r.get("value"));
            if (type == null || value == null) continue;
            switch (type) {
                case "KEYWORD": {
                    List<User> users = userMapper.findByUsernameContainingOrNicknameContainingOrAccountContaining(value, value, value);
                    if (users.isEmpty()) {
                        out.add(unresolved(type, value, "未匹配到用户"));
                    } else {
                        for (User u : users) out.add(resolved(type, value, u));
                    }
                    break;
                }
                case "ACCOUNT": {
                    User u = userMapper.findByAccount(value);
                    out.add(u != null ? resolved(type, value, u) : unresolved(type, value, "账号不存在"));
                    break;
                }
                case "USERNAME": {
                    User u = userMapper.findByUsername(value);
                    if (u == null) {
                        // 退化为模糊匹配取第一个候选
                        List<User> users = userMapper.findByUsernameContainingOrNicknameContainingOrAccountContaining(value, value, value);
                        if (users.isEmpty()) {
                            out.add(unresolved(type, value, "用户名不存在"));
                        } else {
                            out.add(resolved(type, value, users.get(0)));
                        }
                    } else {
                        out.add(resolved(type, value, u));
                    }
                    break;
                }
                case "UID": {
                    try {
                        Long uid = Long.valueOf(value.trim());
                        User u = userMapper.selectById(uid);
                        out.add(u != null ? resolved(type, value, u) : unresolved(type, value, "用户不存在"));
                    } catch (NumberFormatException ex) {
                        out.add(unresolved(type, value, "UID 须为数字"));
                    }
                    break;
                }
                default:
                    out.add(unresolved(type, value, "未知收件人类型"));
            }
        }
        // 按 userId 去重（同一用户被多种方式命中时只保留一条）
        Map<Long, Map<String, Object>> dedup = new LinkedHashMap<>();
        for (Map<String, Object> m : out) {
            Object uid = m.get("userId");
            if (uid != null) dedup.putIfAbsent((Long) uid, m);
            else dedup.put((long) (out.indexOf(m) + 1) * -1, m);
        }
        return new ArrayList<>(dedup.values());
    }

    private Map<String, Object> resolved(String type, String value, User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("value", value);
        m.put("resolved", true);
        m.put("userId", u.getId());
        m.put("nickname", u.getNickname());
        m.put("username", u.getUsername());
        m.put("account", u.getAccount());
        return m;
    }

    private Map<String, Object> unresolved(String type, String value, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("value", value);
        m.put("resolved", false);
        m.put("userId", null);
        m.put("reason", reason);
        return m;
    }

    private Map<String, Object> summary(Map<String, Object> r, String failReason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", r.get("type"));
        m.put("value", r.get("value"));
        m.put("userId", r.get("userId"));
        m.put("nickname", r.get("nickname"));
        m.put("username", r.get("username"));
        m.put("account", r.get("account"));
        if (failReason != null) m.put("reason", failReason);
        return m;
    }

    /** 从 config_json 读取最大收件人数（默认 50） */
    private int maxRecipients() {
        try {
            McpConfig c = mcpConfigMapper.selectByCode(CODE_MESSAGE_SENDER);
            if (c != null && c.getConfigJson() != null && !c.getConfigJson().isBlank()) {
                JsonNode node = json.readTree(c.getConfigJson());
                if (node.has("maxRecipients")) {
                    return node.get("maxRecipients").asInt(50);
                }
            }
        } catch (Exception ignored) {
        }
        return 50;
    }

    private String asString(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** 从 Jackson JsonNode 取出去引号后的文本（替代 toString() 以免带上多余引号） */
    private String nodeText(JsonNode n) {
        if (n == null || n.isNull()) return null;
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
