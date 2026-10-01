package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.dto.AiApplyReq;
import com.synapseai.dto.AiParseReq;
import com.synapseai.service.FamilyAiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 家庭树的 AI 辅助接口（一键建谱 / 体检 / 补推 / 布局推荐）。
 * <p>
 * 注意：这里<b>不负责算坐标</b>，布局仍由后端 tidy-tree 算法完成；
 * AI 只做语义解析（自然语言 → 成员与关系）以及把体检结论说成人话。
 */
@RestController
@RequestMapping("/api/family")
public class FamilyAiController {

    @Autowired
    private FamilyAiService familyAiService;

    /** 一键建谱：把一段自然语言描述解析成成员 + 关系（只预览，不落库） */
    @PostMapping("/{id}/ai/parse")
    public Result<?> parse(@RequestAttribute("uid") Long uid,
                           @PathVariable("id") Long id,
                           @Valid @RequestBody AiParseReq req) {
        return Result.ok(familyAiService.parseFromText(uid, id, req.getText()));
    }

    /** 一键建谱：确认后批量写入 */
    @PostMapping("/{id}/ai/apply")
    public Result<?> apply(@RequestAttribute("uid") Long uid,
                           @PathVariable("id") Long id,
                           @RequestBody AiApplyReq req) {
        return Result.ok(familyAiService.apply(uid, id, req));
    }

    /** 关系体检：规则检测矛盾 + AI 总结 */
    @GetMapping("/{id}/ai/audit")
    public Result<?> audit(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        return Result.ok(familyAiService.audit(uid, id));
    }

    /** 智能补推：列出可补全的关系建议 */
    @GetMapping("/{id}/ai/infer")
    public Result<?> infer(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        return Result.ok(familyAiService.infer(uid, id));
    }

    /** 应用补推建议（不传 body 表示应用全部可处理项） */
    @PostMapping("/{id}/ai/infer/apply")
    public Result<?> inferApply(@RequestAttribute("uid") Long uid,
                                @PathVariable("id") Long id,
                                @RequestBody(required = false) List<Map<String, Object>> items) {
        return Result.ok(familyAiService.inferApply(uid, id, items));
    }

    /** 布局参数推荐（纯规则，不调模型） */
    @GetMapping("/{id}/ai/layout")
    public Result<?> layout(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        return Result.ok(familyAiService.layoutAdvice(uid, id));
    }
}
