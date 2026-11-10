<div align="center">
  <img src="app/src/main/res/drawable-nodpi/flash_phone_launcher.png" width="104" alt="Flash phone icon" />
  <h1>Flash phone</h1>
  <p>An Android AI assistant for calls on your SIM.</p>
  <p>
    <a href="https://github.com/DzsGatsby/FlashPhone/releases/latest"><strong>Download the APK</strong></a> ·
    <a href="docs/README.en.md">User guide</a> ·
    <a href="https://github.com/DzsGatsby/FlashPhone/issues/new/choose">Report a problem</a>
  </p>
</div>

English · [简体中文](docs/readme/README.zh-CN.md) · [繁體中文](docs/readme/README.zh-Hant.md) · [日本語](docs/readme/README.ja.md) · [한국어](docs/readme/README.ko.md) · [Español](docs/readme/README.es.md)

## What it does

- **Answer incoming calls.** Choose which calls AI may answer, reserve important numbers for yourself, or pause automatic answering. Take over mid-call without disconnecting.
- **Place calls with a goal.** Choose the number and SIM, and add what to ask, the background, and what AI may promise. Review the brief before calling.
- **Work while the screen is locked.** When the audio helper and background standby are ready, AI can answer eligible incoming calls. A confirmed outgoing task can be set to dial once after locking the screen within 45 seconds.
- **Review the conversation.** Read the transcript beside the original task, mark the call handled, or reuse the task as a new draft.

## Install and get started

1. On an Android 12 or later phone with an active SIM, open the [latest GitHub release](https://github.com/DzsGatsby/FlashPhone/releases/latest), download the `FlashPhone-vX.Y.Z.apk` asset, and follow the Android installer. If prompted, allow installation from the app that opened the APK.
2. Set up the in-app call audio helper via wireless pairing and add credentials for Volcengine's real-time voice service. Audio support varies by device and Android build.
3. Test that both sides can hear and that you can take over before enabling automatic answering. See the [setup and compatibility guide](docs/README.en.md).

Already installed? Use **Settings → About → Check for updates**, or install the signed APK over the existing app to keep your settings and call records. See the [release and migration guide (简体中文)](docs/app-updates.md).

## Service and data

Flash phone uses your existing SIM and keeps your usual dialer available. During an AI call, voice audio goes to the configured voice service. Flash phone does not save audio recordings; it encrypts transcripts on the device. Carrier and voice service charges may apply.

## Project and license

Flash phone is an independent project based on [Mika by wangzan101 and its contributors](https://github.com/wangzan101/Mika). See [Apache-2.0](LICENSE), the [upstream copyright notice](NOTICE), and [third-party notices](licenses/THIRD_PARTY_NOTICES.md). [Contribution guide](.github/CONTRIBUTING.md).
