package com.sanmajiao.aivocab;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** Private opt-in diagnostics. Only the logging toggle is read; no API key or network request. */
public final class DiagnosticsActivity extends Activity {
    private Bitmap preview;
    private ImageView image;
    private HorizontalScrollView imageScroll;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!CaptureDiagnostics.enabled(this)) {
            CaptureDiagnostics.clear(this);
            Toast.makeText(this, "诊断已关闭，请在开发者选项中开启。", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        // Only this diagnostics window is capturable; MainActivity protects its key fields.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(24), dp(16), dp(24));
        body.setBackgroundColor(Color.rgb(245, 247, 251));
        scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        addText(body, "本次截图与识别诊断", 24);
        addText(body, "本页可截图或复制文字。截图只在本机读取；诊断可能包含题目和屏幕内容，分享前请核对。", 14);
        addButton(body, "返回设置", this::finish);
        final String details = readDetails();
        addButton(body, "复制诊断文字", () -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("本次识别诊断", details));
                Toast.makeText(this, "诊断文字已复制", Toast.LENGTH_SHORT).show();
            }
        });
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            preview = BitmapFactory.decodeFile(CaptureDiagnostics.image(this).getAbsolutePath(), options);
        } catch (RuntimeException | OutOfMemoryError error) {
            addText(body, "原图暂时无法加载，请先复制下方诊断文字。", 14);
        }
        if (preview != null) {
            addText(body, "缓存原图 " + preview.getWidth() + " × " + preview.getHeight()
                    + "；若原图本身已模糊或遮挡，放大不能恢复被遮住的文字。", 14);
            LinearLayout controls = new LinearLayout(this);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            Button fit = new Button(this); fit.setText("适应宽度");
            Button zoom = new Button(this); zoom.setText("放大 2 倍");
            controls.addView(fit, new LinearLayout.LayoutParams(0, -2, 1));
            controls.addView(zoom, new LinearLayout.LayoutParams(0, -2, 1));
            body.addView(controls);
            imageScroll = new HorizontalScrollView(this);
            image = new ImageView(this);
            image.setContentDescription("本次手动捕获的原始屏幕截图");
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            image.setImageBitmap(preview);
            imageScroll.addView(image);
            body.addView(imageScroll, new LinearLayout.LayoutParams(-1, -2));
            fit.setOnClickListener(v -> resizePreview(1));
            zoom.setOnClickListener(v -> resizePreview(2));
            imageScroll.post(() -> resizePreview(1));
        } else {
            addText(body, "暂无缓存原图。请返回原题并点击悬浮窗识别，然后打开本页。", 14);
        }
        TextView raw = addText(body, details, 14);
        raw.setTextIsSelectable(true);
    }

    private String readDetails() {
        try (FileInputStream in = new FileInputStream(CaptureDiagnostics.text(this))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048]; int count;
            while (out.size() < 150000 && (count = in.read(buffer, 0,
                    Math.min(buffer.length, 150000 - out.size()))) > 0) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "暂无手动识别记录。请返回原题并点击悬浮窗识别后查看。";
        }
    }

    private void resizePreview(int scale) {
        if (preview == null || imageScroll == null || imageScroll.getWidth() <= 0) return;
        int width = imageScroll.getWidth() * scale;
        int height = Math.max(1, (int) Math.round(width * (double) preview.getHeight() / preview.getWidth()));
        image.setLayoutParams(new HorizontalScrollView.LayoutParams(width, height));
        imageScroll.scrollTo(0, 0);
    }

    private TextView addText(LinearLayout body, String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(Color.rgb(25, 35, 55));
        view.setPadding(0, dp(8), 0, dp(12)); body.addView(view); return view;
    }

    private void addButton(LinearLayout body, String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false);
        button.setOnClickListener(v -> action.run()); body.addView(button);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onResume() {
        super.onResume();
        if (!CaptureDiagnostics.enabled(this)) {
            CaptureDiagnostics.clear(this);
            finish();
        }
    }

    @Override protected void onDestroy() {
        if (image != null) image.setImageDrawable(null);
        if (preview != null) { preview.recycle(); preview = null; }
        super.onDestroy();
    }
}
