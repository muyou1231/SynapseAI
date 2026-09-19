package com.synapseai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis 缓存配置。
 * - 统一用 JSON 序列化（GenericJackson2JsonRedisSerializer），缓存值含 Map/List 也能正确往返。
 * - 分缓存设置 TTL（兜底防雪崩 / 控制一致性窗口）：
 *     userSafe    默认 10min（用户安全信息，含在线态，过期后有 WS 在线事件兜底）
 *     momentFeed  3min   （广场公开流，写操作会主动失效）
 *     userNotices 30s    （通知中心/铃铛角标，已读/新通知主动失效，ALL 广播靠 TTL 兜底）
 * - CacheErrorHandler 容错：Redis 不可用（断连/超时）时仅记日志、不抛异常，
 *   读操作降级为回源 DB、写/失效操作静默忽略，业务不受影响（最终一致性退化为无缓存）。
 */
@Configuration
@EnableCaching
public class CacheConfig implements org.springframework.cache.annotation.CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);
    private static final String PREFIX = "sc:v2:";

    private final RedisConnectionFactory redisConnectionFactory;

    public CacheConfig(RedisConnectionFactory redisConnectionFactory) {
        this.redisConnectionFactory = redisConnectionFactory;
    }

    @Override
    @Bean
    public CacheManager cacheManager() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer(om);

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .prefixCacheNameWith(PREFIX)
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer))
                .disableCachingNullValues();

        RedisCacheConfiguration feedCfg = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(3))
                .prefixCacheNameWith(PREFIX)
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer))
                .disableCachingNullValues();

        RedisCacheConfiguration noticeCfg = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(30))
                .prefixCacheNameWith(PREFIX)
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer))
                .disableCachingNullValues();

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(base)
                .withCacheConfiguration("momentFeed", feedCfg)
                .withCacheConfiguration("userNotices", noticeCfg)
                .build();
    }

    @Override
    public KeyGenerator keyGenerator() {
        return new SimpleKeyGenerator();
    }

    @Override
    public org.springframework.cache.interceptor.CacheResolver cacheResolver() {
        return null;
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("[cache] GET 失败，降级回源 DB（cache={}, key={}）: {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("[cache] PUT 失败，已忽略（cache={}, key={}）: {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("[cache] EVICT 失败，已忽略（cache={}, key={}）: {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("[cache] CLEAR 失败，已忽略: {}", e.getMessage());
            }
        };
    }
}
