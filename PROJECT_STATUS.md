# PROJECT_STATUS — 語音總控（APK-VoiceHub）

- 目前版本：v0.3.3（main 推送後由 CI 發佈 latest；使用說明書已在模擬器驗證）
- 程式碼：`D:\dev\APK-VoiceHub`；專案資料夾 `D:\01-Project\42-語音總控制APP`（CODE_LOCATION.txt 指過來）
- 套件：`tw.hoonsor.voicehub`（LAB：`tw.hoonsor.voicehub.lab`）

## 任務
- [x] 骨架：Gradle、prod／lab flavor、共用簽章讀環境變數、CI workflow
- [x] 對話畫面（極光光影、泡泡、動作晶片、確認列、打字）
- [x] 喚醒：預設數位助理（VoiceInteractionService）、桌面圖示、快速設定磚、長按圖示捷徑
- [x] 語音：AudioRecord＋VAD、Groq Whisper、Android 內建辨識備援、TTS
- [x] AI：MiniMax 主力、Groq 備援、離線句型；固定 JSON 規劃格式
- [x] Agent API：探索其他 APP、execute／describe／health、本 APP 的 Provider
- [x] 內建 15 個系統動作
- [x] 語音派工（dispatch_send、dispatch_status、離線暫存補送）
- [x] 設定、健康檢查、API 測試台
- [x] 單元測試（離線句型、APP 比對、規劃解析、VAD、派工觸發與回應、manifest 一致性）
- [x] 建立 GitHub repo（public）、CI 發佈 latest（目前 debug 簽章）
- [x] 把 hoonsor/APK-VoiceHub 加進 setup-hoonsor-signing.ps1 的 $Repos（2026-10-17）
- [x] 倬在電腦 A 執行 setup-hoonsor-signing.ps1 產生 hoonsor-release.jks 並寫入 Secrets（2026-10-18，latest 已驗證為共用簽章）
- [x] 使用說明書分頁 F30（v0.2.0）：docs/manual/MANUAL.md 唯一來源、搜尋、點例句帶入、即時狀態、ManualTest、每日收錄檢查 manual_coverage_check.py
- [x] 協定唯一來源 AGENT_PROTOCOL.md、共用 SDK HoonsorAgent.kt、CI 相容檢查、deprecated 支援
- [ ] 實機驗證（見下方清單）
- [ ] Claude Design 版面方案比較（SOP 第 3 階段，事後補）
- [ ] 備份四件套（F23）
- [ ] 各 APP（鬧鐘、照片整理箱、輸入法、螢幕翻譯）補 Agent API

## 版本歷程
| 版本 | 日期 | commit | 變更 | 為什麼 |
|---|---|---|---|---|
| v0.3.3 | 2026-10-26 | （本次） | 修正音量鍵手勢在桌面按不會跳出：Android 15 起「顯示在其他應用程式上層」權限必須搭配看得見的上層視窗才能從背景開畫面，改成先放 1 像素透明視窗再開；1.5 秒內畫面沒出現就改發通知並提示 ColorOS「背景彈出介面」權限；待命通知改為低優先度（原本最低優先度被 ColorOS 藏起來）。API 37 模擬器驗證：桌面按加減加 → 對話畫面跳出、音量還原 | 倬實測：設定頁內偵測得到，回到桌面按了沒反應 |
| v0.3.2 | 2026-10-25 | （本次） | 新增「常用地點」：設定頁每行「名稱=地點」，說「回家」「導航去公司」就導航到設定的地點；地點只存在手機，AI 只看到名稱（Planner 規則 12）；離線句型支援「回家」「導航到…」（Places＋4 個測試） | 倬要說「回家」就導航到住家附近好找的地標 |
| v0.3.1 | 2026-10-25 | （本次） | 鬧鐘／計時一次性：新增 `AlarmRouting`（12 個測試）。倬的鬧鐘有 Agent API 時，AI 或離線句型產生的系統 alarm_set／timer_set 一律改送 alarm_create／timer_start（原本離線模式一定走系統時鐘、AI 只靠提示詞規則 2）；預設不帶 auto_delete（交給 APP 預設一次性），倬說「固定、重複、常駐、保留、響完不要刪」才帶 false，AI 帶錯會依原話修正；舊版鬧鐘 APP 沒有 auto_delete 參數時不帶；提示詞新增規則 11；退回系統時鐘時回覆補「響完不會自動刪除」提醒；說明書內建「設定鬧鐘」「計時器」兩節同步 | 倬的鬧鐘 v0.12.0（commit b65e1c4）新增 auto_delete 選填參數，倬要求語音新增的鬧鐘與計時器預設一次性 |
| v0.3.0 | 2026-10-25 | （本次） | 新增「音量鍵叫出」：常駐待命服務（specialUse 前台服務）只等媒體音量改變的系統廣播，按出「加、減、加」（可改減加減、連按 3 下）就叫出對話畫面並把音量調回；預設數位助理走 showSession，否則需「顯示在其他應用程式上層」，都沒有就發通知；螢幕鎖定不叫出；開機與更新後恢復；設定頁顯示權限狀態與偵測紀錄（VolumeGestureDetector＋7 個測試） | 倬的 OPENEAR Air X3 連按功能鍵 3 下（VOICE_COMMAND）大多沒反應，音量鍵則每次都有反應 |
| v0.2.3 | 2026-10-25 | （本次） | 導航新增機車模式（mode=motorcycle → Google 地圖 l），沒指定交通方式時用設定的預設（預設機車）；設定頁新增「導航預設交通方式」；說明書導航章節同步 | 倬要預設機車導航 |
| v0.2.2 | 2026-10-25 | （本次） | 設定頁新增「朗讀聲音」：可選朗讀引擎與中文聲音、每個聲音都能試聽、選了立刻生效、從系統下載語音回來自動更新清單（VoiceCatalog＋4 個測試）；健康檢查的測試朗讀顯示目前聲音；修正 APP 剛啟動時朗讀引擎還沒初始化、第一句回覆不會唸的問題（最多等 3 秒） | 倬想自己選回應的人聲 |
| v0.2.1 | 2026-10-23 | （本次） | 設定頁的 AI 網址填入大陸廠牌雲端服務時顯示紅框警告、MiniMax 國際版顯示橘框提醒（ProviderPolicy＋測試）；README 新增「隱私與資料規範」：何時錄音、資料送到哪、可以講與不要講什麼 | AI-PROFILE v0.4.4／v0.4.5 修改了大陸廠牌與公務資料規則，倬要求 APP 內提醒與說明跟著改 |
| v0.2.0 | 2026-10-18 | （本次） | 新增「說明書」分頁：內建 17 個動作＋倬注音、照片整理箱、螢幕翻譯共 76 節，每節多句例句；搜尋、目錄、點例句帶進對話畫面（`EXTRA_DRAFT`，不自動送出）；依手機即時偵測標示可用／規劃中／未安裝，並列出未收錄的動作；CI 每天檢查所有 APK-* repo 的動作都已收錄 | 倬要工具書式的使用說明，並要求之後新增可供語音總控操作的 APP 時一定要更新說明書 |
| v0.1.0 | 2026-10-17 | 初始 | 第一版：對話畫面、喚醒、語音、AI 規劃、15 個系統動作、Agent API 大腦＋提供者、語音派工、設定／健康檢查／測試台 | 倬要一個 Gemini Live 式的總入口，能用說的控制自己開發的 APP 與手機；派工規格由 40-利姆路提供 |

## 待實機驗證（OPPO Reno 14F，請用編號回報）
1. [ ] 長按電源鍵能叫出對話畫面（需先設預設數位助理）
2. [ ] 桌面圖示、快速設定磚都能叫出，且一打開就在聽
3. [ ] 講完停一下會自動送出；按方塊鈕能提早結束
4. [ ] 填 Groq key 後中文辨識準確（台灣用語、繁體）
5. [ ] 填 MiniMax key 後「明天早上七點叫我」→ 時鐘 APP 新增鬧鐘並唸出結果
6. [ ] 「打開 LINE」開啟後對話視窗自動收起，結果有唸完
7. [ ] 「今天天氣如何」之類做不到的事會直說做不到
8. [ ] 開 Tailscale 後「派工，測試：幫鬧鐘 APP 加貪睡上限」→ 唸出伺服器回的幾號單（**測完到派工網站按取消**）
9. [ ] 關 Tailscale 再派工 → 唸連不上並暫存；打開 Tailscale 後再開對話畫面 → 自動補送
10. [ ] 「第 1 號派工單進度如何」→ 唸出狀態
11. [ ] 光影動畫順暢、不耗電異常；深色畫面的狀態列圖示看得清楚
12. [ ] ColorOS 不顯示的提示都在 APP 畫面上看得到
13. [ ] 設定 › 音量鍵叫出 打開後通知列出現「音量鍵待命中」；按耳機音量，偵測紀錄出現「音量 X → Y」
14. [ ] 回到桌面，耳機按「加、減、加」→ 對話畫面跳出、音量回到原本的值（試 5 次以上）
15. [ ] 重開機後不打開 APP，音量手勢仍有效（需允許自啟動）
16. [ ] 用一天後看 設定 › 電池 的耗電比例（預估每天 0.1% 以下，超過 1% 回報）
17. [ ] 裝了倬的鬧鐘 v0.12.0 後說「明天七點叫我」→ 倬的鬧鐘新增一次性鬧鐘（列表標一次性），不是系統時鐘
18. [ ] 「設一個固定的明天八點鬧鐘」「常駐的二十五分鐘計時器」→ 響完留著
19. [ ] 關掉網路（離線模式）說「計時五分鐘」→ 一樣交給倬的鬧鐘、一次性

## 已知問題
- 模擬器（API 37）已驗證：對話畫面、測試台、app_list、alarm_set（交給倬的鬧鐘 APP 成功新增 00:00）、離線模式開 YouTube、查單與派工連不上分支。模擬器不在 Tailscale 內，派工成功分支未在裝置上驗證（電腦端 `/api/health` 正常）
- ACTION_SET_ALARM 的 SKIP_UI 是否被尊重取決於處理的時鐘 APP（倬的鬧鐘 APP 會開畫面）
- 預設模型 ID `MiniMax-M3`、`llama-3.3-70b-versatile` 若被下架，請在設定改 ID（測試按鈕會提示）
- 尚未做備份匯出／匯入

## 下一步
1. 實機驗證上面清單（裝最新 latest，用 Obtainium 或 App 內更新），依編號回報修正
2. 鬧鐘 APP 補 Agent API（照 AGENT_PROTOCOL.md：複製 SDK、AgentProvider、相容檢查）。目前四個 APP 都只有 manifest、沒有 Provider，語音總控實際能用的只有內建動作
3. 依序補倬注音、照片整理箱、螢幕翻譯的 Provider
4. 備份四件套、對話紀錄
5. Claude Design 補版面方案比較，更新 DESIGN.md；修 lintLabDebug 的 startActivityAndCollapse 棄用警告

## 交接紀錄
### 2026-10-25（鬧鐘一次性）／電腦 A
- 完成：v0.3.1 鬧鐘／計時改送倬的鬧鐘＋預設一次性（AlarmRouting）。單元測試全過、說明書收錄檢查通過
- 決定＋原因：APP 端只新增選填參數（相容變更），理論上 describe 說明就夠；但離線句型寫死系統動作、AI 也可能不照規則選或自作主張帶 auto_delete，所以在 Orchestrator 執行前用本機規則統一修正，結果可預期、有測試
- 放棄：在 BuiltinActions 的 alarm_set 內轉呼叫鬧鐘 APP（會讓系統動作語意混亂、測試台也看不出實際送去哪）；退回系統時鐘時改開時鐘畫面讓倬手動設定（多一步操作，提醒一句就夠）
- 未驗證：實機上「明天七點叫我」→ 倬的鬧鐘新增一次性鬧鐘、「設一個固定的明天八點鬧鐘」→ 響完留著、離線模式（拔網路）同樣走倬的鬧鐘
- 環境注意：無新增環境變數或套件

### 2026-10-25（續）／電腦 A
- 完成：v0.3.0 音量鍵手勢叫出。單元測試全過；模擬器確認待命服務以前台服務啟動
- 決定＋原因：用 VOLUME_CHANGED_ACTION 廣播偵測（不需無障礙服務、不錄音、耗電極低，耳機與手機側鍵都適用）；預設按法「加、減、加」避免平常調音量誤觸，按完自動還原音量；從背景叫出時優先走數位助理 showSession（不受背景啟動限制）
- 放棄：無障礙服務攔截按鍵（權限大、ColorOS 常關掉、藍牙耳機音量多半走絕對音量不會送按鍵）；MediaSession VolumeProvider（會搶走媒體音量控制）
- 未驗證：API 37 模擬器的媒體音量改不動（setStreamVolume 被忽略），手勢觸發只能實機驗證；OPENEAR Air X3 的音量是否會改變手機媒體音量未知，看偵測紀錄
- 環境注意：本機 gradle 要 `JAVA_HOME=C:\Users\hoonsor\.jdks\jbr-21.0.11`；Git Bash 跑 adb push 會把 /data 路徑改寫，改用 PowerShell

### 2026-10-25／電腦 A
- 完成：v0.2.2 朗讀聲音選擇。模擬器驗證：下載台灣中文語音後列出 6 個聲音（3 離線、3 需連網）、試聽、選擇後重開與重裝仍保留、測試朗讀確認實際使用所選聲音、冷啟動第一句可以唸
- 決定＋原因：用 Android 系統的 TextToSpeech 聲音（免費、離線可用、沒有延遲）；雲端語音（更自然但要連網、多 0.5～1 秒、回覆送雲端）先不做，倬聽過系統聲音後再決定
- 發現：Google 語音服務的中文聲音要先下載語音資料才會出現，清單已加入引導按鈕

### 2026-10-23／電腦 A
- 完成：v0.2.1。設定頁 AI 網址的大陸廠牌警告（ProviderPolicy＋5 個測試）、README「隱私與資料規範」；AI-PROFILE 已到 v0.4.5 並同步（10/21 補進 CLAUDE.md 被直接改寫的 4 條規則）
- 決定＋原因：網址分類只比對網域結尾，避免被 query 或相似字串騙過；本機與區網位址不提醒（AI-PROFILE 允許本機離線模型）
- 未完成：實機驗證；各 APP 的 Provider

### 2026-10-18（續）／電腦 A
- 完成：v0.2.0 使用說明書（F30）。內容只寫在 `docs/manual/MANUAL.md`，由 `build.gradle.kts` 的 assets srcDirs 直接打包；`manual/Manual.kt` 負責解析、搜尋與狀態判斷，`ui/ManualScreen.kt` 是畫面；`ManualTest` 10 個測試；`tools/manual_coverage_check.py`（含 7 個測試）在 CI 的 `manual` job 每天 09:00 執行；模擬器驗證過目錄跳轉、搜尋、例句帶入、「規劃中／未安裝」標籤
- 決定＋原因：
  - 說明書用受限的 Markdown＋`<!-- action: 套件:動作 -->` 標記，而不是 JSON：GitHub 上可以直接閱讀，也方便機器檢查
  - 狀態不寫在說明書文字裡，改成即時偵測：其他 APP 實作 API 時不用回頭改說明書
  - 點例句只帶入、不送出：正式版也能開放這個入口，不會被其他 APP 拿來觸發動作
  - 收錄檢查放在語音總控的每日排程：其他 APP 的 repo 不用改 CI，新 APP 只要有 agent-manifest.json 就會被檢查到
- 規則：AGENT_PROTOCOL.md 第 2 節第 9 步、第 6、7 節；AGENTS.md Always；SOP 技能 hoonsor-android-app v1.2.0（36 號專案 `skills/`，上傳用 zip 已更新，倬要重新上傳到 claude.ai）
- 發現：倬注音、照片整理箱、螢幕翻譯的 Agent API 都還是「規劃中」（只有 manifest，沒有 AgentProvider），所以目前語音總控實際能用的只有內建動作
- 已知：`lintLabDebug` 有一個舊錯誤（VoiceServices.kt 的 `startActivityAndCollapse(Intent)` 已棄用），不影響 assembleRelease，之後再修

### 2026-10-18／電腦 A
- 完成：setup-hoonsor-signing.ps1 加入 APK-VoiceHub，並改存為含 BOM 的 UTF-8（原本無 BOM，在 Windows PowerShell 5.1 下中文會亂碼）；各中文 APP 資料夾補上 CODE_LOCATION.txt 與指向 D:\dev 的 CLAUDE.md
- 決定＋原因：倬選定免接線更新方案 B＋D（GitHub Releases＋Obtainium、App 內自我更新），鬧鐘 APP 已先實作（hoonsor/APK-Alarm v0.11.0）
- 未完成：共用金鑰尚未產生，各 repo 都還沒有 SIGNING_* Secrets，目前 CI 沒有 Secrets 時改用 debug 簽章，而 debug 簽章每次執行都不同，無法覆蓋安裝

### 2026-10-17（續）／電腦 A
- 完成：建立 public repo、CI 綠燈並發佈 latest；依倬同意修改規則：新增 docs/AGENT_PROTOCOL.md（唯一來源）、共用 SDK、相容檢查工具與測試、deprecated 欄位；SOP 技能 v1.1.0 原始檔與上傳 zip 放在 36 號專案 skills/；AI-PROFILE v0.4.2
- 決定＋原因：APP 改版不通知大腦，改用相容規則＋CI 自動檢查（大腦每次即時讀 describe，不寫死動作）；協定只寫一份避免不一致
- 未完成：倬要到 claude.ai 上傳技能 zip、把 36 號專案 claude-project-updates/2026-10-17-語音相容規則.md 的內容貼進 Project 文件；共用簽章 Secrets；AI-PROFILE 同步腳本未正式執行（會整份覆寫全域規則檔，待倬確認）

### 2026-10-17／電腦 A
- 完成：v0.1.0 全部程式、單元測試、模擬器驗證、文件、CI workflow；中途依倬指示加入語音派工
- 決定＋原因：
  - 第三方 APP 只用標準 Intent、不用無障礙（倬選定；穩定、免特殊權限）
  - AI 輸出固定 JSON 而非 tool calling（各家相容性不一）
  - 派工觸發詞本機判斷、原話直送、唸伺服器 message（派工規格要求不在 APP 端改寫）
  - 派工失敗只暫存不立即重送（規格：避免重複開單）
  - 大腦也宣告 AGENT 權限（安裝順序不同時仍拿得到 signature 權限）
  - 先實作再補 Claude Design 方案：倬要求「開始嘗試建立」，版面方案改為事後補
- 放棄＋原因：
  - 在 VoiceInteractionSession 視窗內畫 UI：改用獨立 Activity，三種喚醒方式共用同一畫面
  - 靜態 shortcuts.xml：LAB 版套件名不同會失效，改用動態捷徑
  - R8 縮減：先求穩定，APK 約 18MB，之後再開
- 未完成：GitHub repo 與簽章、實機驗證、備份、Design 方案
- 環境注意：本機建置用 `C:\Users\hoonsor\.jdks\jbr-21.0.11`；模擬器 `Medium_Phone_API_37.0`；不需要新的環境變數
