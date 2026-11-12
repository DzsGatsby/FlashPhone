<!-- translated-from: README.md @ deb26d8962f6 -->

<div align="center">
  <img src="../../app/src/main/res/drawable-nodpi/flash_phone_launcher.png" width="104" alt="Flash phone 圖示" />
  <h1>Flash phone</h1>
  <p>使用現有 SIM 接打電信電話的 Android AI 助理。</p>
  <p>
    <a href="https://github.com/DzsGatsby/FlashPhone/releases/latest"><strong>下載 APK</strong></a> ·
    <a href="../README.md">使用指南（簡體中文）</a> ·
    <a href="https://github.com/DzsGatsby/FlashPhone/issues/new/choose">回報問題</a>
  </p>
</div>

[English](../../README.md) · [简体中文](README.zh-CN.md) · 繁體中文 · [日本語](README.ja.md) · [한국어](README.ko.md) · [Español](README.es.md)

## 功能

- **代接來電。** 選擇哪些來電可由 AI 接聽、將重要號碼留給自己，或暫停自動接聽。通話中可接手，不會掛斷。
- **帶著目標撥電話。** 選好號碼和 SIM，填入要問的問題、背景和可承諾的事項，確認後再撥出。
- **鎖定螢幕後接打。** 音訊助手和背景待命就緒時，AI 可代接符合規則的來電；已確認的任務可在 45 秒內鎖定螢幕後撥號一次。
- **回看對話。** 對照通話文字和原本的任務、播放本機雙聲道錄音，標記處理結果，或將舊任務複製為新草稿。
- **連續處理工作。** 首頁、記錄、外呼任務、接聽規則與設定使用常駐導覽。可搜尋篩選記錄、切換詳情、複製或匯出文字；未送出的任務加密儲存在本機，重新啟動後可恢復。重用記錄只建立草稿，不會直接撥號。

## 安裝與入門

1. 使用 Android 12 或更新版本及有效 SIM。開啟 [GitHub 最新版本](https://github.com/DzsGatsby/FlashPhone/releases/latest)，下載 `FlashPhone-vX.Y.Z.apk` 並按 Android 提示安裝。如有提示，允許開啟 APK 的應用程式安裝。
2. 在手機上以無線配對啟動通話音訊助手，設定火山即時語音服務憑證。音訊支援因裝置與 Android 版本而異。
3. 先確認雙方都聽得見、人工接手正常，再開啟自動接聽。確認音訊並啟用後，背景待命會自動啟動；關閉自動接聽也會撤銷待命。詳見[設定與相容性（簡體中文）](../ai-phone-calls.md)。

已安裝時，在 **關於 → 檢查更新** 更新（主畫面右上角資訊按鈕；舊版從設定進入關於），或直接覆蓋安裝已簽署的 APK，保留設定與紀錄。見[更新與遷移指南（簡體中文）](../app-updates.md)。

## 多裝置與智慧代理工具

1.1.0 新增可選用的私人控制平台：Android、iPhone 與電腦瀏覽器可共用同一位機主的裝置清單、電話任務、文字轉錄及受保護的錄音。將 Android 執行裝置一次性加入帳號，並在其設定中明確啟用遠端電話任務。網頁與七個 `flashphone_*` MCP 工具操作同一組任務，也能取得最終結果；接受撥號請求不會被當作對話已完成。詳見[設定與使用指南（簡體中文）](../../tools/control-plane/README.md)及 [Codex / Grok Bot 技能（簡體中文）](../../tools/flashphone-client/skill/SKILL.md)。

Mac/iPhone 橋接仍屬實驗功能，目前開發機受 Apple 的 iCloud/FaceTime 帳號要求阻擋。實際通話控制與電信雙向音訊尚未驗證，這些檢查通過前，橋接端不會宣告具備撥號能力。iPhone 上的網頁控制台可獨立作為控制端，不依賴該 iPhone 執行電話。見 [Mac 橋接狀態與設定（英文）](../../tools/mac-bridge/README.md)。

## 服務與資料

Flash phone 使用現有 SIM，保留原本的撥號程式。AI 處理通話期間，音訊會傳給設定的語音服務，並預設自動保存本機雙聲道錄音：左側為擷取的對方聲音，右側為裝置電話音軌已接受的 AI 音訊；人工接手時停止錄製。錄音與文字加密存於裝置，播放不會上傳。插話清空佇列可能丟棄未播放資料，右聲道不能證明遠端已聽見。電信通話與語音服務可能各自收費。

每份錄音最多 600 秒，最多 50 份、合計 200 MiB，需要空間時先移除最舊錄音。無線配對及裝置允許的授權完成後，會在已連線且受信任的 Wi-Fi 嘗試恢復無線偵錯，並在開機、解鎖或更新後恢復先前授權的背景接聽；不繞過系統信任、強制停止或廠商限制，不取得 root。新增錄音與恢復已做離線檢查，真實手機錄音及重新開機／更新後恢復仍待實機驗證。

明確啟用遠端電話任務後，該任務的文字轉錄與可用錄音也會透過 HTTPS 上傳至機主已驗證身分的控制平台及私人 R2 儲存桶，不提供公開分享網址。這份遠端副本與一般本機播放分開，並有自己的保留數量與期限限制。控制平台只傳遞任務與結果，不中繼即時通話音訊，也不執行語音模型。

## 專案與授權

Flash phone 是以 [wangzan101 和貢獻者開發的 Mika](https://github.com/wangzan101/Mika) 為基礎的獨立專案。見 [Apache-2.0](../../LICENSE)、[上游版權聲明](../../NOTICE)及[第三方聲明](../../licenses/THIRD_PARTY_NOTICES.md)。[參與貢獻（簡體中文）](../../.github/CONTRIBUTING.zh-CN.md)。
