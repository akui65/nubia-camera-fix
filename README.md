# nubia-camera-fix

让 **努比亚原相机** 在 **nubia Z60 Ultra 领先版（NX721J）+ HyperOS 4（Android 17）移植包** 上正常启动、录像、防抖、正常显示界面。

包含两个互相独立的模块：

| 模块 | 类型 | 作用 |
|---|---|---|
| `CameraMorphoExpiryBypass` | KernelSU / ReSukiSU 模块 | 替换 `/vendor/lib64/libmorpho_video_stabilizer.so`，绕过 Morpho 防抖库的 GMT 过期自毁校验（SIGILL） |
| `CameraImmersionFix` | LSPosed 模块 | 修复相机启动崩溃（ImmersionMode）、防抖抽屉初始化、底部布局微调；关闭“近焦切换控制”后阻止 HAL 近距离切广角 |

作者：**Hell'C**

## 背景

移植包把 Z80 Ultra 的相机 APK 与 Z60 Ultra 的配置混用，产生三类问题：

1. **启动即崩**：`Config.getImmersionMode` 调用 `getIntValue`，对 `"true"` 执行 `Integer.parseInt`，抛 `NumberFormatException`。
2. **录像 SIGILL**：Morpho EIS 库内置 GMT 截止日期，移植包中已过期，校验失败后跳到非法地址。
3. **防抖不可用**：`TopBarFragment` 漏调 `initAnime`，顶栏抽屉从未创建；点击手型图标触发 `mShowAnimSet.start()` NPE。
4. **近焦误切换**：设置里关闭“近焦切换控制”后，35mm 主摄近距离仍会切到 18mm 广角。APK 已下发 `com.zte.chi.sat.fallbackmode=5`，但硬开关 `com.zte.chi.sat.fallbackclose` 从未被置位；v2.4 在宏关闭时补发该标志，并强制 fallbackmode=4/5。

完整根因、证据与实现原理见 [docs/修复全流程.md](docs/修复全流程.md)。

## 目录结构

```
nubia-camera-fix/
├── docs/
│   └── 修复全流程.md
├── lsposed-module/
│   ├── AndroidManifest.xml
│   ├── build.ps1
│   ├── src/com/camera/immersionfix/CameraImmersionHook.java
│   ├── stubs/de/robv/android/xposed/
│   └── res/values/arrays.xml
├── ksu-module/
│   ├── module.prop
│   ├── post-fs-data.sh
│   ├── uninstall.sh
│   ├── apply_patch.py
│   ├── build_module.py
│   └── system/vendor/lib64/libmorpho_video_stabilizer.so
└── release/
    ├── CameraMorphoExpiryBypass-v1.2.zip
    └── CameraImmersionFix-LSPosed-v2.4.apk
```

## 安装（顺序不能反）

1. **先装 KSU 模块**：在 KernelSU / ReSukiSU 管理器中安装 `release/CameraMorphoExpiryBypass-v1.2.zip`，然后 **重启手机**。
2. **再装 LSPosed 模块**：`adb install -r release/CameraImmersionFix-LSPosed-v2.4.apk`，在 LSPosed 中启用并将作用域勾选 `com.android.camera`。
3. **强制停止相机**后重新打开：
   ```
   adb shell am force-stop com.android.camera
   ```

覆盖安装 LSPosed 模块后也必须强停相机，否则会复用旧进程导致 hook 不生效。

## 验证

```bash
# Morpho 补丁是否生效
adb shell su -c 'cat /data/local/tmp/morpho_mount.log'
adb shell su -c 'sha256sum /vendor/lib64/libmorpho_video_stabilizer.so'
# 期望 2fc1ad46d96323e8c04553745069ae251dff9259abb8b4d0c6f877d265f9c554

# Java 修复是否生效
adb logcat | grep CameraImmersionFix
```

详细日志关键字、坐标、偏好项见文档第 7 节。

## 构建

LSPosed 模块（Windows，需要 JDK 11+、Android SDK build-tools 35.0.0）：

```powershell
cd lsposed-module
powershell -ExecutionPolicy Bypass -File build.ps1
```

KSU 模块（需要先用 `apply_patch.py` 从原始库生成补丁库）：

```bash
python ksu-module/build_module.py
```

## 回滚

- 停用 LSPosed 模块并强停相机 → 恢复启动崩溃与界面原状；
- 删除 KernelSU 中的 `morpho_expiry_bypass` 模块并重启 → 恢复原厂 Morpho 库（防抖会再次 SIGILL）。

## 风险与已知限制

- Morpho 补丁绑定固定偏移 `0x408380` 与原始库 SHA-256；ROM/相机升级后必须重新定位。
- 布局偏移（42dp / 31dp）基于 1116×2480、density 3.0 校准，换机型需重测。
- 界面微调仅竖屏生效，横屏保持原厂。
- LSPosed 使用第三方 fork `dev.hyperglass.lsposed`，兼容性以实机为准。

## 免责声明

本项目仅用于个人设备修复与技术研究。修改 vendor 库与 hook 系统相机存在风险，请自行备份并承担后果。
