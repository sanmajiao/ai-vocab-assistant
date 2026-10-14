package com.sanmajiao.aivocab;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic pixel negatives stay public; user screenshots are private JVM replays. */
public class OrderSlotGeometryTest {
    @Test public void twoColumnsAndCenteredLastSlotCountThree() {
        assertEquals(3, count(three(), 4));
    }

    @Test public void twoFullRowsCountFour() {
        Raster image = twoRows();
        assertEquals(4, count(image, 4));
    }

    @Test public void threeColumnsThenCenteredSlotPreservesOlderLayout() {
        Raster image = new Raster();
        image.rect(118, 556, 388, 558, 147);
        image.rect(405, 556, 675, 558, 147);
        image.rect(692, 556, 962, 558, 147);
        image.rect(405, 672, 675, 674, 147);
        assertEquals(4, count(image, 4));
    }

    @Test public void aSingleRowCountsTwo() {
        Raster image = new Raster();
        image.rect(232, 556, 531, 558, 147);
        image.rect(549, 556, 848, 558, 147);
        assertEquals(2, OrderSlotGeometry.count(image, 335, 624, 46, 4));
    }

    @Test public void progressLineAndDividerNeverAddSlots() {
        Raster image = three();
        image.rect(235, 312, 900, 317, 145);
        image.rect(40, 410, 1040, 413, 147);
        image.rectColor(240, 400, 840, 404, 0xff00bd68);
        assertEquals(3, count(image, 4));
    }

    @Test public void shortGrayDashesNeverBecomeWordSlots() {
        Raster image = new Raster();
        for (int x = 210; x < 600; x += 36) image.rect(x, 672, x + 20, 676, 202);
        assertEquals(0, count(image, 4));
    }

    @Test public void outlinedButtonsAboveOrBelowMeaningNeverBecomeSlots() {
        Raster image = new Raster();
        image.outline(232, 530, 531, 590, 147);
        image.outline(549, 530, 848, 590, 147);
        image.outline(232, 870, 531, 930, 147);
        assertEquals(0, count(image, 4));
    }

    @Test public void partiallyFilledDarkOrGreenSlotsLeaveCountUnknown() {
        Raster dark = three();
        dark.rect(250, 520, 260, 550, 30);
        assertEquals(0, count(dark, 4));
        Raster green = three();
        green.rectColor(420, 630, 430, 661, 0xff00bb67);
        assertEquals(0, count(green, 4));
    }

    @Test public void mismatchedWidthsOrUncenteredClusterAreUnknown() {
        Raster width = three();
        width.rect(392, 672, 689, 674, 247);
        width.rect(460, 672, 610, 674, 147);
        assertEquals(0, count(width, 4));
        Raster offCenter = new Raster();
        offCenter.rect(80, 672, 220, 674, 147);
        offCenter.rect(240, 672, 380, 674, 147);
        assertEquals(0, count(offCenter, 4));
    }

    @Test public void incompleteAnchorsAndTooManySlotsAreUnknown() {
        assertEquals(0, count(three(), 2));
        assertEquals(0, OrderSlotGeometry.count(three(), -1, 740, 46, 4));
        assertEquals(0, OrderSlotGeometry.count(three(), 335, 540, 46, 4));
    }

    @Test public void adapterPreservesOtherQuestionObjectsAndFields() {
        Question blank = new Question("blank", "释义", "context", "reg", 9, Collections.emptyList());
        assertSame(blank, OrderSlotHints.attach(three(), Collections.emptyList(), blank));
        Question q = order();
        Question result = OrderSlotHints.attach(three(), Arrays.asList(
                new QuestionParser.OcrLine("く返回", 60, 290, 188, 335),
                new QuestionParser.OcrLine("必然后果", 440, 740, 630, 786)), q);
        assertEquals(3, result.slotCount);
        assertEquals(q.kind, result.kind); assertEquals(q.prompt, result.prompt);
        assertEquals(q.context, result.context); assertEquals(q.options, result.options);
        assertEquals(q.prefix, result.prefix); assertEquals(q.length, result.length);
        assertEquals(q.target, result.target); assertEquals(q.minSelections, result.minSelections);
    }

    @Test public void adapterDoesNotInferCountFromWordsOrTranslation() {
        Question q = order();
        assertEquals(0, OrderSlotHints.attach(new Raster(), Arrays.asList(
                new QuestionParser.OcrLine("返回", 60, 290, 188, 335),
                new QuestionParser.OcrLine("必然后果", 440, 740, 630, 786)), q).slotCount);
        assertEquals(0, OrderSlotHints.attach(three(), Arrays.asList(
                new QuestionParser.OcrLine("必然后果", 440, 740, 630, 786)), q).slotCount);
    }

    private static Question order() {
        return new Question("order", "必然后果", "", "", 0,
                Arrays.asList("an", "inevitable", "consequence", "plead"));
    }
    private static int count(Raster image, int options) {
        return OrderSlotGeometry.count(image, 335, 740, 46, options);
    }
    private static Raster three() {
        Raster image = new Raster();
        image.rect(232, 556, 531, 558, 147);
        image.rect(549, 556, 848, 558, 147);
        image.rect(392, 672, 689, 674, 147);
        return image;
    }
    private static Raster twoRows() {
        Raster image = three();
        image.rect(392, 672, 689, 674, 247);
        image.rect(232, 672, 531, 674, 147);
        image.rect(549, 672, 848, 674, 147);
        return image;
    }
    private static final class Raster implements OrderSlotGeometry.Pixels {
        final int[] pixels = new int[1080 * 2400];
        Raster() { Arrays.fill(pixels, 0xfff7f7f7); }
        public int width() { return 1080; }
        public int height() { return 2400; }
        public void row(int y, int[] target) { System.arraycopy(pixels, y * width(), target, 0, width()); }
        void rect(int l, int t, int r, int b, int gray) {
            rectColor(l, t, r, b, 0xff000000 | gray << 16 | gray << 8 | gray);
        }
        void rectColor(int l, int t, int r, int b, int color) {
            for (int y = t; y < b; y++) Arrays.fill(pixels, y * width() + l, y * width() + r, color);
        }
        void outline(int l, int t, int r, int b, int gray) {
            rect(l, t, r, t + 2, gray); rect(l, b - 2, r, b, gray);
            rect(l, t, l + 2, b, gray); rect(r - 2, t, r, b, gray);
        }
    }
}
