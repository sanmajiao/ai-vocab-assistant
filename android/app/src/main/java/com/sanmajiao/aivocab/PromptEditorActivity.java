package com.sanmajiao.aivocab;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.graphics.Typeface;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/** Developer-only business instruction editor. Never reads or displays the API key. */
public final class PromptEditorActivity extends Activity {
    private static final String[] KINDS = {"choice", "multi-choice", "order", "blank"};
    private static final String[] LABELS = {"单选：双向翻译 / 划线词语境义", "多选：词语搭配", "排序：中文含义 → 英文词块", "填空：补全英文词"};
    private final String[] drafts = new String[KINDS.length];
    private final String[] saved = new String[KINDS.length];
    private SecureSettings settings;
    private EditText editor;
    private TextView state;
    private TextView defaultText;
    private int current = -1;
    private boolean changing;

    @Override public void onCreate(Bundle instance) {
        super.onCreate(instance);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        settings = new SecureSettings(this);
        if (!settings.developerMode()) { finish(); return; }
        String[] restored = instance == null ? null : instance.getStringArray("drafts");
        for (int i = 0; i < KINDS.length; i++) {
            String custom = settings.savedPromptOverride(KINDS[i]);
            saved[i] = custom.isEmpty() ? AnswerRequest.defaultInstructions(KINDS[i]) : custom;
            drafts[i] = restored != null && restored.length == KINDS.length && restored[i] != null
                    ? restored[i] : saved[i];
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(20), dp(20), dp(24));
        body.setBackgroundColor(UiTheme.BACKGROUND);
        scroll.addView(body);
        setContentView(scroll);
        body.addView(button("‹ 返回设置", false, v -> leave()), row());
        TextView title = text("按题型编辑提示词", 25, UiTheme.INK);
        title.setTypeface(null, Typeface.BOLD);
        body.addView(title);
        body.addView(text("题型在手机本地判断。保存后，下次手动识别会使用对应提示词，重启仍有效，无需重新安装。", 14, UiTheme.MUTED));

        LinearLayout scope = card(body, UiTheme.SOFT_TEAL);
        scope.addView(text("只编辑解题要求", 18, UiTheme.INK));
        scope.addView(text("输出格式、选项序号和结果校验由应用固定，无需填写 JSON。单选同时覆盖英文选中文、中文选英文，以及结合上下文判断划线词的含义。", 14, UiTheme.MUTED));
        scope.addView(text("自定义提示词会发送到答案服务，请勿填写密钥或私人信息。关闭开发者选项会停用自定义内容，重新开启后可继续使用。", 12, UiTheme.MUTED));

        LinearLayout content = card(body, UiTheme.SURFACE);
        content.addView(text("选择题型", 15, UiTheme.INK));
        Spinner selector = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        selector.setAdapter(adapter);
        content.addView(selector, row());
        state = text("", 13, UiTheme.TEAL);
        content.addView(state);
        editor = new EditText(this);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setGravity(android.view.Gravity.TOP);
        editor.setMinLines(7);
        editor.setTextSize(15);
        editor.setTextColor(UiTheme.INK);
        editor.setPadding(dp(12), dp(12), dp(12), dp(12));
        editor.setBackground(UiTheme.panel(this, UiTheme.BACKGROUND, 12, true));
        editor.setSaveEnabled(false);
        editor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        content.addView(editor, row());
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!changing && current >= 0) { drafts[current] = s.toString(); updateState(); }
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        content.addView(button("保存当前题型", true, v -> saveCurrent()), row());
        content.addView(button("恢复当前题型默认提示词", false, v -> new AlertDialog.Builder(this)
                .setTitle("恢复当前题型默认值？")
                .setMessage("只清除当前题型的自定义提示词和未保存编辑，其他题型不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复默认", (dialog, which) -> restoreCurrent()).show()), row());
        Button defaultToggle = button("查看内置默认提示词  ＋", false, null);
        content.addView(defaultToggle, row());
        defaultText = text("", 13, UiTheme.MUTED);
        defaultText.setTextIsSelectable(true);
        defaultText.setVisibility(View.GONE);
        content.addView(defaultText);
        defaultToggle.setOnClickListener(v -> {
            boolean open = defaultText.getVisibility() != View.VISIBLE;
            defaultText.setVisibility(open ? View.VISIBLE : View.GONE);
            defaultToggle.setText(open ? "收起内置默认提示词  −" : "查看内置默认提示词  ＋");
        });
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                showKind(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        int initial = instance == null ? 2 : instance.getInt("kind", 2);
        if (initial < 0 || initial >= KINDS.length) initial = 2;
        selector.setSelection(initial);
        showKind(initial);
    }

    @Override protected void onResume() {
        super.onResume();
        if (settings != null && !settings.developerMode()) finish();
    }

    @Override protected void onSaveInstanceState(Bundle instance) {
        if (editor != null && current >= 0) drafts[current] = editor.getText().toString();
        instance.putStringArray("drafts", drafts);
        instance.putInt("kind", current);
        super.onSaveInstanceState(instance);
    }

    private void showKind(int position) {
        if (position == current) return;
        if (current >= 0) drafts[current] = editor.getText().toString();
        current = position;
        changing = true;
        editor.setText(drafts[current]);
        changing = false;
        defaultText.setText(AnswerRequest.defaultInstructions(KINDS[current]));
        updateState();
    }

    private void updateState() {
        boolean dirty = !drafts[current].equals(saved[current]);
        boolean custom = !settings.savedPromptOverride(KINDS[current]).isEmpty();
        int count = drafts[current].length();
        state.setText((custom ? "自定义已启用" : "正在使用内置默认值")
                + (dirty ? " · 编辑未保存" : " · 已保存") + "\n"
                + count + " / " + SecureSettings.MAX_PROMPT_CHARACTERS
                + " 字符 · 切换题型会保留草稿，识别仅使用已保存内容");
        state.setTextColor(count > SecureSettings.MAX_PROMPT_CHARACTERS ? UiTheme.INDIGO : UiTheme.TEAL);
    }

    private void saveCurrent() {
        if (!settings.developerMode()) { finish(); return; }
        String value = editor.getText().toString();
        try {
            if (value.trim().isEmpty() || value.equals(AnswerRequest.defaultInstructions(KINDS[current])))
                settings.clearPromptOverride(KINDS[current]);
            else settings.savePromptOverride(KINDS[current], value);
            String custom = settings.savedPromptOverride(KINDS[current]);
            saved[current] = custom.isEmpty() ? AnswerRequest.defaultInstructions(KINDS[current]) : custom;
            drafts[current] = saved[current];
            changing = true;
            editor.setText(drafts[current]);
            changing = false;
            updateState();
        } catch (IllegalArgumentException | IllegalStateException error) {
            state.setText(error.getMessage());
        }
    }

    private void restoreCurrent() {
        if (!settings.developerMode()) { finish(); return; }
        try {
            settings.clearPromptOverride(KINDS[current]);
            saved[current] = AnswerRequest.defaultInstructions(KINDS[current]);
            drafts[current] = saved[current];
            changing = true;
            editor.setText(drafts[current]);
            changing = false;
            updateState();
        } catch (IllegalArgumentException | IllegalStateException error) { state.setText(error.getMessage()); }
    }

    @Override public void onBackPressed() { leave(); }

    private void leave() {
        if (editor != null && current >= 0) drafts[current] = editor.getText().toString();
        for (int i = 0; i < KINDS.length; i++) {
            if (drafts[i] != null && !drafts[i].equals(saved[i])) {
                new AlertDialog.Builder(this).setTitle("有未保存的提示词")
                        .setMessage("离开会放弃所有题型的未保存编辑，已保存内容不会变化。")
                        .setNegativeButton("继续编辑", null)
                        .setPositiveButton("放弃并返回", (dialog, which) -> finish()).show();
                return;
            }
        }
        finish();
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(6), 0, dp(10));
        return view;
    }

    private Button button(String value, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(value); UiTheme.button(button, primary); button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout card(LinearLayout parent, int color) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        card.setBackground(UiTheme.panel(this, color, 22, color == UiTheme.SURFACE));
        LinearLayout.LayoutParams params = row(); params.topMargin = dp(8); params.bottomMargin = dp(12);
        parent.addView(card, params);
        return card;
    }

    private LinearLayout.LayoutParams row() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8); return params;
    }

    private int dp(int value) { return UiTheme.dp(this, value); }
}
