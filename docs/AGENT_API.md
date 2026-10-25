# Hoonsor Agent API v1 — 語音總控

語音總控同時是 **大腦**（呼叫其他 APP）與 **提供者**（把內建系統動作開放出去）。機器可讀版本：`docs/agent-manifest.json`（與 describe 完全相同，單元測試比對）。

## 協定
協定、SDK、撰寫指南與相容規則只寫在 [AGENT_PROTOCOL.md](AGENT_PROTOCOL.md)（唯一真實來源）。本檔只列語音總控自己提供的動作。

大腦端行為摘要：打開對話畫面時並行呼叫各 APP 的 describe（各 5 秒逾時），動作清單原樣放進 AI 提示詞（deprecated 的不放）；倬自己的 APP 優先於系統動作；summary 與 error.message 直接唸出。

鬧鐘與計時器（`AlarmRouting`，AlarmRoutingTest）：倬的鬧鐘（com.hoonsor.alarm）有 Agent API 時，AI 或離線句型產生的 `alarm_set`／`timer_set` 一律改送它的 `alarm_create`／`timer_start`。預設一次性：不帶 `auto_delete`（交給 APP 預設值）；倬明確說「固定、重複、常駐、保留、響完不要刪」才帶 `auto_delete: false`，AI 帶錯也會依原話修正。退回系統時鐘時 summary 補一句「響完不會自動刪除」的提醒。

## 內建動作（target＝本 APP 套件名）

| 動作 | risk | 參數 | 範例句 | 回傳 data | 狀態 |
|---|---|---|---|---|---|
| app_list | read | query? | 我手機有哪些 APP | apps[]、total | 已實作 |
| app_open | reversible | name | 打開 LINE | package、leavesApp；同名多個回 AMBIGUOUS | 已實作 |
| alarm_set | reversible | hour, minute, label?, days?[1=週一…7=週日] | 明天早上七點叫我 | — | 已實作（交給預設時鐘 APP；有倬的鬧鐘時改送 alarm_create） |
| timer_set | reversible | seconds, label? | 計時五分鐘 | — | 已實作（有倬的鬧鐘時改送 timer_start） |
| phone_dial | reversible | number? / contact? | 打電話給媽媽 | leavesApp | 已實作（開撥號畫面） |
| sms_draft | reversible | to?, body | 傳簡訊跟老婆說我晚點到 | leavesApp | 已實作（開簡訊草稿） |
| map_navigate | reversible | destination, mode? | 導航到台北車站 | leavesApp | 已實作 |
| web_search | read | query | 搜尋今天的天氣 | leavesApp | 已實作 |
| url_open | read | url | 打開 github.com | leavesApp | 已實作 |
| flashlight_set | reversible | on | 開手電筒 | undo | 已實作 |
| volume_set | reversible | stream(media/ring/alarm/notification), percent | 媒體音量調到三十 | undo | 已實作 |
| media_control | reversible | command(play/pause/toggle/next/previous) | 下一首 | — | 已實作 |
| settings_open | read | page | 打開藍牙設定 | leavesApp | 已實作 |
| calendar_event_add | reversible | title, start, end?, location?, allDay? | 下週三下午兩點開人事會議 | leavesApp | 已實作（開新增畫面） |
| battery_status | read | — | 還剩多少電 | percent、charging | 已實作 |
| dispatch_send | reversible | text（原話） | 派工，幫鬧鐘 APP 加貪睡上限 | ticketId、guess；連不上 queued、pending | 已實作 |
| dispatch_status | read | id | 第 12 號派工單進度如何 | ticket | 已實作 |
| backup_export | read | — | 備份設定 | — | 規劃中 |
| backup_import | destructive | — | 從備份還原 | — | 規劃中 |

## 語音派工（dispatch_*）
規格來源：`D:\01-Project\40-利姆路\raphael-dispatch\docs\voice-dispatch-api.md`（以該文件為準）。
- 端點 `POST {派工網址}/api/voice`，只送 `{"text", "source_app":"voice-control"}`；連線 10 秒、讀取 15 秒
- 觸發詞（本機判斷，不經 AI）：幫我派工、派個工、派工、記一個開發需求、記個開發需求、記一個需求、開發需求、我有個點子、我有一個點子、有個點子、跟利姆路說、告訴利姆路、叫利姆路、利姆路。只講觸發詞時會問「要派什麼工？」，下一句整句送出
- 201：唸伺服器 `message`（不自己組句）；400／413：唸「內容無法送出：」＋error；連不上／逾時／5xx：唸 Tailscale 提示並暫存
- 暫存補送時機：打開對話畫面、下一次派工成功、健康檢查頁「立即補送」；每筆每次只送一次
- 查單：`GET /api/tickets/{id}`，狀態翻成中文（待評估、需要你補充說明、等你核准…）
- 不呼叫任何其他會寫入的端點

## 測試
- APP 內：設定 › 測試台，選 APP 與動作、可 dryRun
- LAB 版注入文字跑整條流程：`adb shell am start -n tw.hoonsor.voicehub.lab/tw.hoonsor.voicehub.assistant.AssistantActivity --es text "計時五分鐘"`
- 單元測試：`gradlew testProdDebugUnitTest`（含 manifest 一致性）
