package com.dsh.autotranslation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cross-namespace translation memory: {@code English string -> Chinese}. Two mods that use the
 * same label ("Show Waypoints", "Chunk") only need to be translated once, by whichever backend
 * was used first. Strings containing placeholders are never stored, because their meaning depends
 * on the surrounding format string.
 */
public final class TranslationMemory {

    private static final int MAX_ENTRIES = 50_000;

    private final Map<String, String> entries;
    private final Path file;
    private boolean dirty;

    private TranslationMemory(Path file, Map<String, String> entries) {
        this.file = file;
        this.entries = new LinkedHashMap<>(entries);
    }

    public static TranslationMemory load(Path file) {
        Map<String, String> entries = new LinkedHashMap<>();
        if (file != null && Files.isRegularFile(file)) {
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    entries.putAll(Json.flatten(Files.readString(file, StandardCharsets.UTF_8)));
                    break;
                } catch (Exception e) {
                    // Antivirus/indexers sometimes hold the file right after a write; retry briefly.
                    Logs.LOG.debug("Memory: cannot read {} (attempt {}): {}", file, attempt + 1, e.toString());
                    try {
                        Thread.sleep(250L * (attempt + 1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        Logs.LOG.info("Memory: loaded {} entries from {}", entries.size(), file);
        return new TranslationMemory(file, entries);
    }

    public static TranslationMemory disabled() {
        return new TranslationMemory(null, Map.of());
    }

    public int size() {
        return entries.size();
    }

    /** @return a remembered translation, or null. */
    public String lookup(String english) {
        if (english == null) {
            return null;
        }
        return entries.get(english.trim());
    }

    public void remember(String english, String chinese) {
        if (!storable(english, chinese)) {
            return;
        }
        String key = english.trim();
        String value = chinese.trim();
        if (!value.equals(entries.get(key))) {
            entries.put(key, value);
            dirty = true;
        }
    }

    public void save() {
        if (!dirty || file == null) {
            return;
        }
        try {
            if (entries.size() > MAX_ENTRIES) {
                Logs.LOG.info("Memory: limit {} reached, not saving new entries", MAX_ENTRIES);
                return;
            }
            Json.writeAtomically(file, Json.toJson(new TreeMap<>(entries)));
            dirty = false;
        } catch (Exception e) {
            Logs.LOG.warn("Memory: failed to save {}", file, e);
        }
    }

    /** Placeholders make the text context dependent, so those pairs are not reusable. */
    public static boolean storable(String english, String chinese) {
        if (english == null || chinese == null) {
            return false;
        }
        String en = english.trim();
        String zh = chinese.trim();
        if (en.isEmpty() || zh.isEmpty() || en.length() > 160 || zh.length() > 160) {
            return false;
        }
        if (en.indexOf('%') >= 0 || en.indexOf('§') >= 0 || zh.indexOf('%') >= 0 || zh.indexOf('§') >= 0) {
            return false;
        }
        if (en.equalsIgnoreCase(zh)) {
            return false;
        }
        return zh.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff);
    }
}
