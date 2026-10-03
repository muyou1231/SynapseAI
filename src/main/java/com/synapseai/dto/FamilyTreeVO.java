package com.synapseai.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 家族树出参：适配 AntV G6 渲染。
 * <p>
 * 坐标由后端统一计算（{@link com.synapseai.service.FamilyTreeLayout}），
 * 好处有两个：
 * <ol>
 *   <li>前端无需再跑一次布局算法，首屏更快，500+ 节点也不卡；</li>
 *   <li>导出长图 / PDF 复用同一份坐标，页面所见即导出所得。</li>
 * </ol>
 */
public class FamilyTreeVO {

    /** 家族 id */
    private Long familyId;

    /** 家族名称 */
    private String familyName;

    /** 布局方向：TB=纵向（父上子下） LR=横向（父左子右） */
    private String direction = "TB";

    /** 是否只读（分享链接 / 非创建人访问时为 true，前端据此隐藏编辑入口） */
    private boolean readonly = false;

    /** 成员总数（含被折叠未展开的后代） */
    private int totalMemberCount;

    /** 因超过 maxDepth 被折叠的节点数（>0 说明树是"部分渲染"，前端可提示"展开全部"） */
    private int foldedCount;

    private List<TreeNode> nodes = new ArrayList<>();

    private List<TreeEdge> edges = new ArrayList<>();

    /** 画布内容包围盒，前端据此做初始缩放与"一键居中" */
    private int width;

    private int height;

    // ===================== 节点 =====================

    public static class TreeNode {

        /** 成员 id（G6 节点 id，转 String 避免类型差异） */
        private String id;

        private Long memberId;

        private String name;

        /** 专属头像 URL（可能为空） */
        private String avatar;

        /** 相册首图 URL（无专属头像时作为兜底展示，仍为空才回退姓氏首字） */
        private String photoUrl;

        /** 性别：0=未知 1=男 2=女 */
        private Integer gender;

        /** 出生年份（字符串，可能为 ""） */
        private String birthYear;

        /** 逝世年份（已逝世且日期未知时为 ""） */
        private String deathYear;

        /** 是否已逝世（true 且 deathYear 为空 = 逝世日期未知） */
        private boolean deceased = false;

        /** 是否在世 */
        private boolean alive = true;

        /** 辈分层号（0 起） */
        private Integer generation = 0;

        /** 人物简介摘要（tooltip 用，最长 100 字） */
        private String bioBrief;

        /** 布局坐标（后端算好） */
        private double x;

        private double y;

        /** 配偶分组 id：同一组的人在同一"家庭单元"内横向排列 */
        private Integer groupId;

        /** 是否处于折叠状态（后代未展开） */
        private boolean collapsed = false;

        /** 折叠的后代数量，>0 时前端显示"+N" */
        private int foldedDescendants = 0;

        /** 直系子女数量 */
        private int childCount = 0;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Long getMemberId() {
            return memberId;
        }

        public void setMemberId(Long memberId) {
            this.memberId = memberId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAvatar() {
            return avatar;
        }

        public void setAvatar(String avatar) {
            this.avatar = avatar;
        }

        public String getPhotoUrl() {
            return photoUrl;
        }

        public void setPhotoUrl(String photoUrl) {
            this.photoUrl = photoUrl;
        }

        public Integer getGender() {
            return gender;
        }

        public void setGender(Integer gender) {
            this.gender = gender;
        }

        public String getBirthYear() {
            return birthYear;
        }

        public void setBirthYear(String birthYear) {
            this.birthYear = birthYear;
        }

        public String getDeathYear() {
            return deathYear;
        }

        public void setDeathYear(String deathYear) {
            this.deathYear = deathYear;
        }

        public boolean isDeceased() {
            return deceased;
        }

        public void setDeceased(boolean deceased) {
            this.deceased = deceased;
        }

        public boolean isAlive() {
            return alive;
        }

        public void setAlive(boolean alive) {
            this.alive = alive;
        }

        public Integer getGeneration() {
            return generation;
        }

        public void setGeneration(Integer generation) {
            this.generation = generation;
        }

        public String getBioBrief() {
            return bioBrief;
        }

        public void setBioBrief(String bioBrief) {
            this.bioBrief = bioBrief;
        }

        public double getX() {
            return x;
        }

        public void setX(double x) {
            this.x = x;
        }

        public double getY() {
            return y;
        }

        public void setY(double y) {
            this.y = y;
        }

        public Integer getGroupId() {
            return groupId;
        }

        public void setGroupId(Integer groupId) {
            this.groupId = groupId;
        }

        public boolean isCollapsed() {
            return collapsed;
        }

        public void setCollapsed(boolean collapsed) {
            this.collapsed = collapsed;
        }

        public int getFoldedDescendants() {
            return foldedDescendants;
        }

        public void setFoldedDescendants(int foldedDescendants) {
            this.foldedDescendants = foldedDescendants;
        }

        public int getChildCount() {
            return childCount;
        }

        public void setChildCount(int childCount) {
            this.childCount = childCount;
        }
    }

    // ===================== 连线 =====================

    public static class TreeEdge {

        /** 起点成员 id（String） */
        private String source;

        /** 终点成员 id（String） */
        private String target;

        /**
         * 关系类型（决定线型）：
         * SPOUSE=夫妻实线 / EX_SPOUSE=离异虚线 / PARENT=父子实线 /
         * STEP_PARENT=继父母虚线 / ADOPTED=收养虚线
         */
        private String type;

        private String label;

        /**
         * 出线点（可空）。当同一孩子的多位家长同属一个家庭单元（夫妻 / 共同育儿）时，
         * 多条父子边会合并为一条，本字段记录单元的中点：TB 方向为 x 坐标，LR 方向为 y 坐标。
         * 前端与导出器据此从「两人中间」出线，而不是从其中一人的卡片边缘出线。
         */
        private Double coupleCenter;

        /**
         * 是否为「侧亲线」（可空，默认 false）。
         * 孩子的实际住处（跟配偶住在一起）离亲生父母很远时，父子线会横穿整张图；
         * 这类线若和本支 sibling 的横梁画在同一高度，视觉上会连成一根通长横梁，
         * 看上去像全屋檐下的孩子共同一对父母。这里标记出来：
         * 前端与导出器把它下移一格 + 改用浅色虚线，两家人一眼就能分开。
         */
        private Boolean sideLink = false;

        public Double getCoupleCenter() {
            return coupleCenter;
        }

        public void setCoupleCenter(Double coupleCenter) {
            this.coupleCenter = coupleCenter;
        }

        public Boolean getSideLink() {
            return sideLink;
        }

        public void setSideLink(Boolean sideLink) {
            this.sideLink = sideLink;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }

    public Long getFamilyId() {
        return familyId;
    }

    public void setFamilyId(Long familyId) {
        this.familyId = familyId;
    }

    public String getFamilyName() {
        return familyName;
    }

    public void setFamilyName(String familyName) {
        this.familyName = familyName;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public boolean isReadonly() {
        return readonly;
    }

    public void setReadonly(boolean readonly) {
        this.readonly = readonly;
    }

    public int getTotalMemberCount() {
        return totalMemberCount;
    }

    public void setTotalMemberCount(int totalMemberCount) {
        this.totalMemberCount = totalMemberCount;
    }

    public int getFoldedCount() {
        return foldedCount;
    }

    public void setFoldedCount(int foldedCount) {
        this.foldedCount = foldedCount;
    }

    public List<TreeNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<TreeNode> nodes) {
        this.nodes = nodes;
    }

    public List<TreeEdge> getEdges() {
        return edges;
    }

    public void setEdges(List<TreeEdge> edges) {
        this.edges = edges;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }
}
