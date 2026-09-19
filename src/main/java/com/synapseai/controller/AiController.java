package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.dto.AiRequest;
import com.synapseai.service.AiAssistantService;
import com.synapseai.service.AiService;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 学习空间 AI 能力接口（走统一 X-Token 鉴权，需登录）。
 * 后续前端「学习空间」模块直接调用这些接口即可。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;
    private final AiAssistantService aiAssistantService;

    /** 流式输出用的独立线程池（避免阻塞 Tomcat 业务线程） */
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public AiController(AiService aiService, AiAssistantService aiAssistantService) {
        this.aiService = aiService;
        this.aiAssistantService = aiAssistantService;
    }

    /** 通用对话（可带 system 角色设定） */
    @PostMapping("/chat")
    public Result<String> chat(@RequestBody AiRequest.AiChatReq req) {
        return Result.ok(aiService.chat(req.getSystem(), req.getPrompt()));
    }

    /** AI 定制学习计划：输入目标/时长/基础，返回结构化 JSON 计划 */
    @PostMapping("/plan")
    public Result<String> plan(@RequestBody AiRequest.AiPlanReq req) {
        String[] sp = buildPlanPrompt(req.getGoal(), req.getDuration(), req.getLevel());
        return Result.ok(aiService.chat(sp[0], sp[1]));
    }

    /** 知识点互问：基于薄弱点生成练习题与参考答案 */
    @PostMapping("/quiz")
    public Result<String> quiz(@RequestBody AiRequest.AiQuizReq req) {
        String system = "你是一位出题老师。请基于用户给出的学科与薄弱点生成练习题，"
                + "每题附参考答案，使用中文，难度循序渐进。";
        int count = req.getCount() <= 0 ? 5 : req.getCount();
        String user = String.format("学科：%s%n薄弱点：%s%n题目数量：%d%n请生成题目与答案。",
                req.getSubject(), req.getWeakPoints(), count);
        return Result.ok(aiService.chat(system, user));
    }

    /** 资料/错题摘要归类：提炼关键知识点 */
    @PostMapping("/summarize")
    public Result<String> summarize(@RequestBody AiRequest.AiSummarizeReq req) {
        String system = "你是一位学习助手。请对给定内容进行摘要与归类，提取关键知识点，"
                + "用中文条理清晰地输出。";
        return Result.ok(aiService.chat(system, req.getContent()));
    }

    /** moyo 助手帮写朋友圈/广场文案：给定主题或关键词，返回可直接发布的文案 */
    @PostMapping("/assistant/copy")
    public Result<String> assistantCopy(@RequestAttribute("uid") Long uid,
                                        @RequestBody(required = false) java.util.Map<String, String> body) {
        String topic = body != null ? body.get("topic") : null;
        return Result.ok(aiAssistantService.generateMomentCopy(topic));
    }

    /**
     * 流式对话接口（SSE）。
     * 一个接口承载学习空间全部 AI 功能，由 mode 决定 system 角色与提示构造，
     * 后端逐 token 通过 SSE 推送到前端，实现「打字机」式实时显示。
     * 支持跨域/代理的 SSE 头设置；客户端断开时通过 onCompletion/onError 回收资源。
     */
    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@RequestAttribute("uid") Long uid, @RequestBody AiRequest.AiStreamReq req) {
        SseEmitter emitter = new SseEmitter(3600_000L); // 超时 60 分钟，适配长文本生成
        // 按 mode 构造 (system, user) —— 与现有非流式接口共用提示词模板
        String[] sp = buildPrompt(req);
        String system = sp[0];
        String user = sp[1];
        final String mode = req.getMode() == null ? "chat" : req.getMode();

        // 对话记忆：仅 chat 模式把前端传来的历史拼成多轮上下文（plan/quiz/summarize 保持单轮纯净）
        List<ChatCompletionMessageParam> history = null;
        if ("chat".equals(mode) && req.getHistory() != null && !req.getHistory().isEmpty()) {
            history = new ArrayList<>();
            for (AiRequest.ChatTurn t : req.getHistory()) {
                if (t == null || t.getContent() == null || t.getContent().isBlank()) continue;
                String role = t.getRole() == null ? "user" : t.getRole();
                if ("assistant".equals(role)) {
                    history.add(ChatCompletionMessageParam.ofAssistant(
                            ChatCompletionAssistantMessageParam.builder().content(t.getContent()).build()));
                } else {
                    history.add(ChatCompletionMessageParam.ofUser(
                            ChatCompletionUserMessageParam.builder().content(t.getContent()).build()));
                }
            }
            if (history.isEmpty()) history = null;
        }

        final List<ChatCompletionMessageParam> h = history;
        streamExecutor.execute(() -> {
            try {
                Consumer<String> onToken = token -> {
                    if (token == null || token.isEmpty()) return;
                    try {
                        emitter.send(SseEmitter.event().data(token));
                    } catch (IOException e) {
                        throw new RuntimeException("SSE 发送中断（客户端可能已断开）", e);
                    }
                };
                if (h != null) {
                    aiService.streamChat(system, h, user, onToken);
                } else {
                    aiService.streamChat(system, user, onToken);
                }
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("生成失败：" + e.getMessage()));
                } catch (IOException ignored) { /* 客户端已断开 */ }
                emitter.completeWithError(e);
            }
        });

        // 客户端断开或超时的兜底回收
        emitter.onCompletion(() -> { /* 正常结束，无需处理 */ });
        emitter.onTimeout(() -> emitter.complete());
        return emitter;
    }

    /**
     * 制定计划提示词（非流式 / 流式共用）。
     * 关键约束：AI 必须**只输出纯 JSON**（不要任何 markdown 代码块包裹、不要解释文字）。
     * 为降低流式切分导致的结构错乱，强制「单行紧凑 JSON」，并明确 steps 元素必须是对象。
     */
    private String[] buildPlanPrompt(String goal, String duration, String level) {
        String system = "你是一位严谨、专业且鼓励人心的学习规划师。"
                + "请把用户的学习目标拆解为**详尽、可落地**的阶段性计划，按时间顺序（阶段/周/天）组织，控制每步时长合理。\n\n"
                + "【内容要求，越详细越好】\n"
                + "1. 先划分 2~4 个**学习阶段**（如基础期、进阶期、强化期、冲刺期），再为每个阶段拆出多条具体步骤。\n"
                + "2. 每条步骤都必须包含：\n"
                + "   - 具体要做什么（动作清晰，忌空话）；\n"
                + "   - 推荐的学习资源/方法（如具体书籍、视频、网站、练习方式）；\n"
                + "   - 大致耗时或频率（如每天 1 小时、每周 3 次）；\n"
                + "   - 这一步完成后的产出或验收标准（如能独立写出 xx、做完 xx 套题）。\n"
                + "3. 步骤总数控制在 8~20 条，覆盖从入门到产出的完整路径，不要过于简略。\n"
                + "4. note 字段写 3~5 句整体建议（时间管理、避坑、心态等），要具体可操作。\n\n"
                + "【输出格式，必须严格遵守，违反将视为错误】\n"
                + "1. 只输出一个 JSON 对象，**整段必须是一行**，不要换行、不要缩进、不要包含 ```json 代码块标记。\n"
                + "2. 不要任何额外解释文字、不要前言后语、不要思考过程。\n"
                + "3. JSON 结构（注意：引号、括号、逗号必须完整正确）：\n"
                + "{\"title\":\"计划标题（含目标与周期）\",\"steps\":[{\"text\":\"阶段1-第1步：具体做法+资源+耗时+验收标准\"}],\"note\":\"整体提醒建议\"}\n"
                + "4. steps 必须是对象数组，每个元素只能是 {\"text\":\"...\"} 这种形式（**不要写成裸字符串或缺失花括号**）。\n"
                + "5. title、note、每个 text 的值都必须用双引号包裹，且内部的双引号必须转义为 \\\"。\n"
                + "6. 禁止输出 done 字段（由前端处理）；使用简体中文，避免制造焦虑。\n"
                + "7. 若用户未给出明确目标，title 写\"学习目标探索\"，steps 给出帮助其明确目标的引导性步骤，note 给出友好提示。";
        String user = String.format("学习目标：%s%n可用时长：%s%n当前基础：%s%n请严格按照上述要求，制定一份详细、分阶段的定制化学习计划，"
                + "以单行 JSON 格式返回，不要输出任何其他内容。",
                goal, duration, level);
        return new String[]{ system, user };
    }

    /** 根据 mode 复用提示词模板，返回 [system, user] */
    private String[] buildPrompt(AiRequest.AiStreamReq req) {
        String mode = req.getMode() == null ? "chat" : req.getMode();
        switch (mode) {
            case "plan": {
                return buildPlanPrompt(req.getGoal(), req.getDuration(), req.getLevel());
            }
            case "quiz": {
                String system = "你是一位出题老师。请基于用户给出的学科与薄弱点生成练习题，"
                        + "每题附参考答案，使用中文，难度循序渐进。";
                int count = req.getCount() <= 0 ? 5 : req.getCount();
                String user = String.format("学科：%s%n薄弱点：%s%n题目数量：%d%n请生成题目与答案。",
                        req.getSubject(), req.getWeakPoints(), count);
                return new String[]{ system, user };
            }
            case "summarize": {
                String system = "你是一位学习助手。请对给定内容进行摘要与归类，提取关键知识点，"
                        + "用中文条理清晰地输出。";
                return new String[]{ system, req.getPrompt() };
            }
            case "chat":
            default: {
                // 通用对话：没有特定 system 角色
                return new String[]{ "", req.getPrompt() };
            }
        }
    }
}
