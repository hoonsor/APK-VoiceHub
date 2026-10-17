package tw.hoonsor.voicehub.agent

import tw.hoonsor.voicehub.agent.Schema.bool
import tw.hoonsor.voicehub.agent.Schema.int
import tw.hoonsor.voicehub.agent.Schema.intArray
import tw.hoonsor.voicehub.agent.Schema.obj
import tw.hoonsor.voicehub.agent.Schema.str

/**
 * 大腦內建的「系統動作」：透過 Android 標準 Intent 或系統服務操作手機與第三方 APP。
 * 純資料，JVM 測試會拿它與 docs/agent-manifest.json 比對，兩邊要一致。
 */
object BuiltinCatalog {
    const val APP_NAME = "系統（語音總控內建）"
    const val DESCRIPTION = "用 Android 標準方式操作手機：開啟 APP、鬧鐘與計時器、撥號、簡訊草稿、導航、搜尋、手電筒、音量、媒體播放、系統設定頁；以及把開發需求語音派工給利姆路。"

    val SETTINGS_PAGES = listOf("main", "wifi", "bluetooth", "display", "sound", "battery", "location", "apps", "notifications", "data", "airplane", "date")
    val STREAMS = listOf("media", "ring", "alarm", "notification")
    val MEDIA_COMMANDS = listOf("play", "pause", "toggle", "next", "previous")
    val NAV_MODES = listOf("driving", "walking", "transit", "bicycling")

    val actions: List<ActionSpec> = listOf(
        ActionSpec(
            "app_list", "列出 APP", "列出手機上可開啟的 APP 名稱，可用關鍵字篩選。", Risk.READ,
            obj("query" to str("名稱關鍵字，可省略")),
            listOf("我手機有哪些 APP", "有沒有裝 LINE"),
        ),
        ActionSpec(
            "app_open", "開啟 APP", "依名稱開啟手機上的任一 APP（名稱可模糊比對）。", Risk.REVERSIBLE,
            obj("name" to str("APP 名稱，例如 LINE、相機、YouTube"), required = listOf("name")),
            listOf("打開 LINE", "開相機", "幫我開 YouTube"),
        ),
        ActionSpec(
            "alarm_set", "設定鬧鐘（系統時鐘）", "用系統時鐘 APP 新增鬧鐘，不跳畫面。", Risk.REVERSIBLE,
            obj(
                "hour" to int("時（0-23）", 0, 23),
                "minute" to int("分（0-59）", 0, 59),
                "label" to str("鬧鐘名稱，可省略"),
                "days" to intArray("重複的星期，1=週一…7=週日；省略表示只響一次"),
                required = listOf("hour", "minute"),
            ),
            listOf("明天早上七點叫我", "設一個六點半的鬧鐘叫運動", "平日早上七點十分的鬧鐘"),
        ),
        ActionSpec(
            "timer_set", "設定計時器（系統時鐘）", "用系統時鐘 APP 開始倒數計時，不跳畫面。", Risk.REVERSIBLE,
            obj(
                "seconds" to int("倒數秒數", 1, 86_400),
                "label" to str("計時器名稱，可省略"),
                required = listOf("seconds"),
            ),
            listOf("計時五分鐘", "三分鐘後提醒我泡麵好了"),
        ),
        ActionSpec(
            "phone_dial", "撥號", "打開撥號畫面並帶入號碼（由使用者按下撥出）。可給號碼或聯絡人名稱。", Risk.REVERSIBLE,
            obj("number" to str("電話號碼"), "contact" to str("聯絡人名稱（需聯絡人權限）")),
            listOf("打電話給媽媽", "撥 0912345678"),
        ),
        ActionSpec(
            "sms_draft", "簡訊草稿", "打開簡訊 APP 並填好收件人與內容（由使用者按下傳送）。", Risk.REVERSIBLE,
            obj("to" to str("電話號碼或聯絡人名稱"), "body" to str("簡訊內容"), required = listOf("body")),
            listOf("傳簡訊跟老婆說我晚點到"),
        ),
        ActionSpec(
            "map_navigate", "導航", "用地圖 APP 導航到目的地。", Risk.REVERSIBLE,
            obj(
                "destination" to str("目的地名稱或地址"),
                "mode" to str("交通方式", NAV_MODES),
                required = listOf("destination"),
            ),
            listOf("導航到台北車站", "走路去最近的全聯"),
        ),
        ActionSpec(
            "web_search", "網路搜尋", "用瀏覽器搜尋關鍵字。", Risk.READ,
            obj("query" to str("搜尋字詞"), required = listOf("query")),
            listOf("搜尋今天的天氣", "查一下 Kotlin 協程"),
        ),
        ActionSpec(
            "url_open", "開啟網址", "用瀏覽器或對應 APP 開啟網址。", Risk.READ,
            obj("url" to str("完整網址"), required = listOf("url")),
            listOf("打開 github.com"),
        ),
        ActionSpec(
            "flashlight_set", "手電筒", "開或關手電筒。", Risk.REVERSIBLE,
            obj("on" to bool("true＝開、false＝關"), required = listOf("on")),
            listOf("開手電筒", "關掉手電筒"),
        ),
        ActionSpec(
            "volume_set", "調整音量", "把指定音量調到百分比。", Risk.REVERSIBLE,
            obj(
                "stream" to str("音量類型", STREAMS),
                "percent" to int("0-100", 0, 100),
                required = listOf("stream", "percent"),
            ),
            listOf("媒體音量調到三十", "鈴聲關靜音", "鬧鐘音量開到最大"),
        ),
        ActionSpec(
            "media_control", "媒體播放控制", "控制目前播放中的音樂或影片。", Risk.REVERSIBLE,
            obj("command" to str("指令", MEDIA_COMMANDS), required = listOf("command")),
            listOf("暫停音樂", "下一首", "繼續播放"),
        ),
        ActionSpec(
            "settings_open", "開啟系統設定", "打開指定的系統設定頁。", Risk.READ,
            obj("page" to str("設定頁", SETTINGS_PAGES), required = listOf("page")),
            listOf("打開藍牙設定", "Wi-Fi 設定", "我要改螢幕亮度"),
        ),
        ActionSpec(
            "calendar_event_add", "新增行事曆活動", "打開行事曆的新增畫面並填好內容（由使用者按儲存）。", Risk.REVERSIBLE,
            obj(
                "title" to str("活動名稱"),
                "start" to str("開始時間，ISO 格式 yyyy-MM-ddTHH:mm"),
                "end" to str("結束時間，ISO 格式，可省略（預設一小時）"),
                "location" to str("地點，可省略"),
                "allDay" to bool("是否整天"),
                required = listOf("title", "start"),
            ),
            listOf("下週三下午兩點開人事會議", "明天整天請假"),
        ),
        ActionSpec(
            "battery_status", "電池狀態", "查詢電量與是否充電中。", Risk.READ,
            obj(),
            listOf("還剩多少電", "有在充電嗎"),
        ),
        ActionSpec(
            "dispatch_send", "語音派工", "把開發需求或點子送到利姆路的派工收件匣，由背景的 Claude 整理與派工。text 必須是使用者的原話（只去掉「派工」「我有個點子」這類觸發詞），不可摘要、改寫或拆單。回覆時直接唸 summary。", Risk.REVERSIBLE,
            obj("text" to str("使用者原話（去掉觸發詞），1-5000 字"), required = listOf("text")),
            listOf("派工，幫鬧鐘 APP 加一個貪睡次數上限", "我有個點子，做一個記帳工具可以拍收據", "叫利姆路把 27 號專案跑一下收工"),
        ),
        ActionSpec(
            "dispatch_status", "查派工單", "查詢某張派工單目前的狀態。", Risk.READ,
            obj("id" to int("派工單編號", 1), required = listOf("id")),
            listOf("第 12 號派工單進度如何", "12 號單做好了沒"),
        ),
    )

    val DISPATCH_ACTIONS = setOf("dispatch_send", "dispatch_status")

    fun catalog(packageName: String, version: String) = AgentCatalog(
        app = APP_NAME,
        packageName = packageName,
        version = version,
        description = DESCRIPTION,
        actions = actions,
        builtin = true,
    )
}
