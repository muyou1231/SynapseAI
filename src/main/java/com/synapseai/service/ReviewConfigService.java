package com.synapseai.service;

import com.synapseai.mapper.ReviewConfigMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 朋友圈审核模式（全局开关，单行 id=1）：
 *  - AUTO = 自动审核：用户发布/重新发布的内容直接生效（status=NORMAL）
 *  - MANUAL = 人工审核：用户发布/重新发布的内容进入 PENDING 待管理员审核
 *  - AI   = AI 审核：先用 AI 自动判定，通过则直接发布(NORMAL)，
 *           不通过则进入 PENDING 待人工复审（用户可在「我的」页申请人工复审）
 */
@Service
public class ReviewConfigService {

    @Autowired
    private ReviewConfigMapper reviewConfigMapper;

    /** 读取当前审核模式，缺省回退 AUTO（直接发布） */
    public String getMode() {
        String m = reviewConfigMapper.getMode();
        return (m == null) ? "AUTO" : m;
    }

    /** 设置审核模式，仅接受 AUTO / MANUAL / AI，否则抛异常 */
    public void setMode(String mode) {
        if (!"AUTO".equalsIgnoreCase(mode) && !"MANUAL".equalsIgnoreCase(mode) && !"AI".equalsIgnoreCase(mode)) {
            throw new RuntimeException("审核模式只能是 AUTO、MANUAL 或 AI");
        }
        reviewConfigMapper.setMode(mode.toUpperCase());
    }
}
