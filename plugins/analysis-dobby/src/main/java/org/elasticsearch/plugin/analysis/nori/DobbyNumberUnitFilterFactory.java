/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.plugin.analysis.nori;

import org.apache.lucene.analysis.TokenStream;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;
import org.elasticsearch.index.analysis.Analysis;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link DobbyNumberUnitFilter}용 팩토리. 등록 이름 {@code dobby_number_unit}.
 *
 * <p>설정:
 * <ul>
 *   <li>{@code number_separators} (문자열, 기본 {@code .~-∼}) — 숫자덩어리 내부 구분자.</li>
 *   <li>{@code units_whitelist} / {@code units_whitelist_path} — {@code NNBC/SL} 외 추가 {@code NNG} 단위.</li>
 *   <li>{@code preserve_original} (불리언, 기본 {@code true}) — 원토큰 보존 여부.</li>
 * </ul>
 */
public class DobbyNumberUnitFilterFactory extends AbstractTokenFilterFactory {

    private static final String DEFAULT_SEPARATORS = ".~-∼"; // . ~ - ∼(U+223C)

    private final Set<Character> separators;
    private final Set<String> unitWhitelist;
    private final boolean preserveOriginal;

    public DobbyNumberUnitFilterFactory(IndexSettings indexSettings, Environment env, String name, Settings settings) {
        super(name, settings);

        String sep = settings.get("number_separators", DEFAULT_SEPARATORS);
        Set<Character> sepSet = new HashSet<>();
        for (int i = 0; i < sep.length(); i++) {
            sepSet.add(sep.charAt(i));
        }
        this.separators = Collections.unmodifiableSet(sepSet);

        List<String> whitelist = Analysis.getWordList(env, settings, "units_whitelist");
        this.unitWhitelist = whitelist != null
            ? Collections.unmodifiableSet(new HashSet<>(whitelist))
            : Collections.emptySet();

        this.preserveOriginal = settings.getAsBoolean("preserve_original", true);
    }

    @Override
    public TokenStream create(TokenStream tokenStream) {
        return new DobbyNumberUnitFilter(tokenStream, separators, unitWhitelist, preserveOriginal);
    }
}
