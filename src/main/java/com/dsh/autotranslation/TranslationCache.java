package com.dsh.autotranslation;

import java.util.LinkedHashMap;
import java.util.Map;

/** On-disk cache for one namespace: English source hash plus translated entries. */
public final class TranslationCache {

    public String namespace = "";
    public String model = "";
    public String sourceHash = "";
    public long updatedAt = 0L;
    public Map<String, String> entries = new LinkedHashMap<>();

    public static TranslationCache load(java.nio.file.Path file) {
        try {
            if (java.nio.file.Files.isRegularFile(file)) {
                TranslationCache cache = Json.GSON.fromJson(
                        java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8),
                        TranslationCache.class);
                if (cache != null) {
                    if (cache.entries == null) {
                        cache.entries = new LinkedHashMap<>();
                    }
                    return cache;
                }
            }
        } catch (Exception e) {
            Logs.LOG.warn("Corrupt translation cache {}, starting fresh", file, e);
        }
        return new TranslationCache();
    }

    public void save(java.nio.file.Path file) {
        try {
            updatedAt = System.currentTimeMillis();
            Json.writeAtomically(file, Json.GSON.toJson(this));
        } catch (Exception e) {
            Logs.LOG.warn("Failed to save cache {}", file, e);
        }
    }
}

