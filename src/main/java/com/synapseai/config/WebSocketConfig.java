package com.synapseai.config;

import com.synapseai.common.AuthChannelInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AuthChannelInterceptor authChannelInterceptor;

    public WebSocketConfig(AuthChannelInterceptor authChannelInterceptor) {
        this.authChannelInterceptor = authChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // SockJS 端点：前端连接 /ws
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 客户端订阅前缀
        // 说明：本工程基于 Spring Boot 3.0.2（Spring 6.0），SimpleBrokerRegistration 尚无 setHeartbeat 方法，
        // 故「连接保活」改在前端 Stomp 客户端以 { outgoing:15000, incoming:0 } 主动发心跳帧实现（见 ws.js），
        // 既能让移动端 NAT/运营商在空闲时不回收连接，又因不强求服务端回心跳而避免客户端自断。
        registry.enableSimpleBroker("/topic");
        // 客户端发送消息前缀（对应 @MessageMapping("chat.send") -> /app/chat.send）
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // 图片以 base64 内嵌，放宽消息体大小限制（默认 64KB 不够）
        registration.setMessageSizeLimit(10 * 1024 * 1024);
        registration.setSendBufferSizeLimit(10 * 1024 * 1024);
        registration.setSendTimeLimit(20000);
    }
}
