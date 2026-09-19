package com.synapseai.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 时间胶囊正文加密工具（AES-256-GCM）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>密钥不是配置文件里的明文，而是对 {@code app.capsule.secret} 做 SHA-256 摘要得到 32 字节，
 *       因此配置文件只需放一个任意长度的口令串，不会因长度不合法导致启动失败。</li>
 *   <li>每条密文使用独立的 12 字节随机 IV，密文格式为 Base64(IV ‖ ciphertext+tag)，
 *       解密时从头部切出 IV。IV 不要求保密，但绝不复用，这是 GCM 模式的安全底线。</li>
 *   <li>GCM 自带完整性校验（tag 128 位），密文被篡改时 decrypt 抛异常而非解出乱码。</li>
 * </ul>
 *
 * <p>注意：这是"应用级单一主密钥"方案，适用于 MVP。生产环境应把 {@code app.capsule.secret}
 * 托管到云 KMS，或改为按用户派生子密钥（HKDF），避免单密钥泄露打穿全库历史胶囊。
 */
@Component
public class CapsuleCrypto {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final byte[] keyBytes;

    public CapsuleCrypto(@Value("${app.capsule.secret:moyo-capsule-default-secret}") String secret) {
        this.keyBytes = sha256(secret);
    }

    private static byte[] sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，不可能走到这里
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private SecretKey buildKey() {
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /** 加密：返回 Base64(IV ‖ 密文) */
    public String encrypt(String plainText) {
        if (plainText == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, buildKey(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("时间胶囊加密失败", e);
        }
    }

    /** 解密：入参为 encrypt 产出的 Base64 串；密文被篡改或密钥错误时抛 IllegalStateException */
    public String decrypt(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            byte[] all = Base64.getDecoder().decode(base64);
            if (all.length <= IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("密文长度不合法");
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            byte[] cipherText = new byte[all.length - IV_LENGTH_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH_BYTES);
            System.arraycopy(all, IV_LENGTH_BYTES, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, buildKey(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("时间胶囊解密失败（密文可能被篡改或密钥已变更）", e);
        }
    }
}
