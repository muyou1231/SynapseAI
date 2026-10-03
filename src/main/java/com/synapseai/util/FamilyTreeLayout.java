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
    /** 同层单元之间的最小硬间隙：低于这个值卡片就叠在一起了，洗版时用它兜底 */
    public static final int MIN_GAP = 12;
    /** 画布四周留白 */
    public static final int PADDING = 90;
    /**
     * 是否启用「多父居中」：孩子同时属于两家（血亲父母 + 姻亲公婆）时，把孩子连同整支后代
     * 挪到各父单元中心的中点。开着更容易让祖辈那支整体跑偏（实测 王德海被拉到 365、
     * 而两个孩子组的中点是 803）；关掉后改为「父母各自向孩子靠拢」（8.5 第三遍直系对齐），
     * 树身保持 tidy-tree 的对称。排版本身不需要这个开关，保留它便于回归对比。
     */
    private static final boolean MULTI_PARENT_CENTER = false;

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
        // child#parent -> 关系类型（画父子边时判断继父母，不必再回头查关系列表）
        Map<String, String> parentType = new HashMap<>();
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
                parentType.put(b + "#" + a, type);
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
        // ⚠️ 这里只合并「配偶」。早期版本还会合并「共同育儿的家长」与「亲家长辈」，
        // 结果是把出嫁女儿的父亲（冯富忠）并进女婿父母的单元里 —— 冯贵英看起来就
        // 变成孔令兴父母的女儿，纸上也就找不到"冯富忠 → 冯贵英"那条线（线的中点取了
        // 整个合并单元的中心）。现在改为：每个人挂回自己亲生父母的单元下方，
        // 孩子的两边家长各垂一条线，见 7.5 节「多父居中」。
        Map<Long, Long> parent = new HashMap<>();
        for (long id : memberMap.keySet()) {
            parent.put(id, id);
        }
        for (long[] pair : spousePairs) {
            union(parent, pair[0], pair[1]);
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

        // ---------- 8.5 多父居中：把孩子摆到两家人中间 ----------
        // 场景：冯贵英（冯富忠的女儿）同时是孔令兴的伴侣、孔德权/孔德志的母亲。
        // tidy-tree 只把「孩子单元」挂在一个父单元下（另外一组家长就成孤儿根被甩到边上），
        // 于是孩子看着像属于夫家、娘家那边只有一条对不上的线。
        // 这里按「辈分从浅到深」把「有多个父单元」的单元连同其整支后代，
        // 平移到各父单元中心的中点，让两边家长都从自己身下垂线过来。
        List<Long> byDepth = new ArrayList<>(renderedGroups);
        byDepth.sort(Comparator.comparingLong((Long g) -> depth.getOrDefault(g, 0))
                .thenComparingLong(g -> groupMembers.get(g).get(0)));
        // 第一遍：多父居中。有多个父单元（孩子分属两家人）时，连同整支后代挪到各父单元中心的中点。
        for (Long g : byDepth) {
            List<Long> pgs = new ArrayList<>();
            for (Long p : groupParents.getOrDefault(g, new LinkedHashSet<>())) {
                if (renderedGroups.contains(p) && !pgs.contains(p)) {
                    pgs.add(p);
                }
            }
            if (!MULTI_PARENT_CENTER || pgs.size() < 2) {
                continue; // 只有一个（或没有）父单元，保持 tidy-tree 的结果
            }
            double sum = 0;
            for (Long p : pgs) {
                sum += center.getOrDefault(p, 0.0);
            }
            double delta = (sum / pgs.size()) - center.getOrDefault(g, 0.0);
            if (Math.abs(delta) > 0.5) {
                shiftCrossAxis(g, delta, lr, true, treeChildren, renderedGroups, groupMembers, pos, center);
            }
        }

        // pinLinks：被"竖线对齐"钉在一起的两个单元（孩子组 ↔ 其上方那位家长）。
        // 洗版推开某一根竖线时，另一头必须跟着走，否则刚拉直的父子线又歪了。
        Map<Long, Long> pinLinks = new HashMap<>();

        // 第二遍：直系对齐（单亲 + 独苗 → 拉成一条竖线）。
        // 场景：冯贵英（冯富忠的女儿）嫁进孔家后，和丈夫孔令兴被并进同一个家庭单元
        // （前配偶也要并到一起，才会画那条虚线），于是「冯富忠 → 冯贵英」在 tidy-tree 里
        // 成了夫家那根主干上的一根侧枝，看着就像女儿跑到了亲家父母底下、自己没线。
        // 这里把「只有一位成员、孩子全在另一个单元」的家长，挪到那个孩子本人的正上方，
        // 父女/父子成一条笔直的竖线；只挪父母，孩子留在原位（孩子的横向由其子树决定）。
        // ⚠️ 必须放在洗版之后：洗版会把孩子单元往右推，先对齐再洗版的话，竖线又被拉歪了。
        // ⚠️ 位移方向是「把孩子组连同整支后代挪到家长下方」，而不是把家长搬到孩子头上：
        //    家长往往是同层里另一家人的邻居（冯富忠与 孔祥德/肖启云同属第 0 层），
        //    把家长往左搬会直接撞上人家的卡片；反过来让孩子那支挪过去，两边都不挤。
        for (Long g : byDepth) {
            List<Long> gmembers = groupMembers.get(g);
            if (gmembers == null || gmembers.size() != 1) {
                continue; // 只对单亲（如丧偶/独居的父亲）生效；夫妻仍由 tidy-tree 居中
            }
            List<Long> cgs = new ArrayList<>();
            for (Long c : groupChildren.getOrDefault(g, new LinkedHashSet<>())) {
                if (renderedGroups.contains(c) && !cgs.contains(c)) {
                    cgs.add(c);
                }
            }
            if (cgs.size() != 1) {
                continue; // 孩子分散在多个单元，无从谈"正上方"
            }
            Long cg = cgs.get(0);
            Long p = gmembers.get(0);                     // 这位单亲
            List<Long> kids = new ArrayList<>(childrenOf.getOrDefault(p, new LinkedHashSet<>()));
            if (kids.isEmpty()) {
                continue;
            }
            for (Long k : kids) {
                if (!cg.equals(groupOf.get(k))) {         // 孩子不在那唯一一个单元里 → 放弃
                    kids.clear();
                    break;
                }
            }
            if (kids.isEmpty()) {
                continue;
            }
            double sum = 0;
            for (Long k : kids) {
                sum += center.getOrDefault(cg, 0.0) + memberOffset(cg, k, groupMembers) * (NODE_W + SPOUSE_GAP);
            }
            double pc = center.getOrDefault(g, 0.0);      // 家长（不动）
            double cur = sum / kids.size();               // 孩子当前所在
            double delta = pc - cur;
            if (Math.abs(delta) > 0.5) {
                shiftCrossAxis(cg, delta, lr, true, treeChildren, renderedGroups, groupMembers, pos, center);
            }
            pinLinks.put(cg, g);
            pinLinks.put(g, cg);
        }

        // 第三遍：同层去重叠（洗版）。
        // 直系对齐把某一支挪过去之后，可能压到同层邻居身上 —— 例如冯贵英那组被挪到中间后，
        // 正好盖在同期兄弟 孔令魁 的卡片上。这里按「层」把单元重排一遍，
        // 保证同层单元之间至少留 MIN_GAP，卡片不会叠在一起。
        // ⚠️ 平移必须 withSubtree=true（连同整支后代一起挪）：否则父单元被推到同层邻居
        // 右边，孩子还留在 tidy-tree 算的旧位置上，父子线斜得离谱（实测 冯贵英一组被挪到
        // x=777，孩子却停在 474.5/670.5，看起来像属于夫家另一支）。
        // ⚠️ 被"钉"住的竖线两头一起平移（pinLinks），否则洗版一推，刚拉直的父子线又歪了。
        int maxLv = 0;
        for (Long g : renderedGroups) {
            maxLv = Math.max(maxLv, depth.getOrDefault(g, 0));
        }
        for (int lv = 0; lv <= maxLv; lv++) {
            List<Long> row = new ArrayList<>();
            for (Long g : renderedGroups) {
                if (depth.getOrDefault(g, 0) == lv) {
                    row.add(g);
                }
            }
            if (row.size() < 2) {
                continue;
            }
            row.sort(Comparator.comparingDouble((Long g) -> center.getOrDefault(g, 0.0))
                    .thenComparingLong(g -> groupMembers.get(g).get(0)));
            double rowCursor = Double.NEGATIVE_INFINITY;
            for (Long g : row) {
                int n = groupMembers.get(g).size();
                double half = (n * NODE_W + (n - 1) * SPOUSE_GAP) / 2.0;
                double c = center.getOrDefault(g, 0.0);
                if (rowCursor != Double.NEGATIVE_INFINITY && c < rowCursor + MIN_GAP + half) {
                    double target = rowCursor + MIN_GAP + half;
                    double delta = target - c;
                    shiftCrossAxis(g, delta, lr, true, treeChildren, renderedGroups, groupMembers, pos, center);
                    Long pin = pinLinks.get(g);
                    if (pin != null && !pin.equals(g)) {
                        // 竖线另一头（家长）跟着一起挪，保持笔直
                        shiftCrossAxis(pin, delta, lr, false, treeChildren, renderedGroups, groupMembers, pos, center);
                    }
                    c = center.getOrDefault(g, target);
                }
                rowCursor = c + half;
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
        // 父子边按「孩子 + 家长所在的家庭单元」聚合：
        //  · 同一单元的多位家长（夫妻）合并为一条，从两人中间垂下；
        //  · 家长分属不同单元（父亲家 / 母亲家 / 继父母）时，两边各垂一条。
        // ⚠️ coupleCenter 只取「本条关系涉及的这几位家长」的坐标均值，不能取整个单元中心：
        //    否则单亲父亲（如冯富忠）会被并进女婿父母的单元，线从亲家桌底下来，
        //    看上去就是「两人之间没有连线」。
        // 注意：这里的 parentsOf 是「孩子 -> 家长集合」；childrenOf 则是「家长 -> 孩子集合」，
        // 遍历时别搞反，否则父子边会把 source/target 画反、coupleCenter 取到孩子自己身上。
        for (Map.Entry<Long, LinkedHashSet<Long>> e : parentsOf.entrySet()) {
            Long child = e.getKey();
            if (!nodeMap.containsKey(child)) {
                continue;
            }
            Map<Long, List<Long>> parentsByGroup = new LinkedHashMap<>();
            boolean hasStep = false;
            for (Long p : e.getValue()) {
                if (!nodeMap.containsKey(p)) {
                    continue;
                }
                Long pg = groupOf.get(p);
                if (pg == null) {
                    continue;
                }
                parentsByGroup.computeIfAbsent(pg, k -> new ArrayList<>()).add(p);
                String t = parentType.get(child + "#" + p);
                if (t != null && t.startsWith("STEP_")) {
                    hasStep = true;
                }
            }
            for (Map.Entry<Long, List<Long>> pe : parentsByGroup.entrySet()) {
                List<Long> ps = pe.getValue();       // 本单元里真正当这个孩子家长的人
                double sum = 0;
                for (Long p : ps) {
                    double[] pp = pos.get(p);
                    if (pp != null) {
                        sum += lr ? pp[1] : pp[0];  // TB 取 x，LR 取 y
                    }
                }
                Long source = Collections.min(ps);
                FamilyTreeVO.TreeEdge edge = new FamilyTreeVO.TreeEdge();
                edge.setSource(String.valueOf(source));
                edge.setTarget(String.valueOf(child));
                edge.setType(hasStep ? "STEP_PARENT" : "PARENT");
                edge.setCoupleCenter(ps.isEmpty() ? 0.0 : sum / ps.size());
                vo.getEdges().add(edge);
            }
        }
        // 夫妻边：双向存两条，只输出 id 较小的一端
        List<FamilyRelation> spouseRels = new ArrayList<>();
        for (FamilyRelation r : relations) {
            Long a = r.getMemberAId();
            Long b = r.getMemberBId();
            if (!nodeMap.containsKey(a) || !nodeMap.containsKey(b)) {
                continue;
            }
            if ("SPOUSE".equals(r.getRelationType()) || "EX_SPOUSE".equals(r.getRelationType())) {
                if (a > b) {
                    continue; // 夫妻双向存两条，只输出一条
                }
                spouseRels.add(r);
            }
            // SON / DAUGHTER / ADOPTED_* 是反向边，父子方向已覆盖，不重复画线
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
        // 画布尺寸按「最终节点包围盒」算，而不是 tidy-tree 的跨度：
        // 8.5 的三遍后处理（直系对齐 / 洗版）会把整支后裔推出 tidy-tree 的区间，
        // 照跨度给宽度会直接把最右侧那张卡片裁掉半张（实测 冯贵英 的右边被切 28px）。
        double right = PADDING;
        double bottom = PADDING;
        for (double[] p : pos.values()) {
            // TB：x 是交叉轴（卡片宽 NODE_W），y 是层轴（卡片高 NODE_H）；LR 反过来
            double rx = lr ? p[1] : p[0];
            double by = lr ? p[0] : p[1];
            right = Math.max(right, rx + (lr ? NODE_H / 2.0 : NODE_W / 2.0));
            bottom = Math.max(bottom, by + (lr ? NODE_W / 2.0 : NODE_H / 2.0));
        }
        int w = (int) Math.max(right + PADDING, PADDING * 2);
        int h = (int) Math.max(bottom + PADDING, PADDING * 2);
        if (lr) {
            vo.setWidth(h);
            vo.setHeight(w);
        } else {
            vo.setWidth(w);
            vo.setHeight(h);
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

    /**
     * 该单元中某位成员相对单元中心的横向偏移（成员按 sortGroupMembers 排序后等距排列）。
     * 用来把单亲对准到某个孩子本人，而不是整个孩子单元的中点。
     */
    private static double memberOffset(Long groupId, Long memberId, Map<Long, List<Long>> groupMembers) {
        List<Long> mids = groupMembers.get(groupId);
        if (mids == null) {
            return 0;
        }
        int idx = mids.indexOf(memberId);
        if (idx < 0) {
            return 0;
        }
        return idx - (mids.size() - 1) / 2.0;
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
     * 沿「交叉轴」平移某个单元（可选连同其整支后代）。
     * TB 布局时交叉轴是 x，LR 布局时是 y（层号永远走另一根轴）。
     * 同时同步 center，保证后续父子边的出线点（coupleCenter）跟着一起走。
     *
     * @param withSubtree true=连同后代整体平移（多父居中用），false=只挪自己（单子直连用）
     */
    private static void shiftCrossAxis(Long g,
                                       double delta,
                                       boolean lr,
                                       boolean withSubtree,
                                       Map<Long, List<Long>> treeChildren,
                                       Set<Long> rendered,
                                       Map<Long, List<Long>> groupMembers,
                                       Map<Long, double[]> pos,
                                       Map<Long, Double> center) {
        Set<Long> subtree = new LinkedHashSet<>();
        if (withSubtree) {
            Deque<Long> stack = new ArrayDeque<>();
            stack.push(g);
            while (!stack.isEmpty()) {
                Long cur = stack.pop();
                if (!rendered.contains(cur) || !subtree.add(cur)) {
                    continue;
                }
                for (Long c : treeChildren.getOrDefault(cur, Collections.<Long>emptyList())) {
                    stack.push(c);
                }
            }
        } else {
            subtree.add(g);
        }
        for (Long sg : subtree) {
            for (Long mid : groupMembers.getOrDefault(sg, Collections.<Long>emptyList())) {
                double[] p = pos.get(mid);
                if (p != null) {
                    if (lr) {
                        p[1] += delta;
                    } else {
                        p[0] += delta;
                    }
                }
            }
            Double c = center.get(sg);
            if (c != null) {
                center.put(sg, c + delta);
            }
        }
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
