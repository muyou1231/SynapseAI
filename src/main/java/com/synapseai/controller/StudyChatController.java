package com.synapseai.controller;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.synapseai.common.LenientStringDeserializer;
import com.synapseai.common.Result;
import com.synapseai.entity.StudyAiSession;
import com.synapseai.entity.StudyChat;
import com.synapseai.mapper.StudyAiSessionMapper;
import com.synapseai.service.StudyChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习空间：AI 对话历史持久化接口（需登录，走统一 X-Token 鉴权）。
 * 前端在「智能工具」页（独立 AI 会话框）：
 *   - GET /sessions 列出我的 AI 会话（含消息数）
 *   - POST /session 新建会话（返回 id）
 *   - DELETE /session/{id} 删除会话及旗下全部消息
 *   - GET /list?sessionId= 拉取某会话历史（刷新/换设备可靠恢复）
 *   - POST /send 在指定会话内追加 user 消息并创建 ai 占位（返回 aiId）
 *   - POST /update 流式实时更新 ai 内容
 */
@RestController
@RequestMapping("/api/study/chat")
public class StudyChatController {

    @Autowired
    private StudyChatService studyChatService;
    @Autowired
    private StudyAiSessionMapper sessionMapper;

    /** 我的 AI 会话列表（含每条会话的消息数），按创建时间倒序 */
    @GetMapping("/sessions")
    public Result<?> sessions(@RequestAttribute("uid") Long uid) {
        List<StudyAiSession> list = sessionMapper.selectByUser(uid);
        List<Map<String, Object>> out = new ArrayList<>();
        for (StudyAiSession s : list) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId());
            m.put("title", s.getTitle());
            m.put("createTime", s.getCreateTime());
            m.put("count", studyChatService.countBySession(uid, s.getId()));
            out.add(m);
        }
        return Result.ok(out);
    }

    /** 新建 AI 会话：body { title }（缺省标题后端补「新会话」） */
    @PostMapping("/session")
    public Result<?> createSession(@RequestAttribute("uid") Long uid, @RequestBody CreateSessionReq req) {
        String title = (req == null || req.getTitle() == null) ? "" : req.getTitle().trim();
        if (title.isEmpty()) title = "新会话";
        StudyAiSession s = new StudyAiSession();
        s.setUserId(uid);
        s.setTitle(title);
        sessionMapper.insert(s);
        return Result.ok(s);
    }

    /** 删除 AI 会话（连同旗下全部消息） */
    @DeleteMapping("/session/{id}")
    public Result<?> deleteSession(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        StudyAiSession s = sessionMapper.selectByIdAndUser(id, uid);
        if (s == null) return Result.error("会话不存在或无权限");
        studyChatService.deleteBySession(uid, id);
        sessionMapper.deleteByIdAndUser(id, uid);
        return Result.ok("已删除");
    }

    /** 某会话下的 AI 对话历史（按 seq 升序） */
    @GetMapping("/list")
    public Result<?> list(@RequestAttribute("uid") Long uid,
                          @RequestParam(value = "sessionId", required = false) Long sessionId) {
        List<StudyChat> list;
        if (sessionId != null) {
            list = studyChatService.listBySession(uid, sessionId);
        } else {
            list = studyChatService.list(uid);
        }
        return Result.ok(list);
    }

    /**
     * 发送一轮：在指定会话内追加 user 消息，并创建一条空的 ai 占位消息，返回 ai 实体。
     * body: { sessionId, mode, prompt }
     */
    @PostMapping("/send")
    public Result<?> send(@RequestAttribute("uid") Long uid, @RequestBody StudyChatSendReq req) {
        Long sessionId = req.getSessionId() == null ? 0L : req.getSessionId();
        String mode = (req.getMode() == null || req.getMode().isEmpty()) ? "chat" : req.getMode();
        // 子线 thread：缺省与 mode 一致（chat/plan/quiz/summarize 四类各自独立记录线）
        String thread = (req.getThread() == null || req.getThread().isEmpty()) ? mode : req.getThread();
        String prompt = (req.getPrompt() == null) ? "" : req.getPrompt().trim();
        if (prompt.isEmpty()) {
            return Result.error("内容不能为空");
        }
        studyChatService.appendUser(uid, sessionId, mode, thread, prompt);
        StudyChat ai = studyChatService.createAi(uid, sessionId, mode, thread);
        return Result.ok(ai);
    }

    /** 流式过程中实时更新 AI 回复内容：body { id, content } */
    @PostMapping("/update")
    public Result<?> update(@RequestAttribute("uid") Long uid, @RequestBody StudyChatUpdateReq req) {
        if (req.getId() == null) return Result.error("缺少 id");
        studyChatService.updateAiContent(req.getId(), req.getContent() == null ? "" : req.getContent());
        return Result.ok("ok");
    }

    /** 删除单条对话（user 或 ai） */
    @DeleteMapping("/{id}")
    public Result<?> deleteOne(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        boolean ok = studyChatService.delete(uid, id);
        if (!ok) return Result.error("记录不存在或无权限");
        return Result.ok("已删除");
    }

    /** 清空对话：传 thread 则只清空该子线（如 plan），不传则清空当前会话全部消息 */
    @DeleteMapping("/clear")
    public Result<?> clear(@RequestAttribute("uid") Long uid,
                           @RequestParam(value = "sessionId", required = false) Long sessionId,
                           @RequestParam(value = "thread", required = false) String thread) {
        if (sessionId == null) sessionId = 0L;
        if (thread != null && !thread.isEmpty()) {
            studyChatService.deleteByThread(uid, sessionId, thread);
        } else {
            studyChatService.deleteBySession(uid, sessionId);
        }
        return Result.ok("已清空");
    }

    /** 新建会话请求体 */
    public static class CreateSessionReq {
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String title;
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
    }

    /** 发送请求体 */
    public static class StudyChatSendReq {
        private Long sessionId = 0L;
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String mode;
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String thread;
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String prompt;
        public Long getSessionId() { return sessionId; }
        public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getThread() { return thread; }
        public void setThread(String thread) { this.thread = thread; }
        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
    }

    /** 更新请求体 */
    public static class StudyChatUpdateReq {
        private Long id;
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String content;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }
}
