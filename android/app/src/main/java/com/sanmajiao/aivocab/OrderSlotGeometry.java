package com.sanmajiao.aivocab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Visible, empty word slots only. No OCR strings or translation-based length guesses. */
final class OrderSlotGeometry {
    interface Pixels {
        int width();
        int height();
        void row(int y, int[] pixels);
    }

    private static final class Segment {
        final int left, top, right, bottom, ink;
        Segment(int left, int top, int right, int bottom, int ink) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom; this.ink = ink;
        }
        int width() { return right - left; }
        int height() { return bottom - top; }
        double centerY() { return (top + bottom) / 2.0; }
    }

    private OrderSlotGeometry() { }

    /** Scan between the navigation toolbar and the Chinese meaning. Zero is unknown. */
    static int count(Pixels image, int toolbarBottom, int promptTop, int promptHeight, int optionCount) {
        if (image == null || optionCount < 1 || promptHeight < 1) return 0;
        int width = image.width(), height = image.height();
        if (width < 80 || height < 80 || toolbarBottom < 0 || promptTop <= toolbarBottom
                || promptTop > height) return 0;
        int font = Math.max(8, promptHeight);
        int left = Math.max(2, width / 25), right = width - left;
        int top = Math.max(toolbarBottom + Math.max(4, font / 2), promptTop - width * 2 / 3);
        int bottom = Math.min(height, promptTop - Math.max(3, font / 3));
        int span = right - left, rows = bottom - top;
        if (rows < font || span <= 0) return 0;
        boolean[] ink = new boolean[span * rows];
        int[] row = new int[width];
        for (int y = top; y < bottom; y++) {
            image.row(y, row);
            for (int x = left; x < right; x++) ink[(y - top) * span + x - left] = lineInk(row[x]);
        }
        List<Segment> segments = new ArrayList<>();
        int[] queue = new int[ink.length];
        int maxThickness = Math.max(3, font / 5);
        for (int seed = 0; seed < ink.length; seed++) {
            if (!ink[seed]) continue;
            int head = 0, tail = 0, minX = span, minY = rows, maxX = 0, maxY = 0;
            queue[tail++] = seed; ink[seed] = false;
            while (head < tail) {
                int p = queue[head++], x = p % span, y = p / span;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || nx >= span || ny < 0 || ny >= rows) continue;
                    int next = ny * span + nx;
                    if (ink[next]) { ink[next] = false; queue[tail++] = next; }
                }
            }
            Segment line = new Segment(left + minX, top + minY, left + maxX + 1, top + maxY + 1, tail);
            if (line.width() >= Math.max(width / 10, font * 2)
                    && line.width() <= width * .45 && line.height() <= maxThickness
                    && line.width() >= line.height() * 16
                    && line.ink >= line.width() * line.height() * .55
                    && line.top > top + 1 && line.bottom < bottom - 1) segments.add(line);
        }
        if (segments.isEmpty() || segments.size() > optionCount) return 0;
        segments.sort(Comparator.comparingDouble(Segment::centerY).thenComparingInt(s -> s.left));
        List<List<Segment>> bands = new ArrayList<>();
        for (Segment line : segments) {
            if (bands.isEmpty() || Math.abs(bands.get(bands.size() - 1).get(0).centerY() - line.centerY())
                    > maxThickness + 2) bands.add(new ArrayList<>());
            bands.get(bands.size() - 1).add(line);
        }
        if (bands.size() > 3) return 0;
        double firstWidth = segments.get(0).width();
        int firstCount = bands.get(0).size();
        double previousY = -1;
        for (List<Segment> band : bands) {
            band.sort(Comparator.comparingInt(s -> s.left));
            if (band.size() > 4 || band.size() > firstCount) return 0;
            Segment first = band.get(0), last = band.get(band.size() - 1);
            if (Math.abs((first.left + last.right) / 2.0 - width / 2.0) > width * .12) return 0;
            if (previousY >= 0 && (first.centerY() - previousY < font * 1.2
                    || first.centerY() - previousY > width * .24)) return 0;
            previousY = first.centerY();
            Segment previous = null;
            for (Segment line : band) {
                if (line.width() < firstWidth * .75 || line.width() > firstWidth * 1.25) return 0;
                if (previous != null && (line.left - previous.right < 2
                        || line.left - previous.right > Math.max(font * 1.5, firstWidth * .22))) return 0;
                // A filled slot retains its underline. Its visible letters make the total uncertain.
                if (occupied(image, line, font, row)) return 0;
                previous = line;
            }
        }
        double gap = promptTop - segments.get(segments.size() - 1).bottom;
        if (gap < font * .5 || gap > Math.max(font * 3, width * .13)) return 0;
        if (segments.size() == 1 && firstWidth > width * .34) return 0;
        return segments.size();
    }

    private static boolean occupied(Pixels image, Segment line, int font, int[] row) {
        int content = 0;
        for (int y = Math.max(0, line.top - font * 2); y < line.top - Math.max(2, font / 8); y++) {
            image.row(y, row);
            for (int x = line.left; x < line.right; x++) {
                int p = row[x], r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
                if (Math.min(r, Math.min(g, b)) < 110 || Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 40)
                    if (++content > Math.max(5, font / 2)) return true;
            }
        }
        return false;
    }

    private static boolean lineInk(int pixel) {
        int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
        int min = Math.min(r, Math.min(g, b)), max = Math.max(r, Math.max(g, b));
        return max - min <= 18 && min >= 65 && max <= 215;
    }
}
