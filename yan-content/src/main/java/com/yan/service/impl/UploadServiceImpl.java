package com.yan.service.impl;


import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.auth.EnvironmentVariableCredentialsProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.*;
import com.yan.domain.ResponseResult;
import com.yan.service.UploadService;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

@Service
public class UploadServiceImpl implements UploadService {

    @Override
    public ResponseResult uploadImg(MultipartFile imgFile) {
        try {
            String url = uploadOss(imgFile);
            return new ResponseResult(200, "上传成功", url);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("文件上传失败");
        }
    }

    private String uploadOss(MultipartFile imgFile) throws Exception {
        // 配置信息
        String endpoint = "https://oss-cn-wuhan-lr.aliyuncs.com";
        String bucketName = "yan-blog33";
        String region = "cn-wuhan-lr";

        EnvironmentVariableCredentialsProvider credentialsProvider = CredentialsProviderFactory.newEnvironmentVariableCredentialsProvider();

        ClientBuilderConfiguration clientBuilderConfiguration = new ClientBuilderConfiguration();
        clientBuilderConfiguration.setSignatureVersion(SignVersion.V4);

        OSS ossClient = OSSClientBuilder.create()
                .endpoint(endpoint)
                .credentialsProvider(credentialsProvider)
                .clientConfiguration(clientBuilderConfiguration)
                .region(region)
                .build();

        try {
            InputStream inputStream = imgFile.getInputStream();
            String originalFilename = imgFile.getOriginalFilename();
            String objectName = generateFilePath(originalFilename);

            // 1. 自动识别文件类型 (核心步骤)
            // 如果识别失败，默认为 jpg，防止变成 octet-stream 导致下载
            String contentType = MediaTypeFactory.getMediaType(objectName)
                    .orElse(MediaType.IMAGE_JPEG)
                    .toString();

            // 2. 创建 Metadata (上传时就设置好)
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(contentType); // 设置类型，如 image/jpeg
            metadata.setContentLength(imgFile.getSize());
            // 设置为 inline 表示希望能在线打开，filename设置下载时的默认文件名
            metadata.setContentDisposition("inline;filename=" + originalFilename);

            // 3. 带 Metadata 上传
            PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, objectName, inputStream, metadata);
            ossClient.putObject(putObjectRequest);

            // 4. 生成签名 URL (不要再加 Override Header 的代码了！)
            Date expiration = new Date(System.currentTimeMillis() + 3600 * 1000); // 1小时
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucketName, objectName);
            request.setExpiration(expiration);

            URL signedUrl = ossClient.generatePresignedUrl(request);

            return signedUrl.toString();

        } catch (Exception e) {
            throw e;
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }

    private String generateFilePath(String originalFilename) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd");
        String datePath = sdf.format(new Date());
        String uuid = UUID.randomUUID().toString().replaceAll("-", "");
        String suffix = ".jpg"; // 默认后缀
        if (originalFilename != null && originalFilename.lastIndexOf(".") != -1) {
            suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        return datePath + "/" + uuid + suffix;
    }
}

