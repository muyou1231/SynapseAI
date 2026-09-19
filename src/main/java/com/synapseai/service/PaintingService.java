package com.synapseai.service;

import com.synapseai.entity.Painting;
import com.synapseai.mapper.PaintingMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 程序空间：绘画作品管理（薄服务层，直接编排 mapper） */
@Service
public class PaintingService {

    @Autowired
    private PaintingMapper paintingMapper;

    /** 创建一件作品（归属当前用户） */
    public long create(Long userId, String title, String description, String imageUrl) {
        Painting p = new Painting();
        p.setUserId(userId);
        p.setTitle(title);
        p.setDescription(description == null ? "" : description);
        p.setImageUrl(imageUrl);
        p.setCreateTime(LocalDateTime.now());
        paintingMapper.insert(p);
        return p.getId() == null ? 0L : p.getId();
    }

    /** 我的全部作品（最新在前） */
    public List<Painting> listByUser(Long userId) {
        return paintingMapper.selectByUserId(userId);
    }

    /** 删除（校验归属，非本人忽略） */
    public boolean delete(Long userId, Long id) {
        int n = paintingMapper.deleteById(userId, id);
        return n > 0;
    }
}
