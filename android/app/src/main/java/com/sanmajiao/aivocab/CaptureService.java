package com.sanmajiao.aivocab;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** One consent session, with screenshot/OCR/API work exclusively started by a user tap. */
public final class CaptureService extends Service {
    public static final String ACTION_RECOGNIZE = "com.sanmajiao.aivocab.RECOGNIZE";
    public static final String ACTION_RETRY = "com.sanmajiao.aivocab.RETRY";
    public static final String ACTION_STOP = "com.sanmajiao.aivocab.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";
    public static volatile boolean running;
    private static final String CHANNEL = "manual_vocab_capture";
    private static final int NOTIFICATION_ID = 4101;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private MediaProjection projection;
    private ScreenCapture capture;
    private OverlayController overlay;
    private OcrEngine ocr;
    private SecureSettings settings;
    private DeepSeekClient currentClient;
    private Future<?> networkTask;
    private int generation;
    private boolean stopping;
    private String manualPrompt = "", diagnostic = "";

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            if (stopping) return;
            Toast.makeText(CaptureService.this, "屏幕共享已结束；请回到设置重新授权。", Toast.LENGTH_LONG).show();
            stopSession();
        }
        @Override public void onCapturedContentResize(int width, int height) {
            if (capture != null && !stopping) capture.resize(width, height);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSession();
            return START_NOT_STICKY;
        }
        if (stopping) return START_NOT_STICKY;
        if (projection == null) {
            int resultCode = intent == null ? Activity.RESULT_CANCELED
                    : intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent consent = getConsent(intent);
            if (resultCode != Activity.RESULT_OK || consent == null) {
                stopSession();
                return START_NOT_STICKY;
            }
            try {
                // Android 14 requires the mediaProjection FGS before getMediaProjection.
                startCaptureForeground();
                if (!Settings.canDrawOverlays(this)) throw new IllegalStateException("Missing overlay permission");
                settings = new SecureSettings(this);
                MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                if (manager == null) throw new IllegalStateException("No projection manager");
                projection = manager.getMediaProjection(resultCode, consent);
                if (projection == null) throw new IllegalStateException("No media projection");
                // Required BEFORE the single createVirtualDisplay in ScreenCapture.
                projection.registerCallback(projectionCallback, main);
                capture = new ScreenCapture(this, projection, main);
                ocr = new OcrEngine();
                overlay = new OverlayController(this, new OverlayController.Listener() {
                    @Override public void onRecognize() { recognize(); }
                    @Override public void onRetry() { recognize(); }
                    @Override public void onStop() { stopSession(); }
                    @Override public void onSettings() { openSettings(); }
                });
                overlay.show();
                running = true;
            } catch (RuntimeException error) {
                Toast.makeText(this, "助手未能开启。请确认悬浮窗权限并重新授权屏幕共享。", Toast.LENGTH_LONG).show();
                stopSession();
                return START_NOT_STICKY;
            }
        }
        if (intent != null && (ACTION_RECOGNIZE.equals(intent.getAction())
                || ACTION_RETRY.equals(intent.getAction()))) recognize();
        // Consent and returning to an app never start recognition.
        return START_NOT_STICKY;
    }

    private static Intent getConsent(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        return intent.getParcelableExtra(EXTRA_DATA);
    }

    private void startCaptureForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "手动屏幕识别", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("维持您授权的屏幕共享；仅点击识别时处理画面。");
        if (manager != null) manager.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("词汇翻译助手已开启")
                .setContentText("返回题目后点击识别；仅手动识别，不自动作答。")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else startForeground(NOTIFICATION_ID, notification);
    }

    private void recognize() {
        if (stopping || capture == null || overlay == null || ocr == null) return;
        cancelCurrent();
        final int request = generation;
        final String manualPrefix;
        final int manualLength;
        try {
            manualPrefix = settings.hintPrefix();
            manualLength = settings.hintLength();
            manualPrompt = settings.hintPrompt();
            diagnostic = settings.diagnosticsEnabled() ? "本次手动识别\n" : "";
            CaptureDiagnostics.clear(this);
            // Consume before screenshot/API: a failed request must not leak hints to a new page.
            settings.clearHints();
            if (settings.key().trim().isEmpty()) {
                fail(request, "请先在设置中保存 DeepSeek API 密钥。");
                return;
            }
        } catch (RuntimeException error) {
            fail(request, "无法读取本机密钥或题目提示，请回到设置重新保存。");
            return;
        }
        overlay.setBusy("正在读取当前屏幕，请稍候。每次点击只请求一次答案。");
        overlay.hide();
        // Defensive restoration also covers an unexpected capture callback failure.
        main.postDelayed(() -> {
            if (valid(request)) overlay.restore();
        }, 2800);
        // Let compositor remove the overlay before replacing the capture surface.
        main.postDelayed(() -> {
        if (!valid(request)) return;
        capture.requestFresh(new ScreenCapture.Callback() {
            @Override public void onFrame(Bitmap bitmap) {
                if (!valid(request)) {
                    bitmap.recycle();
                    return;
                }
                overlay.restore();
                if (settings.diagnosticsEnabled()) {
                    diagnostic += CaptureDiagnostics.frame(bitmap) + "\n";
                    CaptureDiagnostics.saveImage(CaptureService.this, bitmap);
                    CaptureDiagnostics.saveText(CaptureService.this, diagnostic);
                }
                overlay.setBusy("截图已取得，正在本机识别题目。截图不会发送给 API。");
                readQuestion(request, bitmap, manualPrefix, manualLength, false, null);
            }
            @Override public void onError(String message) { fail(request, message); }
        });
        }, 160);
    }

    private void readQuestion(int request, Bitmap bitmap, String prefix, int length,
                              boolean enhanced, Question original) {
        OcrEngine.Callback callback = new OcrEngine.Callback() {
            @Override public void onResult(List<QuestionParser.OcrLine> lines) {
                if (!valid(request)) {
                    bitmap.recycle();
                    return;
                }
                Question parsed;
                try { parsed = QuestionParser.parse(lines); }
                catch (IllegalArgumentException error) { parsed = null; }
                boolean physicalChoice = false;
                for (QuestionParser.OcrLine line : lines)
                    if (line != null && line.cardCount > 0) physicalChoice = true;
                final boolean incompleteChoice = physicalChoice && parsed == null;
                parsed = OrderSlotHints.attach(bitmap, lines, parsed);
                if (parsed == null && !physicalChoice
                        && (!prefix.isEmpty() || length > 0 || !manualPrompt.isEmpty()))
                    parsed = QuestionParser.manualBlank(lines, manualPrompt);
                Question question = applyHints(parsed, prefix, length);
                if (settings.diagnosticsEnabled()) {
                    diagnostic += (enhanced ? "\n增强 OCR 原文\n" : "\n基础 OCR 原文\n") + ocr.rawText()
                        + "\n合并及局部几何提示\n" + CaptureDiagnostics.ocr(lines)
                        + "\n解析：" + QuestionParser.diagnostic(lines, question) + "\n";
                    CaptureDiagnostics.saveText(CaptureService.this, diagnostic);
                }
                // One optional local pass also helps missing pale prefix/length hints. It never
                // causes a second API call, and the original parsed question remains a fallback.
                if (!enhanced && (question == null || question.needsReview())) {
                    readQuestion(request, bitmap, prefix, length, true, question);
                    return;
                }
                bitmap.recycle();
                if (question == null && !incompleteChoice) question = original;
                else if (original != null && original.kind.equals(question.kind)
                        && original.prompt.equals(question.prompt)) {
                    // Enhanced OCR must not discard a prefix already seen in the original.
                    question = new Question(question.kind, question.prompt, question.context,
                            question.prefix.isEmpty() ? original.prefix : question.prefix,
                            question.length == 0 ? original.length : question.length, question.options,
                            question.target.isEmpty() ? original.target : question.target, question.minSelections,
                            question.slotCount == 0 ? original.slotCount : question.slotCount);
                }
                if (question == null) {
                    fail(request, "未读到完整题目，请完整显示题干与全部选项后重试。填空可补原始提示；需要排查时在开发者选项开启诊断。");
                    return;
                }
                requestAnswer(request, question);
            }
            @Override public void onError(String message) {
                if (!valid(request)) {
                    bitmap.recycle();
                    return;
                }
                if (!enhanced) {
                    if (settings.diagnosticsEnabled()) diagnostic += "\n基础OCR：" + message + "\n";
                    readQuestion(request, bitmap, prefix, length, true, null);
                    return;
                }
                bitmap.recycle();
                if (settings.diagnosticsEnabled()) {
                    diagnostic += "\n增强OCR：" + message + "\n";
                    CaptureDiagnostics.saveText(CaptureService.this, diagnostic);
                }
                if (original != null) requestAnswer(request, original);
                else fail(request, message + " 请核对页面后手动重试。");
            }
        };
        ocr.setDiagnosticsEnabled(settings.diagnosticsEnabled());
        if (enhanced) ocr.recognizeEnhanced(bitmap, callback);
        else ocr.recognize(bitmap, callback);
    }

    private static Question applyHints(Question question, String prefix, int length) {
        if (question == null || !"blank".equals(question.kind)) return question;
        return new Question(question.kind, question.prompt, question.context,
                prefix == null || prefix.isEmpty() ? question.prefix : prefix,
                length > 0 ? length : question.length, question.options);
    }

    private void requestAnswer(int request, Question question) {
        if (!valid(request)) return;
        String detail = question.display()
                + "\n\n请核对题目信息和候选，自行在原页面作答。"
                + "\n答案会保留到下一次手动识别。";
        if (question.needsReview()) detail += "\n前缀或总长度未确认，候选可能有歧义。";
        final String finalDetail = detail;
        overlay.setBusy("题目已识别，正在请求候选…\n" + question.display());
        final DeepSeekClient client = new DeepSeekClient(settings);
        currentClient = client;
        networkTask = network.submit(() -> {
            try {
                String answer = client.answer(question);
                main.post(() -> {
                    if (!valid(request)) return;
                    recordOrderTrace(client, question);
                    currentClient = null;
                    networkTask = null;
                    if (answer == null || answer.trim().isEmpty()) fail(request, "API 未返回候选，请手动重试。");
                    else overlay.setResult(answer, finalDetail);
                });
            } catch (Exception error) {
                // DeepSeekClient exposes only sanitized user messages, never provider bodies.
                final String reason = safeNetworkMessage(error);
                main.post(() -> {
                    if (!valid(request)) return;
                    recordOrderTrace(client, question);
                    currentClient = null;
                    networkTask = null;
                    fail(request, "答案请求未完成：" + reason + " 请手动重试。\n" + question.display());
                });
            }
        });
    }

    private boolean valid(int request) { return !stopping && request == generation && overlay != null; }

    private void recordOrderTrace(DeepSeekClient client, Question question) {
        if (settings.diagnosticsEnabled() && "order".equals(question.kind)) {
            diagnostic += "\n排序输出协议：" + client.trace() + "\n";
            CaptureDiagnostics.saveText(this, diagnostic);
        }
    }

    private static String safeNetworkMessage(Exception error) {
        String message = error.getMessage();
        if (error instanceof IOException && message != null && message.length() <= 180) {
            String[] prefixes = {"DeepSeek ", "接口", "答案", "填空", "模型", "网络请求失败",
                    "请先保存 DeepSeek", "未识别到可用题目", "题型无效", "选项不完整"};
            for (String prefix : prefixes) if (message.startsWith(prefix)) return message;
        }
        return "请检查网络、密钥和额度。";
    }

    private void fail(int request, String message) {
        if (!valid(request)) return;
        if (settings != null && settings.diagnosticsEnabled()) {
            diagnostic += "\n结果：" + message + "\n";
            CaptureDiagnostics.saveText(this, diagnostic);
        }
        overlay.restore();
        overlay.setError(message);
    }

    private void cancelCurrent() {
        generation++;
        if (capture != null) capture.cancelRequest();
        if (currentClient != null) currentClient.cancel();
        currentClient = null;
        if (networkTask != null) networkTask.cancel(true);
        networkTask = null;
    }

    private void openSettings() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    // Android 26-33 do not provide onCapturedContentResize. Resize the same display on rotation.
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (Build.VERSION.SDK_INT < 34 && capture != null) {
            android.view.WindowManager manager = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            if (manager != null) {
                manager.getDefaultDisplay().getRealMetrics(metrics);
                capture.resize(metrics.widthPixels, metrics.heightPixels);
            }
        }
    }

    private void stopSession() {
        if (stopping) return;
        stopping = true;
        running = false;
        cancelCurrent();
        main.removeCallbacksAndMessages(null);
        if (overlay != null) overlay.destroy();
        overlay = null;
        if (capture != null) capture.close();
        capture = null;
        if (projection != null) {
            projection.unregisterCallback(projectionCallback);
            projection.stop();
            projection = null;
        }
        if (ocr != null) ocr.close();
        ocr = null;
        network.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        stopSession();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
