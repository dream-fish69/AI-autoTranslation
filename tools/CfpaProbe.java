import com.dsh.autotranslation.CfpaClient;
import com.dsh.autotranslation.Config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Debug helper: resolves/downloads the CFPA pack and reports coverage.
 *
 * Usage: java -cp "build/classes;gson.jar;slf4j-api.jar" CfpaProbe <workDir> [gameVersion]
 */
public final class CfpaProbe {

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        String gameVersion = args.length > 1 ? args[1] : "26.3";
        Files.createDirectories(work);

        Config config = new Config();
        long start = System.currentTimeMillis();
        try (CfpaClient client = CfpaClient.open(config, work.resolve("cfpa"), gameVersion)) {
            if (client == null) {
                System.out.println("CFPA unavailable (offline/blocked?)");
                return;
            }
            System.out.printf("version=%s namespaces=%d in %d ms%n",
                    client.version(), client.namespaceCount(), System.currentTimeMillis() - start);
            for (String ns : new String[]{"xaeroworldmap", "sodium", "jei"}) {
                System.out.printf("  %-16s %d entries%n", ns, client.translations(ns).size());
            }
        }
    }
}
