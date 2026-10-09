package com.ts.travelserver.ai;

import com.ts.travelserver.config.AiConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 结果缓存：同一份输入短时间内不重复烧 token。
 *
 * <p>刻意没引 Caffeine / Redis——这个项目一路都在做「能不加依赖就不加」的简化，
 * 一个 LRU + TTL 的 HashMap 够用了：单机、单实例、课程演示规模。
 * 真要上集群，换 Redis 只改这一个类。
 *
 * <p>缓存键 = 场景 + prompt 版本 + 输入 hash。带上 prompt 版本是因为
 * 改了 prompt 就必须让旧缓存失效，否则拿到的是上一版口径算出来的 Δ。
 */
@Component
@RequiredArgsConstructor
public class AiCache {

    private final AiConfig config;

    private final Map<String, CachedItem> store = new LinkedHashMap<String, CachedItem>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedItem> eldest) {
            return size() > Math.max(16, config.getCacheMaxEntries());
        }
    };

    /**
     * 取缓存。过期或没有都返回 null。
     */
    public synchronized String get(String scene, String inputJson) {
        CachedItem entry = store.get(key(scene, inputJson));
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.at > ttlMillis()) {
            store.remove(key(scene, inputJson));
            return null;
        }
        return entry.content;
    }

    public synchronized void put(String scene, String inputJson, String content) {
        if (content == null) {
            return;
        }
        store.put(key(scene, inputJson), new CachedItem(content, System.currentTimeMillis()));
    }

    /** 手动清缓存：改了 prompt 又懒得重启时用（正常改版本号即可） */
    public synchronized void clear() {
        store.clear();
    }

    public String key(String scene, String inputJson) {
        return scene + "@" + config.getPromptVersion() + "@" + sha256(inputJson == null ? "" : inputJson);
    }

    private long ttlMillis() {
        return Math.max(1L, config.getCacheTtlMinutes()) * 60L * 1000L;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    /**
     * 缓存条目：内容 + 写入时间。
     */
    private record CachedItem(String content, long at) {
    }
}
