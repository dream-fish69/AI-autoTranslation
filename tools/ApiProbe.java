import com.dsh.autotranslation.Config;
import com.dsh.autotranslation.OpenAiTranslator;

import java.util.List;

/**
 * Exercises OpenAiTranslator against an arbitrary endpoint (e.g. a local stub server),
 * without starting Minecraft.
 *
 * Usage: java -cp "build/classes;gson.jar;slf4j-api.jar" ApiProbe <url> <apiKey>
 */
public final class ApiProbe {

    public static void main(String[] args) {
        Config config = new Config();
        config.baseUrl = args[0];
        config.apiKey = args[1];
        config.model = "deepseek-chat";

        OpenAiTranslator translator = new OpenAiTranslator(config);
        try {
            List<String> out = translator.translate(List.of("Settings", "Show Waypoints"));
            System.out.println("OK " + out);
        } catch (Throwable t) {
            System.out.println("THREW " + t.getClass().getName());
            System.out.println("MESSAGE " + t.getMessage());
        }
    }
}
