package com.dsh.autotranslation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Keyless fallback via MyMemory. Free tier is heavily rate limited and the quality is
 * mediocre; it exists so the mod does something useful before the user configures an API key.
 */
public final class MyMemoryTranslator implements Translator {

    private static final String ENDPOINT = "https://api.mymemory.translated.net/get";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String targetLang;

    public MyMemoryTranslator(String targetLang) {
        this.targetLang = "zh_cn".equals(targetLang) ? "zh-CN" : targetLang.replace('_', '-');
    }

    @Override
    public String name() {
        return "mymemory";
    }

    @Override
    public List<String> translate(List<String> texts) throws Exception {
        List<String> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            String translated = translateOne(text);
            out.add(translated);
            Thread.sleep(250L);
        }
        return out;
    }

    private String translateOne(String text) {
        try {
            String url = ENDPOINT + "?q=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                    + "&langpair=" + URLEncoder.encode("en|" + targetLang, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "auto-translation/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject data = root.getAsJsonObject("responseData");
            if (data == null || !data.has("translatedText")) {
                return null;
            }
            String value = data.get("translatedText").getAsString();
            if (value == null || value.isBlank() || value.startsWith("MYMEMORY WARNING")) {
                return null;
            }
            return value.replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        } catch (Exception e) {
            return null;
        }
    }

}
