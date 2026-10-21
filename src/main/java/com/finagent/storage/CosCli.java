package com.finagent.storage;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.region.Region;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 对象存储命令行工具，用于本地排查与手工取放文件。
 *
 * <p>不启动 Spring 上下文，直接从环境变量读凭据，因此启动很快。
 * 用法（在项目根目录，先执行 . .\scripts\load-env.ps1）：
 *
 * <pre>
 *   list [prefix]              列出对象
 *   download &lt;key&gt; &lt;本地路径&gt;   下载对象
 *   upload &lt;本地路径&gt; &lt;key&gt;     上传对象
 * </pre>
 */
public final class CosCli {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        CosProperties props = propsFromEnv();
        COSClient client = new COSClient(
                new BasicCOSCredentials(props.getSecretId(), props.getSecretKey()),
                new ClientConfig(new Region(props.getRegion())));
        try {
            CosObjectStorage storage = new CosObjectStorage(client, props);
            switch (args[0]) {
                case "list" -> {
                    String prefix = args.length > 1 ? args[1] : "";
                    List<String> keys = storage.list(prefix, 500);
                    System.out.println("共 " + keys.size() + " 个对象（前缀: [" + prefix + "]）");
                    keys.forEach(k -> System.out.println("  " + k));
                }
                case "buckets" -> {
                    System.out.println("当前凭据可见的桶：");
                    for (var bucket : client.listBuckets()) {
                        System.out.println("  " + bucket.getName() + "  @" + bucket.getLocation());
                    }
                }
                case "download" -> {
                    byte[] content = storage.get(args[1]);
                    Path target = Path.of(args[2]);
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.write(target, content);
                    System.out.println("已下载 " + args[1] + " -> " + target.toAbsolutePath()
                            + " (" + content.length + " 字节)");
                }
                case "delete" -> {
                    storage.delete(args[1]);
                    System.out.println("已删除 " + args[1]);
                }
                case "upload" -> {
                    byte[] content = Files.readAllBytes(Path.of(args[1]));
                    storage.put(args[2], content, "application/pdf");
                    System.out.println("已上传 " + args[1] + " -> " + args[2]
                            + " (" + content.length + " 字节)");
                }
                default -> usage();
            }
        } finally {
            client.shutdown();
        }
    }

    private static CosProperties propsFromEnv() {
        CosProperties p = new CosProperties();
        p.setSecretId(requireEnv("FINAGENT_COS_SECRET_ID"));
        p.setSecretKey(requireEnv("FINAGENT_COS_SECRET_KEY"));
        p.setRegion(requireEnv("FINAGENT_COS_REGION"));
        p.setBucket(requireEnv("FINAGENT_COS_BUCKET"));
        p.setKeyPrefix(envOr("FINAGENT_COS_KEY_PREFIX", ""));
        return p;
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name + "（先执行 . .\\scripts\\load-env.ps1）");
        }
        return v;
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static void usage() {
        System.out.println("用法: list [prefix] | download <key> <localFile> | upload <localFile> <key>");
    }

    private CosCli() {
    }
}
