<!-- translated-from: README.md @ 9d5160db3e1b -->

<div align="center">
  <img src="../../app/src/main/res/drawable-nodpi/flash_phone_launcher.png" width="104" alt="Flash phone のアイコン" />
  <h1>Flash phone</h1>
  <p>お使いの SIM で電話を受けたりかけたりする Android 向け AI アシスタント。</p>
  <p>
    <a href="https://github.com/DzsGatsby/FlashPhone/releases/latest"><strong>APK をダウンロード</strong></a> ·
    <a href="../README.en.md">利用ガイド（英語）</a> ·
    <a href="https://github.com/DzsGatsby/FlashPhone/issues/new/choose">問題を報告</a>
  </p>
</div>

[English](../../README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-Hant.md) · 日本語 · [한국어](README.ko.md) · [Español](README.es.md)

## できること

- **着信を代わりに受ける。** AI が応答できる着信を設定し、重要な番号は自分で受けられます。自動応答は一時停止でき、通話を切らずに途中から引き継げます。
- **目的を伝えて電話をかける。** 電話番号と SIM、聞きたいこと、背景、約束してよい範囲を入力し、内容を確認してから発信します。
- **画面ロック中に電話を扱う。** 通話音声ヘルパーとバックグラウンド待機が有効なら、条件に合う着信に応答できます。確認済みの発信タスクは、45 秒以内にロックすると一度だけ発信を試みます。
- **通話を振り返る。** 文字起こしと元のタスクを見比べ、対応済みにしたり、別のタスクの下書きに再利用したりできます。

## インストールと初期設定

1. Android 12 以降の端末と有効な SIM を用意します。[GitHub の最新リリース](https://github.com/DzsGatsby/FlashPhone/releases/latest)で `FlashPhone-vX.Y.Z.apk` を入手し、Android の案内に従ってインストールします。求められたら、APK を開いたアプリからのインストールを許可します。
2. 端末で通話音声ヘルパーをワイヤレスペアリングし、Volcengine のリアルタイム音声サービスの認証情報を設定します。音声対応は端末と Android のビルドによって異なります。
3. お互いの音声が聞こえ、手動で通話を引き継げることを確認してから自動応答を有効にしてください。[設定と互換性のガイド（英語）](../README.en.md)も参照できます。

インストール済みの場合は **設定 → アプリ情報 → アップデートを確認** を使うか、署名済み APK を上書きインストールすると設定と履歴を引き継げます。[更新と移行のガイド（簡体字中国語）](../app-updates.md)を参照してください。

## サービスとデータ

既存の SIM と標準の電話アプリを引き続き利用できます。AI が応答する間、通話音声は設定した音声サービスに送信されます。通話音声は録音せず、文字起こしは端末に暗号化して保存します。通信事業者と音声サービスの利用料が別途かかることがあります。

## プロジェクトとライセンス

Flash phone は [wangzan101 とコントリビューターによる Mika](https://github.com/wangzan101/Mika) をベースにした独立プロジェクトです。[Apache-2.0](../../LICENSE)、[上流の著作権表示](../../NOTICE)、[サードパーティーの表示](../../licenses/THIRD_PARTY_NOTICES.md)、[貢献ガイド（英語）](../../.github/CONTRIBUTING.md)を参照してください。
