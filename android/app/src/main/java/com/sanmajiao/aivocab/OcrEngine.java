package com.sanmajiao.aivocab;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Bundled on-device models. Screenshots never go to an OCR service. */
public final class OcrEngine implements AutoCloseable {
    public interface Callback {
        void onResult(List<QuestionParser.OcrLine> lines);
        void onError(String message);
    }

    private final TextRecognizer latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final TextRecognizer chinese = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Pass> passes = new ArrayList<>();
    private final List<CardRetry> cardRetries = new ArrayList<>();
    private boolean closed;
    private boolean diagnosticsEnabled;
    private String rawSnapshot = "";
    public String rawText() { return rawSnapshot; }
    public void setDiagnosticsEnabled(boolean enabled) {
        diagnosticsEnabled=enabled;
        if(!enabled) rawSnapshot="";
    }

    public void recognize(Bitmap bitmap, Callback callback) {
        recognizePass(bitmap, false, callback);
    }

    /** One optional local retry, bounded to 4 seconds; keeps pale original prefixes visible. */
    public void recognizeEnhanced(Bitmap bitmap, Callback callback) {
        recognizePass(bitmap, true, callback);
    }

    private void recognizePass(Bitmap source, boolean enhance, Callback callback) {
        if (closed) {
            callback.onError("文字识别已停止。");
            return;
        }
        final Bitmap input;
        final float sx;
        final float sy;
        try {
            float scale = enhance ? Math.min(1.5f, 3000f / Math.max(source.getWidth(), source.getHeight()))
                    : Math.min(1f, 2600f / Math.max(source.getWidth(), source.getHeight()));
            int w = Math.max(1, Math.round(source.getWidth() * scale));
            int h = Math.max(1, Math.round(source.getHeight() * scale));
            input = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            if (enhance) {
                ColorMatrix gray = new ColorMatrix();
                gray.setSaturation(0);
                float contrast = 6f;
                float offset = 255f * (1f - contrast);
                ColorMatrix boost = new ColorMatrix(new float[]{
                        contrast, 0, 0, 0, offset,
                        0, contrast, 0, 0, offset,
                        0, 0, contrast, 0, offset,
                        0, 0, 0, 1, 0});
                boost.postConcat(gray);
                paint.setColorFilter(new ColorMatrixColorFilter(boost));
            }
            // Preserve the complete image: cropping could cut off pale prefix/length hints.
            new Canvas(input).drawBitmap(source, null, new Rect(0, 0, w, h), paint);
            sx = source.getWidth() / (float) w;
            sy = source.getHeight() / (float) h;
        } catch (RuntimeException | OutOfMemoryError error) {
            callback.onError("屏幕图片过大，无法识别。请关闭其他应用后重试。");
            return;
        }
        Pass pass = new Pass(input, source, sx, sy, callback);
        passes.add(pass);
        main.postDelayed(() -> {
            if (!pass.delivered && !closed) {
                pass.delivered = true;
                if(diagnosticsEnabled) rawSnapshot = "中文模型：\n" + CaptureDiagnostics.ocr(pass.chineseLines) + "\n拉丁模型：\n" + CaptureDiagnostics.ocr(pass.latinLines);
                List<QuestionParser.OcrLine> partial = recognitionLines(pass.source, merge(pass.chineseLines, pass.latinLines));
                if (partial.isEmpty()) callback.onError(enhance ? "增强识别未发现更多文字。" : "文字识别超时，请重试。");
                else callback.onResult(partial);
            }
            // ML Kit tasks still own input until both finish, even after the UI timeout.
        }, enhance ? 4000 : 8000);
        InputImage image = InputImage.fromBitmap(input, 0);
        run(latin, image, pass, false);
        run(chinese, image, pass, true);
    }

    private final class Pass {
        final Bitmap input;
        final Bitmap source;
        final float sx;
        final float sy;
        final Callback callback;
        List<QuestionParser.OcrLine> latinLines = Collections.emptyList();
        List<QuestionParser.OcrLine> chineseLines = Collections.emptyList();
        int remaining = 2;
        int failures;
        boolean delivered;
        Pass(Bitmap input, Bitmap source, float sx, float sy, Callback callback) {
            this.input = input;
            this.source = source;
            this.sx = sx;
            this.sy = sy;
            this.callback = callback;
        }
        void finish() {
            remaining--;
            if (remaining != 0) return;
            input.recycle();
            passes.remove(this);
            if (delivered || closed) return;
            delivered = true;
            if (failures == 2) callback.onError("本地文字识别失败，请重试。");
            else {
                if(diagnosticsEnabled) rawSnapshot = "中文模型：\n" + CaptureDiagnostics.ocr(chineseLines) + "\n拉丁模型：\n" + CaptureDiagnostics.ocr(latinLines);
                completeRecognition(source, merge(chineseLines, latinLines), callback);
            }
        }
    }

    private void run(TextRecognizer recognizer, InputImage image, Pass pass, boolean isChinese) {
        try {
            recognizer.process(image).addOnCompleteListener(task -> {
                if (task.isSuccessful() && !pass.delivered && !closed) {
                    List<QuestionParser.OcrLine> lines = extract(task.getResult(), pass.sx, pass.sy, pass.source);
                    if (isChinese) pass.chineseLines = lines;
                    else pass.latinLines = lines;
                } else pass.failures++;
                pass.finish();
            });
        } catch (RuntimeException error) {
            pass.failures++;
            pass.finish();
        }
    }

    private static List<QuestionParser.OcrLine> extract(Text text, float sx, float sy, Bitmap source) {
        return extract(text,sx,sy,source,0,0);
    }

    private static List<QuestionParser.OcrLine> extract(Text text, float sx, float sy, Bitmap source,int dx,int dy) {
        List<QuestionParser.OcrLine> result = new ArrayList<>();
        if (text == null) return result;
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null || line.getText().trim().isEmpty()) continue;
                List<QuestionParser.OcrLine> words = new ArrayList<>();
                for (Text.Element element : line.getElements()) {
                    Rect word = element.getBoundingBox();
                    if (word != null) {
                        int l = dx+Math.round(word.left * sx), t = dy+Math.round(word.top * sy);
                        int r = dx+Math.round(word.right * sx), b = dy+Math.round(word.bottom * sy);
                        words.add(new QuestionParser.OcrLine(element.getText(), l, t, r, b,
                                PixelHints.green(source, l, t, r, b), Collections.emptyList()));
                    }
                }
                result.add(new QuestionParser.OcrLine(line.getText(),
                        dx+Math.round(box.left * sx), dy+Math.round(box.top * sy),
                        dx+Math.round(box.right * sx), dy+Math.round(box.bottom * sy), false, words));
            }
        }
        return result;
    }

    private static List<QuestionParser.OcrLine> recognitionLines(Bitmap source,
                                                               List<QuestionParser.OcrLine> raw) {
        List<ChoiceCardGeometry.Card> cards=ChoiceCardHints.cards(source,raw);
        List<QuestionParser.OcrLine> confirmed=ChoiceCardHints.annotate(raw,cards);
        // Physical slots are stronger evidence than a three-item text-only guess.
        for(QuestionParser.OcrLine line:confirmed) if(line.cardCount>0) return confirmed;
        // Keep real choice/grid words intact. Pixel hints only supplement uncertain fill fields.
        try {
            Question question = QuestionParser.parse(raw);
            if (question != null && !"blank".equals(question.kind)) return raw;
        } catch (IllegalArgumentException ignored) { /* Geometry may still recover a visible fill hint. */ }
        return PixelHints.supplement(source, raw);
    }

    /** A single bounded on-device crop retry. The bitmap remains owned until callback. */
    private void completeRecognition(Bitmap source,List<QuestionParser.OcrLine> raw,Callback callback) {
        List<ChoiceCardGeometry.Card> cards=ChoiceCardHints.cards(source,raw);
        List<QuestionParser.OcrLine> annotated=ChoiceCardHints.annotate(raw,cards);
        boolean confirmed=false;
        for(QuestionParser.OcrLine line:annotated) if(line.cardCount>0) confirmed=true;
        if(confirmed && QuestionParser.parse(annotated)!=null) {callback.onResult(annotated);return;}
        if(!confirmed) {callback.onResult(recognitionLines(source,raw));return;}
        try {
            ChoiceCardGeometry.Card first=cards.get(0),last=cards.get(cards.size()-1);
            int left=Math.max(0,first.left-8),top=Math.max(0,first.top-8);
            int right=Math.min(source.getWidth(),first.right+8),bottom=Math.min(source.getHeight(),last.bottom+8);
            int w=right-left,h=bottom-top;
            float scale=Math.min(2f,2600f/Math.max(w,h));
            Bitmap crop=Bitmap.createBitmap(Math.round(w*scale),Math.round(h*scale),Bitmap.Config.ARGB_8888);
            new Canvas(crop).drawBitmap(source,new Rect(left,top,right,bottom),
                    new Rect(0,0,crop.getWidth(),crop.getHeight()),new Paint(Paint.FILTER_BITMAP_FLAG));
            CardRetry retry=new CardRetry(callback,w/(float)crop.getWidth(),h/(float)crop.getHeight());
            cardRetries.add(retry);
            main.postDelayed(()->retry.deliver(annotated),2500);
            try {
                chinese.process(InputImage.fromBitmap(crop,0)).addOnCompleteListener(task->{
                    try {
                    if(!retry.delivered && !closed && task.isSuccessful()) {
                        List<QuestionParser.OcrLine> local=extract(task.getResult(),retry.sx,retry.sy,source,left,top);
                        if(diagnosticsEnabled) rawSnapshot+="\n选项局部补读：\n"+CaptureDiagnostics.ocr(local);
                        List<QuestionParser.OcrLine> candidate=new ArrayList<>();
                        for(QuestionParser.OcrLine line:raw) if(line.center()<first.top || line.center()>last.bottom) candidate.add(line);
                        candidate.addAll(local);
                        List<QuestionParser.OcrLine> result=ChoiceCardHints.annotate(candidate,cards);
                        retry.deliver(QuestionParser.parse(result)!=null?result:annotated);
                    } else retry.deliver(annotated);
                    } catch(RuntimeException | OutOfMemoryError error) {retry.deliver(annotated);}
                    finally {crop.recycle();cardRetries.remove(retry);}
                });
            } catch(RuntimeException error) {retry.deliver(annotated);crop.recycle();cardRetries.remove(retry);}
        } catch(RuntimeException | OutOfMemoryError error) {callback.onResult(annotated);}
    }

    private final class CardRetry {
        final Callback callback; final float sx,sy; boolean delivered;
        CardRetry(Callback callback,float sx,float sy) {
            this.callback=callback;this.sx=sx;this.sy=sy;
        }
        void deliver(List<QuestionParser.OcrLine> result) {
            if(delivered || closed) return; delivered=true;callback.onResult(result);
        }
    }

    private static List<QuestionParser.OcrLine> merge(List<QuestionParser.OcrLine> cn,
                                                     List<QuestionParser.OcrLine> en) {
        List<QuestionParser.OcrLine> result = new ArrayList<>(cn);
        for (QuestionParser.OcrLine line : en) {
            boolean chineseOverlap = false;
            for (QuestionParser.OcrLine existing : result) {
                if (overlap(existing, line) && containsHan(existing.text)) {
                    chineseOverlap = true;
                    break;
                }
            }
            if (chineseOverlap) continue;
            for (int i = result.size() - 1; i >= 0; i--) {
                QuestionParser.OcrLine old = result.get(i);
                // A short Latin result must not erase a wider CN line (often the pale prefix).
                if (overlap(old, line) && (old.text.equalsIgnoreCase(line.text)
                        || line.right - line.left >= (old.right - old.left) * .85)) result.remove(i);
                else if (overlap(old, line)) chineseOverlap = true;
            }
            if (chineseOverlap) continue;
            result.add(line);
        }
        result.sort(Comparator.comparingInt((QuestionParser.OcrLine line) -> line.top)
                .thenComparingInt(line -> line.left));
        return result;
    }

    private static boolean containsHan(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    private static boolean overlap(QuestionParser.OcrLine a, QuestionParser.OcrLine b) {
        int w = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
        int h = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        long area = (long) w * h;
        long small = Math.min((long) Math.max(1, a.right - a.left) * Math.max(1, a.bottom - a.top),
                (long) Math.max(1, b.right - b.left) * Math.max(1, b.bottom - b.top));
        return area > small * 0.45;
    }

    @Override public void close() {
        closed = true;
        rawSnapshot="";
        for(CardRetry retry:new ArrayList<>(cardRetries)) if(!retry.delivered) {
            retry.delivered=true;retry.callback.onError("文字识别已停止。");
        }
        for (Pass pass : new ArrayList<>(passes)) {
            if (!pass.delivered) {
                pass.delivered = true;
                pass.callback.onError("文字识别已停止。");
            }
        }
        latin.close();
        chinese.close();
    }
}
