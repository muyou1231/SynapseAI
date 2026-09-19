package com.synapseai.service;

import com.synapseai.common.CodeStore;
import com.synapseai.common.TokenStore;
import com.synapseai.dto.LoginRequest;
import com.synapseai.dto.ProfileUpdateDto;
import com.synapseai.dto.RegisterRequest;
import com.synapseai.dto.SwitchRequest;
import com.synapseai.entity.Friendship;
import com.synapseai.entity.User;
import com.synapseai.entity.UserPrivacy;
import com.synapseai.mapper.FriendshipMapper;
import com.synapseai.mapper.UserMapper;
import com.synapseai.mapper.UserPrivacyMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Pattern;

@Service
public class UserService {

    /** 邮箱格式 */
    private static final Pattern EMAIL_RE = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    /** 密码强度：至少 1 字母 + 1 数字，长度 > 6（即 >= 7） */
    private static final Pattern PASSWORD_RE = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{7,}$");

    /** 支持单独设置可见范围的资料字段 */
    static final List<String> PRIVACY_FIELDS = List.of(
            "signature", "location", "hobbies", "gender", "age", "birthday", "religion", "education", "createDays");

    /** 各字段的默认可见范围（未设置时生效） */
    static final Map<String, String> DEFAULT_PRIVACY = Map.of(
            "signature", "PUBLIC",
            "location", "PUBLIC",
            "hobbies", "PUBLIC",
            "gender", "PUBLIC",
            "age", "PUBLIC",
            "birthday", "FRIENDS",
            "religion", "PRIVATE",
            "education", "FRIENDS",
            "createDays", "PUBLIC");

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserPrivacyMapper userPrivacyMapper;

    @Autowired
    private FriendshipMapper friendshipMapper;

    @Autowired
    private AiAssistantService aiAssistantService;

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private CodeStore codeStore;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private final Random random = new Random();

    /** 生成唯一的 10 位数字账号 */
    private String generateAccount() {
        String acc;
        do {
            StringBuilder sb = new StringBuilder(10);
            for (int i = 0; i < 10; i++) {
                sb.append(random.nextInt(10));
            }
            acc = sb.toString();
        } while (userMapper.existsByAccount(acc));
        return acc;
    }

    /** 生成一个新的唯一账号（>=10 位纯数字），供管理端「生成账号」按钮使用 */
    public String newAccount() {
        return generateAccount();
    }

    public Map<String, Object> register(RegisterRequest req) {
        String username = req.getUsername();
        String email = req.getEmail();
        String password = req.getPassword();
        String code = req.getCode();
        if (username == null || username.isBlank()) {
            throw new RuntimeException("用户名不能为空");
        }
        if (email == null || !EMAIL_RE.matcher(email.trim()).matches()) {
            throw new RuntimeException("邮箱格式不正确");
        }
        email = email.trim();
        if (!isPasswordValid(password)) {
            throw new RuntimeException("密码须同时包含字母和数字，且长度大于 6");
        }
        if (code == null || code.isBlank()) {
            throw new RuntimeException("请先获取并填写邮箱验证码");
        }
        // 必须经过邮箱验证：验证码正确且未过期（一次性消费）
        if (!codeStore.verify("register:" + email, code)) {
            throw new RuntimeException("邮箱验证码错误或已过期");
        }
        // 一个邮箱最多 3 个账号
        if (userMapper.countByEmail(email) >= 3) {
            throw new RuntimeException("该邮箱最多只能注册 3 个账号");
        }
        if (userMapper.existsByUsername(username)) {
            throw new RuntimeException("用户名已存在");
        }
        User u = new User();
        u.setUsername(username);
        u.setAccount(generateAccount());
        u.setPassword(password); // 演示用明文，生产请加密
        u.setEmail(email);
        u.setNickname(req.getNickname() != null && !req.getNickname().isBlank()
                ? req.getNickname() : username);
        u.setAvatar(req.getAvatar());
        userMapper.insert(u);
        Map<String, Object> result = new HashMap<>();
        result.put("token", tokenStore.createToken(u.getId()));
        result.put("user", toSafe(u));
        setOnline(u.getId(), true); // 注册即上线
        // 自动把默认 AI 助手加为好友，新用户开箱即可对话（按 is_default=1 定位，删除默认后自动切换到下一个）
        addDefaultAssistantAsFriend(u.getId());
        return result;
    }

    /** 密码强度校验：至少 1 字母 + 1 数字，长度 > 6 */
    public boolean isPasswordValid(String password) {
        return password != null && PASSWORD_RE.matcher(password).matches();
    }

    /**
     * 忘记密码（公开，无需登录）：凭邮箱验证码重置该邮箱下全部账号的密码。
     * 一个邮箱最多 3 个账号，重置时一并更新（邮箱持有者可控），重置后可用新密码登录其中任一账号。
     */
    public void resetPassword(String email, String code, String newPassword) {
        if (email == null || !EMAIL_RE.matcher(email.trim()).matches()) {
            throw new RuntimeException("邮箱格式不正确");
        }
        email = email.trim();
        if (!isPasswordValid(newPassword)) {
            throw new RuntimeException("新密码须同时包含字母和数字，且长度大于 6");
        }
        // 验证码正确且未过期（一次性消费），命名空间 resetpwd 与注册/改密隔离
        if (!codeStore.verify("resetpwd:" + email, code != null ? code : "")) {
            throw new RuntimeException("邮箱验证码错误或已过期");
        }
        List<User> users = userMapper.findByEmailList(email);
        if (users.isEmpty()) {
            throw new RuntimeException("该邮箱未注册任何账号");
        }
        for (User u : users) {
            userMapper.updatePasswordById(u.getId(), newPassword);
        }
    }

    /**
     * 绑定 / 换绑邮箱（需登录 + 新邮箱验证码，命名空间 bind 与注册/改密/重置隔离）。
     *  - 首次绑定（此前 email 为空）：必须同时设置符合格式的新密码（适配旧账号弱密码，
     *    满足「第一次绑定后要求更换密码以匹配现在的密码格式」），更新 email + password。
     *  - 换绑（此前已有邮箱）：仅需新邮箱验证码，密码不变，仅更新 email。
     * 一个邮箱最多 3 个账号；与自己当前邮箱相同则视为无变化直接返回。
     */
    @CacheEvict(value = "userSafe", key = "#uid")
    public Map<String, Object> bindEmail(Long uid, String email, String code, String newPassword) {
        if (email == null || !EMAIL_RE.matcher(email.trim()).matches()) {
            throw new RuntimeException("邮箱格式不正确");
        }
        email = email.trim();
        User u = userMapper.selectById(uid);
        if (u == null) {
            throw new RuntimeException("用户不存在");
        }
        // 与当前邮箱相同：无变化
        if (email.equals(u.getEmail())) {
            Map<String, Object> same = new HashMap<>();
            same.put("firstBind", false);
            same.put("changed", false);
            same.put("email", email);
            return same;
        }
        // 新邮箱验证码（一次性消费）
        if (!codeStore.verify("bind:" + email, code != null ? code : "")) {
            throw new RuntimeException("邮箱验证码错误或已过期");
        }
        // 一个邮箱最多 3 个账号（排除自身当前邮箱）
        int cnt = userMapper.countByEmail(email);
        if (u.getEmail() != null && u.getEmail().equals(email)) cnt -= 1; // 理论不会命中（前面已等于返回），保险
        if (cnt >= 3) {
            throw new RuntimeException("该邮箱已绑定 3 个账号，无法再绑定");
        }
        boolean firstBind = (u.getEmail() == null || u.getEmail().isEmpty());
        if (firstBind) {
            // 首次绑定：强制设置符合格式的新密码（旧账号弱密码在此统一升级）
            if (!isPasswordValid(newPassword)) {
                throw new RuntimeException("首次绑定邮箱需同时设置新密码：须包含字母和数字，且长度大于 6");
            }
            userMapper.updateEmailById(uid, email);
            userMapper.updatePasswordById(uid, newPassword);
        } else {
            userMapper.updateEmailById(uid, email);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("firstBind", firstBind);
        result.put("changed", true);
        result.put("email", email);
        return result;
    }

    public Map<String, Object> login(LoginRequest req) {
        String identifier = req.getAccount();
        if (identifier == null || identifier.isBlank()) {
            throw new RuntimeException("账号不能为空");
        }
        // 邮箱登录：一个邮箱可能对应多个账号，按密码逐个匹配定位具体账号
        if (identifier.contains("@")) {
            for (User u : userMapper.findByEmailList(identifier.trim())) {
                if (u.getPassword().equals(req.getPassword())) {
                    return loginAs(u);
                }
            }
            throw new RuntimeException("邮箱或密码错误");
        }
        User u = userMapper.findByAccount(identifier);
        if (u == null) {
            u = userMapper.findByUsername(identifier); // 兼容用用户名登录（便于管理员使用）
        }
        if (u == null) {
            throw new RuntimeException("账号不存在");
        }
        // 冻结用户仍可登录：登录后所有受限操作由 FrozenGuardInterceptor 兜底拦截，
        // 仅允许查看通知、通讯与消息，朋友圈等其余功能禁用。
        if (!u.getPassword().equals(req.getPassword())) {
            throw new RuntimeException("密码错误");
        }
        return loginAs(u);
    }

    /** 统一构建登录结果（签发 token + 资料 + 置在线） */
    private Map<String, Object> loginAs(User u) {
        Map<String, Object> result = new HashMap<>();
        result.put("token", tokenStore.createToken(u.getId()));
        result.put("user", toSafe(u));
        setOnline(u.getId(), true); // 登录即上线
        return result;
    }

    public User getById(Long id) {
        return userMapper.selectById(id);
    }

    /**
     * 返回不含密码的用户信息（带 Redis 缓存，key=用户 id）。
     * 热点读路径（消息发送、通知、朋友圈、群成员等）统一走这里，降低 DB 压力。
     * 注意：密码绝不进入缓存。更新资料/冻结等写操作会通过 @CacheEvict("userSafe") 失效保证一致性。
     */
    @Cacheable(value = "userSafe", key = "#id")
    public Map<String, Object> getSafeUser(Long id) {
        return toSafe(getById(id));
    }

    /** 向指定用户实时推送一条 WS 通知（写入 /topic/user/{uid}） */
    public void notifyUser(Long uid, Map<String, Object> msg) {
        messagingTemplate.convertAndSend("/topic/user/" + uid, msg);
    }

    /** 设置在线状态并广播给所有好友（上线 true / 下线 false） */
    public void setOnline(Long uid, boolean online) {
        userMapper.updateOnline(uid, online);
        broadcastPresence(uid, online);
    }

    /** 向所有好友推送在线状态变更（type=PRESENCE） */
    private void broadcastPresence(Long uid, boolean online) {
        for (Friendship f : friendshipMapper.findAcceptedByUser(uid)) {
            Long other = f.getUserId().equals(uid) ? f.getFriendId() : f.getUserId();
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "PRESENCE");
            msg.put("userId", uid);
            msg.put("online", online);
            messagingTemplate.convertAndSend("/topic/user/" + other, msg);
        }
    }

    /**
     * 切换账号：校验新账号 ≠ 当前账号，旧账号下线、新账号上线，返回新账号的 token + 资料。
     * 若账号与当前账号相同则拒绝（前端也会拦截）。
     */
    public Map<String, Object> switchAccount(Long oldUid, SwitchRequest req) {
        User old = userMapper.selectById(oldUid);
        User neu = userMapper.findByAccount(req.getAccount());
        if (neu == null) {
            throw new RuntimeException("账号不存在");
        }
        if (!neu.getPassword().equals(req.getPassword())) {
            throw new RuntimeException("密码错误");
        }
        if (old != null && old.getAccount().equals(req.getAccount())) {
            throw new RuntimeException("不能切换到当前账号");
        }
        if (old != null) setOnline(oldUid, false); // 旧账号下线（广播给其好友）
        setOnline(neu.getId(), true);              // 新账号上线（广播给其好友）
        Map<String, Object> result = new HashMap<>();
        result.put("token", tokenStore.createToken(neu.getId()));
        result.put("user", toSafe(neu));
        return result;
    }

    /** 更新个人信息：仅覆盖请求中提供的非空字段，其余保持不变（密码/账号/用户名不可经此修改） */
    @CacheEvict(value = "userSafe", key = "#uid")
    public Map<String, Object> updateProfile(Long uid, ProfileUpdateDto dto) {
        User u = userMapper.selectById(uid);
        if (u == null) {
            throw new RuntimeException("用户不存在");
        }
        if (dto.getNickname() != null) u.setNickname(dto.getNickname());
        if (dto.getAvatar() != null) u.setAvatar(dto.getAvatar());
        if (dto.getGender() != null) u.setGender(dto.getGender());
        if (dto.getAge() != null) u.setAge(dto.getAge());
        if (dto.getReligion() != null) u.setReligion(dto.getReligion());
        if (dto.getEducation() != null) u.setEducation(dto.getEducation());
        if (dto.getBirthday() != null) u.setBirthday(dto.getBirthday());
        if (dto.getSignature() != null) u.setSignature(dto.getSignature());
        if (dto.getLocation() != null) u.setLocation(dto.getLocation());
        if (dto.getHobbies() != null) u.setHobbies(dto.getHobbies());
        // 邮箱绑定：null=跳过；""=清空；非空=校验格式与「最多 3 个账号」后绑定
        if (dto.getEmail() != null) {
            String email = dto.getEmail().trim();
            if (email.isEmpty()) {
                u.setEmail(null);
            } else {
                if (!EMAIL_RE.matcher(email).matches()) {
                    throw new RuntimeException("邮箱格式不正确");
                }
                // 一个邮箱最多绑定 3 个账号（若当前账号已绑该邮箱，则不重复计数）
                int cnt = userMapper.countByEmail(email);
                if (u.getEmail() != null && u.getEmail().equals(email)) {
                    cnt -= 1;
                }
                if (cnt >= 3) {
                    throw new RuntimeException("该邮箱已绑定 3 个账号，无法再绑定");
                }
                u.setEmail(email);
            }
        }
        userMapper.updateProfile(u);

        // 保存字段可见范围设置
        if (dto.getPrivacy() != null) {
            for (Map.Entry<String, String> e : dto.getPrivacy().entrySet()) {
                final String field = e.getKey();
                final String vis = e.getValue();
                if (!PRIVACY_FIELDS.contains(field)) continue;
                if (!isValidVisibility(vis)) continue;
                userPrivacyMapper.upsert(uid, field, vis);
            }
        }
        return getProfileView(uid, uid);
    }

    private boolean isValidVisibility(String vis) {
        return "PUBLIC".equals(vis) || "FRIENDS".equals(vis) || "PRIVATE".equals(vis);
    }

    /**
     * 构建「他人/本人查看」的资料视图。
     * 根据每个字段的可见范围（公开/仅好友/仅自己）与查看者和主人的好友关系过滤：
     *  - 本人：返回全部字段 + 当前可见范围设置
     *  - 好友：公开 + 仅好友可见的字段
     *  - 陌生人：仅公开字段
     */
    public Map<String, Object> getProfileView(Long ownerId, Long viewerId) {
        User u = userMapper.selectById(ownerId);
        if (u == null) {
            throw new RuntimeException("用户不存在");
        }
        final boolean self = ownerId.equals(viewerId);

        // 加载主人设置的可见范围
        Map<String, String> privacy = new HashMap<>();
        for (UserPrivacy p : userPrivacyMapper.selectByUserId(ownerId)) {
            privacy.put(p.getField(), p.getVisibility());
        }

        // 是否好友（仅用于非本人）
        final boolean isFriend = self || friendshipMapper.existsByUserIdAndFriendIdAndStatus(viewerId, ownerId, "ACCEPTED");

        // 创建天数
        int createDays = 0;
        if (u.getCreateTime() != null) {
            createDays = (int) ChronoUnit.DAYS.between(u.getCreateTime().toLocalDate(), LocalDate.now());
            if (createDays < 0) createDays = 0;
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        List<String> hidden = new ArrayList<>();
        for (String field : PRIVACY_FIELDS) {
            String level = privacy.getOrDefault(field, DEFAULT_PRIVACY.get(field));
            boolean visible = self || "PUBLIC".equals(level) || ("FRIENDS".equals(level) && isFriend);
            if (!visible) {
                hidden.add(field);
                continue;
            }
            Object val = switch (field) {
                case "signature" -> u.getSignature();
                case "location" -> u.getLocation();
                case "hobbies" -> u.getHobbies();
                case "gender" -> u.getGender();
                case "age" -> u.getAge();
                case "birthday" -> u.getBirthday();
                case "religion" -> u.getReligion();
                case "education" -> u.getEducation();
                case "createDays" -> createDays;
                default -> null;
            };
            fields.put(field, val);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", u.getId());
        result.put("account", u.getAccount());
        result.put("username", u.getUsername());
        result.put("nickname", u.getNickname());
        result.put("avatar", u.getAvatar());
        result.put("self", self);
        result.put("isFriend", isFriend);
        result.put("online", u.getOnline());
        result.put("role", u.getRole());
        result.put("fields", fields);
        result.put("hidden", hidden);
        if (self) result.put("privacy", privacy);
        return result;
    }

    public List<Map<String, Object>> search(String kw) {
        if (kw == null || kw.isBlank()) {
            return List.of();
        }
        // 用户端搜索排除 AI 助手账户：AI 助手不出现在搜索结果，不能被主动加好友或拉黑
        List<User> users = userMapper.findByUsernameContainingOrNicknameContainingOrAccountContaining(kw, kw, kw);
        return users.stream()
                .filter(u -> !"AI".equals(u.getRole()))
                .map(this::toSafe)
                .toList();
    }

    /**
     * 搜索可添加的 AI 助手：按账号/用户名/昵称匹配 role=AI 账户。
     * 不排除默认助手——所有 AI（含默认）都可通过「添加好友」搜索并添加；标记 isFriend 以便前端区分「加好友」/「已是好友」。
     */
    public List<Map<String, Object>> searchAssistant(String kw, Long uid) {
        if (kw == null || kw.isBlank()) {
            return List.of();
        }
        List<User> users = userMapper.findByUsernameContainingOrNicknameContainingOrAccountContaining(kw, kw, kw);
        return users.stream()
                .filter(u -> "AI".equals(u.getRole()))
                .filter(u -> !u.getId().equals(uid))                       // 排除自己
                .map(u -> {
                    Map<String, Object> m = toSafe(u);
                    boolean friend = friendshipMapper.existsByUserIdAndFriendIdAndStatus(uid, u.getId(), "ACCEPTED");
                    m.put("isFriend", friend);
                    return m;
                })
                .toList();
    }

    /** 返回不含密码的用户信息 */
    public Map<String, Object> toSafe(User u) {
        if (u == null) {
            return null;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("account", u.getAccount());
        m.put("email", u.getEmail());
        m.put("nickname", u.getNickname());
        m.put("avatar", u.getAvatar());
        m.put("gender", u.getGender());
        m.put("age", u.getAge());
        m.put("birthday", u.getBirthday());
        m.put("religion", u.getReligion());
        m.put("education", u.getEducation());
        m.put("signature", u.getSignature());
        m.put("location", u.getLocation());
        m.put("hobbies", u.getHobbies());
        m.put("online", u.getOnline());
        m.put("role", u.getRole());
        m.put("frozen", Boolean.TRUE.equals(u.getFrozen()));
        // 标记 moyo AI 助手账户（前端据此显示 AI 标识、特殊处理聊天/在线状态）
        boolean assistant = aiAssistantService.isAssistant(u.getId());
        m.put("isAssistant", assistant);
        // AI 助手：附带自身运行状态（在线/离线/维修/忙碌），用于头像右下角状态点着色
        if (assistant) {
            m.put("aiStatus", aiAssistantService.getStatusByUserId(u.getId()));
        }
        return m;
    }

    public Optional<User> findByUsername(String username) {
        return Optional.ofNullable(userMapper.findByUsername(username));
    }

    /** 判断账号是否被冻结 */
    public boolean isFrozen(Long uid) {
        User u = userMapper.selectById(uid);
        return u != null && Boolean.TRUE.equals(u.getFrozen());
    }

    /**
     * 强制下线：彻底删除该用户的登录态——置离线 + 广播给好友 + 移除全部登录令牌（使其后续请求鉴权失败），
     * 并通过 WS 实时推送 KICK，前端收到后立即回到登录页（客户端据此断开 WS 并清除本地会话）。
     */
    public void forceOffline(Long uid) {
        User u = userMapper.selectById(uid);
        if (u == null) return;
        setOnline(uid, false);              // 置离线 + 广播 PRESENCE(offline) 给好友
        tokenStore.removeByUser(uid);       // 踢掉所有会话令牌（登录态被删除，后续 REST 鉴权失败）
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "KICK");
        msg.put("reason", "您的账号已被管理员强制下线");
        messagingTemplate.convertAndSend("/topic/user/" + uid, msg);
    }

    /**
     * 确保管理员账号存在且为 ADMIN 角色（应用启动时由 AdminBootstrap 调用）。
     * 不存在则创建（role=ADMIN）；已存在但角色非 ADMIN 则升级为 ADMIN。
     */
    public void ensureAdmin(String username, String password) {
        User admin = userMapper.findByUsername(username);
        if (admin == null) {
            admin = new User();
            admin.setUsername(username);
            admin.setAccount(generateAccount());
            admin.setPassword(password);
            admin.setNickname("系统管理员");
            admin.setRole("ADMIN");
            userMapper.insert(admin);
        } else if (!"ADMIN".equals(admin.getRole())) {
            userMapper.updateRole(admin.getId(), "ADMIN");
        }
    }

    /**
     * 确保 moyo AI 助手账户存在（独立 AI 角色账户，role=AI，由管理员在管理端统一维护）。
     * 不存在则创建；返回其 userId。助手名/头像由 ai_assistant 配置驱动，这里仅保证底层账户存在。
     */
    public Long ensureAssistant(String username, String password, String nickname) {
        User a = userMapper.findByUsername(username);
        if (a == null) {
            a = new User();
            a.setUsername(username);
            a.setAccount(generateAccount());
            a.setPassword(password);
            a.setNickname(nickname);
            a.setRole("AI");
            a.setSignature("你的智能伙伴");
            userMapper.insert(a);
        } else if (!"AI".equals(a.getRole())) {
            // 存量账户若仍是旧 USER 角色，升级为 AI
            userMapper.updateRole(a.getId(), "AI");
        }
        return a.getId();
    }

    /**
     * 给指定用户自动添加当前默认 AI 助手为好友（ACCEPTED，双向）。
     * 默认助手按 is_default=1 定位（删除默认后自动切换到下一个），不再依赖固定 username。
     * 若该好友关系已存在则跳过。
     */
    public void addDefaultAssistantAsFriend(Long uid) {
        Long aid = aiAssistantService.getDefaultUserId();
        if (aid == null || aid.equals(uid)) return;
        if (!friendshipMapper.existsByUserIdAndFriendIdAndStatus(uid, aid, "ACCEPTED")) {
            Friendship f = new Friendship();
            f.setUserId(uid);
            f.setFriendId(aid);
            f.setStatus("ACCEPTED");
            friendshipMapper.insert(f);
        }
        if (!friendshipMapper.existsByUserIdAndFriendIdAndStatus(aid, uid, "ACCEPTED")) {
            Friendship rev = new Friendship();
            rev.setUserId(aid);
            rev.setFriendId(uid);
            rev.setStatus("ACCEPTED");
            friendshipMapper.insert(rev);
        }
    }

    /**
     * 给指定用户自动添加 moyo 助手为好友（ACCEPTED，双向）。
     * 若该好友关系已存在则跳过。助手账户由 username 定位。
     */
    public void addAssistantAsFriend(Long uid, String assistantUsername) {
        User a = userMapper.findByUsername(assistantUsername);
        if (a == null || a.getId().equals(uid)) return;
        Long aid = a.getId();
        if (!friendshipMapper.existsByUserIdAndFriendIdAndStatus(uid, aid, "ACCEPTED")) {
            Friendship f = new Friendship();
            f.setUserId(uid);
            f.setFriendId(aid);
            f.setStatus("ACCEPTED");
            friendshipMapper.insert(f);
        }
        if (!friendshipMapper.existsByUserIdAndFriendIdAndStatus(aid, uid, "ACCEPTED")) {
            Friendship rev = new Friendship();
            rev.setUserId(aid);
            rev.setFriendId(uid);
            rev.setStatus("ACCEPTED");
            friendshipMapper.insert(rev);
        }
    }

    /** 全部用户 id（用于启动时给存量用户批量添加助手好友） */
    public List<Long> allUserIds() {
        return userMapper.selectAllIds();
    }
}
