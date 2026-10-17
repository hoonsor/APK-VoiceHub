# Hoonsor Agent API 協定（唯一真實來源）

> 所有倬的 APP 都依這份實作。SOP（hoonsor-android-app 技能）、各 APP 的 `docs/AGENT_API.md` 都只引用這裡，不複製內容。
> 網址：https://github.com/hoonsor/APK-VoiceHub/blob/main/docs/AGENT_PROTOCOL.md
> 協定版本：api 1／SDK 1／最後更新 2026-10-17

## 1. 角色
- **提供者**：每個 APP。把自己的功能開放成「動作」
- **大腦**：語音總控（tw.hoonsor.voicehub）。每次打開對話畫面時呼叫各 APP 的 `describe`，把動作清單交給 AI 規劃後 `execute`
- 大腦**不寫死**任何 APP 的動作。APP 改版時，只要遵守第 5 節的相容規則，大腦就不用改，也**不需要通知語音總控專案**

## 2. 怎麼接（用共用 SDK，不要自己寫）
1. 把 `app/src/main/java/tw/hoonsor/agent/HoonsorAgent.kt` **整份複製**到 APP 的同一路徑（套件 `tw.hoonsor.agent`）。不要在 APP 裡改它；要改就改來源、遞增 `SDK_VERSION`，再複製回各 APP
2. 寫 `.agent.AgentProvider` 繼承 `HoonsorAgentProvider`，實作 `describe()`、`execute(request)`，需要時覆寫 `health()`、`timeoutMs(action)`
3. Manifest：
```xml
<permission android:name="tw.hoonsor.permission.AGENT" android:protectionLevel="signature" />
<uses-permission android:name="tw.hoonsor.permission.AGENT" />
<provider android:name=".agent.AgentProvider"
    android:authorities="${applicationId}.agent"
    android:exported="true"
    android:permission="tw.hoonsor.permission.AGENT">
    <intent-filter><action android:name="tw.hoonsor.agent.PROVIDER" /></intent-filter>
</provider>
```
4. 所有 APP **必須用共用簽章 hoonsor-release.jks**，否則拿不到 signature 權限（Android 12+ 不同簽章宣告同名權限還會安裝失敗）
5. `execute` 裡的動作一律走 APP 原本的 Repository，不另寫一份寫入邏輯
6. 複製 `tools/agent_compat_check.py` 到 APP 的 `tools/`，CI 加相容檢查（第 6 節）
7. 寫 `docs/agent-manifest.json`（與 describe 完全相同，JVM 測試比對）和 `docs/AGENT_API.md`（給人看）
8. 設定頁加「API 測試台」；健康檢查頁顯示 Agent API 狀態

## 3. 方法
`ContentProvider.call(method, null, extras)`，結果放在回傳 Bundle 的 `json`。

| 方法 | 輸入 | 回傳 |
|---|---|---|
| `describe` | — | `{api:1, app, package, version, description, actions:[{name, title, description, risk, params, examples, deprecated?}]}` |
| `execute` | extras `json`＝`{api, requestId, action, params, dryRun, confirmToken, utterance}` | 成功 `{ok:true, requestId, summary, data, undo}`；失敗 `{ok:false, requestId, error:{code, message, data?}}` |
| `health` | — | `{ok, issues:[...]}` |

- `name` 只用 `[a-z0-9_]`，格式 `<物件>_<動詞>`（alarm_create、photo_list）；每個 APP 至少要有一個查詢類動作 `*_list`
- `params` 是 JSON Schema（type object、properties、required）
- 逾時：預設 5 秒；要連網的動作可覆寫 `timeoutMs`，最多 30 秒

### risk
| 值 | 大腦的行為 | APP 該做的 |
|---|---|---|
| `read` | 直接執行 | 不改任何資料 |
| `reversible` | 直接執行 | 盡量回 `undo:{action, params}` |
| `destructive` | 一律先確認 | 沒帶有效 `confirmToken` 時回 `NEEDS_CONFIRMATION`（data.confirmToken）；帶了才執行 |

### 錯誤碼
`INVALID_PARAMS`、`NOT_FOUND`、`AMBIGUOUS`（data.candidates：字串陣列）、`NEEDS_CONFIRMATION`（data.confirmToken）、`NEEDS_UI`（data.intentUri，`Intent.toUri(URI_INTENT_SCHEME)`）、`PERMISSION`、`INTERNAL`。大腦自用：`UNAVAILABLE`。

### data 的約定
- `leavesApp: true`：這個動作會開啟畫面，大腦唸完結果後自動收起對話視窗
- 查詢結果放 data，大腦會讓 AI 整理後唸出

## 4. 撰寫指南（AI 判斷準不準就看這裡）
- **examples**：寫倬真的會講的台灣口語，至少 2 句，涵蓋不同講法（「七點叫我」「設七點的鬧鐘」）。AI 主要靠例句決定要叫哪個動作
- **title**：4–8 個字的中文動作名，會顯示在對話畫面的動作晶片上
- **description**：一句話說清楚做什麼、不做什麼、跟其他相似動作怎麼區分
- **summary**：成功後**直接被唸出來**。一句台灣口語、包含關鍵結果（「已新增明天 07:00 的鬧鐘」），不要寫「操作成功」
- **error.message**：也會被唸出來。說原因＋建議（「找不到叫運動的鬧鐘，要不要先列出全部？」）
- 參數用語意化的名稱與單位（`seconds`、`hour`），description 寫範圍與單位；能用 enum 就用 enum
- 會刪資料、覆蓋資料、對外發送的動作一律 `destructive`

## 5. 相容規則（APP 改版時必守）
動作名稱與參數是**公開契約**。大腦、未來的電腦端大腦、捷徑、自動化都可能記得它們。

| 可以直接做（相容） | 不可以做（破壞性） | 要改時的正確做法 |
|---|---|---|
| 新增動作 | 刪除或改名動作 | 新增新動作；舊的加 `deprecated: true` 保留**至少一個正式版**後才刪 |
| 新增**選填**參數 | 新增必填參數 | 新參數一律選填或有預設值 |
| 改 title、description、examples、summary 措辭 | 刪參數、改型別、改單位或意義 | 開新參數（如 `minutes` → 新增 `seconds`），舊參數保留 |
| 放寬範圍、增加 enum 選項 | 縮小範圍、移除 enum 選項 | — |
| risk 提高（reversible → destructive） | risk 降低（需說明理由） | 檢查工具會提醒 |

`deprecated: true` 的動作：仍可執行，但大腦不再把它給 AI 看。

## 6. 自動檢查（取代人工通知）
- 每個 APP 的 CI 執行 `python3 tools/agent_compat_check.py --git-baseline docs/agent-manifest.json`，與最近一個 `v*` tag 的 manifest 比對；有破壞性變更時編譯失敗
- 真的必須破壞（協定升版）時，commit 訊息加 `BREAKING-AGENT-API`，只警告不擋。**這種情況才要在語音總控的 PROJECT_STATUS.md 待辦加一筆**
- JVM 測試：`docs/agent-manifest.json` 與 describe 完全相同

## 7. 什麼時候要更新語音總控（其餘情況都不用）
1. 協定升版（api 2：新增方法、新錯誤碼、傳檔案、串流）
2. 某個 APP 需要大腦做特別處理（例：語音派工要原話直送、不經 AI）
3. 新的 data 約定（例：新增 `leavesApp` 這類旗標）

## 8. 變更紀錄
- 2026-10-17 api 1／SDK 1：初版；加入 deprecated、相容規則、撰寫指南、相容檢查工具
