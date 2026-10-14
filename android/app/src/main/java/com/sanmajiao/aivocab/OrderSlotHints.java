package com.sanmajiao.aivocab;

import android.graphics.Bitmap;
import java.util.List;

/** Attach a slot count only to an already parsed word-order question. */
final class OrderSlotHints {
    private OrderSlotHints() { }

    static Question attach(Bitmap image, List<QuestionParser.OcrLine> lines, Question question) {
        if (question == null || !"order".equals(question.kind) || image == null || image.isRecycled()) return question;
        return attach(new OrderSlotGeometry.Pixels() {
            public int width() { return image.getWidth(); }
            public int height() { return image.getHeight(); }
            public void row(int y, int[] row) { image.getPixels(row, 0, row.length, 0, y, row.length, 1); }
        }, lines, question);
    }

    /** JVM replay can supply real JPEG pixels without using an Android Bitmap stub. */
    static Question attach(OrderSlotGeometry.Pixels image, List<QuestionParser.OcrLine> lines, Question question) {
        if (question == null || !"order".equals(question.kind) || image == null || lines == null) return question;
        String meaning = chinese(question.prompt);
        if (meaning.isEmpty()) return question.withSlotCount(0);
        QuestionParser.OcrLine prompt = null;
        for (QuestionParser.OcrLine line : lines) {
            String text = chinese(line.text);
            if (!text.isEmpty() && meaning.contains(text) && text.length() >= Math.min(2, meaning.length())
                    && (prompt == null || line.top < prompt.top)) prompt = line;
        }
        if (prompt == null) return question.withSlotCount(0);
        int toolbarBottom = -1;
        for (QuestionParser.OcrLine line : lines) {
            if (line.bottom < prompt.top && line.text.contains("返回") && line.left < image.width() * .35)
                toolbarBottom = Math.max(toolbarBottom, line.bottom);
        }
        int count = OrderSlotGeometry.count(image, toolbarBottom, prompt.top,
                Math.max(1, prompt.bottom - prompt.top), question.options.size());
        return question.withSlotCount(count);
    }

    private static String chinese(String text) {
        return text == null ? "" : text.replaceAll("[^\\u4e00-\\u9fff]", "");
    }
}
