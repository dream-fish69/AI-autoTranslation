package com.dsh.autotranslation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Protects printf placeholders, colour codes and newlines so a translator cannot
 * rename {@code %s} or eat {@code §a}.
 */
public final class PlaceholderGuard {

    private static final Pattern TOKENS = Pattern.compile(
            "%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[tT]?[a-zA-Z%]|§[0-9a-fk-orA-FK-OR]");
    private static final char OPEN = '⟦';
    private static final char CLOSE = '⟧';

    public record Guarded(String text, List<String> tokens) {
    }

    private PlaceholderGuard() {
    }

    public static Guarded protect(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder out = new StringBuilder(input.length());
        Matcher m = TOKENS.matcher(input);
        int last = 0;
        while (m.find()) {
            out.append(input, last, m.start());
            out.append(OPEN).append(tokens.size()).append(CLOSE);
            tokens.add(m.group());
            last = m.end();
        }
        out.append(input, last, input.length());
        return new Guarded(out.toString(), tokens);
    }

    /**
     * Restores the protected tokens.
     *
     * @return the restored string, or {@code null} when the translator dropped/renamed a token.
     */
    public static String restore(String translated, Guarded guarded) {
        if (guarded.tokens().isEmpty()) {
            return translated;
        }
        String out = translated;
        for (int i = 0; i < guarded.tokens().size(); i++) {
            String token = OPEN + String.valueOf(i) + CLOSE;
            if (!out.contains(token)) {
                // Some models normalise the brackets; try a couple of tolerated variants.
                String alt = "[" + i + "]";
                if (out.contains(alt)) {
                    out = out.replace(alt, guarded.tokens().get(i));
                    continue;
                }
                return null;
            }
            out = out.replace(token, guarded.tokens().get(i));
        }
        return out;
    }

    /** True when every protected token of {@code source} survives in {@code translated}. */
    public static boolean compatible(String source, String translated) {
        if (translated == null) {
            return false;
        }
        Guarded guarded = protect(source);
        if (guarded.tokens().isEmpty()) {
            return true;
        }
        Map<String, Integer> required = new LinkedHashMap<>();
        for (String token : guarded.tokens()) {
            required.merge(token, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            if (count(translated, entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
