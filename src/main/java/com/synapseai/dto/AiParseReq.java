package com.synapseai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** AI 一键建谱：自然语言解析请求 */
public class AiParseReq {

    @NotBlank(message = "请输入家族描述")
    @Size(max = 8000, message = "描述内容过长（上限 8000 字）")
    private String text;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
