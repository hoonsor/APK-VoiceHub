# PROJECT_STATUS — 語音總控（APK-VoiceHub）

- 目前版本：v0.2.1（main 推送後由 CI 發佈 latest；使用說明書已在模擬器驗證）
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

## 已知問題
- 模擬器（API 37）已驗證：對話畫面、測試台、app_list、alarm_set（交給倬的鬧鐘 APP 成功新增 00:00）、離線模式開 YouTube、查單與派工連不上分支。模擬器不在 Tailscale 內，派工成功分支未在裝置上驗證（電腦端 `/api/health` 正常）
- ACTION_SET_ALARM 的 SKIP_UI 是否被尊重取決於處理的時鐘 APP（倬的鬧鐘 APP 會開畫面）
- 預設模型 ID `MiniMax-M3`、`llama-3.3-70b-versatile` 若被下架，請在設定改 ID（測試按鈕會提示）
- 尚未做備份匯出／匯入

## 下一步
1. 倬執行 `D:\01-Project\36-Claude使用流程改善\setup-hoonsor-signing.ps1`（repo 已加進 `$Repos`），完成後重跑 CI，確認 latest 改用共用簽章
2. 實機驗證上面清單，依編號修正
3. 鬧鐘 APP 先補 Agent API（alarm_list、alarm_create、alarm_toggle、timer_start…），讓大腦能直接操作而不是透過系統時鐘 Intent
4. 備份四件套、對話紀錄
5. Claude Design 補版面方案比較，更新 DESIGN.md

## 交接紀錄
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
