package com.dsh.autotranslation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * CFPA (CFPAOrg/Minecraft-Mod-Language-Package) 汉化资源包：下载、缓存、按命名空间取译文。
 *
 * <p>资源包是 {@code assets/<ns>/lang/zh_cn.json} 的集合，所以可以只取我们关心的命名空间，
 * 既不需要把整个包塞进游戏，也不会覆盖模组自带的中文。</p>
 */
public final class CfpaClient implements CfpaSource {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ZIP_PREFIX = "Minecraft-Mod-Language-Modpack-";
    private static final String USER_AGENT = "auto-translation/1.0 (+Minecraft NeoForge mod)";
    private static final Pattern LANG_ENTRY = Pattern.compile("^assets/([^/]+)/lang/zh_cn\\.json$");
    private static final Pattern MD5 = Pattern.compile("^[0-9a-fA-F]{32}$");

    /** Packs smaller than this are stubs for a just-released Minecraft version. */
    private static final long MIN_USEFUL_BYTES = 512L * 1024L;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ZipFile zip;
    private final String version;
    private final Map<String, String> index = new HashMap<>();
    private final Map<String, Map<String, String>> cache = new HashMap<>();

    private CfpaClient(Path zipPath, String version) throws IOException {
        this.zip = new ZipFile(zipPath.toFile());
        this.version = version;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            Matcher matcher = LANG_ENTRY.matcher(entry.getName());
            if (matcher.matches()) {
                index.putIfAbsent(matcher.group(1), entry.getName());
            }
        }
    }

    /** Opens the cached/local pack, a remote range-read pack, or downloads one. Null = unavailable. */
    public static CfpaSource open(Config config, Path cfpaDir, String gameVersion) {
        if (!config.cfpaEnabled) {
            return null;
        }
        try {
            if (config.cfpaLocalZip != null && !config.cfpaLocalZip.isBlank()) {
                Path local = Path.of(config.cfpaLocalZip);
                if (Files.isRegularFile(local)) {
                    Logs.LOG.info("CFPA: using local pack {}", local);
                    return new CfpaClient(local, "local");
                }
                Logs.LOG.warn("CFPA: local pack not found at {}", local);
            }
            Path cached = cachedFresh(config, cfpaDir);
            if (cached != null) {
                Logs.LOG.info("CFPA: using cached pack {}", cached.getFileName());
                return new CfpaClient(cached, cached.getFileName().toString());
            }
            CfpaSource remote = RemoteCfpa.open(config, gameVersion);
            if (remote != null) {
                markChecked(cfpaDir);
                return remote;
            }
            Path zip = ensureDownloaded(config, cfpaDir, gameVersion);
            if (zip == null) {
                Logs.LOG.warn("CFPA: no pack available (offline or blocked); continuing with AI only");
                return null;
            }
            return new CfpaClient(zip, zip.getFileName().toString());
        } catch (Exception e) {
            Logs.LOG.warn("CFPA: failed to open pack: {}", e.toString());
            return null;
        }
    }

    /** Records a successful remote check so the update gate also works in range mode. */
    private static void markChecked(Path dir) {
        try {
            State state = new State();
            state.version = "remote";
            state.checkedAt = System.currentTimeMillis();
            state.save(dir.resolve("state.json"));
        } catch (Exception e) {
            Logs.LOG.debug("CFPA: cannot record remote check: {}", e.toString());
        }
    }

    /** Local zip that is still inside the update-check interval, or null. */
    private static Path cachedFresh(Config config, Path dir) {        State state = State.load(dir.resolve("state.json"));
        if (state == null || state.version.isBlank()) {
            return null;
        }
        long ttl = Math.max(1, config.cfpaCheckIntervalHours) * 3_600_000L;
        if (System.currentTimeMillis() - state.checkedAt >= ttl) {
            return null;
        }
        Path path = dir.resolve("cfpa-" + state.version + ".zip");
        return Files.isRegularFile(path) ? path : null;
    }

    public String version() {
        return version;
    }

    public int namespaceCount() {
        return index.size();
    }

    /** Translations for one namespace; empty when CFPA does not cover it. */
    public Map<String, String> translations(String namespace) {
        Map<String, String> cached = cache.get(namespace);
        if (cached != null) {
            return cached;
        }
        Map<String, String> result = new LinkedHashMap<>();
        String entryName = index.get(namespace);
        if (entryName != null) {
            try (InputStream in = zip.getInputStream(zip.getEntry(entryName))) {
                result.putAll(Json.flatten(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            } catch (Exception e) {
                Logs.LOG.debug("CFPA: cannot read {}: {}", entryName, e.toString());
            }
        }
        cache.put(namespace, result);
        return result;
    }

    @Override
    public void close() {
        try {
            zip.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ download

    private static Path ensureDownloaded(Config config, Path dir, String gameVersion) throws IOException {
        Files.createDirectories(dir);
        Path stateFile = dir.resolve("state.json");
        State state = State.load(stateFile);
        long now = System.currentTimeMillis();

        Path cached = cachedFresh(config, dir);
        if (cached != null) {
            Logs.LOG.info("CFPA: using cached pack {}", cached.getFileName());
            return cached;
        }
        Path existing = state == null || state.version.isBlank() ? null : dir.resolve("cfpa-" + state.version + ".zip");

        List<String> candidates = "auto".equalsIgnoreCase(config.cfpaVersion) || config.cfpaVersion.isBlank()
                ? candidates(gameVersion)
                : List.of(config.cfpaVersion.trim());
        for (String prefix : config.cfpaMirrorPrefixes) {
            String base = prefix + config.cfpaReleaseBase;
            // A pack below this size is a stub for a brand-new Minecraft version: prefer the newest
            // *useful* pack instead of the newest *existing* one.
            String chosen = null;
            long chosenSize = -1;
            for (String candidate : candidates) {
                long size = fetchSize(base + ZIP_PREFIX + candidate + ".zip");
                if (size <= 0) {
                    continue;
                }
                if (size >= MIN_USEFUL_BYTES) {
                    chosen = candidate;
                    chosenSize = size;
                    break;
                }
                if (size > chosenSize) {
                    chosen = candidate;
                    chosenSize = size;
                }
            }
            if (chosen == null) {
                continue;
            }
            String candidate = chosen;
            String zipUrl = base + ZIP_PREFIX + candidate + ".zip";
            String md5 = fetchText(base + candidate.replace('-', '.') + ".md5");
            Path target = dir.resolve("cfpa-" + candidate + ".zip");
            boolean unchanged = state != null
                    && candidate.equals(state.version)
                    && md5 != null
                    && md5.equalsIgnoreCase(state.md5)
                    && Files.isRegularFile(target);
            if (unchanged) {
                Logs.LOG.info("CFPA: {} unchanged (md5 {})", candidate, md5);
            } else {
                Logs.LOG.info("CFPA: downloading {} ({} KB) from {}",
                        candidate, chosenSize / 1024, prefix.isEmpty() ? "github" : prefix);
                if (!download(zipUrl, target)) {
                    continue;
                }
            }
            State next = new State();
            next.version = candidate;
            next.mirror = prefix;
            next.md5 = md5 == null ? "" : md5;
            next.checkedAt = now;
            next.save(stateFile);
            return target;
        }

        if (existing != null && Files.isRegularFile(existing)) {
            Logs.LOG.warn("CFPA: update check failed, keeping cached {}", existing.getFileName());
            return existing;
        }
        return null;
    }

    /** All release URLs to try, mirror by mirror, newest useful pack first. */
    static java.util.List<String> urls(Config config, String gameVersion) {
        java.util.List<String> candidates = "auto".equalsIgnoreCase(config.cfpaVersion) || config.cfpaVersion.isBlank()
                ? candidates(gameVersion)
                : java.util.List.of(config.cfpaVersion.trim());
        java.util.List<String> urls = new ArrayList<>();
        for (String prefix : config.cfpaMirrorPrefixes) {
            String base = prefix + config.cfpaReleaseBase;
            for (String candidate : candidates) {
                urls.add(base + ZIP_PREFIX + candidate + ".zip");
            }
        }
        return urls;
    }

    static String versionOf(String url) {
        int start = url.lastIndexOf('/') + 1;
        int end = url.lastIndexOf(".zip");
        return end > start ? url.substring(start + ZIP_PREFIX.length(), end) : "remote";
    }

    /** CFPA names packs after the Minecraft version with dots replaced by dashes. */
    static List<String> candidates(String gameVersion) {
        List<String> out = new ArrayList<>();
        if (gameVersion != null && !gameVersion.isBlank()) {
            String[] parts = gameVersion.split("\\.");
            if (parts.length >= 2) {
                out.add(parts[0] + "-" + parts[1]);
            }
            if (gameVersion.startsWith("26.")) {
                out.add("26-1");
            }
        }
        out.add("1-21");
        out.add("1-20");
        out.add("1-19");
        return out.stream().distinct().toList();
    }

    /** Content length of a release asset, or -1 when missing. */
    private static long fetchSize(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=0-0")
                    .GET()
                    .build();
            HttpResponse<java.io.InputStream> response =
                    HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int code = response.statusCode();
            long size;
            if (code != 200 && code != 206) {
                size = -1;
            } else {
                String contentRange = response.headers().firstValue("Content-Range").orElse("");
                int slash = contentRange.lastIndexOf('/');
                size = -1;
                if (slash >= 0) {
                    try {
                        size = Long.parseLong(contentRange.substring(slash + 1).trim());
                    } catch (NumberFormatException ignored) {
                        // fall through to Content-Length
                    }
                }
                if (size < 0) {
                    size = response.headers().firstValueAsLong("Content-Length").orElse(code == 206 ? 1 : -1);
                }
            }
            response.body().close();
            return size;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String fetchText(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            String text = response.body().trim().toLowerCase(Locale.ROOT);
            return MD5.matcher(text).matches() ? text : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean download(String url, Path target) {
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                Logs.LOG.warn("CFPA: {} -> HTTP {}", url, response.statusCode());
                return false;
            }
            Files.createDirectories(target.getParent());
            try (InputStream in = response.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(tmp);
            if (size < 1024) {
                Logs.LOG.warn("CFPA: downloaded file suspiciously small ({} bytes)", size);
                Files.deleteIfExists(tmp);
                return false;
            }
            try (ZipFile probe = new ZipFile(tmp.toFile())) {
                if (probe.size() == 0) {
                    Files.deleteIfExists(tmp);
                    return false;
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            Logs.LOG.info("CFPA: saved {} ({} KB)", target.getFileName(), size / 1024);
            return true;
        } catch (Exception e) {
            Logs.LOG.warn("CFPA: download failed from {}: {}", url, e.toString());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            return false;
        }
    }

    /** Persisted resolution state, also used to skip the daily update check. */
    static final class State {
        String version = "";
        String mirror = "";
        String md5 = "";
        long checkedAt = 0L;

        static State load(Path file) {
            try {
                if (Files.isRegularFile(file)) {
                    State state = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), State.class);
                    if (state != null) {
                        return state;
                    }
                }
            } catch (Exception e) {
                Logs.LOG.debug("CFPA: cannot read state: {}", e.toString());
            }
            return null;
        }

        void save(Path file) {
            try {
                Json.writeAtomically(file, GSON.toJson(this));
            } catch (Exception e) {
                Logs.LOG.debug("CFPA: cannot write state: {}", e.toString());
            }
        }
    }
}
