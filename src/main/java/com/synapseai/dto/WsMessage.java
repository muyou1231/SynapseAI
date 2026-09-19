package com.synapseai.dto;

import java.util.List;

/**
 * 推送给客户端的消息（已附带发送者信息）
 */
public class WsMessage {

    private Long id;
    private Long senderId;
    private String senderNickname;
    private String senderAvatar;
    /** TEXT / IMAGE */
    private String type;
    private String content;
    /** USER / GROUP */
    private String targetType;
    private Long targetId;
    private String createTime;
    /** 是否已撤回（撤回后气泡显示为系统提示） */
    private Boolean recalled = false;
    /** 接收方是否已读（单聊有效） */
    private Boolean read = false;
    /** 是否加急消息 */
    private Boolean urgent = false;
    /** 是否已被发送者软删除（前端渲染时跳过，双保险） */
    private Boolean deleted = false;
    /** READ 通知专用：被标记为已读的消息 id 列表 */
    private java.util.List<Long> readIds;
    /** TYPING 通知专用：对方是否正在输入 */
    private Boolean typing = false;
    /** ⑩ 消息炸弹：倒计时秒数 / 截止时间 / 状态（PENDING/REPLIED/EXPLODED），非炸弹消息均为 null */
    private Integer bombSeconds;
    private String bombDeadline;
    private String bombStatus;

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

    public String getSenderNickname() {
        return senderNickname;
    }

    public void setSenderNickname(String senderNickname) {
        this.senderNickname = senderNickname;
    }

    public String getSenderAvatar() {
        return senderAvatar;
    }

    public void setSenderAvatar(String senderAvatar) {
        this.senderAvatar = senderAvatar;
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

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public Boolean getRecalled() {
        return recalled;
    }

    public void setRecalled(Boolean recalled) {
        this.recalled = recalled;
    }

    public Boolean getRead() {
        return read;
    }

    public void setRead(Boolean read) {
        this.read = read;
    }

    public Boolean getUrgent() {
        return urgent;
    }

    public void setUrgent(Boolean urgent) {
        this.urgent = urgent;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    public java.util.List<Long> getReadIds() {
        return readIds;
    }

    public void setReadIds(java.util.List<Long> readIds) {
        this.readIds = readIds;
    }

    public Boolean getTyping() {
        return typing;
    }

    public void setTyping(Boolean typing) {
        this.typing = typing;
    }

    public Integer getBombSeconds() {
        return bombSeconds;
    }

    public void setBombSeconds(Integer bombSeconds) {
        this.bombSeconds = bombSeconds;
    }

    public String getBombDeadline() {
        return bombDeadline;
    }

    public void setBombDeadline(String bombDeadline) {
        this.bombDeadline = bombDeadline;
    }

    public String getBombStatus() {
        return bombStatus;
    }

    public void setBombStatus(String bombStatus) {
        this.bombStatus = bombStatus;
    }

    /** 历史消息分页结果（含总数和 hasMore 标记） */
    public static class HistoryResult {
        private List<WsMessage> items;
        private int total;
        private boolean hasMore;

        public HistoryResult(List<WsMessage> items, int total, boolean hasMore) {
            this.items = items;
            this.total = total;
            this.hasMore = hasMore;
        }

        public List<WsMessage> getItems() { return items; }
        public void setItems(List<WsMessage> items) { this.items = items; }

        public int getTotal() { return total; }
        public void setTotal(int total) { this.total = total; }

        public boolean isHasMore() { return hasMore; }
        public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }
    }
}
