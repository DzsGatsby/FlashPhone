<!-- translated-from: README.md @ 9d5160db3e1b -->

<div align="center">
  <img src="../../app/src/main/res/drawable-nodpi/flash_phone_launcher.png" width="104" alt="Flash phone 图标" />
  <h1>Flash phone</h1>
  <p>用现有 SIM 接打运营商电话的 Android AI 助手。</p>
  <p>
    <a href="https://github.com/DzsGatsby/FlashPhone/releases/latest"><strong>下载 APK</strong></a> ·
    <a href="../README.md">使用指南</a> ·
    <a href="https://github.com/DzsGatsby/FlashPhone/issues/new/choose">反馈问题</a>
  </p>
</div>

[English](../../README.md) · 简体中文 · [繁體中文](README.zh-Hant.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Español](README.es.md)

## 能做什么

- **代接来电。** 设置 AI 可以接听的来电，把重要号码留给本人，也可以暂时关闭自动接听。通话中可接管，不会挂断。
- **带着目标打电话。** 选号码和 SIM，写下要问的问题、背景和可以承诺的事项，核对后再拨出。
- **锁屏接打。** 通话音频助手和后台待命就绪后，AI 可在锁屏时代接符合规则的来电。确认的拨出任务可设为 45 秒内锁屏后尝试拨出一次。
- **回看原话和任务。** 结束后核对通话文字与任务，标记处理结果，或将旧任务复制成新草稿。

## 安装与上手

1. 使用 Android 12 或更新版本及可用 SIM。打开 [GitHub 最新版](https://github.com/DzsGatsby/FlashPhone/releases/latest)，下载 `FlashPhone-vX.Y.Z.apk`，按系统提示安装。若系统提示，为打开 APK 的应用允许安装权限。
2. 在手机上无线配对应用内的通话音频助手，填入火山端到端实时语音凭据。音频支持取决于机型和系统版本。
3. 先实测双方听得见、人工接管正常，再开启自动接听。详见[首次配置与兼容性](../ai-phone-calls.md)。

已安装的用户可在 **设置 → 关于 → 检查更新** 下载新版，也可下载签名 APK 直接覆盖安装，保留设置和记录。详见[更新与迁移指南](../app-updates.md)。

## 服务与数据

Flash phone 使用现有 SIM，保留原有拨号器。AI 处理电话期间，语音会发往配置的语音服务；应用不保存通话录音，文字记录加密保存在本机。运营商通话和语音服务可能分别计费。

## 项目与许可

Flash phone 是基于 [wangzan101 和贡献者开发的 Mika](https://github.com/wangzan101/Mika) 构建的独立项目。见 [Apache-2.0](../../LICENSE)、[上游版权声明](../../NOTICE)和[第三方声明](../../licenses/THIRD_PARTY_NOTICES.md)。[参与贡献](../../.github/CONTRIBUTING.zh-CN.md)。
