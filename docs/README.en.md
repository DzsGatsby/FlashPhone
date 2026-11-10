# Flash phone user guide

[简体中文](README.md) · [Project home](../README.md) · [Download the APK](https://github.com/DzsGatsby/FlashPhone/releases/latest)

## Set up your phone

1. Install the signed `FlashPhone-vX.Y.Z.apk` from the latest GitHub release. You need Android 12 or later, an active SIM, and the phone's primary Android user. The audio helper's two-way audio support depends on your device and Android build.
2. Open **Settings** in Flash phone and set up its **Call audio helper**. Use wireless pairing from the phone: open Android's wireless debugging pairing dialog, leave its six-digit code visible, then enter the code in the Flash phone notification. Pairing starts the helper; the call itself does not travel through the debugging connection.
3. Add your Volcengine real-time voice credentials. A speech recognition key alone is insufficient. Enable AI calling and grant the requested phone and notification permissions.
4. With a test caller's permission, confirm that both sides can hear each other, that speaking can interrupt the AI, and that **Take over** keeps the ordinary phone call connected. After a successful test, confirm audio on this device before enabling automatic answering.

## Answer, call, and review

- In **Answering**, set the rules, keep important numbers for yourself, and turn on background standby if you want AI to answer with the screen locked. Check the standby notification before relying on it. Standby can be interrupted by a reboot, force stop, or the phone's battery controls.
- In **Call tasks**, fill in the number, SIM, goal, questions, and limits. Review the task to call now, or choose to dial once after locking the screen within 45 seconds. You can cancel before locking. The app does not redial expired or cancelled tasks.
- In **Records**, compare the call transcript with your task and make a new draft from a previous task. Reusing a task does not place a call.
- For a restart, update, or stopped helper, reopen Flash phone and check that the helper and standby are ready. Restarting the helper may require Wi-Fi that permits wireless debugging.

See the [complete calling guide (简体中文)](ai-phone-calls.md) for audio limits, service costs, optional voice cloning, and discussing call instructions with Codex.

## Updates and migration

In Flash phone, open **Settings → About → Check for updates**, download the update and confirm in the Android installer. The independent release source is [DzsGatsby/FlashPhone](https://github.com/DzsGatsby/FlashPhone/releases).

Versions through 1.0.7 that check the former release source can use the one-time 1.0.8 transition update. You can also download a signed APK directly from the new release page and install it over the existing app. Keep the existing installation to preserve settings and records. See the [release guide (简体中文)](app-updates.md).

[Report call or device compatibility issues](https://github.com/DzsGatsby/FlashPhone/issues/new?template=device-report.yml).
