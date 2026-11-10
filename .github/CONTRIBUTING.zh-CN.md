# 参与贡献

[English](CONTRIBUTING.md) · 简体中文

Flash phone 专注用 SIM 处理运营商电话：接听规则、拨出任务、通话音频与接管、记录和应用更新。

## 报告问题

电话音频、助手配对、后台待命或机型相关问题请提交[机型兼容性报告](https://github.com/DzsGatsby/FlashPhone/issues/new?template=device-report.yml)。填写手机型号、Android 版本、Flash phone 版本（**设置 → 关于**）、复现步骤、预期和实际结果。如果是音频问题，请说明是否锁屏以及电话两边各听到了什么。

不要贴号码、通话原文、密钥或配对码。需要重现通话时，请先征得对方同意。

## 构建和测试

使用 JDK 17，并通过 `ANDROID_HOME` 或 `local.properties` 配置 Android SDK。在仓库根目录执行：

```sh
./gradlew -PdevInstall :core:test :app:testDebugUnitTest :app:assembleDebug
```

`-PdevInstall` 使用 Flash phone 的应用 ID；若开发包和已安装应用签名不同，请用单独的测试设备。要打包可选的 Codex 引擎，加 `-PphoneCodex=true`。详见[电话开发验证](../docs/ai-phone-calls.md#开发验证)及[维护者发布流程](../docs/app-updates.md#发布者操作)。

改动电话行为时尽量补有针对性的测试，并说明实测的锁屏状态和通话两端各听到了什么。更新[英文首页](../README.md)时同步维护各语言译文；`ReadmeTranslationsTest` 会检查指纹和结构。

[继承的 Mika 组件清单](../docs/dev/inherited-catalog.md)供阅读共用源码时参考。清单的源数据变更后，运行 `./gradlew updateReadme`。
