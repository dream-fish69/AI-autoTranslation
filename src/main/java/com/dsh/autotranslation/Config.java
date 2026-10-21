package com.dsh.autotranslation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Plain-JSON configuration stored at {@code config/auto-translation/config.json}.
 *
 * <p>Deliberately not using the NeoForge config system: this mod must keep working
 * across loader refactors and the config is tiny.</p>
 */
public final class Config {

    private static final Logger LOG = LoggerFactory.getLogger("auto-translation");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Master switch. */
    public boolean enabled = true;

    /** Language code written into the generated resource pack. */
    public String targetLanguage = "zh_cn";

    /** Language file read from mod jars and translated. */
    public String sourceLanguage = "en_us";

    /**
     * If a namespace already ships the target language, should we still translate it?
     * Official translations are usually better, so this defaults to false.
     */
    public boolean overwriteExistingTranslations = false;

    /** Also fill the keys a mod's own zh_cn is missing, without touching the existing ones. */
    public boolean fillMissingKeys = true;

    /** Harvest short CFPA term pairs into the offline dictionary so the next run needs fewer API calls. */
    public boolean learnGlossary = true;

    /** Reuse translations of identical English strings across different mods. */
    public boolean translationMemory = true;

    /** "openai" (any OpenAI-compatible endpoint), "mymemory" (free, low quality) or "none". */
    public volatile String provider = "openai";

    /** API key for the OpenAI-compatible endpoint. Empty = only CFPA translations. */
    public volatile String apiKey = "";

    /** Full chat-completions endpoint, e.g. DeepSeek / Moonshot / SiliconFlow / local Ollama. */
    public volatile String baseUrl = "https://api.deepseek.com/v1/chat/completions";

    public volatile String model = "deepseek-chat";

    /** Extra instruction appended to the system prompt (glossary, tone, ...). */
    public String extraPrompt = "";

    /** Strings sent per request. */
    public int batchSize = 25;

    /** Concurrent requests. Keep small to stay friendly to rate limits. */
    public int parallelism = 4;

    /** Re-translate strings whose English source changed since the cached translation. */
    public boolean retranslateChanged = false;

    /** Trigger a resource reload automatically once new translations are ready. */
    public boolean autoReload = true;

    /** Print progress to the game log. */
    public boolean verbose = true;

    /** Namespaces that are never touched. */
    public Set<String> excludedNamespaces = new LinkedHashSet<>(List.of("minecraft", "neoforge", "c", "auto_translation"));

    /** Only handle these namespaces when non-empty. */
    public Set<String> includedNamespaces = new LinkedHashSet<>();

    // ------------------------------------------------------------------ CFPA

    /** Use the CFPAOrg community translation pack as the primary source (recommended). */
    public boolean cfpaEnabled = true;

    /** "auto" derives the pack name from the running game version, or set e.g. "1-21" / "26-1". */
    public String cfpaVersion = "auto";

    /** Release folder of the CFPA pack. */
    public String cfpaReleaseBase = "https://github.com/CFPAOrg/Minecraft-Mod-Language-Package/releases/download/autobuild/";

    /** Tried in order; an empty prefix means direct GitHub. */
    public List<String> cfpaMirrorPrefixes = new ArrayList<>(List.of("", "https://gh-proxy.com/", "https://ghfast.top/"));

    /** Optional local CFPA zip (offline use / testing). */
    public String cfpaLocalZip = "";

    /** Read only the needed mods out of the remote pack with HTTP ranges instead of downloading it. */
    public boolean cfpaPerMod = true;

    /** How often to check for a newer CFPA pack. */
    public int cfpaCheckIntervalHours = 24;

    public static Config load(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                Config cfg = GSON.fromJson(json, Config.class);
                if (cfg != null) {
                    cfg.normalize();
                    return cfg;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to read {}, using defaults", file, e);
        }
        Config cfg = new Config();
        cfg.save(file);
        return cfg;
    }

    private void normalize() {
        if (targetLanguage == null || targetLanguage.isBlank()) targetLanguage = "zh_cn";
        if (sourceLanguage == null || sourceLanguage.isBlank()) sourceLanguage = "en_us";
        if (provider == null || provider.isBlank()) provider = "openai";
        if (baseUrl == null) baseUrl = "https://api.deepseek.com/v1/chat/completions";
        if (model == null) model = "deepseek-chat";
        if (batchSize < 1) batchSize = 1;
        if (batchSize > 100) batchSize = 100;
        if (parallelism < 1) parallelism = 1;
        if (parallelism > 16) parallelism = 16;
        if (excludedNamespaces == null) excludedNamespaces = new LinkedHashSet<>();
        if (includedNamespaces == null) includedNamespaces = new LinkedHashSet<>();
        if (cfpaVersion == null || cfpaVersion.isBlank()) cfpaVersion = "auto";
        if (cfpaReleaseBase == null || cfpaReleaseBase.isBlank()) {
            cfpaReleaseBase = "https://github.com/CFPAOrg/Minecraft-Mod-Language-Package/releases/download/autobuild/";
        }
        if (cfpaMirrorPrefixes == null || cfpaMirrorPrefixes.isEmpty()) {
            cfpaMirrorPrefixes = new ArrayList<>(List.of("", "https://gh-proxy.com/", "https://ghfast.top/"));
        }
        if (cfpaLocalZip == null) cfpaLocalZip = "";
        if (cfpaCheckIntervalHours < 1) cfpaCheckIntervalHours = 24;
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Failed to write {}", file, e);
        }
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("provider=" + provider + ", model=" + model + ", target=" + targetLanguage);
        lines.add("batch=" + batchSize + ", parallelism=" + parallelism + ", autoReload=" + autoReload);
        lines.add("cfpa=" + (cfpaEnabled ? ("on (" + cfpaVersion + ")") : "off"));
        return lines;
    }
}
