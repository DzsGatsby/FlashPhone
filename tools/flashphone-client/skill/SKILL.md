---
name: flash-phone
description: 通过 Flash phone 的 MCP 或 CLI 管理已配对的安卓与 iPhone-Mac 电话设备，执行用户明确交代的电话任务，查询进度并取回最终结果和录音。
---

优先用 `flashphone_*` MCP 工具。工具不可用时使用本机私有客户端：
`python3 "$HOME/Library/Application Support/Flash phone/bin/flashphone.py"`。
客户端默认从同目录上一级 `controller.json` 读取受保护的凭据；不能在聊天、命令参数、issue、日志或技能中粘贴 token。

在 Grok Bot 的“在本机执行”环境使用专门的本机桥接入口：`$HOME/.grokbot/flash-phone/flashphone devices`、`tools` 或 `call flashphone_TOOL '{JSON参数}'`。它通过本机私有 Unix socket 访问同一控制面，凭据由 Mac 后台代理读取；保留旧 Mika 入口但新电话任务使用 Flash phone。不要在 Grok 云电脑里使用本机路径，也不要索取或粘贴控制密钥。

先列设备，按本次用户指定的设备选择，核对 online、ready 与 capabilities。iPhone 是 Mac 桥接执行器；`requires_setup` 不能当成可拨。用户未指定且有多个可用设备时，采用用户已保存的明确默认；没有明确默认再询问设备。

发起外呼前应有用户明确请求的号码、目标、需要询问的问题及限制。不要编造号码或任务内容。为同一次意图生成并保留一个 idempotency_key，网络超时后查询原任务或用同一个 key，不得换 key 重拨。

`flashphone_call_task` 返回 task_id 只证明任务受理。接着用 `flashphone_wait_task`（每次不超过25秒）或 `flashphone_task_status`，跟到终态。后台继续执行时如实报告进度；不要把 accepted、dialing、active 或 handed_off 描述成任务完成。

AI 交还系统而电话仍连接时应说明 AI 已退出。只有 carrier_ended=true 才能称实际电话已结束；interrupted/unknown 需要明确执行结果未确认。通话终态与录音保存是两个阶段，必要时再取 recording metadata。

返回任务的实际结果、未回答问题、结束原因和可访问的录音；未取得的内容留空，不推测对端说过或听见什么。转写或累计音频计数不能证明远端听清。

取消任务会取消尚未执行的拨号或停止 AI 并交还通话，不等于挂断。只有用户要求挂断该任务电话时才用 `flashphone_hangup_task`；服务端会核对绑定的任务/通话身份。设备离线、权限不足或前次状态不明时停止新拨号，不自动循环重试。

CLI 对应命令：`devices`、`call --device ID --number NUMBER --goal TEXT --idempotency-key KEY`、`status TASK_ID`、`wait TASK_ID --seconds 25`、`cancel TASK_ID`、`hangup TASK_ID`、`recording TASK_ID --output ABSOLUTE_FILE`。录音默认仅通过已认证接口取回，不创建公开分享链接。
