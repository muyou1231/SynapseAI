package com.synapseai.controller;

import com.synapseai.common.AuthUser;
import com.synapseai.dto.CallSignal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

/**
 * 语音通话信令中继：客户端发送到 /app/call.signal，服务端原样（并补全 from）转发给目标用户。
 * 实际媒体流由 WebRTC 端到端传输，服务端不接触音频数据。
 */
@Controller
public class CallController {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @MessageMapping("call.signal")
    public void signal(@Payload CallSignal sig, Principal principal) {
        Long senderId = principal instanceof AuthUser au ? au.getUserId() : sig.getFrom();
        if (senderId == null || sig.getTo() == null) return;
        // 安全：from 必须是登录用户本人，防止冒用他人身份发起/响应通话
        if (!senderId.equals(sig.getFrom())) sig.setFrom(senderId);
        // 不能呼叫自己
        if (senderId.equals(sig.getTo())) return;

        Map<String, Object> out = new HashMap<>();
        out.put("from", sig.getFrom());
        out.put("callId", sig.getCallId());
        out.put("sdp", sig.getSdp());
        out.put("candidate", sig.getCandidate());
        out.put("reason", sig.getReason());

        String t = sig.getType() == null ? "" : sig.getType().toUpperCase();
        switch (t) {
            case "OFFER":
                out.put("type", "CALL_OFFER");
                break;
            case "ANSWER":
                out.put("type", "CALL_ANSWER");
                break;
            case "ICE":
                out.put("type", "CALL_ICE");
                break;
            case "BYE":
                out.put("type", "CALL_BYE");
                break;
            default:
                return; // 未知类型直接忽略
        }
        messagingTemplate.convertAndSend("/topic/user/" + sig.getTo(), out);
    }
}
