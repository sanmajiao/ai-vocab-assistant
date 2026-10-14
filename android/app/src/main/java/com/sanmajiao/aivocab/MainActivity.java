package com.sanmajiao.aivocab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Switch;

import java.util.Locale;

/** Settings and explicit permission flow; screen capture is never triggered here. */
public final class MainActivity extends Activity {
    private static final int REQUEST_OVERLAY = 101;
    private static final int REQUEST_CAPTURE = 102;
    private SecureSettings settings;
    private EditText keyInput;
    private EditText modelInput;
    private EditText prefixInput;
    private EditText lengthInput;
    private EditText promptInput;
    private TextView status;
    private LinearLayout apiContent;
    private Button apiToggle;
    private Switch developerSwitch;
    private Switch diagnosticsSwitch;
    private boolean pendingStart;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        settings = new SecureSettings(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(22), dp(20), dp(24));
        body.setBackgroundColor(UiTheme.BACKGROUND);
        scroll.addView(body);
        setContentView(scroll);

        TextView title = text("词汇助手", 28, UiTheme.INK);
        title.setTypeface(null, Typeface.BOLD);
        body.addView(title);
        try {
            body.addView(text("版本 " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName,
                    12, UiTheme.MUTED));
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        body.addView(text("让每次词汇练习更轻松", 15, UiTheme.MUTED));

        LinearLayout hero = card(body, UiTheme.SOFT_TEAL);
        TextView heroTitle = text("准备好，开始识别", 22, UiTheme.INK);
        heroTitle.setTypeface(null, Typeface.BOLD);
        hero.addView(heroTitle);
        hero.addView(text("打开悬浮助手，回到题目，再手动点击识别。", 14, UiTheme.MUTED));
        Button start = button("开启悬浮助手  →", v -> beginStart());
        start.setTextSize(18);
        UiTheme.button(start, true);
        hero.addView(start, row());
        status = text("准备就绪 · 开启时需要悬浮窗与屏幕共享权限", 13, UiTheme.TEAL);
        hero.addView(status);
        hero.addView(button("停止助手", v -> stopAssistant()), row());

        LinearLayout steps = card(body, UiTheme.SURFACE);
        steps.addView(text("使用步骤", 18, UiTheme.INK));
        steps.addView(text("① 首次使用，在下方配置 DeepSeek API。\n② 如手机有屏幕共享防护，先解除题目画面的模糊或打码保护。\n③ 开启悬浮助手并授权，回到题目点击“已切换·识别”。\n④ 核对候选后自行答题；换题再识别。", 14, UiTheme.MUTED));
        steps.addView(button("屏幕防护与手机品牌指南  ↗", v ->
                startActivity(new Intent(this, ScreenProtectionHelpActivity.class))), row());
        steps.addView(text("若无法解除防护，或共享画面仍被遮住，本产品无法识别该页面，请停止助手。使用结束后可恢复系统防护。", 13, UiTheme.MUTED));

        LinearLayout hints = fold(body, "补充填空提示（可选）");
        hints.addView(text("识别不准时补充中文释义、原始前缀或总长度；只用于下一次手动识别，读取后自动清除。", 13, UiTheme.MUTED));
        promptInput = input("中文释义，例如 大学注册主任", false);
        promptInput.setMaxLines(3);
        hints.addView(promptInput, row());
        prefixInput = input("原始前缀，例如 imp", true);
        prefixInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        hints.addView(prefixInput, row());
        lengthInput = input("单词总长度 1–60，未知留空", true);
        lengthInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        hints.addView(lengthInput, row());
        try {
            prefixInput.setText(settings.hintPrefix());
            promptInput.setText(settings.hintPrompt());
            int length = settings.hintLength();
            lengthInput.setText(length > 0 ? String.valueOf(length) : "");
        } catch (Exception ignored) { }
        hints.addView(button("下次识别使用补充提示", v -> saveHints()), row());

        LinearLayout apiCard = card(body, UiTheme.SURFACE);
        apiToggle = button("DeepSeek API 配置  ＋", null);
        apiCard.addView(apiToggle, row());
        apiContent = new LinearLayout(this);
        apiContent.setOrientation(LinearLayout.VERTICAL);
        apiContent.setVisibility(View.GONE);
        apiCard.addView(apiContent);
        apiToggle.setOnClickListener(v -> toggle(apiContent, apiToggle, "DeepSeek API 配置"));
        apiContent.addView(text("API Key", 15, UiTheme.INK));
        keyInput = new EditText(this);
        keyInput.setSingleLine(true);
        keyInput.setHint("输入自己的 API Key");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setSaveEnabled(false);
        keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        styleInput(keyInput);
        apiContent.addView(keyInput, row());
        apiContent.addView(text("密钥加密保存在本机，不显示在悬浮窗中。", 12, UiTheme.MUTED));

        apiContent.addView(text("模型", 15, UiTheme.INK));
        modelInput = input("deepseek-flash", true);
        modelInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        apiContent.addView(modelInput, row());
        try {
            keyInput.setText(settings.key());
            String model = settings.model();
            modelInput.setText(model == null || model.trim().isEmpty() ? "deepseek-flash" : model);
        } catch (Exception error) {
            keyInput.setText("");
            modelInput.setText("deepseek-flash");
        }

        apiContent.addView(button("保存 API 设置", v -> saveSettings()), row());
        apiContent.addView(button("清除本机密钥", v -> new AlertDialog.Builder(this)
                .setTitle("清除设置")
                .setMessage("清除本机保存的 API Key、模型、补充提示、自定义提示词及开发者设置，并停止助手。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (dialog, which) -> clearSettings()).show()), row());

        LinearLayout privacy = fold(body, "关于、隐私与使用限制");
        privacy.addView(text("截图在手机本地识别，提取的题目文字与补充提示发送至 DeepSeek。助手不会自动切题、点击或提交答案；候选需自行核对。屏幕共享授权会话持续到停止助手或系统撤销授权。请识别前离开私人信息页面。", 13, UiTheme.MUTED));
        privacy.addView(text("文字识别由 Google ML Kit 在手机本地完成，ML Kit 不向 Google 上传识别的图片或文字结果；其 SDK 会发送性能和使用指标，并可能获取组件更新。诊断默认关闭；仅主动开启开发者选项及本机诊断后，缓存最近一次截图与 OCR，关闭即清除。", 13, UiTheme.MUTED));
        TextView links = text("项目与 MIT 许可证：\nhttps://github.com/sanmajiao/ai-vocab-assistant\n\nML Kit 条款与隐私：\nhttps://developers.google.com/ml-kit/terms", 13, UiTheme.INDIGO);
        Linkify.addLinks(links, Linkify.WEB_URLS);
        links.setMovementMethod(LinkMovementMethod.getInstance());
        privacy.addView(links);
        LinearLayout developer = fold(body, "开发者选项");
        developer.addView(text("排查问题时再开启；平时无需保留截图和识别日志。", 13, UiTheme.MUTED));
        developerSwitch = new Switch(this);
        developerSwitch.setText("开启开发者选项");
        developerSwitch.setTextColor(UiTheme.INK);
        developerSwitch.setChecked(settings.developerMode());
        developer.addView(developerSwitch, row());
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setVisibility(settings.developerMode() ? View.VISIBLE : View.GONE);
        developer.addView(tools);
        tools.addView(button("按题型编辑提示词  ↗", v -> {
            if (settings.developerMode()) startActivity(new Intent(this, PromptEditorActivity.class));
        }), row());
        tools.addView(text("修改保存后用于下一次手动识别；关闭开发者选项会停用自定义提示词，但保留内容。", 12, UiTheme.MUTED));
        diagnosticsSwitch = new Switch(this);
        diagnosticsSwitch.setText("保存本次截图与 OCR 诊断");
        diagnosticsSwitch.setTextColor(UiTheme.INK);
        diagnosticsSwitch.setChecked(settings.diagnosticsEnabled());
        tools.addView(diagnosticsSwitch, row());
        LinearLayout diagnostics = new LinearLayout(this);
        diagnostics.setOrientation(LinearLayout.VERTICAL);
        diagnostics.setVisibility(settings.diagnosticsEnabled() ? View.VISIBLE : View.GONE);
        tools.addView(diagnostics);
        diagnostics.addView(text("仅本机缓存最近一次手动识别；不记录密钥或接口响应。关闭任一开关会清除缓存。", 12, UiTheme.MUTED));
        diagnostics.addView(button("查看本次截图与 OCR", v -> showDiagnostics()), row());
        diagnostics.addView(button("清除本次诊断", v -> {
            CaptureDiagnostics.clear(this);
            status.setText("已清除本机截图与识别诊断。");
        }), row());
        developerSwitch.setOnCheckedChangeListener((button, enabled) -> {
            try {
                settings.setDeveloperMode(enabled);
                tools.setVisibility(enabled ? View.VISIBLE : View.GONE);
                if (!enabled) diagnosticsSwitch.setChecked(false);
                status.setText(enabled ? "开发者选项已开启，诊断需单独开启。" : "开发者选项已关闭，自定义提示词已停用，诊断缓存已清除。");
            } catch (Exception error) { status.setText("开发者设置保存失败，请重试。"); }
        });
        diagnosticsSwitch.setOnCheckedChangeListener((button, enabled) -> {
            try {
                settings.setDiagnosticsEnabled(enabled);
                diagnostics.setVisibility(enabled ? View.VISIBLE : View.GONE);
                status.setText(enabled ? "本机诊断已开启，下次手动识别开始记录。" : "本机诊断已关闭，缓存已清除。");
            } catch (Exception error) { status.setText("诊断设置保存失败，请重试。"); }
        });
        if (!settings.developerMode() || !settings.diagnosticsEnabled()) CaptureDiagnostics.clear(this);
        pendingStart = savedInstanceState != null && savedInstanceState.getBoolean("pendingOverlayPermission", false);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("pendingOverlayPermission", pendingStart);
        super.onSaveInstanceState(state);
    }

    private boolean saveSettings() {
        String key = keyInput.getText().toString().trim();
        String model = modelInput.getText().toString().trim();
        if (key.isEmpty()) {
            status.setText("请先输入自己的 DeepSeek API Key。");
            openApi();
            keyInput.requestFocus();
            return false;
        }
        if (model.isEmpty()) model = "deepseek-flash";
        try {
            settings.save(key, model);
            modelInput.setText(model);
            status.setText("设置已加密保存到本机。");
            return true;
        } catch (Exception error) {
            status.setText("保存失败：本机密钥存储不可用，请重试。没有开启助手。");
            return false;
        }
    }

    private void beginStart() {
        boolean saved = false;
        try {
            String key = settings.key();
            saved = !key.isEmpty() && key.equals(keyInput.getText().toString().trim())
                    && settings.model().equals(modelInput.getText().toString().trim());
        } catch (Exception ignored) { }
        if (!saved && !saveSettings()) return;
        pendingStart = true;
        if (!Settings.canDrawOverlays(this)) {
            status.setText("请允许“显示在其他应用上层”，然后返回本页。");
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), REQUEST_OVERLAY);
            } catch (Exception error) {
                pendingStart = false;
                status.setText("无法打开悬浮窗权限页面。请到系统设置 → 应用 → 特殊权限中允许悬浮窗。");
            }
        } else {
            requestCapture();
        }
    }

    private void saveHints() {
        String manualPrompt = promptInput.getText().toString().trim();
        String prefix = prefixInput.getText().toString().trim()
                .replace('\u2019', '\'').toLowerCase(Locale.ROOT);
        String lengthText = lengthInput.getText().toString().trim();
        if (prefix.length() > 60 || !prefix.matches("[a-z'\\-]*")) {
            status.setText("前缀只能包含英文字母、撇号或连字符，最多 60 个字符。");
            return;
        }
        int length = 0;
        if (!lengthText.isEmpty()) {
            try { length = Integer.parseInt(lengthText); }
            catch (NumberFormatException error) {
                status.setText("总长度请填写 1–60 的整数，未知时留空。");
                return;
            }
            if (length < 1 || length > 60) {
                status.setText("总长度必须为 1–60，未知时留空。");
                return;
            }
        }
        if (length > 0 && prefix.length() > length) {
            status.setText("原始前缀不能长于单词总长度，请核对题目。");
            return;
        }
        try {
            if (prefix.isEmpty() && length == 0 && manualPrompt.isEmpty()) {
                settings.clearHints();
                status.setText("已清除待用补充提示。下次识别将按原题文字处理。");
            } else {
                settings.saveHints(prefix, length);
                settings.saveHintPrompt(manualPrompt);
                status.setText("补充提示已保存，只用于下一次“已切换·识别”或“重试”，使用后自动清除。");
            }
            prefixInput.setText("");
            lengthInput.setText("");
            promptInput.setText("");
        } catch (Exception error) {
            status.setText("补充提示保存失败，请重试。");
        }
    }

    private void requestCapture() {
        pendingStart = false;
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) throw new IllegalStateException("No projection manager");
            status.setText("请按系统提示授权屏幕共享，建议共享整个屏幕以便切换题目；授权后仍需手动识别。");
            Intent capture = Build.VERSION.SDK_INT >= 34
                    ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    : manager.createScreenCaptureIntent();
            startActivityForResult(capture, REQUEST_CAPTURE);
        } catch (Exception error) {
            status.setText("无法请求屏幕共享权限。请检查系统是否支持屏幕捕获。");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY) {
            if (!pendingStart) return;
            if (Settings.canDrawOverlays(this)) requestCapture();
            else {
                pendingStart = false;
                status.setText("未获得悬浮窗权限，助手没有开启。可重新点击“开启悬浮助手”授权。");
            }
        } else if (requestCode == REQUEST_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                status.setText("屏幕共享授权已取消，助手没有开启。");
                return;
            }
            if (!Settings.canDrawOverlays(this)) {
                status.setText("悬浮窗权限已撤销，请重新授权后开启。");
                return;
            }
            try {
                Intent service = new Intent(this, CaptureService.class);
                service.putExtra("resultCode", resultCode);
                service.putExtra("data", data);
                startForegroundService(service);
                status.setText("已请求开启助手。请返回题目，再点击悬浮窗“已切换·识别”；授权本身不会截图。");
            } catch (Exception error) {
                status.setText("无法开启前台屏幕共享服务，请返回本页重新授权。系统可能限制了后台服务。");
            }
        }
    }

    private void stopAssistant() {
        pendingStart = false;
        try {
            Intent stop = new Intent(this, CaptureService.class);
            stop.setAction("com.sanmajiao.aivocab.STOP");
            startService(stop);
            status.setText("已发送停止请求，悬浮窗和屏幕共享将结束。");
        } catch (Exception error) {
            status.setText("停止请求未送达。可通过系统屏幕共享提示或助手通知停止。");
        }
    }

    private void clearSettings() {
        try {
            settings.clear();
            settings.clearHints();
            stopAssistant();
            keyInput.setText("");
            modelInput.setText("deepseek-flash");
            prefixInput.setText("");
            lengthInput.setText("");
            promptInput.setText("");
            CaptureDiagnostics.clear(this);
            developerSwitch.setChecked(false);
            diagnosticsSwitch.setChecked(false);
            status.setText("已清除本机密钥、模型和补充提示，并发送停止请求。");
        } catch (Exception error) {
            status.setText("清除失败：本机密钥存储不可用，请重试。");
        }
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(6), 0, dp(10));
        return view;
    }

    private void showDiagnostics() {
        if (settings.diagnosticsEnabled()) startActivity(new Intent(this, DiagnosticsActivity.class));
        else status.setText("请在开发者选项中主动开启本机诊断，再进行一次识别。");
    }

    private Button button(String value, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(value);
        UiTheme.button(view, false);
        view.setOnClickListener(listener);
        return view;
    }

    private LinearLayout.LayoutParams row() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        return params;
    }

    private LinearLayout card(LinearLayout parent, int color) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        card.setBackground(UiTheme.panel(this, color, 22, color == UiTheme.SURFACE));
        LinearLayout.LayoutParams params = row();
        params.topMargin = dp(8);
        params.bottomMargin = dp(12);
        parent.addView(card, params);
        return card;
    }

    private LinearLayout fold(LinearLayout parent, String title) {
        LinearLayout card = card(parent, UiTheme.SURFACE);
        Button toggle = button(title + "  ＋", null);
        card.addView(toggle, row());
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setVisibility(View.GONE);
        card.addView(content);
        toggle.setOnClickListener(v -> toggle(content, toggle, title));
        return content;
    }

    private void toggle(LinearLayout content, Button button, String title) {
        boolean expanded = content.getVisibility() != View.VISIBLE;
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        button.setText(title + (expanded ? "  −" : "  ＋"));
    }

    private void openApi() {
        apiContent.setVisibility(View.VISIBLE);
        apiToggle.setText("DeepSeek API 配置  −");
    }

    private EditText input(String hint, boolean singleLine) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(singleLine);
        styleInput(input);
        return input;
    }

    private void styleInput(EditText input) {
        input.setTextSize(15);
        input.setTextColor(UiTheme.INK);
        input.setHintTextColor(UiTheme.MUTED);
        input.setPadding(dp(12), dp(12), dp(12), dp(12));
        input.setBackground(UiTheme.panel(this, UiTheme.BACKGROUND, 12, true));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
