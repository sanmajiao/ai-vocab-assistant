package com.sanmajiao.aivocab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Pure pixel geometry shared by the Bitmap adapter and ordinary JVM tests. */
final class GrayDashGeometry {
    interface Pixels {
        int width();
        int height();
        void row(int y, int[] pixels);
    }

    static final class Hint {
        final List<Box> dashes;
        final boolean typed;
        Hint(List<Box> dashes, boolean typed) { this.dashes = dashes; this.typed = typed; }
    }

    static final class Box {
        final int left, top, right, bottom;
        Box(int left, int top, int right, int bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        int width() { return right - left; }
        int height() { return bottom - top; }
        double centerY() { return (top + bottom) / 2.0; }
    }

    private GrayDashGeometry() { }

    /** The OCR hint box may already contain dashes; find their full run from its left edge. */
    static List<Box> afterPrefix(Pixels image, Box prefix) {
        Hint hint = readHint(image, prefix);
        return hint.typed ? Collections.emptyList() : hint.dashes;
    }

    static Hint readHint(Pixels image, Box prefix) {
        return readHint(image, prefix, image.width());
    }

    /** Production callers restrict the scan to a visibly underlined input field. */
    static Hint readHint(Pixels image, Box prefix, int fieldRight) {
        int width = image.width(), height = image.height();
        if (prefix.width() <= 0 || prefix.height() <= 0 || prefix.left >= width)
            return new Hint(Collections.emptyList(), false);
        int[] row = new int[width];
        int scanLeft = Math.max(0, prefix.left);
        int scanRight = Math.min(width, fieldRight);
        int scanTop = Math.max(0, prefix.top - 3);
        int scanBottom = Math.min(height, prefix.bottom + 4);
        int span = scanRight - scanLeft, rows = scanBottom - scanTop;
        if (span <= 0 || rows <= 0) return new Hint(Collections.emptyList(), false);
        boolean[] ink = new boolean[span * rows];
        for (int y = scanTop; y < scanBottom; y++) {
            image.row(y, row);
            int background = background(row);
            for (int x = scanLeft; x < scanRight; x++)
                ink[(y - scanTop) * span + x - scanLeft] = grayInk(row[x], background);
        }

        List<Box> dashes = new ArrayList<>();
        int[] queue = new int[ink.length];
        for (int seed = 0; seed < ink.length; seed++) {
            if (!ink[seed]) continue;
            int head = 0, tail = 0, minX = span, minY = rows, maxX = 0, maxY = 0;
            ink[seed] = false; queue[tail++] = seed;
            while (head < tail) {
                int pixel = queue[head++], x = pixel % span, y = pixel / span;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || nx >= span || ny < 0 || ny >= rows) continue;
                    int next = ny * span + nx;
                    if (ink[next]) { ink[next] = false; queue[tail++] = next; }
                }
            }
            Box box = new Box(scanLeft + minX, scanTop + minY, scanLeft + maxX + 1, scanTop + maxY + 1);
            if (box.width() >= Math.max(Math.max(3, width / 250), prefix.height() * .3)
                    && box.width() >= box.height() * 2.5
                    && box.width() <= Math.min(width / 14, Math.max(10, prefix.height() * 1.5))
                    && box.height() <= Math.max(8, height / 150)
                    && tail >= box.width() * box.height() * .55
                    && box.centerY() >= prefix.top && box.centerY() <= prefix.bottom
                    && isolatedGrayDash(image, box, row))
                dashes.add(box);
        }
        dashes.sort(Comparator.comparingInt(b -> b.left));
        // Build a complete local run before exposing its count. Distant runs cannot extend it.
        for (Box first : dashes) {
            if (first.left - prefix.right > Math.max(20, prefix.height() * 1.4)) break;
            // Validate only letters BEFORE the first dash. Including a merged box's pale
            // dashes here could incorrectly turn dark typed letters into a gray prefix.
            int glyphState = glyphState(image, prefix, first, row);
            if (glyphState == 0) continue;
            List<Box> run = new ArrayList<>(); run.add(first);
            for (Box dash : dashes) {
                if (dash.left <= first.left || Math.abs(dash.centerY() - first.centerY())
                        > Math.max(3, prefix.height() * .15)) continue;
                Box previous = run.get(run.size() - 1);
                int gap = dash.left - previous.right;
                if (gap < 1 || gap > Math.max(dash.width(), previous.width()) * 2.8
                        || dash.width() > first.width() * 1.8 || dash.width() < first.width() * .55) break;
                run.add(dash);
            }
            if (run.size() >= 2) return new Hint(run, glyphState < 0);
        }
        return new Hint(Collections.emptyList(), false);
    }

    /** A long neutral line just below the hint identifies the input's horizontal bounds. */
    static Box inputField(Pixels image, Box hint) {
        int[] row = new int[image.width()];
        int top = Math.max(0, hint.bottom + 4);
        int bottom = Math.min(image.height(), hint.bottom + Math.max(24, (int) (hint.height() * .85)));
        for (int y = top; y < bottom; y++) {
            image.row(y, row);
            int background = background(row), start = -1;
            for (int x = 0; x <= row.length; x++) {
                boolean ink = x < row.length && grayInk(row[x], background);
                if (ink && start < 0) start = x;
                if (!ink && start >= 0) {
                    if (x - start >= Math.max(image.width() / 10, hint.height() * 4)
                            && start <= hint.left - Math.max(5, hint.height() * .2)
                            && x >= hint.right - 3) return new Box(start, y, x, y + 1);
                    start = -1;
                }
            }
        }
        return null;
    }

    /** Black glyph antialiasing has pale fragments bordering a dark/colored glyph core. */
    private static boolean isolatedGrayDash(Pixels image, Box box, int[] row) {
        for (int y = Math.max(0, box.top - 2); y < Math.min(image.height(), box.bottom + 2); y++) {
            image.row(y, row);
            for (int x = Math.max(0, box.left - 2); x < Math.min(image.width(), box.right + 2); x++) {
                int p = row[x];
                if (Math.min(red(p), Math.min(green(p), blue(p))) < 115 || !neutral(p)) return false;
            }
        }
        return true;
    }

    /** 1 = pale original, -1 = confidently dark typed glyphs, 0 = insufficient evidence. */
    private static int glyphState(Pixels image, Box hint, Box first, int[] row) {
        int left = Math.max(0, hint.left), right = Math.min(image.width(), first.left);
        int top = Math.max(0, hint.top), bottom = Math.min(image.height(), hint.bottom);
        int pale = 0, dark = 0, glyphRight = left, glyphTop = bottom, glyphBottom = top;
        int darkRight = left, darkTop = bottom, darkBottom = top;
        for (int y = top; y < bottom; y++) {
            image.row(y, row);
            int background = background(row);
            for (int x = left; x < right; x++) {
                int p = row[x];
                if (grayInk(p, background)) {
                    pale++;
                    glyphRight = Math.max(glyphRight, x + 1);
                    glyphTop = Math.min(glyphTop, y);
                    glyphBottom = Math.max(glyphBottom, y + 1);
                } else if (Math.min(red(p), Math.min(green(p), blue(p))) < 115) {
                    dark++;
                    darkRight = Math.max(darkRight, x + 1);
                    darkTop = Math.min(darkTop, y);
                    darkBottom = Math.max(darkBottom, y + 1);
                }
            }
        }
        if (pale >= 4 && pale > dark * 2.5
                && glyphBottom - glyphTop > first.height() * 2.5
                && first.left - glyphRight <= Math.max(20, hint.height() * 1.4)) return 1;
        if (dark >= 4 && dark > pale * 2.5
                && darkBottom - darkTop > first.height() * 2.5
                && first.left - darkRight <= Math.max(20, hint.height() * 1.4)) return -1;
        return 0;
    }

    private static int background(int[] row) {
        int[] histogram = new int[256];
        for (int x = 0; x < row.length; x += 4) {
            int p = row[x], r = red(p);
            if (r >= 200 && neutral(p)) histogram[r]++;
        }
        int best = 255;
        for (int value = 200; value < 256; value++) if (histogram[value] > histogram[best]) best = value;
        return best;
    }
    private static boolean grayInk(int p, int background) {
        return red(p) >= 115 && red(p) <= background - 12 && neutral(p);
    }
    private static boolean neutral(int p) {
        return Math.abs(red(p) - green(p)) < 15 && Math.abs(red(p) - blue(p)) < 15;
    }
    private static int red(int p) { return (p >>> 16) & 255; }
    private static int green(int p) { return (p >>> 8) & 255; }
    private static int blue(int p) { return p & 255; }
}
