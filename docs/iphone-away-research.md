# iPhone 随身外出，家中 AI 使用原号接打：接续研究

核查日期：2026-11-12。状态：**未部署、未完成端到端验证**。

## 目标与边界

大陆联通原 SIM 留在随身 iPhone，手机在外用 4G/5G；Mac 在家，AI 仍用原号码自动接听、主动外呼并保存双向录音，任务经现有 Flash phone MCP/控制面返回结果。无需日常打开 App，不增加随身设备，不改变现有网络。不默认为已允许换号、移卡、呼叫转移、购买设备或开通收费服务。

## 已接回的侧边对话

侧边会话 `01a1cb7c-3158-74c2-ae3a-53ba3ff218d4`，继承主会话后于北京时间 2026-11-12 00:42—02:49 独立研究；最终标记受阻，未完成。此前没有部署 VPN、购置硬件或验证异地通话。

最后候选为：联通同号蜂窝手表留家 → ESP32 HFP → USB 串口 PCM → Mac AI。手表不是已拥有设备，机型互通尚无实证。不能将侧边对话的候选当成主项目已实现能力。

## 本轮新增结论

### 1. iPhone 原生接口：可单向注入，不构成无人值守 AI 对话

Apple 提供向 Phone/FaceTime 通话加入合成语音的 API，从 iOS 18.2 起可用，因此“原生通话完全不能注入 AI 语音”过于绝对。

但 2026-09 Apple Developer Forums 的实机问题与本目标高度吻合：iPhone 16 Pro/iOS 26.6.2，自动接听后前台能播报；锁屏后 app 挂起，事件回到前台才到达。Apple DTS 明确说明没有蜂窝接通唤醒第三方 app 的公共机制，也没有获取原生蜂窝对端语音的公共 API。语音注入 API 本身不提供对端声音。2026-10 DTS 再次确认 CXCallObserver 不负责挂起 app 的唤醒/重启。

来源：
- https://developer.apple.com/documentation/avfaudio/adding-synthesized-speech-to-calls
- https://developer.apple.com/forums/thread/848873
- https://developer.apple.com/forums/thread/849682

结论限于受支持公共 API；没有对所有私有接口作不可能性证明，也没有为此越狱、修改系统或启动真实通话。

### 2. 同号手表：保留为实验候选，不能直接进入采购

华为 WATCH 4 兼容表确认 iOS 支持 eSIM 一号双终端；官方说明副终端独立外呼显示主号码，独立 eSIM 通话可以走蓝牙耳机。这覆盖“号码归属”和“音频到耳机”两个分段条件。

新增的重要缺口：
- 华为通话总说明仍写独立使用需要佩戴；联通专项说明虽能关闭离腕/睡眠自动断网，但没有证明**持续离腕、在充电座上、锁屏及重启后**均能自动接打。
- 耳机能接听不证明指定手表接受外部 HFP `ATD<number>` 任意号码拨号。HFP 标准能力与具体机型行为须分开。
- `jensb1/handsfree` 的实现不是免驱 USB 声卡，而是自定义串口 PCM；现有 Flash phone Phone.app tap/虚拟麦克风不能直接套用，需新增 transport adapter。
- 不能用其他运营商的规则替代联通的同振、另一端接听取消及并发规则。

来源：
- https://consumer.huawei.com/cn/support/content/zh-cn15953878/
- https://consumer.huawei.com/cn/support/content/zh-cn15957523/
- https://consumer.huawei.com/cn/support/content/zh-cn15818715/
- https://consumer.huawei.com/cn/support/content/zh-cn15817379/
- https://consumer.huawei.com/cn/support/content/zh-cn16121881/
- https://m1.img.10010.com/resources/noticeSys/20260828/html/47a1d514cb944acd9a0e85d507aac864.html

桥接实现固定审计版本：`jensb1/handsfree@99d52220ff22772f7c1fd41af854ac3b5596008d`。
https://github.com/jensb1/handsfree/blob/99d52220ff22772f7c1fd41af854ac3b5596008d/esp32/main/main.c

该实现具有 dial(arg)、单独 redial、answer_call、双向 8kHz PCM 和断线重连；作者说明 macOS 26 原生 HFP SCO 返回 unsupported，采用原版 ESP32 解决音频桥接。该描述是作者的设备实证，不是本项目当前 Mac 的全平台结论；仓库未证明 WATCH 4 兼容。不能把其代码存在或本地编译成功当作已支持手表。

### 3. 运营商网络侧：不要混淆三个不同入口

- Apple “在其他 iCloud 设备进行 Wi-Fi 通话”：是可能脱离 iPhone 的官方路径；大陆联通当前 Apple 功能表未列出，不按可用交付。
- 联通助理/AI 秘书：官方说明通过遇忙/不可及呼叫转接代接。不是已提供给个人原号的自定义双向 AI API，也未证明支持用户要求的主动外呼。
- **联通 App 内电话/Wi-Fi 通话小程序**：与 Apple Continuity 不同。昨晚仅有新闻检索提及，未做业务协议/实机检查。本轮新增为优先核验入口：是否能在无原 SIM 的现有安卓上登录 iPhone 原号码，以及 iPhone 原生和家中 App 是否允许同时在线。公开新闻的“有 Wi-Fi 就能用”不能证明这两项。

当前仅查到主 App 通用隐私政策，不能把其中读取 SIM 状态的权限推断为电话小程序必须插卡，也不能把普通账号登录推断为已经获得 IMS 通话资格。

来源：
- https://support.apple.com/zh-cn/109510
- https://vvm.wo116114.com/
- https://img.client.10010.com/stprototype/stAPPxy/indexUser.html

云通信产品的“自有号码”不能自动解释为个人手机 SIM：腾讯公开范围列固话/400/95；阿里号码规则明确不支持用户自有手机号码。未发现可据以直接开通的个人原号双向媒体接口。
- https://intl.cloud.tencent.com/zh/document/product/1229/59521
- https://help.aliyun.com/zh/aiccs/user-guide/number-application

### 4. VPN：没有把网络连通当作通话能力

Apple 官方普通中继仍要求同一 Wi-Fi。此前历史案例的异地 Wi-Fi + IKEv2 成功，不证明纯蜂窝成功。未找到当前系统下“iPhone 纯蜂窝在外、Mac 在家”完整双向音频可复核实现。

https://support.apple.com/en-ie/guide/security-pdf/sec28a79bf17/1/web/1

不修改路由、DNS、代理、PF，不为未经证明的路径部署常驻 VPN 或新增云计费资源。纯蜂窝候选标记为未证明，而不是物理不可能。

## 下一项实际核验

优先只读查看联通电话小程序的服务协议/帮助/开通条件，需取得官方页面中的：
1. 原 SIM 是否必须在运行小程序的本机；
2. 登录同号是否注销 iPhone 的原生语音注册，来电如何分配；
3. 是否支持呼入与呼出，外呼显示哪个号码；
4. 是否有官方 SDK/媒体接口，后台锁屏如何维持；
5. 当前号码能否使用，以及费用和实际开通动作。

只有前 3 项成立，才值得对家中安卓进一步开发媒体与无人值守适配。若原 SIM 必须在本机，直接淘汰这一条，不要求用户换卡来“验收”。

当前设备访问：Android 远程连接存在，但系统锁屏不被已配置解锁适配器识别，返回 unsupported_keyguard；ADB 无设备。iPhone Mirroring 显示 Mac 登录解锁窗口。没有读取、猜测或索取密码。已请求本人仅解锁镜像，再继续只读检查；没有发送预约、订购、拨号或短信。

## 手表借测验收清单（未执行）

先用可借测设备及其自有测试号码，不先开通用户号码的收费业务。真实拨号须另有明确测试范围；以下不是自动执行授权。

| 阶段 | 必须拿到的证据 | 失败后的决定 |
| --- | --- | --- |
| 手机外出 | 配对手机断开蓝牙、仅用蜂窝，手表仍入网 | 不能独立则淘汰 |
| 任意外呼 | 对一个此前未回拨过的获授权号码发一次 ATD，真实 CLCC active、对端见主号 | 不以 redial/AT OK 代替 |
| 来电 | 唯一来电身份、一次 ATA、接通后 SCO | 不抢占其他电话 |
| 双向 AI | 对端听见 AI 且 AI 收到对端第二/第三轮，独立双轨回听 | PCM 发送计数不算听见 |
| 竞争接听 | 手机上先接时，手表接听被取消且 AI 不另起会话 | 未知状态停止，不重拨 |
| 离腕充电 | 离腕充电至少跨过原睡眠策略窗口，仍完成同样接打 | 不能靠佩戴假设通过 |
| 恢复 | 桥重启、USB重连、手表重启后无需每天点 App | 待机在线不等于可通话 |
| 最终结果 | MCP 同一 task_id/call_id 的接通、结束、转写、录音回传 | 受理/在线不算完成 |

## 对主项目的影响

本次不发布新电话能力，不把现有 mac-continuity 端点标记为支持 iPhone 异地。既有 Android 来电成功与 Mac 普通拨号成功继续保留，但均不构成此目标的完成证据。Cloudflare 仍是任务和结果控制面，不能代替运营商媒体终端。
