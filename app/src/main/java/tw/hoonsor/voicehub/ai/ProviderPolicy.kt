package tw.hoonsor.voicehub.ai

import java.net.URI

/**
 * 依 AI-PROFILE §4「個資與公務資料」判斷 AI 服務網址的資料規範，設定頁據此顯示警告。
 * 純邏輯，有 JVM 測試（ProviderPolicyTest）。規則改了要同步改這裡。
 */
object ProviderPolicy {

    enum class Kind {
        /** 大陸廠牌雲端服務：公務資料即使去識別化也不可送 */
        MAINLAND_CLOUD,
        /** MiniMax 國際版：只可處理本機去識別化後的公務文字 */
        MINIMAX_INTL,
        /** 本機或區網：資料不出這台裝置／這個網路 */
        LOCAL,
        /** 其他（Groq、OpenAI、Anthropic…）：一般公文可送，人事資料仍須先去識別化 */
        OTHER,
    }

    data class Notice(val kind: Kind, val title: String, val body: String, val danger: Boolean)

    /** 大陸廠牌雲端服務的網域（含子網域） */
    private val mainlandDomains = listOf(
        "minimaxi.com", "minimax.chat",          // MiniMax 中國版
        "deepseek.com",                           // DeepSeek
        "moonshot.cn", "moonshot.ai",             // Kimi
        "aliyuncs.com", "aliyun.com",             // 通義千問（DashScope）
        "bigmodel.cn", "zhipuai.cn",              // 智譜 GLM
        "volces.com", "volcengine.com",           // 豆包（火山引擎）
        "baidubce.com", "baidu.com",              // 文心（千帆）
        "tencentcloudapi.com", "hunyuan.cloud.tencent.com", // 混元
        "siliconflow.cn", "siliconflow.com",      // 硅基流動
        "stepfun.com",                            // 階躍星辰
        "lingyiwanwu.com", "01.ai",               // 零一萬物
        "baichuan-ai.com",                        // 百川
        "sensenova.cn",                           // 商湯
        "xf-yun.com", "xfyun.cn",                 // 訊飛
    )

    private val minimaxIntl = listOf("minimax.io")

    fun host(url: String): String? {
        val t = url.trim()
        if (t.isEmpty()) return null
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "https://$t"
        return runCatching { URI(withScheme).host }.getOrNull()?.lowercase()?.trimEnd('.')
    }

    private fun matches(host: String, domain: String) = host == domain || host.endsWith(".$domain")

    private fun isLocal(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") || host == "::1" || host == "[::1]" ||
            host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)

    fun classify(url: String): Kind {
        val h = host(url) ?: return Kind.OTHER
        return when {
            isLocal(h) -> Kind.LOCAL
            minimaxIntl.any { matches(h, it) } -> Kind.MINIMAX_INTL
            mainlandDomains.any { matches(h, it) } || h.endsWith(".cn") -> Kind.MAINLAND_CLOUD
            else -> Kind.OTHER
        }
    }

    /** 設定頁要顯示的提醒；不需要提醒時回 null */
    fun notice(url: String): Notice? = when (classify(url)) {
        Kind.MAINLAND_CLOUD -> Notice(
            Kind.MAINLAND_CLOUD,
            "大陸廠牌雲端服務",
            "依你的資料規範：人事資料、公文內容、學校內部資訊即使已去識別化也不可以送到這個服務。" +
                "只限個人用途、公開資訊與測試。如果只是要用這家的模型，可以改用國際版或本機離線執行。",
            danger = true,
        )
        Kind.MINIMAX_INTL -> Notice(
            Kind.MINIMAX_INTL,
            "MiniMax 國際版",
            "只可以處理已在本機去識別化的公務文字。語音指令會原樣送出、沒辦法事先去識別化，" +
                "所以不要用語音講人事資料（姓名＋生日、身分證字號、薪資、考績、請假原因、住址電話）或公文內容。",
            danger = false,
        )
        Kind.LOCAL, Kind.OTHER -> null
    }
}
