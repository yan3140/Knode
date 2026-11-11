package com.knode.content.upload;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.comm.NoRetryStrategy;
import com.aliyun.oss.common.comm.Protocol;
import com.aliyun.oss.common.comm.SignVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UploadConfiguration {
    @Bean public UploadSettings uploadSettings(
            @Value("${KNODE_CONTENT_UPLOAD_ENABLED:false}") String enabled,
            @Value("${KNODE_CONTENT_UPLOAD_ENDPOINT:}") String endpoint,
            @Value("${KNODE_CONTENT_UPLOAD_REGION:}") String region,
            @Value("${KNODE_CONTENT_UPLOAD_BUCKET:}") String bucket) {
        return UploadSettings.parse(enabled,endpoint,region,bucket);
    }
    @Bean(destroyMethod="close")
    @ConditionalOnMissingBean(UploadStorage.class)
    public UploadStorage uploadStorage(UploadSettings settings) {
        if (!settings.enabled()) return (name,type,bytes) -> { throw new UploadFailure(503,500); };
        try {
            var credentials=CredentialsProviderFactory.newEnvironmentVariableCredentialsProvider();
            credentials.getCredentials();
            OSS client=OSSClientBuilder.create().endpoint(settings.endpoint())
                    .credentialsProvider(credentials).clientConfiguration(sdkConfiguration())
                    .region(settings.region()).build();
            return new AliyunUploadStorage(client,settings.bucket());
        } catch (Exception failure) {
            throw new IllegalStateException("Upload configuration unavailable");
        }
    }
    public static ClientBuilderConfiguration sdkConfiguration() {
        ClientBuilderConfiguration config=new ClientBuilderConfiguration();
        config.setSignatureVersion(SignVersion.V4);
        config.setProtocol(Protocol.HTTPS);
        config.setUseSystemPropertyValues(false);
        config.setRedirectEnable(false);
        config.setMaxErrorRetry(0);
        config.setRetryStrategy(new NoRetryStrategy());
        config.setMaxConnections(10);
        config.setConnectionRequestTimeout(500);
        config.setConnectionTimeout(1500);
        config.setSocketTimeout(2500);
        config.setRequestTimeoutEnabled(true);
        config.setRequestTimeout(5000);
        return config;
    }
}
