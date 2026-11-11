# Flash phone 私人控制面

`worker.js` 是可直接 multipart 上传的单个 ES 模块；无 Wrangler 配置、无生产 npm 依赖。
默认 export 的 Worker 负责同源/HTTPS检查，`OwnerControl` named export 使用 SQLite Durable Object 与可休眠 WebSocket。
每个个人 owner 是一个独立 Durable Object 实例；当前只启用机主私人实例，没有注册或选择他人账户的入口。

## 机主先这样使用

正式控制台：[flash-phone.dzsgatsby.workers.dev](https://flash-phone.dzsgatsby.workers.dev)。
网页是控制端；实际电话由选中的 Android 手机或已完成设置的 Mac/iPhone 桥接设备执行。

### 安装与登录

- **Android**：从 [FlashPhone 正式发布](https://github.com/DzsGatsby/FlashPhone/releases/latest)下载安装包含“设备账号与远程电话任务”的版本（1.1.0 起）。已有用户可从 App 内检查更新；同包同签名覆盖安装，保留设置和记录，不卸载旧版。
- **已配置的 Mac**：打开已安装的 Flash phone 控制台入口，或运行下面的本机客户端命令。程序从本机私有配置生成一次性登录链接，浏览器打开后自动换取安全 Cookie；不需要查找或粘贴主密钥。

```sh
python3 "$HOME/Library/Application Support/Flash phone/bin/flashphone.py" open-dashboard
```

Mac 链接中的 `#login_code` 在页面脚本启动时立即从地址栏和当前历史项移除，不进入 URL query；成功、失败后均清掉页面内存中的短码，不把 token 保存到 localStorage。
如果链接已用过或超过5分钟，重新从 Mac 入口打开即可；不会重新执行电话任务。
在另一台手机/电脑登录：已登录控制台 → **设备与授权 → 生成登录短码**，另一台浏览器打开控制台并输入该短码。跨设备登录码只用于控制端，不能代替下面的执行设备注册码。

本机客户端的私有配置为 `~/Library/Application Support/Flash phone/controller.json`，仅当前用户可读（0600）；不要把其内容发到聊天、截图、issue或仓库。客户端及工具参数见 [Flash phone 客户端技能](../flashphone-client/skill/SKILL.md)，[客户端源码](../flashphone-client/flashphone.py) 与 [Mac 启动入口](../flashphone-client/launcher.swift)。新 Mac 的配置和授权须由机主/维护者完成，公开网页没有注册新机主的入口。

### Android 一次性加入账号

1. 在已登录控制台进入 **设备与授权 → 注册设备**，得到5分钟有效、只能用一次的设备连接码。
2. 手机打开 Flash phone → **设置 → 连接设备账号 / 远程电话任务**，填写设备名称和 **一次性设备连接码**，点 **连接设备账号**。
3. 手机完成本来的电话、SIM、语音服务与音频助手配置后，明确开启 **允许远程电话任务**；接受本应用的后台连接通知授权。此开关允许本账号以后按明确交代直接发起电话，无须每通再在手机点确认，仍会在手机检查号码、SIM、空闲状态与电话权限。
4. 控制台刷新，核对这台手机的在线与就绪状态。需要时在手机点 **恢复后台连接**；关闭远程任务开关会停止接收任务并交还 AI 通话，不会隐式挂断运营商电话。

加入账号与音频助手授权是两个环节；有设备名称、已配对或网络已连接都不能代替电话实际就绪。设备凭据由手机本地加密保存，不要求反复输入主密钥或把手机暴露成公网监听服务。

### 在线、就绪与可拨号是不同状态

| 显示 | 表示什么 | 下一步 |
| --- | --- | --- |
| 离线 | 没有已确认的设备连接 | 查看本机联网、远程开关和后台通知状态；不会排队稍后自动拨号 |
| 在线但未就绪 | 已连上私人账号，电话或音频条件尚未满足 | 按设备返回的中文原因处理，先不创建新电话任务 |
| 在线、ready=true、含 `calls.dial` | 设备声明具备本次远程拨号条件 | 仍须明确选择设备、号码、SIM和本次交代；实际效果以电话事件及对端证据为准 |
| `requires_setup` / iPhone 账号要求 | Mac 桥接可在线，但 Apple 电话或音频配置尚未通过 | 机主自行核对 iPhone/iCloud/FaceTime 与 Mac Apple Account；不切号、不自动重试电话 |

Mac/iPhone 的构建、一次性加入与本机权限见 [Mac 桥接说明](../mac-bridge/README.md)。当前已观察到 Apple 的“iPhone Calls Not Available”账号要求阻碍，尚未确认 ACTIVE，没有真实 iPhone 双向通话成功证据；在线、虚拟回环或云端合成协议验收均不等于 iPhone 可以打电话。

### 发起、跟进和取回完整结果

网页 **发起通话** 中选择已就绪设备和线路，填写号码、**本次目标**、必须问到的问题、背景与不能承诺的事项，再核对并确认一次。设备离线、忙碌或未就绪时直接拒绝；网络中断后查看原任务，不能换一个新标识反复拨号。**通话记录** 可按目标、号码、状态和设备筛选，原地展开详情、事件、对话原文和录音；复用只创建草稿，不自动重拨。

Codex/Grok 已安装 Flash phone 工具时，也可以直接交代指定设备、号码、目标、问题和限制。工具流程为：

1. `flashphone_devices`：按机主指定的设备核对 `online`、`status.ready` 和 `capabilities`。
2. `flashphone_call_task`：为同一次意图保留一个 `idempotency_key`。取得 task_id **仅说明受理**，不能据此说已打通或目标完成。
3. `flashphone_wait_task`（每次最多25秒）或 `flashphone_task_status`：继续查询同一 task_id，保留 `timed_out` 与 `terminal` 的区别。`accepted/preparing/dialing/ringing/active/ai_active` 都不是最终结果；`handed_off` 表示 AI 已退出，系统电话可能仍在进行，仍非终态。
4. 仅当 `carrier_ended=true` 才称系统电话真实结束。`failed/canceled/interrupted` 可以终止任务，但不能被写成对话成功或已确认挂断；`interrupted` 的结果保持未知。
5. 返回实际 `result.summary/outcome/transcript`、未回答事项和结束原因。`needs_review` 不是目标已完成。再用 `flashphone_recording` 查可用录音；录音上传可能晚于通话终态，必要时刷新等待 `recording_ready`，不要为得到录音再拨一次。

录音只通过已认证的控制台/客户端播放或下载，没有公开 token 链接。AI 音轨仅说明本机/桥接管道接受了相应音频，不能单独证明远端实际听清。
`flashphone_cancel_task` 取消未拨任务或停止 AI 并交还系统；只有机主明确要求挂断时才用 `flashphone_hangup_task`，严格绑定原任务的那通电话，不影响后来出现的其他电话。
CLI 的 `devices/status/wait/recording` 对应上述同一组任务，参数见 [客户端技能](../flashphone-client/skill/SKILL.md)。

### 本说明的验收边界

Cloudflare正式兼容日期2026-11-11的隔离合成环境已验证 MCP → WSS 单次指令 → 持久事件 ACK → 最终结果回读、权限隔离和不重拨，合成资源已清理；全程没有真实电话、模型会话或录音对象。
这些证据不证明 Android/iPhone 真机外呼、远端听见、双向录音或浏览器实际布局全部通过。已在 Mac Chrome 实际验证控制台启动器的一次性短码自动登录、工作总览/设备授权/记录/发起通话导航、设备阻碍展示、注册码弹窗，以及未提交草稿切页恢复与清理。该检查未拨号；手机浏览器布局、真实电话任务详情及录音回听仍须单列验收。

## 部署

以 Cloudflare CLI `cf` 或已授权 connector 部署，不运行 Wrangler、不修改账号套餐。
`metadata.example.json` 供首次 multipart 上传参考；既有命名空间不重复创建 migration。
如使用 declarative exports，请声明 `OwnerControl` 为 SQLite durable-object。
必需绑定：

- `OWNERS` → `OwnerControl` SQLite DO。
- `RECORDINGS` → 私有 R2 bucket `flash-phone-recordings`，关闭公共访问。
- `ROOT_KEY_HASH` → secret_text，值为本机随机 256 位主密钥的 SHA-256 hex。不要把主密钥、token或secret写入版本库。

目标 compatibility date `2026-11-11`，`nodejs_compat`。开启 logs/traces，但关闭 invocation logs；源码仅显式记录固定 event/category，不打印请求、参数、号码、任务、音频、token或短码。root登录响应只设置30天HttpOnly Secure SameSite=Strict cookie；MCP/原生客户端可用Bearer。

## 契约补充（PROTOCOL.md不变）

- Web使用 `GET /api/me`、`GET /api/sessions`、`DELETE /api/sessions/:id`、`POST /api/logout`。
- `hello` 持久化设备状态后回 `{v:1,type:"hello_ack",device_id,server_time,protocol:1}`。`status` 可含 `sims:[{id,label,default}]`。
- `event` 持久化后回 `{v:1,type:"ack",task_id,sequence}`。旧序号重复也ack；设备收到ack才删持久spool。
- 错误：HTTP非2xx `{error:{code,message}}`；WS `{v:1,type:"error",error:{code,message}}`。撤销设备关闭连接4003/device_revoked。
- enroll响应 `{device_id,device_token,account:{id:"personal",label:"我的 Flash phone"},account_label:"我的 Flash phone"}`。
- cancel/hangup有效30秒，expected_call_id使用最新绑定；未绑定cancel可为null，未绑定hangup拒绝。
- 已收到dialing不能称完成。`handed_off`不结束任务；系统真实挂断才为ended+carrier_ended=true。
- 一般JSON请求16KiB；command16KiB内；WS事件80KiB，单task累计result/transcript64KiB。
- 录音响应 `{recording:{task_id,bytes,sha256,url}}`。推荐上传 `X-Recording-SHA256` 64位hex：同任务/长度/摘要重复返回既有结果，其他字节409，不覆盖原证据。服务端以原生DigestStream验证摘要，以FixedLengthStream保持R2已知长度；仅缓存12字节WAV签名。下载仅controller，支持Range，URL不带token。

## 限额与恢复

32执行设备，64控制会话，16未使用授权码；短码8位、5分钟、一次消费；失败5次/IP/5分钟，另有全局100次/5分钟。
每设备一通未结束任务；不接受离线/未就绪设备，不创建稍后拨号队列。命令落库后先标消费再发送，重连不重播。
消费过的幂等标识永久保留最多10000，满后拒绝新任务；已清理任务的同key返回410，不创建新电话。
200任务、128事件/任务，终态任务30天，超过180条的旧终态任务按alarm清理。录音40MiB/份、50份/1GiB/owner、最多2并发；每次上传120秒硬截止并取消流，提交前重查同一reservation；未完成上传10分钟兜底清理。
无idle interval/cron。只在实际到期项和未确认任务存在时设置DO alarm；MCP wait最多25秒。撤销/失联不会隐式挂断电话。

## 构建和本地验收

```
node build.mjs
npm install --ignore-scripts --no-audit --no-fund
npm test
```

`npm test`用官方Miniflare/workerd、真实SQLite/R2/WS，所有电话均为合成协议消息，绝不触发手机。
覆盖认证、CSRF、身份撤销、device scope、幂等、真实end与handoff、受保护流式录音、MCP和workerd进程重启。
本机可用workerd最大compat date为2026-09-15：可显式用
`MINIFLARE_WORKERD_PATH=<有效本地workerd> TEST_COMPATIBILITY_DATE=2026-09-15 npm test`
做兼容路径验收；这不等同已验收部署目标2026-11-11，部署后还需真实HTTPS/WSS、空设备状态、MCP、授权与受保护录音API读回。

控制台浏览器只轮询当前可见页面（有任务5秒、空闲20秒），切后台停止；不会mock设备在线或虚构真实通话成功。
