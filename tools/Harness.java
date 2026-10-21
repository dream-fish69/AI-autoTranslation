import com.dsh.autotranslation.Config;
import com.dsh.autotranslation.Pipeline;
import com.dsh.autotranslation.Translator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline smoke test for the pipeline: scans a real mods folder and runs it with a mock
 * translator, so scanning, CFPA lookup, caching and pack generation can be verified
 * without the game.
 *
 * Usage: java -cp "build/classes;gson.jar;slf4j-api.jar" Harness <modsDir> <workDir> [cfpaZip]
 */
public final class Harness {

    public static void main(String[] args) throws Exception {
        Path mods = Path.of(args[0]);
        Path work = Path.of(args[1]);
        Files.createDirectories(work);

        Config config = new Config();
        config.provider = "none";
        config.verbose = true;
        config.autoReload = false;
        config.batchSize = 20;
        config.parallelism = 2;
        config.cfpaEnabled = args.length > 2;
        config.cfpaLocalZip = args.length > 2 ? args[2] : "";

        // Optional: args[3]=mcRoot args[4]=clientJar -> use the real offline dictionary
        // instead of the mock, so the no-API-key path can be exercised outside the game.
        com.dsh.autotranslation.Translator translator;
        if (args.length > 4) {
            translator = new com.dsh.autotranslation.OfflineTranslator(
                    com.dsh.autotranslation.OfflineDictionary.load(Path.of(args[3]), Path.of(args[4]), work.resolve("glossary-learned.json")));
            config.provider = "offline";
        } else {
            translator = new Translator() {
                @Override
                public String name() {
                    return "mock";
                }

                @Override
                public List<String> translate(List<String> texts) {
                    List<String> out = new ArrayList<>(texts.size());
                    for (String text : texts) {
                        out.add("【AI】" + text);
                    }
                    return out;
                }
            };
        }

        long start = System.currentTimeMillis();
        Pipeline.Result result = Pipeline.run(config, mods, work.resolve("pack"), translator, "26.3");
        System.out.printf("backend=%s namespaces=%d strings=%d cfpa=%d memory=%d ai=%d pending=%d changed=%s in %d ms%n",
                translator.name(), result.namespaces(), result.totalStrings(), result.cfpaStrings(),
                result.memoryStrings(), result.translatedNow(), result.pending(), result.changed(),
                System.currentTimeMillis() - start);

        try (var stream = Files.walk(work)) {
            stream.filter(Files::isRegularFile).sorted().forEach(p -> {
                try {
                    System.out.printf("  %-60s %,d bytes%n",
                            work.relativize(p), Files.size(p));
                } catch (Exception ignored) {
                }
            });
        }
    }
}
