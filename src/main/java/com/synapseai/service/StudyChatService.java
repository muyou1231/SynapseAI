package com.synapseai.service;

import com.synapseai.entity.StudyChat;
import com.synapseai.mapper.StudyChatMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 学习空间：AI 对话历史持久化（薄服务层，直接编排 mapper） */
@Service
public class StudyChatService {

    @Autowired
    private StudyChatMapper studyChatMapper;

    /** 追加一条「用户」消息，返回该条记录 id（seq 按所属子线 thread 独立顺延） */
    public long appendUser(Long userId, Long sessionId, String mode, String thread, String content) {
        int seq = studyChatMapper.selectMaxSeq(userId, sessionId, thread) + 1;
        StudyChat m = new StudyChat();
        m.setUserId(userId);
        m.setSessionId(sessionId == null ? 0L : sessionId);
        m.setRole("user");
        m.setMode(mode);
        m.setThread(thread);
        m.setContent(content);
        m.setSeq(seq);
        m.setCreateTime(LocalDateTime.now());
        studyChatMapper.insert(m);
        return m.getId() == null ? 0L : m.getId();
    }

    /**
     * 创建一条「AI」占位消息（流式开始前先落库，content 为空），返回实体。
     * 流式过程中调用 updateAiContent 实时更新，刷新中途也能从库里恢复半截内容。
     */
    public StudyChat createAi(Long userId, Long sessionId, String mode, String thread) {
        int seq = studyChatMapper.selectMaxSeq(userId, sessionId, thread) + 1;
        StudyChat m = new StudyChat();
        m.setUserId(userId);
        m.setSessionId(sessionId == null ? 0L : sessionId);
        m.setRole("ai");
        m.setMode(mode);
        m.setThread(thread);
        m.setContent("");
        m.setSeq(seq);
        m.setCreateTime(LocalDateTime.now());
        studyChatMapper.insert(m);
        return m;
    }

    /** 流式过程中实时更新 AI 回复内容 */
    public void updateAiContent(Long id, String content) {
        studyChatMapper.updateContent(id, content);
    }

    /** 某个 AI 会话下的全部对话（按 seq 升序回放，跨全部子线，供前端按 thread 分桶） */
    public List<StudyChat> listBySession(Long userId, Long sessionId) {
        return studyChatMapper.selectBySession(userId, sessionId);
    }

    /** 某个 AI 会话下、某条子线（thread）的对话（按 seq 升序回放）——四类独立记录线各自记忆 */
    public List<StudyChat> listByThread(Long userId, Long sessionId, String thread) {
        return studyChatMapper.selectByThread(userId, sessionId, thread);
    }

    /** 我的全部对话（按 seq 升序回放，兼容改造前的旧数据 session_id=0） */
    public List<StudyChat> list(Long userId) {
        return studyChatMapper.selectByUserId(userId);
    }

    /** 按会话删除全部消息（校验归属） */
    public void deleteBySession(Long userId, Long sessionId) {
        studyChatMapper.deleteBySession(userId, sessionId);
    }

    /** 按会话 + 子线删除消息（清空某一类记录线） */
    public void deleteByThread(Long userId, Long sessionId, String thread) {
        studyChatMapper.deleteByThread(userId, sessionId, thread);
    }

    /** 统计某会话下的消息条数 */
    public int countBySession(Long userId, Long sessionId) {
        return studyChatMapper.countBySession(userId, sessionId);
    }

    /** 删除单条（校验归属） */
    public boolean delete(Long userId, Long id) {
        int n = studyChatMapper.deleteById(userId, id);
        return n > 0;
    }

    /** 清空当前用户全部对话 */
    public void deleteAll(Long userId) {
        studyChatMapper.deleteAll(userId);
    }
}
