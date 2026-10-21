package com.finagent.storage;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.region.Region;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * COS 相关 Bean 的装配。
 *
 * <p>只有在四个必需配置项（secret-id / secret-key / region / bucket）全部存在时才装配，
 * 这样没有凭据的机器上应用仍可启动、测试仍可通过（COS 相关测试自动跳过）。
 */
@Configuration
@EnableConfigurationProperties(CosProperties.class)
@ConditionalOnProperty(prefix = "finagent.cos",
        name = {"secret-id", "secret-key", "region", "bucket"})
public class ObjectStorageConfig {

    @Bean(destroyMethod = "shutdown")
    public COSClient cosClient(CosProperties props) {
        COSCredentials credentials =
                new BasicCOSCredentials(props.getSecretId(), props.getSecretKey());
        return new COSClient(credentials, new ClientConfig(new Region(props.getRegion())));
    }

    @Bean
    public ObjectStorage objectStorage(COSClient cosClient, CosProperties props) {
        return new CosObjectStorage(cosClient, props);
    }
}
