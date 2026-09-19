package com.synapseai.common;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * WebSocket 连接拦截器：在 CONNECT 时根据 token 解析用户并绑定到会话。
 */
@Component
public class AuthChannelInterceptor implements ChannelInterceptor {

    private final TokenStore tokenStore;

    public AuthChannelInterceptor(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String token = accessor.getFirstNativeHeader("token");
            if (token != null) {
                Long userId = tokenStore.getUserId(token);
                if (userId != null) {
                    accessor.setUser(new AuthUser(userId));
                }
            }
        }
        return message;
    }
}
