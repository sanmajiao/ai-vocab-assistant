package com.sanmajiao.aivocab;

import org.junit.Test;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** Exercises the actual pure rules called after JSON decoding, without Android JSON stubs. */
public class OrderRulesTest {
    private static OrderRules.Result words(List<String> options, int slots, Object... words) throws IOException {
        return OrderRules.validateWords(options, Arrays.asList(words), slots);
    }
    private static void rejectWords(List<String> options, int slots, Object... words) {
        try { words(options, slots, words); fail("Expected invalid chunks to be rejected"); }
        catch (IOException expected) { }
    }

    @Test public void wordsProtocolMapsScholarshipToActualScreenIndices() throws IOException {
        OrderRules.Result result = words(Arrays.asList("scholarship", "a", "for", "apply"),
                4, "apply", "for", "a", "scholarship");
        assertEquals("apply for a scholarship", result.sentence);
        assertEquals("4. apply → 3. for → 2. a → 1. scholarship", result.sequence);
    }
    @Test public void wordsProtocolSkipsDistractorsWithConfirmedSlots() throws IOException {
        OrderRules.Result result = words(Arrays.asList("of", "reflect", "be", "aware"), 3, "be", "aware", "of");
        assertEquals("be aware of", result.sentence);
        assertEquals("3. be → 4. aware → 1. of", result.sequence);
    }
    @Test public void wordsProtocolAcceptsPossessiveAndSmartApostrophes() throws IOException {
        OrderRules.Result result = words(Arrays.asList("issue", "at", "one's", "peril"),
                3, "at", "one’s", "peril");
        assertEquals("at one's peril", result.sentence);
        assertEquals("2. at → 3. one's → 4. peril", result.sequence);
        assertEquals("at one's peril", words(Arrays.asList("at", "one's", "peril"),
                3, "at", "one‘s", "peril").sentence);
    }
    @Test public void wordsProtocolKeepsArticleBeforeNounAndInsideObject() throws IOException {
        assertEquals("a graduate seminar", words(Arrays.asList("a", "registrar", "graduate", "seminar"),
                3, "a", "graduate", "seminar").sentence);
        assertEquals("the current issue", words(Arrays.asList("the", "issue", "current", "faculty"),
                3, "the", "current", "issue").sentence);
        assertEquals("crush the seeds into oil", words(Arrays.asList("oil", "into", "the", "seeds", "crush"),
                5, "crush", "the", "seeds", "into", "oil").sentence);
    }
    @Test public void wordsProtocolUsesDuplicateChunksOnlyUpToAvailableCopies() throws IOException {
        OrderRules.Result result = words(Arrays.asList("a", "test", "A", "day"),
                4, "a", "test", "a", "day");
        assertEquals("a test A day", result.sentence);
        assertEquals("1. a → 2. test → 3. A → 4. day", result.sequence);
        rejectWords(Arrays.asList("a", "test", "day"), 3, "a", "a", "day");
    }
    @Test public void wordsProtocolUnknownSlotCountAllowsNonemptySubset() throws IOException {
        assertEquals("plead for", words(Arrays.asList("plead", "scholarship", "embarrassed", "for"),
                0, "plead", "for").sentence);
    }
    @Test public void wordsProtocolRejectsUnknownWordsAndFabricatedSpelling() {
        rejectWords(Arrays.asList("a", "graduate", "seminar"), 3, "a", "postgraduate", "seminar");
        rejectWords(Arrays.asList("at", "one's", "peril"), 3, "at", "ones", "peril");
    }
    @Test public void wordsProtocolRejectsCountMismatchOrEmptySelections() {
        List<String> options = Arrays.asList("a", "graduate", "seminar", "issue");
        rejectWords(options, 3, "a", "graduate");
        rejectWords(options, 3, "a", "graduate", "seminar", "issue");
        rejectWords(options, 0);
        rejectWords(options, -1, "a");
    }
    @Test public void wordsProtocolRejectsNonstringEmptyAndNullChunks() {
        List<String> options = Arrays.asList("a", "graduate", "seminar");
        rejectWords(options, 3, "a", 3, "seminar");
        rejectWords(options, 3, "a", null, "seminar");
        rejectWords(options, 3, "a", " ", "seminar");
        rejectWords(options, 3, "a", true, "seminar");
    }
    @Test public void wordsProtocolTreatsMultiwordOptionAsSingleChunk() throws IOException {
        OrderRules.Result result = words(Arrays.asList("at", "one's peril", "issue"), 2, "AT", "one’s   peril");
        assertEquals("at one's peril", result.sentence);
        assertEquals("1. at → 2. one's peril", result.sequence);
        rejectWords(Arrays.asList("at", "one's peril", "issue"), 3, "at", "one's", "peril");
        rejectWords(Arrays.asList("at", "one's", "peril"), 2, "at", "one's peril");
    }
    @Test public void wordsProtocolNormalizesNfkcCaseAndUnicodeSpacesWithoutChangingOriginal() throws IOException {
        assertEquals("an Consequence", words(Arrays.asList("an", "Consequence"), 2,
                "ＡＮ", "\u00a0CONSEQUENCE\u2003").sentence);
    }
    @Test public void wordsProtocolPreservesHyphensPeriodsAndChunkBoundaries() throws IOException {
        assertEquals("long-term contract", words(Arrays.asList("long-term", "contract"),
                2, "long-term", "contract").sentence);
        rejectWords(Arrays.asList("long-term", "contract"), 2, "longterm", "contract");
        rejectWords(Arrays.asList("do", "sth."), 2, "do", "sth");
        rejectWords(Arrays.asList("an", "issue"), 1, "anissue");
    }
    @Test public void legacyProtocolRemainsStrictWhenTextAndIndicesDisagree() {
        reject(Arrays.asList("scholarship", "a", "for", "apply"), Arrays.asList(4, 2, 3, 1),
                4, "apply for a scholarship");
        reject(Arrays.asList("of", "reflect", "be", "aware"), Arrays.asList(3, 4, 4), 3, "be aware of");
    }
    @Test public void wordsProtocolHandlesNullContainersAndInvalidOriginalChunks() {
        try { OrderRules.validateWords(null, Arrays.asList("a"), 1); fail(); }
        catch (IOException expected) { }
        try { OrderRules.validateWords(Arrays.asList("a"), null, 1); fail(); }
        catch (IOException expected) { }
        rejectWords(Arrays.asList(null, " "), 1, "a");
    }

    private static String result(List<String> options, int slots, String answer, Integer... indices) throws IOException {
        return OrderRules.validate(options, Arrays.asList(indices), slots, answer).sentence;
    }
    private static void reject(List<String> options, List<?> indices, int slots, String answer) {
        try { OrderRules.validate(options, indices, slots, answer); fail("Expected invalid ordering to be rejected"); }
        catch (IOException expected) { }
    }
    @Test public void inevitableConsequenceUsesThreeOfFourOptions() throws IOException {
        List<String> options = Arrays.asList("an", "inevitable", "Consequence", "plead");
        assertEquals("an inevitable Consequence", result(options, 3, "an inevitable consequence", 1, 2, 3));
    }
    @Test public void scholarshipUsesAllFourInAnswerOrder() throws IOException {
        assertEquals("apply for a scholarship", result(Arrays.asList("scholarship", "apply", "a", "for"),
                4, "apply for a scholarship", 2, 4, 3, 1));
    }
    @Test public void articleCanLeadItsNounPhraseWithoutAddingDistractor() throws IOException {
        assertEquals("a graduate seminar", result(Arrays.asList("seminar", "muddle", "a", "graduate"),
                3, "a graduate seminar", 3, 4, 1));
        assertEquals("a caring attitude", result(Arrays.asList("attitude", "a", "scholarship", "caring"),
                3, "a caring attitude", 2, 4, 1));
    }
    @Test public void articleCanStayInsideExpressionAndModifyObjectBeforePreposition() throws IOException {
        assertEquals("crush the seeds into oil", result(Arrays.asList("oil", "into", "the", "seeds", "crush"),
                5, "crush the seeds into oil", 5, 3, 4, 2, 1));
        assertEquals("at a glance", result(Arrays.asList("glance", "at", "a", "seminar"),
                3, "at a glance", 2, 3, 1));
    }
    @Test public void muddleThroughSkipsTwoDistractors() throws IOException {
        assertEquals("muddle through", result(Arrays.asList("muddle", "through", "complain", "issue"),
                2, "muddle through", 1, 2));
    }
    @Test public void pleadForCanSkipMiddleDistractors() throws IOException {
        assertEquals("plead for", result(Arrays.asList("plead", "scholarship", "embarrassed", "for"),
                2, "plead for", 1, 4));
    }
    @Test public void accompaniedBySkipsFourthDistractor() throws IOException {
        assertEquals("be accompanied by", result(Arrays.asList("be", "by", "accompanied", "scholarship"),
                3, "be accompanied by", 1, 3, 2));
    }
    @Test public void previousUndertakeFourSlotsStillUsesAllTokensOnce() throws IOException {
        assertEquals("undertake to do sth.", result(Arrays.asList("sth.", "do", "to", "undertake"),
                4, "undertake to do sth.", 4, 3, 2, 1));
    }
    @Test public void unknownSlotCountAcceptsNecessarySubset() throws IOException {
        assertEquals("muddle through", result(Arrays.asList("muddle", "through", "complain", "issue"),
                0, "muddle through", 1, 2));
    }
    @Test public void unknownSlotCountAlsoAcceptsCompleteSequence() throws IOException {
        assertEquals("plead for", result(Arrays.asList("plead", "for"), 0, "plead for", 1, 2));
    }
    @Test public void knownSlotCountRejectsTooFewAndTooMany() {
        List<String> options = Arrays.asList("an", "inevitable", "Consequence", "plead");
        reject(options, Arrays.asList(1, 2), 3, "an inevitable");
        reject(options, Arrays.asList(1, 2, 3, 4), 3, "an inevitable Consequence plead");
        reject(options, Arrays.asList(1, 2, 3), 5, "an inevitable Consequence");
    }
    @Test public void emptySelectionIsRejectedEvenWithUnknownSlots() {
        reject(Arrays.asList("plead", "for"), Collections.emptyList(), 0, "");
    }
    @Test public void duplicateIndexIsRejected() {
        reject(Arrays.asList("plead", "for"), Arrays.asList(1, 1), 2, "plead plead");
    }
    @Test public void indexRangeIsStrictBeforeCastingLong() {
        List<String> options = Arrays.asList("plead", "for");
        reject(options, Arrays.asList(0, 2), 2, "plead for");
        reject(options, Arrays.asList(1, 3), 2, "plead for");
        reject(options, Arrays.asList(1, Long.MAX_VALUE), 2, "plead for");
    }
    @Test public void indicesMustBeIntegerValuesWithoutStringOrFloatingCoercion() {
        List<String> options = Arrays.asList("plead", "for");
        reject(options, Arrays.asList(1.0, 2), 2, "plead for");
        reject(options, Arrays.asList("1", 2), 2, "plead for");
        reject(options, Arrays.asList(null, 2), 2, "plead for");
    }
    @Test public void answerCannotFabricateWordsOrRemoveTokenBoundaries() {
        List<String> options = Arrays.asList("an", "inevitable", "Consequence", "plead");
        reject(options, Arrays.asList(1, 2, 3), 3, "an unavoidable consequence");
        reject(options, Arrays.asList(1, 2, 3), 3, "aninevitableconsequence");
        reject(options, Arrays.asList(1, 2, 3), 3, "✓ an inevitable consequence");
    }
    @Test public void punctuationAndAnswerSequenceMustMatchChosenOriginalTokens() {
        List<String> options = Arrays.asList("sth.", "do", "to", "undertake");
        reject(options, Arrays.asList(4, 3, 2, 1), 4, "undertake to do sth");
        reject(options, Arrays.asList(3, 4, 2, 1), 4, "undertake to do sth.");
    }
    @Test public void normalizedSpacingAndCaseStillReturnOriginalText() throws IOException {
        assertEquals("an Consequence", result(Arrays.asList("an", "Consequence", "plead"),
                2, "  AN   consequence  ", 1, 2));
    }
    @Test public void oldConstructorsDefaultUnknownAndCopyPreservesQuestionMetadata() {
        List<String> options = Arrays.asList("an", "inevitable", "Consequence", "plead");
        Question old = new Question("order", "必然后果", "", "", 0, options);
        assertEquals(0, old.slotCount);
        Question oldEight = new Question("order", "必然后果", "context", "prefix", 7, options, "target", 2);
        assertEquals(0, oldEight.slotCount);
        Question counted = oldEight.withSlotCount(3);
        assertEquals(3, counted.slotCount); assertEquals(oldEight.options, counted.options);
        assertEquals(oldEight.context, counted.context); assertEquals(oldEight.prefix, counted.prefix);
        assertEquals(oldEight.length, counted.length); assertEquals(oldEight.target, counted.target);
        assertEquals(oldEight.minSelections, counted.minSelections);
        assertTrue(counted.display().contains("只选必要词块")); assertTrue(counted.display().contains("填入 3 项"));
    }
}
