package com.dsh.autotranslation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/** Chooses the translation backend from the config. */
public final class Translators {

    private Translators() {
    }

    public static Translator create(Config config, Path workDir) {
        String provider = config.provider == null ? "" : config.provider.trim().toLowerCase(java.util.Locale.ROOT);
        switch (provider) {
            case "none" -> {
                Logs.LOG.info("Translation provider is 'none': only cached translations will be used.");
                return new NoopTranslator();
            }
            case "mymemory" -> {
                return new MyMemoryTranslator(config.targetLanguage);
            }
            case "offline" -> {
                return offline(workDir);
            }
            case "openai", "deepseek", "llm", "" -> {
                if (isBlank(config.apiKey) || isBlank(config.baseUrl)) {
                    Logs.LOG.info("No API endpoint/key configured: falling back to the built-in offline dictionary");
                    return offline(workDir);
                }
                return new OpenAiTranslator(config);
            }
            default -> {
                Logs.LOG.warn("Unknown provider '{}', falling back to the built-in offline dictionary", config.provider);
                return offline(workDir);
            }
        }
    }

    private static Translator offline(Path workDir) {
        Path learned = workDir == null ? null : workDir.resolve("glossary-learned.json");
        return new OfflineTranslator(OfflineDictionary.load(GamePaths.mcRoot(), GamePaths.clientJar(), learned));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Locates the files the offline dictionary reads. Only touched on the client. */
    static final class GamePaths {

        private GamePaths() {
        }

        static Path mcRoot() {
            try {
                Path gameDir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
                if (Files.isDirectory(gameDir.resolve("assets").resolve("indexes"))) {
                    return gameDir;
                }
                Path parent = gameDir.getParent();
                if (parent != null && Files.isDirectory(parent.resolve("assets").resolve("indexes"))) {
                    return parent;
                }
                return parent != null && parent.getParent() != null ? parent.getParent() : gameDir;
            } catch (Throwable t) {
                Logs.LOG.debug("Offline dictionary: cannot locate .minecraft", t);
                return null;
            }
        }

        static Path clientJar() {
            try {
                var source = net.minecraft.client.Minecraft.class.getProtectionDomain().getCodeSource();
                return source == null ? null : Path.of(source.getLocation().toURI());
            } catch (Throwable t) {
                Logs.LOG.debug("Offline dictionary: cannot locate the client jar", t);
                return null;
            }
        }
    }

    private static final class NoopTranslator implements Translator {
        @Override
        public String name() {
            return "none";
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public boolean retryable() {
            return false;
        }

        @Override
        public List<String> translate(List<String> texts) {
            return Collections.nCopies(texts.size(), null);
        }
    }
}
