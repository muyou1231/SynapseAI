package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.dto.EventSaveReq;
import com.synapseai.dto.FamilySaveReq;
import com.synapseai.dto.FamilyTreeVO;
import com.synapseai.dto.MemberSaveReq;
import com.synapseai.dto.RelationCreateReq;
import com.synapseai.service.FamilyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 家族族谱 / 家庭树接口。
 * <p>
 * 统一约定：
 * <ul>
 *   <li>全部返回 {@link Result} 包装（{@code code=0} 为成功），异常由 {@code GlobalExceptionHandler} 统一兜底。</li>
 *   <li>请求体参数用 {@code @Valid} 校验，校验失败自动转成 {@code Result.error(提示语)}。</li>
 *   <li>写操作带乐观锁 version，冲突返回 {@code code=409}。</li>
 *   <li>{@code /api/family/share/**} 为只读分享入口，已在 WebConfig 中放行（无需登录）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/family")
public class FamilyController {

    @Autowired
    private FamilyService familyService;

    // ==================================================================
    // 一、家族
    // ==================================================================

    /** 创建家族 */
    @PostMapping("/create")
    public Result<?> create(@RequestAttribute("uid") Long uid, @Valid @RequestBody FamilySaveReq req) {
        return Result.ok(familyService.createFamily(uid, req));
    }

    /** 我的家族列表 */
    @GetMapping("/list")
    public Result<?> list(@RequestAttribute("uid") Long uid) {
        return Result.ok(familyService.listMine(uid));
    }

    /** 家族详情 */
    @GetMapping("/{id}")
    public Result<?> detail(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        return Result.ok(familyService.familyDetail(uid, id));
    }

    /** 编辑家族（乐观锁） */
    @PostMapping("/update")
    public Result<?> update(@RequestAttribute("uid") Long uid, @Valid @RequestBody FamilySaveReq req) {
        familyService.updateFamily(uid, req);
        return Result.ok();
    }

    /** 删除家族（级联清理） */
    @DeleteMapping("/{id}")
    public Result<?> delete(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        familyService.deleteFamily(uid, id);
        return Result.ok();
    }

    // ==================================================================
    // 二、成员
    // ==================================================================

    /** 新增 / 编辑成员（乐观锁）。id 为空=新增 */
    @PostMapping("/member/save")
    public Result<?> saveMember(@RequestAttribute("uid") Long uid, @Valid @RequestBody MemberSaveReq req) {
        return Result.ok(familyService.saveMember(uid, req));
    }

    /** 成员详情（含相册与亲属） */
    @GetMapping("/member/{id}")
    public Result<?> memberDetail(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        return Result.ok(familyService.memberDetail(uid, id));
    }

    /**
     * 删除前预检：返回影响范围（后代 / 关系 / 照片数量），
     * 前端据此弹二次确认。删除只解除关系，不会连坐删除后代成员。
     */
    @GetMapping("/member/{id}/delete-preview")
    public Result<?> deletePreview(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        return Result.ok(familyService.deletePreview(uid, id));
    }

    /** 删除成员（级联清理关系与相册） */
    @DeleteMapping("/member/{id}")
    public Result<?> deleteMember(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        familyService.deleteMember(uid, id);
        return Result.ok();
    }

    /** 上传成员头像 */
    @PostMapping("/member/{id}/avatar")
    public Result<?> uploadAvatar(@RequestAttribute("uid") Long uid,
                                  @PathVariable("id") Long id,
                                  @RequestParam("file") MultipartFile file) {
        String url = familyService.updateMemberAvatar(uid, id, file);
        return Result.ok(Collections.singletonMap("url", url));
    }

    // ==================================================================
    // 三、成员相册
    // ==================================================================

    /** 成员相册 */
    @GetMapping("/member/{id}/photos")
    public Result<?> photos(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        return Result.ok(familyService.listPhotos(uid, id));
    }

    /** 批量上传相册图片（支持一次选多张） */
    @PostMapping("/member/{id}/photos")
    public Result<?> uploadPhotos(@RequestAttribute("uid") Long uid,
                                  @PathVariable("id") Long id,
                                  @RequestParam("files") List<MultipartFile> files,
                                  @RequestParam(value = "captions", required = false) String[] captions) {
        int n = familyService.addPhotos(uid, id, files, captions);
        return Result.ok(Collections.singletonMap("count", n));
    }

    /** 删除相册图片 */
    @DeleteMapping("/photo/{photoId}")
    public Result<?> deletePhoto(@RequestAttribute("uid") Long uid, @PathVariable("photoId") Long photoId) {
        familyService.deletePhoto(uid, photoId);
        return Result.ok();
    }

    // ==================================================================
    // 四、亲属关系
    // ==================================================================

    /**
     * 建立亲属关系。
     * relationType：SPOUSE / EX_SPOUSE / CHILD / ADOPTED_CHILD / PARENT / STEP_PARENT
     */
    @PostMapping("/relation/add")
    public Result<?> addRelation(@RequestAttribute("uid") Long uid, @Valid @RequestBody RelationCreateReq req) {
        Long otherId = familyService.addRelation(uid, req);
        return Result.ok(Collections.singletonMap("memberId", otherId));
    }

    /** 解除两人的全部关系 */
    @DeleteMapping("/relation")
    public Result<?> removeRelation(@RequestAttribute("uid") Long uid,
                                    @RequestParam("aId") Long aId,
                                    @RequestParam("bId") Long bId) {
        familyService.removeRelation(uid, aId, bId);
        return Result.ok();
    }

    /** 某成员的关联亲属 */
    @GetMapping("/member/{id}/relations")
    public Result<?> relations(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        return Result.ok(familyService.listRelations(id));
    }

    // ==================================================================
    // 五、家族树
    // ==================================================================

    /**
     * 家族树（G6 渲染数据）。
     *
     * @param direction TB=纵向（上父下子）/ LR=横向（左父右子）
     * @param maxDepth  渲染层数，超过则折叠后代；传 0 表示全部展开
     * @param expanded  已手动展开的节点 id，逗号分隔
     */
    @GetMapping("/{id}/tree")
    public Result<?> tree(@RequestAttribute("uid") Long uid,
                          @PathVariable("id") Long id,
                          @RequestParam(value = "direction", defaultValue = "TB") String direction,
                          @RequestParam(value = "maxDepth", required = false) Integer maxDepth,
                          @RequestParam(value = "expanded", required = false) String expanded) {
        FamilyTreeVO vo = familyService.tree(uid, id, direction, maxDepth, parseIds(expanded));
        return Result.ok(vo);
    }

    // ==================================================================
    // 六、大事记
    // ==================================================================

    /** 新增 / 编辑大事记 */
    @PostMapping("/event/save")
    public Result<?> saveEvent(@RequestAttribute("uid") Long uid, @Valid @RequestBody EventSaveReq req) {
        return Result.ok(familyService.saveEvent(uid, req));
    }

    /** 大事记列表（按事件日期倒序） */
    @GetMapping("/{id}/events")
    public Result<?> events(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        return Result.ok(familyService.listEvents(uid, id));
    }

    /** 删除大事记 */
    @DeleteMapping("/event/{eventId}")
    public Result<?> deleteEvent(@RequestAttribute("uid") Long uid, @PathVariable("eventId") Long eventId) {
        familyService.deleteEvent(uid, eventId);
        return Result.ok();
    }

    // ==================================================================
    // 七、分享链接
    // ==================================================================

    /** 生成只读分享 token */
    @PostMapping("/{id}/share")
    public Result<?> share(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        String token = familyService.generateShareToken(uid, id);
        return Result.ok(Collections.singletonMap("token", token));
    }

    /** 撤销分享链接 */
    @DeleteMapping("/{id}/share")
    public Result<?> revokeShare(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        familyService.revokeShareToken(uid, id);
        return Result.ok();
    }

    /** 分享页基础信息（无需登录，WebConfig 已放行） */
    @GetMapping("/share/{token}")
    public Result<?> shareInfo(@PathVariable("token") String token) {
        return Result.ok(familyService.shareInfo(token));
    }

    /** 分享页家族树（无需登录，只读） */
    @GetMapping("/share/{token}/tree")
    public Result<?> shareTree(@PathVariable("token") String token,
                               @RequestParam(value = "direction", defaultValue = "TB") String direction,
                               @RequestParam(value = "maxDepth", required = false) Integer maxDepth,
                               @RequestParam(value = "expanded", required = false) String expanded) {
        return Result.ok(familyService.treeByToken(token, direction, maxDepth, parseIds(expanded)));
    }

    // ==================================================================
    // 八、导出
    // ==================================================================

    /**
     * 导出族谱长图（PNG）。
     * 服务端按与页面完全一致的坐标绘制，头像会从本站拉取（失败时回退为姓氏首字占位）。
     */
    @GetMapping("/{id}/export/png")
    public ResponseEntity<ByteArrayResource> exportPng(@RequestAttribute("uid") Long uid,
                                                       @PathVariable("id") Long id,
                                                       @RequestParam(value = "direction", defaultValue = "TB") String direction,
                                                       HttpServletRequest request) {
        byte[] data = familyService.exportPng(uid, id, direction, baseUrl(request));
        return fileResponse(data, familyFileName(id, "png"), MediaType.IMAGE_PNG);
    }

    /** 导出族谱 PDF（按 A4 分页） */
    @GetMapping("/{id}/export/pdf")
    public ResponseEntity<ByteArrayResource> exportPdf(@RequestAttribute("uid") Long uid,
                                                       @PathVariable("id") Long id,
                                                       @RequestParam(value = "direction", defaultValue = "TB") String direction,
                                                       HttpServletRequest request) {
        byte[] data = familyService.exportPdf(uid, id, direction, baseUrl(request));
        return fileResponse(data, familyFileName(id, "pdf"), MediaType.APPLICATION_PDF);
    }

    // ==================================================================
    // 九、搜索
    // ==================================================================

    /** 全局搜索成员（跨我有权限的家族） */
    @GetMapping("/search")
    public Result<?> search(@RequestAttribute("uid") Long uid,
                            @RequestParam("keyword") @NotBlank(message = "搜索关键词不能为空") String keyword) {
        return Result.ok(familyService.searchMembers(uid, keyword));
    }

    /** 家族内搜索（树内高亮定位） */
    @GetMapping("/{id}/search")
    public Result<?> searchInFamily(@RequestAttribute("uid") Long uid,
                                    @PathVariable("id") Long id,
                                    @RequestParam("keyword") String keyword) {
        return Result.ok(familyService.searchInFamily(uid, id, keyword));
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** "12,34" → Set{12,34} */
    private static Set<Long> parseIds(String csv) {
        Set<Long> set = new HashSet<>();
        if (csv == null || csv.isBlank()) {
            return set;
        }
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(s -> {
                    try {
                        set.add(Long.valueOf(s));
                    } catch (NumberFormatException ignored) {
                        // 忽略非法 id
                    }
                });
        return set;
    }

    /** 站点根地址，供导出时拼接头像绝对地址 */
    private static String baseUrl(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : (":" + port));
    }

    private static String familyFileName(Long familyId, String ext) {
        return "family-tree-" + familyId + "." + ext;
    }

    private static ResponseEntity<ByteArrayResource> fileResponse(byte[] data, String filename, MediaType type) {
        ByteArrayResource resource = new ByteArrayResource(data);
        String encoded;
        try {
            encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            encoded = filename;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encoded)
                .body(resource);
    }
}
