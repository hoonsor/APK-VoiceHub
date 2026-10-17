# Hoonsor Agent API v1 — 語音總控

語音總控同時是 **大腦**（呼叫其他 APP）與 **提供者**（把內建系統動作開放出去）。機器可讀版本：`docs/agent-manifest.json`（與 describe 完全相同，單元測試比對）。

## 協定（所有倬的 APP 共用）
- 權限：`tw.hoonsor.permission.AGENT`（signature）。每個 APP 都宣告並 uses-permission，**必須同一把簽章**
- Provider：exported、authorities `${applicationId}.agent`、permission 同上、intent-filter action `tw.hoonsor.agent.PROVIDER`
- 大腦用 `<queries><intent><action android:name="tw.hoonsor.agent.PROVIDER"/></intent></queries>` ＋ `queryIntentContentProviders` 探索
- `ContentProvider.call(method, null, extras)`，結果在回傳 Bundle 的 `json`

| 方法 | 輸入 | 回傳 |
|---|---|---|
| `describe` | — | `{api:1, app, package, version, description, actions:[{name,title,description,risk,params,examples}]}` |
| `execute` | extras `json`＝`{api, requestId, action, params, dryRun, confirmToken, utterance}` | 成功 `{ok:true, requestId, summary, data, undo}`；失敗 `{ok:false, requestId, error:{code,message,data?}}` |
| `health` | — | `{ok, issues:[...]}` |

錯誤碼：`INVALID_PARAMS`、`NOT_FOUND`、`AMBIGUOUS`（data.candidates）、`NEEDS_CONFIRMATION`（data.confirmToken）、`NEEDS_UI`（data.intentUri）、`PERMISSION`、`INTERNAL`。大腦端另有 `UNAVAILABLE`（沒回應／已卸載）。

risk：`read` 直接執行；`reversible` 直接執行並盡量回 undo；`destructive` 必須先回 `NEEDS_CONFIRMATION`。
`summary` 會被大腦直接唸出來：寫成一句台灣口語。會跳到別的 APP 的動作請在 data 放 `leavesApp: true`。

### 大腦怎麼用你的 APP（給其他 APP 的開發者）
1. 打開對話畫面時並行呼叫每個 APP 的 describe（各 5 秒逾時）
2. 動作清單（name、risk、title、description、params、examples）原樣放進 AI 提示詞；**examples 寫倬真的會講的話**，越像越準
3. 倬自己的 APP 優先於系統動作；execute 逾時 5 秒
4. 失敗時把 `error.message` 唸給倬聽，所以 message 要寫人話

## 內建動作（target＝本 APP 套件名）

| 動作 | risk | 參數 | 範例句 | 回傳 data | 狀態 |
|---|---|---|---|---|---|
| app_list | read | query? | 我手機有哪些 APP | apps[]、total | 已實作 |
| app_open | reversible | name | 打開 LINE | package、leavesApp；同名多個回 AMBIGUOUS | 已實作 |
| alarm_set | reversible | hour, minute, label?, days?[1=週一…7=週日] | 明天早上七點叫我 | — | 已實作（交給預設時鐘 APP） |
| timer_set | reversible | seconds, label? | 計時五分鐘 | — | 已實作 |
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
