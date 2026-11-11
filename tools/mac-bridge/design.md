# Mac / iPhone Continuity bridge：只读可行性核查

核查日期：2026-11-11。目标是让远程 MCP/skill 委派明确的电话任务，由指定设备执行并返回最终结果。**Mac 桥接有可实施的音频部件，但尚未证明 Phone.app 可无人值守控制，亦未证明真实运营商电话的双向声音。实现与本机虚拟链路验收见 [README.md](README.md)。**没有拨打/接听电话、抓取音频、启动 Phone.app、改变音频设备/服务、安装驱动或调用收费模型。

## 本机实证

只读检查 `sw_vers`、Phone.app 的 Info.plist/资源目录、`system_profiler SPAudioDataType -json`（报告只保留设备名）、指定 LaunchAgent 状态及 SDK 头文件，未读取号码、联系人、Apple Account、电话历史或凭据。

- macOS **26.5.1**；`/System/Applications/Phone.app` 存在，版本 1.0，bundle ID `com.apple.mobilephone`。
- Phone.app 注册 `tel`、`telephony`、`facetime-audio`、`tel-phoneapp`、`phoneapp`、`vmshow` URL scheme。**有 scheme 不等于有接听/挂断/读取通话状态的公开 RPC。**这次未调用任何 scheme。
- Info.plist 无 `NSAppleScriptEnabled` / `OSAScriptingDefinition`，资源目录未找到 `.sdef` / `.scriptSuite` / `.scriptTerminology`。本机 `/usr/bin/sdef` 因只有 Command Line Tools 而提示需要完整 Xcode；未安装 Xcode，也不由此推断存在可用的 AppleScript `answer` 指令。
- 枚举到的音频设备名：`LG ULTRAFINE`、`iPhone Microphone`、`BlackHole 2ch`、`MacBook Air Microphone`、`MacBook Air Speakers`、`Filmage Audio Device`、`ZoomAudioDevice`、`Filmage 聚集设备`。HAL 目录还存在 `ParrotAudioPlugin.driver`；仅安装文件存在不能证明其设备可用。
- `iPhone Microphone` 是连续互通麦克风设备，**不是蜂窝通话下行接口**；其存在不能证明已开通 iPhone 通话中继。
- 现有 `com.bijiben.sonobus-audio-mode` LaunchAgent 已加载且正在运行。因此 BlackHole/Filmage 不能视为新桥接独占的空闲资源，不能直接重置系统输入输出或停掉现有服务。
- 无提示的 `AXIsProcessTrusted()` 查询返回 true。这只说明当前脚本运行身份的辅助功能信任；不是未来独立 bridge app 的授权，也未核实该 app 的 Apple Events、麦克风或系统音频录制权限。
- 本机 SDK 实际声明：`AudioHardwareCreateProcessTap` 自 macOS 14.2 可用、iOS 不可用；`AVAudioSession.setPreferredMicrophoneInjectionMode` 自 iOS 18.2 / visionOS 2.2 可用、macOS 不可用。

## 官方支持与实际缺口

### 电话控制

Apple 支持从 Mac 的 Phone.app 经 iPhone 接打电话，不要求企业 SIP 或呼叫转移。正常中继前提包括同一 Apple Account、两端 Wi-Fi 及同一网络、iPhone 允许其他设备通话，以及 FaceTime 的相应配置。Phone.app 的官方操作是键盘/联系人拨号及通知上的 Answer/Decline/End Call。根智能体随后通过 CUA 读到 Phone Settings → Calls 的 “Calls from iPhone”=1；没有读取 Apple Account、号码或联系人。此设置开启仍不等于真实电话双向已验收。[Apple 中继要求](https://support.apple.com/en-sg/102405)、[Phone.app 指南（macOS 26 版本入口）](https://support.apple.com/en-kw/guide/phoneapp/phn28c9d643a/1.0/mac/26)。

首版控制适配器应采用用户已授权的、绑定 Phone.app 进程和当前窗口的 Accessibility 动作，并读回连接/结束状态；当前无可承诺的公开 Phone.app 电话脚本 API。先识别可用元素，再按实际的号码/SIM/任务确认操作，不用固定坐标或不检查焦点的全局按键。`tel:` 可作为打开拨号入口的候选，不作为可靠拨通/接听/结束证明。Apple 旧 URL 文档说明 iOS `tel:` 会请求拨号确认；不能将该旧文档直接当成 macOS 26 每次确认行为的证据。[Apple Phone Links](https://developer.apple.com/library/archive/featuredarticles/iPhoneURLScheme_Reference/PhoneLinks/PhoneLinks.html)。

**锁屏、未登录的 GUI session、休眠以及 Phone.app 通知布局变化，都是桌面控制适配器的真实边界。**本轮未运行 AX 读取通话 UI，因为可能暴露真实号码/历史。不能承诺 Mac 锁屏后靠 AX 自动接打，也不能以系统 Call Screening 自动询问来意等同于自定义 AI 接听。

多设备必须以 `device_id` 明确选择端点。一个 Mac 的 Continuity 配置不能直接视作任意多台 iPhone 的可编程设备池；具体中继 iPhone、默认语音线路和 macOS 26 的可选 SIM 行为仍需单独确认。后续版本的在线指南出现的线路选择能力，不自动等于这台 26.5.1 已具备或可脚本化。

### 来话音频：Mac 上获取 Phone 进程的输出

公开 Core Audio tap 能捕获指定进程或进程组的输出，并经私有 aggregate device 作为 HAL 输入读取；可以让原输出继续到原设备。相关 API 为 `CATapDescription`、`AudioHardwareCreateProcessTap`、`AudioHardwareCreateAggregateDevice`、`kAudioAggregateDevicePropertyTapList`，以及 HAL IO 回调。需要用途说明 `NSAudioCaptureUsageDescription` 和系统音频录制授权。[Apple 官方样例](https://developer.apple.com/documentation/coreaudio/capturing-system-audio-with-core-audio-taps.md)、[用途说明键](https://developer.apple.com/documentation/bundleresources/information-property-list/nsaudiocaptureusagedescription.md)。

这是 **Mac 端进程输出捕获能力**；Apple 的通用 tap 样例没有保证 Phone/Continuity 的每种通话都可被捕获。实际输出也可能来自 Phone 的系统辅助进程，不能只拿 UI 进程 PID 就声称拿到了对端语音。选择目标、流格式、进程退出和系统拒绝均须检查；本轮没有创建 tap 或 aggregate device，没有捕获本人声音。

备选是将 Phone 的输出送到一个独立虚拟接收总线，再由 helper 捕获。根智能体随后通过 CUA 确认本机 Phone.app 的 Video 菜单有独立 Microphone 与 Output，各自列出 Use System Setting、BlackHole、Filmage、Zoom 等项；仅观察，未改变选择。Apple 对 FaceTime 的文档也明确提供 Video 菜单设备选择。[FaceTime 设备选择](https://support.apple.com/et-ee/guide/facetime/fctm26739220/mac)。

### AI 上行：让 Phone 使用独立虚拟麦克风

Mac helper 可把模型 PCM 重采样后，通过 Core Audio HAL output / AudioUnit 写到专用虚拟设备；Phone 必须实际选用这个设备的输入端，才会把它作为麦克风送入中继电话。`BlackHole 2ch` 的现有输入/输出设备形态使此路线具备原型条件；**设备存在不等于 Phone 已在读它，更不等于远端可听**。

macOS 普通 `AVAudioEngine` 输出不会自动成为另一个进程的麦克风；不能把本机播放声音当成电话注入。优先“Phone 进程 tap 取下行 + 独立虚拟麦克风送上行”，避免把上下行接到同一个回环总线造成 AI 听到自己。若 Phone 只能跟随系统默认设备，涉及全局路由及现有 SonoBus 自动切换冲突，必须在后续明确的独占音频租约中处理；本轮不修改任何全局设置。

### iOS 原生的新上行能力，不等于完整蜂窝桥接

Apple 官方 AAC 样例支持从 iOS 18.2 起，把获授权 app 的音频加到使用 Apple voice processing 的 Phone、FaceTime 等通话中。流程是：本人开启“设置 → 辅助功能 → 音频与视觉 → 通话中添加音频”，app 声明 `NSMicrophoneInjectionUsageDescription` 并请求 `AVAudioApplication.requestMicrophoneInjectionPermission()`，再使用 `AVAudioSession.setPreferredMicrophoneInjectionMode(.spokenAudio)`；可监听 microphone injection capabilities 变化。系统麦克风静音也会令添加的音频静音。此 API **不可在 macOS 使用**。[Apple 官方样例与权限流程](https://developer.apple.com/documentation/avfaudio/adding-synthesized-speech-to-calls.md)。

因此不能说 iOS 完全没有上行注入能力；但它不提供第三方 app 读取系统蜂窝通话下行，也不授予自动接听控制。Apple DTS 明确指出不存在拦截原生蜂窝通话并处理其音频的这种通用框架，CallKit 不会把 Phone.app 的音频交给其他 app。不能把 CallKit 的自有 VoIP 通话控制用作系统蜂窝电话接管。[Apple DTS 答复](https://developer.apple.com/forums/thread/835552)。

## 最小可实施架构

```text
Grok/Codex skill → authenticated MCP task broker → selected device_id
  ├─ Android adapter → Flash phone existing task/call runtime
  └─ mac-continuity adapter → local signed Mac helper
       ├─ Phone.app AX controller (preflight → number/line confirmation → connected → end)
       ├─ RX: narrowly scoped Phone/output-process tap → resample → realtime model
       ├─ TX: model PCM → bounded ring buffer → dedicated virtual microphone → Phone
       └─ per-task state/result/recording, cleanup and audio-route restoration
```

- 独立 `MacAudioBridge.app` 使用稳定签名/Bundle ID 承接 TCC 授权。MCP 不直接让大模型任意执行 AppleScript/终端命令。
- MCP 最小接口：`devices.list`、`calls.start(device_id, number, line, task_brief, request_id, max_seconds)`、`calls.wait(task_id, bounded_wait)`、`calls.cancel(task_id)`。`task_brief` 复用目标/必问事项/背景/不得承诺；任务不可变、幂等且单设备限一通。
- 端点发布独立能力位：`control_ready`、`rx_verified`、`tx_verified`、`unattended_verified`、`line_selection_verified`，不以设备在线代表能接打。未通过双向实测的 Mac 端点只开放 preflight，不伪造 `completed`。
- 任务状态至少区分 `preparing/needs_user_action/dialing/ringing/active/finishing/completed/failed/cancelled`；`wait` 返回最终摘要、逐项得到的答复/未完成项、真实结束原因及受保护的本地记录引用。拨出成功、电话接通、任务完成是三件不同的事。
- 本地固定容量音频队列、时间戳与打断代际。下行录到对方、上行设备接受、远端确实听到分开验收；收到打断清模型排队和本机待播，不扩大成挂断或自动重拨。
- 网络任务凭据放钥匙串，MCP 鉴权与设备连接隔离。默认本机/受保护连接，调用方只能操作已登记设备；不给公网裸露任意 Phone/AX/音频控制接口。
- 首版保持单个已登录且唤醒的 Mac 端点，避免在未验证的多 iPhone/SIM 或锁屏环境承诺无人值守。Mac 休眠/退出登录/音频总线被占用时应准确失败，而非静默改全局设备或抢占现有 SonoBus。

## 后续真正需要本人处理的位置（本轮未出现弹窗，不请求现在处理）

1. 若尚未配置：iPhone“设置 → App → 电话 → 在其他设备上通话”允许此 Mac；Mac Phone/FaceTime 相关中继设置。Apple Account 登录/本人验证仍由本人处理，不读取账号或凭据。
2. 独立 helper 首次运行时，系统可能请求辅助功能、系统音频录制或麦克风许可；对应“系统设置 → 隐私与安全 → 辅助功能 / 屏幕与系统音频录制 / 麦克风”。仅在真的使用 Apple Events 时再请求“自动化”。当前脚本 AX=true 不代替这些授权。[Apple 权限说明](https://support.apple.com/en-my/guide/mac-help/mchl211c911f/mac)。
3. Phone 首次拨号/设备切换出现的真实系统确认，须在实际出现后识别；不得凭旧 iOS URL 文档提前宣称 Mac 每通都必须本人点击，也不得承诺可绕过它。
4. 首次有界真实验证：通过受控电话分别确认 Phone 输入虚拟设备的注入、下行 tap 可听、插话、手动接管、挂断和恢复。**这需要后续授权，当前设计报告不是已完成该实测。**

结论：可以先实现 MCP 多端点与 Mac preflight/任务状态，不必先动全局音频。Mac 音频与 Phone 控制作为独立适配器验证；在此之前，iPhone 原生上行注入、Mac tap、虚拟音频设备、Phone UI 中继均不能单独当作完整自动 AI 蜂窝电话已成立。

## 本轮实现补充（2026-11-11）

独立源码、可复现编译/离线测试和边界见 [README.md](README.md)。新 helper 实际只对 Filmage Audio Device 运行2s纯虚拟997Hz探针并通过，没有打开物理麦克风、Phone tap或电话，也没有改变系统默认设备/BlackHole/SonoBus。出站设备协议和ACK已对齐；未执行真实enroll、凭据配置或安装LaunchAgent。

根智能体的CUA额外确认了Phone keypad唯一popover/textfield、popover内Identifier=Accept/Description=Call，并输入合成号码读回后清空取消。native controller只实现受限准备阶段，ACTIVE/end选择器尚未核实，agent仍requires_setup、不宣称calls.dial。不能把合成loopback或创建tapAPI存在当成对端语音/真实上行的验收。

### 后续受控验收的实际结果

根智能体通过 CUA 进行获授权的单次有界实拨尝试时，Phone 显示 “iPhone Calls Not Available / Your iPhone must use the same iCloud and FaceTime account.”，未接通、没有 ACTIVE、没有真实模型会话。第一次点击后界面未变，根智能体误重复点击，产生两条失败记录，随后承认并停止所有进一步拨号；Phone 麦克风已恢复 Use System Setting。此次没有取得 ACTIVE/end 选择器或真实双向声音证明。daemon 以本地 setup_reason=iphone_account_requirement 明确汇报 blocked，保持设备在线但不发布电话能力；不会自行切换账号或重试。本人须核对账号条件，再另行明确授权后续实拨。

### 账号阻断的只读核对路径（未执行 UI）

Apple 官方中继文档明确要求：iPhone 的“设置→App→FaceTime”开启FaceTime、号码选择正确；Mac主Apple Account及Mac FaceTime登录与iPhone一致，另需允许其他设备通话。[官方中继设置](https://support.apple.com/en-us/102405)。Phone里Calls from iPhone=1只覆盖其中一个条件，不能替代这些账号/激活状态。

现有iPhone Mirroring若能正常连接，可由本人在镜像里的“设置→顶部Apple Account”与“设置→App→FaceTime”核对显示的账号/登录状态；无需为核对而拨号、切号或退出。Mirroring自身也要求Mac/iPhone同一Apple Account及双重认证，所以“镜像可用”仅是主账号匹配的间接线索，仍不证明FaceTime独立登录/号码激活满足电话中继。Apple的FaceTime设置文档允许仅号码而不登录Apple Account使用FaceTime；这也说明FaceTime“开”不自动等于已满足同账号中继。[Mirroring官方要求](https://support.apple.com/en-us/120421)、[FaceTime设置](https://support.apple.com/en-mide/guide/iphone/iph40976f340/ios)。

这只是官方文档研究；实现智能体没有打开镜像、读取Apple Account/号码、修改设置或再次拨号。若真正检查时系统要求设备密码/Touch ID/Apple Account验证，由本人处理，软件不绕过。当前Apple弹窗本身不证明具体哪台设备的哪个账号不一致。
