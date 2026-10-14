package com.sanmajiao.aivocab;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Exercises real pixel arrays: no Android Bitmap/Color/Rect default-value stubs. */
public class GrayDashGeometryTest {
    private static final GrayDashGeometry.Box PREFIX = new GrayDashGeometry.Box(117, 487, 180, 521);

    @Test public void observedRegGeometryCountsSixOn247Background() {
        Raster image = reg(247, 202);
        image.rect(69, 535, 434, 538, 147); // Continuous input underline is not a missing-letter dash.
        List<GrayDashGeometry.Box> dashes = GrayDashGeometry.afterPrefix(image, PREFIX);
        assertEquals(6, dashes.size());
        assertEquals(198, dashes.get(0).left); assertEquals(387, dashes.get(5).right);
    }
    @Test public void whiteAndDarkerGrayBackgroundsStillSeparateInk() {
        assertEquals(6, GrayDashGeometry.afterPrefix(reg(255, 202), PREFIX).size());
        assertEquals(6, GrayDashGeometry.afterPrefix(reg(230, 185), PREFIX).size());
    }
    @Test public void paleDashesDoNotMergeIntoGrayBackground() {
        Raster image = reg(247, 235);
        for (int[] dash : new int[][] {{198, 217}, {234, 251}, {267, 284}, {301, 319}, {335, 353}, {369, 387}})
            image.rect(dash[0], 492, dash[1], 496, 235);
        assertEquals(6, GrayDashGeometry.afterPrefix(image, PREFIX).size());
    }
    @Test public void jpegBackgroundHalosDoNotCreateExtraComponents() {
        Raster image = reg(247, 202);
        for (int y = 484; y < 525; y++) for (int x = 440; x < 900; x++)
            image.pixels[y * image.width() + x] = color(242 + (x + y) % 8);
        assertEquals(6, GrayDashGeometry.afterPrefix(image, PREFIX).size());
    }
    @Test public void darkTypedLettersNeverBecomeOriginalPrefix() {
        assertTrue(GrayDashGeometry.afterPrefix(reg(247, 30), PREFIX).isEmpty());
    }
    @Test public void darkTypedLettersWithAntialiasingRemainRejected() {
        Raster image = reg(247, 30);
        image.rect(117, 489, 120, 519, 202);
        assertTrue(GrayDashGeometry.afterPrefix(image, PREFIX).isEmpty());
    }
    @Test public void greenAnswerGlyphsNeverBecomeGrayPrefix() {
        Raster image = reg(247, 202);
        image.rect(117, 487, 180, 521, 247);
        image.rectRgb(120, 490, 169, 517, 0xff00b060);
        assertTrue(GrayDashGeometry.afterPrefix(image, PREFIX).isEmpty());
    }
    @Test public void uniformBackgroundHasNoPrefixEvidence() {
        assertTrue(GrayDashGeometry.afterPrefix(new Raster(247), PREFIX).isEmpty());
    }
    @Test public void underlineOnGlyphRowIsNotSplitIntoDashes() {
        Raster image = new Raster(247); image.glyphs(202);
        image.rect(198, 493, 435, 496, 147);
        assertTrue(GrayDashGeometry.afterPrefix(image, PREFIX).isEmpty());
    }
    @Test public void distantRunCannotIncreaseLocalCount() {
        Raster image = reg(247, 202);
        image.rect(650, 492, 669, 496, 202); image.rect(683, 492, 702, 496, 202);
        assertEquals(6, GrayDashGeometry.afterPrefix(image, PREFIX).size());
    }
    @Test public void neighboringRowCannotIncreaseLocalCount() {
        Raster image = reg(247, 202);
        image.rect(402, 520, 421, 524, 202); image.rect(435, 520, 454, 524, 202);
        assertEquals(6, GrayDashGeometry.afterPrefix(image, PREFIX).size());
    }
    @Test public void onlyOneDashHasInsufficientEvidenceForSupplement() {
        Raster image = new Raster(247); image.glyphs(202);
        image.rect(198, 492, 217, 496, 202);
        assertTrue(GrayDashGeometry.afterPrefix(image, PREFIX).isEmpty());
    }
    @Test public void underlineWithinScanningBandDoesNotIncreaseCount() {
        Raster image = reg(247, 202);
        image.rect(69, 521, 434, 524, 147);
        assertEquals(6, GrayDashGeometry.afterPrefix(image, PREFIX).size());
    }
    @Test public void unusuallyLargeGapStopsCountInsteadOfJoiningWordGroups() {
        Raster image = new Raster(247); image.glyphs(202);
        image.rect(198, 492, 217, 496, 202); image.rect(234, 492, 251, 496, 202);
        image.rect(340, 492, 359, 496, 202); image.rect(373, 492, 392, 496, 202);
        assertEquals(2, GrayDashGeometry.afterPrefix(image, PREFIX).size());
    }

    @Test public void mergedFacThreeOcrMarksAreReplacedByFourPixelDashes() {
        QuestionParser.OcrLine fac = new QuestionParser.OcrLine("fac---", 117, 487, 284, 521);
        Question q = QuestionParser.parse(PixelHints.supplement(fourDashes(202), Arrays.asList(fac,
                new QuestionParser.OcrLine("全体教师会议", 117, 560, 400, 594))));
        assertNotNull(q); assertEquals("fac", q.prefix); assertEquals(7, q.length);
    }

    @Test public void mergedBoyBoxContainsSomeDashesButStillCountsAllFour() {
        QuestionParser.OcrLine boy = new QuestionParser.OcrLine("boy-", 117, 487, 217, 521);
        QuestionParser.OcrLine marks = new QuestionParser.OcrLine("--", 234, 487, 319, 521);
        QuestionParser.OcrLine friends = new QuestionParser.OcrLine("friends", 360, 487, 500, 521);
        QuestionParser.OcrLine line = new QuestionParser.OcrLine("boy- -- friends", 117, 487, 500, 521,
                false, Arrays.asList(boy, marks, friends));
        List<QuestionParser.OcrLine> corrected = PixelHints.supplement(fourDashes(202), Arrays.asList(line,
                new QuestionParser.OcrLine("儿时的朋友", 117, 560, 400, 594)));
        Question q = QuestionParser.parse(corrected);
        assertNotNull(q); assertEquals("boy", q.prefix); assertEquals(7, q.length);
        assertEquals("____ friends", q.context);
        assertEquals(2, corrected.get(0).words.size());
        assertSame(friends, corrected.get(0).words.get(1));
    }

    @Test public void pixelCountAlsoOverridesTooManyOcrMarks() {
        List<QuestionParser.OcrLine> corrected = PixelHints.supplement(fourDashes(202), Arrays.asList(
                new QuestionParser.OcrLine("fac------", 117, 487, 387, 521)));
        assertEquals(1, corrected.size()); assertEquals("fac----", corrected.get(0).text);
    }

    @Test public void fullLineContextAndGreenWordMetadataSurviveReplacement() {
        QuestionParser.OcrLine before = new QuestionParser.OcrLine("He", 30, 487, 80, 521,
                true, java.util.Collections.emptyList());
        QuestionParser.OcrLine fac = new QuestionParser.OcrLine("fac---", 117, 487, 284, 521);
        QuestionParser.OcrLine line = new QuestionParser.OcrLine("He fac---", 30, 487, 284, 521,
                false, Arrays.asList(before, fac));
        List<QuestionParser.OcrLine> corrected = PixelHints.supplement(fourDashes(202), Arrays.asList(line));
        assertEquals(1, corrected.size()); assertEquals("He fac----", corrected.get(0).text);
        assertSame(before, corrected.get(0).words.get(0));
        assertTrue(corrected.get(0).words.get(0).highlighted);
    }

    @Test public void mergedDarkTypedPrefixIsNotRescuedByPaleDashes() {
        QuestionParser.OcrLine typed = new QuestionParser.OcrLine("fac---", 117, 487, 284, 521);
        QuestionParser.OcrLine meeting = new QuestionParser.OcrLine("meeting", 360, 487, 500, 521);
        QuestionParser.OcrLine line = new QuestionParser.OcrLine("fac--- meeting", 117, 487, 500, 521,
                false, Arrays.asList(typed, meeting));
        List<QuestionParser.OcrLine> corrected = PixelHints.supplement(fourDashes(30), Arrays.asList(line,
                new QuestionParser.OcrLine("全体教师会议", 117, 560, 400, 594)));
        assertEquals("____ meeting", corrected.get(0).text);
        assertSame(meeting, corrected.get(0).words.get(1));
        Question q = QuestionParser.parse(corrected);
        assertNotNull(q); assertEquals("", q.prefix); assertEquals(0, q.length); assertTrue(q.needsReview());
        assertEquals("____ meeting", q.context);
    }

    @Test public void ordinaryDarkEnglishWithoutDashEvidenceRemainsUntouched() {
        QuestionParser.OcrLine word = new QuestionParser.OcrLine("friends", 360, 487, 500, 521);
        List<QuestionParser.OcrLine> corrected = PixelHints.supplement(fourDashes(30), Arrays.asList(word));
        assertEquals(1, corrected.size()); assertSame(word, corrected.get(0));
    }

    @Test public void oldRegSixPixelsStillProduceNineLetters() {
        List<QuestionParser.OcrLine> corrected = PixelHints.supplement(reg(247, 202), Arrays.asList(
                new QuestionParser.OcrLine("reg", 117, 487, 180, 521),
                new QuestionParser.OcrLine("教务主任", 117, 560, 400, 594)));
        Question q = QuestionParser.parse(corrected);
        assertNotNull(q); assertEquals("reg", q.prefix); assertEquals(9, q.length);
    }

    @Test public void grayGlyphAndDashesWithoutAnInputFieldDoNotInjectHints() {
        Raster image = fourDashes(202); image.rect(69, 535, 434, 538, 247);
        QuestionParser.OcrLine word = new QuestionParser.OcrLine("fac---", 117, 487, 284, 521);
        List<QuestionParser.OcrLine> result = PixelHints.supplement(image, Arrays.asList(word));
        assertEquals(1, result.size()); assertSame(word, result.get(0));
    }

    @Test public void tinyPaleBlackGlyphEdgesAreNotDashes() {
        Raster image = new Raster(247); image.glyphs(30);
        image.rect(198, 492, 203, 493, 202); image.rect(220, 492, 227, 493, 202);
        assertTrue(GrayDashGeometry.readHint(image, PREFIX).dashes.isEmpty());
    }

    @Test public void widePaleEdgesAdjacentToBlackGlyphCoresAreNotDashes() {
        Raster image = new Raster(247); image.glyphs(30);
        image.rect(198, 492, 217, 493, 202); image.rect(234, 492, 251, 493, 202);
        image.rect(198, 493, 217, 496, 30); image.rect(234, 493, 251, 496, 30);
        assertTrue(GrayDashGeometry.readHint(image, PREFIX).dashes.isEmpty());
    }

    @Test public void supplementStopsAtActualInputFieldRightBoundary() {
        Raster image = fourDashes(202);
        image.rect(69, 535, 434, 538, 247); image.rect(69, 535, 330, 538, 147);
        image.rect(335, 492, 353, 496, 202);
        List<QuestionParser.OcrLine> result = PixelHints.supplement(image, Arrays.asList(
                new QuestionParser.OcrLine("fac---", 117, 487, 284, 521)));
        assertEquals("fac----", result.get(0).text);
    }

    @Test public void choiceWithVisibleFieldLikePixelsKeepsAllOriginalWords() {
        QuestionParser.OcrLine he = new QuestionParser.OcrLine("He", 30, 487, 80, 521);
        QuestionParser.OcrLine was = new QuestionParser.OcrLine("was", 117, 487, 180, 521);
        QuestionParser.OcrLine green = new QuestionParser.OcrLine("embarrassed", 360, 487, 700, 521,
                true, java.util.Collections.emptyList());
        QuestionParser.OcrLine sentence = new QuestionParser.OcrLine("He was embarrassed", 30, 487, 700, 521,
                false, Arrays.asList(he, was, green));
        List<QuestionParser.OcrLine> original = Arrays.asList(sentence,
                new QuestionParser.OcrLine("羞愧的", 100, 600, 400, 634),
                new QuestionParser.OcrLine("高兴的", 100, 750, 400, 784),
                new QuestionParser.OcrLine("悲伤的", 100, 900, 400, 934),
                new QuestionParser.OcrLine("生气的", 100, 1050, 400, 1084));
        assertEquals("choice", QuestionParser.parse(original).kind);
        List<QuestionParser.OcrLine> result = PixelHints.supplement(fourDashes(30), original);
        assertEquals(original, result); assertSame(sentence, result.get(0));
    }

    @Test public void ordinaryTypedWordsNeverChangeWithoutVisibleInputLayout() {
        Raster image = fourDashes(30); image.rect(69, 535, 434, 538, 247);
        QuestionParser.OcrLine word = new QuestionParser.OcrLine("library", 117, 487, 180, 521);
        List<QuestionParser.OcrLine> result = PixelHints.supplement(image, Arrays.asList(word));
        assertEquals(1, result.size()); assertSame(word, result.get(0));
    }

    private static Raster fourDashes(int glyphColor) {
        Raster image = new Raster(247); image.glyphs(glyphColor);
        for (int[] dash : new int[][] {{198, 217}, {234, 251}, {267, 284}, {301, 319}})
            image.rect(dash[0], 492, dash[1], 496, 202);
        image.rect(69, 535, 434, 538, 147);
        return image;
    }

    private static Raster reg(int background, int glyphColor) {
        Raster image = new Raster(background); image.glyphs(glyphColor);
        // Coordinates of the six separate components beside the observed 1080x2400 reg row.
        int[][] dashes = {{198, 217}, {234, 251}, {267, 284}, {301, 319}, {335, 353}, {369, 387}};
        for (int[] dash : dashes) image.rect(dash[0], 492, dash[1], 496, Math.max(115, Math.min(202, background - 12)));
        image.rect(69, 535, 434, 538, 147);
        return image;
    }
    private static int color(int value) { return 0xff000000 | value << 16 | value << 8 | value; }
    private static final class Raster implements GrayDashGeometry.Pixels {
        final int[] pixels = new int[1080 * 2400];
        Raster(int background) { Arrays.fill(pixels, color(background)); }
        public int width() { return 1080; }
        public int height() { return 2400; }
        public void row(int y, int[] row) { System.arraycopy(pixels, y * width(), row, 0, width()); }
        void glyphs(int value) {
            rect(120, 491, 126, 517, value); rect(139, 491, 145, 517, value); rect(160, 491, 166, 517, value);
        }
        void rect(int l, int t, int r, int b, int value) { rectRgb(l, t, r, b, color(value)); }
        void rectRgb(int l, int t, int r, int b, int argb) {
            for (int y = t; y < b; y++) Arrays.fill(pixels, y * width() + l, y * width() + r, argb);
        }
    }
}
