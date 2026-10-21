package com.dsh.autotranslation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Offline fallback "model": a term dictionary built from
 * <ul>
 *   <li>the vanilla {@code en_us} / {@code zh_cn} language files (read from the local game files,
 *       so it ships no data of its own and stays in sync with the game version), and</li>
 *   <li>a small curated glossary bundled in the mod jar for mod UI vocabulary the vanilla game
 *       never uses (Waypoint, Backpack, ...).</li>
 * </ul>
 *
 * <p>It matches whole strings first, then composes translations word by word (longest phrase
 * wins). Strings where less than 60% of the letters can be covered are left in English on
 * purpose — a half-translated sentence reads worse than the original.</p>
 */
public final class OfflineDictionary {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z][A-Za-z'’\\-]*|\\s+|.");
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.:/-]+");
    private static final double MIN_COVERAGE = 0.6;
    /** Articles carry no meaning in Chinese; dropping them reads better than 一个/该. */
    private static final java.util.Set<String> DROP_WORDS = java.util.Set.of("a", "an", "the");
    /** A trailing preposition is almost always a truncated label ("... to KM at"); drop it. */
    private static final java.util.Set<String> TRAILING_DROP = java.util.Set.of("at", "to");

    private final Map<String, String> exact = new HashMap<>();
    private final Map<String, String> words = new HashMap<>();

    public static OfflineDictionary load(Path mcRoot, Path clientJar, Path learnedFile) {
        OfflineDictionary dictionary = new OfflineDictionary();
        try {
            dictionary.loadVanilla(mcRoot, clientJar);
        } catch (Exception e) {
            Logs.LOG.warn("Offline dictionary: vanilla terms unavailable ({})", e.toString());
        }
        if (learnedFile != null) {
            try {
                Map<String, String> learned = GlossaryLearner.load(learnedFile);
                learned.forEach((english, chinese) -> dictionary.add(english, chinese, false));
                if (!learned.isEmpty()) {
                    Logs.LOG.info("Offline dictionary: {} learned term(s) from CFPA", learned.size());
                }
            } catch (Exception e) {
                Logs.LOG.warn("Offline dictionary: learned glossary unavailable ({})", e.toString());
            }
        }
        try {
            dictionary.loadGlossary();
        } catch (Exception e) {
            Logs.LOG.warn("Offline dictionary: bundled glossary unavailable ({})", e.toString());
        }
        Logs.LOG.info("Offline dictionary ready: {} phrases, {} words", dictionary.exact.size(), dictionary.words.size());
        return dictionary;
    }

    public int size() {
        return exact.size();
    }

    /** @return Chinese text, or {@code null} when the string is not worth/possible to translate. */
    public String translate(String text) {
        if (text == null) {
            return null;
        }
        String input = text.trim();
        if (input.isEmpty() || input.length() > 200 || !hasAsciiLetter(input)) {
            return null;
        }
        if (IDENTIFIER.matcher(input).matches() && (input.indexOf(':') >= 0 || input.indexOf('/') >= 0)) {
            return null;
        }

        String exactHit = exact.get(input.toLowerCase(Locale.ROOT));
        if (exactHit != null) {
            return exactHit;
        }

        List<String> segments = new ArrayList<>();
        Matcher matcher = SEGMENT.matcher(input);
        while (matcher.find()) {
            segments.add(matcher.group());
        }

        StringBuilder out = new StringBuilder(input.length() + 8);
        int total = 0;
        int translated = 0;
        boolean anyCjk = false;
        int lastWord = -1;
        for (int k = segments.size() - 1; k >= 0; k--) {
            if (isWord(segments.get(k))) {
                lastWord = k;
                break;
            }
        }
        int i = 0;
        while (i < segments.size()) {
            String segment = segments.get(i);
            if (!isWord(segment)) {
                append(out, segment);
                i++;
                continue;
            }
            String lowerWord = segment.toLowerCase(Locale.ROOT);
            if (DROP_WORDS.contains(lowerWord)
                    || (i == lastWord && TRAILING_DROP.contains(lowerWord))) {
                total += segment.length();
                translated += segment.length();
                i++;
                continue;
            }
            total += segment.length();

            String hit = null;
            int consumed = 1;
            int maxWords = Math.min(3, (segments.size() - i + 1) / 2);
            for (int n = maxWords; n >= 2 && hit == null; n--) {
                StringBuilder phrase = new StringBuilder(segment);
                boolean usable = true;
                for (int k = 1; k < n; k++) {
                    String separator = segments.get(i + 2 * k - 1);
                    String next = segments.get(i + 2 * k);
                    if (!separator.isBlank() || !isWord(next)) {
                        usable = false;
                        break;
                    }
                    phrase.append(' ').append(next);
                }
                if (!usable) {
                    continue;
                }
                String phraseHit = exact.get(phrase.toString().toLowerCase(Locale.ROOT));
                if (phraseHit != null) {
                    hit = phraseHit;
                    consumed = n;
                }
            }
            if (hit == null) {
                hit = word(segment);
            }
            if (hit != null) {
                append(out, hit);
                translated += segment.length();
                anyCjk |= hasCjk(hit);
            } else {
                append(out, segment);
            }
            i += consumed == 1 ? 1 : 2 * consumed - 1;
        }

        if (!anyCjk || total == 0 || translated < total * MIN_COVERAGE) {
            return null;
        }
        String result = out.toString()
                .replaceAll("(?<=[\\u4e00-\\u9fff])[ \\t]+(?=[\\u4e00-\\u9fff])", "")
                .trim();
        return result.equalsIgnoreCase(input) ? null : result;
    }

    // ------------------------------------------------------------------ lookup

    private String word(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        String hit = words.get(lower);
        if (hit == null && lower.length() > 3 && lower.endsWith("s")) {
            hit = words.get(lower.substring(0, lower.length() - 1));
        }
        if (hit == null && lower.length() > 4 && lower.endsWith("es")) {
            hit = words.get(lower.substring(0, lower.length() - 2));
        }
        return hit;
    }

    private void add(String english, String chinese, boolean override) {
        if (english == null || chinese == null) {
            return;
        }
        String en = english.trim();
        String zh = chinese.trim();
        if (en.isEmpty() || zh.isEmpty() || en.length() > 64 || en.equalsIgnoreCase(zh)) {
            return;
        }
        if (en.indexOf('%') >= 0 || en.indexOf('§') >= 0 || zh.indexOf('%') >= 0) {
            return;
        }
        String key = en.toLowerCase(Locale.ROOT);
        if (override) {
            exact.put(key, zh);
        } else {
            exact.putIfAbsent(key, zh);
        }
        if (en.indexOf(' ') < 0 && en.length() <= 24) {
            if (override) {
                words.put(key, zh);
            } else {
                words.putIfAbsent(key, zh);
            }
        }
    }

    // ------------------------------------------------------------------ sources

    private void loadVanilla(Path mcRoot, Path clientJar) throws IOException {
        Map<String, String> english = readJarLang(clientJar, "assets/minecraft/lang/en_us.json");
        Map<String, String> chinese = readAssetsLang(mcRoot, "minecraft/lang/zh_cn.json");
        if (english.isEmpty() || chinese.isEmpty()) {
            Logs.LOG.info("Offline dictionary: vanilla language files not found (en={}, zh={})",
                    english.size(), chinese.size());
            return;
        }
        for (Map.Entry<String, String> entry : english.entrySet()) {
            String zh = chinese.get(entry.getKey());
            if (zh != null) {
                add(entry.getValue(), zh, false);
            }
        }
    }

    private void loadGlossary() throws IOException {
        try (InputStream in = OfflineDictionary.class.getResourceAsStream(
                "/assets/auto_translation/lang/offline-glossary.json")) {
            if (in == null) {
                return;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    add(entry.getKey(), entry.getValue().getAsString(), true);
                }
            }
        }
    }

    private static Map<String, String> readJarLang(Path jar, String entryName) throws IOException {
        if (jar == null || !Files.isRegularFile(jar)) {
            return Map.of();
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(entryName);
            if (entry == null) {
                return Map.of();
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return Json.flatten(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Non-English vanilla languages live in the asset index objects, not in the client jar.
     * Picks the biggest index file (the one belonging to the newest game version that has the
     * file) and reads the hashed object.
     */
    private static Map<String, String> readAssetsLang(Path mcRoot, String objectPath) throws IOException {
        if (mcRoot == null) {
            return Map.of();
        }
        Path indexes = mcRoot.resolve("assets").resolve("indexes");
        Path objects = mcRoot.resolve("assets").resolve("objects");
        if (!Files.isDirectory(indexes) || !Files.isDirectory(objects)) {
            return Map.of();
        }
        List<Path> candidates;
        try (Stream<Path> stream = Files.list(indexes)) {
            candidates = stream
                    .filter(path -> path.toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(OfflineDictionary::fileSize).reversed())
                    .toList();
        }
        for (Path index : candidates) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(index, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                JsonObject entries = root.getAsJsonObject("objects");
                if (entries == null) {
                    continue;
                }
                JsonElement ref = entries.get(objectPath);
                if (ref == null || !ref.isJsonObject()) {
                    continue;
                }
                String hash = ref.getAsJsonObject().get("hash").getAsString();
                Path object = objects.resolve(hash.substring(0, 2)).resolve(hash);
                if (!Files.isRegularFile(object)) {
                    continue;
                }
                return Json.flatten(Files.readString(object, StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // try next index
            }
        }
        return Map.of();
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ text helpers

    private static boolean isWord(String segment) {
        return !segment.isEmpty() && isAsciiLetter(segment.charAt(0));
    }

    private static void append(StringBuilder out, String segment) {
        if (out.length() > 0 && !segment.isEmpty()
                && isAsciiLetterOrDigit(out.charAt(out.length() - 1))
                && isAsciiLetterOrDigit(segment.charAt(0))) {
            out.append(' ');
        }
        out.append(segment);
    }

    private static boolean hasAsciiLetter(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isAsciiLetter(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9');
    }
}
