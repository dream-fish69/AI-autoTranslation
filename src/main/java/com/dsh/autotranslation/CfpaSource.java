package com.dsh.autotranslation;

import java.util.Map;

/** Abstraction over "a CFPA pack we can read zh_cn from" (local file or remote ranges). */
public interface CfpaSource extends AutoCloseable {

    String version();

    int namespaceCount();

    Map<String, String> translations(String namespace);

    @Override
    void close();
}
