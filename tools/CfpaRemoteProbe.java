import com.dsh.autotranslation.CfpaClient;
import com.dsh.autotranslation.CfpaSource;
import com.dsh.autotranslation.Config;

import java.nio.file.Path;
import java.util.List;

/** Exercises the remote (range-based) CFPA source against a local range server. */
public final class CfpaRemoteProbe {

    public static void main(String[] args) throws Exception {
        Config config = new Config();
        config.cfpaLocalZip = "";
        config.cfpaReleaseBase = args[0];
        config.cfpaMirrorPrefixes = List.of("");
        config.cfpaVersion = args.length > 1 ? args[1] : "1-21";

        Path dir = Path.of(args.length > 2 ? args[2] : "build/cfpa-probe");
        try (CfpaSource source = CfpaClient.open(config, dir, "26.3")) {
            if (source == null) {
                System.out.println("no CFPA source");
                return;
            }
            System.out.printf("version=%s namespaces=%d%n", source.version(), source.namespaceCount());
            for (String ns : new String[]{"xaeroworldmap", "jei", "sodium"}) {
                System.out.printf("  %-16s %d entries%n", ns, source.translations(ns).size());
            }
        }
    }
}
