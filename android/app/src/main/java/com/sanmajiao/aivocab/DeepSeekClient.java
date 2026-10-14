package com.sanmajiao.aivocab;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Blocking call for a background worker, invoked only by a manual user action. */
public final class DeepSeekClient {
    public static final String ENDPOINT = "https://api.deepseek.com/chat/completions";
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "api-deadline"); thread.setDaemon(true); return thread;
    });
    private final SecureSettings settings;
    private final Object lock = new Object();
    private HttpURLConnection active;
    private long generation;
    private volatile String trace = "";

    public DeepSeekClient(SecureSettings settings) { this.settings = settings; }

    /** Shape and validation outcome only; never stores response text or credentials. */
    public String trace() { return trace; }

    public void cancel() {
        synchronized (lock) { generation++; if (active != null) active.disconnect(); active = null; }
    }

    public String answer(Question question) throws IOException {
        trace = "";
        // Validate locally before looking up an override for a supported kind.
        AnswerRequest request = AnswerRequest.forQuestion(question);
        String override = settings.promptOverride(question.kind);
        if (!override.isEmpty()) request = AnswerRequest.forQuestion(question, override);
        boolean ordering = "order".equals(question.kind);
        if (ordering) trace = "order protocol=words; response=not-received";
        if ("choice".equals(question.kind) && question.options.size() != 3 && question.options.size() != 4)
            throw new IOException("选项不完整，请重新识别");
        String key = settings.key();
        if (key.isEmpty()) throw new IOException("请先保存 DeepSeek API Key");
        final HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        final long token;
        synchronized (lock) {
            token = ++generation;
            if (active != null) active.disconnect();
            active = connection;
        }
        int budgetMs = "choice".equals(question.kind) ? 5000 : 10000;
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> deadline = DEADLINES.schedule(() -> {
            expired.set(true); connection.disconnect();
        }, budgetMs, TimeUnit.MILLISECONDS);
        try {
            connection.setConnectTimeout(budgetMs);
            connection.setReadTimeout(budgetMs);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            JSONObject payload = new JSONObject();
            for (java.util.Map.Entry<String, Object> field : request.fields.entrySet()) {
                Object value = field.getValue();
                payload.put(field.getKey(), value == null ? JSONObject.NULL
                        : value instanceof java.util.List ? new JSONArray((java.util.List<?>) value) : value);
            }
            JSONArray messages = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", request.system))
                    .put(new JSONObject().put("role", "user").put("content", payload.toString()));
            byte[] body = new JSONObject().put("model", settings.model()).put("messages", messages)
                    .put("thinking", new JSONObject().put("type", "disabled"))
                    .put("response_format", new JSONObject().put("type", "json_object"))
                    .put("max_tokens", 220).put("stream", false).put("temperature", 0)
                    .toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            check(token, expired);
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            int status = connection.getResponseCode();
            check(token, expired);
            if (status != 200) throw new IOException(statusMessage(status));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[2048]; int count;
                while ((count = in.read(buffer)) != -1) {
                    check(token, expired);
                    if (bytes.size() + count > 65536) throw new IOException("接口响应过长，请重试");
                    bytes.write(buffer, 0, count);
                }
            }
            check(token, expired);
            JSONObject response = new JSONObject(bytes.toString("UTF-8"));
            JSONObject choice = response.getJSONArray("choices").getJSONObject(0);
            if (!"stop".equals(choice.optString("finish_reason"))) throw new IOException("接口答案未完整返回，请重试");
            String content = choice.getJSONObject("message").getString("content");
            String shape = ordering ? orderResponseShape(content) : "";
            if (ordering) trace = shape + "; validation=pending";
            String result;
            try { result = validateAnswer(question, content); }
            catch (IOException error) {
                if (ordering) trace = shape + "; validation=" + orderFailureCode(error.getMessage());
                throw error;
            }
            if (ordering) trace = shape + "; validation=accepted";
            check(token, expired);
            return result;
        } catch (org.json.JSONException error) {
            throw new IOException("接口答案格式无效，请重新识别或重试");
        } catch (IOException error) {
            check(token, expired);
            // Never surface server bodies, connection diagnostics, or API keys.
            if (isSafeMessage(error.getMessage())) throw error;
            throw new IOException("网络请求失败，请检查网络后手动重试");
        } finally {
            deadline.cancel(false);
            connection.disconnect();
            synchronized (lock) { if (active == connection) active = null; }
        }
    }

    static String validateAnswer(Question question, String content) throws IOException {
        try {
            JSONObject data = new JSONObject(content.trim());
            if ("order".equals(question.kind)) {
                if (data.has("error")) throw new IOException("模型无法确定答案，请核对题目");
                if (data.has("words")) {
                    JSONArray words = data.optJSONArray("words");
                    if (words == null) throw new IOException("接口排序词块格式无效");
                    java.util.List<Object> rawWords = new java.util.ArrayList<>();
                    for (int i = 0; i < words.length(); i++) rawWords.add(words.opt(i));
                    OrderRules.Result result = OrderRules.validateWords(question.options, rawWords, question.slotCount);
                    return result.sentence + "\n顺序：" + result.sequence;
                }
                JSONArray indices = data.optJSONArray("indices");
                if (indices == null || !(data.opt("answer") instanceof String))
                    throw new IOException("接口排序答案格式无效");
                java.util.List<Object> rawIndices = new java.util.ArrayList<>();
                for (int i = 0; i < indices.length(); i++) rawIndices.add(indices.opt(i));
                OrderRules.Result result = OrderRules.validate(question.options, rawIndices,
                        question.slotCount, data.getString("answer"));
                return result.sentence + "\n顺序：" + result.sequence;
            }
            if ("multi-choice".equals(question.kind)) {
                JSONArray indices = data.optJSONArray("indices");
                if (indices == null || indices.length() > question.options.size()
                        || indices.length() < Math.max(1, question.minSelections))
                    throw new IOException("接口选项序号数量无效");
                boolean[] used = new boolean[question.options.size()];
                StringBuilder assembled = new StringBuilder(), display = new StringBuilder();
                JSONArray answers = data.optJSONArray("answers");
                if (answers == null || answers.length() != indices.length())
                    throw new IOException("接口多选答案格式无效");
                for (int i = 0; i < indices.length(); i++) {
                    Object raw = indices.opt(i);
                    if (!(raw instanceof Integer || raw instanceof Long)) throw new IOException("接口选项序号无效");
                    long number = ((Number) raw).longValue();
                    if (number < 1 || number > used.length || used[(int) number - 1])
                        throw new IOException("接口选项序号无效或重复");
                    used[(int) number - 1] = true;
                    String option = question.options.get((int) number - 1);
                    if (!(answers.opt(i) instanceof String)
                            || !comparison(option).equals(comparison(answers.getString(i))))
                        throw new IOException("答案文字与选项序号不一致，请核对");
                    if (assembled.length() > 0) assembled.append(' ');
                    assembled.append(option);
                    if (display.length() > 0) display.append("\n");
                    display.append(number).append(". ").append(option);
                }
                return display.toString();
            }
            if (!(data.opt("answer") instanceof String)) throw new IOException("模型无法确定答案，请核对题目");
            String answer = data.getString("answer").trim();
            if ("choice".equals(question.kind)) {
                Object raw = data.opt("option");
                if (!(raw instanceof Integer || raw instanceof Long)) throw new IOException("接口选项序号无效");
                long index = ((Number) raw).longValue();
                if (index < 1 || index > question.options.size()) throw new IOException("接口选项序号无效");
                String option = question.options.get((int) index - 1);
                if (!comparison(answer).equals(comparison(option))) throw new IOException("答案文字与选项序号不一致，请核对");
                return "第 " + index + " 项 · " + option;
            }
            if (!answer.matches("[A-Za-z]+(?:[-' ][A-Za-z]+)*")) throw new IOException("填空答案格式无效");
            String letters = answer.replaceAll("[^A-Za-z]", "");
            String prefixLetters = question.prefix.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
            if (!question.prefix.isEmpty() && (prefixLetters.isEmpty() || !letters.toLowerCase(Locale.ROOT).startsWith(prefixLetters)))
                throw new IOException("答案不符合前缀，请核对识别结果");
            if (question.length > 0 && letters.length() != question.length) throw new IOException("答案不符合总长度，请核对识别结果");
            return answer;
        } catch (org.json.JSONException error) { throw new IOException("接口答案格式无效，请重试"); }
    }

    static String orderResponseShape(String content) {
        try {
            JSONObject data = new JSONObject(content.trim());
            if (data.has("error")) return "order response=abstained";
            String field = data.has("words") ? "words" : "indices";
            JSONArray values = data.optJSONArray(field);
            if (values == null) return "order protocol=" + field + "; array=missing-or-invalid";
            int strings = 0, integers = 0, other = 0;
            for (int i = 0; i < values.length(); i++) {
                Object raw = values.opt(i);
                if (raw instanceof String) strings++;
                else if (raw instanceof Integer || raw instanceof Long) integers++;
                else other++;
            }
            return "order protocol=" + field + "; count=" + values.length() + "; strings=" + strings
                    + "; integers=" + integers + "; other=" + other;
        } catch (org.json.JSONException error) { return "order response=invalid-json"; }
    }

    private static String orderFailureCode(String message) {
        if (message == null) return "rejected";
        if (message.contains("数量")) return "count-mismatch";
        if (message.contains("不在选项")) return "unmatched-or-overused-chunk";
        if (message.contains("文字与选项")) return "legacy-text-index-mismatch";
        if (message.contains("序号")) return "legacy-invalid-index";
        if (message.startsWith("模型")) return "abstained";
        return "invalid-format";
    }

    private void check(long token, AtomicBoolean expired) throws IOException {
        synchronized (lock) { if (token != generation || Thread.currentThread().isInterrupted()) throw new IOException("请求已取消"); }
        if (expired.get()) throw new IOException("接口等待超时，请手动重试");
    }
    private static String comparison(String text) { return QuestionParser.clean(text).replaceAll("\\s", "").toLowerCase(Locale.ROOT); }
    private static boolean isSafeMessage(String text) {
        return text != null && (text.startsWith("接口") || text.startsWith("答案") || text.startsWith("填空")
                || text.startsWith("模型") || text.startsWith("DeepSeek"));
    }
    private static String statusMessage(int status) {
        if (status == 401 || status == 403) return "DeepSeek 密钥或权限无效，请检查设置";
        if (status == 402) return "DeepSeek 余额不足，请检查账户";
        if (status == 429) return "DeepSeek 请求受限，请稍后手动重试";
        return "DeepSeek 请求失败（HTTP " + status + "），请稍后重试";
    }
}
