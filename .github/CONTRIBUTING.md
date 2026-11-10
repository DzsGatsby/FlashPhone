# Contributing to Flash phone

English · [简体中文](CONTRIBUTING.zh-CN.md)

Flash phone focuses on carrier calls over a SIM: answering rules, call tasks, call audio and takeover, records, and in-app updates.

## Reporting issues

Use the [device compatibility form](https://github.com/DzsGatsby/FlashPhone/issues/new?template=device-report.yml) for calling, audio helper, standby, or device-specific issues. Include the phone model, Android version, Flash phone version (in **Settings → About**), the steps you took, and what you observed. For audio problems, say which side heard what and whether the phone was locked.

Leave out phone numbers, call transcripts, keys, and pairing codes. Use a test call with the other person's consent if you need to reproduce a problem.

## Building and testing

Use JDK 17 and an Android SDK configured through `ANDROID_HOME` or `local.properties`. From the repository root:

```sh
./gradlew -PdevInstall :core:test :app:testDebugUnitTest :app:assembleDebug
```

The `-PdevInstall` build uses the Flash phone application ID; use a separate test device if your development APK has a different signing key from your installed app. The optional Codex runtime is included with `-PphoneCodex=true`; see the [calling guide](../docs/ai-phone-calls.md#开发验证) and the [maintainer release guide](../docs/app-updates.md#发布者操作) (both in Simplified Chinese).

For changes to call behavior, include a focused test where possible. Describe the paths you verified on a device, including lock state and what each side could hear. Keep README translations in step with the [English source](../README.md); `ReadmeTranslationsTest` checks their source fingerprint and structure.

The [inherited Mika catalog](../docs/dev/inherited-catalog.en.md) is a reference for the shared source. Run `./gradlew updateReadme` if you change the catalog's underlying data.
