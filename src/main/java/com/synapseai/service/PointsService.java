package com.synapseai.service;

import com.synapseai.entity.Intimacy;
import com.synapseai.entity.PointsLog;
import com.synapseai.entity.UserPoints;
import com.synapseai.mapper.IntimacyMapper;
import com.synapseai.mapper.PointsLogMapper;
import com.synapseai.mapper.UserPointsMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ⑪ 聊天挖矿 + 双人亲密度。
 * - 积分获取（消息发送 / 在线时长）每日有总量上限，用 Redis 计数器控制，避免频繁 SUM 查询 points_log，
 *   也天然规避了"挂机刷屏刷分"。
 * - 积分消耗（如隐藏"已编辑"标记）不受每日上限约束，只要余额够即可。
 * - 亲密度：同一对好友互发消息每日计数上限 100 次，同样用 Redis 计数器控制。
 */
@Service
public class PointsService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 每日通过"获得类"操作累计可拿到的积分上限 */
    private static final int DAILY_GAIN_CAP = 200;
    /** 同一对好友每日最多计入亲密度经验的互动次数 */
    private static final int DAILY_INTIMACY_CAP = 100;

    /** 积分等级阈值（下限），level = 命中的最后一个下限对应的等级 */
    private static final long[] POINT_LEVEL_THRESHOLDS = {0, 200, 600, 1500, 3500, 7000};
    /** 亲密度等级阈值（下限） */
    private static final int[] INTIMACY_LEVEL_THRESHOLDS = {0, 100, 500, 2000, 5000};

    @Autowired
    private UserPointsMapper userPointsMapper;
    @Autowired
    private PointsLogMapper pointsLogMapper;
    @Autowired
    private IntimacyMapper intimacyMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;

    private static int levelOf(long value, long[] thresholds) {
        int level = 1;
        for (int i = 0; i < thresholds.length; i++) {
            if (value >= thresholds[i]) level = i + 1;
        }
        return level;
    }

    private static int levelOfIntimacy(int value) {
        int level = 1;
        for (int i = 0; i < INTIMACY_LEVEL_THRESHOLDS.length; i++) {
            if (value >= INTIMACY_LEVEL_THRESHOLDS[i]) level = i + 1;
        }
        return level;
    }

    private String today() {
        return LocalDateTime.now().format(DAY_FMT);
    }

    /** 我的积分（不存在则创建默认值：0 分 / Lv1） */
    public UserPoints getOrCreate(Long userId) {
        UserPoints p = userPointsMapper.findByUserId(userId);
        if (p != null) return p;
        p = new UserPoints();
        p.setUserId(userId);
        p.setPoints(0L);
        p.setLevel(1);
        userPointsMapper.insert(p);
        return p;
    }

    public List<PointsLog> recentLog(Long userId, int limit) {
        return pointsLogMapper.findRecent(userId, Math.max(1, Math.min(limit, 100)));
    }

    /**
     * 授予积分（受每日总量上限约束，超出部分自动截断；截断到 0 时不落流水）。
     * reason 建议取值：CHAT_DURATION（在线时长）/ MESSAGE_SEND（发消息）。
     */
    public void grant(Long userId, int amount, String reason, Long refId) {
        if (amount <= 0 || userId == null) return;
        String capKey = "points:daily-gain:" + userId + ":" + today();
        Long usedBefore = redisTemplate.opsForValue().increment(capKey, 0); // 读取当前值，不存在则视为 0
        long used = usedBefore == null ? 0 : usedBefore;
        int allowed = (int) Math.max(0, Math.min(amount, DAILY_GAIN_CAP - used));
        if (allowed <= 0) return;
        redisTemplate.opsForValue().increment(capKey, allowed);
        redisTemplate.expire(capKey, Duration.ofHours(26));
        applyDelta(userId, allowed, reason, refId);
    }

    /** 消耗积分（如隐藏"已编辑"标记花 30 分）。余额不足返回 false，不做任何变更。 */
    public boolean spend(Long userId, int amount, String reason, Long refId) {
        if (amount <= 0 || userId == null) return false;
        UserPoints p = getOrCreate(userId);
        if (p.getPoints() < amount) return false;
        applyDelta(userId, -amount, reason, refId);
        return true;
    }

    private void applyDelta(Long userId, int delta, String reason, Long refId) {
        UserPoints p = getOrCreate(userId);
        long newPoints = Math.max(0, p.getPoints() + delta);
        p.setPoints(newPoints);
        p.setLevel(levelOf(newPoints, POINT_LEVEL_THRESHOLDS));
        userPointsMapper.updateByUserId(p);

        PointsLog log = new PointsLog();
        log.setUserId(userId);
        log.setChangeVal(delta);
        log.setReason(reason);
        log.setRefId(refId);
        pointsLogMapper.insert(log);
    }

    /** 双人亲密度（规范化 a&lt;b，不存在则创建默认值） */
    public Intimacy getIntimacy(Long userA, Long userB) {
        long a = Math.min(userA, userB);
        long b = Math.max(userA, userB);
        Intimacy i = intimacyMapper.findByPair(a, b);
        if (i != null) return i;
        i = new Intimacy();
        i.setUserA(a);
        i.setUserB(b);
        i.setExp(0);
        i.setLevel(1);
        intimacyMapper.insert(i);
        return i;
    }

    /** 好友互动加经验（消息互发触发），当日同一对好友最多计 100 次，超出静默忽略 */
    public void creditIntimacy(Long userA, Long userB) {
        if (userA == null || userB == null || userA.equals(userB)) return;
        long a = Math.min(userA, userB);
        long b = Math.max(userA, userB);
        String capKey = "intimacy:daily:" + a + ":" + b + ":" + today();
        Long count = redisTemplate.opsForValue().increment(capKey, 1);
        redisTemplate.expire(capKey, Duration.ofHours(26));
        if (count != null && count > DAILY_INTIMACY_CAP) return; // 超出当日上限，不再加经验

        Intimacy i = getIntimacy(a, b);
        int newExp = i.getExp() + 1;
        i.setExp(newExp);
        i.setLevel(levelOfIntimacy(newExp));
        intimacyMapper.updateByPair(i);
    }
}
