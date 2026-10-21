package com.dsh.autotranslation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Single logger so the pipeline can be exercised outside the game (see tools/Harness.java). */
public final class Logs {

    public static final Logger LOG = LoggerFactory.getLogger("auto-translation");

    private Logs() {
    }
}
