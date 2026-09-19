package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.service.FileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 文件上传与代理访问。
 * - POST /api/file/upload  需登录（X-Token），上传到 MinIO，返回代理 URL
 * - GET  /api/files/{name} 公开，后端从 MinIO 取流转发（走 8080，不暴露 9000）
 */
@RestController
@RequestMapping("/api")
public class FileController {

    /** 代理路径前缀（与类映射 /api + 方法 /files/** 对应），供 serve 截取对象名 */
    private static final String FILES_PREFIX = "/api/files/";

    @Autowired
    private FileService fileService;

    @PostMapping("/file/upload")
    public Result<?> upload(@RequestParam("file") MultipartFile file,
                            @RequestParam(value = "prefix", defaultValue = "photo") String prefix) {
        try {
            if (file.isEmpty()) return Result.error("文件为空");
            String url = fileService.upload(file, prefix);
            String objectName = url.substring(FileService.PROXY_PREFIX.length());
            Map<String, String> data = new HashMap<>();
            data.put("url", url);
            data.put("objectName", objectName);
            return Result.ok(data);
        } catch (Exception e) {
            return Result.error("上传失败：" + e.getMessage());
        }
    }

    /**
     * 代理转发 MinIO 对象。
     * 注意：Spring Boot 3 默认 PathPatternParser 不支持用 {var:.+} 跨斜杠捕获，
     * 因此用 /** 通配，再从请求 URI 中截出对象名（含多层目录，如 photo/chat/xxx.png）。
     */
    @GetMapping("/files/**")
    public void serve(HttpServletRequest request, HttpServletResponse response) {
        String uri = request.getRequestURI();
        String prefix = FileController.FILES_PREFIX;
        if (!uri.startsWith(prefix) || uri.length() == prefix.length()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String objectName = uri.substring(prefix.length());
        InputStream in = null;
        try {
            in = fileService.download(objectName);
            response.setContentType(fileService.contentType(objectName));
            response.setHeader("Cache-Control", "public, max-age=31536000");
            in.transferTo(response.getOutputStream());
            response.flushBuffer();
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) { }
            }
        }
    }
}
