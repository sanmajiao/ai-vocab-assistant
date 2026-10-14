package com.sanmajiao.aivocab;

import java.io.IOException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Pure ordering rules; JSON decoding and networking remain in DeepSeekClient. */
final class OrderRules {
    private OrderRules() { }

    static final class Result {
        final String sentence, sequence;
        Result(String sentence, String sequence) { this.sentence = sentence; this.sequence = sequence; }
    }

    /** One authoritative sequence: map returned original chunks to unused screen options. */
    static Result validateWords(List<String> options, List<?> words, int slotCount) throws IOException {
        if (options == null || words == null || slotCount < 0 || words.isEmpty()
                || words.size() > options.size() || slotCount > 0 && words.size() != slotCount)
            throw new IOException("接口排序词块数量与槽位不一致");
        boolean[] used = new boolean[options.size()];
        java.util.List<Integer> mapped = new java.util.ArrayList<>();
        for (Object word : words) {
            if (!(word instanceof String) || comparison((String) word).isEmpty())
                throw new IOException("接口排序词块格式无效");
            String normalized = comparison((String) word);
            int index = -1;
            for (int i = 0; i < options.size(); i++) {
                String option = options.get(i);
                if (!used[i] && option != null && !comparison(option).isEmpty()
                        && comparison(option).equals(normalized)) { index = i; break; }
            }
            if (index < 0) throw new IOException("接口排序词块不在选项中或重复使用");
            used[index] = true;
            mapped.add(index + 1);
        }
        return assemble(options, mapped);
    }

    static Result validate(List<String> options, List<?> indices, int slotCount, String answer) throws IOException {
        if (options == null || indices == null || slotCount < 0 || indices.isEmpty()
                || indices.size() > options.size() || slotCount > 0 && indices.size() != slotCount)
            throw new IOException("接口排序选项数量与槽位不一致");
        boolean[] used = new boolean[options.size()];
        java.util.List<Integer> mapped = new java.util.ArrayList<>();
        for (Object raw : indices) {
            if (!(raw instanceof Integer || raw instanceof Long)) throw new IOException("接口选项序号无效");
            long number = ((Number) raw).longValue();
            if (number < 1 || number > used.length || used[(int) number - 1])
                throw new IOException("接口选项序号无效或重复");
            used[(int) number - 1] = true;
            String option = options.get((int) number - 1);
            if (option == null || option.trim().isEmpty()) throw new IOException("接口排序选项原文无效");
            mapped.add((int) number);
        }
        Result result = assemble(options, mapped);
        if (answer == null || !comparison(answer).equals(comparison(result.sentence)))
            throw new IOException("答案排序文字与选项序号不一致，请核对");
        return result;
    }

    private static Result assemble(List<String> options, List<Integer> indices) {
        StringBuilder sentence = new StringBuilder(), sequence = new StringBuilder();
        for (int number : indices) {
            String option = options.get(number - 1);
            if (sentence.length() > 0) sentence.append(' ');
            sentence.append(option);
            if (sequence.length() > 0) sequence.append(" → ");
            sequence.append(number).append(". ").append(option);
        }
        return new Result(sentence.toString(), sequence.toString());
    }

    private static String comparison(String text) {
        // Keep token boundaries: joining every character would also accept a
        // fabricated word such as "aninevitableconsequence".
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replace('’', '\'').replace('‘', '\'')
                .replaceAll("[\\s\\p{Z}]+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
