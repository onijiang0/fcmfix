# fcmfix(Android 10-17)

[![Android CI](https://github.com/kooritea/fcmfix/workflows/Android%20CI/badge.svg)](https://github.com/kooritea/fcmfix/actions)

让fcm/gcm唤醒未启动的应用进行发送通知  

### 附加功能

- 阻止Android系统在应用停止时自动移除通知栏的通知
- 在miui/hyperos(?)/OxygenOS15(?)/ColorOS15/16(?)上动态解除来自fcm的自启动限制
- 移除miui/hyperos对后台应用的通知限制
- 没有预期唤醒目标应用时发送提示通知

### lsposed作用域
- 系统框架（system）必须勾选
- 在miui/hyperos上如果推送没有问题，就不需要勾选电量和性能
- **Android 15-17 / ColorOS 16 建议同时勾选 `com.google.android.gms`**：部分 ROM 会把 system_server 中的广播路径内联，GMS 侧 hook 是双保险

### 关于fcm

fcm是在Android中由google维护的一条介于google服务器与gms应用之间用于推送通知的长链接。  
一般的工作流程为应用服务器将消息发送到google服务器，google服务器将消息推送给gms应用，gms应用通过广播传递给应用，应用通过接收到的fcm消息决定是否发送通知和通知内容。  
其中gms通过fcm广播通知应用时，如果应用处于非运行状态，就会出现`Failed to broadcast to stopped app`，fcmfix主要就是解决这个问题。

### Android 15-17 说明

- 系统侧同时挂接 `BroadcastController.broadcastIntentLocked` / `broadcastIntentLockedTraced` / `broadcastIntentWithFeature` 以及 AMS 同名方法
- 对入口方法做 deoptimize，避免薄包装被内联后 hook 不触发
- `appOp` 参数按类型/名称自动定位，并兼容 `BroadcastOptions`（AOSP 15-17 叶子方法签名）
- 新增 GMS 进程侧 `GmsDeliveryFix`：在发送源头补 `FLAG_INCLUDE_STOPPED_PACKAGES`
- 远程 SharedPreferences 失败时可通过 ConfigProvider 读取本地 `config.json`

### 已知问题

- 非miui/hyperos/OxygenOS15/ColorOS15/16系统可能需要给予目标应用类似允许自启动的权限，以及电池选项设置为不优化
- 应用分身（双开）场景下部分 ROM 仍可能无法拉起
