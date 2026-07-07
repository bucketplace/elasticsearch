/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.plugin.analysis.nori;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.ko.POS;
import org.apache.lucene.analysis.ko.tokenattributes.PartOfSpeechAttribute;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 숫자(SN) + 단위명사를 하나의 토큰으로 결합하는 필터.
 *
 * <p>mecab-ko-dic 기반 토크나이저는 {@code 1개}를 {@code 1/SN} + {@code 개/NNBC}로,
 * {@code 1.5개}를 {@code 1/SN}+{@code ./SY}+{@code 5/SN}+{@code 개/NNBC}로 분리한다.
 * 본 필터는 토큰화 이후 단계에서 다음을 수행한다.
 *
 * <ol>
 *   <li><b>숫자덩어리(number-run) 식별</b> — {@code SN ( SEP SN )*}. SEP은 term이
 *       {@link #separators}(기본 {@code . ~ -})에 속하는 {@code SY/SC} 토큰이며 각 토큰은
 *       offset이 인접(공백 없음)해야 한다. 천단위 콤마는 토큰화 전 char_filter에서 제거되는 것을 전제로 한다.</li>
 *   <li><b>단위 결합</b> — 숫자덩어리 바로 뒤(offset 인접) 토큰의 품사가 {@code NNBC} 또는 {@code SL}이거나,
 *       {@code NNG}이면서 {@link #unitWhitelist}에 포함되면 결합 토큰을 생성한다.</li>
 * </ol>
 *
 * <p>{@link #preserveOriginal}가 true이면 원토큰을 모두 유지하면서 결합 토큰을 같은 위치에
 * 중첩(posInc=0, posLength=덩어리+단위 토큰 수)으로 추가한다. 예: {@code 1.5개} →
 * {@code 1}, {@code 1.5개}(중첩), {@code .}, {@code 5}, {@code 개}. false이면 결합 토큰만 남긴다.
 *
 * <p>결합 토큰의 품사 속성은 비워 두므로({@code leftPOS == null}) 다운스트림
 * {@link org.apache.lucene.analysis.ko.KoreanPartOfSpeechStopFilter}에서 항상 보존된다.
 *
 * <p><b>필터 순서 의존성.</b> 본 필터는 {@code dobby_part_of_speech}(POS stop filter)보다
 * <em>앞</em>에 배치해야 하며, 결합 토큰 span 내부의 구성 토큰(예: {@code 1.5개}의 {@code ./SY})을
 * stop filter로 제거해서는 안 된다. 내부 구성 토큰이 제거되면 position이 collapse되어 결합 토큰의
 * posLength가 실제 position 수와 어긋나고 phrase/span 쿼리가 오동작한다. 기본 nori stoptags에는
 * {@code SN/SY/SC}가 없어 현재 설정에선 안전하지만, {@code dobby_part_of_speech}의 stoptags에
 * 이들을 추가하면 그래프가 깨지므로 주의한다.
 */
public final class DobbyNumberUnitFilter extends TokenFilter {

    /** 결합 토큰에 부여할 타입(디버깅/하이라이팅 식별용). */
    static final String NUMBER_UNIT_TYPE = "<NUMBER_UNIT>";

    private final CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAttr = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncrAttr = addAttribute(PositionIncrementAttribute.class);
    private final PositionLengthAttribute posLengthAttr = addAttribute(PositionLengthAttribute.class);
    private final TypeAttribute typeAttr = addAttribute(TypeAttribute.class);
    private final PartOfSpeechAttribute posAttr = addAttribute(PartOfSpeechAttribute.class);

    private final Set<Character> separators;
    private final Set<String> unitWhitelist;
    private final boolean preserveOriginal;

    /** 방출 대기 토큰(이미 결정된 출력). */
    private final ArrayDeque<State> output = new ArrayDeque<>();
    /** 앞서 읽었으나 아직 처리하지 않은 입력 토큰(LIFO). */
    private final ArrayDeque<Tok> pushback = new ArrayDeque<>();

    private boolean exhausted = false;

    public DobbyNumberUnitFilter(
        TokenStream input,
        Set<Character> separators,
        Set<String> unitWhitelist,
        boolean preserveOriginal
    ) {
        super(input);
        this.separators = separators;
        this.unitWhitelist = unitWhitelist;
        this.preserveOriginal = preserveOriginal;
    }

    @Override
    public boolean incrementToken() throws IOException {
        while (output.isEmpty()) {
            if (exhausted && pushback.isEmpty()) {
                return false;
            }
            fill();
        }
        restoreState(output.poll());
        return true;
    }

    /** 입력에서 한 세그먼트(passthrough 1개 또는 숫자덩어리[+단위])를 읽어 {@link #output}에 채운다. */
    private void fill() throws IOException {
        Tok first = nextTok();
        if (first == null) {
            return;
        }

        // 숫자(SN)로 시작하지 않거나 위치 증가가 1이 아닌(중첩) 토큰은 그대로 통과.
        if (first.posIncr != 1 || first.leftPOS != POS.Tag.SN) {
            output.add(first.state);
            return;
        }

        final List<Tok> run = new ArrayList<>();
        run.add(first);
        int lastEnd = first.endOffset;
        Tok unit = null;

        while (true) {
            Tok t = nextTok();
            if (t == null) {
                break;
            }
            // 인접하지 않거나 중첩 토큰이면 덩어리 종료.
            if (t.startOffset != lastEnd || t.posIncr != 1) {
                pushback.push(t);
                break;
            }
            if (t.leftPOS == POS.Tag.SN) {
                run.add(t);
                lastEnd = t.endOffset;
                continue;
            }
            if (isSeparator(t)) {
                // 구분자는 뒤에 SN이 인접할 때만 덩어리의 일부.
                Tok u = nextTok();
                if (u != null && u.startOffset == t.endOffset && u.posIncr == 1 && u.leftPOS == POS.Tag.SN) {
                    run.add(t);
                    run.add(u);
                    lastEnd = u.endOffset;
                    continue;
                }
                if (u != null) {
                    pushback.push(u);
                }
                pushback.push(t);
                break;
            }
            if (isUnit(t)) {
                unit = t;
                break;
            }
            // 단위가 아닌 토큰 → 덩어리 종료(다음 fill에서 처리).
            pushback.push(t);
            break;
        }

        emit(run, unit);
    }

    /** 결정된 숫자덩어리(run)와 단위(unit, nullable)를 출력 큐에 배치. */
    private void emit(List<Tok> run, Tok unit) {
        if (unit == null) {
            // 결합 없음 → 원토큰 그대로 방출.
            for (Tok t : run) {
                output.add(t.state);
            }
            return;
        }

        if (preserveOriginal) {
            // [run0], [결합(중첩)], [run1..], [unit]
            output.add(run.get(0).state);
            output.add(buildCombined(run, unit, /* posIncr */ 0, /* posLength */ run.size() + 1));
            for (int i = 1; i < run.size(); i++) {
                output.add(run.get(i).state);
            }
            output.add(unit.state);
        } else {
            // 결합 토큰만(한 위치). 첫 토큰의 위치 증가를 계승.
            output.add(buildCombined(run, unit, /* posIncr */ run.get(0).posIncr, /* posLength */ 1));
        }
    }

    /** 결합 토큰을 합성해 {@link State}로 캡처. POS는 비워 두어 stop filter에서 보존되게 한다. */
    private State buildCombined(List<Tok> run, Tok unit, int posIncr, int posLength) {
        final StringBuilder sb = new StringBuilder();
        for (Tok t : run) {
            sb.append(t.term);
        }
        sb.append(unit.term);

        clearAttributes();
        termAttr.setEmpty().append(sb);
        offsetAttr.setOffset(run.get(0).startOffset, unit.endOffset);
        posIncrAttr.setPositionIncrement(posIncr);
        posLengthAttr.setPositionLength(posLength);
        typeAttr.setType(NUMBER_UNIT_TYPE);
        // posAttr: setToken 미호출 → leftPOS == null → KoreanPartOfSpeechStopFilter가 보존.
        return captureState();
    }

    private boolean isSeparator(Tok t) {
        // 구분자는 term 글자뿐 아니라 POS도 SY/SC여야 한다. 같은 '.'이라도 문장 종결부호(SF)처럼
        // 다른 POS로 태깅된 토큰을 숫자 구분자로 오인하지 않게 하는 방어 장치다. 다만 mecab-ko-dic은
        // 숫자 사이에 낀 기호를 대부분 SY(., ~, -)/SC(, · /)로 태깅하므로 기본 사전에선 결합 결과가 바뀌지 않는다.
        return t.term.length() == 1
            && separators.contains(t.term.charAt(0))
            && (t.leftPOS == POS.Tag.SY || t.leftPOS == POS.Tag.SC);
    }

    private boolean isUnit(Tok t) {
        if (t.leftPOS == POS.Tag.NNBC || t.leftPOS == POS.Tag.SL) {
            return true;
        }
        return t.leftPOS == POS.Tag.NNG && unitWhitelist.contains(t.term);
    }

    /** pushback이 있으면 그것을, 없으면 입력에서 한 토큰을 읽어 메타데이터와 함께 반환. */
    private Tok nextTok() throws IOException {
        if (!pushback.isEmpty()) {
            return pushback.pop();
        }
        if (exhausted) {
            return null;
        }
        if (!input.incrementToken()) {
            exhausted = true;
            return null;
        }
        Tok t = new Tok();
        t.state = captureState();
        t.term = termAttr.toString();
        t.startOffset = offsetAttr.startOffset();
        t.endOffset = offsetAttr.endOffset();
        t.posIncr = posIncrAttr.getPositionIncrement();
        t.leftPOS = posAttr.getLeftPOS();
        return t;
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        output.clear();
        pushback.clear();
        exhausted = false;
    }

    /** 입력 토큰 1개의 캡처 상태 + 판정에 필요한 메타데이터. */
    private static final class Tok {
        State state;
        String term;
        int startOffset;
        int endOffset;
        int posIncr;
        POS.Tag leftPOS;
    }
}
