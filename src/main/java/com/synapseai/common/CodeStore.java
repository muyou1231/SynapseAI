package com.synapseai.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邮箱验证码存储（内存版，单实例演示用）。
 * 生产环境建议替换为 Redis（带 TTL），以支持多实例部署。
 */
@Component
public class CodeStore {

    private static class Entry {
        String code;
        long expireAt;
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public CodeStore(@Value("${app.verify-code.expire-seconds:300}") int expireSeconds) {
        this.ttlMillis = expireSeconds * 1000L;
    }

    /** 保存验证码（覆盖同名邮箱旧码） */
    public void save(String email, String code) {
        Entry e = new Entry();
        e.code = code;
        e.expireAt = System.currentTimeMillis() + ttlMillis;
        store.put(email, e);
    }

    /** 校验验证码：成功则立即消费（一次性）；失败/过期返回 false */
    public boolean verify(String email, String code) {
        Entry e = store.get(email);
        if (e == null) return false;
        if (System.currentTimeMillis() > e.expireAt) {
            store.remove(email);
            return false;
        }
        if (!e.code.equals(code)) return false;
        store.remove(email);
        return true;
    }
}
