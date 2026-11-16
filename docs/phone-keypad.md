# 通话按键与客服语音菜单

电话接通后，AI 可以使用内置 `send_dtmf` 工具发送 0–9、*、#。实时语音模型依据当前客服菜单和本通任务选择下一步，收到发送结果后继续听下一层菜单；不在拨号前的语音交代会话中提供此工具。

正在通话页提供“按键”入口，可逐键操作，也可连续输入号码并发送。每次最多 32 位。发送绑定打开面板时的同一通电话，电话结束或切换后不能沿用旧面板操作新电话。发送中不接受另一组重叠按键。

使用 Android `Call.playDtmfTone` / `stopDtmfTone`，每个按键成对开始与停止，随后才发送下一位。AI 每通最多 24 次工具请求、累计 96 个按键。所有权检查、取消和发送上限限制异常循环；不自动重试部分发送或结果不明的输入。按键结果仅报告设备提交状态、完成数量和是否部分完成，不说明对端客服一定识别成功，也不把长号码复制到转写或诊断中。

实时协议按官方 Seeduplex Function Calling 约定，通过 `session.update` 注册工具，处理 `response.function_call_arguments.done`，再用对应 `call_id` 的 `conversation.item.create` 回传结果。接收音频和麦克风发送继续运行，不因逐位等待而阻塞。只复用现有语音会话，不增加独立模型或常驻云服务。

参考：[Android Call 接口](https://developer.android.com/reference/android/telecom/Call)、[豆包实时语音接入说明](https://docs.volcengine.com/docs/doubao-voice/access-must-read?lang=zh)、[全双工接口](https://docs.volcengine.com/docs/doubao-voice/end-to-end-real-time-voice-full-duplex-version?lang=zh)。

## 验收边界

离线测试核对协议消息、重复和过期请求、逐位时序、取消及通话身份变化；隔离模拟器核对按键面板和原有操作。没有真实手机运营商通话与客服菜单的实证前，不称已完成端到端按键验收。
