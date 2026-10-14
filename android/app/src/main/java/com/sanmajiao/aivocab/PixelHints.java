package com.sanmajiao.aivocab;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local visual evidence only: gray dash geometry and green glyphs, no semantic guessing. */
final class PixelHints {
    private static final Pattern PREFIX = Pattern.compile("([A-Za-z]{1,15})(?:\\s*[_–—-]){0,30}");
    private PixelHints() { }

    static boolean green(Bitmap image, int l, int t, int r, int b) {
        if (image == null || image.isRecycled()) return false;
        int green = 0, ink = 0;
        for (int y = Math.max(0, t); y < Math.min(b, image.getHeight()); y += 2)
            for (int x = Math.max(0, l); x < Math.min(r, image.getWidth()); x += 2) {
                int p = image.getPixel(x, y), red = Color.red(p), g = Color.green(p), blue = Color.blue(p);
                if (Math.min(red, Math.min(g, blue)) < 235) ink++;
                if (g > 90 && g > red + 25 && g > blue + 15) green++;
            }
        return green >= 4 && green > ink * .22;
    }

    static List<QuestionParser.OcrLine> supplement(Bitmap image, List<QuestionParser.OcrLine> original) {
        if (image == null || image.isRecycled()) return new ArrayList<>(original);
        return supplement(new GrayDashGeometry.Pixels() {
            public int width() { return image.getWidth(); }
            public int height() { return image.getHeight(); }
            public void row(int y, int[] row) { image.getPixels(row, 0, row.length, 0, y, row.length, 1); }
        }, original);
    }

    /** Ordinary JVM tests supply actual pixels, without Android Bitmap stubs. */
    static List<QuestionParser.OcrLine> supplement(GrayDashGeometry.Pixels pixels,
                                                 List<QuestionParser.OcrLine> original) {
        List<QuestionParser.OcrLine> result = new ArrayList<>(original);
        try {
            Question raw = QuestionParser.parse(original);
            // Pixel completion must never turn a recognized option/grid question into a blank.
            if (raw != null && !"blank".equals(raw.kind)) return result;
            List<GrayDashGeometry.Box> consumed = new ArrayList<>();
            for (QuestionParser.OcrLine line : original) {
                List<QuestionParser.OcrLine> words = line.words.isEmpty() ? Collections.singletonList(line) : line.words;
                for (QuestionParser.OcrLine prefix : words) {
                    Matcher parsed = PREFIX.matcher(prefix.text.trim());
                    if (!parsed.matches() || prefix.highlighted) continue;
                    String letters = parsed.group(1);
                    boolean alreadyCounted = false;
                    for (GrayDashGeometry.Box box : consumed)
                        if (prefix.left < box.right && prefix.right > box.left
                                && prefix.top < box.bottom && prefix.bottom > box.top) alreadyCounted = true;
                    if (alreadyCounted) continue;
                    GrayDashGeometry.Box prefixBox = new GrayDashGeometry.Box(
                            prefix.left, prefix.top, prefix.right, prefix.bottom);
                    GrayDashGeometry.Box fieldBox = GrayDashGeometry.inputField(pixels, prefixBox);
                    // A black English word alone is not an input. Require its visible field.
                    if (fieldBox == null) continue;
                    GrayDashGeometry.Hint geometry = GrayDashGeometry.readHint(pixels, prefixBox, fieldBox.right);
                    List<GrayDashGeometry.Box> dashes = geometry.dashes;
                    if (dashes.isEmpty()) continue;
                    GrayDashGeometry.Box last = dashes.get(dashes.size() - 1);
                    String marks = String.join("", Collections.nCopies(dashes.size(), "-"));
                    // Typed letters do not reveal the original prefix or total length.
                    String field = geometry.typed ? "____" : letters + marks;
                    QuestionParser.OcrLine corrected = new QuestionParser.OcrLine(field,
                            prefix.left, prefix.top, last.right, prefix.bottom, false, Collections.emptyList());
                    List<QuestionParser.OcrLine> correctedWords = new ArrayList<>();
                    for (QuestionParser.OcrLine word : line.words) {
                        if (word == prefix) correctedWords.add(corrected);
                        else if (!(word.text.matches("[\\s_–—-]+") && word.left >= prefix.left - 3
                                && word.right <= last.right + 3
                                && Math.abs(word.center() - prefix.center()) < prefix.height())) correctedWords.add(word);
                    }
                    QuestionParser.OcrLine hint = new QuestionParser.OcrLine(field,
                            prefix.left, prefix.top, last.right, prefix.bottom, false, correctedWords);
                    String fieldPattern = "(?<![A-Za-z])" + Pattern.quote(letters)
                            + "(?![A-Za-z])(?:\\s*[_–—-]){0,30}";
                    if (!line.text.replaceFirst(fieldPattern, "").trim().isEmpty()) {
                        String replacement = line.text.replaceFirst(fieldPattern, field);
                        hint = new QuestionParser.OcrLine(replacement, line.left, line.top,
                                Math.max(line.right, last.right), line.bottom,
                                line.highlighted, correctedWords);
                    }
                    result.remove(line);
                    for (int k = result.size() - 1; k >= 0; k--) {
                        QuestionParser.OcrLine old = result.get(k);
                        if (old.left >= prefix.left - 3 && old.right <= last.right + 3
                                && Math.abs(old.center() - prefix.center()) < prefix.height()) result.remove(k);
                    }
                    result.add(hint);
                    consumed.add(new GrayDashGeometry.Box(prefix.left, prefix.top, last.right, prefix.bottom));
                }
            }
            result.sort(Comparator.comparingInt((QuestionParser.OcrLine line) -> line.top).thenComparingInt(line -> line.left));
        } catch (RuntimeException | OutOfMemoryError ignored) { /* Original OCR remains usable. */ }
        return result;
    }
}
