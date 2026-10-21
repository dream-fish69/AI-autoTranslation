package com.dsh.autotranslation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Any OpenAI-compatible chat-completions endpoint (DeepSeek, Moonshot, SiliconFlow,
 * Ollama, ...). Uses a JSON-object response so batch results stay aligned with inputs.
 */
public final class OpenAiTranslator implements Translator {

    private static final Gson GSON = new GsonBuilder().create();
    private static final String SYSTEM_PROMPT = """
            You are a professional translator localising Minecraft mods into Simplified Chinese.
            Rules:
            1. Translate every string naturally, as it would appear in a Chinese game UI.
            2. Keep it short: buttons, tooltips and config labels must stay compact.
            3. Never translate or reorder placeholders such as %s, %d, %1$s, %%, or tokens like ⟦0⟧.
            4. Do not translate mod names, registry ids (e.g. minecraft:stone, mod_id:item) or file names.
            5. Keep the original capitalisation style where it matters (e.g. key names).
            6. If a string is only a proper noun or a symbol, return it unchanged.
            Reply with a single JSON object whose keys are the numeric ids of the input and whose
            values are the Chinese translations. Do not add explanations or markdown fences.
            """;

    private final Config config;
    private final HttpClient http;

    public OpenAiTranslator(Config config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "openai:" + config.model;
    }

    @Override
    public List<String> translate(List<String> texts) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", config.model);
        payload.addProperty("temperature", 0.0);
        payload.addProperty("stream", false);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        payload.add("response_format", responseFormat);

        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM_PROMPT + (config.extraPrompt.isBlank() ? "" : "\n" + config.extraPrompt)));

        JsonObject input = new JsonObject();
        for (int i = 0; i < texts.size(); i++) {
            input.addProperty(String.valueOf(i), texts.get(i));
        }
        messages.add(message("user", "Translate the following strings from " + config.sourceLanguage
                + " to " + config.targetLanguage + ". Return a JSON object with the same numeric keys.\n"
                + GSON.toJson(input)));
        payload.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status / 100 != 2) {
            String body = truncate(response.body());
            if (status == 400 || status == 401 || status == 402 || status == 403) {
                // Bad key / no balance / wrong endpoint: retrying the same request cannot help.
                throw new FatalApiException(status, body);
            }
            throw new IOException("HTTP " + status + ": " + body);
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
        JsonObject translated = parseObject(content);

        List<String> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            JsonElement element = translated.get(String.valueOf(i));
            out.add(element != null && element.isJsonPrimitive() ? element.getAsString() : null);
        }
        return out;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static JsonObject parseObject(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int fence = text.lastIndexOf("```");
            if (firstLine > 0 && fence > firstLine) {
                text = text.substring(firstLine + 1, fence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 400 ? value.substring(0, 400) + "..." : value;
    }
}
