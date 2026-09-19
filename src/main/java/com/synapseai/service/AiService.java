package com.synapseai.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.core.JsonValue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * 阿里云百炼（通义）AI 服务封装。
 *
 * 调用方式：使用官方 OpenAI Java SDK（com.openai:openai-java）对接
 * 阿里云百炼的 OpenAI 兼容接口（compatible-mode/v1）。
 * 该 SDK 与 dashscope-sdk-java 不同，不会引入 slf4j-simple，
 * 因此可干净地集成进 Spring Boot 3（避免 logback 绑定冲突）。
 * 鉴权 Key 来自本地 application-local.yml（不提交仓库）。
 */
@Service
public class AiService {

    @Value("${app.bailian.api-key:}")
    private String apiKey;

    @Value("${app.bailian.model:qwen-plus}")
    private String model;

    @Value("${app.bailian.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    /** 懒加载并缓存的 OpenAI 客户端（首次调用时构建） */
    private volatile OpenAIClient client;

    private OpenAIClient client() {
        OpenAIClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new IllegalStateException(
                                "未配置阿里云百炼 API Key：请在 src/main/resources/application-local.yml 的 app.bailian.api-key 填写（该文件已被 .gitignore 忽略，不会提交）");
                    }
                    c = OpenAIOkHttpClient.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            // qwen3.7-plus 是推理模型，生成较慢，整体超时放宽到 5 分钟
                            .timeout(Duration.ofMinutes(5))
                            .build();
                    client = c;
                }
            }
        }
        return c;
    }

    /** 普通对话（无 system 设定） */
    public String chat(String userPrompt) {
        return chat(null, userPrompt);
    }

    /**
     * 发起一次对话补全。
     *
     * @param systemPrompt 可选的系统角色设定
     * @param userPrompt   用户/业务输入
     * @return 模型回复文本
     */
    public String chat(String systemPrompt, String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("对话内容不能为空");
        }
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.addSystemMessage(systemPrompt);
        }
        builder.addUserMessage(userPrompt);
        // 关闭推理模型的「思考链」（enable_thinking=false，百炼兼容接口支持）：
        // 否则 qwen 推理模型会把 reasoning 混进 content，污染 plan 等要求纯 JSON 的输出。
        builder.putAdditionalBodyProperty("enable_thinking", JsonValue.from(false));

        try {
            ChatCompletion completion = client().chat().completions().create(builder.build());
            if (completion.choices().isEmpty()) return "";
            return completion.choices().get(0).message().content().orElse("");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("调用阿里云百炼异常：" + e.getMessage(), e);
        }
    }

    /**
     * 流式对话补全：逐块回调 token，适用于 SSE 实时输出。
     *
     * @param systemPrompt 可选的系统角色设定
     * @param userPrompt   用户/业务输入
     * @param onToken      每收到一个增量片段时回调（可能是空串，需调用方自行判空）
     */
    public void streamChat(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("对话内容不能为空");
        }
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.addSystemMessage(systemPrompt);
        }
        builder.addUserMessage(userPrompt);
        // 关闭推理模型思考链（见 chat() 说明），保证 plan 等纯 JSON 输出不被 reasoning 污染
        builder.putAdditionalBodyProperty("enable_thinking", JsonValue.from(false));

        // 使用官方 SDK 的流式接口：createStreaming 返回 StreamResponse（可迭代 ChatCompletionChunk）
        try (var stream = client().chat().completions().createStreaming(builder.build())) {
            stream.stream().forEach(chunk -> {
                if (chunk.choices().isEmpty()) return;
                var delta = chunk.choices().get(0).delta();
                // delta.content() 为 Optional<String>；推理模型可能先返回 reasoning，再返回正文
                delta.content().ifPresent(onToken);
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("调用阿里云百炼异常：" + e.getMessage(), e);
        }
    }

    /**
     * 多轮流式对话补全：在 system 之后追加历史消息（user/assistant 交替），最后追加当前 user，
     * 使 AI 能「记住」之前若干轮对话（学习空间 AI 记忆功能）。
     *
     * @param systemPrompt 系统角色设定（可为空）
     * @param history      历史多轮消息（已是「除了当前这轮之外的」完整对话），不会自动去重
     * @param userPrompt   当前用户输入
     * @param onToken      每收到一个增量片段时回调
     */
    public void streamChat(String systemPrompt, List<ChatCompletionMessageParam> history, String userPrompt, Consumer<String> onToken) {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("对话内容不能为空");
        }
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.addSystemMessage(systemPrompt);
        }
        if (history != null) {
            history.forEach(builder::addMessage);
        }
        builder.addUserMessage(userPrompt);
        // 关闭推理模型思考链（chat 记忆模式也统一关闭，避免思考链混入对话正文）
        builder.putAdditionalBodyProperty("enable_thinking", JsonValue.from(false));

        try (var stream = client().chat().completions().createStreaming(builder.build())) {
            stream.stream().forEach(chunk -> {
                if (chunk.choices().isEmpty()) return;
                var delta = chunk.choices().get(0).delta();
                delta.content().ifPresent(onToken);
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("调用阿里云百炼异常：" + e.getMessage(), e);
        }
    }
}
