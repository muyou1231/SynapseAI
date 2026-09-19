package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 加急消息屏蔽关系：user_id 屏蔽了 peer_id 的加急弹窗（单聊/群聊均按发送方 peer 屏蔽）
 */
@TableName("urgent_mute")
public class UrgentMute {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long peerId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getPeerId() {
        return peerId;
    }

    public void setPeerId(Long peerId) {
        this.peerId = peerId;
    }
}
