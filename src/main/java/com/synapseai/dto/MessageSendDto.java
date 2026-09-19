package com.synapseai.dto;

/**
 * 客户端通过 WebSocket 发送的消息体
 */
public class MessageSendDto {

    /** 发送者（服务端会优先使用 token 解析出的 userId） */
    private Long senderId;
    /** TEXT / IMAGE */
    private String type;
    /** 文本内容，或图片的 base64 / URL */
    private String content;
    /** USER / GROUP */
    private String targetType;
    /** 接收用户 id 或群 id */
    private Long targetId;
    /** 是否加急（可选，默认 false） */
    private Boolean urgent = false;
    /** ⑩ 消息炸弹：倒计时秒数（可选，null/♤0 表示普通消息，仅支持单聊 TEXT 消息） */
    private Integer bombSeconds;

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public Boolean getUrgent() {
        return urgent;
    }

    public void setUrgent(Boolean urgent) {
        this.urgent = urgent;
    }

    public Integer getBombSeconds() {
        return bombSeconds;
    }

    public void setBombSeconds(Integer bombSeconds) {
        this.bombSeconds = bombSeconds;
    }
}
