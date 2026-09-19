package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.entity.Intimacy;
import com.synapseai.entity.PointsLog;
import com.synapseai.entity.UserPoints;
import com.synapseai.service.PointsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ⑪ 聊天挖矿：积分余额 / 积分流水 / 双人亲密度。
 * 积分通过发消息、在线时长自动获得（每日有上限），可消耗积分隐藏"已编辑"标记等。
 */
@RestController
@RequestMapping("/api/points")
public class PointsController {

    @Autowired
    private PointsService pointsService;

    /** 我的积分与等级 */
    @GetMapping("/me")
    public Result<?> me(@RequestAttribute("uid") Long uid) {
        UserPoints p = pointsService.getOrCreate(uid);
        Map<String, Object> m = new HashMap<>();
        m.put("points", p.getPoints());
        m.put("level", p.getLevel());
        return Result.ok(m);
    }

    /** 我的积分流水（最近 N 条，默认 20，最多 100） */
    @GetMapping("/log")
    public Result<?> log(@RequestAttribute("uid") Long uid,
                         @RequestParam(value = "limit", defaultValue = "20") int limit) {
        List<PointsLog> list = pointsService.recentLog(uid, limit);
        return Result.ok(list);
    }

    /** 我与某位好友的亲密度 */
    @GetMapping("/intimacy")
    public Result<?> intimacy(@RequestAttribute("uid") Long uid, @RequestParam("peerId") Long peerId) {
        Intimacy i = pointsService.getIntimacy(uid, peerId);
        Map<String, Object> m = new HashMap<>();
        m.put("peerId", peerId);
        m.put("exp", i.getExp());
        m.put("level", i.getLevel());
        return Result.ok(m);
    }
}
