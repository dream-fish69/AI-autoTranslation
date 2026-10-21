package com.dsh.autotranslation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * Scan → CFPA (quality) → AI/offline fallback → write pack.
 *
 * <p>Priority per key: the mod's own zh_cn &gt; CFPA &gt; cached/AI &gt; untouched.
 * When {@code fillMissingKeys} is on, namespaces that already ship a partial translation are
 * topped up instead of being skipped.</p>
 */
public final class Pipeline {

    public record Result(int namespaces, int totalStrings, int cfpaStrings, int memoryStrings,
                         int translatedNow, int pending, boolean retryable, boolean changed) {
    }

    private Pipeline() {
    }

    public static Result run(Config config, Path modsDir, Path packDir, Translator override, String gameVersion)
            throws Exception {
        return run(config, modsDir, packDir, override, gameVersion, null);
    }

    /**
     * @param only optional restriction to a few namespaces (used by the "retranslate this mod"
     *             button); empty/null means "everything".
     */
    public static Result run(Config config, Path modsDir, Path packDir, Translator override,
                             String gameVersion, Set<String> only) throws Exception {
        Predicate<String> wanted = id -> !config.excludedNamespaces.contains(id)
                && (config.includedNamespaces.isEmpty() || config.includedNamespaces.contains(id))
                && (only == null || only.isEmpty() || only.contains(id));

        Map<String, ModsScanner.Namespace> found =
                new ModsScanner(config.sourceLanguage, config.targetLanguage, wanted).scan(modsDir);
        Path workDir = packDir.getParent();
        Path cacheDir = workDir.resolve("cache");
        Translator translator = override != null ? override : Translators.create(config, workDir);
        CfpaSource cfpa = CfpaClient.open(config, workDir.resolve("cfpa"), gameVersion);
        TranslationMemory memory = config.translationMemory
                ? TranslationMemory.load(workDir.resolve("memory.json"))
                : TranslationMemory.disabled();
        if (cfpa != null) {
            Logs.LOG.info("CFPA: pack {} indexed ({} namespaces)", cfpa.version(), cfpa.namespaceCount());
        }

        Map<String, Map<String, String>> packEntries = new TreeMap<>();
        Map<String, String> learnedTerms = new LinkedHashMap<>();
        int total = 0;
        int cfpaStrings = 0;
        int memoryStrings = 0;
        int translatedNow = 0;
        int pending = 0;
        int done = 0;

        RunState.reset();
        RunState.total = found.size();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, config.parallelism), runnable -> {
            Thread thread = new Thread(runnable, "auto-translation");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (ModsScanner.Namespace namespace : found.values()) {
                RunState.current = namespace.id;
                RunState.done = done++;
                if (namespace.source.isEmpty()) {
                    continue;
                }

                boolean officialMatters = namespace.hasTarget && !config.overwriteExistingTranslations;
                if (officialMatters && !config.fillMissingKeys) {
                    if (config.verbose) {
                        Logs.LOG.info("Skip {}: already ships {}", namespace.id, config.targetLanguage);
                    }
                    RunState.summary(namespace.id, namespace.target.size(), 0, 0, 0, 0, true);
                    continue;
                }
                Map<String, String> official = officialMatters ? namespace.target : Map.of();

                // 1) community translations first, never over an official string
                Map<String, String> primary = new LinkedHashMap<>();
                if (cfpa != null) {
                    for (Map.Entry<String, String> entry : cfpa.translations(namespace.id).entrySet()) {
                        String key = entry.getKey();
                        String english = namespace.source.get(key);
                        String chinese = entry.getValue();
                        if (english == null || chinese == null || chinese.isBlank()
                                || chinese.equals(english) || official.containsKey(key)) {
                            continue;
                        }
                        if (!PlaceholderGuard.compatible(english, chinese)) {
                            Logs.LOG.debug("CFPA: dropping {} ({}): placeholder mismatch", key, namespace.id);
                            continue;
                        }
                        primary.put(key, chinese);
                    }
                }
                if (config.learnGlossary) {
                    for (Map.Entry<String, String> entry : primary.entrySet()) {
                        if (GlossaryLearner.isLearnable(namespace.source.get(entry.getKey()), entry.getValue())) {
                            learnedTerms.putIfAbsent(namespace.source.get(entry.getKey()), entry.getValue());
                        }
                    }
                }

                // 2) cross-mod translation memory for identical English strings
                Map<String, String> remembered = new LinkedHashMap<>();
                if (config.translationMemory) {
                    for (String key : namespace.source.keySet()) {
                        if (official.containsKey(key) || primary.containsKey(key)) {
                            continue;
                        }
                        String hit = memory.lookup(namespace.source.get(key));
                        if (hit != null) {
                            remembered.put(key, hit);
                        }
                    }
                    primary.forEach(memory::remember);
                }

                // 2) cached / AI translations for whatever CFPA and the mod do not cover
                TranslationCache cache = TranslationCache.load(cacheDir.resolve(namespace.id + ".json"));
                cache.entries = Collections.synchronizedMap(new LinkedHashMap<>(cache.entries));
                cache.namespace = namespace.id;

                // Entries produced by the built-in dictionary are low confidence: when a real API
                // backend becomes available (player just entered a key), translate them again.
                boolean aiBackend = translator.retryable();
                boolean upgradeFromOffline = aiBackend && "offline".equals(cache.model);

                String hash = Json.hashEntries(namespace.source);
                boolean sourceChanged = !hash.equals(cache.sourceHash);
                cache.sourceHash = hash;
                cache.entries.keySet().retainAll(namespace.source.keySet());

                List<String> missing = new ArrayList<>();
                for (String key : namespace.source.keySet()) {
                    if (official.containsKey(key) || primary.containsKey(key)
                            || remembered.containsKey(key)) {
                        continue;
                    }
                    if (!cache.entries.containsKey(key) || upgradeFromOffline
                            || (sourceChanged && config.retranslateChanged)) {
                        missing.add(key);
                    }
                }
                int appliedNow = 0;
                if (!missing.isEmpty()) {
                    appliedNow = translateAll(namespace, missing, cache, translator, config, pool);
                    translatedNow += appliedNow;
                    pending += Math.max(0, missing.size() - appliedNow);
                }
                // Only claim the cache for a better backend when it actually produced something,
                // otherwise a failed API run would permanently mask the offline entries.
                if (aiBackend && (!upgradeFromOffline || appliedNow > 0)) {
                    cache.model = translator.name();
                } else if (cache.model == null || cache.model.isBlank()) {
                    cache.model = translator.name();
                }
                cache.save(cacheDir.resolve(namespace.id + ".json"));
                if (config.translationMemory) {
                    for (String key : missing) {
                        String value = cache.entries.get(key);
                        if (value != null) {
                            memory.remember(namespace.source.get(key), value);
                        }
                    }
                }

                Map<String, String> output = new LinkedHashMap<>(primary);
                output.putAll(remembered);
                for (Map.Entry<String, String> entry : namespace.source.entrySet()) {
                    String key = entry.getKey();
                    if (output.containsKey(key) || official.containsKey(key)) {
                        continue;
                    }
                    String value = cache.entries.get(key);
                    if (value != null && !value.isBlank() && !value.equals(entry.getValue())) {
                        output.put(key, value);
                    }
                }
                if (!output.isEmpty()) {
                    packEntries.put(namespace.id, output);
                    total += output.size();
                    cfpaStrings += primary.size();
                    memoryStrings += remembered.size();
                }
                if (config.verbose) {
                    Logs.LOG.info("{}: {} new strings (official {}, cfpa {}, memory {}, ai {}), {} missing",
                            namespace.id, output.size(), official.size(), primary.size(),
                            remembered.size(), output.size() - primary.size() - remembered.size(), missing.size());
                }
                RunState.summary(namespace.id, official.size(), primary.size(), remembered.size(),
                        Math.max(0, output.size() - primary.size() - remembered.size()), missing.size(), false);
            }
        } finally {
            pool.shutdownNow();
            if (cfpa != null) {
                cfpa.close();
            }
        }

        if (config.learnGlossary && !learnedTerms.isEmpty()) {
            GlossaryLearner.merge(workDir.resolve("glossary-learned.json"), learnedTerms);
        }
        RunState.running = false;
        if (config.translationMemory) {
            memory.save();
        }

        boolean changed = PackWriter.write(packDir, packEntries, config.targetLanguage);
        return new Result(packEntries.size(), total, cfpaStrings, memoryStrings, translatedNow, pending,
                translator.retryable(), changed);
    }

    private static int translateAll(ModsScanner.Namespace namespace, List<String> keys,
                                    TranslationCache cache, Translator translator, Config config,
                                    ExecutorService pool) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += config.batchSize) {
            batches.add(keys.subList(i, Math.min(keys.size(), i + config.batchSize)));
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (List<String> batch : batches) {
            futures.add(pool.submit(() -> translateBatch(namespace, batch, cache, translator, config)));
        }
        int applied = 0;
        for (Future<Integer> future : futures) {
            try {
                applied += future.get();
            } catch (Exception e) {
                Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null
                        ? e.getCause() : e;
                if (cause instanceof FatalApiException fatal) {
                    // Bad key / no balance: abort the whole run instead of logging one stack per batch.
                    throw fatal;
                }
                Logs.LOG.warn("Translation batch for {} failed: {}", namespace.id, cause.toString());
            }
        }
        return applied;
    }

    private static int translateBatch(ModsScanner.Namespace namespace, List<String> keys,
                                      TranslationCache cache, Translator translator, Config config) {
        if (keys.isEmpty()) {
            return 0;
        }
        List<PlaceholderGuard.Guarded> guarded = new ArrayList<>(keys.size());
        List<String> texts = new ArrayList<>(keys.size());
        for (String key : keys) {
            PlaceholderGuard.Guarded g = PlaceholderGuard.protect(namespace.source.get(key));
            guarded.add(g);
            texts.add(g.text());
        }
        try {
            List<String> result = translator.translate(texts);
            int applied = 0;
            for (int i = 0; i < keys.size(); i++) {
                String value = i < result.size() ? result.get(i) : null;
                if (value == null || value.isBlank()) {
                    continue;
                }
                String restored = PlaceholderGuard.restore(value.trim(), guarded.get(i));
                if (restored == null || restored.isBlank()) {
                    continue;
                }
                cache.entries.put(keys.get(i), restored);
                applied++;
            }
            return applied;
        } catch (FatalApiException e) {
            throw e;
        } catch (java.io.IOException e) {
            // Network / HTTP level problem: splitting the batch will not help, fail fast so the
            // outer retry loop can try the whole run again after a backoff.
            throw new RuntimeException("translation backend I/O error: " + e.getMessage(), e);
        } catch (Exception e) {
            if (keys.size() == 1) {
                Logs.LOG.debug("Failed to translate '{}' ({}): {}",
                        keys.get(0), namespace.id, e.toString());
                return 0;
            }
            int middle = keys.size() / 2;
            return translateBatch(namespace, keys.subList(0, middle), cache, translator, config)
                    + translateBatch(namespace, keys.subList(middle, keys.size()), cache, translator, config);
        }
    }
}
