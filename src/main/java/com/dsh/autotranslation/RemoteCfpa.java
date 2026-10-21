package com.dsh.autotranslation;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CFPA pack read through {@link RemoteZip}: only the central directory plus the handful of
 * {@code assets/<ns>/lang/zh_cn.json} entries we need are downloaded (typically tens of KB
 * instead of the full 3 MB pack).
 */
public final class RemoteCfpa implements CfpaSource {

    private static final Pattern LANG_ENTRY = Pattern.compile("^assets/([^/]+)/lang/zh_cn\\.json$");
    private static final int MAX_PROBES = 6;

    private final RemoteZip zip;
    private final String version;
    private final Map<String, String> index = new HashMap<>();
    private final Map<String, Map<String, String>> cache = new HashMap<>();

    private RemoteCfpa(RemoteZip zip, String version) {
        this.zip = zip;
        this.version = version;
        zip.entries().keySet().forEach(name -> {
            Matcher matcher = LANG_ENTRY.matcher(name);
            if (matcher.matches()) {
                index.putIfAbsent(matcher.group(1), name);
            }
        });
    }

    /** Tries the same mirrors/candidates as the downloader, bounded to a few attempts. */
    public static RemoteCfpa open(Config config, String gameVersion) {
        if (!config.cfpaPerMod || !config.cfpaEnabled) {
            return null;
        }
        int probes = 0;
        for (String url : CfpaClient.urls(config, gameVersion)) {
            if (++probes > MAX_PROBES) {
                return null;
            }
            try {
                RemoteZip zip = RemoteZip.open(url);
                String version = CfpaClient.versionOf(url);
                Logs.LOG.info("CFPA: using remote pack {} via ranges ({} bytes)", version, zip.size());
                return new RemoteCfpa(zip, version);
            } catch (Exception e) {
                Logs.LOG.debug("CFPA remote probe failed: {}", e.toString());
            }
        }
        return null;
    }

    @Override
    public String version() {
        return version + " (remote)";
    }

    @Override
    public int namespaceCount() {
        return index.size();
    }

    @Override
    public Map<String, String> translations(String namespace) {
        Map<String, String> cached = cache.get(namespace);
        if (cached != null) {
            return cached;
        }
        Map<String, String> result = new LinkedHashMap<>();
        String entry = index.get(namespace);
        if (entry != null) {
            try {
                result.putAll(Json.flatten(new String(zip.read(entry), StandardCharsets.UTF_8)));
            } catch (Exception e) {
                Logs.LOG.debug("CFPA remote: cannot read {}: {}", entry, e.toString());
            }
        }
        cache.put(namespace, result);
        return result;
    }

    @Override
    public void close() {
        // RemoteZip keeps no persistent resources.
    }
}
