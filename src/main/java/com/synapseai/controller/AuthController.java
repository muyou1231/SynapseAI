package com.synapseai.controller;

import com.synapseai.common.CodeStore;
import com.synapseai.common.Result;
import com.synapseai.common.TokenStore;
import com.synapseai.entity.User;
import com.synapseai.mapper.UserMapper;
import com.synapseai.service.EmailService;
import com.synapseai.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 邮箱验证码相关接口（对未登录用户公开 send-code / verify-code / reset-password / code-config）：
 *  - POST /api/auth/send-code    {email, type}       发送验证码邮件；type ∈ {register, changepwd, login, resetpwd, bind}
 *  - POST /api/auth/verify-code  {email, code, type} 校验验证码；register/changepwd/resetpwd/bind 仅标记已验证(不签发 token)，login 校验通过签发登录 token
 *  - POST /api/auth/change-password {email, code, newPassword}  修改密码（需登录 + 邮箱验证码）
 *  - POST /api/auth/reset-password   {email, code, newPassword} 忘记密码重置（公开 + 邮箱验证码）
 *  - POST /api/auth/bind-email       {email, code, newPassword?} 绑定/换绑邮箱（需登录 + 新邮箱验证码；首次绑定须带新密码）
 *  - GET  /api/auth/code-config                           返回验证码重新发送冷却秒数（前端倒计时依据）
 * 注册/改密/重置/绑定场景的验证码以 type 命名空间隔离，无法跨场景使用。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Pattern EMAIL_RE = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

    private final UserMapper userMapper;
    private final EmailService emailService;
    private final CodeStore codeStore;
    private final TokenStore tokenStore;
    private final UserService userService;

    @Value("${app.verify-code.length:6}")
    private int codeLength;

    @Value("${app.verify-code.expire-seconds:300}")
    private int expireSeconds;

    @Value("${app.verify-code.resend-seconds:60}")
    private int resendSeconds;

    public AuthController(UserMapper userMapper, EmailService emailService, CodeStore codeStore,
                          TokenStore tokenStore, UserService userService) {
        this.userMapper = userMapper;
        this.emailService = emailService;
        this.codeStore = codeStore;
        this.tokenStore = tokenStore;
        this.userService = userService;
    }

    /** 返回验证码统一配置（前端倒计时冷却秒数等），无需登录 */
    @GetMapping("/code-config")
    public Result<?> codeConfig() {
        Map<String, Object> data = new HashMap<>();
        data.put("cooldown", resendSeconds);
        data.put("expireSeconds", expireSeconds);
        return Result.ok(data);
    }

    @PostMapping("/send-code")
    public Result<?> sendCode(@RequestBody Map<String, String> body) {
        String email = body == null ? null : body.get("email");
        String type = body == null ? null : body.get("type");
        if (type == null) type = "login";
        if (email == null || !EMAIL_RE.matcher(email.trim()).matches()) {
            return Result.error("邮箱格式不正确");
        }
        email = email.trim();
        // register / changepwd / resetpwd：发送给任意合法邮箱（注册/改密/重置场景，邮箱不必已绑定）
        // login：邮箱验证码登录，要求邮箱已绑定账号
        if ("login".equals(type)) {
            User u = userMapper.findByEmail(email);
            if (u == null) {
                return Result.error("该邮箱尚未绑定账号，请先到「个人资料」中绑定邮箱");
            }
        }
        String code = generateCode();
        codeStore.save(type + ":" + email, code);
        try {
            emailService.sendCode(email, code, type);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
        // 返回冷却秒数，前端依此显示重新发送倒计时（统一由后端配置驱动）
        Map<String, Object> data = new HashMap<>();
        data.put("cooldown", resendSeconds);
        data.put("message", "验证码已发送至 " + maskEmail(email));
        return Result.ok(data);
    }

    @PostMapping("/verify-code")
    public Result<?> verifyCode(@RequestBody Map<String, String> body) {
        String email = body == null ? null : body.get("email");
        String code = body == null ? null : body.get("code");
        String type = body == null ? null : body.get("type");
        if (type == null) type = "login";
        if (email == null || code == null || !EMAIL_RE.matcher(email.trim()).matches()) {
            return Result.error("邮箱或验证码格式不正确");
        }
        email = email.trim();
        if (!codeStore.verify(type + ":" + email, code)) {
            return Result.error("验证码错误或已过期");
        }
        // 注册 / 改密：仅校验通过，不签发 token（注册/改密接口会再次校验验证码）
        if (!"login".equals(type)) {
            return Result.ok("邮箱验证通过");
        }
        // 邮箱验证码登录：校验通过签发登录 token
        User u = userMapper.findByEmail(email);
        if (u == null) {
            return Result.error("该邮箱尚未绑定账号");
        }
        userService.setOnline(u.getId(), true);
        Map<String, Object> result = new HashMap<>();
        result.put("token", tokenStore.createToken(u.getId()));
        result.put("user", userService.getSafeUser(u.getId()));
        return Result.ok(result);
    }

    /**
     * 修改密码（需登录）：校验当前账号绑定邮箱的验证码 + 新密码强度后改密。
     * 不要求原密码——邮箱验证码即身份验证（兼具「忘记密码」能力）。
     */
    @PostMapping("/change-password")
    public Result<?> changePassword(@RequestAttribute("uid") Long uid,
                                    @RequestBody Map<String, String> body) {
        String email = body == null ? null : body.get("email");
        String code = body == null ? null : body.get("code");
        String newPassword = body == null ? null : body.get("newPassword");
        if (email == null || !EMAIL_RE.matcher(email.trim()).matches()) {
            return Result.error("邮箱格式不正确");
        }
        email = email.trim();
        User u = userMapper.selectById(uid);
        if (u == null) {
            return Result.error("用户不存在");
        }
        if (u.getEmail() == null || !u.getEmail().equals(email)) {
            return Result.error("邮箱与当前账号不符（请在个人资料中确认绑定的邮箱）");
        }
        if (!userService.isPasswordValid(newPassword)) {
            return Result.error("新密码须同时包含字母和数字，且长度大于 6");
        }
        if (!codeStore.verify("changepwd:" + email, code != null ? code : "")) {
            return Result.error("邮箱验证码错误或已过期");
        }
        u.setPassword(newPassword);
        userMapper.updateById(u);
        return Result.ok("密码修改成功，请使用新密码登录");
    }

    /**
     * 忘记密码（公开，无需登录）：凭邮箱验证码重置该邮箱下全部账号的密码。
     * 邮箱未注册 / 验证码错误 / 新密码强度不足均返回错误。
     */
    @PostMapping("/reset-password")
    public Result<?> resetPassword(@RequestBody Map<String, String> body) {
        String email = body == null ? null : body.get("email");
        String code = body == null ? null : body.get("code");
        String newPassword = body == null ? null : body.get("newPassword");
        try {
            userService.resetPassword(email, code, newPassword);
            return Result.ok("密码重置成功，请使用新密码登录");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 绑定 / 换绑邮箱（需登录）：凭「新邮箱」验证码绑定；首次绑定（此前无邮箱）须同时传符合格式的新密码。
     * 返回 { firstBind, changed, email }，前端据此提示与刷新会话。
     */
    @PostMapping("/bind-email")
    public Result<?> bindEmail(@RequestAttribute("uid") Long uid,
                               @RequestBody Map<String, String> body) {
        String email = body == null ? null : body.get("email");
        String code = body == null ? null : body.get("code");
        String newPassword = body == null ? null : body.get("newPassword");
        try {
            Map<String, Object> result = userService.bindEmail(uid, email, code, newPassword);
            return Result.ok(result);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    private String generateCode() {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codeLength; i++) {
            sb.append(rnd.nextInt(10));
        }
        return sb.toString();
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return email;
        return email.charAt(0) + "***" + email.substring(at);
    }
}
