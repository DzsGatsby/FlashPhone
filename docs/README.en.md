# Flash phone user guide

[简体中文](README.md) · [Project home](../README.md) · [Download the APK](https://github.com/DzsGatsby/FlashPhone/releases/latest)

## Set up your phone

1. Install the signed `FlashPhone-vX.Y.Z.apk` from the latest GitHub release. You need Android 12 or later, an active SIM, and the phone's primary Android user. The audio helper's two-way audio support depends on your device and Android build.
2. Open **Settings** in Flash phone and set up its **Call audio helper**. Use wireless pairing from the phone: open Android's wireless debugging pairing dialog, leave its six-digit code visible, then enter the code in the Flash phone notification. Pairing starts the helper; the call itself does not travel through the debugging connection.
3. Add your Volcengine real-time voice credentials. A speech recognition key alone is insufficient. Enable AI calling and grant the requested phone and notification permissions.
4. With a test caller's permission, confirm that both sides can hear each other, that speaking can interrupt the AI, and that **Take over** keeps the ordinary phone call connected. After a successful test, confirm audio on this device before enabling automatic answering.

## Answer, call, and review

- In **Answering**, set the rules and keep important numbers for yourself. After the audio test, enabling automatic answering also starts background standby; disabling automatic answering revokes it. No separate standby opt-in is required. Check the standby notification before relying on it. Force-stop or the phone's battery controls can still interrupt standby.
- In **Call tasks**, fill in the number, SIM, goal, questions, and limits. Review the task to call now, or choose to dial once after locking the screen within 45 seconds. You can cancel before locking. The app does not redial expired or cancelled tasks.
- In **Records**, compare the transcript with your task, play the local recording, or make a new draft from a previous task. Reusing a task does not place a call.
- After pairing and supported grants, the app attempts to recover the helper on connected, system-trusted Wi-Fi. Boot, unlock and app-update events can restore previously authorized incoming standby; they never schedule outgoing calls. Check the helper and standby states after recovery. Force-stop, unavailable Wi-Fi and manufacturer controls can still prevent it.

## Local recordings and automatic recovery

The AI-managed part of each call is automatically recorded in two-channel 16 kHz WAV: caller capture on the left and AI data accepted by the device's phone audio track on the right. It stops at manual takeover and does not record the rest of the ordinary phone call. Local recordings use Android Keystore-backed encryption and are not uploaded for playback. A right-channel sample is not proof of far-end delivery: an interruption may discard audio already queued to the track.

Each recording is bounded to 600 seconds. The app retains at most 50 recording files and 200 MiB, evicting the oldest when space is needed; text history may outlast its audio. An interrupted monitor produces a partial recording when possible. Private temporary WAV files used during recording or playback are removed after use; crash leftovers are cleaned at the next app initialization. Deleting history also removes its recording.

Automatic recovery uses the authenticated helper to request and verify the app's own secure-settings grant, battery exemption and background app-ops where Android permits them. Saved authorization, pairing and an available trusted Wi-Fi connection are required to reopen wireless debugging without a running helper. The app leaves system Wi-Fi trust decisions to the owner, uses bounded retries, and restores only previously authorized incoming standby. Explicitly disabling recovery stops regranting and restores changes owned by this feature. It is not root access or a guarantee against every manufacturer's process management.

The new recording and recovery implementations have offline checks. Playback from a real recorded phone call, and recovery after an actual reboot/update, remain to be verified on the phone.

See the [complete calling guide (简体中文)](ai-phone-calls.md) for audio limits, service costs, optional voice cloning, and discussing call instructions with Codex.

## Updates and migration

In Flash phone, open **Settings → About → Check for updates**, download the update and confirm in the Android installer. The independent release source is [DzsGatsby/FlashPhone](https://github.com/DzsGatsby/FlashPhone/releases).

Versions through 1.0.7 that check the former release source can use the one-time 1.0.8 transition update. You can also download a signed APK directly from the new release page and install it over the existing app. Keep the existing installation to preserve settings and records. See the [release guide (简体中文)](app-updates.md).

[Report call or device compatibility issues](https://github.com/DzsGatsby/FlashPhone/issues/new?template=device-report.yml).
