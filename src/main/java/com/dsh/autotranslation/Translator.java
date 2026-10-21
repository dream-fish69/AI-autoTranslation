package com.dsh.autotranslation;

import java.util.List;

/** A translation backend. Implementations must be thread safe. */
public interface Translator {

    String name();

    /** False when no backend is configured (e.g. no API key); retries would be pointless. */
    default boolean available() {
        return true;
    }

    /** Whether a failed run is worth retrying (network backends yes, local dictionaries no). */
    default boolean retryable() {
        return available();
    }

    /**
     * Translates a batch.
     *
     * @return one entry per input; {@code null} means "could not translate this entry".
     */
    List<String> translate(List<String> texts) throws Exception;
}
