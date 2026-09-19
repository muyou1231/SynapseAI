package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 时间胶囊：写给未来的自己或指定好友的消息，到期日前加密封存，到期后解密解锁 */
@TableName("time_capsule")
public class TimeCapsule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long senderId;

    /** null = 写给未来的自己 */
    private Long receiverId;

    /** AES-256-GCM 加密后的正文（密文 Base64） */
    private String contentEnc;

    /** 精确到日的开启日期 */
    private LocalDateTime openTime;

    /** SEALED=封存中 / UNLOCKED=已解锁 */
    private String status = "SEALED";

    private LocalDateTime unlockedTime;

    private LocalDateTime createTime = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public Long getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(Long receiverId) {
        this.receiverId = receiverId;
    }

    public String getContentEnc() {
        return contentEnc;
    }

    public void setContentEnc(String contentEnc) {
        this.contentEnc = contentEnc;
    }

    public LocalDateTime getOpenTime() {
        return openTime;
    }

    public void setOpenTime(LocalDateTime openTime) {
        this.openTime = openTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getUnlockedTime() {
        return unlockedTime;
    }

    public void setUnlockedTime(LocalDateTime unlockedTime) {
        this.unlockedTime = unlockedTime;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
