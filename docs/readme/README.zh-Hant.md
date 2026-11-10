<!-- translated-from: README.md @ 9d5160db3e1b -->

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
- **回看對話。** 對照通話文字和原本的任務，標記處理結果，或將舊任務複製為新草稿。

## 安裝與入門

1. 使用 Android 12 或更新版本及有效 SIM。開啟 [GitHub 最新版本](https://github.com/DzsGatsby/FlashPhone/releases/latest)，下載 `FlashPhone-vX.Y.Z.apk` 並按 Android 提示安裝。如有提示，允許開啟 APK 的應用程式安裝。
2. 在手機上以無線配對啟動通話音訊助手，設定火山即時語音服務憑證。音訊支援因裝置與 Android 版本而異。
3. 先確認雙方都聽得見、人工接手正常，再開啟自動接聽。詳見[設定與相容性（簡體中文）](../ai-phone-calls.md)。

已安裝時，在 **設定 → 關於 → 檢查更新** 更新，或直接覆蓋安裝已簽署的 APK，保留設定與紀錄。見[更新與遷移指南（簡體中文）](../app-updates.md)。

## 服務與資料

Flash phone 使用現有 SIM，保留原本的撥號程式。AI 處理通話期間，音訊會傳給設定的語音服務。應用程式不保留通話錄音，文字紀錄加密存於裝置。電信通話與語音服務可能各自收費。

## 專案與授權

Flash phone 是以 [wangzan101 和貢獻者開發的 Mika](https://github.com/wangzan101/Mika) 為基礎的獨立專案。見 [Apache-2.0](../../LICENSE)、[上游版權聲明](../../NOTICE)及[第三方聲明](../../licenses/THIRD_PARTY_NOTICES.md)。[參與貢獻（簡體中文）](../../.github/CONTRIBUTING.zh-CN.md)。
