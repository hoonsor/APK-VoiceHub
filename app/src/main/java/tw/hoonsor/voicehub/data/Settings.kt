package tw.hoonsor.voicehub.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** STT 引擎 */
enum class SttEngine(val label: String) { GROQ("Groq Whisper（準確）"), SYSTEM("Android 內建（免費、離線可用）") }

/**
 * 設定（SharedPreferences "settings"）。API key 另存在 "vault"，以 Keystore AES-GCM 加密，
 * 系統備份規則排除 vault（換機後 Keystore 金鑰不存在，備份過去也解不開）。
 */
class Settings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val vault = KeyVault(context)

    // ---- AI 主力（OpenAI 相容 Chat Completions） ----
    var llmBaseUrl: String
        get() = p.getString("llmBaseUrl", DEFAULT_LLM_BASE) ?: DEFAULT_LLM_BASE
        set(v) = p.edit().putString("llmBaseUrl", v.trim()).apply()
    var llmModel: String
        get() = p.getString("llmModel", DEFAULT_LLM_MODEL) ?: DEFAULT_LLM_MODEL
        set(v) = p.edit().putString("llmModel", v.trim()).apply()
    var llmKey: String?
        get() = vault.get("llmKey")
        set(v) = vault.put("llmKey", v)

    // ---- Groq：語音轉文字＋AI 備援 ----
    var groqKey: String?
        get() = vault.get("groqKey")
        set(v) = vault.put("groqKey", v)
    var groqBaseUrl: String
        get() = p.getString("groqBaseUrl", DEFAULT_GROQ_BASE) ?: DEFAULT_GROQ_BASE
        set(v) = p.edit().putString("groqBaseUrl", v.trim()).apply()
    var sttModel: String
        get() = p.getString("sttModel", DEFAULT_STT_MODEL) ?: DEFAULT_STT_MODEL
        set(v) = p.edit().putString("sttModel", v.trim()).apply()
    var fallbackModel: String
        get() = p.getString("fallbackModel", DEFAULT_FALLBACK_MODEL) ?: DEFAULT_FALLBACK_MODEL
        set(v) = p.edit().putString("fallbackModel", v.trim()).apply()
    var fallbackEnabled: Boolean
        get() = p.getBoolean("fallbackEnabled", true)
        set(v) = p.edit().putBoolean("fallbackEnabled", v).apply()

    var sttEngine: SttEngine
        get() = runCatching { SttEngine.valueOf(p.getString("sttEngine", SttEngine.GROQ.name)!!) }.getOrDefault(SttEngine.GROQ)
        set(v) = p.edit().putString("sttEngine", v.name).apply()

    // ---- 對話 ----
    var ttsEnabled: Boolean
        get() = p.getBoolean("ttsEnabled", true)
        set(v) = p.edit().putBoolean("ttsEnabled", v).apply()
    var speechRate: Float
        get() = p.getFloat("speechRate", 1.1f)
        set(v) = p.edit().putFloat("speechRate", v).apply()
    /** 回覆後自動再聽（連續對話） */
    var autoListen: Boolean
        get() = p.getBoolean("autoListen", false)
        set(v) = p.edit().putBoolean("autoListen", v).apply()
    /** 動作成功、畫面跳到別的 APP 後自動關閉對話視窗 */
    var autoClose: Boolean
        get() = p.getBoolean("autoClose", true)
        set(v) = p.edit().putBoolean("autoClose", v).apply()

    // ---- 語音派工（利姆路 raphael-dispatch，經 Tailscale，不需金鑰） ----
    var dispatchBaseUrl: String
        get() = p.getString("dispatchBaseUrl", DEFAULT_DISPATCH_BASE) ?: DEFAULT_DISPATCH_BASE
        set(v) = p.edit().putString("dispatchBaseUrl", v.trim()).apply()

    val hasLlm get() = !llmKey.isNullOrBlank()
    val hasGroq get() = !groqKey.isNullOrBlank()

    companion object {
        const val DEFAULT_LLM_BASE = "https://api.minimax.io/v1"
        const val DEFAULT_LLM_MODEL = "MiniMax-M3"
        const val DEFAULT_GROQ_BASE = "https://api.groq.com/openai/v1"
        const val DEFAULT_STT_MODEL = "whisper-large-v3"
        const val DEFAULT_FALLBACK_MODEL = "llama-3.3-70b-versatile"
        const val DEFAULT_DISPATCH_BASE = "https://hoonsoropenclaw.tail71361e.ts.net"
    }
}

/** Android Keystore AES-GCM 加密的小型金庫 */
class KeyVault(context: Context) {
    private val p = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun get(name: String): String? = p.getString(name, null)?.let { decrypt(it) }

    fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) p.edit().remove(name).apply()
        else p.edit().putString(name, encrypt(value.trim())).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val enc = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + enc, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        String(c.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val ALIAS = "voicehub_vault"
    }
}
