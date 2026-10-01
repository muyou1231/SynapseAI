package com.synapseai.util;

import com.synapseai.dto.FamilyTreeVO;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 族谱导出：把 {@link FamilyTreeVO}（后端已算好坐标）渲染成「长图 PNG」与「PDF」。
 * <p>
 * 设计取舍：
 * <ul>
 *   <li>不引第三方 PDF 库：族谱导出本质是"把一张长图印到 A4 上"，
 *       用 JDK 自带 {@code ImageIO}（JPEG 编码）+ 手写极简 PDF 容器即可完成，
 *       依赖越少越可控，也避免 PDFBox 之类库在 JDK 17 上的兼容风险。</li>
 *   <li>与页面共用同一份坐标：前端 G6 用什么坐标，导出就画什么坐标，做到所见即所得。</li>
 *   <li>头像按需远程拉取，失败（超时 / 404）时回退为"姓氏首字"圆形占位，保证导出永不失败。</li>
 * </ul>
 */
public final class FamilyTreeExporter {

    // ---------- 国风配色 ----------
    private static final Color BG = new Color(0xF7, 0xF3, 0xEA);          // 米白底
    private static final Color INK = new Color(0x2F, 0x2A, 0x24);          // 墨色文字
    private static final Color INK_LIGHT = new Color(0x7A, 0x72, 0x68);    // 次要文字
    private static final Color MALE_BG = new Color(0xEA, 0xF1, 0xEC);      // 男：浅墨绿
    private static final Color FEMALE_BG = new Color(0xF8, 0xEE, 0xE4);    // 女：浅赭石
    private static final Color UNKNOWN_BG = new Color(0xF2, 0xF0, 0xEA);   // 未知：浅灰
    private static final Color ALIVE_BORDER = new Color(0x3E, 0x5C, 0x4B); // 在世：墨绿
    private static final Color DEAD_BORDER = new Color(0xA0, 0x92, 0x82);  // 已故：灰褐
    private static final Color LINE_PARENT = new Color(0x6B, 0x7F, 0x6E);  // 父子连线
    private static final Color LINE_SPOUSE = new Color(0xA9, 0x71, 0x4B);  // 夫妻连线（赭石）
    private static final Color LINE_EX = new Color(0xB8, 0xB0, 0xA4);      // 离异虚线

    /** 超清倍数：2 倍绘制再按需求缩放，保证导出清晰 */
    private static final int SCALE = 2;

    private FamilyTreeExporter() {
    }

    // ==================================================================
    // 长图
    // ==================================================================

    /**
     * 渲染族谱长图。
     *
     * @param vo      家族树（含坐标）
     * @param baseUrl 站点根地址，用于把头像相对路径（/api/files/xxx）拼成绝对地址；可为空
     * @return PNG 字节
     */
    public static byte[] renderPng(FamilyTreeVO vo, String baseUrl) {
        int w = Math.max(vo.getWidth(), 800);
        int h = Math.max(vo.getHeight(), 600);
        BufferedImage img = new BufferedImage(w * SCALE, h * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.scale(SCALE, SCALE);
            g.setColor(BG);
            g.fillRect(0, 0, w, h);

            Map<String, FamilyTreeVO.TreeNode> nodeMap = new HashMap<>();
            for (FamilyTreeVO.TreeNode n : vo.getNodes()) {
                nodeMap.put(n.getId(), n);
            }

            // 1) 先画连线（在节点下层）
            drawEdges(g, vo, nodeMap);
            // 2) 再画节点卡片
            Map<String, BufferedImage> avatarCache = new HashMap<>();
            for (FamilyTreeVO.TreeNode n : vo.getNodes()) {
                // 头像回退链：专属头像 → 相册首图 → 姓氏首字（与前端节点保持一致）
                String av = (n.getAvatar() != null && !n.getAvatar().isEmpty()) ? n.getAvatar() : n.getPhotoUrl();
                drawNode(g, n, loadAvatar(av, baseUrl, avatarCache));
            }
            // 3) 标题
            drawTitle(g, vo, w);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (Exception e) {
            throw new IllegalStateException("生成族谱长图失败：" + e.getMessage());
        }
        return out.toByteArray();
    }

    // ==================================================================
    // PDF
    // ==================================================================

    /** A4 尺寸（pt） */
    private static final float A4_W = 595.28f;
    private static final float A4_H = 841.89f;
    private static final float MARGIN = 28f;

    /**
     * 把长图按 A4 页宽分页，生成 PDF。
     * <p>
     * 实现：长图按页宽等比缩放后纵向切片，每页放一段，切片以 JPEG(DCTDecode) 嵌入 PDF。
     */
    public static byte[] renderPdf(FamilyTreeVO vo, String baseUrl) {
        byte[] pngBytes = renderPng(vo, baseUrl);
        BufferedImage src;
        try {
            src = ImageIO.read(new java.io.ByteArrayInputStream(pngBytes));
        } catch (Exception e) {
            throw new IllegalStateException("生成 PDF 失败：长图解析异常");
        }
        double scale = (A4_W - MARGIN * 2) / (double) src.getWidth();
        int sliceH = (int) Math.floor((A4_H - MARGIN * 2) / scale); // 每页对应的源图高度
        if (sliceH < 1) {
            sliceH = src.getHeight();
        }
        int pages = (int) Math.ceil(src.getHeight() / (double) sliceH);

        List<byte[]> pageImages = new ArrayList<>();
        List<float[]> pageSizes = new ArrayList<>();
        for (int i = 0; i < pages; i++) {
            int y = i * sliceH;
            int hh = Math.min(sliceH, src.getHeight() - y);
            if (hh <= 0) {
                break;
            }
            BufferedImage slice = src.getSubimage(0, y, src.getWidth(), hh);
            // 缩放到 PDF 分辨率，避免超大图片体积
            int targetW = (int) (A4_W - MARGIN * 2);
            int targetH = (int) Math.round(hh * scale);
            BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D sg = scaled.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            sg.drawImage(slice, 0, 0, targetW, targetH, null);
            sg.dispose();
            pageImages.add(toJpeg(scaled));
            pageSizes.add(new float[]{targetW, targetH});
        }
        return buildPdf(pageImages, pageSizes);
    }

    // ==================================================================
    // 绘制细节
    // ==================================================================

    private static void drawTitle(Graphics2D g, FamilyTreeVO vo, int width) {
        g.setFont(font(Font.BOLD, 26));
        g.setColor(INK);
        String title = vo.getFamilyName() == null ? "家族族谱" : vo.getFamilyName() + " · 族谱";
        FontMetrics fm = g.getFontMetrics();
        int x = Math.max(24, (width - fm.stringWidth(title)) / 2);
        g.drawString(title, x, 40);
        g.setFont(font(Font.PLAIN, 12));
        g.setColor(INK_LIGHT);
        String sub = "共 " + vo.getTotalMemberCount() + " 位成员　导出时间：" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        FontMetrics fm2 = g.getFontMetrics();
        g.drawString(sub, Math.max(24, (width - fm2.stringWidth(sub)) / 2), 60);
    }

    private static void drawEdges(Graphics2D g, FamilyTreeVO vo, Map<String, FamilyTreeVO.TreeNode> nodeMap) {
        int nw = FamilyTreeLayout.NODE_W;
        int nh = FamilyTreeLayout.NODE_H;
        boolean lr = "LR".equalsIgnoreCase(vo.getDirection());
        g.setStroke(new BasicStroke(1.6f));
        for (FamilyTreeVO.TreeEdge e : vo.getEdges()) {
            FamilyTreeVO.TreeNode a = nodeMap.get(e.getSource());
            FamilyTreeVO.TreeNode b = nodeMap.get(e.getTarget());
            if (a == null || b == null) {
                continue;
            }
            String type = e.getType();
            if ("SPOUSE".equals(type) || "EX_SPOUSE".equals(type)) {
                g.setColor("EX_SPOUSE".equals(type) ? LINE_EX : LINE_SPOUSE);
                if ("EX_SPOUSE".equals(type)) {
                    g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                            0f, new float[]{5f, 4f}, 0f));
                } else {
                    g.setStroke(new BasicStroke(1.8f));
                }
                // 夫妻：同层相邻，直接连中心
                g.drawLine((int) a.getX(), (int) a.getY(), (int) b.getX(), (int) b.getY());
            } else {
                // 父子：走正交折线（父 → 中途 → 子）
                g.setColor(LINE_PARENT);
                if ("STEP_PARENT".equals(type)) {
                    g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                            0f, new float[]{5f, 4f}, 0f));
                } else {
                    g.setStroke(new BasicStroke(1.6f));
                }
                if (lr) {
                    int x1 = (int) (a.getX() + nw / 2.0);
                    // 合并出线：同单元多家长的边从单元中点（纵向）出线
                    int y1 = (int) (e.getCoupleCenter() != null ? e.getCoupleCenter() : a.getY());
                    int x2 = (int) (b.getX() - nw / 2.0);
                    int y2 = (int) b.getY();
                    int midX = (x1 + x2) / 2;
                    g.drawLine(x1, y1, midX, y1);
                    g.drawLine(midX, y1, midX, y2);
                    g.drawLine(midX, y2, x2, y2);
                } else {
                    // 合并出线：同单元多家长的边从单元中点（横向）垂下，再分支到各孩子
                    int x1 = (int) (e.getCoupleCenter() != null ? e.getCoupleCenter() : a.getX());
                    int y1 = (int) (a.getY() + nh / 2.0);
                    int x2 = (int) b.getX();
                    int y2 = (int) (b.getY() - nh / 2.0);
                    int midY = (y1 + y2) / 2;
                    g.drawLine(x1, y1, x1, midY);
                    g.drawLine(x1, midY, x2, midY);
                    g.drawLine(x2, midY, x2, y2);
                }
            }
        }
        g.setStroke(new BasicStroke(1.6f));
    }

    private static void drawNode(Graphics2D g, FamilyTreeVO.TreeNode n, BufferedImage avatar) {
        int nw = FamilyTreeLayout.NODE_W;
        int nh = FamilyTreeLayout.NODE_H;
        int x = (int) (n.getX() - nw / 2.0);
        int y = (int) (n.getY() - nh / 2.0);

        Color bg = n.getGender() != null && n.getGender() == 2 ? FEMALE_BG
                : (n.getGender() != null && n.getGender() == 1 ? MALE_BG : UNKNOWN_BG);
        Color border = n.isAlive() ? ALIVE_BORDER : DEAD_BORDER;

        // 卡片底 + 轻微阴影
        g.setColor(new Color(0x00, 0x00, 0x00, 22));
        g.fill(new RoundRectangle2D.Float(x + 2f, y + 3f, nw, nh, 14, 14));
        g.setColor(bg);
        g.fill(new RoundRectangle2D.Float(x, y, nw, nh, 14, 14));
        g.setColor(border);
        g.setStroke(new BasicStroke(n.isAlive() ? 1.8f : 1.4f));
        g.draw(new RoundRectangle2D.Float(x, y, nw, nh, 14, 14));

        // 头像（圆形）
        int av = 40;
        int ax = x + 8;
        int ay = y + (nh - av) / 2;
        Shape clip = new java.awt.geom.Ellipse2D.Float(ax, ay, av, av);
        Shape old = g.getClip();
        // 圆形裁剪内画头像或占位底色
        g.setClip(clip);
        if (avatar != null) {
            g.drawImage(avatar, ax, ay, av, av, null);
        } else {
            g.setColor(avatarBg(n.getGender()));
            g.fillRect(ax, ay, av, av);
        }
        g.setClip(old);
        // 无头像时用姓氏首字占位（不裁剪，避免文字被切）
        if (avatar == null) {
            g.setColor(INK);
            g.setFont(font(Font.BOLD, 18));
            String initial = firstChar(n.getName());
            FontMetrics fm = g.getFontMetrics();
            g.drawString(initial, ax + (av - fm.stringWidth(initial)) / 2, ay + av / 2 + fm.getAscent() / 2 - 2);
        }
        g.setColor(border);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(clip);

        // 姓名
        int tx = ax + av + 10;
        g.setColor(INK);
        g.setFont(font(Font.BOLD, 14));
        String name = clipText(g, n.getName() == null ? "未命名" : n.getName(), nw - av - 26);
        g.drawString(name, tx, y + 30);

        // 生卒年份
        g.setColor(INK_LIGHT);
        g.setFont(font(Font.PLAIN, 12));
        String years = yearText(n);
        g.drawString(clipText(g, years, nw - av - 26), tx, y + 49);

        // 折叠标记：+N
        if (n.isCollapsed() && n.getFoldedDescendants() > 0) {
            String badge = "+" + n.getFoldedDescendants();
            g.setColor(LINE_SPOUSE);
            g.setFont(font(Font.BOLD, 11));
            FontMetrics fm = g.getFontMetrics();
            int bw = fm.stringWidth(badge) + 12;
            g.fill(new RoundRectangle2D.Float(x + nw - bw - 4f, y + nh - 8f, bw, 16, 8, 8));
            g.setColor(Color.WHITE);
            g.drawString(badge, x + nw - bw + 2f, y + nh + 4f);
        }
    }

    private static Color avatarBg(Integer gender) {
        if (gender == null) {
            return new Color(0xE3, 0xDF, 0xD6);
        }
        if (gender == 2) {
            return new Color(0xE8, 0xC9, 0xB0);
        }
        if (gender == 1) {
            return new Color(0xC3, 0xD3, 0xC6);
        }
        return new Color(0xE3, 0xDF, 0xD6);
    }

    private static String yearText(FamilyTreeVO.TreeNode n) {
        String b = n.getBirthYear() == null || n.getBirthYear().isEmpty() ? "未知" : n.getBirthYear();
        String d = n.getDeathYear() == null || n.getDeathYear().isEmpty() ? "" : n.getDeathYear();
        // 在世：只标出生年，避免截断（出生年缺失时显示「未知」）
        if (!n.isDeceased()) {
            return b + " –";
        }
        // 已逝世：逝世日期未知时标注「未知」
        return b + " – " + (d.isEmpty() ? "未知" : d);
    }

    private static String firstChar(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        return name.substring(0, 1);
    }

    private static String clipText(Graphics2D g, String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (fm.stringWidth(sb.toString() + c + "…") > maxWidth) {
                break;
            }
            sb.append(c);
        }
        return sb + "…";
    }

    /** 中文字体：优先微软雅黑，取不到则回退到逻辑字体（Linux 服务器可用 -- 由 fontconfig 兜底） */
    private static Font font(int style, int size) {
        Font f = new Font("Microsoft YaHei", style, size);
        if (!"Microsoft YaHei".equalsIgnoreCase(f.getFamily())) {
            // 字体缺失时 AWT 会回退到 Dialog，这里再兜一层，尽量保证中文可用
            Font sans = new Font(Font.SANS_SERIF, style, size);
            return sans;
        }
        return f;
    }

    /** 加载头像：本地相对路径会拼上 baseUrl；失败返回 null（回退文字占位） */
    private static BufferedImage loadAvatar(String url, String baseUrl, Map<String, BufferedImage> cache) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        if (cache.containsKey(url)) {
            return cache.get(url);
        }
        BufferedImage img = null;
        try {
            String target = url.startsWith("http") ? url
                    : (baseUrl == null || baseUrl.isEmpty() ? null : baseUrl + (url.startsWith("/") ? url : "/" + url));
            if (target != null) {
                URLConnection conn = new URL(target).openConnection();
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(3000);
                try (InputStream in = conn.getInputStream()) {
                    BufferedImage raw = ImageIO.read(in);
                    if (raw != null) {
                        img = raw;
                    }
                }
            }
        } catch (Exception ignored) {
            img = null; // 头像加载失败不影响导出
        }
        cache.put(url, img);
        return img;
    }

    private static byte[] toJpeg(BufferedImage img) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "jpg", out);
        } catch (Exception e) {
            throw new IllegalStateException("图片编码失败");
        }
        return out.toByteArray();
    }

    // ==================================================================
    // 极简 PDF 容器（仅支持图片页，零第三方依赖）
    // ==================================================================

    /**
     * 组装 PDF 字节流。
     * <p>
     * 对象编号规划：1 = Catalog，2 = Pages，其后每页 3 个对象，
     * 第 i 页（从 0 计）：Page = 3 + i*3，Contents = 4 + i*3，Image = 5 + i*3。
     * 严格按「对象头 → stream → endstream」顺序写入，并在写的过程中记录每个对象的字节偏移用于 xref。
     */
    private static byte[] buildPdf(List<byte[]> images, List<float[]> sizes) {
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        List<Long> offsets = new ArrayList<>();
        int n = images.size();

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) {
            kids.append(3 + i * 3).append(" 0 R ");
        }

        write(pdf, "%PDF-1.4\n");
        offsets.add((long) pdf.size());
        write(pdf, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        offsets.add((long) pdf.size());
        write(pdf, "2 0 obj\n<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + n + " >>\nendobj\n");

        for (int i = 0; i < n; i++) {
            int pageObj = 3 + i * 3;
            int contentObj = 4 + i * 3;
            int imageObj = 5 + i * 3;
            float w = sizes.get(i)[0];
            float h = sizes.get(i)[1];
            float y = Math.max(MARGIN, A4_H - MARGIN - h);

            offsets.add((long) pdf.size());
            write(pdf, pageObj + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
                    + f(A4_W) + " " + f(A4_H) + "] /Resources << /XObject << /Im0 " + imageObj
                    + " 0 R >> >> /Contents " + contentObj + " 0 R >>\nendobj\n");

            String content = "q " + f(w) + " 0 0 " + f(h) + " " + f(MARGIN) + " " + f(y) + " cm /Im0 Do Q\n";
            offsets.add((long) pdf.size());
            write(pdf, contentObj + " 0 obj\n<< /Length " + content.length() + " >>\nstream\n" + content + "endstream\nendobj\n");

            byte[] jpg = images.get(i);
            offsets.add((long) pdf.size());
            write(pdf, imageObj + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + (int) w
                    + " /Height " + (int) h + " /ColorSpace /DeviceRGB /BitsPerComponent 8 "
                    + "/Filter /DCTDecode /Length " + jpg.length + " >>\nstream\n");
            pdf.write(jpg, 0, jpg.length);
            write(pdf, "\nendstream\nendobj\n");
        }

        long xrefPos = pdf.size();
        int total = 2 + n * 3;
        StringBuilder xref = new StringBuilder("xref\n0 " + (total + 1) + "\n0000000000 65535 f \n");
        for (Long off : offsets) {
            String s = String.format("%010d", off);
            xref.append(s).append(" 00000 n \n");
        }
        write(pdf, xref.toString());
        write(pdf, "trailer\n<< /Size " + (total + 1) + " /Root 1 0 R >>\nstartxref\n" + xrefPos + "\n%%EOF\n");
        return pdf.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        out.write(b, 0, b.length);
    }

    private static String f(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
