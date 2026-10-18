# AGENTS.md — 語音總控（APK-VoiceHub）

> 給接手的 AI（Claude、Antigravity、Codex、DeepSeek Harness、B 電腦）。不看對話紀錄也要能接手。

## 專案目的
倬的「賈維斯」手機入口：像 Gemini Live 一樣叫出對話畫面（底部極光光影＋對話泡泡），對手機說話，
APP 把話轉成動作，去操作**倬自己開發的 APP**（Hoonsor Agent API）與**手機上的其他 APP**（Android 標準 Intent），
再用文字＋語音回報結果；做不到就直說做不到。另外把開發需求「語音派工」到利姆路（40-利姆路／raphael-dispatch）。

## 技術棧
- Kotlin、Jetpack Compose＋Material3；minSdk 30／targetSdk 35；AGP 8.7.3、Kotlin 2.1.0、Gradle 8.11.1
- 版本集中在 `gradle/libs.versions.toml`；版號只改 `app/build.gradle.kts` 的 `appVersion`（versionCode 自動推導 1.2.3→10203）
- 兩個 flavor：`prod`（tw.hoonsor.voicehub，「語音總控」）、`lab`（tw.hoonsor.voicehub.lab，「語音總控 LAB」）
- 沒有資料庫；設定用 SharedPreferences `settings`；API key 用 Keystore AES-GCM 存在 `vault`

## 建置與安裝
- 本機：JDK 21（`C:\Users\hoonsor\.jdks\jbr-21.0.11`），**不要用 Android Studio 內附的 JBR 25**（Gradle 8.11 不支援）
  ```powershell
  $env:JAVA_HOME="C:\Users\hoonsor\.jdks\jbr-21.0.11"; .\gradlew.bat testProdDebugUnitTest assembleRelease
  ```
  產出 `app/build/outputs/apk/{prod,lab}/release/*.apk`（本機沒有簽章 Secrets 時為 debug 簽章）
- CI：`.github/workflows/build.yml`，main → `latest` 預先發行版；`vX.Y.Z` tag → Release；先跑單元測試
- 共用簽章：倬用 `36-Claude使用流程改善/setup-hoonsor-signing.ps1` 寫入 Secrets（repo 要先加進腳本的 `$Repos` 清單）
- `docs/agent-manifest.json` 與內建動作不一致時測試會失敗；改動作後執行：
  `$env:UPDATE_MANIFEST="1"; .\gradlew.bat testProdDebugUnitTest`

## 目錄與架構
```
app/src/main/java/tw/hoonsor/voicehub/
├─ App.kt                 Graph 單例（settings、registry、orchestrator、dispatch、speaker）
├─ assistant/             對話畫面
│  ├─ AssistantActivity    半透明疊層；桌面圖示／ACTION_ASSIST／快速設定磚都進這裡
│  ├─ AssistantViewModel   狀態機 IDLE→LISTENING→TRANSCRIBING→THINKING→EXECUTING→SPEAKING
│  ├─ AssistantScreen      極光光影（Canvas＋blur）、泡泡、動作晶片、確認列、文字輸入
│  └─ VoiceServices        VoiceInteractionService/Session（預設數位助理）、Stub RecognitionService、TileService
├─ voice/                 MicRecorder（AudioRecord 16k＋Vad）、SystemStt、Speaker（TTS）、Vad／Wav（純邏輯）
├─ ai/                    LlmClient（OpenAI 相容 chat、/models、Whisper 轉錄）、Planner（提示詞＋JSON 解析，純邏輯）
├─ agent/                 AgentModels（協定）、BuiltinCatalog（內建動作規格）、BuiltinActions（Intent 執行）、
│                         ToolRegistry（探索其他 APP＋分派）、Orchestrator（一句話的完整流程）、
│                         OfflineParser／AppMatcher（離線句型與 APP 名稱比對，純邏輯）、AgentProvider（本 APP 的 Agent API）
├─ (tw/hoonsor/agent/HoonsorAgent.kt)  共用提供者 SDK：各 APP 整份複製使用，本 APP 的 AgentProvider 也用它
├─ dispatch/              語音派工：DispatchTrigger（觸發詞）、DispatchClient（HTTP）、DispatchQueue（離線暫存）、DispatchService
├─ data/                  Settings、KeyVault
├─ manual/                Manual：說明書解析、搜尋、依手機實際偵測結果判斷「可用／規劃中／未安裝」（純邏輯，ManualTest）
└─ ui/                    MainActivity（動作／說明書／測試台／健康檢查／設定）、Screens、ManualScreen、Common、Icons（手繪向量）、theme（唯一色票）
docs/manual/MANUAL.md     使用說明書的唯一來源，build.gradle.kts 直接打包成 assets/MANUAL.md
```

### 使用說明書
- 內容只寫在 `docs/manual/MANUAL.md`；格式（章、節、`<!-- action: 套件:動作 -->` 標記、`- 「例句」→ 說明`）寫在該檔開頭的註解
- 點例句會用 `AssistantActivity.EXTRA_DRAFT` 打開對話畫面並放進輸入框，**不會自動送出**（所以正式版也開放這個入口）
- 狀態標籤即時偵測（ToolRegistry 的 describe 結果＋PackageManager），說明書文字裡不寫死「已完成」
- 檢查：`ManualTest`（內建動作都要收錄）；`tools/manual_coverage_check.py`（所有 APK-* repo 的動作都要收錄，CI 每天跑）
  - 本機：`python tools/manual_coverage_check.py --local D:\dev`

### 一句話的處理流程（Orchestrator）
1. 有待確認動作 → 是／否判斷
2. **派工**：`DispatchTrigger` 本機判斷觸發詞 → 原話直送，不經 AI；查單（「第 N 號單進度」）也直達
3. AI 規劃：主力 MiniMax → 備援 Groq → 離線句型（`OfflineParser`）。模型只輸出 `{"say","calls":[{target,action,params}]}`
4. 依序執行 calls；`NEEDS_CONFIRMATION` 先問、`NEEDS_UI` 開畫面、`AMBIGUOUS` 請倬選、失敗就停
5. 全部成功且是簡單動作 → 直接用各動作 summary 回覆（省一次 AI 呼叫）；有失敗或要解讀查詢資料 → 再給 AI 一輪
6. 有跳到其他 APP（`data.leavesApp`）→ 唸完後自動收起對話視窗

## Agent API 協定的唯一來源
- `docs/AGENT_PROTOCOL.md`：協定、SDK 用法、撰寫指南、**相容規則**。其他 APP 與 SOP 技能都引用這裡，改協定只改這裡
- `app/src/main/java/tw/hoonsor/agent/HoonsorAgent.kt`：提供者 SDK 的來源，改了要遞增 `SDK_VERSION`，再複製到各 APP
- `tools/agent_compat_check.py`：破壞性變更檢查（CI 會跑；各 APP 複製使用）。測試：`python -m unittest discover -s tools`
- 其他 APP 改版不需要通知本專案；只有協定升版、需要大腦特別處理、新增 data 約定時才要改這裡

## 關鍵設計決策（＋原因）
- **不用各家 tool calling，改要求固定 JSON**：MiniMax／Groq 相容程度不一，換模型不用改程式
- **一律走「預設數位助理」VoiceInteractionService，但 UI 在自己的 Activity**：桌面圖示、快速設定磚、電源鍵共用同一畫面；session 只負責 `startAssistantActivity` 後 `hide()`
- **第三方 APP 只用 Android 標準 Intent，不用無障礙自動點擊**（倬 2026-10-17 選定）：穩定、免特殊權限；無障礙列為 P2
- **撥號、簡訊、行事曆只開畫面填好內容，由倬按最後一下**：避免語音誤判直接打出去／傳出去
- **派工不經 AI**：規格（40-利姆路 `raphael-dispatch/docs/voice-dispatch-api.md`）要求原話直送、伺服器 message 直接唸
- **派工連不上不立即重送，只暫存**：規格要求失敗自動重送不超過 1 次以免重複開單；補送時機＝打開對話畫面、下一次派工成功、健康檢查頁按「立即補送」
- **大腦也宣告 `tw.hoonsor.permission.AGENT`**：先裝大腦、後裝其他 APP 時，signature 權限才拿得到（所有 APP 必須同一把簽章）
- **LAB 版可用 adb 注入文字**（`--es text "…"`）：方便自動化測試整條流程；正式版關閉

## 絕對不要重踩的坑
- Gradle 用 JDK 25 會失敗；中文路徑會讓 Gradle 出錯（程式碼放 `D:\dev\APK-VoiceHub`）
- `backup_rules`／`data_extraction_rules` 不能 `exclude` 沒有被 `include` 的檔案，lint 會擋 release
- Bash heredoc 在這台 Windows 的 Git Bash 偶爾解析失敗，寫檔用編輯工具
- Whisper 在無聲時會幻聽（「請不吝點贊」等），`AssistantViewModel.cleanTranscript` 會濾掉
- 模擬器不在 Tailscale 網路內，派工只能測「連不上」分支；真的送單要實機
- 測試派工不要對正式網站 POST；若真的送了，要到派工網站按「取消」
- 不同簽章的 APP 宣告同名權限，Android 12+ 會安裝失敗；換簽章前先備份再卸載
- 背景不能錄音：動作跳到別的 APP 後不要自動再聽

## 倬的偏好
繁體中文（台灣）、深色質感 UI、HSL 色票不用原色、手繪圖示、做不到要直說原因、每個設定有「測試」按鈕、
穩定可靠第一、每步可回滾。完整見 `36-Claude使用流程改善/AI-PROFILE.md`。

## Always／Ask／Never
- **Always**：改程式前 commit 快照；改動作就同步 `BuiltinCatalog`、`docs/AGENT_API.md`、`docs/agent-manifest.json`、`docs/FEATURES.md`、**`docs/manual/MANUAL.md`**；倬的任何 APP 新增 Agent API 或動作，同一次工作就補說明書（AGENT_PROTOCOL 第 7 節）；新功能先發 LAB 版；每版更新 PROJECT_STATUS.md
- **Ask**：建立／公開 GitHub repo、對外送出（派工 POST、簡訊）、改套件名、換簽章、加無障礙服務
- **Never**：把 API key、keystore、密碼寫進 repo；`--force` push；在 APP 端用 AI 改寫派工內容；用語音處理公務／個資
