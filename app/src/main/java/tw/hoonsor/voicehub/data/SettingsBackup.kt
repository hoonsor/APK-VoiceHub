package tw.hoonsor.voicehub.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.backup.AutoBackup
import java.io.InputStream
import java.io.OutputStream

/**
 * 語音總控的備份：只有 SharedPreferences "settings"（常用地點、語音修正詞、提醒設定…）。
 * API key 存在 "vault"（Keystore 加密），換機後解不開，也不該離開手機，所以不備份。
 */
object SettingsBackup {
    const val FORMAT = "voicehub-settings"
    private const val PREFS = "settings"

    fun export(ctx: Context, out: OutputStream) {
        out.write(encode(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all).toString(2).toByteArray(Charsets.UTF_8))
    }

    /** 覆蓋目前的設定；回傳還原的項目數。格式不對丟 IllegalArgumentException */
    fun import(ctx: Context, input: InputStream): Int {
        val values = decode(JSONObject(input.bufferedReader(Charsets.UTF_8).readText()))
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        values.forEach { (k, v) ->
            when (v) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
        }
        e.commit()
        return values.size
    }

    // ---- 純邏輯（SettingsBackupTest） ----

    /** 每個值記型別，還原時才放得回 SharedPreferences 的同一種 put */
    fun encode(all: Map<String, *>): JSONObject {
        val prefs = JSONObject()
        all.toSortedMap().forEach { (k, v) ->
            val (t, value) = when (v) {
                is String -> "s" to v
                is Boolean -> "b" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                is Set<*> -> "ss" to JSONArray(v.map { it.toString() }.sorted())
                else -> return@forEach
            }
            prefs.put(k, JSONObject().put("t", t).put("v", value))
        }
        return JSONObject().put("format", FORMAT).put("version", 1).put("prefs", prefs)
    }

    fun decode(j: JSONObject): Map<String, Any> {
        require(j.optString("format") == FORMAT) { "這不是語音總控的備份檔" }
        val prefs = j.optJSONObject("prefs") ?: throw IllegalArgumentException("備份檔沒有設定內容")
        val out = linkedMapOf<String, Any>()
        prefs.keys().forEach { k ->
            val o = prefs.optJSONObject(k) ?: return@forEach
            out[k] = when (o.optString("t")) {
                "s" -> o.getString("v")
                "b" -> o.getBoolean("v")
                "i" -> o.getInt("v")
                "l" -> o.getLong("v")
                "f" -> o.getDouble("v").toFloat()
                "ss" -> o.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                else -> return@forEach
            }
        }
        return out
    }
}

/** 每日自動備份的來源（AndroidManifest 的 tw.hoonsor.backup.SOURCE 指到這裡） */
class VoiceHubBackupSource : AutoBackup.Source {
    /** LAB 版另放一個資料夾，不跟正式版混在一起 */
    override fun appName() = if (tw.hoonsor.voicehub.BuildConfig.FLAVOR == "lab") "VoiceHub-LAB" else "VoiceHub"
    override fun extension() = "json"
    override fun mimeType() = "application/json"
    override fun write(context: Context, out: OutputStream) = SettingsBackup.export(context, out)
}
