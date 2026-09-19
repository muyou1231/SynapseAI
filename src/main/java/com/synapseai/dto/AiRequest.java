package com.synapseai.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.synapseai.common.LenientStringDeserializer;
import lombok.Data;

import java.util.List;

/**
 * AI 接口请求体（学习空间 AI 能力）。字段按需使用，未传即为 null/默认值。
 * 所有 String 字段使用宽松反序列化：前端即使误传对象/数组也不会 400。
 */
public class AiRequest {

    /** 通用对话：可带 system 角色设定 */
    @Data
    public static class AiChatReq {
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String system;
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String prompt;
    }

    /** AI 定制学习计划 */
    @Data
    public static class AiPlanReq {
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String goal;       // 学习目标，如 "一个月过六级"
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String duration;   // 可用时长，如 "每天2小时"
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String level;      // 当前基础，如 "四级500分"
    }

    /** 知识点互问：基于薄弱点出题 */
    @Data
    public static class AiQuizReq {
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String subject;    // 学科，如 "计算机网络"
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String weakPoints; // 薄弱点，如 "TCP拥塞控制"
        private int count = 5;     // 题目数量
    }

    /** 资料/错题摘要归类 */
    @Data
    public static class AiSummarizeReq {
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String content;    // 待摘要的原文
    }

    /**
     * 流式对话请求：一个接口承载全部 AI 功能，由 mode 决定角色与提示构造。
     * mode 取值：chat（通用对话）/ plan（制定计划）/ quiz（互问）/ summarize（摘要）。
     */
    @Data
    public static class AiStreamReq {
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String mode = "chat";
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String prompt;      // 通用对话 / 摘要 的内容
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String goal;        // plan: 学习目标
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String duration;    // plan: 可用时长
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String level;       // plan: 当前基础
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String subject;     // quiz: 学科
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String weakPoints;  // quiz: 薄弱点
        private int count = 5;      // quiz: 题数
        /**
         * 对话记忆：前端传来的历史多轮消息（不含当前这轮），用于让 AI 记住之前的对话。
         * 仅 chat 模式生效；plan/quiz/summarize 为一次性任务，忽略此字段以免污染输出格式。
         * 每项为 { role: "user"|"assistant", content: "..." }。
         */
        private List<ChatTurn> history;
    }

    /** 流式多轮上下文的单条历史：role ∈ user | assistant */
    @Data
    public static class ChatTurn {
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String role;
        @JsonDeserialize(using = LenientStringDeserializer.class)
        private String content;
    }
}
