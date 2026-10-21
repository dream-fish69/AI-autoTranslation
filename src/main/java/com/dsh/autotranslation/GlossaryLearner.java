package com.dsh.autotranslation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Harvests short English→Chinese pairs from CFPA and stores them in
 * {@code config/auto-translation/glossary-learned.json}, so the offline dictionary grows with
 * every run and needs less API work next time.
 */
public final class GlossaryLearner {

    private static final int MAX_ENTRIES = 20_000;
    private static final int MAX_PAIRS_PER_RUN = 2_000;

    private GlossaryLearner() {
    }

    public static boolean isLearnable(String english, String chinese) {
        if (english == null || chinese == null) {
            return false;
        }
        String en = english.trim();
        String zh = chinese.trim();
        if (en.isEmpty() || en.length() > 24 || zh.isEmpty() || zh.length() > 14) {
            return false;
        }
        if (!en.matches("[A-Za-z][A-Za-z'’\\-]*(?: [A-Za-z][A-Za-z'’\\-]*){0,2}")) {
            return false;
        }
        boolean cjk = zh.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff);
        return cjk && !zh.contains("%");
    }

    public static Map<String, String> load(Path file) {
        try {
            if (file != null && Files.isRegularFile(file)) {
                return Json.flatten(Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Logs.LOG.debug("Glossary: cannot read {}: {}", file, e.toString());
        }
        return Map.of();
    }

    public static synchronized void merge(Path file, Map<String, String> pairs) {
        try {
            Map<String, String> all = new LinkedHashMap<>(load(file));
            int added = 0;
            for (Map.Entry<String, String> entry : pairs.entrySet()) {
                if (added >= MAX_PAIRS_PER_RUN) {
                    break;
                }
                String key = entry.getKey().trim();
                String value = entry.getValue().trim();
                if (isLearnable(key, value) && all.putIfAbsent(key, value) == null) {
                    added++;
                }
            }
            if (added == 0) {
                return;
            }
            if (all.size() > MAX_ENTRIES) {
                Logs.LOG.info("Glossary: limit {} reached, skipping new terms", MAX_ENTRIES);
                return;
            }
            Json.writeAtomically(file, Json.toJson(new TreeMap<>(all)));
            Logs.LOG.info("Glossary: learned {} new term(s), {} total", added, all.size());
        } catch (Exception e) {
            Logs.LOG.warn("Glossary: failed to save {}", file, e);
        }
    }
}
