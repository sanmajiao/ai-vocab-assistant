package com.sanmajiao.aivocab;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local help only: no credentials, captured images, OCR or API requests are read. */
public final class ScreenProtectionHelpActivity extends Activity {
    private final List<LinearLayout> sections = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout body = column();
        body.setPadding(dp(20), dp(24), dp(20), dp(24));
        body.setBackgroundColor(Color.rgb(245, 247, 251));
        scroll.addView(body);
        setContentView(scroll);

        addText(body, "先解除屏幕防护", 26);
        addText(body, "屏幕识别需要清晰的原图。若手机的共享防护把题目打码、模糊或变黑，先按下面的品牌指南处理，再开启助手。", 16);
        addText(body, "使用前检查\n1. 找到系统提供的共享防护开关，按本机提示临时关闭。\n2. 返回助手，停止后重新开启，重新授权共享。\n3. 在自己的词汇题目页点一次识别，打开“本次截图与OCR原文”查看原图。\n4. 原图文字清晰后再继续使用；原图仍打码、全黑或看不清时，停止助手。反复重试或请求答案不能恢复被遮住的文字。", 15);
        addText(body, "没有相关防护功能的手机，可直接检查原图。开关无法关闭、设备受单位管理，或题目应用本身禁止截图时，本产品无法在该页面使用屏幕识别，请停止，避免继续消耗接口费用。", 15);
        addButton(body, "打开系统设置（可搜索关键词）", () -> openSettings(Settings.ACTION_SETTINGS));
        addButton(body, "打开安全设置", () -> openSettings(Settings.ACTION_SECURITY_SETTINGS));
        addButton(body, "查看手机型号与系统版本", () -> openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS));
        addButton(body, "返回助手", this::finish);
        addText(body, "按品牌展开指南", 20);
        addText(body, "设置名称随型号、地区和系统版本变化。按钮只打开设置，由你自己选择；助手不会替你关闭任何保护。", 14);

        String brand = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        boolean xiaomi = brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco");
        LinearLayout mi = addSection(body, "小米 / Redmi / POCO · HyperOS / MIUI", xiaomi, state);
        addText(mi, "已核实的操作：打开“设置”，在顶部搜索“电诈防护”，进入后找到“屏幕共享防护”，将它关闭。找不到时再搜索“屏幕防护”。具体入口以手机显示为准。", 15);
        addText(mi, "若共享时状态栏显示“防护中”，可以下拉通知，进入“屏幕防护中”；仅当系统确实提供“继续共享”时，按提示选择。完成后重新开启助手，并检查本次原图。", 15);
        addText(mi, "Redmi K80 Pro / HyperOS 3.0.304 已有用户实机确认：关闭防护后题目画面清晰。其他型号、版本及 POCO 地区系统仍需各自检查，不保证都有此开关。", 14);
        addSource(mi, "EV录屏官方：小米录屏模糊的处理方法", "https://www.ieway.cn/help/desc-evcapture-android-2133.html");
        addSource(mi, "ToDesk 官方常见问题（辅助参考）", "https://www.todesk.com/faq/114.html");

        boolean colorOs = brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme");
        LinearLayout oppo = addSection(body, "OPPO / 一加 / realme", colorOs, state);
        addText(oppo, "ColorOS、realme UI 的入口可能不同，目前没有核实可适用于所有机型的关闭路径。请打开系统设置，搜索“屏幕共享”“屏幕防护”“隐私保护”；若找到相关开关，先阅读本机说明，仅调整共享画面的防护。", 15);
        addText(oppo, "OPPO 官方说明：安全键盘、私密保险箱等隐私功能可能限制截图或让录屏显示黑画面。先离开这些敏感页面，再回到普通题目测试。没有可关闭的共享防护，或原图仍被遮住，请停止使用，并携型号与系统版本向对应厂商咨询。", 15);
        addSource(oppo, "OPPO 官方：隐私功能与录屏限制", "https://www.oppo.com/en/app-privacy/");
        addSource(oppo, "一加官方客户服务", "https://service.oneplus.com/cn");
        addSource(oppo, "realme 官方安全隐私说明", "https://privacy.realme.com/cn/security");

        LinearLayout vivo = addSection(body, "vivo / iQOO · OriginOS", brand.contains("vivo") || brand.contains("iqoo"), state);
        addText(vivo, "vivo 官方安全白皮书说明，部分 OriginOS 共享场景会隐藏通知、小窗等隐私内容；这不代表所有题目都受保护，也不代表一定有通用关闭开关。", 15);
        addText(vivo, "在系统设置搜索“屏幕共享”“隐私防护”“屏幕保护”。如本机有相关开关，按说明调整后重新开启助手，检查原图。找不到或不能关闭时，记录型号、OriginOS 版本，向 vivo / iQOO 官方客服询问；原图仍打码则不能使用本产品识别。", 15);
        addSource(vivo, "vivo 官方：千镜安全架构技术白皮书", "https://privacycenterstatic.vivo.com.cn/privacycenter/83b83d7f-670e-4a81-a2d1-23bf85ab4477-%E5%8D%83%E9%95%9C%E5%AE%89%E5%85%A8%E6%9E%B6%E6%9E%84%E6%8A%80%E6%9C%AF%E7%99%BD%E7%9A%AE%E4%B9%A61008.pdf");
        addSource(vivo, "vivo 官方服务与咨询", "https://www.vivo.com.cn/service");

        LinearLayout huawei = addSection(body, "华为 / 荣耀", brand.contains("huawei") || brand.contains("honor"), state);
        addText(huawei, "华为官方说明：输入密码的页面会禁止录屏并显示黑画面。请先退出密码、支付等敏感页面。华为与荣耀的系统各自不同，未核实跨型号通用的共享防护关闭路径。", 15);
        addText(huawei, "在本机设置搜索“屏幕共享”“隐私保护”“录屏”。只按系统实际提供的选项操作；找不到、开关被限制或题目原图仍黑屏时，停止助手，并向各自厂商咨询型号与版本。请先确认本机能够安装、运行 Android 应用。", 15);
        addSource(huawei, "华为官方：密码界面录屏黑屏说明", "https://consumer.huawei.com/cn/support/content/zh-cn00406940/");
        addSource(huawei, "荣耀官方设备使用指南与咨询", "https://www.honor.com/cn/support/knowledge-page/");

        LinearLayout samsung = addSection(body, "Samsung · One UI", brand.contains("samsung"), state);
        addText(samsung, "Samsung 官方说明：部分页面和应用不允许截图、录屏，功能也因型号及版本而异。目前未核实 One UI 所有型号通用的解除开关。", 15);
        addText(samsung, "可在设置搜索“屏幕共享”“录屏”“隐私”，检查本机是否提供共享防护选项。如果系统是 Android 15 或更新版本，可阅读下方 Android 指南。设置中没有相关开关，或题目原图仍被屏蔽，请停止助手，并向 Samsung 支持咨询。", 15);
        addSource(samsung, "Samsung 官方：截图、录屏及限制", "https://www.samsung.com/us/support/answer/ANS10001616/");

        LinearLayout android = addSection(body, "Android 15 及更新版本 · 测试选项", false, state);
        addText(android, "Android 官方提供开发者测试选项“Disable screen share protections”（中文名称可能类似“停用屏幕共享保护”）。厂商可能调整或不提供该选项；它不能保证解除题目应用自身的截图限制或单位管理策略。", 15);
        addText(android, "仅在临时测试自己的普通词汇页面时：\n1. 在“关于手机”查找“版本号 / Build number”，按官方或本机说明启用开发者选项。原生 Android 通常需连续点版本号 7 次；厂商系统位置可能不同。\n2. 打开“开发者选项”，查找上述英文名称；如果确有该开关，打开它表示临时停用共享保护。\n3. 重新开启助手并检查原图；找不到或原图仍被遮住就停止。\n4. 测试结束关闭该选项，恢复共享保护。无需开启 USB 调试。", 15);
        addText(android, "官方将此选项限定为演示或测试用途，不建议长期保持开启。系统自带录屏可能不受同样限制，因此系统录屏清晰不能代替助手原图检查。", 14);
        addButton(android, "打开开发者选项（如已启用）", () -> openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        addSource(android, "Android 官方：开发者选项与共享保护", "https://developer.android.com/studio/debug/dev-options");
        addSource(android, "Android 官方：Android 15 共享保护变化", "https://developer.android.com/about/versions/15/behavior-changes-all");

        LinearLayout other = addSection(body, "其他品牌 / 找不到开关 / 仍然打码", false, state);
        addText(other, "先查看“关于手机”，记下型号与系统版本；在设置搜索“屏幕共享”“屏幕防护”“隐私保护”。仅当系统明确提供相关开关时，按手机说明处理。可以向厂商询问：“普通页面在第三方屏幕共享里被打码，是否有用户可调整的共享保护设置？”", 15);
        addText(other, "只关闭你能正常操作的可选共享保护。无法关闭、单位管控或题目应用强制禁止截图时，本产品没有可用的屏幕识别来源；请停止助手。不要为此开启无关权限、修改系统或反复请求答案。", 15);

        addText(body, "只识别自己的普通词汇题目；共享时离开密码、支付和私人信息页面。使用结束先停止助手，再恢复临时关闭的可选保护。保护设置变化后，下一次使用仍要检查原图。", 14);
        addText(body, "资料核对：2026-10-14。各品牌指南按来源已证实的范围编写，最终以本机设置及原图为准。", 12);
    }

    private LinearLayout addSection(LinearLayout parent, String title, boolean defaultExpanded, Bundle state) {
        int index = sections.size();
        LinearLayout details = column();
        details.setPadding(dp(12), 0, dp(12), dp(10));
        boolean expanded = state == null ? defaultExpanded : state.getBoolean("section_" + index, defaultExpanded);
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        Button toggle = new Button(this);
        toggle.setAllCaps(false);
        toggle.setText((expanded ? "收起 · " : "展开 · ") + title);
        toggle.setOnClickListener(view -> {
            boolean show = details.getVisibility() != View.VISIBLE;
            details.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText((show ? "收起 · " : "展开 · ") + title);
        });
        parent.addView(toggle, row());
        parent.addView(details, row());
        sections.add(details);
        return details;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        for (int index = 0; index < sections.size(); index++) {
            state.putBoolean("section_" + index, sections.get(index).getVisibility() == View.VISIBLE);
        }
        super.onSaveInstanceState(state);
    }

    private void openSettings(String action) {
        if (tryOpenResolved(new Intent(action))) return;
        if (!Settings.ACTION_SETTINGS.equals(action) && tryOpenResolved(new Intent(Settings.ACTION_SETTINGS))) {
            Toast.makeText(this, "已打开设置首页，请搜索指南中的关键词。", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "无法直接打开，请手动进入手机设置并搜索关键词。", Toast.LENGTH_LONG).show();
    }

    private boolean tryOpenResolved(Intent intent) {
        try {
            if (intent.resolveActivity(getPackageManager()) == null) return false;
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
            return false;
        }
    }

    private void addSource(LinearLayout body, String label, String url) {
        addButton(body, label + " ↗", () -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            try {
                // Package visibility can hide a browser from resolveActivity; launching an
                // implicit web intent is still permitted, so try it if resolution is inconclusive.
                intent.resolveActivity(getPackageManager());
                startActivity(intent);
            } catch (ActivityNotFoundException | SecurityException error) {
                Toast.makeText(this, "暂无可用浏览器，请通过厂商官网查看帮助。", Toast.LENGTH_LONG).show();
            }
        });
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private void addText(LinearLayout body, String content, int size) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(25, 35, 55));
        view.setPadding(0, dp(8), 0, dp(10));
        body.addView(view, row());
    }

    private void addButton(LinearLayout body, String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setOnClickListener(v -> action.run());
        body.addView(view, row());
    }

    private LinearLayout.LayoutParams row() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
