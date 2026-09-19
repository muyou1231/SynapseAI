package com.synapseai.common;

import com.synapseai.entity.User;
import com.synapseai.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

/**
 * 冻结账号权限拦截器：在鉴权拦截器之后执行。
 * 冻结用户仅允许「查看通知 / 通讯 / 消息」相关接口，其余所有行为（建群、发朋友圈、改资料等）一律拒绝。
 * 管理员不受冻结限制。
 */
@Component
public class FrozenGuardInterceptor implements HandlerInterceptor {

    @Autowired
    private UserMapper userMapper;

    /** 冻结用户仍允许访问的路径前缀（任意方法），优先于 GET_ONLY 匹配 */
    private static final List<String> ALLOWED_PREFIXES = Arrays.asList(
            "/api/user/me",        // 加载会话 / 保持登录
            "/api/user/logout",    // 允许退出
            "/api/user/online",    // 允许同步在线状态（接收消息）
            "/api/user/switch",    // 允许切换账号
            "/api/user/search",    // 通讯：搜索
            "/api/notice/",        // 通知：查看
            "/api/message/",       // 消息：查看 + 收发
            "/api/friend/",        // 通讯：好友列表 / 申请 / 接受
            "/api/group/list",     // 消息（群）：查看我的群聊，以便打开群会话收发消息
            "/api/group/members",  // 消息（群）：查看群成员
            "/api/ws"              // WebSocket 握手（消息实时通道）
    );

    /** 其余 /api/user/ 下的请求：仅允许 GET（如查看资料），禁止 POST 改资料 */
    private static final String GET_ONLY_PREFIX = "/api/user/";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long uid = (Long) request.getAttribute("uid");
        if (uid == null) return true;                 // 白名单/未登录路径，交给鉴权拦截器
        User u = userMapper.selectById(uid);
        if (u == null) return true;
        if ("ADMIN".equals(u.getRole())) return true; // 管理员不受冻结限制
        if (!Boolean.TRUE.equals(u.getFrozen())) return true; // 未冻结放行

        String uri = request.getRequestURI();
        String method = request.getMethod();

        for (String p : ALLOWED_PREFIXES) {
            if (uri.startsWith(p)) return true;
        }
        if (uri.startsWith(GET_ONLY_PREFIX) && "GET".equals(method)) {
            return true; // 查看资料等只读操作允许
        }
        return deny(response);
    }

    private boolean deny(HttpServletResponse response) {
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        try (PrintWriter w = response.getWriter()) {
            w.write("{\"code\":403,\"message\":\"账号已被冻结，仅可查看通知、通讯与消息\"}");
        } catch (java.io.IOException ignored) { /* 忽略写出异常 */ }
        return false;
    }
}
