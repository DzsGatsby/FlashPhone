<!-- translated-from: README.md @ deb26d8962f6 -->

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
- **回看原话和任务。** 结束后核对通话文字与任务、回放本机双声道录音，标记处理结果，或将旧任务复制成新草稿。
- **连续处理工作。** 首页、记录、外呼任务、接听规则与设置使用常驻导航。可搜索筛选记录、切换详情、复制或导出文字；未发送的任务加密保存在本机，重启后可恢复。复用记录只创建草稿，不会直接拨号。

## 安装与上手

1. 使用 Android 12 或更新版本及可用 SIM。打开 [GitHub 最新版](https://github.com/DzsGatsby/FlashPhone/releases/latest)，下载 `FlashPhone-vX.Y.Z.apk`，按系统提示安装。若系统提示，为打开 APK 的应用允许安装权限。
2. 在手机上无线配对应用内的通话音频助手，填入火山端到端实时语音凭据。音频支持取决于机型和系统版本。
3. 先实测双方听得见、人工接管正常，再开启自动接听。确认音频并开启后，后台待命自动启动；关闭自动接听同时撤销待命。详见[首次配置与兼容性](../ai-phone-calls.md)。

已安装的用户可在 **关于 → 检查更新** 下载新版（主界面右上角信息按钮；旧版从设置进入关于），也可下载签名 APK 直接覆盖安装，保留设置和记录。详见[更新与迁移指南](../app-updates.md)。

## 多设备与智能体工具

1.1.0 新增可选的私人控制面：Android、iPhone 和电脑浏览器可共享同一机主的设备列表、电话任务、转写及受保护录音。将 Android 执行设备一次性加入账号，并在其设置中明确开启远程电话任务。网页与七个 `flashphone_*` MCP 工具操作同一组任务，也能取得最终结果；拨号请求的受理不会被当成对话完成。详见[配置与使用指南](../../tools/control-plane/README.md)及 [Codex / Grok Bot 技能](../../tools/flashphone-client/skill/SKILL.md)。

Mac/iPhone 桥接仍为实验功能，目前开发机受 Apple 的 iCloud/FaceTime 账号要求阻碍。实际通话控制与运营商双向音频尚未验证，在这些检查通过前，桥接端不会声明可拨号。iPhone 上的网页控制台可独立作为控制端，不依赖该 iPhone 执行电话。见 [Mac 桥接状态与配置](../../tools/mac-bridge/README.md)。

## 服务与数据

Flash phone 使用现有 SIM，保留原有拨号器。AI 处理电话期间，语音会发往配置的语音服务，同时默认自动保存本机双声道录音：左侧为采集的对方声音，右侧为设备电话音轨已接受的 AI 音频，人工接管时停录。录音与文字加密保存在本机，回放不上传。插话清空队列可能丢弃未播放数据，因此右声道不能证明远端实际听见。运营商通话和语音服务可能分别计费。

每份录音最多 600 秒，最多 50 份、总量 200 MiB，空间不足先移除最旧录音。完成无线配对和设备支持的授权后，会在已连接的受信任 Wi-Fi 上尝试恢复无线调试，并在开机、解锁或更新后恢复此前授权的后台接听；不绕过系统信任、强行停止或厂商限制，不获得 root。新增录音和恢复已做离线检查，真实手机录音及重启/升级恢复仍待实机验收。

明确开启远程电话任务后，该任务的转写和可用录音还会经 HTTPS 上传至机主已认证的控制面及私有 R2 存储桶，没有公开分享链接。这份远程副本独立于普通本机回放，并有自己的保留数量与时长限制。控制面只传递任务与结果，不中继实时通话音频，也不运行语音模型。

## 项目与许可

Flash phone 是基于 [wangzan101 和贡献者开发的 Mika](https://github.com/wangzan101/Mika) 构建的独立项目。见 [Apache-2.0](../../LICENSE)、[上游版权声明](../../NOTICE)和[第三方声明](../../licenses/THIRD_PARTY_NOTICES.md)。[参与贡献](../../.github/CONTRIBUTING.zh-CN.md)。
