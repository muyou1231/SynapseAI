package com.synapseai.dto;

/**
 * 聊天记录搜索结果项：一条命中的消息 + 它所属会话的可读名称。
 * 前端据此渲染「会话名 + 片段 + 时间」并跳转定位。
 */
public class MessageSearchItem {

    /** 命中的消息（已附带发送者昵称/头像） */
    private WsMessage message;
    /** 会话类型：USER / GROUP */
    private String convType;
    /** 会话 id（USER 为对方 id；GROUP 为群 id） */
    private Long convId;
    /** 会话可读名称（好友昵称 / 群名） */
    private String convName;

    public WsMessage getMessage() {
        return message;
    }

    public void setMessage(WsMessage message) {
        this.message = message;
    }

    public String getConvType() {
        return convType;
    }

    public void setConvType(String convType) {
        this.convType = convType;
    }

    public Long getConvId() {
        return convId;
    }

    public void setConvId(Long convId) {
        this.convId = convId;
    }

    public String getConvName() {
        return convName;
    }

    public void setConvName(String convName) {
        this.convName = convName;
    }
}
