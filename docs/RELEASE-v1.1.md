# v1.1 发布说明

本版本只更新 **CameraImmersionFix（LSPosed）**：

- **CameraImmersionFix（LSPosed）2.4**（versionCode 15）
- **CameraMorphoExpiryBypass（KernelSU / ReSukiSU）1.2** 本次无改动，不重复附带；已安装的用户无需重装或重启。

## 本次修复的问题

在相机设置中关闭「近焦切换控制」后，默认 35mm 主摄在近距离场景下仍会自动切换到 18mm 广角。

## 根因

- APK 侧链路是通的：`NormalParametersItem#getSatFallBackMode()` 在 `pref_camera_macro_ai_control_key = "off"` 时返回 5（宏关/暗光开），由 `ParametersSetter#setSatFallBackMode()` 写入 HAL 厂商 tag `com.zte.chi.sat.fallbackmode`。
- APK 自带的快速回落 `QuickFallbackController` 在 `off` 时把 AF 阈值置为 -1，自身不会切镜头。
- 真正的镜头切换由 CHI/HAL 的 SAT 回退执行。APK 内还有一个硬开关 `com.zte.chi.sat.fallbackclose`，但对应字段 `mSatFallBackClose` 在整个 APK 中从未被置 true（`setSatFallBackClose(Z)` 零调用者），HAL 一直收到 0。
- 同时，HAL 对 `fallbackmode = 4/5` 的处理无法从 APK 侧证明，存在被当作未知值、继续执行近摄回退的可能。

## v2.4 做了什么

仅在 `pref_camera_macro_ai_control_key == "off"` 时介入，其它取值（`on` / `on_far` / `on_closer`）保持原厂行为：

1. `NormalParametersItem#isSatFallBackClose()` 返回 `true`，随预览请求下发 `com.zte.chi.sat.fallbackclose=1`。
2. `NormalParametersItem#getSatFallBackMode()` 强制返回 4（暗光关）或 5（暗光开），避免被 cameraId / 支持类型判断降级为 0。

## 适配机型与系统

| 项目 | 要求 |
|---|---|
| 机型 | nubia Z60 Ultra 领先版（NX721J） |
| 系统 | HyperOS 4 移植包（底包 Xiaomi 14），Android 17 |
| Root | KernelSU Ultra / ReSukiSU |
| Hook 框架 | LSPosed（本机验证为 `dev.hyperglass.lsposed` 1.0.2） |
| 相机 | 移植包内的努比亚相机 `com.android.camera` |

其它机型、其它 ROM 未验证。

## 生效范围与副作用

- 生效范围：仅 `com.android.camera`，且仅当「近焦切换控制=关」时。
- 关闭后 35mm 主摄不再自动切 18mm，这是预期行为。
- 主摄最近对焦距离明显远于超广角：贴得太近会拉风箱、对不上焦或边缘发虚。拍微距需手动切 0.6x 或微距模式。
- 暗光切换：mode 4/5 理论上会保留暗光回落；若 HAL 把 `fallbackclose` 当作总开关，暗光切换也可能一并失效，需要继续观察。
- 其它设置不受影响，模块的 ImmersionMode、防抖抽屉、布局微调等既有功能保持不变。

## 安装 / 升级

1. 覆盖安装（与旧版同签名，可直接升级）：
   ```
   adb install -r CameraImmersionFix-LSPosed-v2.4.apk
   ```
2. 打开 LSPosed 管理器 → 模块 → 启用 `Camera Immersion Fix`，作用域勾选 `com.android.camera`。
3. **强制停止相机**后重新打开（覆盖安装后必须做，否则复用旧进程，hook 不生效）：
   ```
   adb shell am force-stop com.android.camera
   ```
4. KSU 模块无需重装，也无需为此重启手机。

## 验证要点

- 设置保持「近焦切换控制=关、暗光切换=开」，对准近物缓慢靠近，观察是否还跳 18mm。
- 可选日志：
  ```
  adb logcat | grep CameraImmersionFix
  ```
  期望出现 `force sat.fallbackclose=1 (macro off)` 和 `sat.fallbackmode X -> 5 (macro off)`。

## 回滚方式

| 目标 | 操作 |
|---|---|
| 回退本版 | LSPosed 中停用模块，或覆盖安装 `CameraImmersionFix-LSPosed-v2.3.apk`，强停相机后重开 |
| 完全移除 | 卸载 LSPosed 模块并强停相机/重启 |
| KSU 模块 | 本版未改动，无需处理 |

## 已知限制

- 修复依赖 HAL 识别 `com.zte.chi.sat.fallbackclose`；若某版 HAL 不识别，需要继续分析 vendor 侧 SAT 实现。
- 近摄时主摄不切广角会受最近对焦距离限制，属于功能取舍而非缺陷。
- 实机验证仅覆盖上述机型与 ROM。
- LSPosed 使用第三方 fork，兼容性以实机为准。

## 校验和（SHA-256）

| 文件 | 大小 | SHA-256 |
|---|---|---|
| `CameraImmersionFix-LSPosed-v2.4.apk` | 20,947 B | `1b160d8a4f1ba730ed49bebce69dc48d14593bd74ebfa320df234a562e5a3962` |

LSPosed 模块签名证书 SHA-256：`0310b3343076329d99d1f9f4de28fd7aed9cfc4dafeb4a37f5e839674757cdf3`
