package com.synapseai.service;

import com.synapseai.common.BizException;
import com.synapseai.dto.EventSaveReq;
import com.synapseai.dto.FamilySaveReq;
import com.synapseai.dto.FamilyTreeVO;
import com.synapseai.dto.MemberSaveReq;
import com.synapseai.dto.RelationCreateReq;
import com.synapseai.entity.*;
import com.synapseai.mapper.*;
import com.synapseai.util.FamilyTreeExporter;
import com.synapseai.util.FamilyTreeLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 家族族谱 / 家庭树核心业务。
 * <p>
 * 权限模型（贯穿全模块）：
 * <ul>
 *   <li><b>可写</b>：家族创建人，或在该家族中已绑定自己账号的成员（家族成员本人）。</li>
 *   <li><b>可读</b>：可写者；或家族为公开（visibility=2）；或持有有效的只读分享 token。</li>
 *   <li>私有家族（visibility=0）对无关用户完全不可见，鉴权在 Service 层统一拦截。</li>
 * </ul>
 * 并发控制：家族与成员的更新都带 {@code version} 乐观锁，
 * 更新行数为 0 即表示"他人已抢先修改"，抛出 {@link BizException} 提示前端刷新后重试。
 */
@Service
public class FamilyService {

    private static final Logger log = LoggerFactory.getLogger(FamilyService.class);

    /** 单个用户最多创建的家族数 */
    private static final int MAX_FAMILY_PER_USER = 20;
    /** 单家族成员上限（防止极端数据拖垮构图） */
    private static final int MAX_MEMBER_PER_FAMILY = 5000;
    /** 默认渲染层数，超出的后代折叠（超大家族懒加载） */
    private static final int DEFAULT_MAX_DEPTH = 4;

    private static final Set<String> PARENT_TYPES = Set.of("FATHER", "MOTHER", "STEP_FATHER", "STEP_MOTHER");

    @Autowired
    private FamilyMapper familyMapper;
    @Autowired
    private FamilyMemberMapper memberMapper;
    @Autowired
    private FamilyRelationMapper relationMapper;
    @Autowired
    private FamilyMemberPhotoMapper photoMapper;
    @Autowired
    private FamilyEventMapper eventMapper;
    @Autowired
    private FileService fileService;

    // ==================================================================
    // 一、家族 CRUD
    // ==================================================================

    /** 创建家族，返回新家族 id */
    public Long createFamily(Long uid, FamilySaveReq req) {
        if (familyMapper.countByUser(uid) >= MAX_FAMILY_PER_USER) {
            throw new BizException("每个账号最多创建 " + MAX_FAMILY_PER_USER + " 个家族");
        }
        Family f = new Family();
        f.setName(req.getName().trim());
        f.setIntro(req.getIntro() == null ? "" : req.getIntro().trim());
        f.setCoverUrl(req.getCoverUrl() == null ? "" : req.getCoverUrl().trim());
        f.setUserId(uid);
        f.setVisibility(req.getVisibility() == null ? 0 : req.getVisibility());
        familyMapper.insert(f);
        return f.getId();
    }

    /** 我的家族列表（我创建的 + 我作为成员被登记的），带成员数 */
    public List<Map<String, Object>> listMine(Long uid) {
        List<Family> list = familyMapper.listMine(uid);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Family f : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("name", f.getName());
            m.put("intro", f.getIntro());
            m.put("coverUrl", f.getCoverUrl());
            m.put("visibility", f.getVisibility());
            m.put("shareToken", f.getShareToken());
            m.put("memberCount", f.getMemberCount() == null ? 0 : f.getMemberCount());
            m.put("version", f.getVersion());
            m.put("createTime", f.getCreateTime());
            m.put("updateTime", f.getUpdateTime());
            m.put("owner", Objects.equals(f.getUserId(), uid));
            m.put("writable", true); // 列表里出现的都是我有份的家族
            out.add(m);
        }
        return out;
    }

    /** 编辑家族（乐观锁） */
    public void updateFamily(Long uid, FamilySaveReq req) {
        Family f = requireFamily(req.getId());
        assertWrite(f, uid);
        f.setName(req.getName().trim());
        f.setIntro(req.getIntro() == null ? "" : req.getIntro().trim());
        f.setCoverUrl(req.getCoverUrl() == null ? "" : req.getCoverUrl().trim());
        f.setVisibility(req.getVisibility() == null ? 0 : req.getVisibility());
        f.setVersion(req.getVersion());
        if (familyMapper.updateWithVersion(f) == 0) {
            throw new BizException(409, "家族信息已被他人修改，请刷新后重试");
        }
    }

    /** 删除家族：级联清理成员、关系、相册、大事记 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteFamily(Long uid, Long familyId) {
        Family f = requireFamily(familyId);
        if (!Objects.equals(f.getUserId(), uid)) {
            throw new BizException("只有创建人可以删除家族");
        }
        List<FamilyMember> members = memberMapper.listByFamily(familyId);
        for (FamilyMember m : members) {
            photoMapper.softDeleteByMember(m.getId());
            memberMapper.softDelete(m.getId());
        }
        relationMapper.deleteByFamily(familyId);
        eventMapper.deleteByFamily(familyId);
        familyMapper.softDelete(familyId);
    }

    /** 家族详情（含权限标记） */
    public Map<String, Object> familyDetail(Long uid, Long familyId) {
        Family f = requireFamily(familyId);
        assertRead(f, uid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("name", f.getName());
        m.put("intro", f.getIntro());
        m.put("coverUrl", f.getCoverUrl());
        m.put("visibility", f.getVisibility());
        m.put("shareToken", f.getShareToken());
        m.put("version", f.getVersion());
        m.put("memberCount", memberMapper.countByFamily(familyId));
        m.put("owner", Objects.equals(f.getUserId(), uid));
        m.put("writable", isWritable(f, uid));
        m.put("createTime", f.getCreateTime());
        return m;
    }

    // ==================================================================
    // 二、成员 CRUD
    // ==================================================================

    /** 新增或编辑成员（乐观锁）；返回成员 id */
    public Long saveMember(Long uid, MemberSaveReq req) {
        LocalDate birth = parseDate(req.getBirthDate(), "出生日期");
        LocalDate death = parseDate(req.getDeathDate(), "逝世日期");
        // 逝世标记：优先取前端显式值；未传时按「是否填了逝世日期」推导，兼容旧客户端。
        // 填了逝世日期一律视为已逝世；标记为在世则清空逝世日期，避免出现「在世却有忌日」的矛盾数据。
        boolean deceased = req.getDeceased() != null ? req.getDeceased() : (death != null);
        if (death != null) {
            deceased = true;
        } else if (!deceased) {
            death = null;
        }
        if (birth != null && death != null && death.isBefore(birth)) {
            throw new BizException("逝世日期不能早于出生日期");
        }
        if (req.getId() == null) {
            // ---- 新增 ----
            Family f = requireFamily(req.getFamilyId());
            assertWrite(f, uid);
            if (memberMapper.countByFamily(req.getFamilyId()) >= MAX_MEMBER_PER_FAMILY) {
                throw new BizException("单个家族成员数已达上限 " + MAX_MEMBER_PER_FAMILY);
            }
            FamilyMember m = new FamilyMember();
            m.setFamilyId(req.getFamilyId());
            m.setUserId(req.getUserId());
            m.setName(req.getName().trim());
            m.setAvatarUrl(req.getAvatarUrl() == null ? "" : req.getAvatarUrl());
            m.setGender(req.getGender() == null ? 0 : req.getGender());
            m.setBirthDate(birth);
            m.setDeceased(deceased);
            m.setDeathDate(death);
            m.setBio(req.getBio());
            m.setOccupation(req.getOccupation() == null ? "" : req.getOccupation());
            m.setHometown(req.getHometown() == null ? "" : req.getHometown());
            m.setPhone(req.getPhone() == null ? "" : req.getPhone());
            m.setAddress(req.getAddress() == null ? "" : req.getAddress());
            m.setRemark(req.getRemark() == null ? "" : req.getRemark());
            memberMapper.insert(m);
            return m.getId();
        }
        // ---- 编辑 ----
        FamilyMember old = memberMapper.selectById(req.getId());
        if (old == null) {
            throw new BizException("成员不存在或已删除");
        }
        assertWrite(requireFamily(old.getFamilyId()), uid);
        old.setName(req.getName().trim());
        old.setAvatarUrl(req.getAvatarUrl() == null ? "" : req.getAvatarUrl());
        old.setGender(req.getGender() == null ? 0 : req.getGender());
        old.setBirthDate(birth);
        old.setDeceased(deceased);
        old.setDeathDate(death);
        old.setBio(req.getBio());
        old.setOccupation(req.getOccupation() == null ? "" : req.getOccupation());
        old.setHometown(req.getHometown() == null ? "" : req.getHometown());
        old.setPhone(req.getPhone() == null ? "" : req.getPhone());
        old.setAddress(req.getAddress() == null ? "" : req.getAddress());
        old.setRemark(req.getRemark() == null ? "" : req.getRemark());
        old.setVersion(req.getVersion());
        if (memberMapper.updateWithVersion(old) == 0) {
            throw new BizException(409, "该成员信息已被他人修改，请刷新后重试");
        }
        return old.getId();
    }

    /** 成员详情：基础信息 + 相册 + 关联亲属 */
    public Map<String, Object> memberDetail(Long uid, Long memberId) {
        FamilyMember m = memberMapper.selectById(memberId);
        if (m == null) {
            throw new BizException("成员不存在或已删除");
        }
        assertRead(requireFamily(m.getFamilyId()), uid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId());
        out.put("familyId", m.getFamilyId());
        out.put("userId", m.getUserId());
        out.put("name", m.getName());
        out.put("avatarUrl", m.getAvatarUrl());
        out.put("gender", m.getGender());
        out.put("birthDate", m.getBirthDate() == null ? "" : m.getBirthDate().toString());
        out.put("deceased", m.getDeceased() != null && m.getDeceased());
        out.put("deathDate", m.getDeathDate() == null ? "" : m.getDeathDate().toString());
        out.put("bio", m.getBio() == null ? "" : m.getBio());
        out.put("occupation", m.getOccupation());
        out.put("hometown", m.getHometown());
        out.put("phone", m.getPhone());
        out.put("address", m.getAddress());
        out.put("remark", m.getRemark());
        out.put("version", m.getVersion());
        out.put("photos", photoMapper.listByMember(memberId));
        out.put("relations", listRelations(memberId));
        out.put("writable", isWritable(requireFamily(m.getFamilyId()), uid));
        return out;
    }

    /**
     * 删除前预检：返回影响范围（后代数量、关系数量），供前端二次确认。
     * 注意：<b>不会删除后代</b>，只是解除关系——后代成员仍保留在家族中，仅失去与本人的连线。
     */
    public Map<String, Object> deletePreview(Long uid, Long memberId) {
        FamilyMember m = memberMapper.selectById(memberId);
        if (m == null) {
            throw new BizException("成员不存在或已删除");
        }
        assertWrite(requireFamily(m.getFamilyId()), uid);
        List<FamilyRelation> all = relationMapper.listByFamily(m.getFamilyId());
        Set<Long> desc = descendants(memberId, all);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", m.getName());
        out.put("descendantCount", desc.size());
        out.put("relationCount", relationMapper.listByMember(memberId).size());
        out.put("photoCount", photoMapper.countByMember(memberId));
        out.put("descendantNames", namesOf(desc, m.getFamilyId()));
        return out;
    }

    /** 删除成员：级联清理其全部关系与相册 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteMember(Long uid, Long memberId) {
        FamilyMember m = memberMapper.selectById(memberId);
        if (m == null) {
            throw new BizException("成员不存在或已删除");
        }
        assertWrite(requireFamily(m.getFamilyId()), uid);
        relationMapper.deleteByMember(memberId);
        photoMapper.softDeleteByMember(memberId);
        memberMapper.softDelete(memberId);
    }

    /** 上传并更新成员头像 */
    public String updateMemberAvatar(Long uid, Long memberId, MultipartFile file) {
        FamilyMember m = memberMapper.selectById(memberId);
        if (m == null) {
            throw new BizException("成员不存在或已删除");
        }
        assertWrite(requireFamily(m.getFamilyId()), uid);
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的图片");
        }
        try {
            String url = fileService.upload(file, "family/avatar");
            memberMapper.updateAvatar(memberId, url);
            return url;
        } catch (Exception e) {
            throw new BizException("头像上传失败：" + e.getMessage());
        }
    }

    // ==================================================================
    // 三、成员相册
    // ==================================================================

    public List<FamilyMemberPhoto> listPhotos(Long uid, Long memberId) {
        FamilyMember m = memberMapper.selectById(memberId);
        if (m == null) {
            throw new BizException("成员不存在或已删除");
        }
        assertRead(requireFamily(m.getFamilyId()), uid);
        return photoMapper.listByMember(memberId);
    }

    /** 批量上传相册图片，返回新增的图片条数 */
    @Transactional(rollbackFor = Exception.class)
    public int addPhotos(Long uid, Long memberId, List<MultipartFile> files, String[] captions) {
        FamilyMember m = memberMapper.selectById(memberId);
        if (m == null) {
            throw new BizException("成员不存在或已删除");
        }
        assertWrite(requireFamily(m.getFamilyId()), uid);
        if (files == null || files.isEmpty()) {
            throw new BizException("请选择要上传的图片");
        }
        int sort = photoMapper.maxSortNo(memberId);
        int ok = 0;
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file == null || file.isEmpty()) {
                continue;
            }
            try {
                String url = fileService.upload(file, "family/photo");
                FamilyMemberPhoto p = new FamilyMemberPhoto();
                p.setMemberId(memberId);
                p.setUrl(url);
                p.setCaption(captions != null && i < captions.length ? captions[i] : "");
                p.setSortNo(++sort);
                photoMapper.insert(p);
                ok++;
            } catch (Exception e) {
                log.warn("相册图片上传失败 memberId={}: {}", memberId, e.getMessage());
            }
        }
        if (ok == 0) {
            throw new BizException("图片上传失败，请重试");
        }
        return ok;
    }

    public void deletePhoto(Long uid, Long photoId) {
        FamilyMemberPhoto p = photoMapper.selectById(photoId);
        if (p == null) {
            throw new BizException("图片不存在");
        }
        FamilyMember m = memberMapper.selectById(p.getMemberId());
        if (m == null) {
            throw new BizException("成员不存在");
        }
        assertWrite(requireFamily(m.getFamilyId()), uid);
        photoMapper.softDelete(photoId);
    }

    // ==================================================================
    // 四、亲属关系
    // ==================================================================

    /**
     * 建立亲属关系。六种业务动作见 {@link RelationCreateReq#getRelationType()} 的说明。
     * 返回新建成员 id（若现场创建了对方成员）或对方已有成员 id。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long addRelation(Long uid, RelationCreateReq req) {
        FamilyMember self = memberMapper.selectById(req.getMemberId());
        if (self == null) {
            throw new BizException("成员不存在或已删除");
        }
        Family f = requireFamily(self.getFamilyId());
        assertWrite(f, uid);
        String type = req.getRelationType();
        String desc = req.getRelationDesc() == null ? "" : req.getRelationDesc();

        // 「添加父母」走独立的双亲分支：支持一次同时指定父亲 + 母亲，因此不强制要求 relativeId / newRelative
        boolean parentMode = "PARENT".equals(type) || "STEP_PARENT".equals(type);

        // 对方：已有成员 or 现场新建
        FamilyMember other = null;
        if (parentMode) {
            // 兼容旧调用：仍可通过 relativeId / newRelative 只指定一位家长，按其性别判定是父还是母
            if (req.getRelativeId() != null) {
                other = requireSameFamily(req.getRelativeId(), f.getId(), "对方成员");
            }
        } else if (req.getRelativeId() != null) {
            other = memberMapper.selectById(req.getRelativeId());
            if (other == null) {
                throw new BizException("对方成员不存在或已删除");
            }
            if (!Objects.equals(other.getFamilyId(), self.getFamilyId())) {
                throw new BizException("不能跨家族建立亲属关系");
            }
        } else {
            if (req.getNewRelative() == null || req.getNewRelative().getName() == null
                    || req.getNewRelative().getName().trim().isEmpty()) {
                throw new BizException("请填写亲属姓名");
            }
            other = createRelative(f.getId(), req.getNewRelative());
        }
        if (other != null && Objects.equals(other.getId(), self.getId())) {
            throw new BizException("不能与自己建立亲属关系");
        }

        List<FamilyRelation> all = relationMapper.listByFamily(f.getId());
        Long resultId = other == null ? self.getId() : other.getId();
        switch (type) {
            case "SPOUSE" -> {
                checkNotBlood(self.getId(), other.getId(), all, "不能与直系血亲（父母 / 子女）登记为配偶");
                insertBoth(f.getId(), self.getId(), other.getId(), "SPOUSE", desc);
            }
            case "EX_SPOUSE" -> {
                // 标记离异：把已有的双向 SPOUSE 改成 EX_SPOUSE
                int n1 = relationMapper.updateType(self.getId(), other.getId(), "SPOUSE", "EX_SPOUSE");
                int n2 = relationMapper.updateType(other.getId(), self.getId(), "SPOUSE", "EX_SPOUSE");
                if (n1 + n2 == 0) {
                    throw new BizException("两人之间不存在有效的夫妻关系，无法标记离异");
                }
            }
            case "CHILD", "ADOPTED_CHILD" -> {
                boolean adopted = "ADOPTED_CHILD".equals(type);
                if (req.getFatherId() != null || req.getMotherId() != null) {
                    // 前端显式指定了父亲 / 母亲：以选中的两位家长各建一条父子边
                    addChildWithParents(f.getId(), req.getFatherId(), req.getMotherId(), other, adopted, desc, all);
                } else {
                    // 未指定：沿用「本人 + 唯一配偶自动补位」，并防环（不能把长辈登记为子女）
                    if (ancestors(self.getId(), all).contains(other.getId())) {
                        throw new BizException("不能把长辈（祖先）登记为子女，会造成辈分循环");
                    }
                    addChild(f.getId(), self, other, adopted, desc, all);
                }
            }
            case "PARENT", "STEP_PARENT" -> {
                // 添加父母：可同时指定一位父亲 + 一位母亲（也可只给其一）
                boolean step = "STEP_PARENT".equals(type);
                List<Long> added = addParents(f, self, req, other, step, desc, all);
                if (!added.isEmpty()) {
                    resultId = added.get(added.size() - 1);
                }
            }
            default -> throw new BizException("不支持的关系类型：" + type);
        }
        return resultId;
    }

    /**
     * 添加父母：一次最多登记「一位父亲 + 一位母亲」。
     * <p>
     * 业务约束（用户明确要求）：
     * <ol>
     *   <li>可以同时选两个人，但只能是一父一母；</li>
     *   <li>父亲必须是男性（或性别未知），母亲必须是女性（或性别未知）—— 两个男的 / 两个女的直接拒绝；</li>
     *   <li>同一角色不能重复登记（已有父亲时再加一位父亲会被拒绝，需先解除）；</li>
     *   <li>被登记为父母的人不能是本人的晚辈（防辈分循环）。</li>
     * </ol>
     *
     * @param legacyOther 兼容旧调用（只传了 relativeId / newRelative）时的那位家长
     * @return 本次关联上的家长 id 列表
     */
    private List<Long> addParents(Family f, FamilyMember child, RelationCreateReq req,
                                  FamilyMember legacyOther, boolean step, String desc,
                                  List<FamilyRelation> all) {
        Long fatherId = req.getFatherId();
        Long motherId = req.getMotherId();
        String fatherType = step ? "STEP_FATHER" : "FATHER";
        String motherType = step ? "STEP_MOTHER" : "MOTHER";

        // 兼容旧调用：只传了一位家长时，按其性别判定角色
        if (fatherId == null && motherId == null && req.getNewFather() == null && req.getNewMother() == null
                && legacyOther != null) {
            if (legacyOther.getGender() != null && legacyOther.getGender() == 2) {
                motherId = legacyOther.getId();
            } else {
                fatherId = legacyOther.getId();
            }
        }

        if (fatherId != null && Objects.equals(fatherId, motherId)) {
            throw new BizException("父亲与母亲不能是同一人");
        }
        if (fatherId != null && Objects.equals(fatherId, child.getId())) {
            throw new BizException("不能把自己设为自己的" + (step ? "继父" : "父亲"));
        }
        if (motherId != null && Objects.equals(motherId, child.getId())) {
            throw new BizException("不能把自己设为自己的" + (step ? "继母" : "母亲"));
        }

        Set<Long> childDescendants = descendants(child.getId(), all);
        List<Long> added = new ArrayList<>();

        // ---- 父亲 ----
        if (fatherId != null) {
            FamilyMember father = requireSameFamily(fatherId, f.getId(), "所选父亲");
            checkRoleGender(father, 1, step ? "继父" : "父亲");
            checkDuplicateParent(child.getId(), fatherType, all, fatherId, step ? "继父" : "父亲");
            if (childDescendants.contains(fatherId)) {
                throw new BizException("不能把晚辈（后代）登记为" + (step ? "继父" : "父亲") + "，会造成辈分循环");
            }
            linkParent(f.getId(), fatherId, child, fatherType, false, desc);
            added.add(fatherId);
        }
        // ---- 母亲 ----
        if (motherId != null) {
            FamilyMember mother = requireSameFamily(motherId, f.getId(), "所选母亲");
            checkRoleGender(mother, 2, step ? "继母" : "母亲");
            checkDuplicateParent(child.getId(), motherType, all, motherId, step ? "继母" : "母亲");
            if (childDescendants.contains(motherId)) {
                throw new BizException("不能把晚辈（后代）登记为" + (step ? "继母" : "母亲") + "，会造成辈分循环");
            }
            linkParent(f.getId(), motherId, child, motherType, false, desc);
            added.add(motherId);
        }

        // ---- 现场新建父亲 / 母亲（性别按角色固定，避免选进两个同性）----
        RelationCreateReq.NewRelative nf = req.getNewFather();
        if (fatherId == null && nf != null && hasName(nf)) {
            if (hasGenderConflict(nf, 1)) {
                throw new BizException("新建的父亲性别应为男");
            }
            nf.setGender(1);
            FamilyMember created = createRelative(f.getId(), nf);
            linkParent(f.getId(), created.getId(), child, fatherType, false, desc);
            added.add(created.getId());
        }
        RelationCreateReq.NewRelative nm = req.getNewMother();
        if (motherId == null && nm != null && hasName(nm)) {
            if (hasGenderConflict(nm, 2)) {
                throw new BizException("新建的母亲性别应为女");
            }
            nm.setGender(2);
            FamilyMember created = createRelative(f.getId(), nm);
            linkParent(f.getId(), created.getId(), child, motherType, false, desc);
            added.add(created.getId());
        }

        if (added.isEmpty()) {
            throw new BizException("请至少选择或新建一位家长（父亲 / 母亲）");
        }
        return added;
    }

    /** 角色性别校验：男性成员不能被登记为母亲，女性成员不能被登记为父亲（性别未知则放行） */
    private void checkRoleGender(FamilyMember m, int expectedGender, String role) {
        Integer g = m.getGender();
        if (g == null || g == 0) {
            return; // 性别未知，不强行拒绝
        }
        if (g != expectedGender) {
            throw new BizException("「" + m.getName() + "」的性别为"
                    + (g == 1 ? "男" : "女") + "，不能被登记为" + role);
        }
    }

    /**
     * 同一角色不能重复登记（一个成员最多一位父亲 / 母亲；继父母同理）。
     * <p>
     * 例外：若传入的候选人<b>就是当前已登记的那位</b>，视为幂等操作直接放行 ——
     * 常见场景是「已用添加子女建好父亲，后来想补登母亲」，此时用户会顺手把父亲也一起勾上，
     * 这种情况下只应补登缺失的一方，而不是报错。
     */
    private void checkDuplicateParent(Long childId, String type, List<FamilyRelation> all,
                                      Long candidateId, String role) {
        for (FamilyRelation r : all) {
            if (!Objects.equals(r.getMemberBId(), childId) || !type.equals(r.getRelationType())) {
                continue;
            }
            if (Objects.equals(r.getMemberAId(), candidateId)) {
                return; // 同一人，幂等
            }
            FamilyMember exist = memberMapper.selectById(r.getMemberAId());
            throw new BizException("已登记" + role + "「" + (exist == null ? "未知成员" : exist.getName())
                    + "」，如需更换请先解除原有关系");
        }
    }

    private FamilyMember requireSameFamily(Long memberId, Long familyId, String label) {
        FamilyMember m = memberMapper.selectById(memberId);
        if (m == null || !Objects.equals(m.getFamilyId(), familyId)) {
            throw new BizException(label + "不存在或不在本家族");
        }
        return m;
    }

    private static boolean hasName(RelationCreateReq.NewRelative nr) {
        return nr != null && nr.getName() != null && !nr.getName().trim().isEmpty();
    }

    private static boolean hasGenderConflict(RelationCreateReq.NewRelative nr, int expected) {
        Integer g = nr.getGender();
        return g != null && g != 0 && g != expected;
    }

    /** 解除两人的全部关系（夫妻双向、父子反向边一并清理） */
    @Transactional(rollbackFor = Exception.class)
    public void removeRelation(Long uid, Long aId, Long bId) {
        FamilyMember a = memberMapper.selectById(aId);
        if (a == null) {
            throw new BizException("成员不存在或已删除");
        }
        assertWrite(requireFamily(a.getFamilyId()), uid);
        if (relationMapper.deleteBetween(aId, bId) == 0) {
            throw new BizException("两人之间没有可解除的关系");
        }
    }

    /**
     * 某成员的关联亲属列表（带对方姓名与称谓）。
     * <p>
     * 由于夫妻、父子都是"双向存两条"（正向 + 反向边），直接遍历会得到重复项。
     * 这里按对方成员聚合去重，并优先采用<b>直接称谓</b>：
     * 若自己是关系的客体 b（"对方是自己的 xxx"），称谓最精确，优先保留；
     * 只有当缺少该方向的边时，才用反向边推导（此时结合自己的性别给出准确称谓）。
     */
    public List<Map<String, Object>> listRelations(Long memberId) {
        FamilyMember self = memberMapper.selectById(memberId);
        Integer selfGender = self == null ? 0 : self.getGender();
        List<FamilyRelation> list = relationMapper.listByMember(memberId);

        // key = 对方成员 id，value = 展示数据；isDirect=true 表示由"直接称谓"的边得到
        Map<Long, Map<String, Object>> map = new LinkedHashMap<>();
        // 第一遍：只取直接称谓（自己是客体 b）
        for (FamilyRelation r : list) {
            if (!Objects.equals(r.getMemberBId(), memberId)) {
                continue;
            }
            String title = titleOf(r.getRelationType());
            if (title == null) {
                continue;
            }
            map.put(r.getMemberAId(), toRelationItem(r, r.getMemberAId(), title, true));
        }
        // 第二遍：补充只有反向边的亲属（称谓由对方视角反推）
        for (FamilyRelation r : list) {
            if (!Objects.equals(r.getMemberAId(), memberId)) {
                continue;
            }
            if (map.containsKey(r.getMemberBId())) {
                continue; // 已有更精确的直接称谓
            }
            String title = reverseTitleOf(r.getRelationType(), selfGender);
            if (title == null) {
                continue;
            }
            map.put(r.getMemberBId(), toRelationItem(r, r.getMemberBId(), title, false));
        }
        return new ArrayList<>(map.values());
    }

    private Map<String, Object> toRelationItem(FamilyRelation r, Long otherId, String title, boolean direct) {
        FamilyMember other = memberMapper.selectById(otherId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("relationId", r.getId());
        m.put("memberId", otherId);
        m.put("name", other == null ? "（已删除）" : other.getName());
        m.put("avatarUrl", other == null ? "" : other.getAvatarUrl());
        m.put("title", title);
        m.put("relationType", r.getRelationType());
        m.put("desc", r.getRelationDesc());
        m.put("direct", direct);
        return m;
    }

    // ==================================================================
    // 五、家族树
    // ==================================================================

    /**
     * 构建家族树（G6 渲染 + 导出共用）。
     *
     * @param maxDepth 渲染层数上限，超过则折叠后代（null 用默认值 4；传 0 表示全部展开）
     * @param expanded 用户手动展开的节点 id
     */
    public FamilyTreeVO tree(Long uid, Long familyId, String direction, Integer maxDepth, Set<Long> expanded) {
        Family f = requireFamily(familyId);
        assertRead(f, uid);
        return buildTree(f, direction, maxDepth, expanded, !isWritable(f, uid));
    }

    /** 只读分享入口：凭 token 查看 */
    public FamilyTreeVO treeByToken(String token, String direction, Integer maxDepth, Set<Long> expanded) {
        if (token == null || token.isBlank()) {
            throw new BizException("分享链接无效");
        }
        Family f = familyMapper.selectByShareToken(token.trim());
        if (f == null) {
            throw new BizException("分享链接无效或已被撤销");
        }
        if (f.getVisibility() == null || f.getVisibility() == 0) {
            throw new BizException("该家族已设为私有，分享链接已失效");
        }
        return buildTree(f, direction, maxDepth, expanded, true);
    }

    private FamilyTreeVO buildTree(Family f, String direction, Integer maxDepth, Set<Long> expanded, boolean readonly) {
        int depth = maxDepth == null ? DEFAULT_MAX_DEPTH : (maxDepth <= 0 ? Integer.MAX_VALUE : maxDepth);
        List<FamilyMember> members = memberMapper.listByFamily(f.getId());
        List<FamilyRelation> relations = relationMapper.listByFamily(f.getId());
        FamilyTreeVO vo = FamilyTreeLayout.build(members, relations, direction, depth,
                expanded == null ? Collections.emptySet() : expanded, firstPhotoMap(members));
        vo.setFamilyId(f.getId());
        vo.setFamilyName(f.getName());
        vo.setReadonly(readonly);
        return vo;
    }

    /**
     * 批量取成员的「相册首图」，一次查询完成（避免逐成员 N+1）。
     * 用途：族谱节点在成员没有专属头像时，用相册首图兜底展示，都没有才回退姓氏首字。
     */
    private Map<Long, String> firstPhotoMap(List<FamilyMember> members) {
        Map<Long, String> map = new HashMap<>();
        if (members == null || members.isEmpty()) {
            return map;
        }
        List<Long> ids = new ArrayList<>();
        for (FamilyMember m : members) {
            if (m != null && m.getId() != null) {
                ids.add(m.getId());
            }
        }
        if (ids.isEmpty()) {
            return map;
        }
        try {
            List<Map<String, Object>> rows = photoMapper.firstPhotoByMembers(ids);
            if (rows != null) {
                for (Map<String, Object> r : rows) {
                    Object mid = r.get("memberId");
                    Object url = r.get("url");
                    if (mid instanceof Number && url != null) {
                        map.put(((Number) mid).longValue(), String.valueOf(url));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查询相册首图失败，节点将回退显示姓氏：{}", e.getMessage());
        }
        return map;
    }

    // ==================================================================
    // 六、家族大事记
    // ==================================================================

    public Long saveEvent(Long uid, EventSaveReq req) {
        LocalDate date = parseDate(req.getEventDate(), "事件日期");
        if (date == null) {
            throw new BizException("请填写事件日期");
        }
        if (req.getId() == null) {
            Family f = requireFamily(req.getFamilyId());
            assertWrite(f, uid);
            FamilyEvent e = new FamilyEvent();
            e.setFamilyId(req.getFamilyId());
            e.setTitle(req.getTitle().trim());
            e.setEventDate(date);
            e.setContent(req.getContent());
            e.setMemberIds(joinIds(req.getMemberIds()));
            eventMapper.insert(e);
            return e.getId();
        }
        FamilyEvent old = eventMapper.selectById(req.getId());
        if (old == null) {
            throw new BizException("事件不存在");
        }
        assertWrite(requireFamily(old.getFamilyId()), uid);
        old.setTitle(req.getTitle().trim());
        old.setEventDate(date);
        old.setContent(req.getContent());
        old.setMemberIds(joinIds(req.getMemberIds()));
        eventMapper.update(old);
        return old.getId();
    }

    /** 大事记列表：按事件日期倒序 */
    public List<Map<String, Object>> listEvents(Long uid, Long familyId) {
        Family f = requireFamily(familyId);
        assertRead(f, uid);
        List<FamilyEvent> list = eventMapper.listByFamily(familyId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (FamilyEvent e : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("title", e.getTitle());
            m.put("eventDate", e.getEventDate() == null ? "" : e.getEventDate().toString());
            m.put("content", e.getContent() == null ? "" : e.getContent());
            m.put("memberIds", splitIds(e.getMemberIds()));
            m.put("memberNames", namesOf(splitIds(e.getMemberIds()), familyId));
            m.put("createTime", e.getCreateTime());
            out.add(m);
        }
        return out;
    }

    public void deleteEvent(Long uid, Long eventId) {
        FamilyEvent e = eventMapper.selectById(eventId);
        if (e == null) {
            throw new BizException("事件不存在");
        }
        assertWrite(requireFamily(e.getFamilyId()), uid);
        eventMapper.delete(eventId);
    }

    // ==================================================================
    // 七、分享链接
    // ==================================================================

    /** 生成（或复用）只读分享 token */
    public String generateShareToken(Long uid, Long familyId) {
        Family f = requireFamily(familyId);
        if (!Objects.equals(f.getUserId(), uid)) {
            throw new BizException("只有创建人可以生成分享链接");
        }
        if (f.getShareToken() != null && !f.getShareToken().isBlank()) {
            return f.getShareToken();
        }
        String token = UUID.randomUUID().toString().replace("-", "") + Long.toHexString(System.currentTimeMillis());
        familyMapper.updateShareToken(familyId, token);
        return token;
    }

    /** 撤销分享链接（置空 token，旧链接立即失效） */
    public void revokeShareToken(Long uid, Long familyId) {
        Family f = requireFamily(familyId);
        if (!Objects.equals(f.getUserId(), uid)) {
            throw new BizException("只有创建人可以撤销分享链接");
        }
        familyMapper.updateShareToken(familyId, null);
    }

    /** 只读查看分享家族的基础信息（供分享页标题使用） */
    public Map<String, Object> shareInfo(String token) {
        Family f = familyMapper.selectByShareToken(token);
        if (f == null || f.getVisibility() == null || f.getVisibility() == 0) {
            throw new BizException("分享链接无效或已失效");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("name", f.getName());
        m.put("intro", f.getIntro());
        m.put("coverUrl", f.getCoverUrl());
        m.put("memberCount", memberMapper.countByFamily(f.getId()));
        m.put("readonly", true);
        return m;
    }

    // ==================================================================
    // 八、导出
    // ==================================================================

    /**
     * 导出族谱长图（PNG）。
     *
     * @param baseUrl 站点根地址，用于把头像相对路径拼成绝对地址（导出在服务端绘制，需要能访问到图片）
     */
    public byte[] exportPng(Long uid, Long familyId, String direction, String baseUrl) {
        Family f = requireFamily(familyId);
        assertRead(f, uid);
        FamilyTreeVO vo = buildTree(f, direction, 0, null, true);
        return FamilyTreeExporter.renderPng(vo, baseUrl);
    }

    /** 导出族谱 PDF（按 A4 分页） */
    public byte[] exportPdf(Long uid, Long familyId, String direction, String baseUrl) {
        Family f = requireFamily(familyId);
        assertRead(f, uid);
        FamilyTreeVO vo = buildTree(f, direction, 0, null, true);
        return FamilyTreeExporter.renderPdf(vo, baseUrl);
    }

    // ==================================================================
    // 九、搜索
    // ==================================================================

    /** 按姓名模糊搜索成员：只搜我有权限看到的家族 */
    public List<Map<String, Object>> searchMembers(Long uid, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String kw = keyword.trim();
        List<FamilyMember> list = memberMapper.searchGlobal(uid, kw, 50);
        List<Map<String, Object>> out = new ArrayList<>();
        for (FamilyMember m : list) {
            Family f = familyMapper.selectById(m.getFamilyId());
            if (f == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("memberId", m.getId());
            item.put("familyId", f.getId());
            item.put("familyName", f.getName());
            item.put("name", m.getName());
            item.put("avatarUrl", m.getAvatarUrl());
            item.put("gender", m.getGender());
            item.put("birthDate", m.getBirthDate() == null ? "" : m.getBirthDate().toString());
            item.put("deceased", m.getDeceased() != null && m.getDeceased());
            item.put("deathDate", m.getDeathDate() == null ? "" : m.getDeathDate().toString());
            item.put("alive", !(m.getDeceased() != null && m.getDeceased()));
            item.put("writable", isWritable(f, uid));
            out.add(item);
        }
        return out;
    }

    /** 家族内搜索（树内高亮定位用） */
    public List<Map<String, Object>> searchInFamily(Long uid, Long familyId, String keyword) {
        Family f = requireFamily(familyId);
        assertRead(f, uid);
        List<FamilyMember> list = memberMapper.searchInFamily(familyId, keyword, 100);
        List<Map<String, Object>> out = new ArrayList<>();
        for (FamilyMember m : list) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("memberId", m.getId());
            item.put("name", m.getName());
            out.add(item);
        }
        return out;
    }

    // ==================================================================
    // 十、递归查询（MySQL 8 WITH RECURSIVE，5.7 自动降级为内存 BFS）
    // ==================================================================

    /**
     * 取某成员的全部后代 id（不含自己）。
     * <p>
     * 优先走 MySQL 8 的 WITH RECURSIVE；本项目运行环境为 MySQL 5.7，
     * 捕获语法异常后自动降级为内存 BFS，功能与结果完全一致。
     */
    public Set<Long> descendantIds(Long memberId) {
        try {
            List<Long> ids = relationMapper.findDescendantIds(memberId);
            if (ids != null) {
                Set<Long> set = new LinkedHashSet<>(ids);
                set.remove(memberId);
                return set;
            }
        } catch (Exception e) {
            log.debug("MySQL 不支持 WITH RECURSIVE，降级为内存递归：{}", e.getMessage());
        }
        FamilyMember m = memberMapper.selectById(memberId);
        if (m == null) {
            return Collections.emptySet();
        }
        return descendants(memberId, relationMapper.listByFamily(m.getFamilyId()));
    }

    /** 取某成员的全部祖先 id（不含自己） */
    public Set<Long> ancestorIds(Long memberId) {
        try {
            List<Long> ids = relationMapper.findAncestorIds(memberId);
            if (ids != null) {
                Set<Long> set = new LinkedHashSet<>(ids);
                set.remove(memberId);
                return set;
            }
        } catch (Exception ignored) {
            // 降级
        }
        FamilyMember m = memberMapper.selectById(memberId);
        if (m == null) {
            return Collections.emptySet();
        }
        return ancestors(memberId, relationMapper.listByFamily(m.getFamilyId()));
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private Family requireFamily(Long familyId) {
        if (familyId == null) {
            throw new BizException("家族 id 不能为空");
        }
        Family f = familyMapper.selectById(familyId);
        if (f == null) {
            throw new BizException("家族不存在或已删除");
        }
        return f;
    }

    /** 读权限：可写者 / 公开家族 / （分享入口另行放行） */
    private void assertRead(Family f, Long uid) {
        if (isWritable(f, uid)) {
            return;
        }
        Integer v = f.getVisibility();
        if (v != null && v == 2) {
            return; // 公开
        }
        throw new BizException("无权访问该家族");
    }

    /** 写权限：创建人 或 已绑定账号的家族成员本人 */
    private void assertWrite(Family f, Long uid) {
        if (!isWritable(f, uid)) {
            throw new BizException("无权修改该家族，仅创建人与家族成员本人可编辑");
        }
    }

    private boolean isWritable(Family f, Long uid) {
        if (uid == null) {
            return false;
        }
        if (Objects.equals(f.getUserId(), uid)) {
            return true;
        }
        return memberMapper.countMemberUser(f.getId(), uid) > 0;
    }

    /** 现场新建一位亲属（用于"添加配偶/子女/父母"时对方尚未入册的场景） */
    private FamilyMember createRelative(Long familyId, RelationCreateReq.NewRelative nr) {
        FamilyMember m = new FamilyMember();
        m.setFamilyId(familyId);
        m.setName(nr.getName().trim());
        m.setGender(nr.getGender() == null ? 0 : nr.getGender());
        LocalDate birth = parseDate(nr.getBirthDate(), "出生日期");
        LocalDate death = parseDate(nr.getDeathDate(), "逝世日期");
        boolean deceased = nr.getDeceased() != null ? nr.getDeceased() : (death != null);
        if (death != null) {
            deceased = true;
        } else if (!deceased) {
            death = null;
        }
        m.setBirthDate(birth);
        m.setDeceased(deceased);
        m.setDeathDate(death);
        m.setAvatarUrl(nr.getAvatarUrl() == null ? "" : nr.getAvatarUrl());
        memberMapper.insert(m);
        return m;
    }

    /** 添加子女：自动补上另一位家长（配偶），形成 FATHER + MOTHER 两条边 */
    private void addChild(Long familyId, FamilyMember parent, FamilyMember child, boolean adopted,
                          String desc, List<FamilyRelation> all) {
        // 1) 本人 → 子女（按本人性别决定 FATHER / MOTHER）
        String parentType = parent.getGender() != null && parent.getGender() == 2 ? "MOTHER" : "FATHER";
        insertOne(familyId, parent.getId(), child.getId(), parentType, desc);
        // 2) 反向边：子女 → 本人（性别已知时才写，避免误判）
        String back = child.getGender() == null ? null
                : (child.getGender() == 2 ? (adopted ? "ADOPTED_DAUGHTER" : "DAUGHTER")
                                          : (adopted ? "ADOPTED_SON" : "SON"));
        if (back != null) {
            insertOne(familyId, child.getId(), parent.getId(), back, desc);
        }
        // 3) 配偶自动成为另一位家长：形成 FATHER + MOTHER 两条
        Long spouseId = findSpouse(parent.getId(), all);
        if (spouseId != null) {
            FamilyMember spouse = memberMapper.selectById(spouseId);
            if (spouse != null && !Objects.equals(spouseId, child.getId())) {
                String spouseType = spouse.getGender() != null && spouse.getGender() == 2 ? "MOTHER" : "FATHER";
                if (!spouseType.equals(parentType)) {
                    insertOne(familyId, spouseId, child.getId(), spouseType, desc);
                    String spouseBack = child.getGender() == null ? null
                            : (child.getGender() == 2 ? (adopted ? "ADOPTED_DAUGHTER" : "DAUGHTER")
                                                      : (adopted ? "ADOPTED_SON" : "SON"));
                    if (spouseBack != null) {
                        insertOne(familyId, child.getId(), spouseId, spouseBack, desc);
                    }
                }
            }
        }
    }

    /**
     * 添加子女（显式指定父亲 / 母亲）。
     * 与 {@link #addChild} 的区别：不再按「本人 + 唯一配偶」推导另一位家长，
     * 而是对前端选中的两位家长各建一条 FATHER / MOTHER 边，
     * 因此即使两位家长之间没有夫妻关系，也能正确指向这两个人。
     */
    private void addChildWithParents(Long familyId, Long fatherId, Long motherId, FamilyMember child,
                                     boolean adopted, String desc, List<FamilyRelation> all) {
        if (fatherId == null && motherId == null) {
            throw new BizException("请至少指定一位家长（父亲或母亲）");
        }
        if (fatherId != null && Objects.equals(fatherId, child.getId())) {
            throw new BizException("家长不能是孩子本人");
        }
        if (motherId != null && Objects.equals(motherId, child.getId())) {
            throw new BizException("家长不能是孩子本人");
        }
        if (fatherId != null && Objects.equals(fatherId, motherId)) {
            throw new BizException("父亲与母亲不能是同一人");
        }
        // 防环：被登记为家长的人不能是孩子的晚辈（后代）
        Set<Long> childDescendants = descendants(child.getId(), all);
        if (fatherId != null && childDescendants.contains(fatherId)) {
            throw new BizException("不能把晚辈（后代）登记为父亲，会造成辈分循环");
        }
        if (motherId != null && childDescendants.contains(motherId)) {
            throw new BizException("不能把晚辈（后代）登记为母亲，会造成辈分循环");
        }
        linkParent(familyId, fatherId, child, "FATHER", adopted, desc);
        linkParent(familyId, motherId, child, "MOTHER", adopted, desc);
    }

    /** 建立「家长 → 子女」单向关系，并补上子女视角的反向边（儿子 / 女儿） */
    private void linkParent(Long familyId, Long parentId, FamilyMember child, String parentType,
                            boolean adopted, String desc) {
        if (parentId == null) {
            return;
        }
        FamilyMember p = memberMapper.selectById(parentId);
        if (p == null || !Objects.equals(p.getFamilyId(), familyId)) {
            throw new BizException("所选家长不存在或不在本家族");
        }
        insertOne(familyId, parentId, child.getId(), parentType, desc);
        String back = child.getGender() == null ? null
                : (child.getGender() == 2 ? (adopted ? "ADOPTED_DAUGHTER" : "DAUGHTER")
                                          : (adopted ? "ADOPTED_SON" : "SON"));
        if (back != null) {
            insertOne(familyId, child.getId(), parentId, back, desc);
        }
    }

    /** 添加父母 */
    private void addParent(Long familyId, FamilyMember parent, FamilyMember child, boolean step, String desc) {
        String type;
        if (step) {
            type = parent.getGender() != null && parent.getGender() == 2 ? "STEP_MOTHER" : "STEP_FATHER";
        } else {
            type = parent.getGender() != null && parent.getGender() == 2 ? "MOTHER" : "FATHER";
        }
        insertOne(familyId, parent.getId(), child.getId(), type, desc);
        String back = child.getGender() == null ? null
                : (child.getGender() == 2 ? "DAUGHTER" : "SON");
        if (back != null) {
            insertOne(familyId, child.getId(), parent.getId(), back, desc);
        }
    }

    /** 插入一条关系（已存在则忽略，靠唯一索引 + 业务判断双重保障） */
    private void insertOne(Long familyId, Long a, Long b, String type, String desc) {
        if (relationMapper.countRelation(a, b, type) > 0) {
            return;
        }
        FamilyRelation r = new FamilyRelation();
        r.setFamilyId(familyId);
        r.setMemberAId(a);
        r.setMemberBId(b);
        r.setRelationType(type);
        r.setRelationDesc(desc == null ? "" : desc);
        try {
            relationMapper.insert(r);
        } catch (Exception e) {
            log.debug("关系已存在，忽略：{}-{} {}", a, b, type);
        }
    }

    private void insertBoth(Long familyId, Long a, Long b, String type, String desc) {
        insertOne(familyId, a, b, type, desc);
        insertOne(familyId, b, a, type, desc);
    }

    /** 找配偶：返回第一位仍在婚姻状态（SPOUSE）的配偶 id */
    private Long findSpouse(Long memberId, List<FamilyRelation> all) {
        for (FamilyRelation r : all) {
            if (!"SPOUSE".equals(r.getRelationType())) {
                continue;
            }
            if (Objects.equals(r.getMemberAId(), memberId)) {
                return r.getMemberBId();
            }
            if (Objects.equals(r.getMemberBId(), memberId)) {
                return r.getMemberAId();
            }
        }
        return null;
    }

    /** 直系血亲校验：不能与父母 / 子女登记为配偶 */
    private void checkNotBlood(Long a, Long b, List<FamilyRelation> all, String msg) {
        for (FamilyRelation r : all) {
            if (!PARENT_TYPES.contains(r.getRelationType())) {
                continue;
            }
            boolean ab = Objects.equals(r.getMemberAId(), a) && Objects.equals(r.getMemberBId(), b);
            boolean ba = Objects.equals(r.getMemberAId(), b) && Objects.equals(r.getMemberBId(), a);
            if (ab || ba) {
                throw new BizException(msg);
            }
        }
    }

    /** 内存 BFS：后代（不含自己） */
    private Set<Long> descendants(Long memberId, List<FamilyRelation> all) {
        Map<Long, Set<Long>> children = new HashMap<>();
        for (FamilyRelation r : all) {
            if (PARENT_TYPES.contains(r.getRelationType())) {
                children.computeIfAbsent(r.getMemberAId(), k -> new LinkedHashSet<>()).add(r.getMemberBId());
            }
        }
        Set<Long> seen = new LinkedHashSet<>();
        Deque<Long> q = new ArrayDeque<>();
        for (Long c : children.getOrDefault(memberId, Collections.emptySet())) {
            if (seen.add(c)) {
                q.add(c);
            }
        }
        while (!q.isEmpty()) {
            Long cur = q.poll();
            for (Long c : children.getOrDefault(cur, Collections.emptySet())) {
                if (seen.add(c)) {
                    q.add(c);
                }
            }
        }
        return seen;
    }

    /** 内存 BFS：祖先（不含自己） */
    private Set<Long> ancestors(Long memberId, List<FamilyRelation> all) {
        Map<Long, Set<Long>> parents = new HashMap<>();
        for (FamilyRelation r : all) {
            if (PARENT_TYPES.contains(r.getRelationType())) {
                parents.computeIfAbsent(r.getMemberBId(), k -> new LinkedHashSet<>()).add(r.getMemberAId());
            }
        }
        Set<Long> seen = new LinkedHashSet<>();
        Deque<Long> q = new ArrayDeque<>();
        for (Long p : parents.getOrDefault(memberId, Collections.emptySet())) {
            if (seen.add(p)) {
                q.add(p);
            }
        }
        while (!q.isEmpty()) {
            Long cur = q.poll();
            for (Long p : parents.getOrDefault(cur, Collections.emptySet())) {
                if (seen.add(p)) {
                    q.add(p);
                }
            }
        }
        return seen;
    }

    /** 批量取姓名（用于影响范围提示、大事记关联人展示） */
    private List<String> namesOf(Collection<Long> ids, Long familyId) {
        List<String> names = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return names;
        }
        List<Long> idList = new ArrayList<>(ids);
        List<FamilyMember> ms = memberMapper.listByIds(idList);
        int limit = 8; // 只展示前 8 个，避免提示过长
        for (int i = 0; i < ms.size() && i < limit; i++) {
            names.add(ms.get(i).getName());
        }
        if (ms.size() > limit) {
            names.add("等 " + ms.size() + " 人");
        }
        return names;
    }

    private static String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    private static List<Long> splitIds(String s) {
        List<Long> out = new ArrayList<>();
        if (s == null || s.isBlank()) {
            return out;
        }
        for (String part : s.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.valueOf(t));
            } catch (NumberFormatException ignored) {
                // 忽略脏数据
            }
        }
        return out;
    }

    private static LocalDate parseDate(String s, String label) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new BizException(label + "格式不正确，应为 yyyy-MM-dd");
        }
    }

    /** 称谓（正向：a 是 b 的 type） */
    private static String titleOf(String type) {
        return switch (type) {
            case "SPOUSE" -> "配偶";
            case "EX_SPOUSE" -> "前配偶";
            case "FATHER" -> "父亲";
            case "MOTHER" -> "母亲";
            case "SON" -> "儿子";
            case "DAUGHTER" -> "女儿";
            case "STEP_FATHER" -> "继父";
            case "STEP_MOTHER" -> "继母";
            case "ADOPTED_SON" -> "养子";
            case "ADOPTED_DAUGHTER" -> "养女";
            default -> null;
        };
    }

    /**
     * 反称谓：自己是关系主体 a（"自己是对方的 type"）时，对方是自己的什么人。
     * 需要结合自己的性别，避免把"女儿的父亲"说成"母亲"。
     */
    private static String reverseTitleOf(String type, Integer selfGender) {
        Integer g = selfGender == null ? 0 : selfGender;
        return switch (type) {
            case "SPOUSE" -> "配偶";
            case "EX_SPOUSE" -> "前配偶";
            case "FATHER", "MOTHER" -> g == 1 ? "儿子" : (g == 2 ? "女儿" : "子女");
            case "STEP_FATHER", "STEP_MOTHER" -> g == 1 ? "继子" : (g == 2 ? "继女" : "继子女");
            case "SON" -> "父亲";
            case "DAUGHTER" -> "母亲";
            case "ADOPTED_SON" -> "养父";
            case "ADOPTED_DAUGHTER" -> "养母";
            default -> null;
        };
    }
}
