package com.sanmajiao.aivocab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Only the latest manual screenshot/OCR, app-private cache; no credentials or API responses. */
final class CaptureDiagnostics {
    static boolean enabled(Context context) { return new SecureSettings(context).diagnosticsEnabled(); }
    static File image(Context context) { return new File(context.getCacheDir(), "last-manual-screen.png"); }
    static File text(Context context) { return new File(context.getCacheDir(), "last-manual-ocr.txt"); }
    static String frame(Bitmap bitmap) {
        int dark = 0, samples = 0, min = 255, max = 0;
        for (int y = 0; y < bitmap.getHeight(); y += Math.max(1, bitmap.getHeight() / 70))
            for (int x = 0; x < bitmap.getWidth(); x += Math.max(1, bitmap.getWidth() / 50)) {
                int p = bitmap.getPixel(x, y), bright = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3;
                samples++; if (bright < 12) dark++; min = Math.min(min, bright); max = Math.max(max, bright);
            }
        return "截图 " + bitmap.getWidth() + " × " + bitmap.getHeight() + "；采样亮度 " + min + "–" + max
                + "；黑色像素 " + (samples == 0 ? 0 : dark * 100 / samples) + "%"
                + (samples > 0 && dark > samples * .97 ? "\n画面接近全黑，可能是受保护页面/授权范围错误，请重新共享整个屏幕。" : "");
    }
    static void saveImage(Context context, Bitmap bitmap) {
        if (!enabled(context)) { clear(context); return; }
        try (FileOutputStream out = new FileOutputStream(image(context))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        catch (Exception ignored) { }
    }
    static void saveText(Context context, String value) {
        if (!enabled(context)) { clear(context); return; }
        try (FileOutputStream out = new FileOutputStream(text(context))) { out.write(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ignored) { }
    }
    static String ocr(List<QuestionParser.OcrLine> lines) {
        StringBuilder result = new StringBuilder();
        for (QuestionParser.OcrLine line : lines) result.append('[').append(line.left).append(',').append(line.top)
                .append(',').append(line.right).append(',').append(line.bottom).append("] ").append(line.text).append('\n');
        return result.toString();
    }
    static void clear(Context context) { image(context).delete(); text(context).delete(); }
}
