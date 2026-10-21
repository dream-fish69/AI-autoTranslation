package com.dsh.autotranslation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Small JSON helpers shared by the cache and the generated pack. */
public final class Json {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Json() {
    }

    public static void writeAtomically(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Stable hash over the source strings, used to detect mod updates. */
    public static String hashEntries(Map<String, String> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(entries).forEach((key, value) -> {
                digest.update(key.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 1);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return Integer.toHexString(entries.hashCode());
        }
    }

    public static String toJson(Map<String, String> entries) {
        JsonObject object = new JsonObject();
        new TreeMap<>(entries).forEach(object::addProperty);
        return GSON.toJson(object);
    }

    public static Map<String, String> flatten(String json) {
        Map<String, String> result = new TreeMap<>();
        JsonElement root = JsonParser.parseString(json);
        if (root.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    result.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        return result;
    }
}
