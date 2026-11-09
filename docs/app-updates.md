# Flash phone：应用更新与发布

Flash phone 直接从 [GitHub Releases](https://github.com/DzsGatsby/Mika/releases) 检查版本和下载 APK，不需要自建更新服务器。首个版本为 `0.1.39`（Android 版本代码 `40`），发布标签为 `v0.1.39`，附件为完整的 `FlashPhone-v0.1.39.apk`。

## 已安装用户怎么更新

- 如果目前安装的是此前的电话实验版本，请先从本仓库发布页下载并覆盖安装 `0.1.39`，让应用切换到新的更新源。保留原有安装和数据，直接打开 APK 完成系统确认；不要先卸载。
- 此后打开应用可触发检查，也可使用应用中的检查更新入口。后台会向 Android 申请约每 15 分钟检查一次；这是系统调度间隔，省电、网络限制或强行停止应用会推迟检查。安装需要用户在 Android 系统页面确认。
- 原有正式 Mika 应用可并存。Flash phone 沿用历史应用 ID `com.wangzan101.mika.dev` 和签名，只为了让此前同包、同签名的安装可以覆盖升级并保留数据。两者使用不同的发布源和安装包。

## 发布者操作

准备 JDK 17、Android SDK Build Tools（`aapt`、`apksigner`）、Python 3 与已登录的 GitHub CLI。GitHub 账号需要 fork 的发布权限。先更新 `app/build.gradle.kts` 的 `versionName` 和递增的 `versionCode`，提交改动并把该提交推到 fork 的目标分支。

在已保存原签名的本机，从干净的该提交构建并通过测试：

```sh
./gradlew -PdevInstall -PphoneCodex=true :core:test :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

保留一份已经在原设备上验证过的旧 APK 作为签名基准。将 `REFERENCE_APK` 指向这份 APK，先预检：

```sh
python3 tools/release/publish-flash.py \
  app/build/outputs/apk/debug/app-debug.apk 0.1.39 "$(git rev-parse HEAD)" \
  --branch feat/ai-phone-calls --reference-apk "$REFERENCE_APK"
```

也可用 `--signer-sha256` 传入独立核验过的旧证书 SHA-256；证书摘要和 APK 文件摘要是不同的值。可用 `--build-tools` 指定 SDK 工具目录，用 `--notes-file` 指定已审阅的公开发布说明。默认预检只读 GitHub，无标签、发布或上传操作。全部检查通过后，使用同一命令追加 `--publish`。

发布模式会确认本地 HEAD 干净、GitHub 分支的提交与其完全相同，核对 APK 的应用 ID、版本名、版本代码、架构和签名，再运行上述构建与测试并验证候选 APK 与本次构建产物逐字节一致。脚本先把唯一的 APK 附件上传至 GitHub 草稿，读回 SHA-256、大小、名称和下载地址后再设为正式最新版。最后从应用读取的 [latest API](https://api.github.com/repos/DzsGatsby/Mika/releases/latest) 与 Git 标签回读核验。若某版本已经有 Release（包括未完成的草稿），脚本会停止，不覆盖附件；检查草稿或采用新的版本号后再发布。构建、标签和附件上传方式见 [GitHub CLI 发布手册](https://cli.github.com/manual/gh_release_create)；APK 证书校验使用 [Android apksigner](https://developer.android.com/tools/apksigner)。

**必须保留当前构建使用的本机签名。** 旧 APK 和证书摘要可用来核对签名；真正构建仍需要原来的私钥。把本机签名文件妥善保存在可信设备或安全备份中，不能提交到 Git、上传为 Release 附件或放进 CI 日志。签名丢失后，用新签名构建的包不能直接覆盖现有安装。
