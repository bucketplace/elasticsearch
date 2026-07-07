/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.plugin.analysis.nori;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.ko.KoreanTokenizer;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.analysis.AnalysisTestsHelper;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.test.ESTestCase.TestAnalysis;
import org.elasticsearch.test.ESTokenStreamTestCase;

import java.io.IOException;
import java.io.StringReader;

/**
 * {@link DobbyNumberUnitFilter}의 그래프 속성(term/offset/posIncrement/posLength/type) 단위 테스트.
 * 실제 {@link KoreanTokenizer} 출력에 필터를 태워 {@code BaseTokenStreamTestCase} 단언으로 회귀를 잡는다.
 */
public class DobbyNumberUnitFilterTests extends ESTokenStreamTestCase {

    public void testSingleUnitOverlapsOriginal() throws IOException {
        TokenStream stream = analyze(filterFactory(Settings.EMPTY), "1개");
        assertTokenStreamContents(
            stream,
            new String[] { "1", "1개", "개" },
            new int[] { 0, 0, 1 },
            new int[] { 1, 2, 2 },
            null,
            new int[] { 1, 0, 1 },
            new int[] { 1, 2, 1 }
        );
    }

    public void testDecimalNumberRun() throws IOException {
        TokenStream stream = analyze(filterFactory(Settings.EMPTY), "1.5개");
        assertTokenStreamContents(
            stream,
            new String[] { "1", "1.5개", ".", "5", "개" },
            new int[] { 0, 0, 1, 2, 3 },
            new int[] { 1, 4, 2, 3, 4 },
            null,
            new int[] { 1, 0, 1, 1, 1 },
            new int[] { 1, 4, 1, 1, 1 }
        );
    }

    public void testSpaceBreaksCombination() throws IOException {
        TokenStream stream = analyze(filterFactory(Settings.EMPTY), "1 개");
        assertTokenStreamContents(
            stream,
            new String[] { "1", "개" },
            new int[] { 0, 2 },
            new int[] { 1, 3 },
            null,
            new int[] { 1, 1 },
            new int[] { 1, 1 }
        );
    }

    public void testLatinUnit() throws IOException {
        TokenStream stream = analyze(filterFactory(Settings.EMPTY), "100ml");
        assertTokenStreamContents(
            stream,
            new String[] { "100", "100ml", "ml" },
            new int[] { 0, 0, 3 },
            new int[] { 3, 5, 5 },
            null,
            new int[] { 1, 0, 1 },
            new int[] { 1, 2, 1 }
        );
    }

    public void testWhitelistedCommonNounUnit() throws IOException {
        Settings settings = Settings.builder().putList("index.analysis.filter.my_filter.units_whitelist", "박스").build();
        TokenStream stream = analyze(filterFactory(settings), "2박스");
        assertTokenStreamContents(
            stream,
            new String[] { "2", "2박스", "박스" },
            new int[] { 0, 0, 1 },
            new int[] { 1, 3, 3 },
            null,
            new int[] { 1, 0, 1 },
            new int[] { 1, 2, 1 }
        );
    }

    public void testPreserveOriginalFalseKeepsOnlyCombined() throws IOException {
        Settings settings = Settings.builder().put("index.analysis.filter.my_filter.preserve_original", false).build();
        TokenStream stream = analyze(filterFactory(settings), "1개");
        assertTokenStreamContents(
            stream,
            new String[] { "1개" },
            new int[] { 0 },
            new int[] { 2 },
            null,
            new int[] { 1 },
            new int[] { 1 }
        );
    }

    public void testCombinedTokenTypeIsNumberUnit() throws IOException {
        TokenStream stream = analyze(filterFactory(Settings.EMPTY), "1개");
        TypeAttribute typeAttr = stream.addAttribute(TypeAttribute.class);
        stream.reset();
        assertTrue(stream.incrementToken()); // 1
        assertTrue(stream.incrementToken()); // 1개(결합)
        assertEquals(DobbyNumberUnitFilter.NUMBER_UNIT_TYPE, typeAttr.type());
        stream.end();
        stream.close();
    }

    private TokenFilterFactory filterFactory(Settings filterSettings) throws IOException {
        Settings settings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current())
            .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
            .put("index.analysis.filter.my_filter.type", "dobby_number_unit")
            .put(filterSettings)
            .build();
        TestAnalysis analysis = AnalysisTestsHelper.createTestAnalysisFromSettings(settings, new AnalysisNoriPlugin());
        return analysis.tokenFilter.get("my_filter");
    }

    private TokenStream analyze(TokenFilterFactory factory, String text) {
        // NOTE: 필터가 소수점('.'=SY) 구분자를 보려면 tokenizer가 punctuation을 버리면 안 된다.
        Tokenizer tokenizer = new KoreanTokenizer(
            KoreanTokenizer.DEFAULT_TOKEN_ATTRIBUTE_FACTORY,
            null,
            KoreanTokenizer.DecompoundMode.DISCARD,
            false,
            false
        );
        tokenizer.setReader(new StringReader(text));
        return factory.create(tokenizer);
    }
}
