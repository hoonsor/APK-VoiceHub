package tw.hoonsor.voicehub.agent

import org.json.JSONObject

/**
 * 常用的簡單指令直接在手機上處理、不問 AI（省掉 2～6 秒）。
 * 只收「一句話只做一件事」而且句型很明確的：開 APP、音量、手電筒、媒體播放、計時、今天的鬧鐘、電量。
 * 句子裡有「然後、再、順便、嗎」這類字，或句型不完全符合，一律回 null 交給 AI。
 * 開 APP 另外要在 Orchestrator 確認找得到很像的 APP 才走這裡。純邏輯，有 JVM 測試（FastPathTest）。
 */
object FastPath {
    private val compound = Regex("然後|接著|再來|之後|並且|而且|順便|同時|還要|以及|如果|的話|為什麼|怎麼|嗎|呢|是不是|幫我查|告訴我|和|跟")
    private val num = "[0-9零〇一二兩三四五六七八九十百]+"

    private val streams = linkedMapOf(
        "媒體" to "media", "音樂" to "media", "影片" to "media",
        "鈴聲" to "ring", "來電" to "ring", "鬧鐘" to "alarm", "通知" to "notification",
    )
    private val volume = Regex("^(?:把)?(媒體|音樂|影片|鈴聲|來電|鬧鐘|通知)?(?:的)?音量(?:調到|調成|設成|設為|設定為|開到|轉到|調|設)(?:百分之)?($num|最大|最小|靜音)(?:%|％|趴)?$")
    private val muteRing = Regex("^(鈴聲|來電|通知|媒體)(?:關靜音|靜音|關掉)$")
    private val flashlight = Regex("^(?:幫我)?(?:(打開|開啟|開|關掉|關閉|關)手電筒|手電筒(打開|開啟|開|關掉|關閉|關))$")
    private val media = mapOf(
        "下一首" to "next", "下一首歌" to "next", "跳過這首" to "next", "上一首" to "previous", "上一首歌" to "previous",
        "暫停" to "pause", "暫停音樂" to "pause", "音樂暫停" to "pause", "暫停播放" to "pause", "停止播放" to "pause",
        "繼續播放" to "play", "繼續放" to "play", "播放音樂" to "play", "放音樂" to "play",
    )
    private val timer = Regex("^(?:幫我)?(?:設|設定|開始)?(?:一個)?(?:計時|倒數計時|倒數)(?:器)?($num(?:個)?(?:小時|鐘頭|分鐘|分|秒)(?:$num(?:分鐘|分|秒))?半?|半小時)$|^($num(?:個)?(?:小時|鐘頭|分鐘|分|秒)(?:$num(?:分鐘|分|秒))?半?|半小時)(?:的)?(?:計時|倒數計時|倒數)(?:器)?$")
    private val alarmDateWords = Regex("明天|後天|今天|今晚以後|星期|禮拜|週|周|每天|每日|平日|假日|號|月")
    private val alarm = Regex("^(?:幫我)?(?:設|設定|定)?(?:一個)?(?:鬧鐘)?(早上|上午|中午|下午|傍晚|晚上|今晚|凌晨|半夜)?($num[點点](?:半|一刻|三刻|${num}分?)?|[0-9]{1,2}[:：][0-9]{2})(?:的)?(?:叫我|叫醒我|叫我起床|鬧鐘|的鬧鐘)$")
    /** 查電量是唯讀動作，句型放寬：短句裡有電量字眼、又沒有設定或其他動作就算（v0.8.0 太嚴，「電池還剩多少」會掉到 AI） */
    private val batteryAsk = Regex("電量|電池|多少電|幾趴|還有電|剩多少電")
    private val batteryBlock = Regex("設定|省電|最佳化|優化|模式|充電器|然後|接著|順便|再|和|跟|打開|開啟|關|設|調|為什麼|怎麼")

    /** 開 APP 的名稱（只抓名稱，找不找得到 APP 由呼叫端判斷） */
    private val appOpen = Regex("^(?:幫我|請)?(?:打開|開啟|啟動|開)(.{1,20}?)(?:APP|app|應用程式)?$")

    fun match(raw: String): OfflineParser.Call? {
        val t = OfflineParser.normalize(raw)
        if (t.isEmpty() || t.length > 24) return null
        // 電量在複合字詞檢查之前：「電量還有多少呢」「還有電嗎」這種問句也要快
        if (t.length <= 14 && batteryAsk.containsMatchIn(t) && !batteryBlock.containsMatchIn(t)) {
            return OfflineParser.Call("battery_status", JSONObject())
        }
        if (compound.containsMatchIn(t)) return null

        flashlight.find(t)?.let { m ->
            val verb = m.groupValues[1].ifEmpty { m.groupValues[2] }
            return OfflineParser.Call("flashlight_set", JSONObject().put("on", !verb.startsWith("關")))
        }
        media[t]?.let { return OfflineParser.Call("media_control", JSONObject().put("command", it)) }

        volume.find(t)?.let { m ->
            val stream = streams[m.groupValues[1]] ?: "media"
            val percent = when (val v = m.groupValues[2]) {
                "最大" -> 100
                "最小", "靜音" -> 0
                else -> ChineseNumber.parse(v)?.takeIf { it in 0..100 } ?: return null
            }
            return OfflineParser.Call("volume_set", JSONObject().put("stream", stream).put("percent", percent))
        }
        muteRing.find(t)?.let { m ->
            return OfflineParser.Call("volume_set", JSONObject().put("stream", streams.getValue(m.groupValues[1])).put("percent", 0))
        }

        if (timer.matches(t)) {
            OfflineParser.durationSeconds(t)?.let { return OfflineParser.Call("timer_set", JSONObject().put("seconds", it)) }
        }

        // 有日期、星期的鬧鐘交給 AI（「明天七點」在早上六點說時，系統鬧鐘會設成今天）
        if (!alarmDateWords.containsMatchIn(t) && alarm.matches(t)) {
            OfflineParser.clockTime(t)?.let { (h, m) -> return OfflineParser.Call("alarm_set", JSONObject().put("hour", h).put("minute", m)) }
        }

        appOpen.find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            // 「開燈」「開會」這類一個字的不算
            if (name.length >= 2 && name != "手電筒") return OfflineParser.Call("app_open", JSONObject().put("name", name))
        }
        return null
    }

    /** 開 APP 要很有把握才不問 AI：只有一個最高分，而且分數 ≥ 80（完全相同、開頭相同、偏好對照、發音相同） */
    fun confidentApp(name: String, entries: List<AppMatcher.Entry>): AppMatcher.Entry? {
        val ranked = AppMatcher.rank(name, entries)
        val best = ranked.firstOrNull() ?: return null
        if (best.second < 80) return null
        val ties = ranked.filter { it.second == best.second }.distinctBy { it.first.packageName }
        return if (ties.size == 1) best.first else null
    }
}
