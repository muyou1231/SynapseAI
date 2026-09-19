package com.synapseai.common;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 基于 Redis 的令牌存储（替换原内存版 {@code ConcurrentHashMap} 实现）。
 * 服务重启 / 多实例部署下登录态不再丢失，是后续所有依赖"可靠用户身份"的新功能
 * （时间胶囊定时解锁、消息炸弹倒计时、积分体系等）的地基，对外接口保持不变，
 * {@link com.synapseai.config.WebConfig} 与 {@link AuthChannelInterceptor} 无需改动。
 * <p>
 * 存储结构：
 * - {@code auth:token:{token}} -> userId，TTL 7 天（每次登录刷新，不做"访问续期"，避免每次请求都写 Redis）。
 * - {@code auth:user-tokens:{userId}} -> Set&lt;token&gt;，反向索引，用于 {@link #removeByUser} 精确踢人，
 *   避免用 KEYS/SCAN 全量扫描定位某用户名下的全部会话。
 */
@Component
public class TokenStore {

    private static final String TOKEN_PREFIX = "auth:token:";
    private static final String USER_TOKENS_PREFIX = "auth:user-tokens:";
    private static final Duration TOKEN_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;

    @Autowired
    public TokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String createToken(Long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(TOKEN_PREFIX + token, String.valueOf(userId), TOKEN_TTL);
        String userTokensKey = USER_TOKENS_PREFIX + userId;
        redisTemplate.opsForSet().add(userTokensKey, token);
        redisTemplate.expire(userTokensKey, TOKEN_TTL);
        return token;
    }

    public Long getUserId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String v = redisTemplate.opsForValue().get(TOKEN_PREFIX + token.trim());
        if (v == null) return null;
        try {
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void remove(String token) {
        if (token == null) return;
        String t = token.trim();
        String userIdStr = redisTemplate.opsForValue().get(TOKEN_PREFIX + t);
        redisTemplate.delete(TOKEN_PREFIX + t);
        if (userIdStr != null) {
            redisTemplate.opsForSet().remove(USER_TOKENS_PREFIX + userIdStr, t);
        }
    }

    /** 强制下线：移除某用户持有的全部令牌（踢掉其所有登录会话） */
    public void removeByUser(Long userId) {
        if (userId == null) return;
        String userTokensKey = USER_TOKENS_PREFIX + userId;
        Set<String> tokens = redisTemplate.opsForSet().members(userTokensKey);
        if (tokens != null && !tokens.isEmpty()) {
            List<String> keys = new ArrayList<>();
            for (String t : tokens) keys.add(TOKEN_PREFIX + t);
            redisTemplate.delete(keys);
        }
        redisTemplate.delete(userTokensKey);
    }

    /**
     * 当前持有有效令牌的用户 id（去重，近似"在线/近期活跃"），用于向全员广播通知等场景。
     * 用 SCAN 游标遍历 + MGET 批量取值，避免 KEYS 命令在大数据量下阻塞 Redis。
     */
    public Set<Long> onlineUserIds() {
        ScanOptions options = ScanOptions.scanOptions().match(TOKEN_PREFIX + "*").count(500).build();
        List<String> keys = redisTemplate.execute((RedisCallback<List<String>>) connection -> {
            List<String> ks = new ArrayList<>();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    ks.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return ks;
        });
        if (keys == null || keys.isEmpty()) {
            return Collections.emptySet();
        }
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        Set<Long> result = new LinkedHashSet<>();
        if (values != null) {
            for (String v : values) {
                if (v == null) continue;
                try {
                    result.add(Long.valueOf(v));
                } catch (NumberFormatException ignored) { /* 忽略脏数据 */ }
            }
        }
        return result;
    }
}
