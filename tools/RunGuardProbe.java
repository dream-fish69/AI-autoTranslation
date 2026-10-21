import com.dsh.autotranslation.Config;
import com.dsh.autotranslation.RunGuard;
import com.dsh.autotranslation.RunState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/** Exercises the "nothing to do, stay dormant" gate without starting Minecraft. */
public final class RunGuardProbe {

    public static void main(String[] args) throws Exception {
        Path mods = Path.of(args[0]);
        Path work = Path.of(args[1]);
        Files.createDirectories(work.resolve("pack"));

        Config config = new Config();
        config.cfpaEnabled = false;

        System.out.println("no record          -> skip=" + RunGuard.canSkip(config, mods, work.resolve("pack"), work));
        RunGuard.remember(config, mods, work.resolve("pack"), work, "test");
        System.out.println("after remember     -> skip=" + RunGuard.canSkip(config, mods, work.resolve("pack"), work));

        Path extra = mods.resolve("zz-extra-test.jar");
        Files.copy(mods.resolve("partial-test.jar"), extra, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("new jar added      -> skip=" + RunGuard.canSkip(config, mods, work.resolve("pack"), work));
        Files.deleteIfExists(extra);
        RunGuard.remember(config, mods, work.resolve("pack"), work, "test");
        System.out.println("jar removed+remember -> skip=" + RunGuard.canSkip(config, mods, work.resolve("pack"), work));

        config.apiKey = "sk-changed";
        System.out.println("api key changed    -> skip=" + RunGuard.canSkip(config, mods, work.resolve("pack"), work));

        config.apiKey = "";
        config.cfpaEnabled = true;
        System.out.println("cfpa due (no state)-> skip=" + RunGuard.canSkip(config, mods, work.resolve("pack"), work));

        RunState.SUMMARIES.put("xaeroworldmap", "308 条 · CFPA 288 · AI 20");
        RunState.SUMMARIES.put("sodium", "51 条 · CFPA 0 · AI 51");
        RunGuard.remember(config, mods, work.resolve("pack"), work, "demo");
        RunGuard.Record record = RunGuard.loadRecord(work);
        System.out.println("persisted summaries=" + record.namespaces());
        RunState.restore(record.namespaces(), record.at());
        System.out.println("status line        -> " + RunState.text());
    }
}
