package com.yan;

import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.auth.EnvironmentVariableCredentialsProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.PutObjectResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

@SpringBootTest
public class OSSTest {

//    @Test
//    public void test() throws com.aliyuncs.exceptions.ClientException {
//        // Endpoint以华东1（杭州）为例，其它Region请按实际情况填写。
//        String endpoint = "https://oss-cn-wuhan-lr.aliyuncs.com";
//        // 从环境变量中获取访问凭证。运行本代码示例之前，请确保已设置环境变量OSS_ACCESS_KEY_ID和OSS_ACCESS_KEY_SECRET。
//        EnvironmentVariableCredentialsProvider credentialsProvider = CredentialsProviderFactory.newEnvironmentVariableCredentialsProvider();
//        // 填写Bucket名称。
//        String bucketName = "yan-blog33";
//        // 填写Object完整路径，完整路径中不能包含Bucket名称，例如exampledir/exampleobject.txt。
//        String objectName = "littlegirl.jpg";
//        // 填写Bucket所在地域。以华东1（杭州）为例，Region填写为cn-hangzhou。
//        String region = "cn-wuhan-lr";
//
//        // 创建OSSClient实例。
//        // 当OSSClient实例不再使用时，调用shutdown方法以释放资源。
//        ClientBuilderConfiguration clientBuilderConfiguration = new ClientBuilderConfiguration();
//        clientBuilderConfiguration.setSignatureVersion(SignVersion.V4);
//        OSS ossClient = OSSClientBuilder.create()
//                .endpoint(endpoint)
//                .credentialsProvider(credentialsProvider)
//                .clientConfiguration(clientBuilderConfiguration)
//                .region(region)
//                .build();
//
//        try {
//            // 填写字符串。
//            InputStream inputStream = new FileInputStream("C:\\Users\\Ellen\\Pictures\\Camera Roll\\202305301685408143828500.jpg");
//
//            // 创建PutObjectRequest对象。
//            PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, objectName, inputStream);
//
//            // 如果需要上传时设置存储类型和访问权限，请参考以下示例代码。
//            // ObjectMetadata metadata = new ObjectMetadata();
//            // metadata.setHeader(OSSHeaders.OSS_STORAGE_CLASS, StorageClass.Standard.toString());
//            // metadata.setObjectAcl(CannedAccessControlList.Private);
//            // putObjectRequest.setMetadata(metadata);
//
//            // 上传字符串。
//            PutObjectResult result = ossClient.putObject(putObjectRequest);
//        } catch (OSSException oe) {
//            System.out.println("Caught an OSSException, which means your request made it to OSS, "
//                    + "but was rejected with an error response for some reason.");
//            System.out.println("Error Message:" + oe.getErrorMessage());
//            System.out.println("Error Code:" + oe.getErrorCode());
//            System.out.println("Request ID:" + oe.getRequestId());
//            System.out.println("Host ID:" + oe.getHostId());
//        } catch (ClientException ce) {
//            System.out.println("Caught an ClientException, which means the client encountered "
//                    + "a serious internal problem while trying to communicate with OSS, "
//                    + "such as not being able to access the network.");
//            System.out.println("Error Message:" + ce.getMessage());
//        } catch (FileNotFoundException e) {
//            throw new RuntimeException(e);
//        } finally {
//            if (ossClient != null) {
//                ossClient.shutdown();
//            }
//        }
//    }
}


