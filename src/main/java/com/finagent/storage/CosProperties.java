package com.finagent.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 腾讯云 COS 配置。
 *
 * <p>值由环境变量经 Spring Boot 宽松绑定注入，不写入仓库：
 * FINAGENT_COS_SECRET_ID / FINAGENT_COS_SECRET_KEY / FINAGENT_COS_REGION /
 * FINAGENT_COS_BUCKET / FINAGENT_COS_KEY_PREFIX。
 */
@ConfigurationProperties(prefix = "finagent.cos")
public class CosProperties {

    /** 腾讯云 SecretId */
    private String secretId;

    /** 腾讯云 SecretKey */
    private String secretKey;

    /** 地域，例如 ap-guangzhou */
    private String region;

    /** 存储桶，形如 mybucket-1251234567（必须带 APPID 后缀） */
    private String bucket;

    /** 对象键统一前缀，便于多环境隔离，例如 dev/ */
    private String keyPrefix = "";

    public String getSecretId() {
        return secretId;
    }

    public void setSecretId(String secretId) {
        this.secretId = secretId;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
