package com.synapseai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapseai.common.BizException;
import com.synapseai.dto.AiApplyReq;
import com.synapseai.dto.FamilyTreeVO;
import com.synapseai.dto.MemberSaveReq;
import com.synapseai.dto.RelationCreateReq;
import com.synapseai.entity.FamilyMember;
import com.synapseai.entity.FamilyRelation;
import com.synapseai.mapper.FamilyMemberMapper;
import com.synapseai.mapper.FamilyRelationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 家庭树的 AI 辅助能力。
 * <p>
 * 分工原则（重要）：
 * <ul>
 *   <li><b>坐标算不来 AI</b>：布局是确定性几何问题，仍由 {@code FamilyTreeLayout} 负责，
 *       AI 不参与算坐标——那样只会更慢、更贵、还不稳定。</li>
 *   <li><b>AI 只做它擅长的语义活</b>：把一段自然语言描述解析成结构化的成员 + 关系（一键建谱），
 *       以及把体检结果翻译成人话。</li>
 *   <li><b>能靠规则判断的一律用规则</b>：体检、补推、布局推荐都是确定性推导，
 *       规则既准确又免费，AI 只负责表达。</li>
 * </ul>
 */
@Service
public class FamilyAiService {

    private static final Logger log = LoggerFactory.getLogger(FamilyAiService.class);

    /** 提示词里最多带多少已有成员，防止超长 */
    private static final int MAX_EXISTING_IN_PROMPT = 120;

    private static final Set<String> PARENT_TYPES = Set.of("FATHER", "MOTHER", "STEP_FATHER", "STEP_MOTHER");

    @Autowired
    private AiService aiService;
    @Autowired
    private FamilyService familyService;
    @Autowired
    private FamilyMemberMapper memberMapper;
    @Autowired
    private FamilyRelationMapper relationMapper;
    @Autowired
    private ObjectMapper objectMapper;

    // ==================================================================
    // 一、一键建谱：自然语言 → 结构化预览（不落库）
    // ==================================================================

    public Map<String, Object> parseFromText(Long uid, Long familyId, String text) {
        familyService.familyDetail(uid, familyId); // 校验可读，顺带确认家族存在
        List<FamilyMember> existing = memberMapper.listByFamily(familyId);

        String system = "你是家谱结构化助手。用户会用自然语言描述一个家庭的成员与关系。\n" +
                "请只输出一个 JSON 对象，不要任何解释、不要 markdown 代码块，格式严格如下：\n" +
                "{\n" +
                "  \"members\": [ {\"ref\":\"A\",\"name\":\"姓名\",\"gender\":1,\"birthDate\":\"1950-01-01\",\"deceased\":false,\"deathDate\":\"\"} ],\n" +
                "  \"unions\": [ {\"spouses\":[\"A\",\"B\"],\"children\":[\"C\",\"D\"]} ]\n" +
                "}\n" +
                "规则：\n" +
                "1) ref 用简短字母数字（A、B、p1…），在 members 与 unions 之间保持一致。\n" +
                "2) gender：1=男，2=女，0=未知。\n" +
                "3) birthDate / deathDate：完整日期写 yyyy-MM-dd；只知道年份写 yyyy-01-01；完全不知道写空字符串。\n" +
                "4) deceased：只有文中明确说已故/去世/逝世，或给了逝世日期，才为 true，否则 false。\n" +
                "5) unions 表示「一段关系及其子女」：spouses 放这段关系的双方（1~2 人），children 放他们的孩子。单亲时 spouses 只放 1 人。\n" +
                "6) 不要输出 JSON 之外的任何文字。\n";

        StringBuilder user = new StringBuilder();
        if (!existing.isEmpty()) {
            user.append("家族里已有这些成员（若描述中提到同名的人，请沿用其姓名，后端会自动复用）：\n");
            int n = Math.min(existing.size(), MAX_EXISTING_IN_PROMPT);
            for (int i = 0; i < n; i++) {
                FamilyMember m = existing.get(i);
                user.append("- ").append(m.getName());
                if (m.getBirthDate() != null) {
                    user.append("（").append(m.getBirthDate().getYear()).append(" 年生）");
                }
                user.append('\n');
            }
            if (existing.size() > n) {
                user.append("- ……还有 ").append(existing.size() - n).append(" 位\n");
            }
            user.append('\n');
        }
        user.append("请解析下面这段描述：\n").append(text);

        String raw;
        try {
            raw = aiService.chat(system, user.toString());
        } catch (Exception e) {
            log.warn("AI 建谱解析失败: {}", e.getMessage());
            throw new BizException("AI 解析失败：" + e.getMessage());
        }
        JsonNode root = extractJson(raw);
        if (root == null) {
            throw new BizException("AI 返回的内容不是合法 JSON，请再试一次或把描述写得更清楚");
        }

        // 已有成员按姓名建索引，用于「复用 vs 新建」
        Map<String, FamilyMember> byName = new HashMap<>();
        for (FamilyMember m : existing) {
            if (m.getName() != null) {
                byName.putIfAbsent(m.getName().trim(), m);
            }
        }

        List<Map<String, Object>> members = new ArrayList<>();
        Map<String, Integer> genderByRef = new HashMap<>();
        JsonNode arr = root.path("members");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                String name = text(n.path("name")).trim();
                if (name.isEmpty()) {
                    continue;
                }
                String ref = text(n.path("ref")).trim();
                if (ref.isEmpty()) {
                    ref = "m" + (members.size() + 1);
                }
                int gender = n.path("gender").asInt(0);
                if (gender != 1 && gender != 2) {
                    gender = 0;
                }
                boolean deceased = n.path("deceased").asBoolean(false);
                String birth = normalizeDate(text(n.path("birthDate")));
                String death = normalizeDate(text(n.path("deathDate")));
                if (!death.isEmpty()) {
                    deceased = true;
                }

                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("ref", ref);
                mm.put("name", name);
                mm.put("gender", gender);
                mm.put("birthDate", birth);
                mm.put("deceased", deceased);
                mm.put("deathDate", death);
                FamilyMember hit = byName.get(name);
                if (hit != null) {
                    mm.put("matchId", hit.getId());
                    mm.put("matchName", hit.getName());
                }
                members.add(mm);
                genderByRef.put(ref, gender);
            }
        }

        List<Map<String, Object>> unions = new ArrayList<>();
        JsonNode urr = root.path("unions");
        if (urr.isArray()) {
            for (JsonNode n : urr) {
                List<String> sp = new ArrayList<>();
                for (JsonNode s : n.path("spouses")) {
                    String r = text(s).trim();
                    if (!r.isEmpty()) {
                        sp.add(r);
                    }
                }
                List<String> ch = new ArrayList<>();
                for (JsonNode c : n.path("children")) {
                    String r = text(c).trim();
                    if (!r.isEmpty()) {
                        ch.add(r);
                    }
                }
                if (sp.isEmpty() && ch.isEmpty()) {
                    continue;
                }
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("spouses", sp);
                u.put("children", ch);
                unions.add(u);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("members", members);
        out.put("unions", unions);
        out.put("memberCount", members.size());
        out.put("unionCount", unions.size());
        return out;
    }

    // ==================================================================
    // 二、一键建谱：应用（批量落库）
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> apply(Long uid, Long familyId, AiApplyReq req) {
        if (req == null || req.getMembers() == null || req.getMembers().isEmpty()) {
            throw new BizException("没有可写入的成员");
        }
        Map<String, Long> refToId = new LinkedHashMap<>();
        Map<String, Integer> genderByRef = new LinkedHashMap<>();
        int created = 0;
        int reused = 0;

        for (AiApplyReq.AiMember m : req.getMembers()) {
            String ref = m.getRef() == null || m.getRef().trim().isEmpty() ? "m" + (refToId.size() + 1) : m.getRef().trim();
            String name = m.getName() == null ? "" : m.getName().trim();
            if (name.isEmpty()) {
                continue;
            }
            int gender = (m.getGender() == null) ? 0 : m.getGender();
            Long id;
            if (m.getMatchId() != null && memberMapper.selectById(m.getMatchId()) != null) {
                id = m.getMatchId();
                reused++;
            } else {
                MemberSaveReq sr = new MemberSaveReq();
                sr.setFamilyId(familyId);
                sr.setName(name);
                sr.setGender(gender);
                sr.setBirthDate(safe(m.getBirthDate()));
                sr.setDeceased(Boolean.TRUE.equals(m.getDeceased()));
                sr.setDeathDate(safe(m.getDeathDate()));
                id = familyService.saveMember(uid, sr);
                created++;
            }
            refToId.put(ref, id);
            genderByRef.put(ref, gender);
        }

        int spouseLinks = 0;
        int parentLinks = 0;
        List<String> skipped = new ArrayList<>();
        if (req.getUnions() != null) {
            for (AiApplyReq.AiUnion u : req.getUnions()) {
                List<Long> sp = resolve(u.getSpouses(), refToId);
                List<Long> ch = resolve(u.getChildren(), refToId);
                // 配偶关系
                if (sp.size() >= 2) {
                    try {
                        RelationCreateReq r = new RelationCreateReq();
                        r.setMemberId(sp.get(0));
                        r.setRelativeId(sp.get(1));
                        r.setRelationType("SPOUSE");
                        familyService.addRelation(uid, r);
                        spouseLinks++;
                    } catch (Exception e) {
                        skipped.add("配偶关系：" + e.getMessage());
                    }
                }
                if (ch.isEmpty()) {
                    continue;
                }
                // 按性别挑出父亲 / 母亲；性别未知时按出现顺序补位
                Long father = null;
                Long mother = null;
                for (Long pid : sp) {
                    Integer g = genderOf(pid, genderByRef, refToId);
                    if (g != null && g == 2 && mother == null) {
                        mother = pid;
                    } else if (g != null && g == 1 && father == null) {
                        father = pid;
                    }
                }
                if (father == null && mother == null) {
                    // 性别都未知：按出现顺序依次当父亲、母亲
                    if (sp.size() >= 1) {
                        father = sp.get(0);
                    }
                    if (sp.size() >= 2) {
                        mother = sp.get(1);
                    }
                }
                Long self = father != null ? father : mother;
                for (Long cid : ch) {
                    try {
                        RelationCreateReq r = new RelationCreateReq();
                        r.setMemberId(self);
                        r.setRelativeId(cid);
                        r.setRelationType("CHILD");
                        r.setFatherId(father);
                        r.setMotherId(mother);
                        familyService.addRelation(uid, r);
                        parentLinks++;
                    } catch (Exception e) {
                        skipped.add("父子关系：" + e.getMessage());
                    }
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("createdMembers", created);
        out.put("reusedMembers", reused);
        out.put("spouseLinks", spouseLinks);
        out.put("parentLinks", parentLinks);
        out.put("memberIds", new ArrayList<>(refToId.values()));
        if (!skipped.isEmpty()) {
            out.put("skipped", skipped);
        }
        return out;
    }

    // ==================================================================
    // 三、关系体检（规则检测 + AI 总结）
    // ==================================================================

    public Map<String, Object> audit(Long uid, Long familyId) {
        familyService.familyDetail(uid, familyId);
        List<FamilyMember> members = memberMapper.listByFamily(familyId);
        List<FamilyRelation> relations = relationMapper.listByFamily(familyId);

        Map<Long, FamilyMember> byId = new LinkedHashMap<>();
        for (FamilyMember m : members) {
            byId.put(m.getId(), m);
        }
        Map<Long, Set<Long>> childrenOf = new HashMap<>();
        Map<Long, Set<Long>> parentsOf = new HashMap<>();
        Map<Long, Set<Long>> spousesOf = new HashMap<>();
        for (FamilyRelation r : relations) {
            Long a = r.getMemberAId();
            Long b = r.getMemberBId();
            if (PARENT_TYPES.contains(r.getRelationType())) {
                childrenOf.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(b);
                parentsOf.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(a);
            } else if ("SPOUSE".equals(r.getRelationType()) || "EX_SPOUSE".equals(r.getRelationType())) {
                spousesOf.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(b);
                spousesOf.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(a);
            }
        }

        List<Map<String, Object>> issues = new ArrayList<>();
        // 1) 日期矛盾
        for (FamilyMember m : members) {
            if (m.getBirthDate() != null && m.getDeathDate() != null && m.getDeathDate().isBefore(m.getBirthDate())) {
                issues.add(issue("ERROR", "DATE", m.getId(), m.getName(),
                        "逝世日期早于出生日期",
                        "把逝世日期改到出生日期之后，或清空其中一个"));
            }
        }
        // 2) 年龄倒挂：家长出生晚于孩子
        for (Map.Entry<Long, Set<Long>> e : childrenOf.entrySet()) {
            FamilyMember parent = byId.get(e.getKey());
            for (Long cid : e.getValue()) {
                FamilyMember child = byId.get(cid);
                if (parent == null || child == null) {
                    continue;
                }
                if (parent.getBirthDate() != null && child.getBirthDate() != null
                        && parent.getBirthDate().isAfter(child.getBirthDate())) {
                    issues.add(issue("ERROR", "AGE", child.getId(), child.getName(),
                            "家长「" + parent.getName() + "」出生晚于孩子「" + child.getName() + "」",
                            "检查两人的出生日期是否填反"));
                }
            }
        }
        // 3) 同名重复
        Map<String, List<FamilyMember>> dup = new LinkedHashMap<>();
        for (FamilyMember m : members) {
            if (m.getName() != null) {
                dup.computeIfAbsent(m.getName().trim(), k -> new ArrayList<>()).add(m);
            }
        }
        for (Map.Entry<String, List<FamilyMember>> e : dup.entrySet()) {
            if (e.getValue().size() > 1) {
                issues.add(issue("WARN", "DUPLICATE", e.getValue().get(0).getId(), e.getKey(),
                        "有 " + e.getValue().size() + " 位同名成员，可能是重复录入",
                        "确认后删除多余的，或用籍贯/生卒加以区分"));
            }
        }
        // 4) 只有一位家长
        for (Map.Entry<Long, Set<Long>> e : parentsOf.entrySet()) {
            FamilyMember child = byId.get(e.getKey());
            if (child != null && e.getValue().size() == 1) {
                Long pid = e.getValue().iterator().next();
                FamilyMember p = byId.get(pid);
                issues.add(issue("INFO", "SINGLE_PARENT", child.getId(), child.getName(),
                        "只登记了一位家长（" + (p == null ? "?" : p.getName()) + "）",
                        "若另一位已知，可在添加子女时手动指定父亲与母亲"));
            }
        }
        // 5) 共同育儿但未登记配偶
        for (Map.Entry<Long, Set<Long>> e : parentsOf.entrySet()) {
            Set<Long> ps = e.getValue();
            if (ps.size() != 2) {
                continue;
            }
            List<Long> two = new ArrayList<>(ps);
            Long a = two.get(0);
            Long b = two.get(1);
            Set<Long> sa = spousesOf.get(a);
            if (sa == null || !sa.contains(b)) {
                issues.add(issue("INFO", "NO_SPOUSE", e.getKey(), byId.get(e.getKey()) == null ? null : byId.get(e.getKey()).getName(),
                        "两位家长之间未登记夫妻关系",
                        "若确为夫妻，补登记后布局会把两人并为一组，孩子居中"));
            }
        }
        // 6) 孤立成员
        for (FamilyMember m : members) {
            boolean linked = !childrenOf.getOrDefault(m.getId(), Collections.emptySet()).isEmpty()
                    || !parentsOf.getOrDefault(m.getId(), Collections.emptySet()).isEmpty()
                    || !spousesOf.getOrDefault(m.getId(), Collections.emptySet()).isEmpty();
            if (!linked) {
                issues.add(issue("INFO", "ISOLATED", m.getId(), m.getName(),
                        "没有任何亲属关系，会独立显示在画布一角",
                        "补上父母 / 配偶 / 子女关系"));
            }
        }

        // 7) AI 把体检结果翻译成人话（失败就退回规则文本，不影响主流程）
        String summary = ruleSummary(issues, members.size());
        if (!issues.isEmpty()) {
            try {
                StringBuilder sb = new StringBuilder();
                for (Map<String, Object> is : issues) {
                    sb.append("- [").append(is.get("level")).append("] ").append(is.get("message")).append('\n');
                }
                String s = aiService.chat(
                        "你是家谱校对助手。下面是一份族谱体检问题清单，请用两三句中文口语化地总结" +
                                "「最该先处理什么」，不要逐条罗列，不要超过 120 字。",
                        sb.toString());
                if (s != null && !s.trim().isEmpty()) {
                    summary = s.trim();
                }
            } catch (Exception e) {
                log.warn("AI 体检总结失败，回退规则文案: {}", e.getMessage());
            }
        }

        long errors = issues.stream().filter(i -> "ERROR".equals(i.get("level"))).count();
        long warns = issues.stream().filter(i -> "WARN".equals(i.get("level"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("memberCount", members.size());
        out.put("issueCount", issues.size());
        out.put("errorCount", errors);
        out.put("warnCount", warns);
        out.put("issues", issues);
        out.put("summary", summary);
        return out;
    }

    // ==================================================================
    // 四、智能补推缺失关系（规则推导）
    // ==================================================================

    public List<Map<String, Object>> infer(Long uid, Long familyId) {
        familyService.familyDetail(uid, familyId);
        List<FamilyMember> members = memberMapper.listByFamily(familyId);
        List<FamilyRelation> relations = relationMapper.listByFamily(familyId);
        Map<Long, FamilyMember> byId = new LinkedHashMap<>();
        for (FamilyMember m : members) {
            byId.put(m.getId(), m);
        }
        Map<Long, Set<Long>> childrenOf = new HashMap<>();
        Map<Long, Set<Long>> parentsOf = new HashMap<>();
        Map<Long, Set<Long>> spousesOf = new HashMap<>();
        for (FamilyRelation r : relations) {
            Long a = r.getMemberAId();
            Long b = r.getMemberBId();
            if (PARENT_TYPES.contains(r.getRelationType())) {
                childrenOf.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(b);
                parentsOf.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(a);
            } else if ("SPOUSE".equals(r.getRelationType()) || "EX_SPOUSE".equals(r.getRelationType())) {
                spousesOf.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(b);
                spousesOf.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(a);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        // ① 单亲 + 该家长有唯一配偶 → 补另一位家长
        for (Map.Entry<Long, Set<Long>> e : parentsOf.entrySet()) {
            if (e.getValue().size() != 1) {
                continue;
            }
            Long childId = e.getKey();
            Long pid = e.getValue().iterator().next();
            Set<Long> sp = spousesOf.get(pid);
            if (sp == null || sp.size() != 1) {
                continue;
            }
            Long other = sp.iterator().next();
            FamilyMember p = byId.get(pid);
            FamilyMember o = byId.get(other);
            FamilyMember c = byId.get(childId);
            if (p == null || o == null || c == null) {
                continue;
            }
            boolean pFemale = Integer.valueOf(2).equals(p.getGender());
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "SECOND_PARENT");
            s.put("childId", childId);
            s.put("parentId", pid);
            s.put("newParentId", other);
            s.put("fatherId", pFemale ? other : pid);
            s.put("motherId", pFemale ? pid : other);
            s.put("message", "「" + c.getName() + "」只登记了" + (pFemale ? "母亲" : "父亲") + "「" + p.getName()
                    + "」，其配偶「" + o.getName() + "」可补为另一位家长");
            s.put("applyable", true);
            out.add(s);
        }
        // ② 共同育儿但未登记夫妻 → 补配偶
        for (Map.Entry<Long, Set<Long>> e : parentsOf.entrySet()) {
            if (e.getValue().size() != 2) {
                continue;
            }
            List<Long> two = new ArrayList<>(e.getValue());
            Long a = two.get(0);
            Long b = two.get(1);
            Set<Long> sa = spousesOf.get(a);
            if (sa != null && sa.contains(b)) {
                continue;
            }
            FamilyMember ma = byId.get(a);
            FamilyMember mb = byId.get(b);
            if (ma == null || mb == null) {
                continue;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "SPOUSE");
            s.put("aId", a);
            s.put("bId", b);
            s.put("message", "「" + ma.getName() + "」与「" + mb.getName() + "」共同育儿但未登记夫妻，补登记后两人会并为一组");
            s.put("applyable", true);
            out.add(s);
        }
        // ③ 关系已表明性别但未填 → 补性别
        for (FamilyMember m : members) {
            if (m.getGender() != null && m.getGender() != 0) {
                continue;
            }
            String suggest = null;
            for (FamilyRelation r : relations) {
                if ("FATHER".equals(r.getRelationType()) && r.getMemberAId().equals(m.getId())) {
                    suggest = "MALE";
                } else if ("MOTHER".equals(r.getRelationType()) && r.getMemberAId().equals(m.getId())) {
                    suggest = "FEMALE";
                }
            }
            if (suggest == null) {
                continue;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "GENDER");
            s.put("memberId", m.getId());
            s.put("gender", "MALE".equals(suggest) ? 1 : 2);
            s.put("message", "「" + m.getName() + "」在关系里是" + ("MALE".equals(suggest) ? "父亲" : "母亲") + "，但性别未填");
            s.put("applyable", true);
            out.add(s);
        }
        return out;
    }

    /** 应用补推建议（默认全部可自动处理的） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> inferApply(Long uid, Long familyId, List<Map<String, Object>> picked) {
        List<Map<String, Object>> list = (picked == null || picked.isEmpty()) ? infer(uid, familyId) : picked;
        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (Map<String, Object> s : list) {
            if (!Boolean.TRUE.equals(s.get("applyable"))) {
                continue;
            }
            try {
                String type = String.valueOf(s.get("type"));
                if ("SECOND_PARENT".equals(type)) {
                    RelationCreateReq r = new RelationCreateReq();
                    r.setMemberId(longOf(s.get("parentId")));
                    r.setRelativeId(longOf(s.get("childId")));
                    r.setRelationType("CHILD");
                    r.setFatherId(longOf(s.get("fatherId")));
                    r.setMotherId(longOf(s.get("motherId")));
                    familyService.addRelation(uid, r);
                } else if ("SPOUSE".equals(type)) {
                    RelationCreateReq r = new RelationCreateReq();
                    r.setMemberId(longOf(s.get("aId")));
                    r.setRelativeId(longOf(s.get("bId")));
                    r.setRelationType("SPOUSE");
                    familyService.addRelation(uid, r);
                } else if ("GENDER".equals(type)) {
                    Long mid = longOf(s.get("memberId"));
                    FamilyMember m = memberMapper.selectById(mid);
                    if (m != null && m.getFamilyId().equals(familyId)) {
                        MemberSaveReq sr = new MemberSaveReq();
                        sr.setId(mid);
                        sr.setName(m.getName());
                        sr.setGender(intOf(s.get("gender")));
                        sr.setBirthDate(m.getBirthDate() == null ? "" : m.getBirthDate().toString());
                        sr.setDeceased(Boolean.TRUE.equals(m.getDeceased()));
                        sr.setDeathDate(m.getDeathDate() == null ? "" : m.getDeathDate().toString());
                        sr.setVersion(m.getVersion());
                        familyService.saveMember(uid, sr);
                    }
                } else {
                    continue;
                }
                ok++;
            } catch (Exception e) {
                failed.add(String.valueOf(s.get("message")) + " → " + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", ok);
        if (!failed.isEmpty()) {
            out.put("failed", failed);
        }
        return out;
    }

    // ==================================================================
    // 五、布局参数推荐（纯规则，不调模型）
    // ==================================================================

    public Map<String, Object> layoutAdvice(Long uid, Long familyId) {
        FamilyTreeVO vo = familyService.tree(uid, familyId, "TB", 0, null);
        List<FamilyTreeVO.TreeNode> nodes = vo.getNodes() == null ? Collections.emptyList() : vo.getNodes();
        int count = nodes.size();
        int maxDepth = 0;
        Map<Integer, Integer> widthByGen = new LinkedHashMap<>();
        for (FamilyTreeVO.TreeNode n : nodes) {
            int g = n.getGeneration() == null ? 0 : n.getGeneration();
            maxDepth = Math.max(maxDepth, g + 1);
            widthByGen.merge(g, 1, Integer::sum);
        }
        int maxWidth = widthByGen.values().stream().max(Integer::compareTo).orElse(0);

        // 规则：横向（LR）更擅长「深而窄」或「单层很宽」的树；纵向（TB）适合常见的宽浅型
        String direction;
        String reason;
        if (count == 0) {
            direction = "TB";
            reason = "还没有成员，先用纵向";
        } else if (maxWidth >= 7) {
            direction = "LR";
            reason = "同一辈最多有 " + maxWidth + " 人，纵向会拉得很宽，横向排布更省横向空间";
        } else if (maxDepth >= 5) {
            direction = "LR";
            reason = "族谱深达 " + maxDepth + " 代，纵向会被压得很扁，横向更适合深树";
        } else {
            direction = "TB";
            reason = "规模适中（" + count + " 人 / " + maxDepth + " 代），纵向是最直观的家族树观感";
        }

        // 折叠层级：人数越多越浅，避免一屏塞不下
        int suggestDepth;
        if (maxDepth <= 3) {
            suggestDepth = 0; // 0 = 不折叠
        } else if (maxDepth <= 5) {
            suggestDepth = 4;
        } else {
            suggestDepth = 3;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("memberCount", count);
        out.put("maxDepth", maxDepth);
        out.put("maxWidth", maxWidth);
        out.put("direction", direction);
        out.put("maxDepthSuggest", suggestDepth);
        out.put("spacing", maxWidth >= 7 ? "COMPACT" : "COMFORT");
        out.put("reason", reason);
        out.put("currentDirection", vo.getDirection());
        return out;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 从 AI 输出里抠出 JSON（容忍前后寒暄与 markdown 代码块） */
    private JsonNode extractJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String s = raw.trim();
        s = s.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) {
            return null;
        }
        try {
            return objectMapper.readTree(s.substring(a, b + 1));
        } catch (Exception e) {
            log.warn("AI 输出 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? "" : n.asText("");
    }

    /** 日期归一：yyyy → yyyy-01-01；yyyy-MM → yyyy-MM-01；非法 → 空 */
    private static String normalizeDate(String s) {
        if (s == null) {
            return "";
        }
        String v = s.trim();
        if (v.isEmpty()) {
            return "";
        }
        if (v.matches("^\\d{4}$")) {
            return v + "-01-01";
        }
        if (v.matches("^\\d{4}-\\d{2}$")) {
            return v + "-01";
        }
        if (v.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return v;
        }
        return "";
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static Long longOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer intOf(Object o) {
        Long v = longOf(o);
        return v == null ? 0 : v.intValue();
    }

    private static List<Long> resolve(List<String> refs, Map<String, Long> refToId) {
        List<Long> out = new ArrayList<>();
        if (refs == null) {
            return out;
        }
        for (String r : refs) {
            Long id = refToId.get(r == null ? "" : r.trim());
            if (id != null && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static Integer genderOf(Long memberId, Map<String, Integer> genderByRef, Map<String, Long> refToId) {
        for (Map.Entry<String, Long> e : refToId.entrySet()) {
            if (e.getValue().equals(memberId)) {
                return genderByRef.get(e.getKey());
            }
        }
        return null;
    }

    private static Map<String, Object> issue(String level, String type, Long memberId, String name,
                                             String message, String suggestion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("type", type);
        m.put("memberId", memberId);
        m.put("name", name);
        m.put("message", message);
        m.put("suggestion", suggestion);
        return m;
    }

    private static String ruleSummary(List<Map<String, Object>> issues, int memberCount) {
        if (issues.isEmpty()) {
            return "共 " + memberCount + " 位成员，没有发现明显问题。";
        }
        long errors = issues.stream().filter(i -> "ERROR".equals(i.get("level"))).count();
        long warns = issues.stream().filter(i -> "WARN".equals(i.get("level"))).count();
        return "共 " + memberCount + " 位成员，发现 " + issues.size() + " 处待确认（"
                + errors + " 处需要修正、" + warns + " 处可能重复）。";
    }
}
