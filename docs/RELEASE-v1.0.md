# v1.0 发布说明

本版本同时包含两个模块：

- **CameraImmersionFix（LSPosed）2.3**（versionCode 14）
- **CameraMorphoExpiryBypass（KernelSU / ReSukiSU）1.2**（versionCode 3）

## 适配机型与系统

| 项目 | 要求 |
|---|---|
| 机型 | nubia Z60 Ultra 领先版（NX721J） |
| 系统 | HyperOS 4 移植包（底包 Xiaomi 14），Android 17 |
| Root | KernelSU Ultra / ReSukiSU |
| Hook 框架 | LSPosed（本机验证为 `dev.hyperglass.lsposed` 1.0.2） |
| 相机 | 移植包内的努比亚相机 `com.android.camera` |

其它机型、其它 ROM 未验证，布局偏移与库偏移可能需要重新校准。

## 两个模块分别解决什么

### 1. CameraMorphoExpiryBypass（先装，需重启）

- **问题**：录像/防抖时进程 SIGILL，崩溃在 `libmorpho_video_stabilizer.so`。
- **原因**：Morpho EIS 库内置 GMT 过期校验，过期后跳到非法地址（`.rodata` 的 `ff ff ff ff`）。
- **做法**：把该库的校验结果寄存器强制为“有效”（文件偏移 `0x408380`：`ldr w20,[sp,#0x580]` → `mov w20,#1`），在 `post-fs-data` 阶段通过 `mount --bind` 只替换这一个 vendor 文件，并 `chcon u:object_r:vendor_file:s0`。
- **效果**：防抖恢复正常，不再 SIGILL。

### 2. CameraImmersionFix（后装，LSPosed 启用）

- **启动崩溃**：`Config.getIntValue` 对 `"true"` 执行 `Integer.parseInt` → `NumberFormatException`。模块在 `Config#getIntValue` 中把 `true/false` 归一化为 `1/0`，并处理 Feature 默认值回退。
- **防抖不可用/闪退**：顶栏抽屉未初始化（`initAnime` 漏调用），点击 `playShowAnim` 时 `mShowAnimSet == null` 触发 NPE。模块补抽屉初始化、空值保护、收纳与位置对齐。
- **界面微调**（竖屏）：底部三键上移 42dp、模式栏上移 31dp、人文街拍与专业模式右侧按钮与快门平齐、录像中三图标保持高度。

## 安装顺序

1. 在 KernelSU / ReSukiSU 中安装 `CameraMorphoExpiryBypass-v1.2.zip`。
2. **重启手机**（`post-fs-data.sh` 只在开机阶段执行）。
3. 安装 `CameraImmersionFix-LSPosed-v2.3.apk`，在 LSPosed 中启用，作用域勾选 `com.android.camera`。
4. 强制停止相机后重新打开：
   ```
   adb shell am force-stop com.android.camera
   ```

## 验证要点

```bash
# Morpho 补丁
adb shell su -c 'cat /data/local/tmp/morpho_mount.log'
adb shell su -c 'sha256sum /vendor/lib64/libmorpho_video_stabilizer.so'
# 期望 2fc1ad46d96323e8c04553745069ae251dff9259abb8b4d0c6f877d265f9c554

# Java 修复
adb logcat | grep CameraImmersionFix
```

日志关键字：

- `hooked ...Config#getIntValue`
- `ImmersionMode=true (feature default) -> 1`
- `drawer initialized, host=android.widget.RelativeLayout`
- `drawer pos ... left=144 top=135`
- `hide right top views=4 drawerRight=720` / `restore right top views=4`
- `collapsed antishake drawer on record start`
- `recordingLayout bottomMargin=210px (lift 42dp)`
- `streetEffect-visible bottomMargin=210px (lift 42dp)`
- `proModePicker bottomMargin=210px (lift 42dp)`

界面坐标（1116×2480）：

- `shutter_button [435,2024][681,2270]`
- `camera_select_button [0,1853][1116,1973]`

偏好：切换防抖档位后 `pref_antishake_key` 在 `on` / `v2` / `off` 之间变化。

## 回滚方式

| 目标 | 操作 |
|---|---|
| 停用 Java 修复 | LSPosed 中停用模块（或卸载），强停相机/重启 |
| 恢复 Morpho 原库 | KernelSU 中删除 `morpho_expiry_bypass` 模块，重启 |
| 全部回滚 | 卸载两个模块并重启，重新安装相机备份 APK |

## 已知限制

- 布局偏移基于 1116×2480、density 3.0 校准，其它分辨率需重测。
- 界面微调仅竖屏生效，横屏保持原厂布局。
- 抽屉避让是展开时临时隐藏右侧组件，不是重新排布。
- Morpho 补丁绑定固定偏移与原始库 SHA，ROM/相机升级后需重新定位。
- 录像中三图标与未开拍时仍有约 12dp 固有差异。
- LSPosed 使用第三方 fork，兼容性以实机为准。

## 校验和（SHA-256）

| 文件 | 大小 | SHA-256 |
|---|---|---|
| `CameraImmersionFix-LSPosed-v2.3.apk` | 20,947 B | `7b27a2dafe69ad8fcbed7e788e89a0539427381335fedc69a4ba8cb4bc59b009` |
| `CameraMorphoExpiryBypass-v1.2.zip` | 5,582,191 B | `5413876173127201d56d97ca62bc61cf061cae38f258b425a917b620144c7d4b` |

LSPosed 模块签名证书 SHA-256：`0310b3343076329d99d1f9f4de28fd7aed9cfc4dafeb4a37f5e839674757cdf3`
