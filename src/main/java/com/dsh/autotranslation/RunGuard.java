package com.dsh.autotranslation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Cheap "is there anything to do?" gate for repeated launches.
 *
 * <p>After a successful run we remember a fingerprint of the inputs that matter (mods folder
 * listing, key config, generated pack state) plus the per-namespace summary. On the next launch,
 * an identical fingerprint plus a not-yet-due CFPA check means there is nothing to translate, so
 * the pipeline never starts, the mod stays dormant, and the status screen is restored from the
 * stored record.</p>
 */
public final class RunGuard {

    public record Record(String fingerprint, String status, long at, String summary,
                         Map<String, String> namespaces) {
    }

    private RunGuard() {
    }

    public static boolean canSkip(Config config, Path modsDir, Path packDir, Path workDir) {
        try {
            Record record = loadRecord(workDir);
            if (record == null || !"DONE".equals(record.status())) {
                return false;
            }
            if (!record.fingerprint().equals(fingerprint(config, modsDir, packDir))) {
                return false;
            }
            return !cfpaDue(config, workDir);
        } catch (Exception e) {
            Logs.LOG.debug("RunGuard: cannot evaluate, will run: {}", e.toString());
            return false;
        }
    }

    public static void remember(Config config, Path modsDir, Path packDir, Path workDir, String summary) {
        try {
            Record record = new Record(fingerprint(config, modsDir, packDir), "DONE",
                    System.currentTimeMillis(), summary, new LinkedHashMap<>(RunState.SUMMARIES));
            Json.writeAtomically(workDir.resolve("run-state.json"), Json.GSON.toJson(record));
        } catch (Exception e) {
            Logs.LOG.debug("RunGuard: cannot remember run: {}", e.toString());
        }
    }

    public static Record loadRecord(Path workDir) {
        Path file = workDir.resolve("run-state.json");
        try {
            if (Files.isRegularFile(file)) {
                return Json.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Record.class);
            }
        } catch (Exception e) {
            Logs.LOG.debug("RunGuard: cannot read {}: {}", file, e.toString());
        }
        return null;
    }

    static String fingerprint(Config config, Path modsDir, Path packDir) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append(config.targetLanguage).append('|').append(config.sourceLanguage)
                .append('|').append(config.provider).append('|').append(config.model)
                .append('|').append(Integer.toHexString(Objects.hashCode(config.apiKey)))
                .append('|').append(config.fillMissingKeys)
                .append('|').append(config.overwriteExistingTranslations)
                .append('|').append(config.learnGlossary)
                .append('|').append(config.cfpaEnabled)
                .append('|').append(config.cfpaPerMod)
                .append('|').append(config.cfpaVersion)
                .append('|').append(config.includedNamespaces)
                .append('|').append(config.excludedNamespaces)
                .append('\n');

        try (Stream<Path> stream = Files.list(modsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            text.append(path.getFileName())
                                    .append('|').append(Files.size(path))
                                    .append('|').append(Files.getLastModifiedTime(path).toMillis())
                                    .append('\n');
                        } catch (IOException ignored) {
                            // a jar that vanished mid-scan simply changes nothing
                        }
                    });
        }

        Path packState = packDir.resolve(".state");
        text.append("pack=")
                .append(Files.isRegularFile(packState) ? Files.readString(packState).trim() : "missing")
                .append('\n');
        return sha256(text.toString());
    }

    static boolean cfpaDue(Config config, Path workDir) {
        if (!config.cfpaEnabled) {
            return false;
        }
        Path stateFile = workDir.resolve("cfpa").resolve("state.json");
        if (!Files.isRegularFile(stateFile)) {
            return true;
        }
        CfpaClient.State state = CfpaClient.State.load(stateFile);
        if (state == null) {
            return true;
        }
        long ttl = Math.max(1, config.cfpaCheckIntervalHours) * 3_600_000L;
        return System.currentTimeMillis() - state.checkedAt > ttl;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
