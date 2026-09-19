package com.synapseai.service;

import com.synapseai.entity.UserSetting;
import com.synapseai.mapper.UserSettingMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 用户通用开关型设置（一人一行，不存在即按需创建默认行）。
 * 目前承载 ③ 隐身阅读，后续新功能的开关可继续在此扩展列与 getter。
 */
@Service
public class UserSettingService {

    @Autowired
    private UserSettingMapper userSettingMapper;

    /** 获取设置（不存在则插入默认行：全部开关关闭） */
    public UserSetting getOrCreate(Long userId) {
        UserSetting s = userSettingMapper.findByUserId(userId);
        if (s != null) return s;
        s = new UserSetting();
        s.setUserId(userId);
        s.setGhostRead(false);
        userSettingMapper.insert(s);
        return s;
    }

    public boolean isGhostRead(Long userId) {
        if (userId == null) return false;
        UserSetting s = userSettingMapper.findByUserId(userId);
        return s != null && Boolean.TRUE.equals(s.getGhostRead());
    }

    public void setGhostRead(Long userId, boolean enabled) {
        getOrCreate(userId);
        userSettingMapper.updateGhostRead(userId, enabled);
    }
}
