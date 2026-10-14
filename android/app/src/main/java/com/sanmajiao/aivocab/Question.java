package com.sanmajiao.aivocab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The current screen only; never a conversation or an answer history. */
public final class Question {
    public final String kind, prompt, context, prefix;
    /** Total alphabetic length; zero means unknown. */
    public final int length;
    public final List<String> options;
    /** Visible marked word or collocation base; never inferred from a translation. */
    public final String target;
    public final int minSelections;
    /** Number of visible ordering slots; zero means not confirmed. */
    public final int slotCount;

    public Question(String kind, String prompt, String context, String prefix,
                    int length, List<String> options) {
        this(kind, prompt, context, prefix, length, options, "", 0);
    }

    public Question(String kind, String prompt, String context, String prefix,
                    int length, List<String> options, String target, int minSelections) {
        this(kind, prompt, context, prefix, length, options, target, minSelections, 0);
    }

    public Question(String kind, String prompt, String context, String prefix,
                    int length, List<String> options, String target, int minSelections, int slotCount) {
        this.kind = kind;
        this.prompt = prompt == null ? "" : prompt;
        this.context = context == null ? "" : context;
        this.prefix = prefix == null ? "" : prefix;
        this.length = Math.max(0, length);
        this.target = target == null ? "" : target;
        this.minSelections = minSelections;
        this.slotCount = Math.max(0, slotCount);
        this.options = Collections.unmodifiableList(new ArrayList<>(options == null
                ? Collections.emptyList() : options));
    }

    public Question withSlotCount(int count) {
        return new Question(kind, prompt, context, prefix, length, options, target, minSelections, count);
    }

    public boolean needsReview() {
        return "blank".equals(kind) && (prefix.isEmpty() || length == 0 || context.contains("缺失位置未确认"));
    }

    public String display() {
        if (!"blank".equals(kind)) {
            StringBuilder result = new StringBuilder(prompt);
            if (!target.isEmpty()) result.append("\n目标：").append(target);
            if ("multi-choice".equals(kind)) result.append("\n多选：至少 ").append(minSelections).append(" 项");
            if ("order".equals(kind)) result.append("\n词块排序：只选必要词块，每项最多一次")
                    .append(slotCount > 0 ? " · 填入 " + slotCount + " 项" : " · 槽位数未确认");
            for (int i = 0; i < options.size(); i++)
                result.append('\n').append(i + 1).append(". ").append(options.get(i));
            return result.toString();
        }
        return context + "\n" + prompt + "\n前缀 " + (prefix.isEmpty() ? "未识别" : prefix)
                + " · 总长度 " + (length == 0 ? "未确认" : length)
                + (needsReview() ? "\n提示不完整，答案仅供核对" : "");
    }
}
