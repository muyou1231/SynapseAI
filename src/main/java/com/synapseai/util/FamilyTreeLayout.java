package com.synapseai.util;

import com.synapseai.dto.FamilyTreeVO;
import com.synapseai.entity.FamilyMember;
import com.synapseai.entity.FamilyRelation;

import java.util.*;

/**
 * 家族树布局算法（族谱专用，家谱树 pedigree layout）。
 * <p>
 * <b>为什么要自己算坐标，而不用 G6 自带的 compactBox？</b>
 * G6 的树布局要求数据是一棵严格的树（每个节点只有一个父节点），而真实族谱天然是"图"：
 * 一个孩子有父母两人、一个人可能有多个配偶、继父母养子女并存。
 * 强行套树布局会导致夫妻被拆到不同层、继子女错位。
 * <p>
 * 因此这里采用家谱布局的标准做法：
 * <ol>
 *   <li>用并查集把「配偶对」合并成一个<b>家庭单元（group）</b>；再婚多配偶会自然合并进同一单元。</li>
 *   <li>以 group 为节点、以「父母单元 → 子女单元」为边构建布局树，按最长路径分层（辈分）。</li>
 *   <li>经典 tidy-tree 两遍布局：后序算子树跨度，前序分配区间，父单元居中于其子女单元。</li>
 *   <li>单元内成员横向并排（纵向布局时），夫妻连线自然成为一条横线。</li>
 * </ol>
 * 坐标在后端一次算好，前端 G6 与导出长图 / PDF 共用同一份坐标，做到"页面所见即导出所得"。
 */
public final class FamilyTreeLayout {

    /** 节点卡片宽度（与前端 G6 自定义节点、导出长图保持一致） */
    public static final int NODE_W = 132;
    /** 节点卡片高度 */
    public static final int NODE_H = 76;
    /**
     * 夫妻（同单元内相邻成员）间距。
     * 数值偏大会让夫妻看起来像两家人，偏小则卡片挤在一起 —— 38 兼顾辨识度与紧凑度。
     */
    public static final int SPOUSE_GAP = 38;
    /**
     * 层与层（辈分）间距。
     * 节点高 76，留出约 94 的空白给父子折线走线，避免两条折线贴着卡片。
     */
    public static final int LEVEL_GAP = 170;
    /** 同层不同家庭单元之间的间距（兄弟姐妹各自成家后的横向留白） */
    public static final int GROUP_GAP = 64;
    /** 画布四周留白 */
    public static final int PADDING = 90;

    /** 长辈 → 晚辈 方向的关系类型（决定谁在上/左） */
    private static final Set<String> PARENT_TYPES = Set.of(
            "FATHER", "MOTHER", "STEP_FATHER", "STEP_MOTHER");

    private FamilyTreeLayout() {
    }

    // ==================================================================
    // 对外主入口
    // ==================================================================

    /**
     * 构建家族树。
     *
     * @param members   家族内全部在册成员
     * @param relations 家族内全部关系
     * @param direction TB=纵向（上父下子）/ LR=横向（左父右子）
     * @param maxDepth  超过该辈分层数则折叠后代（超大家族懒加载，配合 expanded 逐层展开）
     * @param expanded  被用户手动展开的节点 id（这些节点的子树无视 maxDepth 继续展开）
     * @param firstPhoto 成员 → 相册首图 URL（无专属头像时兜底展示，可为 null）
     */
    public static FamilyTreeVO build(List<FamilyMember> members,
                                     List<FamilyRelation> relations,
                                     String direction,
                                     int maxDepth,
                                     Set<Long> expanded,
                                     Map<Long, String> firstPhoto) {
        boolean lr = "LR".equalsIgnoreCase(direction);
        FamilyTreeVO vo = new FamilyTreeVO();
        vo.setDirection(lr ? "LR" : "TB");

        if (members == null || members.isEmpty()) {
            vo.setWidth(PADDING * 2);
            vo.setHeight(PADDING * 2);
            return vo;
        }

        // ---------- 1. 基础索引 ----------
        Map<Long, FamilyMember> memberMap = new LinkedHashMap<>();
        for (FamilyMember m : members) {
            memberMap.put(m.getId(), m);
        }
        // child -> parents（有序，保证结果稳定）
        Map<Long, LinkedHashSet<Long>> parentsOf = new HashMap<>();
        Map<Long, LinkedHashSet<Long>> childrenOf = new HashMap<>();
        // 配偶边：key = "min-max"，value = 类型（SPOUSE / EX_SPOUSE）
        Map<String, String> spouseType = new LinkedHashMap<>();
        List<long[]> spousePairs = new ArrayList<>();

        for (FamilyRelation r : relations) {
            Long a = r.getMemberAId();
            Long b = r.getMemberBId();
            if (!memberMap.containsKey(a) || !memberMap.containsKey(b) || a.equals(b)) {
                continue; // 关系两端必须都在册，且不能自指
            }
            String type = r.getRelationType();
            if (PARENT_TYPES.contains(type)) {
                parentsOf.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(a);
                childrenOf.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(b);
            } else if ("SPOUSE".equals(type) || "EX_SPOUSE".equals(type)) {
                long x = Math.min(a, b);
                long y = Math.max(a, b);
                String key = x + "-" + y;
                // 只要有一对是配偶就按"实线"处理：离异仅在双方都是 EX_SPOUSE 时才画虚线
                if (!spouseType.containsKey(key)) {
                    spouseType.put(key, type);
                    spousePairs.add(new long[]{x, y});
                } else if ("SPOUSE".equals(type)) {
                    spouseType.put(key, "SPOUSE");
                }
            }
        }

        // ---------- 2. 并查集：配偶合并为「家庭单元」 ----------
        Map<Long, Long> parent = new HashMap<>();
        for (long id : memberMap.keySet()) {
            parent.put(id, id);
        }
        for (long[] pair : spousePairs) {
            union(parent, pair[0], pair[1]);
        }
        // 共同育儿的两位家长（可能并非夫妻）也合并为一个单元，
        // 这样他们的孩子会落在两人正中间，而不是挂在其中一人正下方。
        // 合并条件很严格，见 coParentPairs 注释，避免把多段关系的人错误并到一起。
        for (long[] pair : coParentPairs(parentsOf, childrenOf, spousePairs, memberMap)) {
            union(parent, pair[0], pair[1]);
        }

        // ---------- 2.5 亲家合并：同一孩子单元的多组长辈并为一排 ----------
        // 场景：小夫妻各自的长辈（丈夫的父母 + 妻子的父亲）会各自成组；
        // 建树时孩子单元只挂在一组下面，另一组就成「无主长辈」被甩到画布最右侧，
        // 离自己的子女隔半个画面。这里把「同一孩子单元的多组家长」合并为一个单元，
        // 让双方长辈并排站在子女单元正上方。
        // 只合并「不同成员带来的长辈」（亲家关系）；同一个人有多组家长（再婚等）不合并。
        for (int pass = 0; pass < 2; pass++) {
            Map<Long, List<Long>> gm0 = buildGroups(memberMap, parent);
            Map<Long, Long> go0 = buildGroupOf(gm0);
            // 孩子单元 -> 其长辈组集合
            Map<Long, Set<Long>> parentGroupsByChildGroup = new LinkedHashMap<>();
            for (Map.Entry<Long, LinkedHashSet<Long>> e : parentsOf.entrySet()) {
                Long cg = go0.get(e.getKey());
                if (cg == null) {
                    continue;
                }
                for (Long p : e.getValue()) {
                    Long pg = go0.get(p);
                    if (pg != null && !pg.equals(cg)) {
                        parentGroupsByChildGroup.computeIfAbsent(cg, k -> new LinkedHashSet<>()).add(pg);
                    }
                }
            }
            boolean changed = false;
            for (Map.Entry<Long, Set<Long>> e : parentGroupsByChildGroup.entrySet()) {
                if (e.getValue().size() < 2) {
                    continue;
                }
                // 这些长辈组必须来自该单元的「不同成员」（夫妻双方各自的父母）。
                // 若全部家长组都指向同一个成员（该成员本人有多组家长），跳过。
                Map<Long, Set<Long>> byMember = new LinkedHashMap<>();
                for (Long mid : gm0.getOrDefault(e.getKey(), Collections.<Long>emptyList())) {
                    Set<Long> pgs = new LinkedHashSet<>();
                    for (Long p : parentsOf.getOrDefault(mid, new LinkedHashSet<>())) {
                        Long pg = go0.get(p);
                        if (pg != null && !pg.equals(e.getKey())) {
                            pgs.add(pg);
                        }
                    }
                    if (!pgs.isEmpty()) {
                        byMember.put(mid, pgs);
                    }
                }
                if (byMember.size() < 2) {
                    continue;
                }
                List<Long> gs = new ArrayList<>(e.getValue());
                for (int i = 1; i < gs.size(); i++) {
                    union(parent, gs.get(0), gs.get(i));
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }

        // groupId（用组内最小成员 id 作为代表） -> 成员列表（主成员在前）
        Map<Long, List<Long>> groupMembers = new LinkedHashMap<>();
        for (Long id : memberMap.keySet()) {
            long root = find(parent, id);
            groupMembers.computeIfAbsent(root, k -> new ArrayList<>()).add(id);
        }
        for (List<Long> list : groupMembers.values()) {
            sortGroupMembers(list, parentsOf, childrenOf);
        }
        // member -> groupId
        Map<Long, Long> groupOf = new HashMap<>();
        for (Map.Entry<Long, List<Long>> e : groupMembers.entrySet()) {
            for (Long mid : e.getValue()) {
                groupOf.put(mid, e.getKey());
            }
        }

        // ---------- 3. 单元层级（辈分）：最长路径松弛 ----------
        // group -> 子 group（去重）
        Map<Long, LinkedHashSet<Long>> groupChildren = new HashMap<>();
        Map<Long, LinkedHashSet<Long>> groupParents = new HashMap<>();
        for (Map.Entry<Long, LinkedHashSet<Long>> e : childrenOf.entrySet()) {
            Long pg = groupOf.get(e.getKey());
            if (pg == null) continue;
            for (Long child : e.getValue()) {
                Long cg = groupOf.get(child);
                if (cg == null || cg.equals(pg)) continue; // 同单元内不算父子层级
                groupChildren.computeIfAbsent(pg, k -> new LinkedHashSet<>()).add(cg);
                groupParents.computeIfAbsent(cg, k -> new LinkedHashSet<>()).add(pg);
            }
        }
        Map<Long, Integer> depth = new HashMap<>();
        for (Long g : groupMembers.keySet()) {
            depth.put(g, 0);
        }
        int guard = groupMembers.size() + 1; // 防环：最多松弛 N 轮
        for (int i = 0; i < guard; i++) {
            boolean changed = false;
            for (Map.Entry<Long, LinkedHashSet<Long>> e : groupChildren.entrySet()) {
                int pd = depth.getOrDefault(e.getKey(), 0);
                for (Long cg : e.getValue()) {
                    if (depth.getOrDefault(cg, 0) < pd + 1) {
                        depth.put(cg, pd + 1);
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }

        // ---------- 4. 构建布局森林（每个单元只挂在一个父单元下） ----------
        // 用 BFS「首次访问者即父」的方式生成树：天然无环，异常数据（互为父母）也不会导致布局递归栈溢出。
        Map<Long, List<Long>> treeChildren = new HashMap<>();
        List<Long> roots = new ArrayList<>();
        Set<Long> visited = new LinkedHashSet<>();
        for (Long g : groupMembers.keySet()) {
            Set<Long> ps = groupParents.get(g);
            if (ps == null || ps.isEmpty()) {
                roots.add(g);
            }
        }
        if (roots.isEmpty()) {
            // 极端情况：所有单元都互相是父（数据成环），取第一个单元兜底作为根
            roots.add(groupMembers.keySet().iterator().next());
        }
        Deque<Long> bfs = new ArrayDeque<>();
        for (Long r : roots) {
            if (visited.add(r)) {
                bfs.add(r);
            }
        }
        while (!bfs.isEmpty()) {
            Long g = bfs.poll();
            for (Long c : groupChildren.getOrDefault(g, new LinkedHashSet<>())) {
                if (visited.add(c)) {
                    treeChildren.computeIfAbsent(g, k -> new ArrayList<>()).add(c);
                    bfs.add(c);
                }
            }
        }
        // 环内未被访问到的单元（没有任何"无父入口"可达）补作根节点，保证不丢人
        List<Long> extraRoots = new ArrayList<>();
        for (Long g : groupMembers.keySet()) {
            if (visited.add(g)) {
                extraRoots.add(g);
            }
        }
        if (!extraRoots.isEmpty()) {
            Deque<Long> bfs2 = new ArrayDeque<>(extraRoots);
            roots.addAll(extraRoots);
            while (!bfs2.isEmpty()) {
                Long g = bfs2.poll();
                for (Long c : groupChildren.getOrDefault(g, new LinkedHashSet<>())) {
                    if (visited.add(c)) {
                        treeChildren.computeIfAbsent(g, k -> new ArrayList<>()).add(c);
                        bfs2.add(c);
                    }
                }
            }
        }
        roots.sort(Comparator.comparingLong(g -> groupMembers.get(g).get(0)));
        for (List<Long> cs : treeChildren.values()) {
            cs.sort(Comparator.comparingLong(g -> groupMembers.get(g).get(0)));
        }

        // ---------- 5. 后代计数（含折叠提示与删除影响范围） ----------
        Map<Long, Integer> descendantCount = new HashMap<>();
        for (Long mid : memberMap.keySet()) {
            countDescendants(mid, childrenOf, descendantCount, new HashSet<>());
        }

        // ---------- 6. 决定哪些单元参与渲染（折叠逻辑） ----------
        Set<Long> renderedGroups = new LinkedHashSet<>();
        Set<Long> foldedGroups = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        Map<Long, Integer> renderDepth = new HashMap<>();
        for (Long r : roots) {
            queue.add(r);
            renderDepth.put(r, 0);
        }
        // 用户显式展开的成员所在单元 —— 连同其「整支后代单元」一并展开。
        // （早期版本只展开一层，导致点击 +N 后子节点立刻又被折叠，看起来像"点了没反应"）
        Set<Long> expandGroups = new LinkedHashSet<>();
        if (expanded != null && !expanded.isEmpty()) {
            for (Long mid : expanded) {
                Long g = groupOf.get(mid);
                if (g != null && expandGroups.add(g)) {
                    collectSubtree(g, treeChildren, expandGroups);
                }
            }
        }
        while (!queue.isEmpty()) {
            Long g = queue.poll();
            if (!renderedGroups.add(g)) {
                continue;
            }
            int d = renderDepth.getOrDefault(g, 0);
            List<Long> cs = treeChildren.get(g);
            if (cs == null || cs.isEmpty()) {
                continue;
            }
            // 是否继续展开：未到 maxDepth，或该单元属于用户展开的那一支
            boolean keepGoing = d + 1 <= maxDepth || expandGroups.contains(g);
            if (keepGoing) {
                for (Long c : cs) {
                    renderDepth.putIfAbsent(c, d + 1);
                    queue.add(c);
                }
            } else {
                foldedGroups.add(g);
            }
        }
        // 折叠单元的后代一律不渲染（可能由其它路径进入，这里过滤掉）
        Set<Long> suppressed = new LinkedHashSet<>();
        for (Long fg : foldedGroups) {
            collectSubtree(fg, treeChildren, suppressed);
            suppressed.remove(fg); // 折叠单元自身仍要渲染
        }
        renderedGroups.removeAll(suppressed);

        // ---------- 7. tidy-tree 布局：后序算跨度，前序分配坐标 ----------
        Map<Long, Double> span = new HashMap<>();
        Map<Long, Double> center = new HashMap<>();
        double cursor = PADDING;
        for (Long root : roots) {
            if (!renderedGroups.contains(root)) {
                continue;
            }
            computeSpan(root, treeChildren, groupMembers, renderedGroups, span);
            double rootCenter = assign(root, cursor, treeChildren, groupMembers, renderedGroups, span, center);
            cursor += span.get(root) + GROUP_GAP;
            // 根节点自身居中修正（第一个根可能因为子树更宽而偏右）
            center.put(root, rootCenter);
        }
        double totalSpan = Math.max(cursor - GROUP_GAP + PADDING, PADDING * 2);

        // ---------- 8. 落位：单元 → 成员坐标 ----------
        Map<Long, double[]> pos = new HashMap<>(); // memberId -> {x, y}
        int maxLevel = 0;
        for (Long g : renderedGroups) {
            List<Long> mids = groupMembers.get(g);
            double selfSize = mids.size() * NODE_W + (mids.size() - 1) * SPOUSE_GAP;
            double groupCenter = center.getOrDefault(g, PADDING + selfSize / 2.0);
            double left = groupCenter - selfSize / 2.0;
            int level = depth.getOrDefault(g, 0);
            maxLevel = Math.max(maxLevel, level);
            for (int i = 0; i < mids.size(); i++) {
                double c = left + i * (NODE_W + SPOUSE_GAP) + NODE_W / 2.0;
                double cross = PADDING + NODE_H / 2.0 + level * LEVEL_GAP;
                if (lr) {
                    pos.put(mids.get(i), new double[]{cross, c}); // LR：层号决定 x
                } else {
                    pos.put(mids.get(i), new double[]{c, cross}); // TB：层号决定 y
                }
            }
        }

        // ---------- 9. 组装 VO ----------
        Map<Long, FamilyTreeVO.TreeNode> nodeMap = new LinkedHashMap<>();
        for (Long g : renderedGroups) {
            for (Long mid : groupMembers.get(g)) {
                FamilyMember m = memberMap.get(mid);
                FamilyTreeVO.TreeNode n = new FamilyTreeVO.TreeNode();
                n.setId(String.valueOf(mid));
                n.setMemberId(mid);
                n.setName(m.getName());
                n.setAvatar(m.getAvatarUrl() == null ? "" : m.getAvatarUrl());
                // 相册首图兜底：没有专属头像时，前端拿它当头像展示（都为空时才回退姓氏首字）
                String fp = firstPhoto == null ? null : firstPhoto.get(mid);
                n.setPhotoUrl(fp == null ? "" : fp);
                n.setGender(m.getGender() == null ? 0 : m.getGender());
                n.setBirthYear(m.getBirthDate() == null ? "" : String.valueOf(m.getBirthDate().getYear()));
                n.setDeathYear(m.getDeathDate() == null ? "" : String.valueOf(m.getDeathDate().getYear()));
                // 逝世与否以 deceased 标记为准（兼容旧数据：填过逝世日期的一律视为已逝世）
                boolean deceased = (m.getDeceased() != null && m.getDeceased()) || m.getDeathDate() != null;
                n.setDeceased(deceased);
                n.setAlive(!deceased);
                n.setGeneration(depth.getOrDefault(g, 0));
                n.setGroupId(g.intValue());
                n.setBioBrief(brief(m.getBio()));
                n.setChildCount(childrenOf.getOrDefault(mid, new LinkedHashSet<>()).size());
                double[] p = pos.get(mid);
                if (p != null) {
                    n.setX(p[0]);
                    n.setY(p[1]);
                }
                if (foldedGroups.contains(g) && !childrenOf.getOrDefault(mid, new LinkedHashSet<>()).isEmpty()) {
                    n.setCollapsed(true);
                    n.setFoldedDescendants(descendantCount.getOrDefault(mid, 0));
                }
                nodeMap.put(mid, n);
            }
        }

        // ---------- 边：父子 + 夫妻 ----------
        // 父子边按「孩子」聚合：同一孩子若有多位家长，且他们同属一个家庭单元
        // （夫妻 / 共同育儿，见前面的并查集合并），则合并为一条「从单元中点出线」的边。
        // 否则每位家长各画一条，两个孩子就是 4 条线交叉成网，视觉上非常拥挤。
        Map<Long, List<FamilyRelation>> parentRelsByChild = new LinkedHashMap<>();
        List<FamilyRelation> spouseRels = new ArrayList<>();
        for (FamilyRelation r : relations) {
            Long a = r.getMemberAId();
            Long b = r.getMemberBId();
            if (!nodeMap.containsKey(a) || !nodeMap.containsKey(b)) {
                continue;
            }
            String type = r.getRelationType();
            if (PARENT_TYPES.contains(type)) {
                parentRelsByChild.computeIfAbsent(b, k -> new ArrayList<>()).add(r);
            } else if ("SPOUSE".equals(type) || "EX_SPOUSE".equals(type)) {
                if (a > b) {
                    continue; // 夫妻双向存两条，只输出一条
                }
                spouseRels.add(r);
            }
            // SON / DAUGHTER / ADOPTED_* 是反向边，父子方向已覆盖，不重复画线
        }
        for (Map.Entry<Long, List<FamilyRelation>> e : parentRelsByChild.entrySet()) {
            Long child = e.getKey();
            List<FamilyRelation> rels = e.getValue();
            // 这些家长是否同属一个家庭单元
            Long gid = null;
            boolean sameUnit = true;
            boolean hasStep = false;
            for (FamilyRelation r : rels) {
                hasStep = hasStep || r.getRelationType().startsWith("STEP_");
                Long g = groupOf.get(r.getMemberAId());
                if (g == null || (gid != null && !gid.equals(g))) {
                    sameUnit = false;
                    break;
                }
                gid = g;
            }
            // source 取 id 最小的家长，仅用于前端/导出器定位卡片边缘；
            // 实际出线点用 coupleCenter（单元中点），线条从「两人中间」垂下再分支到各孩子。
            if (sameUnit && gid != null) {
                Long source = null;
                for (FamilyRelation r : rels) {
                    if (source == null || r.getMemberAId() < source) {
                        source = r.getMemberAId();
                    }
                }
                FamilyTreeVO.TreeEdge edge = new FamilyTreeVO.TreeEdge();
                edge.setSource(String.valueOf(source));
                edge.setTarget(String.valueOf(child));
                edge.setType(hasStep ? "STEP_PARENT" : "PARENT");
                Double c = center.get(gid);
                if (c != null) {
                    // center 恒为横向坐标：TB 时是单元中点的 x，LR 时是单元中点的 y
                    edge.setCoupleCenter(c);
                }
                vo.getEdges().add(edge);
            } else {
                // 家长分属不同单元（如与前任各自育儿）：无法合并，保留每位家长各一条线
                for (FamilyRelation r : rels) {
                    FamilyTreeVO.TreeEdge edge = new FamilyTreeVO.TreeEdge();
                    edge.setSource(String.valueOf(r.getMemberAId()));
                    edge.setTarget(String.valueOf(child));
                    edge.setType(r.getRelationType().startsWith("STEP_") ? "STEP_PARENT" : "PARENT");
                    vo.getEdges().add(edge);
                }
            }
        }
        for (FamilyRelation r : spouseRels) {
            FamilyTreeVO.TreeEdge e = new FamilyTreeVO.TreeEdge();
            e.setSource(String.valueOf(r.getMemberAId()));
            e.setTarget(String.valueOf(r.getMemberBId()));
            e.setType(r.getRelationType());
            e.setLabel(r.getRelationDesc());
            vo.getEdges().add(e);
        }

        vo.getNodes().addAll(nodeMap.values());
        vo.setTotalMemberCount(members.size());
        int folded = 0;
        for (FamilyTreeVO.TreeNode n : vo.getNodes()) {
            folded += n.getFoldedDescendants();
        }
        vo.setFoldedCount(folded);
        if (lr) {
            vo.setWidth((int) (PADDING * 2 + (maxLevel + 1) * LEVEL_GAP));
            vo.setHeight((int) totalSpan);
        } else {
            vo.setWidth((int) totalSpan);
            vo.setHeight((int) (PADDING * 2 + (maxLevel + 1) * LEVEL_GAP));
        }
        return vo;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 单元内成员排序：血脉方（有父母或有子女者）在前，配偶随后；同类型按 id 升序 */
    private static void sortGroupMembers(List<Long> list,
                                         Map<Long, LinkedHashSet<Long>> parentsOf,
                                         Map<Long, LinkedHashSet<Long>> childrenOf) {
        list.sort((x, y) -> {
            int wx = weight(x, parentsOf, childrenOf);
            int wy = weight(y, parentsOf, childrenOf);
            if (wx != wy) {
                return Integer.compare(wy, wx); // 权重高的在前
            }
            return Long.compare(x, y);
        });
    }

    private static int weight(Long id,
                              Map<Long, LinkedHashSet<Long>> parentsOf,
                              Map<Long, LinkedHashSet<Long>> childrenOf) {
        int w = 0;
        if (!parentsOf.getOrDefault(id, new LinkedHashSet<>()).isEmpty()) {
            w += 2; // 家族内的孩子（血脉方）
        }
        if (!childrenOf.getOrDefault(id, new LinkedHashSet<>()).isEmpty()) {
            w += 1;
        }
        return w;
    }

    /** 后序：计算每个单元的"跨度"（TB 时为宽度，LR 时为高度） */
    private static void computeSpan(Long g,
                                    Map<Long, List<Long>> treeChildren,
                                    Map<Long, List<Long>> groupMembers,
                                    Set<Long> rendered,
                                    Map<Long, Double> span) {
        List<Long> cs = renderedChildren(g, treeChildren, rendered);
        double self = groupMembers.get(g).size() * NODE_W + (groupMembers.get(g).size() - 1) * SPOUSE_GAP;
        if (cs.isEmpty()) {
            span.put(g, self);
            return;
        }
        double total = 0;
        for (Long c : cs) {
            computeSpan(c, treeChildren, groupMembers, rendered, span);
            total += span.get(c) + GROUP_GAP;
        }
        total -= GROUP_GAP;
        span.put(g, Math.max(self, total));
    }

    /** 前序：分配区间，返回该单元的中心坐标 */
    private static double assign(Long g,
                                 double left,
                                 Map<Long, List<Long>> treeChildren,
                                 Map<Long, List<Long>> groupMembers,
                                 Set<Long> rendered,
                                 Map<Long, Double> span,
                                 Map<Long, Double> center) {
        List<Long> cs = renderedChildren(g, treeChildren, rendered);
        double self = groupMembers.get(g).size() * NODE_W + (groupMembers.get(g).size() - 1) * SPOUSE_GAP;
        double sp = span.getOrDefault(g, self);
        if (cs.isEmpty()) {
            double c = left + sp / 2.0;
            center.put(g, c);
            return c;
        }
        double total = 0;
        for (Long c : cs) {
            total += span.getOrDefault(c, 0.0) + GROUP_GAP;
        }
        total -= GROUP_GAP;
        double cursor = left + (sp - total) / 2.0; // 子女整体居中于本单元区间
        double firstCenter = 0;
        double lastCenter = 0;
        for (int i = 0; i < cs.size(); i++) {
            double cc = assign(cs.get(i), cursor, treeChildren, groupMembers, rendered, span, center);
            if (i == 0) {
                firstCenter = cc;
            }
            lastCenter = cc;
            cursor += span.getOrDefault(cs.get(i), 0.0) + GROUP_GAP;
        }
        // 父单元居中于"首子与末子"的中点 —— 经典 tidy-tree 观感
        double c = (firstCenter + lastCenter) / 2.0;
        center.put(g, c);
        return c;
    }

    private static List<Long> renderedChildren(Long g, Map<Long, List<Long>> treeChildren, Set<Long> rendered) {
        List<Long> cs = treeChildren.get(g);
        if (cs == null) {
            return Collections.emptyList();
        }
        List<Long> out = new ArrayList<>();
        for (Long c : cs) {
            if (rendered.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** 收集某单元的全部后代单元（用于折叠时抑制渲染） */
    private static void collectSubtree(Long g, Map<Long, List<Long>> treeChildren, Set<Long> out) {
        List<Long> cs = treeChildren.get(g);
        if (cs == null) {
            return;
        }
        for (Long c : cs) {
            if (out.add(c)) {
                collectSubtree(c, treeChildren, out);
            }
        }
    }

    /** 记忆化统计后代数量（环用 visiting 保护，避免死循环） */
    private static int countDescendants(Long memberId,
                                        Map<Long, LinkedHashSet<Long>> childrenOf,
                                        Map<Long, Integer> memo,
                                        Set<Long> visiting) {
        if (memo.containsKey(memberId)) {
            return memo.get(memberId);
        }
        if (!visiting.add(memberId)) {
            return 0; // 成环，截断
        }
        int total = 0;
        for (Long c : childrenOf.getOrDefault(memberId, new LinkedHashSet<>())) {
            total += 1 + countDescendants(c, childrenOf, memo, visiting);
        }
        visiting.remove(memberId);
        memo.put(memberId, total);
        return total;
    }

    /** 简介摘要：tooltip 用，最长 100 字 */
    private static String brief(String bio) {
        if (bio == null || bio.isEmpty()) {
            return "";
        }
        String s = bio.replaceAll("\\s+", " ").trim();
        return s.length() <= 100 ? s : s.substring(0, 100) + "…";
    }

    /**
     * 找出可以合并为一个单元的「共同父母」对：同一孩子的两位家长。
     * <p>
     * 背景：只合并配偶时，若两位家长并非夫妻（例如手动指定的父亲 / 母亲），
     * 他们会分属两个单元，孩子只会挂在其中一人正下方，而不是落在两人中间。
     * <p>
     * 合并条件（三条全部满足才合并，宁可不合并也不错并）：
     * <ol>
     *   <li>某个孩子恰好有两位家长；</li>
     *   <li>这两人各自只与对方共同育儿（各自的「共同育儿对象」集合大小为 1）——
     *       避免把「与多人各有子女」的人并进某个单元；</li>
     *   <li>这两人都没有其它配偶——避免把现任配偶与前任伴侣并到同一单元
     *       （例如 A 与现任 B 结婚，又与前任 C 有孩子，则 A、B、C 不应同框）。</li>
     * </ol>
     */
    private static List<long[]> coParentPairs(Map<Long, LinkedHashSet<Long>> parentsOf,
                                              Map<Long, LinkedHashSet<Long>> childrenOf,
                                              List<long[]> spousePairs,
                                              Map<Long, FamilyMember> memberMap) {
        // person -> 配偶集合
        Map<Long, Set<Long>> spouses = new HashMap<>();
        if (spousePairs != null) {
            for (long[] p : spousePairs) {
                spouses.computeIfAbsent(p[0], k -> new LinkedHashSet<>()).add(p[1]);
                spouses.computeIfAbsent(p[1], k -> new LinkedHashSet<>()).add(p[0]);
            }
        }
        // person -> 共同育儿对象集合
        Map<Long, Set<Long>> coParents = new HashMap<>();
        for (Map.Entry<Long, LinkedHashSet<Long>> e : childrenOf.entrySet()) {
            Long me = e.getKey();
            for (Long child : e.getValue()) {
                Set<Long> ps = parentsOf.get(child);
                if (ps == null) {
                    continue;
                }
                for (Long other : ps) {
                    if (!other.equals(me)) {
                        coParents.computeIfAbsent(me, k -> new LinkedHashSet<>()).add(other);
                    }
                }
            }
        }

        List<long[]> pairs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<Long, LinkedHashSet<Long>> e : childrenOf.entrySet()) {
            for (Long child : e.getValue()) {
                Set<Long> ps = parentsOf.get(child);
                if (ps == null || ps.size() != 2) {
                    continue; // 只有恰好两位家长才谈得上「居中」
                }
                List<Long> two = new ArrayList<>(ps);
                Long a = two.get(0);
                Long b = two.get(1);
                if (a == null || b == null || !memberMap.containsKey(a) || !memberMap.containsKey(b)) {
                    continue;
                }
                Set<Long> ca = coParents.get(a);
                Set<Long> cb = coParents.get(b);
                if (ca == null || cb == null || ca.size() != 1 || cb.size() != 1) {
                    continue; // 任一方还与别人共同育儿 → 不合并
                }
                if (!ca.contains(b) || !cb.contains(a)) {
                    continue;
                }
                Set<Long> sa = spouses.get(a);
                Set<Long> sb = spouses.get(b);
                if (sa != null && !sa.isEmpty() && !sa.equals(Collections.singleton(b))) {
                    continue; // a 另有配偶
                }
                if (sb != null && !sb.isEmpty() && !sb.equals(Collections.singleton(a))) {
                    continue; // b 另有配偶
                }
                long x = Math.min(a, b);
                long y = Math.max(a, b);
                if (seen.add(x + "-" + y)) {
                    pairs.add(new long[]{x, y});
                }
            }
        }
        return pairs;
    }

    /** 当前并查集下的分组：组代表 -> 成员列表 */
    private static Map<Long, List<Long>> buildGroups(Map<Long, FamilyMember> memberMap, Map<Long, Long> parent) {
        Map<Long, List<Long>> gm = new LinkedHashMap<>();
        for (Long id : memberMap.keySet()) {
            long root = find(parent, id);
            gm.computeIfAbsent(root, k -> new ArrayList<>()).add(id);
        }
        return gm;
    }

    /** 分组结果反查：成员 -> 组代表 */
    private static Map<Long, Long> buildGroupOf(Map<Long, List<Long>> groupMembers) {
        Map<Long, Long> go = new HashMap<>();
        for (Map.Entry<Long, List<Long>> e : groupMembers.entrySet()) {
            for (Long mid : e.getValue()) {
                go.put(mid, e.getKey());
            }
        }
        return go;
    }

    private static long find(Map<Long, Long> parent, long x) {
        long root = x;
        while (parent.get(root) != root) {
            root = parent.get(root);
        }
        // 路径压缩
        long cur = x;
        while (parent.get(cur) != root) {
            long next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

    private static void union(Map<Long, Long> parent, long a, long b) {
        long ra = find(parent, a);
        long rb = find(parent, b);
        if (ra != rb) {
            // 以较小 id 作为代表，保证 groupId 稳定
            if (ra < rb) {
                parent.put(rb, ra);
            } else {
                parent.put(ra, rb);
            }
        }
    }
}
