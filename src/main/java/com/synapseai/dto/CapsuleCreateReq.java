package com.synapseai.dto;

/** ① 时间胶囊创建请求体 */
public class CapsuleCreateReq {

    /** 为空 = 写给未来的自己 */
    private Long receiverId;

    private String content;

    /** 精确到日，格式 yyyy-MM-dd，且必须是明天及以后 */
    private String openDate;

    public Long getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(Long receiverId) {
        this.receiverId = receiverId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getOpenDate() {
        return openDate;
    }

    public void setOpenDate(String openDate) {
        this.openDate = openDate;
    }
}
