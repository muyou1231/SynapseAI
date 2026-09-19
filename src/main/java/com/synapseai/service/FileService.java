package com.synapseai.service;

import com.synapseai.config.MinIOConfigProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

/**
 * 文件存储：上传到 MinIO（内部 9000 端口），但对外只返回走后端 8080 的代理 URL，
 * 这样 MinIO 端口无需对外暴露，配合内网穿透也能正常访问。
 */
@Service
public class FileService {

    /** 代理前缀：返回给前端的链接都以 /api/files/ 开头，浏览器会基于当前 8080 域名请求 */
    public static final String PROXY_PREFIX = "/api/files/";

    @Autowired
    private MinioClient minioClient;
    @Autowired
    private MinIOConfigProperties props;

    /** 上传文件，返回可公网访问的代理 URL（相对路径） */
    public String upload(MultipartFile file, String prefix) throws Exception {
        String bucket = props.getBucket();
        ensureBucket(bucket);
        String ext = extOf(file.getOriginalFilename());
        String objectName = ((prefix == null || prefix.isEmpty()) ? "photo" : prefix)
                + "/" + UUID.randomUUID().toString().replace("-", "") + ext;
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(objectName)
                .contentType(file.getContentType())
                .stream(file.getInputStream(), file.getSize(), -1)
                .build());
        return PROXY_PREFIX + objectName;
    }

    /** 取对象输入流（供代理接口转发） */
    public InputStream download(String objectName) throws Exception {
        return minioClient.getObject(GetObjectArgs.builder()
                .bucket(props.getBucket())
                .object(objectName)
                .build());
    }

    /** 查询对象 content-type（代理转发时设置给响应） */
    public String contentType(String objectName) {
        try {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(objectName)
                    .build());
            String ct = stat.contentType();
            return (ct == null || ct.isEmpty()) ? "application/octet-stream" : ct;
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    private void ensureBucket(String bucket) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private String extOf(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return (i >= 0 && i < name.length() - 1) ? name.substring(i).toLowerCase() : "";
    }
}
