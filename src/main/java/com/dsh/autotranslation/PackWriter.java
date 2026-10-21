package com.dsh.autotranslation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Writes the generated resource pack:
 * <pre>
 * config/auto-translation/pack/assets/&lt;namespace&gt;/lang/zh_cn.json
 * </pre>
 * Only namespaces listed here are emitted, and only keys we actually translated,
 * so a mod's own fallback strings keep working for everything else.
 */
public final class PackWriter {

    private PackWriter() {
    }

    public static boolean write(Path packDir, Map<String, Map<String, String>> byNamespace, String language) throws IOException {
        Files.createDirectories(packDir.resolve("assets"));
        StringBuilder state = new StringBuilder();
        new TreeMap<>(byNamespace).forEach((namespace, entries) ->
                state.append(namespace).append('=').append(Json.hashEntries(entries)).append('\n'));
        String newState = Integer.toHexString(state.toString().hashCode());

        Path stateFile = packDir.resolve(".state");
        boolean changed = !Files.isRegularFile(stateFile)
                || !newState.equals(Files.readString(stateFile, StandardCharsets.UTF_8).trim());
        if (!changed) {
            return false;
        }

        Path assets = packDir.resolve("assets");
        deleteTree(assets);
        Files.createDirectories(assets);
        for (Map.Entry<String, Map<String, String>> e : new TreeMap<>(byNamespace).entrySet()) {
            Path file = assets.resolve(e.getKey()).resolve("lang").resolve(language + ".json");
            Json.writeAtomically(file, Json.toJson(new TreeMap<>(e.getValue())));
        }
        Json.writeAtomically(stateFile, newState);
        return true;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
