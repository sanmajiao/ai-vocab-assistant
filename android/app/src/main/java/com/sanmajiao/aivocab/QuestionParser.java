package com.sanmajiao.aivocab;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative extraction from OCR boxes. No network or Android dependencies. */
public final class QuestionParser {
    private QuestionParser() { }

    public static final class OcrLine {
        public final String text;
        public final int left, top, right, bottom;
        public final boolean highlighted;
        public final List<OcrLine> words;
        /** Zero means unconfirmed; otherwise the physical top-to-bottom card position. */
        public final int cardSlot, cardCount;
        public OcrLine(String text, int left, int top, int right, int bottom) {
            this(text, left, top, right, bottom, false, Collections.emptyList());
        }
        public OcrLine(String text, int left, int top, int right, int bottom,
                       boolean highlighted, List<OcrLine> words) {
            this(text,left,top,right,bottom,highlighted,words,0,0);
        }
        public OcrLine(String text, int left, int top, int right, int bottom,
                       boolean highlighted, List<OcrLine> words, int cardSlot, int cardCount) {
            this.text = text == null ? "" : text;
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
            this.highlighted = highlighted;
            this.words = words == null ? Collections.emptyList() : words;
            this.cardSlot=cardSlot; this.cardCount=cardCount;
        }
        int height() { return Math.max(1, bottom - top); }
        double center() { return (top + bottom) / 2.0; }
    }

    private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern HINT = Pattern.compile(
            "(?<![A-Za-z])([A-Za-z]{1,15}(?:(?=[_\\-–—])|(?=\\s+[\\-–—])))?\\s*([_\\-–—](?:\\s*[_\\-–—]){1,29})");
    private static final Pattern ENGLISH = Pattern.compile("[A-Za-z]+(?:[ '\\-][A-Za-z]+)*[.!?]?");
    // A possessive or compound belongs to one word-bank cell, not zero or
    // several cells. Spaces remain excluded so a sentence cannot count as a grid.
    private static final Pattern ORDER_WORD = Pattern.compile("[A-Za-z]+(?:['-][A-Za-z]+)*\\.?");
    // OCR may read a back-chevron as Japanese く or another Unicode symbol.
    private static final Pattern NAVIGATION = Pattern.compile(
            "[^A-Za-z0-9\\u4e00-\\u9fff]{0,6}返回(?:\\s+(?:Unit|单元|词达人|练习).*|$)", Pattern.CASE_INSENSITIVE);

    private static final class Row {
        final List<OcrLine> parts = new ArrayList<>();
        int top = Integer.MAX_VALUE, bottom;
        String text;
        void add(OcrLine line) { parts.add(line); top = Math.min(top, line.top); bottom = Math.max(bottom, line.bottom); }
        double center() { return parts.stream().mapToDouble(OcrLine::center).average().orElse(0); }
        void finish() {
            parts.sort(Comparator.comparingInt(line -> line.left));
            StringBuilder value = new StringBuilder();
            OcrLine previous = null;
            for (OcrLine part : parts) {
                String token = clean(part.text);
                boolean adjacent = previous != null && part.left - previous.right
                        <= Math.max(2, Math.min(part.height(), previous.height()) * .16)
                        && previous.text.matches("[A-Za-z]+") && token.matches("[A-Za-z]+");
                if (value.length() > 0 && !adjacent) value.append(' ');
                value.append(token); previous = part;
            }
            text = clean(value.toString());
        }
    }

    public static Question parse(List<OcrLine> input) {
        if (input == null || input.isEmpty()) return null;
        final int toolbarBottom = toolbarBottom(input);
        for(OcrLine line:input) if(line!=null && line.cardCount>0)
            return cardChoice(input,toolbarBottom);
        List<OcrLine> sorted = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (OcrLine line : input) if (line != null && line.top >= toolbarBottom && !clean(line.text).isEmpty()) {
            sorted.add(line); if (line.height() > 8) heights.add(line.height());
        }
        if (sorted.isEmpty()) return null;
        Collections.sort(heights);
        double normalHeight = heights.isEmpty() ? 20 : heights.get(heights.size() / 2);
        sorted.sort(Comparator.comparingDouble(OcrLine::center));
        List<Row> rows = new ArrayList<>();
        for (OcrLine line : sorted) {
            Row row = null;
            for (Row candidate : rows) if (Math.abs(candidate.center() - line.center()) < Math.max(7, normalHeight * .48)) {
                row = candidate; break;
            }
            if (row == null) { row = new Row(); rows.add(row); }
            row.add(line);
        }
        for (Row row : rows) row.finish();
        int startY = Integer.MIN_VALUE, endY = Integer.MAX_VALUE;
        boolean hasSubmit = false;
        for (Row row : rows) {
            if (row.text.matches("(?:提交|确认提交)")) hasSubmit = true;
            if (NAVIGATION.matcher(row.text).matches())
                startY = Math.max(startY, row.bottom);
            if (row.text.matches("(?:提交|确认提交|下一题|确定)") || keyboard(row.text)) endY = Math.min(endY, row.top);
        }
        List<Row> body = new ArrayList<>();
        for (Row row : rows) if (row.top >= startY && row.top < endY && !ignored(row.text)) body.add(row);
        if (body.isEmpty()) return null;

        Question structured = structured(body);
        if (structured != null) return structured;

        // An explicit prefix + dash run is stronger evidence than language alone.
        // Text-only boxes cannot reliably distinguish pale hints from typed letters.
        Row hintRow = null;
        Matcher hint = null;
        for (Row row : body) {
            Matcher candidate = HINT.matcher(row.text);
            if (candidate.find() && (hintRow == null || candidate.group(1) != null)) {
                hintRow = row; hint = candidate;
                if (candidate.group(1) != null) break;
            }
        }
        if (hintRow != null) {
            String prefix = hint.group(1) == null ? "" : hint.group(1).toLowerCase(Locale.ROOT);
            String marks = hint.group(2).replaceAll("\\s", "");
            int length = prefix.isEmpty() ? 0 : prefix.length() + marks.length();
            StringBuilder prompt = new StringBuilder(), context = new StringBuilder();
            for (Row row : body) {
                if (CHINESE.matcher(row.text).find()) {
                    String meaning = row.text;
                    if (row == hintRow) meaning = meaning.substring(0, hint.start()) + " " + meaning.substring(hint.end());
                    meaning = clean(meaning);
                    if (!meaning.isEmpty()) append(prompt, meaning);
                    continue;
                }
                String text = row.text;
                if (row == hintRow) text = text.substring(0, hint.start()) + " ____ " + text.substring(hint.end());
                // Separate hint-only line supplements a visible field in context.
                boolean separateField = false;
                for (Row other : body) if (other != row && other.text.contains("____")) separateField = true;
                if (row == hintRow && clean(text).equals("____") && separateField) continue;
                append(context, text);
            }
            String cleanContext = clean(context.toString());
            if (prompt.length() == 0) return null;
            if (!cleanContext.contains("____")) {
                if (!hasSubmit || !Pattern.compile("[A-Za-z]+(?:['-][A-Za-z]+)*\\s+[A-Za-z]+")
                        .matcher(cleanContext).find()) return null;
                cleanContext += " [缺失位置未确认: ____]";
            }
            return new Question("blank", clean(prompt.toString()), cleanContext, prefix, length, Collections.emptyList());
        }

        // Join wrapped prompt/option rows only across small vertical gaps.
        List<String> blocks = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        Row previous = null;
        for (Row row : body) {
            if (previous != null && row.top - previous.bottom > Math.max(16, normalHeight * 1.3)) {
                blocks.add(clean(block.toString())); block.setLength(0);
            }
            append(block, row.text); previous = row;
        }
        if (block.length() > 0) blocks.add(clean(block.toString()));
        Question choice = choice(blocks);
        if (choice != null) return choice;
        List<String> rowTexts = new ArrayList<>();
        for (Row row : body) rowTexts.add(row.text);
        choice = choice(rowTexts);
        if (choice != null) return choice;
        // A visible submit button supports fill classification when MLKit drops
        // the entire pale hint. Keep the missing position explicitly unknown.
        if (hasSubmit) {
            StringBuilder prompt = new StringBuilder(), context = new StringBuilder();
            int englishRows = 0;
            for (Row row : body) {
                if (CHINESE.matcher(row.text).find()) append(prompt, row.text);
                else if (Pattern.compile("[A-Za-z]{2,}").matcher(row.text).find()) {
                    append(context, row.text); englishRows++;
                }
            }
            String phrase = clean(context.toString());
            if (prompt.length() > 0 && (englishRows > 0 || body.size() == 1)) {
                // A lone token may be pale prefix or typed black letters. Without
                // dash/pixel evidence neither prefix nor length is confirmed.
                if (phrase.isEmpty()) phrase = "____";
                if (!phrase.contains("____")) phrase += " [缺失位置未确认: ____]";
                return new Question("blank", clean(prompt.toString()), phrase, "", 0, Collections.emptyList());
            }
        }
        return null;
    }

    /** Confirmed screenshot cards take precedence over text-only three-option guesses. */
    private static Question cardChoice(List<OcrLine> input,int toolbarBottom) {
        int count=0, firstY=Integer.MAX_VALUE;
        for(OcrLine line:input) if(line!=null && line.cardCount>0) {
            if(count!=0 && count!=line.cardCount) return null;
            count=line.cardCount; firstY=Math.min(firstY,line.top);
        }
        if(count!=3 && count!=4) return null;
        List<String> options=new ArrayList<>(); boolean chineseOptions=false, englishOptions=false;
        for(int slot=1;slot<=count;slot++) {
            List<OcrLine> parts=new ArrayList<>();
            for(OcrLine line:input) if(line!=null && line.cardSlot==slot) parts.add(line);
            parts.sort(Comparator.comparingInt((OcrLine line)->line.top).thenComparingInt(line->line.left));
            StringBuilder value=new StringBuilder();
            for(OcrLine line:parts) {
                if(line.text.contains("选项未识别")) return null;
                String token=clean(line.text);
                if(token.matches("[Xx×★☆]")) continue;
                append(value,token);
            }
            String option=stripLabel(value.toString());
            if(CHINESE.matcher(option).find()) chineseOptions=true;
            else if(ENGLISH.matcher(option).matches()) englishOptions=true;
            else return null;
            options.add(option);
        }
        if(chineseOptions && englishOptions) return null;
        List<OcrLine> stems=new ArrayList<>();
        for(OcrLine line:input) if(line!=null && line.cardSlot==0 && line.top>=toolbarBottom
                && line.bottom<firstY && !ignored(clean(line.text))) stems.add(line);
        stems.sort(Comparator.comparingInt((OcrLine line)->line.top).thenComparingInt(line->line.left));
        StringBuilder english=new StringBuilder(), chinese=new StringBuilder(), target=new StringBuilder();
        for(OcrLine line:stems) {
            if(CHINESE.matcher(line.text).find()) append(chinese,clean(line.text));
            else if(line.text.matches(".*[A-Za-z].*")) {
                if(HINT.matcher(line.text).find()) return null;
                append(english,clean(line.text));
                if(line.highlighted) append(target,line.text);
                else for(OcrLine word:line.words) if(word.highlighted) append(target,word.text);
            }
        }
        // English sentence translations belong to context, never the option bank.
        String prompt=english.length()>0?clean(english.toString()):clean(chinese.toString());
        if(prompt.isEmpty()) return null;
        String context=english.length()>0?clean(chinese.toString()):"";
        return new Question("choice",prompt,context,"",0,options,clean(target.toString()),0);
    }

    /** Find navigation in the original boxes before a distant right menu joins its row. */
    private static int toolbarBottom(List<OcrLine> lines) {
        int bottom = Integer.MIN_VALUE;
        if (lines == null) return bottom;
        for (OcrLine line : lines) if (line != null && NAVIGATION.matcher(clean(line.text)).matches())
            bottom = Math.max(bottom, line.bottom);
        return bottom;
    }

    /** Preserve multiline sentences and independent boxes in a grid. */
    private static Question structured(List<Row> rows) {
        StringBuilder all = new StringBuilder();
        for (Row row : rows) append(all, row.text);
        String text = all.toString();
        boolean multi = text.matches("(?is).*(?:commonly used together|commonly go together|常用搭配|选.{0,8}[三3]个|[四4]个.{0,8}[三3]个).*");
        boolean order = text.matches("(?is).*(?:词块|连词成句|排序|正确顺序|reorder|put.*(?:order|sequence)).*");
        int englishBoxes = 0, chineseRows = 0; boolean hasGrid = false;
        for (Row row : rows) {
            if (CHINESE.matcher(row.text).find()) chineseRows++;
            else {
            int rowCells = 0;
            for (OcrLine part : row.parts) {
                if (orderWord(part.text)) { englishBoxes++; rowCells++; }
                else if (part.words.size() > 1) {
                    // Count only separately spaced cells, not every word in a phrase.
                    int cells = 1; boolean alphabetic = true; OcrLine previous = null;
                    for (OcrLine word : part.words) {
                        if (!orderWord(word.text)) alphabetic = false;
                        if (previous != null && word.left - previous.right > Math.max(16, word.height() * 1.3)) cells++;
                        previous = word;
                    }
                    if (alphabetic && cells > 1) { englishBoxes += cells; rowCells += cells; }
                }
            }
            if (rowCells > 1) hasGrid = true;
            }
        }
        // A five-or-more-cell word bank with a Chinese meaning is a visible structure,
        // even when the app supplies no textual sorting instruction.
        if (!multi && chineseRows == 1 && (englishBoxes >= 5 || englishBoxes >= 4 && hasGrid)) order = true;
        if (multi || order) {
            List<String> options = new ArrayList<>();
            StringBuilder prompt = new StringBuilder();
            String target = "";
            boolean started = false;
            for (Row row : rows) {
                boolean instruction = CHINESE.matcher(row.text).find() || row.text.matches("(?i).*(?:choose|commonly|together|order|sequence).*");
                if (multi && !started && target.isEmpty() && prompt.toString().matches("(?is).*\\bwith\\s*$")
                        && row.text.matches("[A-Za-z]+")) {
                    target = row.text; append(prompt, row.text); continue;
                }
                if (!started && instruction) {
                    append(prompt, row.text);
                    if (multi) {
                        Matcher base = Pattern.compile("(?i)(?:with|together with)\\s+([a-z]+)").matcher(row.text);
                        if (base.find()) target = base.group(1);
                        for (OcrLine part : row.parts) for (OcrLine word : part.words)
                            if (word.highlighted) target = word.text;
                    }
                    continue;
                }
                started = true;
                for (OcrLine part : row.parts) {
                    String value = clean(part.text);
                    // ML Kit often reports several grid cells as one line. Gaps between
                    // element boxes distinguish cells; ordinary words in one cell stay together.
                    List<OcrLine> words = part.words;
                    if (words.size() > 1) {
                        StringBuilder cell = new StringBuilder(); OcrLine previous = null;
                        for (OcrLine word : words) {
                            if (previous != null && word.left - previous.right > Math.max(16, word.height() * 1.3)) {
                                if (cell.length() > 0) options.add(clean(cell.toString())); cell.setLength(0);
                            }
                            append(cell, word.text); previous = word;
                        }
                        if (cell.length() > 0) options.add(clean(cell.toString()));
                    } else if (value.matches("[A-Za-z]+(?:[ .'’-][A-Za-z]+)*\\.?")) options.add(value);
                }
            }
            int minimum = 1;
            Matcher count = Pattern.compile("(?:还需选中|选中|选取|至少|选择)\\s*[:：]?\\s*([1-9]|三|四|二|五)\\s*(?:(?:个|项)|(?=\\s|$))").matcher(text);
            if (count.find()) {
                String n = count.group(1); minimum = "三".equals(n) ? 3 : "四".equals(n) ? 4 : "二".equals(n) ? 2 : "五".equals(n) ? 5 : Integer.parseInt(n);
            }
            if (options.size() >= 3 && options.size() <= 20 && prompt.length() > 0)
                return new Question(multi ? "multi-choice" : "order", clean(prompt.toString()), "", "", 0,
                        options, target, multi ? minimum : 0);
            return null;
        }
        // An English sentence followed by four separate English answer rows asks
        // for the marked word's contextual synonym. A single-word stem plus four
        // words does not satisfy the sentence evidence needed for this branch.
        if (rows.size() >= 5) {
            int optionStart = rows.size() - 4;
            StringBuilder sentence = new StringBuilder(); String target = "";
            boolean eligible = true;
            List<String> options = new ArrayList<>();
            for (int i = 0; i < optionStart; i++) {
                if (CHINESE.matcher(rows.get(i).text).find() || HINT.matcher(rows.get(i).text).find()) eligible = false;
                append(sentence, rows.get(i).text);
                String marked = markedWords(rows.get(i));
                if (!marked.isEmpty()) target = target.isEmpty() ? marked : target + " " + marked;
            }
            for (int i = optionStart; i < rows.size(); i++) {
                String option = stripLabel(rows.get(i).text);
                if (!ENGLISH.matcher(option).matches()) eligible = false;
                options.add(option);
            }
            Row stemEnd = rows.get(optionStart - 1), firstOption = rows.get(optionStart);
            if (eligible && sentence.toString().split("\\s+").length >= 4
                    && (!target.isEmpty() || sentence.toString().matches(".*[.!?]$")
                    && firstOption.top - stemEnd.bottom > Math.max(16, stemEnd.bottom - stemEnd.top)))
                return new Question("choice", clean(sentence.toString()), "", "", 0, options, target, 0);
        }
        // English sentence may wrap over several rows before four Chinese options.
        int firstChinese = -1;
        for (int i = 0; i < rows.size(); i++) if (CHINESE.matcher(rows.get(i).text).find()) { firstChinese = i; break; }
        if (firstChinese > 0 && rows.size() - firstChinese >= 3 && rows.size() - firstChinese <= 4) {
            StringBuilder sentence = new StringBuilder(); String target = "";
            List<String> options = new ArrayList<>();
            for (int i = 0; i < firstChinese; i++) {
                append(sentence, rows.get(i).text);
                String marked = markedWords(rows.get(i));
                if (!marked.isEmpty()) target = target.isEmpty() ? marked : target + " " + marked;
            }
            for (int i = firstChinese; i < rows.size(); i++) {
                if (!CHINESE.matcher(rows.get(i).text).find()) return null;
                options.add(stripLabel(rows.get(i).text));
            }
            return new Question("choice", clean(sentence.toString()), "", "", 0, options, target, 0);
        }
        return null;
    }

    private static String markedWords(Row row) {
        StringBuilder result = new StringBuilder();
        for (OcrLine line : row.parts) {
            if (line.highlighted) append(result, line.text);
            else for (OcrLine word : line.words) if (word.highlighted) append(result, word.text);
        }
        return clean(result.toString());
    }

    private static boolean orderWord(String text) {
        return ORDER_WORD.matcher(clean(text)).matches();
    }

    private static String stripLabel(String text) {
        return clean(text.replaceFirst("^(?:[A-Da-d1-4][.)、:]|[①②③④])\\s*", ""));
    }

    /** Used only with explicit manual fill hints; does not invent prefix or length. */
    public static Question manualBlank(List<OcrLine> lines, String manualPrompt) {
        StringBuilder meaning = new StringBuilder(), context = new StringBuilder();
        if (manualPrompt != null && !manualPrompt.trim().isEmpty()) append(meaning, clean(manualPrompt));
        int toolbarBottom = toolbarBottom(lines), endY = Integer.MAX_VALUE;
        if (lines == null) lines = Collections.emptyList();
        for (OcrLine line : lines) if (line != null) {
            String value = clean(line.text);
            if (value.matches("(?:提交|确认提交|下一题|确定)") || keyboard(value)) endY = Math.min(endY, line.top);
        }
        for (OcrLine line : lines) if (line != null && line.top >= toolbarBottom && line.top < endY && !ignored(clean(line.text))) {
            if (CHINESE.matcher(line.text).find()) append(meaning, clean(line.text));
            else if (line.text.matches(".*[A-Za-z].*")) append(context, clean(line.text));
        }
        if (meaning.length() == 0) return null;
        if (context.length() == 0) context.append("____");
        else if (!context.toString().contains("____")) context.append(" [缺失位置未确认: ____]");
        return new Question("blank", clean(meaning.toString()), clean(context.toString()), "", 0, Collections.emptyList());
    }

    public static String diagnostic(List<OcrLine> lines, Question question) {
        if (lines == null || lines.isEmpty()) return "OCR 未返回文字框；请先核对本地截图是否为当前题目。";
        if (question == null) {
            for(OcrLine line:lines) if(line.cardCount>0)
                return "已确认 " + line.cardCount + " 个选项卡，但部分选项文字缺失或不完整；已阻止编号错位，请重试。";
            return "OCR 返回 " + lines.size() + " 个文字框，但未匹配完整题干/选项或填空提示。可核对下方原文；单词题可手动补充中文释义、前缀和总长度。";
        }
        return "已解析 " + question.kind + "，选项 " + question.options.size() + " 个；前缀 "
                + (question.prefix.isEmpty() ? "未知" : question.prefix) + "，总长度 " + question.length
                + ("order".equals(question.kind) ? "，排序空位 " + (question.slotCount == 0 ? "未确认" : question.slotCount) : "")
                + (question.needsReview() ? "；填空仍需人工核对提示/缺失位置。" : "。");
    }

    private static Question choice(List<String> texts) {
        if (texts.size() < 4 || texts.size() > 5) return null;
        boolean chinesePrompt = CHINESE.matcher(texts.get(0)).find();
        List<String> options = new ArrayList<>();
        for (int i = 1; i < texts.size(); i++) {
            String text = clean(texts.get(i).replaceFirst("^(?:[A-Da-d1-4][.)、:]|[①②③④])\\s*", ""));
            if (chinesePrompt ? !ENGLISH.matcher(text).matches() : !CHINESE.matcher(text).find()) return null;
            options.add(text);
        }
        return new Question("choice", texts.get(0), "", "", 0, options);
    }

    private static boolean keyboard(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.matches("(?:[a-z]\\s+){3,}[a-z]")
                || normalized.matches("(?:qwertyuiop|asdfghjkl|zxcvbnm)")
                || normalized.matches(".*(?:搜狗输入法|切换输入法|语音输入).*")
                || normalized.matches("(?:空格|123|符|中/英|完成)");
    }

    private static boolean ignored(String text) {
        return keyboard(text) || NAVIGATION.matcher(text).matches() || text.matches("(?:确定|关闭|继续|返回|提交|下一题|[|¦│]?\\s*跳过|[|¦│])")
                || text.matches("(?i)(?:[0-9.]+\\s*)?(?:KB/s|MB/s|B/s)")
                || text.matches(".*(?:app\\s*\\.\\s*vocabgo|词达人|微信|回答正确|回答错误|答案正确|答案错误|输入正确|输入错误).*")
                || text.matches("(?:.*练习|.*测试|学习进度|本次得分|词义选择|选出正确释义|[0-9]+\\s*/\\s*[0-9]+)")
                || text.matches(".*(?:屏幕共享防护|屏幕共享中|正在共享屏幕).*")
                || text.matches("\\(?\\d{1,3}%?\\)?")
                || Pattern.compile("app\\s*\\.\\s*vocabgo|\\bunit\\s*\\d|\\d{1,2}:\\d{2}|(?:剩余|倒计时)\\s*\\d", Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    static String clean(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replace('’', '\'')
                .replaceAll("[✓✔√✗✘]", "")
                .replaceAll("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])", "")
                .replaceAll("\\s*'\\s*", "'")
                .replaceAll("\\s+", " ").trim();
    }

    private static void append(StringBuilder target, String text) {
        if (target.length() > 0) target.append(' ');
        target.append(text);
    }
}
