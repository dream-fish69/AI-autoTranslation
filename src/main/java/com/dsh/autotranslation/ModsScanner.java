package com.dsh.autotranslation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Walks the {@code mods/} directory and collects {@code assets/<namespace>/lang/<code>.json}.
 *
 * <p>Many NeoForge mods are JarJar wrappers: the real mod (and its lang files) live in
 * {@code META-INF/jarjar/*.jar}, so nested archives are scanned recursively.</p>
 */
public final class ModsScanner {

    private static final Gson GSON = new GsonBuilder().create();
    private static final int MAX_DEPTH = 4;

    /** One translation namespace (a mod id) and what it provides. */
    public static final class Namespace {
        public final String id;
        public final Map<String, String> source = new LinkedHashMap<>();
        /** What the mod itself already ships as zh_cn (empty when it has none). */
        public final Map<String, String> target = new LinkedHashMap<>();
        public boolean hasTarget;
        /** Jar (or nested jar) the strings came from, for debugging. */
        public String origin = "";
        /** Modification time of the archive, used to prefer the newest duplicate version. */
        public long jarTime = Long.MIN_VALUE;

        Namespace(String id) {
            this.id = id;
        }

        public int size() {
            return source.size();
        }
    }

    private final String sourceLang;
    private final String targetLang;
    private final Predicate<String> wanted;

    public ModsScanner(String sourceLang, String targetLang, Predicate<String> wanted) {
        this.sourceLang = sourceLang;
        this.targetLang = targetLang;
        this.wanted = wanted;
    }

    public Map<String, Namespace> scan(Path modsDir) throws IOException {
        Map<String, Namespace> result = new LinkedHashMap<>();
        if (!Files.isDirectory(modsDir)) {
            return result;
        }
        List<Path> jars;
        try (Stream<Path> stream = Files.list(modsDir)) {
            jars = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".jar") && !n.endsWith(".disabled") && !n.endsWith(".jar.jar");
                    })
                    .sorted()
                    .toList();
        }
        for (Path jar : jars) {
            Map<String, Namespace> fromThisJar = new LinkedHashMap<>();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                scanZip(zip, jar.getFileName().toString(), fromThisJar, 0);
            } catch (Exception e) {
                // A broken jar must not stop the scan.
                Logs.LOG.debug("Skipping unreadable jar {}", jar, e);
                continue;
            }
            long modified = Files.getLastModifiedTime(jar).toMillis();
            for (Map.Entry<String, Namespace> e : fromThisJar.entrySet()) {
                Namespace incoming = e.getValue();
                incoming.jarTime = modified;
                Namespace existing = result.get(e.getKey());
                if (existing == null || modified > existing.jarTime) {
                    // Newest jar wins: duplicate mod versions are common and only one is loaded.
                    result.put(e.getKey(), incoming);
                } else if (modified == existing.jarTime) {
                    merge(existing.source, incoming.source);
                    merge(existing.target, incoming.target);
                    existing.hasTarget |= incoming.hasTarget;
                }
            }
        }
        return result;
    }

    private void scanZip(ZipFile zip, String origin, Map<String, Namespace> result, int depth) throws IOException {
        List<ZipEntry> nested = new ArrayList<>();
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory()) {
                continue;
            }
            if (name.startsWith("META-INF/jarjar/") && name.endsWith(".jar")) {
                nested.add(entry);
                continue;
            }
            if (!name.startsWith("assets/") || !name.endsWith(".json") || !name.contains("/lang/")) {
                continue;
            }
            String[] parts = name.split("/");
            if (parts.length != 4) {
                continue;
            }
            String namespace = parts[1];
            String code = parts[3].substring(0, parts[3].length() - ".json".length());
            if (!wanted.test(namespace)) {
                continue;
            }
            if (code.equals(targetLang)) {
                Namespace ns = namespace(result, namespace);
                ns.hasTarget = true;
                try (var in = zip.getInputStream(entry)) {
                    merge(ns.target, readFlatJson(new InputStreamReader(in, StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    Logs.LOG.debug("Failed to read {} in {}", name, origin, e);
                }
            } else if (code.equals(sourceLang)) {
                Namespace ns = namespace(result, namespace);
                if (ns.origin.isEmpty()) {
                    ns.origin = origin;
                }
                try (var in = zip.getInputStream(entry)) {
                    merge(ns.source, readFlatJson(new InputStreamReader(in, StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    Logs.LOG.debug("Failed to read {} in {}", name, origin, e);
                }
            }
        }
        if (depth + 1 > MAX_DEPTH) {
            return;
        }
        for (ZipEntry entry : nested) {
            try (var in = zip.getInputStream(entry)) {
                scanNested(in.readAllBytes(), origin + "!" + entry.getName(), result, depth + 1);
            } catch (Exception e) {
                Logs.LOG.debug("Failed to read nested jar {} in {}", entry.getName(), origin, e);
            }
        }
    }

    private void scanNested(byte[] bytes, String origin, Map<String, Namespace> result, int depth) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            List<byte[]> deeper = new ArrayList<>();
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                if (name.startsWith("META-INF/jarjar/") && name.endsWith(".jar")) {
                    deeper.add(zis.readAllBytes());
                    continue;
                }
                if (!name.startsWith("assets/") || !name.endsWith(".json") || !name.contains("/lang/")) {
                    continue;
                }
                String[] parts = name.split("/");
                if (parts.length != 4) {
                    continue;
                }
                String namespace = parts[1];
                String code = parts[3].substring(0, parts[3].length() - ".json".length());
                if (!wanted.test(namespace)) {
                    continue;
                }
                if (code.equals(targetLang)) {
                    Namespace ns = namespace(result, namespace);
                    ns.hasTarget = true;
                    merge(ns.target, readFlatJson(new InputStreamReader(
                            new ByteArrayInputStream(zis.readAllBytes()), StandardCharsets.UTF_8)));
                } else if (code.equals(sourceLang)) {
                    Namespace ns = namespace(result, namespace);
                    if (ns.origin.isEmpty()) {
                        ns.origin = origin;
                    }
                    merge(ns.source, readFlatJson(new InputStreamReader(new ByteArrayInputStream(zis.readAllBytes()), StandardCharsets.UTF_8)));
                }
            }
            if (depth + 1 <= MAX_DEPTH) {
                for (byte[] inner : deeper) {
                    scanNested(inner, origin + "!/", result, depth + 1);
                }
            }
        }
    }

    private static Map<String, String> readFlatJson(java.io.Reader reader) {
        JsonElement root = JsonParser.parseReader(reader);
        Map<String, String> map = new LinkedHashMap<>();
        if (root.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                JsonElement value = e.getValue();
                if (value != null && value.isJsonPrimitive()) {
                    map.put(e.getKey(), value.getAsString());
                }
            }
        }
        return map;
    }

    private static void merge(Map<String, String> into, Map<String, String> from) {
        for (Map.Entry<String, String> e : from.entrySet()) {
            into.putIfAbsent(e.getKey(), e.getValue());
        }
    }

    private static Namespace namespace(Map<String, Namespace> map, String id) {
        return map.computeIfAbsent(id, Namespace::new);
    }
}

