import com.dsh.autotranslation.OfflineDictionary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Offline dictionary probe (no game needed).
 *
 * Usage: java -cp "build/classes;gson.jar;slf4j-api.jar" OfflineProbe <.minecraft> <client jar> [en_us.json]
 */
public final class OfflineProbe {

    public static void main(String[] args) throws Exception {
        Path mcRoot = Path.of(args[0]);
        Path clientJar = Path.of(args[1]);

        long start = System.currentTimeMillis();
        OfflineDictionary dictionary = OfflineDictionary.load(mcRoot, clientJar, null);
        System.out.printf("dictionary: %d phrases in %d ms%n", dictionary.size(),
                System.currentTimeMillis() - start);

        List<String> samples = List.of(
                "Camera", "your character", "Camera is attached to your character",
                "The camera follows your character", "Show your coordinates",
                "This action cannot be undone", "Convert Map Distance to KM at",
                "Waypoints", "Show Waypoints", "Waypoint Manager", "Open Settings",
                "Settings", "Chunk Builder", "Are you sure?", "Render Distance",
                "%s cannot be empty", "minecraft:stone");

        System.out.println("\n-- samples --");
        for (String sample : samples) {
            System.out.printf("%-42s -> %s%n", sample, String.valueOf(dictionary.translate(sample)));
        }

        if (args.length > 2) {
            JsonObject root = JsonParser.parseString(Files.readString(Path.of(args[2]))).getAsJsonObject();
            int total = 0;
            int hit = 0;
            List<String> examples = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                total++;
                String translated = dictionary.translate(entry.getValue().getAsString());
                if (translated != null) {
                    hit++;
                    if (examples.size() < 12) {
                        examples.add(entry.getValue().getAsString() + "  ->  " + translated);
                    }
                }
            }
            System.out.printf("%n-- real strings: %d/%d covered (%.0f%%) --%n",
                    hit, total, total == 0 ? 0 : 100.0 * hit / total);
            examples.forEach(example -> System.out.println("  " + example));
        }
    }
}
