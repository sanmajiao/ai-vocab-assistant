package com.sanmajiao.aivocab;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Small, draggable manual controls. No capture or recognition occurs in this class. */
public final class OverlayController {
    public interface Listener {
        void onRecognize();
        void onRetry();
        void onStop();
        void onSettings();
    }

    private final Context context;
    private final Listener listener;
    private final WindowManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private LinearLayout content;
    private LinearLayout header;
    private LinearLayout buttons;
    private TextView answerView;
    private TextView resultLabel;
    private TextView detailView;
    private TextView collapseView;
    private ScrollView resultScroll;
    private Button recognizeButton;
    private Button retryButton;
    private Button copyButton;
    private WindowManager.LayoutParams params;
    private boolean attached;
    private boolean collapsed;
    private boolean busy;
    private boolean destroyed;
    private String answer = "";
    private int expandedHeight;
    private int screenWidth;
    private int screenHeight;
    private final ComponentCallbacks configurationCallback = new ComponentCallbacks() {
        @Override public void onConfigurationChanged(Configuration configuration) {
            run(() -> {
                if (destroyed || params == null || manager == null) return;
                sizeWindow();
                updateLayout();
            });
        }
        @Override public void onLowMemory() { }
    };

    public OverlayController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        manager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.context.registerComponentCallbacks(configurationCallback);
    }

    public void show() { run(this::attach); }

    /** Temporarily remove the entire overlay before the service captures a frame. */
    public void hide() { run(this::detach); }

    public void restore() { run(this::attach); }

    public void destroy() {
        run(() -> {
            destroyed = true;
            detach();
            context.unregisterComponentCallbacks(configurationCallback);
            main.removeCallbacksAndMessages(null);
        });
    }

    public void setResult(String result, String detail) {
        run(() -> {
            ensureView();
            if (destroyed) return;
            busy = false;
            answer = result == null ? "" : result.trim();
            resultLabel.setText("上次识别结果 · 换题后点识别");
            String display = answer.replaceFirst("^第\\s*(\\d+)\\s*项", "第 $1 项（从上到下）");
            answerView.setText(answer.isEmpty() ? "未得到候选" : display);
            String safeDetail = detail == null ? "请核对题目和候选后自行作答。" : detail;
            String duplicate = "完整候选：\n" + answer;
            if (!answer.isEmpty() && safeDetail.startsWith(duplicate))
                safeDetail = safeDetail.substring(duplicate.length()).trim();
            detailView.setText(safeDetail);
            resultScroll.post(() -> resultScroll.scrollTo(0, 0));
            updateButtons();
        });
    }

    public void setBusy(String message) {
        run(() -> {
            ensureView();
            if (destroyed) return;
            busy = true;
            answer = "";
            resultLabel.setText("本次识别 · 请稍候");
            answerView.setText("正在识别…");
            detailView.setText(message == null ? "请稍候。" : message);
            resultScroll.scrollTo(0, 0);
            updateButtons();
        });
    }

    public void setError(String message) {
        run(() -> {
            ensureView();
            if (destroyed) return;
            busy = false;
            answer = "";
            resultLabel.setText("本次识别 · 未完成");
            answerView.setText("本次识别未完成");
            detailView.setText(message == null ? "请核对当前页面，再点击重试。" : message);
            resultScroll.scrollTo(0, 0);
            updateButtons();
        });
    }

    private void ensureView() {
        if (root != null || destroyed) return;
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), 0, dp(8), dp(7));
        root.setBackground(UiTheme.panel(context, UiTheme.SURFACE, 18, true));
        root.setElevation(dp(8));

        header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView handle = label("词汇助手 · 拖动", 12);
        handle.setTextColor(UiTheme.MUTED);
        header.addView(handle, new LinearLayout.LayoutParams(0, -1, 1));
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int originX, originY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX(); downY = event.getRawY();
                        originX = params.x; originY = params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = originX + Math.round(event.getRawX() - downX);
                        params.y = originY + Math.round(event.getRawY() - downY);
                        clampPosition();
                        updateLayout();
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default: return false;
                }
            }
        });
        header.addView(headerAction("设置", listener::onSettings));
        collapseView = headerAction("收起", () -> {
            collapsed = !collapsed;
            content.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            collapseView.setText(collapsed ? "展开" : "收起");
            sizeWindow();
            updateLayout();
        });
        header.addView(collapseView);
        header.addView(headerAction("关闭", listener::onStop));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(32)));

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        ScrollView detailScroll = new ScrollView(context);
        resultScroll = detailScroll;
        detailScroll.setFillViewport(false);
        LinearLayout resultBody = new LinearLayout(context);
        resultBody.setOrientation(LinearLayout.VERTICAL);
        resultLabel = label("手动识别 · 核对后自行答题", 10);
        resultLabel.setTextColor(UiTheme.MUTED);
        resultBody.addView(resultLabel);
        answerView = label("等待手动识别", 20);
        answerView.setTextColor(UiTheme.INDIGO);
        answerView.setTextIsSelectable(false);
        resultBody.addView(answerView, new LinearLayout.LayoutParams(-1, -2));
        detailView = label("切换到题目后，点击“已切换·识别”。候选需要自行核对。", 12);
        detailView.setTextColor(UiTheme.MUTED);
        detailView.setGravity(Gravity.TOP);
        resultBody.addView(detailView);
        detailScroll.addView(resultBody);
        content.addView(detailScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        buttons = new LinearLayout(context);
        recognizeButton = action("已切换·识别", listener::onRecognize);
        UiTheme.button(recognizeButton, true);
        recognizeButton.setMinHeight(0);
        recognizeButton.setMinimumHeight(0);
        retryButton = action("重试", listener::onRetry);
        copyButton = action("复制", this::copyAnswer);
        buttons.addView(recognizeButton, controlRow(1.8f));
        buttons.addView(retryButton, controlRow(1));
        buttons.addView(copyButton, controlRow(1));
        content.addView(buttons, new LinearLayout.LayoutParams(-1, dp(42)));
        params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = dp(12);
        params.y = dp(70);
        updateButtons();
    }

    private void sizeWindow() {
        Point size = new Point();
        manager.getDefaultDisplay().getRealSize(size);
        screenWidth = Math.max(1, size.x);
        screenHeight = Math.max(1, size.y);
        // Fixed outer height enforces the cap even when the answer/detail is very long.
        expandedHeight = Math.max(1, Math.min(dp(220), screenHeight / 3));
        params.width = Math.max(1, Math.min(dp(350), screenWidth - dp(16)));
        params.height = collapsed ? Math.min(expandedHeight, dp(36)) : expandedHeight;
        // Reserve explicit space for the controls on short landscape screens.
        int headerHeight = Math.max(1, Math.min(dp(32), expandedHeight / 5));
        int buttonsHeight = Math.max(1, Math.min(dp(42), expandedHeight / 3));
        header.setLayoutParams(new LinearLayout.LayoutParams(-1, headerHeight));
        buttons.setLayoutParams(new LinearLayout.LayoutParams(-1, buttonsHeight));
        clampPosition();
    }

    private void clampPosition() {
        params.x = Math.max(0, Math.min(params.x, Math.max(0, screenWidth - params.width)));
        params.y = Math.max(0, Math.min(params.y, Math.max(0, screenHeight - params.height)));
    }

    private void attach() {
        if (destroyed || manager == null) return;
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "悬浮窗权限未开启或已撤销，请返回设置页授权。", Toast.LENGTH_LONG).show();
            return;
        }
        ensureView();
        sizeWindow();
        try {
            if (attached) manager.updateViewLayout(root, params);
            else {
                manager.addView(root, params);
                attached = true;
            }
        } catch (RuntimeException error) {
            attached = false;
            Toast.makeText(context, "悬浮窗无法显示，请检查权限并重新开启助手。", Toast.LENGTH_LONG).show();
        }
    }

    private void detach() {
        if (!attached || root == null || manager == null) return;
        try { manager.removeViewImmediate(root); }
        catch (RuntimeException ignored) { }
        attached = false;
    }

    private void updateLayout() {
        if (!attached) return;
        try { manager.updateViewLayout(root, params); }
        catch (RuntimeException error) { detach(); }
    }

    private void updateButtons() {
        recognizeButton.setEnabled(!busy);
        retryButton.setEnabled(!busy);
        copyButton.setEnabled(!busy && !answer.isEmpty());
        recognizeButton.setAlpha(busy ? .55f : 1f);
        retryButton.setAlpha(busy ? .55f : 1f);
        copyButton.setAlpha(copyButton.isEnabled() ? 1f : .45f);
    }

    private void copyAnswer() {
        if (busy || answer.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("词汇候选", answer));
        Toast.makeText(context, "已复制候选，请核对后手动输入。", Toast.LENGTH_SHORT).show();
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(5), dp(2), dp(5), dp(2));
        return view;
    }

    private TextView headerAction(String text, Runnable callback) {
        TextView view = label(text, 12);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(UiTheme.INDIGO);
        view.setMinWidth(dp(42));
        view.setLayoutParams(new LinearLayout.LayoutParams(-2, -1));
        view.setOnClickListener(v -> callback.run());
        return view;
    }

    private Button action(String text, Runnable callback) {
        Button view = new Button(context);
        view.setText(text);
        view.setTextSize(12);
        UiTheme.button(view, false);
        view.setMinHeight(0);
        view.setMinimumHeight(0);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setPadding(dp(2), 0, dp(2), 0);
        view.setOnClickListener(v -> { if (!busy) callback.run(); });
        return view;
    }

    private LinearLayout.LayoutParams controlRow(float weight) {
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(0, -1, weight);
        row.leftMargin = dp(2);
        row.rightMargin = dp(2);
        return row;
    }

    private void run(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
