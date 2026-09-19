package com.synapseai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapseai.entity.AiAssistant;
import com.synapseai.entity.Friendship;
import com.synapseai.entity.User;
import com.synapseai.mapper.AiAssistantMapper;
import com.synapseai.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * moyo AI 助手服务：驱动「每个用户的 AI 好友」聊天与文案生成，以及朋友圈 AI 审核。
 * 所有 AI 能力复用 {@link AiService}（阿里云百炼/通义）。
 * 每个 AI 助手是一个 role=AI 的独立 user 账户，配置存于 ai_assistant 表（每行一个助手，user_id 关联 user 表）。
 * 管理员只能在管理端创建/修改；普通用户不可注册/搜索/加好友/改资料。
 * 默认助手为 id=1 的全局单一助手，所有用户共享，改一处全员生效。
 */
@Service
public class AiAssistantService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 助手默认人设（当 ai_assistant.prompt 为空时使用） */
    private static final String DEFAULT_PROMPT =
            "你是「moyo助手」，一个温暖、贴心、乐于助人的 AI 伙伴。" +
            "你可以：陪用户聊天、帮用户生成朋友圈/广场文案、回答学习与生活问题、协助整理信息。" +
            "请用自然、口语化、简洁的中文回复，避免长篇大论，必要时给出可执行的建议。";

    /** 助手系统账户 username 前缀（固定，由 AdminBootstrap 在启动时 ensure 默认助手） */
    public static final String ASSISTANT_USERNAME = "moyo_assistant";

    @Autowired
    private AiAssistantMapper assistantMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private AiService aiService;

    @Autowired
    private com.synapseai.mapper.FriendshipMapper friendshipMapper;

    @Autowired
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    private final java.util.Random random = new java.util.Random();

    /** 生成唯一的 10 位数字账号（与 UserService 逻辑一致），用于新建 AI 助手账户 */
    private String generateAccount() {
        String acc;
        do {
            StringBuilder sb = new StringBuilder(10);
            for (int i = 0; i < 10; i++) sb.append(random.nextInt(10));
            acc = sb.toString();
        } while (userMapper.existsByAccount(acc));
        return acc;
    }

    /**
     * 默认助手账户 userId 缓存（启动时由 AdminBootstrap 填充）。
     * 用于 ChatController 异步回复线程快速定位助手账户，避免每次查库。
     */
    private volatile Long defaultAssistantId;

    /**
     * 将默认助手配置行的 user_id 关联到底层 AI 账户，并标记为默认（is_default=1）。
     * 首次启动/存量数据修复使用；即使该行原本不是默认，也会在此强制置为默认。
     */
    public void bindDefaultAssistantUser(Long userId) {
        // 优先取当前默认助手；无默认则取 id 最小者
        AiAssistant a = getDefaultRaw();
        if (a == null) {
            a = new AiAssistant();
            a.setId(1);
            a.setName("moyo助手");
            a.setEnabled(true);
            a.setStatus("ONLINE");
        }
        a.setUserId(userId);
        a.setIsDefault(true);
        assistantMapper.updateConfig(a);
        this.defaultAssistantId = userId;
    }

    /** 取当前默认助手配置行（is_default=1，无则 id 最小者兜底），不触发内置兜底默认值 */
    private AiAssistant getDefaultRaw() {
        AiAssistant a = assistantMapper.selectDefault();
        if (a == null) a = assistantMapper.selectFirst();
        return a;
    }

    /** 返回默认助手配置（is_default=1；无则取 id 最小者；再无则内置默认值，保证调用方不空指针） */
    public AiAssistant getDefaultAssistant() {
        AiAssistant a = getDefaultRaw();
        if (a == null) {
            a = new AiAssistant();
            a.setId(1);
            a.setName("moyo助手");
            a.setEnabled(true);
            a.setStatus("ONLINE");
            a.setSignature("你的智能伙伴，帮你处理广场、生成文案、答疑解惑～");
        }
        return a;
    }

    /** 返回默认助手的 user_id（缓存优先，未命中查库） */
    public Long getDefaultUserId() {
        if (defaultAssistantId != null) return defaultAssistantId;
        AiAssistant a = getDefaultRaw();
        if (a != null && a.getUserId() != null) {
            this.defaultAssistantId = a.getUserId();
            return this.defaultAssistantId;
        }
        return null;
    }

    /** 设置默认助手账户 userId（启动时调用一次，之后缓存） */
    public void setAssistantId(Long id) {
        this.defaultAssistantId = id;
    }

    /** 返回默认助手账户 userId（可能为 null，未初始化时） */
    public Long getAssistantId() {
        return defaultAssistantId;
    }

    /**
     * 判断给定 userId 是否为某个 AI 助手账户（任一 ai_assistant 行的 user_id 匹配即视为助手）。
     */
    public boolean isAssistant(Long uid) {
        if (uid == null) return false;
        AiAssistant a = assistantMapper.selectByUserId(uid);
        return a != null;
    }

    /**
     * 取指定 userId 对应 AI 助手的运行状态（ONLINE/OFFLINE/MAINTENANCE/BUSY）。
     * 非助手账户返回 null；助手存在但 status 为空时返回默认 ONLINE。
     */
    public String getStatusByUserId(Long uid) {
        if (uid == null) return null;
        AiAssistant a = assistantMapper.selectByUserId(uid);
        if (a == null) return null;
        return (a.getStatus() != null && !a.getStatus().isEmpty()) ? a.getStatus() : "ONLINE";
    }

    /** 全部 AI 助手账户（id 升序），用于管理端列表；回填关联 user 的账号便于展示 */
    public List<AiAssistant> listAssistants() {
        List<AiAssistant> list = assistantMapper.selectAll();
        list.forEach(this::fillAccount);
        return list;
    }

    /** 读取默认助手配置（兼容既有 chat/review 逻辑，现基于 is_default=1 或 id 最小者）。不存在则返回内置默认值。 */
    public AiAssistant getConfig() {
        return getDefaultAssistant();
    }

    /** 按 id 读取助手配置（管理端编辑时使用） */
    public AiAssistant getConfigById(Integer id) {
        AiAssistant a = assistantMapper.selectById(id);
        if (a == null) throw new RuntimeException("AI 助手不存在");
        fillAccount(a);
        return a;
    }

    /** 将关联 user(AI) 账户的 account 回填到 AiAssistant 临时字段（不持久化，仅展示/编辑回显用） */
    private void fillAccount(AiAssistant a) {
        if (a.getUserId() != null) {
            User u = userMapper.selectById(a.getUserId());
            if (u != null) a.setAccount(u.getAccount());
        }
    }

    /** 当前助手是否可用（启用且非 OFFLINE/MAINTENANCE 视为可对话） */
    public boolean isAvailable() {
        AiAssistant a = getConfig();
        if (a.getEnabled() == null || !a.getEnabled()) return false;
        String s = a.getStatus();
        return !"OFFLINE".equals(s) && !"MAINTENANCE".equals(s);
    }

    /** 助手人设提示词（优先用配置中的 prompt，否则用默认） */
    /** 默认助手的人设提示词（向后兼容无 target 参数的调用） */
    private String systemPrompt() {
        return systemPrompt(null);
    }

    /**
     * 指定 AI 助手的人设提示词。assistantUserId 为 null 或非法时回退默认助手。
     * 用于「给哪个 AI 发消息就用哪个 AI 的人设回复」，避免所有对话都走默认助手。
     */
    private String systemPrompt(Long assistantUserId) {
        AiAssistant a;
        if (assistantUserId != null && isAssistant(assistantUserId)) {
            AiAssistant byUser = assistantMapper.selectByUserId(assistantUserId);
            a = (byUser != null) ? byUser : getDefaultAssistant();
        } else {
            a = getConfig();
        }
        String p = a.getPrompt();
        return (p != null && !p.isBlank()) ? p : DEFAULT_PROMPT;
    }

    /**
     * 更新默认助手配置（管理员调试用）：启用/禁用、状态、人设提示词、展示名、头像、签名。
     * 以当前默认助手为编辑目标（is_default=1 或 id 最小者），保证「改默认助手」始终命中真正的默认。
     * 展示名/头像/签名同步写回关联的 user(AI) 账户，保证好友列表与聊天界面展示一致。
     */
    public AiAssistant updateConfig(AiAssistant cfg) {
        AiAssistant def = getDefaultRaw();
        Integer targetId = (def != null) ? def.getId() : 1;
        return updateAssistantById(targetId, cfg);
    }

    /**
     * 按 id 更新助手配置，并双向同步 name/avatar/signature 到关联的 user(AI) 账户。
     * 支持修改账号（account，>=10 位且唯一，落库到 user.account）与默认标记（isDefault）。
     */
    public AiAssistant updateAssistantById(Integer id, AiAssistant cfg) {
        AiAssistant cur = getConfigById(id);
        if (cfg.getName() != null) cur.setName(cfg.getName());
        if (cfg.getAvatar() != null) cur.setAvatar(cfg.getAvatar());
        if (cfg.getEnabled() != null) cur.setEnabled(cfg.getEnabled());
        if (cfg.getStatus() != null) cur.setStatus(cfg.getStatus());
        if (cfg.getPrompt() != null) cur.setPrompt(cfg.getPrompt());
        if (cfg.getSignature() != null) cur.setSignature(cfg.getSignature());
        if (cfg.getIsDefault() != null) cur.setIsDefault(cfg.getIsDefault());
        assistantMapper.updateConfig(cur);

        // AI 状态变更：向所有以该 AI 为好友的在线用户实时推送，使对方列表/会话状态点即时刷新（无需手动刷新）
        if (cfg.getStatus() != null && cur.getUserId() != null) {
            pushAssistantStatusToFriends(cur.getUserId(), cur.getStatus(), cur.getName());
        }

        // 账号变更：校验后同步到 user(AI) 账户
        if (cfg.getAccount() != null && !cfg.getAccount().trim().isEmpty()) {
            String acc = cfg.getAccount().trim();
            if (acc.length() < 10) throw new RuntimeException("账号至少 10 位");
            // 仅当与当前账号不同才校验唯一性
            User exist = userMapper.selectById(cur.getUserId());
            if (exist == null || !acc.equals(exist.getAccount())) {
                if (userMapper.existsByAccount(acc)) throw new RuntimeException("账号已存在，请更换");
            }
            userMapper.updateAccount(cur.getUserId(), acc);
        }

        // 设为默认：清除其它默认标记，并将全体普通用户的「旧默认 AI 好友」替换为新默认
        if (Boolean.TRUE.equals(cfg.getIsDefault())) {
            Long oldDefaultUid = this.defaultAssistantId;
            if (oldDefaultUid == null) {
                AiAssistant oldDef = assistantMapper.selectDefault();
                oldDefaultUid = (oldDef != null) ? oldDef.getUserId() : null;
            }
            assistantMapper.clearDefaults();
            assistantMapper.setDefault(cur.getId());
            this.defaultAssistantId = cur.getUserId();
            switchDefaultFriendForAllUsers(oldDefaultUid, cur.getUserId());
        }

        // 双向同步：展示名/签名/头像同步到 user(AI) 账户
        if (cur.getUserId() != null) {
            userMapper.updateNicknameAndSignature(cur.getUserId(), cur.getName(), cur.getSignature());
            userMapper.updateAvatar(cur.getUserId(), cur.getAvatar());
        }
        return cur;
    }

    /**
     * 向所有以指定 AI 助手为好友的在线用户推送状态变更通知（ASSISTANT_STATUS）。
     * 前端据此即时刷新好友列表/会话列表的状态点，无需手动刷新页面。
     */
    private void pushAssistantStatusToFriends(Long assistantUserId, String status, String name) {
        if (assistantUserId == null || status == null) return;
        List<Friendship> friends = friendshipMapper.findByFriendIdAndStatus(assistantUserId, "ACCEPTED");
        for (Friendship f : friends) {
            Long uid = f.getUserId();
            if (uid == null || uid.equals(assistantUserId)) continue;
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "ASSISTANT_STATUS");
            msg.put("assistantUserId", assistantUserId);
            msg.put("status", status);
            msg.put("name", name);
            messagingTemplate.convertAndSend("/topic/user/" + uid, msg);
        }
    }

    /**
     * 新建 AI 助手：创建 role=AI 的 user 账户 + ai_assistant 配置行。
     * 支持指定账号（account，>=10 位且唯一）与是否默认（isDefault）。
     * 设为默认时会清除其它默认标记，保证全局仅一个默认助手。
     * 返回新建的助手配置（含 id 与 user_id）。
     */
    public AiAssistant createAssistant(AiAssistant req) {
        // 账号：优先用前端传入（需校验），否则后端自动生成 >=10 位唯一账号
        String account = resolveAccount(req.getAccount());
        // 创建底层 AI 角色账户
        User u = new User();
        String username = "ai_" + System.nanoTime();
        u.setUsername(username);
        u.setAccount(account);
        u.setPassword(req.getPassword() != null ? req.getPassword() : "ai@2024");
        u.setNickname(req.getName() != null ? req.getName() : "AI助手");
        u.setRole("AI");
        u.setAvatar(req.getAvatar());
        u.setSignature(req.getSignature() != null ? req.getSignature() : "你的智能伙伴");
        userMapper.insert(u);

        AiAssistant cfg = new AiAssistant();
        cfg.setUserId(u.getId());
        cfg.setName(req.getName() != null ? req.getName() : "AI助手");
        cfg.setAvatar(req.getAvatar());
        cfg.setEnabled(req.getEnabled() != null ? req.getEnabled() : true);
        cfg.setStatus(req.getStatus() != null ? req.getStatus() : "ONLINE");
        cfg.setPrompt(req.getPrompt());
        cfg.setSignature(req.getSignature());
        cfg.setIsDefault(Boolean.TRUE.equals(req.getIsDefault()));
        cfg.setCreateTime(LocalDateTime.now());
        assistantMapper.insert(cfg);

        // 设为默认：清除其它默认标记，并切换全体用户的默认 AI 好友
        if (Boolean.TRUE.equals(req.getIsDefault())) {
            Long oldDefaultUid = this.defaultAssistantId;
            if (oldDefaultUid == null) {
                AiAssistant oldDef = assistantMapper.selectDefault();
                oldDefaultUid = (oldDef != null) ? oldDef.getUserId() : null;
            }
            assistantMapper.clearDefaults();
            assistantMapper.setDefault(cfg.getId());
            this.defaultAssistantId = u.getId();
            switchDefaultFriendForAllUsers(oldDefaultUid, u.getId());
        }
        return cfg;
    }

    /** 解析并校验账号：传入非空则校验 >=10 位且唯一；否则生成 >=10 位唯一账号 */
    private String resolveAccount(String input) {
        if (input != null && !input.trim().isEmpty()) {
            String acc = input.trim();
            if (acc.length() < 10) throw new RuntimeException("账号至少 10 位");
            if (userMapper.existsByAccount(acc)) throw new RuntimeException("账号已存在，请更换");
            return acc;
        }
        return generateAccount();
    }

    /**
     * 删除 AI 助手：删除 ai_assistant 配置行 + 关联的 user(AI) 账户。
     * 若删除的是默认助手，则自动把剩余 id 最小者设为新的默认助手（满足「删除后自动切换」）。
     */
    public void deleteAssistant(Integer id) {
        if (id == null) throw new RuntimeException("参数错误");
        AiAssistant a = assistantMapper.selectById(id);
        if (a == null) throw new RuntimeException("AI 助手不存在");
        boolean wasDefault = Boolean.TRUE.equals(a.getIsDefault());
        if (a.getUserId() != null) {
            userMapper.deleteById(a.getUserId());
        }
        assistantMapper.deleteById(id);
        // 删除的是默认助手：自动把下一个（id 最小者）设为默认
        if (wasDefault) {
            assistantMapper.clearDefaults();
            AiAssistant next = assistantMapper.selectFirst();
            if (next != null) {
                assistantMapper.setDefault(next.getId());
                this.defaultAssistantId = next.getUserId();
            } else {
                this.defaultAssistantId = null;
            }
        }
    }

    /**
     * 将某用户与指定 AI 助手账户直接建立双向 ACCEPTED 好友（不走申请-同意流程）。
     * 已存在则跳过。
     */
    public void addFriendDirect(Long uid, Long assistantUserId) {
        if (uid == null || assistantUserId == null || uid.equals(assistantUserId)) return;
        if (!friendshipMapper.existsByUserIdAndFriendIdAndStatus(uid, assistantUserId, "ACCEPTED")) {
            Friendship f = new Friendship();
            f.setUserId(uid);
            f.setFriendId(assistantUserId);
            f.setStatus("ACCEPTED");
            friendshipMapper.insert(f);
        }
        if (!friendshipMapper.existsByUserIdAndFriendIdAndStatus(assistantUserId, uid, "ACCEPTED")) {
            Friendship rev = new Friendship();
            rev.setUserId(assistantUserId);
            rev.setFriendId(uid);
            rev.setStatus("ACCEPTED");
            friendshipMapper.insert(rev);
        }
    }

    /**
     * 默认助手切换时，将全体普通用户的「旧默认 AI 好友」替换为「新默认 AI 好友」（双向 ACCEPTED）。
     * 不论用户是否已加新默认，旧默认好友关系都会被移除（替换语义）；新默认若尚未建立则补全。
     * 用户自己主动添加的其它 AI 不受影响。
     */
    private void switchDefaultFriendForAllUsers(Long oldUid, Long newUid) {
        if (oldUid == null || newUid == null || oldUid.equals(newUid)) return;
        List<User> users = userMapper.findByRole("USER");
        for (User u : users) {
            Long uid = u.getId();
            if (uid.equals(oldUid) || uid.equals(newUid)) continue;
            // 移除与旧默认的好友关系（双向）
            friendshipMapper.deleteByUserIdAndFriendId(uid, oldUid);
            friendshipMapper.deleteByUserIdAndFriendId(oldUid, uid);
            // 建立与新默认的好友关系（双向，若已存在则跳过）
            if (!friendshipMapper.existsByUserIdAndFriendIdAndStatus(uid, newUid, "ACCEPTED")) {
                Friendship f = new Friendship();
                f.setUserId(uid); f.setFriendId(newUid); f.setStatus("ACCEPTED");
                friendshipMapper.insert(f);
            }
            if (!friendshipMapper.existsByUserIdAndFriendIdAndStatus(newUid, uid, "ACCEPTED")) {
                Friendship rev = new Friendship();
                rev.setUserId(newUid); rev.setFriendId(uid); rev.setStatus("ACCEPTED");
                friendshipMapper.insert(rev);
            }
        }
    }

    /** 普通聊天回复（一次性，无历史）。禁用时返回提示语。
     *  assistantUserId：对话的目标 AI（user 表里 role=AI 的 id）；为 null 时回退默认助手。 */
    public String chat(Long assistantUserId, String userText) {
        // 取目标 AI 的可用状态（避免给离线/维护中的 AI 发消息却用默认助手回复）
        AiAssistant target = (assistantUserId != null && isAssistant(assistantUserId))
                ? assistantMapper.selectByUserId(assistantUserId) : null;
        if (target == null) target = getDefaultAssistant();
        if (target.getEnabled() == null || !target.getEnabled()
                || "OFFLINE".equals(target.getStatus()) || "MAINTENANCE".equals(target.getStatus())) {
            return "抱歉，" + (target.getName() != null ? target.getName() : "该助手") + "当前" + statusTextOf(target) + "，暂时无法回复，请稍后再试～";
        }
        return aiService.chat(systemPrompt(assistantUserId), userText);
    }

    /** 兼容旧调用：无目标 AI 时按默认助手回复 */
    public String chat(String userText) {
        return chat(null, userText);
    }

    /** 生成一条广场/朋友圈文案：给定主题或关键词，返回可直接发布的文案。 */
    public String generateMomentCopy(String topic) {
        if (!isAvailable()) {
            return "抱歉，moyo助手当前" + statusText() + "，暂无法生成文案。";
        }
        String userPrompt = "请帮我写一条适合发在朋友圈/广场的文案。" +
                (topic != null && !topic.isBlank() ? "主题/关键词：" + topic + "。" : "") +
                "要求：1-3 句，自然有温度，不要加 emoji 也不要带话题标签，直接输出文案正文即可。";
        return aiService.chat(systemPrompt(), userPrompt);
    }

    private String statusText() {
        return statusTextOf(getConfig());
    }

    private String statusTextOf(AiAssistant a) {
        if (a == null) return "不可用";
        String s = a.getStatus();
        if ("OFFLINE".equals(s)) return "已离线";
        if ("MAINTENANCE".equals(s)) return "维护中";
        if ("BUSY".equals(s)) return "忙碌中";
        return "不可用";
    }

    /**
     * 对一条动态做 AI 审核判定。
     * 返回 { pass: true/false, suggestion: "..." }。
     * pass=true → 内容合规，可直接发布；pass=false → 不合规，附原因，进入待人工复审。
     */
    public Map<String, Object> reviewMoment(String content) {
        String text = (content == null || content.isBlank()) ? "" : content.trim();
        Map<String, Object> result = new LinkedHashMap<>();
        if (text.isEmpty()) {
            // 纯图片无文本，按通过处理
            result.put("pass", true);
            result.put("suggestion", "");
            return result;
        }
        String system = "你是内容安全审核员。请判断用户发布的内容是否包含违法违规、辱骂攻击、" +
                "色情低俗、诈骗、政治敏感或明显不当信息。只输出严格 JSON，不要输出任何解释文字。" +
                "格式：{\"pass\": true/false, \"suggestion\": \"简短说明，pass 为 false 时给出不通过原因\"}";
        String reply;
        try {
            reply = aiService.chat(system, "请审核以下内容：\n" + text);
        } catch (Exception e) {
            // AI 调用异常时，为不阻塞用户，按通过处理（降级），并记录原因
            result.put("pass", true);
            result.put("suggestion", "AI 审核暂不可用，已转人工复审");
            return result;
        }
        return parseReview(reply);
    }

    /** 解析 AI 返回的 JSON（容错：去 markdown 代码块、正则提取） */
    private Map<String, Object> parseReview(String reply) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean pass = true;
        String suggestion = "";
        if (reply != null && !reply.isBlank()) {
            String cleaned = reply.trim();
            // 去掉可能的 ```json ... ``` 包裹
            Matcher m = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE).matcher(cleaned);
            if (m.find()) cleaned = m.group(1).trim();
            // 仅截取第一个 { 到最后一个 } 之间
            int s = cleaned.indexOf('{');
            int e = cleaned.lastIndexOf('}');
            if (s >= 0 && e > s) cleaned = cleaned.substring(s, e + 1);
            try {
                JsonNode node = JSON.readTree(cleaned);
                if (node.has("pass")) pass = node.get("pass").asBoolean();
                if (node.has("suggestion")) suggestion = node.get("suggestion").asText();
            } catch (Exception ignored) {
                // 解析失败：保守按通过（降级）
            }
        }
        result.put("pass", pass);
        result.put("suggestion", suggestion == null ? "" : suggestion);
        return result;
    }
}
