package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.dto.PaintingReq;
import com.synapseai.entity.Painting;
import com.synapseai.service.PaintingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 程序空间：绘画作品管理接口（需登录，走统一 X-Token 鉴权）。
 * 用户可创建（上传图片或填外部链接 + 标题/描述）与删除自己的作品。
 */
@RestController
@RequestMapping("/api/painting")
public class PaintingController {

    @Autowired
    private PaintingService paintingService;

    /** 我的作品列表（最新在前） */
    @GetMapping("/list")
    public Result<?> list(@RequestAttribute("uid") Long uid) {
        List<Painting> list = paintingService.listByUser(uid);
        return Result.ok(list);
    }

    /** 创建一件作品：body { title, description, imageUrl } */
    @PostMapping("/create")
    public Result<?> create(@RequestAttribute("uid") Long uid, @RequestBody PaintingReq req) {
        String title = (req.getTitle() == null) ? null : req.getTitle().trim();
        String imageUrl = (req.getImageUrl() == null) ? null : req.getImageUrl().trim();
        if (title == null || title.isEmpty()) {
            return Result.error("作品标题不能为空");
        }
        if (title.length() > 128) {
            return Result.error("作品标题过长（最多 128 字）");
        }
        if (imageUrl == null || imageUrl.isEmpty()) {
            return Result.error("请先上传或填写图片地址");
        }
        if (imageUrl.length() > 512) {
            return Result.error("图片地址过长");
        }
        long id = paintingService.create(uid, title, req.getDescription(), imageUrl);
        return Result.ok(id);
    }

    /** 删除作品（仅本人） */
    @DeleteMapping("/{id}")
    public Result<?> delete(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        boolean ok = paintingService.delete(uid, id);
        if (!ok) return Result.error("作品不存在或无权限");
        return Result.ok("已删除");
    }
}
