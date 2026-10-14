package com.sanmajiao.aivocab;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-bound key storage. The application also disables Android backup. */
public final class SecureSettings {
    public static final String DEFAULT_MODEL = "deepseek-flash";
    private static final String ALIAS = "aivocab.api-key.v1";
    private static final byte[] AAD = "aivocab.settings.v1".getBytes(StandardCharsets.UTF_8);
    private final SharedPreferences prefs;
    private final Context context;

    public SecureSettings(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("secure_settings", Context.MODE_PRIVATE);
    }

    public synchronized boolean developerMode() { return prefs.getBoolean("developer_mode", false); }
    public synchronized boolean diagnosticsEnabled() {
        return developerMode() && prefs.getBoolean("diagnostics_enabled", false);
    }
    public synchronized void setDeveloperMode(boolean enabled) {
        SharedPreferences.Editor editor = prefs.edit().putBoolean("developer_mode", enabled);
        if (!enabled) editor.putBoolean("diagnostics_enabled", false);
        if (!editor.commit()) throw new IllegalStateException("开发者设置保存失败");
        if (!enabled) CaptureDiagnostics.clear(context);
    }
    public synchronized void setDiagnosticsEnabled(boolean enabled) {
        if (enabled && !developerMode()) throw new IllegalStateException("请先开启开发者选项");
        if (!prefs.edit().putBoolean("diagnostics_enabled", enabled).commit())
            throw new IllegalStateException("诊断设置保存失败");
        if (!enabled) CaptureDiagnostics.clear(context);
    }

    public static final int MAX_PROMPT_CHARACTERS = 6000;

    /** Stored business instructions are active only while developer mode is enabled. */
    public synchronized String promptOverride(String kind) {
        validatePromptKind(kind);
        return developerMode() ? savedPromptOverride(kind) : "";
    }

    public synchronized String savedPromptOverride(String kind) {
        validatePromptKind(kind);
        return prefs.getString("prompt_override_" + kind, "");
    }

    public synchronized void savePromptOverride(String kind, String instructions) {
        validatePromptKind(kind);
        if (!developerMode()) throw new IllegalStateException("请先开启开发者选项");
        String value = instructions == null ? "" : instructions;
        if (value.length() > MAX_PROMPT_CHARACTERS)
            throw new IllegalArgumentException("提示词最多 6000 个字符；未保存或截断内容");
        if (value.trim().isEmpty()) { clearPromptOverride(kind); return; }
        if (!prefs.edit().putString("prompt_override_" + kind, value).commit())
            throw new IllegalStateException("提示词保存失败，请重试");
    }

    public synchronized void clearPromptOverride(String kind) {
        validatePromptKind(kind);
        if (!developerMode()) throw new IllegalStateException("请先开启开发者选项");
        if (!prefs.edit().remove("prompt_override_" + kind).commit())
            throw new IllegalStateException("默认提示词恢复失败，请重试");
    }

    private static void validatePromptKind(String kind) {
        if (!"choice".equals(kind) && !"multi-choice".equals(kind)
                && !"order".equals(kind) && !"blank".equals(kind))
            throw new IllegalArgumentException("不支持此题型");
    }

    public synchronized String key() {
        String value = prefs.getString("ciphertext", "");
        if (value.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP);
            if (iv.length != 12) throw new IllegalStateException();
            cipher.init(Cipher.DECRYPT_MODE, secret(false), new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("无法解密本机密钥，请清除后重新保存");
        }
    }

    public synchronized String model() {
        return prefs.getString("model", DEFAULT_MODEL);
    }

    public synchronized void save(String key, String model) {
        String safeKey = key == null ? "" : key.trim();
        String safeModel = model == null || model.trim().isEmpty() ? DEFAULT_MODEL : model.trim();
        if (!safeModel.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,99}"))
            throw new IllegalArgumentException("模型名称格式无效");
        if (safeKey.isEmpty() || safeKey.length() > 1024 || safeKey.matches("(?s).*\\s.*"))
            throw new IllegalArgumentException("请填写有效 API Key");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secret(true));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(safeKey.getBytes(StandardCharsets.UTF_8));
            if (!prefs.edit().putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString("model", safeModel).commit()) throw new IllegalStateException();
        } catch (Exception error) {
            throw new IllegalStateException("密钥加密保存失败，请重试");
        }
    }

    public synchronized void clear() {
        if (!prefs.edit().clear().commit()) throw new IllegalStateException("清除设置失败，请重试");
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
        } catch (Exception error) { throw new IllegalStateException("清除本机加密密钥失败，请重试"); }
    }

    /** Consumed by the next manual request; CaptureService clears before networking. */
    public synchronized void saveHints(String prefix, int length) {
        String value = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        if (!value.isEmpty() && (!value.matches("[a-z]+(?:[-'][a-z]+)*") || value.length() > 60))
            throw new IllegalArgumentException("前缀仅支持英文字母、单词内撇号或连字符");
        int letterCount = value.replaceAll("[^a-z]", "").length();
        if (letterCount > 30 || length < 0 || length > 60 || length > 0 && letterCount > length)
            throw new IllegalArgumentException("总长度应为 1–60，且不能短于前缀；未知留空");
        if (!prefs.edit().putString("hint_prefix", value).putInt("hint_length", length).commit())
            throw new IllegalStateException("提示保存失败");
    }
    public synchronized String hintPrefix() { return prefs.getString("hint_prefix", ""); }
    public synchronized String hintPrompt() { return prefs.getString("hint_prompt", ""); }
    public synchronized void saveHintPrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        if (value.length() > 300) throw new IllegalArgumentException("补充释义过长");
        if (!prefs.edit().putString("hint_prompt", value).commit()) throw new IllegalStateException("提示保存失败");
    }
    public synchronized int hintLength() { return prefs.getInt("hint_length", 0); }
    public synchronized void clearHints() {
        if (!prefs.edit().remove("hint_prefix").remove("hint_length").remove("hint_prompt").commit())
            throw new IllegalStateException("提示清除失败，请重新设置后再识别");
    }

    private SecretKey secret(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new IllegalStateException();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
}
