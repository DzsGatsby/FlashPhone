# Flash phone：应用更新与发布

Flash phone 从独立项目的 [GitHub Releases](https://github.com/DzsGatsby/FlashPhone/releases) 检查和下载版本。发布标签为 `vX.Y.Z`，每次发布提供一个完整的 `FlashPhone-vX.Y.Z.apk` 安装包。

## 安装、更新与迁移

- **第一次安装**：打开 [最新版](https://github.com/DzsGatsby/FlashPhone/releases/latest)，在 Assets 下载 FlashPhone APK，再按 Android 提示安装。如果系统询问是否允许安装，请只给打开此 APK 的应用授权。
- **应用内更新**：打开 **设置 → 关于 → 检查更新**，下载后在 Android 安装器确认。应用也会在启动及 Android 允许的后台时段检查；省电、网络限制或强行停止可能延后后台检查。
- **从旧地址迁移**：仍从原仓库检查更新的 1.0.7 及更早版本，可通过应用内更新安装一次过渡版 **1.0.8**。也可直接从新发布页下载签名 APK 覆盖安装，无需 USB。安装后，应用会从新仓库检查后续版本。
- **保留原有数据**：不要先卸载 Flash phone。正式 APK 沿用既有安装的应用 ID 和签名，正常覆盖安装可保留设置和记录。其他签名的自行构建版本不能直接覆盖。

更新后会尝试恢复此前授权的后台接听。已保存安全设置授权、无线配对和受信任 Wi-Fi 连接时，也会尝试自动重新开启无线调试并启动新版助手；系统信任提示、强行停止及厂商限制仍生效。打开接听页核对助手及待命真实状态，安装成功不等于电话已就绪。重启/升级后恢复的新增路径已做代码与离线检查，手机实际恢复仍待验。详见[锁屏接打与后台待命](ai-phone-calls.md#锁屏接打与后台待命)。

新版在 AI 通话阶段默认保留本机加密的双声道录音，人工接管时停止；回放不上传。最多每份 600 秒、50 份及 200 MiB，超限先移除最旧录音。旧通话不会凭文字补生成录音；真实电话录制与回放仍待实机验证。音轨范围和临时文件规则见[本地记录和数据](ai-phone-calls.md#本地记录和数据)。

## 发布者操作

准备 JDK 17、Android SDK Build Tools（`aapt`、`apksigner`）、Python 3 和已登录的 GitHub CLI。更新 `app/build.gradle.kts` 的 `versionName` 与递增的 `versionCode`，将干净的候选提交推到独立仓库的 `main`。

在保存有原签名的机器上构建并通过测试：

```sh
./gradlew -PdevInstall -PphoneCodex=true :core:test :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

让 `REFERENCE_APK` 指向已核验的旧版签名 APK，`VERSION` 为这次发布的版本号（不带 `v`），然后预检：

```sh
python3 tools/release/publish-flash.py \
  app/build/outputs/apk/debug/app-debug.apk "$VERSION" "$(git rev-parse HEAD)" \
  --branch main --reference-apk "$REFERENCE_APK"
```

也可用 `--signer-sha256` 提供独立核验过的旧证书摘要；证书摘要与 APK 文件摘要不同。用 `--notes-file` 提供已审阅的发布说明。默认预检只读，全部通过后用同一命令追加 `--publish`。

脚本核对 GitHub 分支、提交、APK 版本、应用 ID、架构和签名，重新构建并逐字节比较候选包。正式发布前先上传至草稿，核对唯一 APK 的名称、大小、摘要和下载地址；发布后从应用使用的 [latest API](https://api.github.com/repos/DzsGatsby/FlashPhone/releases/latest) 回读版本和附件。重复版本不会覆盖现有附件。旧地址的过渡发布仅用于 1.0.8，后续版本只发布到新仓库。

**保管好原签名私钥。** 旧 APK 和证书摘要只能用于核验，不能代替签名私钥。签名文件不可提交到 Git 或作为发布附件。
