# PROJECT_STATUS — 語音總控（APK-VoiceHub）

- 目前版本：v0.11.0（main 推送後由 CI 發佈 latest；使用說明書已在模擬器驗證）
- 程式碼：`D:dev\APK-VoiceHub`；專案資料夾 `D:\01-Project\42-語音總控制APP`（CODE_LOCATION.txt 指過來）
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
- [x] 專案規劃觸發詞（v0.5.0）：「專案規劃」等觸發詞送出時加 `【專案規劃】` 前綴，後端照技能 hoonsor-project-planning 建專案＋開 Remote Control
- [ ] 實機驗證（見下方清單）
- [ ] Claude Design 版面方案比較（SOP 第 3 階段，事後補）
- [ ] 備份四件套（F23）
- [ ] 各 APP（鬧鐘、照片整理箱、輸入法、螢幕翻譯）補 Agent API
- [ ] 自建地圖（tw.hoonsor.bikemap，94 號）配合（協定第 7 節「APP 需要大腦特別處理」，2026-11-14 登記）：句子含 `(?i)(you|u)[\s\-]?bike|微笑單車|優拜克` 時跳過 `Places.shortcut`／`Places.goTo`／`OfflineParser` 的導航快速路徑，交給 AI 選 `trip_navigate`；內建 `map_navigate` 的 description 補「提到 YouBike／U-Bike 改用自建地圖」；加單元測試（「導航去台北車站騎 YouBike」不得變成 map_navigate）。與自建地圖第一個有 Agent API 的版本一起上線。細節見 hoonsor/APK-BikeMap `docs/AGENT_API.md`「語音總控需要配合的事」

## 版本歷程
| 版本 | 日期 | commit | 變更 | 為什麼 |
|---|---|---|---|---|
| v0.11.0 | 2026-11-10 | （本次） | 每日自動備份：新增共用模組 tw/hoonsor/backup（hoonsor-kit android-auto-backup 1.0.0，Java、零依賴、JobScheduler＋MediaStore），每天把 settings 偏好匯出成 JSON 存到 Documents/HoonsorBackup/VoiceHub/（LAB 版 VoiceHub-LAB），內容沒變不另存、只留 7 份；設定頁新增「自動備份」：開關、上次備份、立即備份、從備份檔還原；API key（vault）不備份 | 倬要所有自製 APP 定期備份到 Dropbox，選方案一（APP 存到同一資料夾＋Dropsync） |
| v0.10.1 | 2026-11-10 | 1d65b9a | 修正派工誤判：AI 自己判斷要派工時，句子裡沒有「派工」就退回重想一次，仍要派工就拿掉不送；提示詞與動作說明同步改成「沒說派工不可派工、說待辦用待辦」；開頭固定觸發詞（我有個點子、叫利姆路…）不變 | 倬說「待辦事項新增APP自動備份」被送成第 485 號派工單；倬定規則：要派工會說「派工」或「派工單」 |
| v0.10.0 | 2026-11-10 | （本次） | 用說的新增、改時間、刪除行程：CalendarCommand 在手機上用規則解析句子（日期、上下午、時間範圍、地點、指定行事曆），CalendarVoice 找活動、組確認句，倬說「確定」後 CalendarWriter 直接寫 CalendarContract（Google 同步推上雲端，不用 Google API）；重複活動、別人邀請的、唯讀行事曆不改；等確認時說了別的長句當新指令；設定頁權限改成讀寫一起要，新增「用說的新增行程時寫到」選行事曆 | 倬要用語音新增 Google 日曆事件，並一起做改時間、刪除 |
| v0.9.0 | 2026-11-09 | ed2f248 | 明天行程提醒：每天定時（預設 21:00）讀手機同步的行事曆（含共享行事曆），在手機上整理成口語、留通知、用內建聲音唸（DailyBrief：精準鬧鐘＋mediaPlayback 前台服務，開機／改時間重排）；新增 calendar_agenda「明天／下週三有什麼行程」本機直達不經 AI；查行程的回覆不記進給 AI 的對話紀錄、不用雲端聲音（Reply.private）；設定頁新增卡片（時間、週末、沒行程也唸、雲端聲音、勾選行事曆、權限狀態、現在唸一次） | 倬要用另一個 Google 帳號共享行事曆，每天定時用語音提醒明天的事 |
| v0.8.1 | 2026-11-09 | （本次） | 查電量放寬句型（電池還剩多少、還有電嗎、電量多少呢都走快速路徑） | 倬實測：查電量很慢，音樂播放器已變快 |
| v0.8.0 | 2026-11-09 | （本次） | 常用簡單指令（開 APP、音量、手電筒、播放、計時、今天的鬧鐘、電量）本機直接處理不問 AI（FastPath）；回覆改用 MiniMax 串流語音（speech-2.8-turbo＋News_Anchor，與 69 號同聲音，失敗退回內建）；開 APP 依發音比對英文名稱（Phonetic，ICU Han-Latin）；聽錯對照表 | 倬實測：開 APP 很慢、聲音生硬、Tailscale 仍聽錯 |
| v0.7.0 | 2026-11-07 | （本次） | 「我的派工單有哪些」唸全部未完成的單（dispatch_status 不給 id）；「音樂播放器」「播放器」優先開 SoundFlow；調音量後讀回確認，系統沒套用就說沒變；Whisper 提示加入英文 APP 名稱；無障礙服務被關掉時發通知提醒 | 倬實測回報：音量回報成功卻沒變、藍牙耳機手勢失效、開到系統音樂、Tailscale 聽錯、只能查單張派工單 |
| v0.6.0 | 2026-11-02 | （本次） | 系統分享選單新增「做成海報樣板」：圖片複製到手機佇列、傳到 65 號閘道 `/api/references`，連不上就留著，開對話畫面或健康檢查補送；「確認不含個資、公務內容」預設關閉；HEIC 轉 JPEG；設定頁可改閘道網址（ReferenceOutbox＋5 個測試） | 倬要把看到的圖分享給海報工坊做成樣板 |
| v0.5.1 | 2026-10-28 | （本次） | 「去＋地點」（去涮金鍋、導航到台北車站、帶我去最近的全聯）不經 AI、直接用預設交通方式（機車）導航（Places.goTo＋2 個測試）；去買／去繳／去接、提醒、時間字眼、問句照常交給 AI；Planner 規則 13：去地點＝導航，不是待辦 | 倬說「去涮金鍋」思考很久後被當成待辦事項（待辦 APP 上線後 AI 誤判） |
| v0.5.0 | 2026-10-26 | （本次） | 新增「專案規劃」：說「專案規劃／規劃專案／規劃一個新專案／規劃新專案」＋想法，送出的 text 加 `【專案規劃】` 前綴（`DispatchTrigger.parse`，不改派工 API）；只講觸發詞時會請你說想法，下一句自動補前綴；說明書新增一節（2 個測試） | 倬要用說的把模糊想法變成新專案，並在手機 Claude App 接手；後端流程見 36 號 `claude-project-updates/2026-10-26-PROJECT-PLANNING-WORKFLOW.md` |
| v0.4.1 | 2026-10-26 | （本次） | 音量鍵手勢新增「按的速度」：快（2 秒內，手機側鍵）／慢（5 秒內、每下間隔 2.5 秒內，藍牙耳機）；音量廣播被合併時，用「變化前音量 ≠ 上次看到的音量」補回漏掉的那一下（feedObserved＋3 個測試，含實機紀錄重現） | 倬實測：手機側鍵在桌面可以叫出，藍牙耳機不行（耳機只送音量變化、不送按鍵，ColorOS 延遲且合併） |
| v0.4.0 | 2026-10-26 | （本次） | 音量鍵手勢新增「無障礙服務」通道（VolumeAccessibilityService，選用）：只收音量鍵與音量廣播、不讀畫面；按鍵用按下當下的時間比對，且可直接從背景開畫面。偵測與叫出抽成 VolumeGestureCore，前台服務與無障礙服務共用；無障礙開著時前台服務不重複處理。設定頁加無障礙狀態與「開啟無障礙服務」按鈕；偵測紀錄標示來源與毫秒。API 37 模擬器驗證：關掉上層顯示權限、只開無障礙，桌面按加減加 → 對話畫面跳出、音量還原 | 倬實測：在桌面時音量廣播延遲 1～3 秒才送到、第一下還被合併掉（ColorOS 凍結背景 APP；VOLUME_CHANGED_ACTION 帶 REPLACE_PENDING），改 ColorOS 後台設定與設預設數位助理都無效 |
| v0.3.4 | 2026-10-26 | （本次） | 「回家」「導航去公司」這類只講常用地點的句子不經 AI、直接導航（Places.shortcut）；講了交通方式或其他內容照常交給 AI | 倬實測「回家」要等 10 多秒，比講地名慢很多 |
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
13. [x] 設定 › 音量鍵叫出 打開後通知列出現「音量鍵待命中」；按耳機音量，偵測紀錄出現「音量 X → Y」
14. [x] 回到桌面，耳機按「加、減、加」→ 對話畫面跳出、音量回到原本的值（試 5 次以上）
15. [ ] 重開機後不打開 APP，音量手勢仍有效（需允許自啟動）
16. [ ] 用一天後看 設定 › 電池 的耗電比例（預估每天 0.1% 以下，超過 1% 回報）
17. [ ] 裝了倬的鬧鐘 v0.12.0 後說「明天七點叫我」→ 倬的鬧鐘新增一次性鬧鐘（列表標一次性），不是系統時鐘
18. [ ] 「設一個固定的明天八點鬧鐘」「常駐的二十五分鐘計時器」→ 響完留著
19. [ ] 關掉網路（離線模式）說「計時五分鐘」→ 一樣交給倬的鬧鐘、一次性
20. [ ] 「專案規劃，做一個記錄喝水的網頁」→ 唸出幾號單；15 分鐘內 Telegram 收到完成通知，手機 Claude App 的 Code 分頁看得到同名工作階段（**測完把資料夾移到 `_trash/`、執行 stop-rc-session.ps1、到派工網站取消**）
21. [ ] 只說「專案規劃」→ 它請你說想法；接著說內容 → 派工網站上那張單的原文開頭是 `【專案規劃】`
22. [ ] 新 Google 帳號加進手機、共享行事曆在 Google 日曆打開同步 → 設定 › 明天行程提醒 列得出那些行事曆；按「現在唸一次」唸對明天的行程
23. [ ] 打開提醒、時間設成 2 分鐘後、回桌面鎖螢幕 → 準時唸出並留通知（ColorOS 要電池不限制）
24. [ ] 說「明天有什麼行程」「下週三有什麼活動」→ 馬上唸，不出現「思考中」
25. [ ] 設定 › 明天行程提醒 按「允許行事曆權限」，在「用說的新增行程時寫到」選好行事曆；說「行事曆新增明天下午兩點考績會」→ 唸出確認句、說「確定」→ Google 日曆（手機與網頁）都看得到
26. [ ] 說「把明天的考績會改到三點」→ 確定 → 時間改了、長度不變；說「取消明天的考績會」→ 確定 → 刪掉，網頁版垃圾桶看得到
27. [ ] 說「幫我在學校行事曆加一個下週五下午三點家長會」（換成你實際的行事曆名稱）→ 確認句唸出那個行事曆
29. [ ] 裝 Dropsync，同步 Documents/HoonsorBackup 到 Dropbox；設定 › 自動備份 按「立即備份」→ Dropbox 出現 VoiceHub-日期.json；改一個常用地點再從那個檔還原 → 地點回到原本的值
28. [ ] 說「待辦事項新增 APP 自動備份」→ 加到待辦事項，不送派工單；說「派工，APP 要自動備份」→ 照常送派工單

## 已知問題
- 模擬器（API 37）已驗證：對話畫面、測試台、app_list、alarm_set（交給倬的鬧鐘 APP 成功新增 00:00）、離線模式開 YouTube、查單與派工連不上分支。模擬器不在 Tailscale 內，派工成功分支未在裝置上驗證（電腦端 `/api/health` 正常）
- ACTION_SET_ALARM 的 SKIP_UI 是否被尊重取決於處理的時鐘 APP（倬的鬧鐘 APP 會開畫面）
- 預設模型 ID `MiniMax-M3`、`llama-3.3-70b-versatile` 若被下架，請在設定改 ID（測試按鈕會提示）
- 尚未做備份匯出／匯入

## 下一步
1. 實機驗證上面清單（裝最新 latest，用 Obtainium 或 App 內更新），依編號回報修正
2. 依序補倬注音、照片整理箱、螢幕翻譯的 Provider（鬧鐘、待辦、資料包、SoundFlow 已有 Provider，2026-11-01 查證）
3. 備份四件套、對話紀錄
4. Claude Design 補版面方案比較，更新 DESIGN.md；修 lintLabDebug 的 startActivityAndCollapse 棄用警告
5. 主動代理化：等倬決定 PRD 方向稿的 D1～D8（`D:\01-Project\42-語音總控制APP\docs\PRD-Duet化方向-v0.1.md`），先做 M0

## 交接紀錄

### 2026-11-10／電腦 A（v0.11.0 每日自動備份）
- 完成：共用模組 android-auto-backup 1.0.0（已收進 44-共用模組庫並打 tag）；VoiceHubBackupSource＋SettingsBackup（JSON 帶型別，可還原）；設定頁 AutoBackupCard；BackupTest（檔名、只刪自己的檔、保留 7 份、設定往返）。模擬器（LAB）驗證：jobscheduler 強制執行寫出 Documents/HoonsorBackup/VoiceHub-LAB/*.json、內容沒變第二次不另存、立即備份寫出含設定的新檔、設定頁顯示上次備份時間。
- 未驗證：從備份檔還原的選檔流程（模擬器 DocumentsUI 讓 System UI ANR），encode／decode 有單元測試；待實機驗證 29。
- 決定：模組用 Java＋內建 API，因為照片 APP 是 build.sh 專案、GestureBox 是 Java；備份預設開啟；內容雜湊相同就不另存，免得 Dropbox 塞滿相同檔案。
- 下一步：依序套用到鬧鐘、手勢、照片、注音輸入法、SoundFlow、賈維斯資料包（倬 11/10 決定：這 6 個加語音總控，先不加密）。
### 2026-11-10／電腦 A（v0.10.1 派工誤判修正）
- 完成：見版本歷程 v0.10.1；DispatchTest 新增 aiNeedsDispatchWord；docs/agent-manifest.json 已重新產生；全部單元測試通過。
- 決定：守門放在 Orchestrator（程式擋），不只靠提示詞，因為模型可能不聽；只限制「AI 判斷的派工」，開頭固定觸發詞照舊，避免改掉說明書寫的用法。
- 未做：第 485 號誤派的單要倬在派工網站取消（APP 沒有取消單的 API）。
- 下一步：倬實測待驗證 28。

### 2026-11-10／電腦 A（v0.10.0 用說的新增、改時間、刪除行程）
- 完成：見版本歷程 v0.10.0；CalendarCommandTest 23 例（新增各句型、整天、指定行事曆、沒說日期、地點、分鐘判斷、改時間、刪除、不該攔的句子、比對）；全部 135 個單元測試通過。API 37 模擬器（LAB）驗證：新增「明天下午三點到五點研習，在圖書館」寫入正確（11/11 15:00–17:00、地點）、「把今天的考績會改到四點」保留 1.5 小時、「取消今天的朝會」刪除、找不到與缺日期的回覆、查行程讀得到新活動、設定頁新區塊
- 決定＋原因：寫 CalendarContract 而不是 Google Calendar API（不用 OAuth、Cloud 專案與 token 維護）；句子只用手機規則解析，不送 AI（標題可能有人名或公務內容）；新增要有「行事曆／行程／活動」字眼才攔（避免搶走鬧鐘、待辦）；改時間、刪除只在找得到活動時才攔，句子有日期或行事曆字眼才回「找不到」；確認流程獨立於 ToolRegistry（calPending），不放進 AI 動作目錄
- 放棄：打開行事曆新增畫面讓倬按儲存（不是全語音）；改重複活動的單一場次（要處理例外事件，風險高，請倬用 Google 日曆 APP）
- 開發在 worktree `D:\dev\APK-VoiceHub-cal`（分支 feat/calendar-write），因為同時有「倬的天氣」session 在改 main；說明書已寫有「一起唸明天天氣」開關，但語音總控程式尚未實作，等那邊處理
- 未確認：ColorOS 實機寫入後同步到 Google 的速度；Google 帳號主要行事曆的名稱是 email，確認句會唸 email（可在設定選別的行事曆）
### 2026-11-09／電腦 A（v0.8.1 電量、v0.9.0 明天行程提醒）
- 倬實測 v0.8.0：音樂播放器變快；查電量仍慢 → v0.8.1 放寬電量句型（原因：只認固定說法）
- 完成：見版本歷程 v0.9.0；新增 CalendarBriefTest（整理、去重、只留當天、整天活動 UTC、星期換算、排程時間）；API 37 模擬器驗證：建本機行事曆＋3 個行程，「現在唸一次」通知標題「明天 11 月 10 日星期二：3 個行程」、系統排程登記 RTC_WAKEUP（exactAllowReason=policy_permission）
- 決定＋原因：讀系統 CalendarContract 而不是 Google Calendar API（不用 OAuth、不用金鑰、共享行事曆同步後就有）；整理用規則不用 AI、預設內建聲音（行事曆可能有人名與公務內容，AI-PROFILE §4）；查行程走 CalendarTrigger 本機直達，AI 規劃到 calendar_agenda 時也不做第二輪；精準鬧鐘用 USE_EXACT_ALARM（Android 13 起自動允許）
- 放棄：WorkManager（時間不準）；setAlarmClock（狀態列會出現鬧鐘圖示，跟倬的鬧鐘混淆）
- 未確認：ColorOS 實機上背景定時是否準時、內建聲音念日期的口音；國定假日不會自動跳過（只有週末開關）

### 2026-11-09／電腦 A（v0.8.0 加快與換聲音）
- 倬實測 v0.7.0：媒體音量 OK、音樂播放器 OK 但慢、派工單列表 OK、Tailscale 仍聽錯、藍牙加減加尚未測
- 完成：見版本歷程 v0.8.0；單元測試 103 個全過（新增 FastPathTest、PhoneticTest、SttFixesTest、CloudVoiceStreamTest）
- 決定＋原因：快速路徑只收句型完全符合、單一動作的指令，有日期的鬧鐘仍走 AI（系統鬧鐘只有時分，「明天七點」可能設成今天）；開 APP 要比對分數 ≥80 且唯一才不問 AI，避免「打開 YouTube 播周杰倫」被截斷。雲端聲音用串流 PCM 24kHz＋AudioTrack，exclude_aggregated_audio=true、status=2 不放音（避免整段重播）；用 AI 主力的 MiniMax key，不新增金鑰
- 實測：電腦 A 呼叫 MiniMax 串流，第一段聲音約 1.3 秒、回傳格式為 `data: {...}` 與 status 1／2，和解析程式一致
- 放棄：每段計時（倬決定先不做）；改用 Gemini Live／OpenAI Realtime 整套重寫（成本與改動太大，先回報選項給倬）
- 未確認：手機實機上的雲端聲音、ICU 拼音在 ColorOS 的實際結果（JVM 測試用假拼音表）、藍牙耳機手勢

### 2026-11-07／電腦 A（倬實測回報修正，v0.7.0）
- 完成：見版本歷程 v0.7.0；單元測試通過（含新增的 listQuery、summarize、音樂播放器別名）
- 決定＋原因：查全部派工單沿用 dispatch_status（id 改選填），不新增動作，說明書與 manifest 只改一節；派工網站 `GET /api/tickets?status=…` 已存在（唯讀確認回 38 張未完成）
- 未確認：「媒體音量調到三十」實際沒變的原因在模擬器重現不了（模擬器 -no-audio）。這版改成讀回確認，倬實測時看它說什麼；最可能是接耳機時的安全音量上限。藍牙耳機手勢失效：程式從 v0.4.1 起沒改過，推測是更新後 ColorOS 關掉了無障礙服務，要倬到 設定 › 音量鍵叫出 確認

### 2026-11-03／電腦 A（main 補齊 v0.5.0～v0.6.0）
- 完成：`feat/project-planning` 合併進 main（5287e79），分支再快轉到 main，兩者一致。說明書沒有重複章節；`testProdDebugUnitTest` 通過
- 決定＋原因：v0.5.0～v0.6.0 都已發正式版（tag），main 卻停在 v0.4.1，`latest` 測試版比正式版還舊；改成讓 main 等於已發布的程式。取代 11-02「只合說明書」的做法
- 回滾：tag `pre-merge-main-2026-11-03`（合併前的 main，934b374）
- 未完成：`feat/vocab-hub`（63 號）要自己把 main 合進去；`docs/manual-datapack`、`docs/photobox-sort-start` 已被取代，可刪（刪遠端分支要倬同意）

### 2026-11-02／電腦 A（說明書收錄賈維斯資料包）
- 完成：MANUAL.md 新增「賈維斯資料包」一章 11 節（本分支 59e624b、main 934b374）。main 的 CI run 41463330344 的 manual 與 build 都綠；`--github` 檢查 124 節通過；Python 16、Android 91 個測試通過
- 決定＋原因：沿用 `docs/manual-datapack` 的章節再依 manifest 與 DataPack 程式補齊，不重寫；同時 cherry-pick 到 main，因為每日排程跑在 main，只合說明書，不把 v0.5～v0.6 的程式帶進 main（回滾點 tag `pre-merge-v0.6.0`）
- 未完成：v0.6.0 tag 那次 CI 會維持紅色（tag 內容不變，重跑也一樣），下一個 tag 才會綠；APK 內的說明書要下一版才有這章。`docs/manual-datapack` 已被取代，可以刪；APK-DataPack 的 PROJECT_STATUS 還寫著未合併
- 環境注意：本機 Python 跑 `--github` 會憑證失敗，要設 `SSL_CERT_FILE` 指向 Git 的 ca-bundle；`--local D:dev` 會把 APK-VoiceHub-vocab worktree 當成別的 APP 而誤報

### 2026-11-02／電腦 A
- 完成：v0.6.0 分享圖片到海報工坊。模擬器驗證：分享畫面、離線時圖片留在佇列；multipart 格式用測試閘道驗證解得開
- 決定＋原因：直接經 Tailscale 傳到 A，不走雲端硬碟（參考圖只放本機）；沿用派工暫存的補送時機，不加 WorkManager
- 未驗證：手機實際傳到閘道（模擬器不在 tailnet），要等 65 號閘道換到 v0.8.0

### 2026-11-01／電腦 A
- 完成：分析 Claude Duet，寫出主動代理化的 PRD 方向稿 v0.1（功能對照、架構、子專案、資料、帳號、階段、決策 D1～D8）。沒有改程式
- 決定＋原因：方向稿放中文資料夾，不進本 repo（repo 公開，文件含個人架構與帳號配置）
- 放棄：直接把代理層塞進 APP（ColorOS 會凍結背景，帳號權杖也不該放手機）；以拉斐爾當核心（MiniMax 不能碰公務，週額度也曾用完）
- 未完成：D1～D8 都未決定，尤其本檔 AGENTS.md 的「Never：用語音處理公務／個資」要倬決定是否修改
- 發現：`main` 還在 v0.4.1；v0.5.0、v0.5.1 只在 `feat/project-planning`，另有 `feat/vocab-hub`、`docs/manual-datapack`、`docs/photobox-sort-start` 未合併
- 環境注意：42 號中文資料夾不在 Git，方向稿目前只存在電腦 A

### 2026-10-26／電腦 A
- 完成：v0.3.3～v0.4.0 音量鍵手勢在桌面叫不出的問題、「回家」常用地點與快速通道
- 發現：OPPO Reno 14F（ColorOS）在 APP 背景時，音量廣播延遲 1～3 秒且會被合併（第一下遺失），在 APP 前景時正常；開「允許完全後台行為」、設預設數位助理都沒改善
- 決定＋原因：加無障礙服務通道（系統綁定不會被凍結、可從背景開畫面、按鍵事件帶按下時間）；保留前台服務給沒開無障礙的情況
- 2026-10-26 實機驗證通過（v0.4.1）：OPENEAR Air X3 在桌面按「加、減、加」（按的速度選「慢」、每下間隔約 1 秒）可以叫出；手機側鍵用無障礙通道也可以
- 原本未驗證：OPENEAR Air X3 的音量鍵在無障礙服務收得到「按鍵」還是只有「音量廣播」（看偵測紀錄的 [無障礙] 按鍵加／音量 X → Y）；若只有廣播且仍延遲，下一步考慮 MediaSession 或放棄耳機改用其他觸發方式

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
- 完成：setup-hoonsor-signing.ps1 加入 APK-VoiceHub，並改存為含 BOM 的 UTF-8（原本無 BOM，在 Windows PowerShell 5.1 下中文會亂碼）；各中文 APP 資料夾補上 CODE_LOCATION.txt 與指向 D:dev 的 CLAUDE.md
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
