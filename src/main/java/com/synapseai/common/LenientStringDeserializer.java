package com.synapseai.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * 宽松字符串反序列化器：即使前端误将某个字段传成 JSON 对象 / 数组 / 数字 / 布尔，
 * 也不会触发 HttpMessageNotReadableException（400），而是安全转换为字符串。
 * - 字符串 -> 原值
 * - 对象 / 数组 -> 序列化为压缩 JSON 文本（尽量避免信息丢失）
 * - 数字 / 布尔 / null -> String.valueOf / 空串
 */
public class LenientStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        // 对象 / 数组：序列化为 JSON 字符串
        return node.toString();
    }
}
