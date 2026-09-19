package com.synapseai.dto;

/**
 * 语音通话 WebRTC 信令载体。
 * type: OFFER / ANSWER / ICE / BYE
 * from/to: 用户 id（服务端会强制 from 为当前登录用户，防止伪造）
 * sdp: offer/answer 的 SDP 描述
 * candidate: ICE candidate（透传，前端自行 JSON 化）
 * callId: 一次通话的唯一标识，由发起方生成，用于把 answer/ice/bye 关联到同一通电话
 * reason: BYE 时携带的结束原因（reject/busy/timeout/normal）
 */
public class CallSignal {
    private String type;
    private Long from;
    private Long to;
    private String sdp;
    private Object candidate;
    private String callId;
    private String reason;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Long getFrom() { return from; }
    public void setFrom(Long from) { this.from = from; }

    public Long getTo() { return to; }
    public void setTo(Long to) { this.to = to; }

    public String getSdp() { return sdp; }
    public void setSdp(String sdp) { this.sdp = sdp; }

    public Object getCandidate() { return candidate; }
    public void setCandidate(Object candidate) { this.candidate = candidate; }

    public String getCallId() { return callId; }
    public void setCallId(String callId) { this.callId = callId; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
