package com.finagent.storage;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 基于腾讯云 COS 的 {@link ObjectStorage} 实现。
 *
 * <p>选择官方 COS SDK 而非 S3 兼容接口：COS 的 S3 兼容层在部分操作上存在差异，
 * 官方 SDK 的行为更可预期。可替换性由 {@link ObjectStorage} 抽象保证。
 */
public class CosObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(CosObjectStorage.class);

    private final COSClient cosClient;
    private final CosProperties props;

    public CosObjectStorage(COSClient cosClient, CosProperties props) {
        this.cosClient = cosClient;
        this.props = props;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        String fullKey = fullKey(key);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        if (contentType != null) {
            metadata.setContentType(contentType);
        }
        cosClient.putObject(new PutObjectRequest(
                props.getBucket(), fullKey, new ByteArrayInputStream(content), metadata));
        log.debug("COS 写入成功: {} ({} 字节)", fullKey, content.length);
    }

    @Override
    public byte[] get(String key) {
        String fullKey = fullKey(key);
        try {
            COSObject object = cosClient.getObject(props.getBucket(), fullKey);
            try (InputStream in = object.getObjectContent()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            throw new NoSuchElementException("对象不存在或读取失败: " + fullKey, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return cosClient.doesObjectExist(props.getBucket(), fullKey(key));
    }

    @Override
    public void delete(String key) {
        cosClient.deleteObject(props.getBucket(), fullKey(key));
        log.debug("COS 删除成功: {}", fullKey(key));
    }

    @Override
    public String presignedUrl(String key, Duration ttl) {
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                props.getBucket(), fullKey(key), HttpMethodName.GET);
        request.setExpiration(new Date(System.currentTimeMillis() + ttl.toMillis()));
        URL url = cosClient.generatePresignedUrl(request);
        return url.toString();
    }

    @Override
    public List<String> list(String prefix, int maxKeys) {
        ListObjectsRequest request = new ListObjectsRequest(
                props.getBucket(), fullKey(prefix == null ? "" : prefix), null, null, maxKeys);
        List<String> keys = new ArrayList<>();
        for (COSObjectSummary summary : cosClient.listObjects(request).getObjectSummaries()) {
            keys.add(summary.getKey());
        }
        return keys;
    }

    private String fullKey(String key) {
        String prefix = props.getKeyPrefix() == null ? "" : props.getKeyPrefix();
        return prefix + key;
    }
}
