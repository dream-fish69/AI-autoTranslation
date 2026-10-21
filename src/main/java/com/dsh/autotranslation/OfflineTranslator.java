package com.dsh.autotranslation;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in offline translator: no API key, no network, no download. Uses
 * {@link OfflineDictionary} (vanilla language pairs + bundled glossary).
 *
 * <p>Results are deliberately conservative: strings it cannot cover well stay English.</p>
 */
public final class OfflineTranslator implements Translator {

    private final OfflineDictionary dictionary;

    public OfflineTranslator(OfflineDictionary dictionary) {
        this.dictionary = dictionary;
    }

    @Override
    public String name() {
        return "offline";
    }

    @Override
    public boolean retryable() {
        // Deterministic: retrying cannot change the outcome.
        return false;
    }

    @Override
    public List<String> translate(List<String> texts) {
        List<String> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(dictionary.translate(text));
        }
        return out;
    }
}
