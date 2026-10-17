# PROJECT_STATUS — 語音總控（APK-VoiceHub）

- 目前版本：v0.1.0（尚未發佈；本機建置與模擬器驗證通過）
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
- [ ] 把 hoonsor/APK-VoiceHub 加進 setup-hoonsor-signing.ps1 的 $Repos 並執行（之後 latest 才是共用簽章）
- [x] 協定唯一來源 AGENT_PROTOCOL.md、共用 SDK HoonsorAgent.kt、CI 相容檢查、deprecated 支援
- [ ] 實機驗證（見下方清單）
- [ ] Claude Design 版面方案比較（SOP 第 3 階段，事後補）
- [ ] 備份四件套（F23）
- [ ] 各 APP（鬧鐘、照片整理箱、輸入法、螢幕翻譯）補 Agent API

## 版本歷程
| 版本 | 日期 | commit | 變更 | 為什麼 |
|---|---|---|---|---|
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
1. 倬同意後建立 GitHub repo `hoonsor/APK-VoiceHub`（public，與其他 APK repo 一致）、push、把 repo 加進 `setup-hoonsor-signing.ps1` 的 `$Repos` 再執行一次
2. 實機驗證上面清單，依編號修正
3. 鬧鐘 APP 先補 Agent API（alarm_list、alarm_create、alarm_toggle、timer_start…），讓大腦能直接操作而不是透過系統時鐘 Intent
4. 備份四件套、對話紀錄
5. Claude Design 補版面方案比較，更新 DESIGN.md

## 交接紀錄
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
