package com.finagent.storage;

import java.time.Duration;
import java.util.List;

/**
 * 对象存储抽象。
 *
 * <p>设计文档 §14 要求通过抽象层屏蔽具体存储实现，便于在 COS / S3 / MinIO 之间替换。
 * 当前实现为腾讯云 COS（见 {@link CosObjectStorage}）。
 *
 * <p>对象键（key）使用相对路径即可，实现类会统一加上配置的前缀。
 */
public interface ObjectStorage {

    /** 写入对象，已存在则覆盖。 */
    void put(String key, byte[] content, String contentType);

    /** 读取对象；不存在或读取失败时抛出 {@link java.util.NoSuchElementException}。 */
    byte[] get(String key);

    /** 判断对象是否存在。 */
    boolean exists(String key);

    /** 删除对象；对象不存在时不报错。 */
    void delete(String key);

    /** 生成带有效期的下载链接，供前端或人工直接访问。 */
    String presignedUrl(String key, Duration ttl);

    /** 列出指定前缀下的对象键（最多 maxKeys 个），用于排查与运维。 */
    List<String> list(String prefix, int maxKeys);
}
