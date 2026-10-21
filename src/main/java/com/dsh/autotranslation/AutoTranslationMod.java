package com.dsh.autotranslation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientResourceLoadFinishedEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Entry point.
 *
 * <p>Strategy: never touch {@code Language}/{@code LanguageManager}. Instead we generate a
 * normal resource pack with {@code assets/<namespace>/lang/zh_cn.json} files and register it
 * as a required built-in pack. Minecraft merges it through the regular pack pipeline, which
 * means the mod keeps working when Mojang refactors the language internals.</p>
 *
 * <p>Lifecycle: one scan per launch, up to {@value #MAX_ATTEMPTS} attempts on failure, then
 * dormant until the next launch — unless the player presses the button in the config screen,
 * which is the only manual escape hatch.</p>
 */
@Mod(value = AutoTranslationMod.MOD_ID, dist = Dist.CLIENT)
public final class AutoTranslationMod {

    public static final String MOD_ID = "auto_translation";
    public static final Logger LOG = Logs.LOG;

    /** Total attempts for one run (startup or manual). */
    private static final int MAX_ATTEMPTS = 3;
    /** Backoff between attempts: 10 s, then 20 s. */
    private static final long RETRY_DELAY_MS = 10_000L;

    /** One-shot lifecycle per game session: {@code WAITING -> RUNNING -> DONE|FAILED}. */
    private enum Stage {
        WAITING, RUNNING, DONE, FAILED
    }

    private static volatile AutoTranslationMod instance;

    private final Config config;
    private final Path workDir;
    private final Path packDir;
    private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.WAITING);
    private final AtomicInteger attempt = new AtomicInteger();

    private volatile Pipeline.Result lastResult;
    private volatile int totalApplied;
    private volatile boolean changedAny;
    private volatile String lastError = "";
    private volatile java.util.Set<String> onlyNamespaces;

    public AutoTranslationMod(IEventBus modBus, ModContainer container) {
        instance = this;
        this.workDir = FMLPaths.CONFIGDIR.get().resolve("auto-translation");
        this.packDir = workDir.resolve("pack");
        this.config = Config.load(workDir.resolve("config.json"));
        try {
            Files.createDirectories(packDir.resolve("assets"));
            Files.createDirectories(workDir.resolve("cache"));
        } catch (IOException e) {
            LOG.warn("Failed to create work directories under {}", workDir, e);
        }

        modBus.addListener(this::onAddPackFinders);
        NeoForge.EVENT_BUS.addListener(this::onClientResourcesLoaded);
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (modContainer, parentScreen) -> new AutoTranslationConfigScreen(parentScreen));

        LOG.info("AutoTranslation ready ({}); work dir = {}", String.join("; ", config.describe()), workDir);
        if ("openai".equalsIgnoreCase(config.provider) && (config.apiKey == null || config.apiKey.isBlank())) {
            LOG.info("No API key configured: using the built-in offline dictionary "
                    + "(vanilla terms + bundled glossary). Enter a key in the mod config screen to upgrade.");
        }
    }

    // ------------------------------------------------------------------ UI hooks

    /** Called by the config screen button. Returns false while a run is already in progress. */
    public static boolean triggerManual() {
        AutoTranslationMod mod = instance;
        return mod != null && mod.startRun(true);
    }

    /** Re-run the pipeline for a single namespace (status screen "重译" button). */
    public static boolean triggerManualFor(String namespace) {
        AutoTranslationMod mod = instance;
        if (mod == null) {
            return false;
        }
        mod.onlyNamespaces = java.util.Set.of(namespace);
        return mod.startRun(true);
    }

    public static boolean isRunning() {
        AutoTranslationMod mod = instance;
        return mod != null && mod.stage.get() == Stage.RUNNING;
    }

    public static String apiEndpoint() {
        AutoTranslationMod mod = instance;
        return mod == null ? "" : mod.config.baseUrl;
    }

    public static String apiKeyValue() {
        AutoTranslationMod mod = instance;
        return mod == null ? "" : mod.config.apiKey;
    }

    public static String apiModel() {
        AutoTranslationMod mod = instance;
        return mod == null ? "" : mod.config.model;
    }

    /**
     * Cheap sanity check between the key format and the endpoint, e.g. a SiliconFlow key pasted
     * into the DeepSeek URL always ends in 401. Empty string means "looks consistent".
     */
    public static String keyWarning(String endpoint, String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String url = endpoint == null ? "" : endpoint.toLowerCase(java.util.Locale.ROOT);
        String trimmed = key.trim();
        boolean deepSeekEndpoint = url.contains("deepseek");
        boolean deepSeekShape = trimmed.matches("sk-[0-9a-fA-F]{32}");
        if (deepSeekEndpoint && !deepSeekShape) {
            return "Key 格式不像 DeepSeek（sk- + 32 位十六进制），请核对接口地址";
        }
        if (!deepSeekEndpoint && deepSeekShape) {
            return "这串 Key 像 DeepSeek 的，但地址不是 DeepSeek，请改回官方地址";
        }
        return "";
    }

    /** Persists endpoint/key/model typed in the config screen. Returns false on invalid input. */
    public static boolean saveApiSettings(String endpoint, String key, String model) {
        AutoTranslationMod mod = instance;
        return mod != null && mod.applyApiSettings(endpoint, key, model);
    }

    private synchronized boolean applyApiSettings(String endpoint, String key, String model) {
        String url = endpoint == null ? "" : endpoint.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return false;
        }
        config.provider = "openai";
        config.baseUrl = url;
        config.apiKey = key == null ? "" : key.trim();
        config.model = model == null || model.isBlank() ? "deepseek-chat" : model.trim();
        config.save(workDir.resolve("config.json"));
        LOG.info("AutoTranslation: API settings saved (endpoint={}, model={}, key={})",
                config.baseUrl, config.model, maskKey(config.apiKey));
        return true;
    }

    private static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "<empty>";
        }
        return key.length() <= 8 ? "****" : key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    public static String statusText() {
        AutoTranslationMod mod = instance;
        if (mod == null) {
            return "模组尚未初始化";
        }
        if (!mod.config.enabled) {
            return "已在配置中关闭（enabled = false）";
        }
        return switch (mod.stage.get()) {
            case WAITING -> "等待首次资源加载完成，随后自动扫描一次";
            case RUNNING -> "正在扫描并翻译（第 " + Math.max(1, mod.attempt.get()) + "/" + MAX_ATTEMPTS + " 次尝试）…";
            case DONE -> mod.summary();
            case FAILED -> "翻译失败" + (mod.lastError.isBlank() ? "" : "（" + mod.lastError + "）")
                    + "，可点下方按钮重试";
        };
    }

    private String summary() {
        Pipeline.Result result = lastResult;
        if (result == null) {
            return "已完成";
        }
        return "上次结果：" + result.totalStrings() + " 条（CFPA " + result.cfpaStrings()
                + " + 记忆 " + result.memoryStrings() + " + " + backendLabel() + " "
                + result.translatedNow() + "），" + result.namespaces() + " 个模组";
    }

    private String backendLabel() {
        if (config.apiKey == null || config.apiKey.isBlank()) {
            return "内置词典";
        }
        return "AI";
    }

    // ------------------------------------------------------------------ pipeline lifecycle

    /**
     * Registers the generated folder as a required built-in resource pack, highest priority.
     *
     * <p>This fires on every resource reload and must keep firing: otherwise a manual F3+T would
     * drop the pack and the UI would fall back to English. It only builds pack metadata and never
     * touches the filesystem, so it does not count as "starting" the translation pipeline.</p>
     */
    private void onAddPackFinders(AddPackFindersEvent event) {
        if (!config.enabled) {
            return;
        }
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        try {
            PackLocationInfo location = new PackLocationInfo(
                    MOD_ID,
                    Component.literal("AutoTranslation (generated)"),
                    PackSource.BUILT_IN,
                    Optional.empty());
            Pack.Metadata metadata = new Pack.Metadata(
                    Component.literal("AutoTranslation generated translations"),
                    PackCompatibility.COMPATIBLE,
                    FeatureFlagSet.of(),
                    List.of());
            Pack pack = new Pack(
                    location,
                    new PathPackResources.PathResourcesSupplier(packDir),
                    metadata,
                    new PackSelectionConfig(true, Pack.Position.TOP, true));

            RepositorySource source = new RepositorySource() {
                @Override
                public void loadPacks(Consumer<Pack> onLoad) {
                    onLoad.accept(pack);
                }
            };
            event.addRepositorySource(source);
        } catch (Throwable t) {
            LOG.error("Failed to register the generated translation pack", t);
        }
    }

    /** Automatic run: exactly once per launch, after the initial resource reload. */
    private void onClientResourcesLoaded(ClientResourceLoadFinishedEvent event) {
        if (!config.enabled) {
            return;
        }
        if (stage.get() != Stage.WAITING) {
            LOG.debug("AutoTranslation: ignoring resource-load event, pipeline is {}", stage.get());
            return;
        }
        if (RunGuard.canSkip(config, FMLPaths.MODSDIR.get(), packDir, workDir)) {
            // Same mods, same config, same pack, CFPA not due: nothing to do this launch.
            stage.set(Stage.DONE);
            RunGuard.Record record = RunGuard.loadRecord(workDir);
            if (record != null) {
                RunState.restore(record.namespaces(), record.at());
            }
            LOG.info("AutoTranslation: nothing changed since the last successful run; staying dormant "
                    + "(manual runs still work from the config screen)");
            return;
        }
        if (!startRun(false)) {
            LOG.debug("AutoTranslation: startup run already started");
        }
    }

    private synchronized boolean startRun(boolean manual) {
        if (stage.get() == Stage.RUNNING) {
            return false;
        }
        stage.set(Stage.RUNNING);
        attempt.set(0);
        totalApplied = 0;
        changedAny = false;
        lastResult = null;
        lastError = "";
        java.util.Set<String> only = this.onlyNamespaces;
        this.onlyNamespaces = null;
        RunState.running = true;

        LOG.info("AutoTranslation: pipeline started ({}{})", manual ? "manual request" : "startup",
                only == null ? "" : ", only " + only);
        Thread worker = new Thread(() -> runPipelineWithRetries(only), "auto-translation-pipeline");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private void runPipelineWithRetries(java.util.Set<String> only) {
        long start = System.currentTimeMillis();
        String gameVersion = gameVersion();
        boolean success = false;

        for (int i = 1; i <= MAX_ATTEMPTS; i++) {
            attempt.set(i);
            try {
                Pipeline.Result result = Pipeline.run(config, FMLPaths.MODSDIR.get(), packDir, null, gameVersion, only);
                lastResult = result;
                totalApplied += result.translatedNow();
                changedAny |= result.changed();

                if (result.pending() == 0 || !result.retryable()) {
                    // Nothing left to translate, or a local/deterministic backend: retrying cannot help.
                    success = true;
                    break;
                }
                LOG.warn("AutoTranslation: attempt {}/{} finished with {} strings still untranslated",
                        i, MAX_ATTEMPTS, result.pending());
            } catch (FatalApiException fatal) {
                // 401/402/403/400: the key, balance or endpoint is wrong. Retrying is pointless.
                lastError = fatal.getMessage();
                LOG.error("AutoTranslation: API rejected the request, stopping: {}", lastError);
                break;
            } catch (Throwable t) {
                lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                LOG.warn("AutoTranslation: attempt {}/{} failed: {}", i, MAX_ATTEMPTS, t.toString());
                LOG.debug("pipeline failure", t);
            }

            if (i < MAX_ATTEMPTS) {
                long delay = RETRY_DELAY_MS * i;
                LOG.info("AutoTranslation: retrying in {}s", delay / 1000);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        finish(success, System.currentTimeMillis() - start);
    }

    private void finish(boolean success, long millis) {
        stage.set(success ? Stage.DONE : Stage.FAILED);
        RunState.running = false;
        if (success) {
            RunState.lastRunAt = System.currentTimeMillis();
            // Remember what we translated from, so the next launch can stay dormant.
            RunGuard.remember(config, FMLPaths.MODSDIR.get(), packDir, workDir, summary());
        }
        LOG.info("AutoTranslation {} after {} ms ({} attempt(s)); dormant until the next launch or a manual reload",
                success ? "finished" : "gave up", millis, attempt.get());

        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (changedAny && config.autoReload) {
                minecraft.reloadResourcePacks();
            }
            notifyPlayer(minecraft);
        });
    }

    private static String gameVersion() {
        try {
            return net.minecraft.SharedConstants.getCurrentVersion().name();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replace('\n', ' ').trim();
        return single.length() <= 120 ? single : single.substring(0, 120) + "…";
    }

    private void notifyPlayer(Minecraft minecraft) {
        try {
            if (minecraft.gui == null) {
                return;
            }
            Pipeline.Result result = lastResult;
            Component title = Component.literal("自动汉化 / AutoTranslation");
            Component body;
            if (result == null) {
                body = Component.literal(lastError.isBlank()
                        ? "翻译失败，可在模组配置里点「仅重新扫描」重试"
                        : "翻译失败：" + shorten(lastError));
            } else if (totalApplied > 0) {
                body = Component.literal("新翻译 " + totalApplied + " 条（CFPA " + result.cfpaStrings()
                        + " + 记忆 " + result.memoryStrings() + " + AI " + result.translatedNow()
                        + "），共 " + result.totalStrings() + " 条，正在重载语言");
            } else {
                body = Component.literal("已加载 " + result.totalStrings() + " 条（CFPA "
                        + result.cfpaStrings() + "），" + result.namespaces() + " 个模组");
            }
            SystemToast.add(minecraft.gui.toastManager(), new SystemToast.SystemToastId(), title, body);
        } catch (Throwable t) {
            LOG.debug("Could not show toast", t);
        }
    }
}
