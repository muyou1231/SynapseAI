package com.synapseai.common;

import java.security.Principal;

/**
 * WebSocket 连接鉴权后的用户身份（携带 userId）
 */
public class AuthUser implements Principal {

    private final Long userId;

    public AuthUser(Long userId) {
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
