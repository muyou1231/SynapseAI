package com.synapseai.task;

import com.synapseai.entity.Message;
import com.synapseai.mapper.MessageMapper;
import com.synapseai.service.TimeCapsuleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 创新功能相关定时任务。
 *
 * <p>说明：这里用 fixedDelay 而非 cron，目的是"上一次跑完再等固定间隔"，
 * 避免任务执行时间超过间隔时重入。当前是单实例部署；若未来多实例，
 * 需引入 ShedLock 之类的分布式锁，否则两例会重复推送（见设计方案 7.3）。
 */
@Component
public class InnovationTasks {

    @Autowired
    private TimeCapsuleService capsuleService;
    @Autowired
    private MessageMapper messageMapper;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /** ① 时间胶囊：每分钟扫描到期胶囊，解锁并通知收件人 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    public void unlockCapsules() {
        try {
            capsuleService.unlockDueCapsules();
        } catch (Exception e) {
            // 定时任务异常绝不能中断调度线程，否则后续所有任务都不再执行
            System.err.println("[InnovationTasks] 解锁时间胶囊失败: " + e.getMessage());
        }
    }

    /**
     * ⑨ 消息炸弹：每 20 秒扫描到期仍未获回复的炸弹并引爆。
     * 引爆后原消息内容替换为占位文案，双方都会收到 BOMB_EXPLODED 事件以更新气泡。
     */
    @Scheduled(fixedDelay = 20_000, initialDelay = 15_000)
    public void explodeBombs() {
        try {
            List<Message> expired = messageMapper.findExpiredPendingBombs(LocalDateTime.now());
            if (expired == null || expired.isEmpty()) return;
            for (Message m : expired) {
                m.setBombStatus("EXPLODED");
                m.setContent("💣 消息已引爆（对方未在倒计时内回复）");
                messageMapper.updateById(m);

                Map<String, Object> evt = new HashMap<>();
                evt.put("type", "BOMB_EXPLODED");
                evt.put("id", m.getId());
                evt.put("senderId", m.getSenderId());
                evt.put("targetType", m.getTargetType());
                evt.put("targetId", m.getTargetId());
                evt.put("content", m.getContent());
                evt.put("bombStatus", "EXPLODED");
                // 通知接收方（被炸的一方）与发送方（获知对方被炸）
                messagingTemplate.convertAndSend("/topic/user/" + m.getTargetId(), evt);
                messagingTemplate.convertAndSend("/topic/user/" + m.getSenderId(), evt);
            }
        } catch (Exception e) {
            System.err.println("[InnovationTasks] 引爆消息炸弹失败: " + e.getMessage());
        }
    }
}
