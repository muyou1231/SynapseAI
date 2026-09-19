package com.synapseai.config;

import com.synapseai.common.FrozenGuardInterceptor;
import com.synapseai.common.TokenStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.PrintWriter;

/**
 * REST 接口鉴权：除登录/注册外，所有 /api/** 必须携带 X-Token 头。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TokenStore tokenStore;

    @Autowired
    private FrozenGuardInterceptor frozenGuard;

    public WebConfig(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                String uri = request.getRequestURI();
                // 文件代理访问（/api/files/**）公开：供 <img> 直接加载，走 8080 穿透，不校验 Token
                if (uri.startsWith("/api/files/")) {
                    return true;
                }
                // 登录 / 注册 放行
                if (uri.endsWith("/api/user/login") || uri.endsWith("/api/user/register")) {
                    return true;
                }
                // 邮箱验证码相关（准备阶段）：发送/校验/忘记密码重置/配置查询 均对未登录用户公开
                if (uri.endsWith("/api/auth/send-code") || uri.endsWith("/api/auth/verify-code")
                        || uri.endsWith("/api/auth/reset-password") || uri.endsWith("/api/auth/code-config")) {
                    return true;
                }
                if (!uri.startsWith("/api/")) {
                    return true;
                }
                String token = request.getHeader("X-Token");
                if (token == null || token.isBlank()) {
                    token = request.getHeader("Authorization");
                }
                Long uid = tokenStore.getUserId(token);
                if (uid == null) {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    try (PrintWriter w = response.getWriter()) {
                        w.write("{\"code\":401,\"message\":\"未登录或登录已过期\"}");
                    }
                    return false;
                }
                request.setAttribute("uid", uid);
                return true;
            }
        }).addPathPatterns("/api/**");

        // 冻结账号权限拦截（在鉴权之后）：冻结用户仅可访问通知/通讯/消息相关接口
        registry.addInterceptor(frozenGuard).addPathPatterns("/api/**");
    }
}
