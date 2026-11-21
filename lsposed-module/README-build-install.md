# Camera Immersion Fix（LSPosed 模块）

## 作用（v2.3）
仅对 `com.android.camera` 生效。当前包含以下 hook：

1. `cn.nubia.camera.nubiacameraconfig.Config#getIntValue(Map, Feature, String)` 的布尔字符串归一化（ImmersionMode 防崩必需）：
   - `"true"` → `1`，`"false"` → `0`，`Boolean true/false` → `1/0`
   - Map 中没有该 key 时回退读 `Feature.get(key)` 的默认值并做同样归一化
   - 其它非数字字符串 → 返回 Feature 默认值（拿不到则 `0`），避免 `NumberFormatException`
   - 合法数字字符串 → 原样交给原方法
2. `NubiaPopupDrawerButton#playShowAnim` 空值保护；动画启动后隐藏手型图标。
3. `NubiaPopupDrawerButton#playHideAnim` after：抽屉收起后恢复手型图标。
4. 顶栏防抖抽屉初始化（v1.6）：防抖按钮 `initialize` 后补 `initAnime(空列表, TopBarFragment.mTopBarParent)`。
5. 抽屉位置（v2.0）：水平贴按钮右侧 8dp；垂直按按钮中线居中；先 `setPosition` 让展开动画从图标向右展开。
6. `BottomBarFragment.initBottomPanelLayout` after：底部三键 `mBottomLayout.bottomMargin += 42dp`（竖屏）。
7. `ActivityBase.initMemberScrollerUI` after：模式栏 `mCameraSelect.bottomMargin += 31dp`（竖屏），并用 `updateMemberScrollerParentLayout` after 兜底。
8. `BottomBarFragment.updateHighSettingLayoutParam` after：右侧设置按钮组 `mHighSettingLayout.bottomMargin += 42dp`（竖屏）。
9. `VideoFragment.onVideoStartBegin` after：录像专用两套布局 `mBottomRecordingButtonLayout` / `mBottomPreRecordingButtonLayout` 的 `bottomMargin += 42dp`（竖屏）。
10. `VideoMemberFragment.startVideoRecord` after：展开状态下开始录像时收起抽屉。
11. `MemberFragment` / `TopBarFragment#dispatchTouchEvent` after：抽屉展开时点击抽屉外部收起（不吞事件，仅 ACTION_UP 判断）。

## v2.3 变更（v2.1 实机复验后的修正）
v2.1 实机复验结果：

| 项 | 结果 | 说明 |
|---|---|---|
| 抽屉左边缘 | 通过 | `drawer pos ... left=144`，紧贴闪光灯右侧 |
| 抽屉垂直中线 | 未完全通过 | 冷启动时抽屉是 GONE，`getHeight()=0`，5 次重试都 `(pending layout)`，最终 top=144（按钮顶边）而非中线值 132 |
| 外部点击收起 + 图标恢复 | 通过 | `drawer collapsed by outside tap` + `antishake button restored`，截图图标已恢复 |
| 专业模式按钮 | 通过 | `proModePicker bottomMargin=210px`，截图与快门平齐 |
| 人文街拍按钮 | 未通过 | 切模式时 `updateStreetEffectButtonLayout` 未再调用，无 `streetEffect` 日志，按钮未上移 |
| 模式切换后位置 | 通过（左边缘正确） | 切回视频后重新初始化，left 仍为 144；垂直同样受 pending 影响 |

v2.3 修正：

1. **展开后重新定位**：`playShowAnim` after 在隐藏图标后 `post` 一帧，重新调用 `positionDrawerNextToButton`，并重置 layout 重试计数。此时抽屉已 VISIBLE，能测到真实高度，`topMargin` 从 144 修正为按钮中线 − 高度/2（约 132），日志出现非 `(pending layout)` 的 `drawer pos`。
2. **人文街拍兜底 hook**：`StreetFragment.setHighSettingVisible(boolean, String)` 在每次切模式时必被调用（日志 `[StreetFragment]setHighSettingVisible`），after 里给 `mStreetEffectLayout` 补 42dp；保留 `updateStreetEffectButtonLayout` after。
3. **移除 v2.0 的高设置面板偏移**：删除 `hookHighSettingLayout`（`mHighSettingLayout.bottomMargin += 42dp`）。该容器在专业模式下就是 S/WB/ISO/EV/连拍 那一排参数行，加上 42dp 后被整体抬高；专业模式右下角的“专业视频”按钮已由 `ProFragment.initModePicker` 单独上移，不再依赖这个偏移。移除后参数行恢复原厂位置。
4. **抽屉右侧重叠避让**：抽屉宽度保持 231dp（三个档位固定宽度，收窄会裁掉第三档），展开时把顶栏中“屏幕 X ≥ 抽屉右边界”的叶子 View 临时设为 `INVISIBLE`（分辨率/帧率文字、镜头图标），收起时恢复。日志 `hide right top views=N drawerRight=...`、`restore right top views=N`。优先保证三个档位都能点到。

## v2.1 变更（v2.0 实机验证后的修正）
v2.0 安装实测结果：

| 项 | 结果 | 处理 |
|---|---|---|
| 录像三图标 | 通过：`recordingLayout/preRecordingLayout bottomMargin=210px`，录像中三图标保持高度 | 保留 |
| 抽屉展开 | 通过：图标隐藏、菜单从图标处展开、`drawerHeight=90 top=135`（按钮中线 180，90/2=45） | 保留 |
| 点击外部收起 | 抽屉收起了，但图标没恢复（`isExpanding` 在 after 时仍为 true） | v2.1 改为无条件恢复图标 |
| 人文街拍右侧按钮 | 未生效：该按钮在 `StreetFragment.mStreetEffectLayout`（`R.id.street_effect_layout`），不在 `mHighSettingLayout` | v2.1 hook `updateStreetEffectButtonLayout` after，加 42dp |
| 专业模式专业视频按钮 | 未生效：该按钮在 `ProFragment` 的 `mode_picker_button_layout`，独立于 BottomBar 布局 | v2.1 hook `initModePicker` after，加 42dp |

其他修正：`applyDrawerPosition` 等待 layout 时最多重试 5 次，避免 v2.0 连续注册 8 次 OnGlobalLayoutListener。

v2.1 追加（用户第二轮反馈，合并在同一版交付）：

- 抽屉左边界改为**手型按钮原来的左边界**（`leftMargin = 按钮窗口 X − 宿主窗口 X`），不再用“按钮右边缘 + 8dp”。展开后菜单正好接替手型图标的位置，左边缘紧贴闪光灯图标右侧。
- 宽度不足时向右延伸；若 `left + 抽屉宽度 > 宿主宽度`，则左移夹取，日志标记 `LEFT_CLAMPED hostWidth=...`。
- 垂直仍按按钮中线对齐（`topMargin = 按钮中线 − 抽屉高度/2`）。
- 取消 `setPosition` 对展开动画起点的改动，恢复原厂从左侧滑入的动画，避免第二次出现位置漂移。
- 模式来回切换后若 `mDrawerParent` 仍 attach，不再直接 return，而是重新计算一次抽屉位置并重置 layout 重试计数，日志 `drawer already initialized, repositioned`。
- 点击外部收起：`dispatchTouchEvent` → `playHideAnim` → after 无条件恢复按钮可见，日志 `antishake button restored`；不再依赖切换模式。

## v2.0 变更（四项界面修复）

| 问题 | 定位 | 实现 | 风险/待验证 |
|---|---|---|---|
| 人文街拍右侧设置按钮没上移 | 该按钮组在 `BottomBarFragment.mHighSettingLayout`（`R.id.high_setting_layout`），v1.9 只改 `mBottomLayout` | `updateHighSettingLayoutParam` after 给 `mHighSettingLayout.bottomMargin += 42dp` | 需设备确认按钮确实在该容器内；若没有，按 id 单独偏移 |
| 专业模式专业视频按钮没上移 | 同上，专业模式共用 `mHighSettingLayout` | 同上 | 同上 |
| 视频开拍后三个图标掉回底部 | 录制时切到 `VideoFragment.mBottomRecordingButtonLayout` / `mBottomPreRecordingButtonLayout`，不经过 `initBottomPanelLayout` | `onVideoStartBegin` after 给两套布局 `bottomMargin += 42dp` | 两套布局若没有 MarginLayoutParams 会跳过并只打日志 |
| 抽屉与图标未完全对齐 | `getLocationOnScreen` 顶边对齐受抽屉阴影/内边距影响 | 改为按钮垂直中线对齐：`topMargin = 按钮中线 − 抽屉高度/2`；抽屉首次 post 高度为 0 时先按顶边放，`OnGlobalLayout` 后再重算 | 日志会先打 `(pending layout)`，再打最终 top |
| 点击外部收起 / 隐藏图标 | 抽屉展开时 `playShowAnim` 后把按钮设 `INVISIBLE`；收起 `playHideAnim` 后恢复 `VISIBLE` | `MemberFragment` 与 `TopBarFragment.dispatchTouchEvent` after，ACTION_UP 时判断触点是否在抽屉 rect 外，外部则 `playHideAnim` | 仅覆盖经过这两个 fragment 的触摸；系统手势区点击不保证触发 |

幂等处理：`liftBottomMargin` 用 `APPLIED_MARGINS` 记录每个 View 上一次设置后的 margin；系统重设后继续叠加，重复回调则跳过，不会累积。

## v1.9 变更（模式栏实际未上移的修复）
- v1.8 只 hook 了 `MemberScrollerManager.updateMemberScrollerParentLayout`，实测启动和切换模式时该方法没有被调用（日志无 `modeBar`，`camera_select_button` 仍是 `[0,1946][1116,2066]`）。
- v1.9 增加 `ActivityBase.initMemberScrollerUI` 的 after hook：从 Activity `mMemberScrollerManager` 取 `mCameraSelect`，直接增加 `bottomMargin`。
- 实测 v1.9：`modeBar-init bottomMargin=507px (lift 31dp)`，`camera_select_button` 变为 `[0,1853][1116,1973]`，与用户标注的红色区域 `1854..1926` 对齐。
- 同时实测底部三键 `shutter_button [435,2024][681,2270]`（上移 42dp），相册/前后摄同步上移，与 `bottom_more_setting_arrow [522,2288][594,2360]` 不再重叠。
- 抽屉实测：`drawer pos button=144,144 host=0,0 buttonSize=72x72 drawerHeight=0 left=240 top=144`；展开截图确认抽屉顶边与按钮顶边一致、左边缘在按钮右侧，未被顶栏裁剪（`setClipChildren(false)` 生效）。
- 录像实测：`hide onAnimationStart` → `collapsed antishake drawer on record start` → `hide onAnimationEnd`。

## v1.8 变更（抽屉与按钮同高）
- `initializeDrawerView` 只写 `topMargin = mPositionY + 4dp`，所以抽屉贴顶栏顶部；v1.7 只改了水平位置。
- v1.8 在 `initAnime` 后通过 `drawer.post()` 等抽屉完成测量，再重设 `RelativeLayout.LayoutParams`：
  - `leftMargin = 按钮窗口 X − 顶栏窗口 X + 按钮宽度 + 8dp`
  - `topMargin = 按钮窗口 Y − 顶栏窗口 Y`（顶边对齐，直接用窗口坐标差，不重复减偏移）
  - 可用高度按宿主根视图计算：`root.height − (顶栏窗口 Y − 根视图窗口 Y)`；若 `topMargin + 抽屉高度 > 可用高度`，则 `topMargin = 可用高度 − 抽屉高度`，日志标记 `CLAMPED`
  - 对顶栏 `setClipChildren(false)`，否则抽屉向下超出 75dp 高的顶栏时会被裁掉一截
- 日志新增：`drawer pos button=x,y host=x,y buttonSize=WxH drawerHeight=H left=L top=T [CLAMPED available=A]`
- 横屏不做布局修改。

## v1.7 变更（界面微调）
需求来自用户对手形按钮/底部栏的标注图（设备分辨率 1116x2480，density 3.0）：

| 项 | 依据 | 实现 | 数值 |
|---|---|---|---|
| 抽屉位置 | 用户要求“放在按钮右侧”；`initializeDrawerView` 只设 `topMargin`，水平位置来自 XML 固定 `marginStart` | `initAnime` 后读取按钮与宿主的屏幕坐标，重设 `mDrawRoot` 的 `leftMargin`；v1.8 起同时对齐按钮顶边 | 按钮右边缘 + 8dp，顶边对齐 |
| 录制时收起抽屉 | 点击拍摄键后抽屉一直存在 | `VideoMemberFragment.startVideoRecord()` after 调 `playHideAnim()` | 条件：`isExpanding==true` 且 `mHideAnimSet!=null` |
| 底部三键上移 | 标注图绿圈中心 y≈2146，当前拍摄键中心 y≈2273 → 127px ≈ 42dp | `BottomBarFragment.initBottomPanelLayout` after，`mBottomLayout.bottomMargin += 42dp` | 42dp |
| 模式栏上移 | 标注图红色区域 y=1854..1926，当前 `camera_select_button` y=1946..2066 → 上移 92px ≈ 31dp | `MemberScrollerManager.updateMemberScrollerParentLayout` after，`mCameraSelect` 的 `FrameLayout.LayoutParams.bottomMargin += 31dp` | 31dp |

实现要点：

- 三处布局修改都放在原方法 **after**，因为这两个方法每次都会重新设置 layout 参数；在它们之后叠加偏移，既不会累积，也不会被后续重设覆盖。
- 所有偏移用 `dp * density` 换算，不写死像素；只对竖屏生效，横屏保持原厂布局。
- 录制收起前检查 `isExpanding` 和 `mHideAnimSet`，避免在未初始化/动画进行中引入新的 NPE。
- 每条 hook 独立 try/catch，失败只写日志。

## v1.6 变更（修顶栏防抖抽屉）
移植包的 `TopBarFragment#initNubiaPopupDrawerButton(NubiaPopupDrawerButton, boolean)` 只做 `updateFoldType` 和设置三个监听器，**没有**像 `BottomBarFragment#initNubiaPopupDrawerButton(button, List)` 那样调用 `initAnime(List, RelativeLayout)`。后果：

- `mDrawerParent`、`mAnimeItems`、`mDrawerLayout`、`mShowAnimSet` 全为 `null`；
- 点击防抖图标时 `playShowAnim()` 在 `mShowAnimSet.start()` 处 NPE（v1.5 已短路，所以不闪退，但界面没有任何反应）；
- 抽屉里的 `drawer_tab_select` 从未 inflate，真正的防抖选项点不到。

v1.6 的处理：

- hook `NubiaPopupDrawerButton#initialize(IconListPreference, IconListPreference, TopBarFragment$TopBarHelper, MemberInfo)` 的 after；
- 只处理 `mPreference.getKey() == "pref_antishake_key"` 的按钮；
- 用 `View.post` 延迟到按钮挂载后的下一帧，再调用 `initAnime(new ArrayList(), host)`；
- `host` 优先取 `mTopBarHelper.getTopBarFragment().mTopBarParent`（`R.id.topbar` 的 `RelativeLayout`，与 `NubiaPopupSwitcherButton.initAnim` 的宿主一致）；取不到时沿父链找第一个 `RelativeLayout`；
- 同时 hook `TopBarFragment#initNubiaPopupDrawerButton` 的 after 作为兜底（内部幂等，`mDrawerParent != null` 直接跳过）；
- 所有 hook 独立 try/catch，失败只写日志，不影响相机主流程。

修复后的调用链（字节码已确认）：

```
initialize(...)
  -> mPreference = pref_antishake_key
  -> (v1.6) initAnime(emptyList, mTopBarParent)
       -> mDrawerParent / mAnimeItems 赋值
       -> initializeDrawerView()
            -> inflate popup_drawer_layout
            -> 绑定 drawer_tab_select / topbarTipText
            -> initializeTabSelectView()   // setTabData(mPreference) + 监听
            -> mDrawerParent.addView(mDrawerLayout)
            -> initializeDrawerAnime()     // 生成 mShowAnimSet / mHideAnimSet
点击图标 -> playShowAnim -> mShowAnimSet.start()
       -> onAnimationStart: mDrawerLayout 可见 + tab 可见
点击 tab -> NubiaPopupDrawerButton$3.onTabSelected
       -> mPreference.setValueIndex(i)   // 写入 pref_antishake_key
       -> SwitcherListener.onSwitch()    // VideoMemberFragment 切换防抖
```

## v1.5 变更（保留）
`NubiaPopupDrawerButton#playShowAnim` 的 before hook：`mShowAnimSet == null` 时短路返回，避免 NPE 闪退。v1.6 修复抽屉后正常路径不会再进入短路；保留它是为了防止初始化因设备时序失败时再次闪退。

## v1.4 变更
- **移除** v1.3 的四个防抖相关 hook：`getBoolValue`、`getBoolean`、`hasSuperAntiShake`、`hasVideoAntiShake`。
- **不再**强制 `SuperAntiShake=false` / `VideoAntiShake=false`，防抖/EIS 恢复由系统与 HAL 决定。
- 版本：versionCode 5 / versionName 1.4；包名、作用域、签名流程不变。

### 为什么移除
v1.3 关闭这两个开关，是为了尝试让 HAL 跳过会 SIGILL 的 Morpho EIS 节点；实测该做法**无效**（HAL 仍创建并初始化 Morpho 节点），却会真实关闭防抖、影响成像。Morpho 过期问题已由独立的 kernel 模块（bind mount 补丁库）解决，因此 v1.4 恢复防抖。

## v1.5 变更（历史，保留说明）
新增一个 UI 防御 hook：

- 目标：`cn.nubia.camera.street.NubiaPopupDrawerButton#playShowAnim()V`（protected、无参、单一重载）。
- 当前移植包里该按钮的 `mShowAnimSet` 可能为 `null`。点击防抖图标时，`playShowAnim` 会执行 `mShowAnimSet.start()`，抛 `NullPointerException`，相机闪退（`NubiaPopupDrawerButton.java:600`）。
- 处理：before hook 读取 `mShowAnimSet`；为 `null` 时直接 `param.setResult(null)` 短路，跳过这段动画，但不影响调用方 `zteOnClick` 继续执行。
- 不吞目标方法异常：正常情况不调用原方法，因此没有 NPE 可吞；字段读取失败时按“动画不可用”短路并记日志。
- 反编译已确认 `playShowAnim` 只做状态判断、日志、`mAnimeItems` bringToFront、`cancelAnim()`、`mShowAnimSet.start()`，没有防抖/偏好写入；跳过它不会直接关闭防抖切换逻辑。
- 版本：versionCode 6 / versionName 1.5。

## 构建
要求：JDK 11+、Android SDK build-tools 35.0.0、platforms/android-35。

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

产物：
`...\camera-launch-diagnosis\outputs\CameraImmersionFix-LSPosed-v2.3.apk`

## 安装与启用（需用户手动操作）
1. 安装：`adb install -r CameraImmersionFix-LSPosed-v2.3.apk`
2. 打开 LSPosed 管理器 → 模块 → 启用 “Camera Immersion Fix”。
3. 作用域勾选 `com.android.camera`（Manifest 已声明 xposedscope，若未自动带入则手动勾选）。
4. **强制停止相机**（或重启手机），再打开相机。

> 重要：覆盖安装/更新模块后，必须先强制停止相机，确保下一次启动是全新进程；否则会复用旧进程，旧进程不会加载新模块，hook 不生效。

## 验证
- `adb logcat | findstr CameraImmersionFix`
  - 应看到 `hooked ...Config#getIntValue`
  - 应看到 `hooked ...NubiaPopupDrawerButton#playShowAnim`
  - 应看到 `hooked ...NubiaPopupDrawerButton#initialize`
  - 应看到 `hooked ...TopBarFragment#initNubiaPopupDrawerButton`
  - 应看到 `hooked ...BottomBarFragment#initBottomPanelLayout`
  - 应看到 `hooked ...MemberScrollerManager#updateMemberScrollerParentLayout`
  - 应看到 `hooked ...VideoMemberFragment#startVideoRecord`
  - 应看到 `ImmersionMode=true (feature default) -> 1`
  - 进入视频模式后应看到 `antishake drawer init scheduled`、`drawer initialized, host=android.widget.RelativeLayout`
  - 应看到 `drawer pos button=... host=... buttonSize=... drawerHeight=... left=... top=...`；若出现 `CLAMPED` 表示抽屉被屏幕可用高度夹取
  - `top` 应接近“按钮顶边 − 顶栏顶边”，即抽屉顶边与按钮顶边对齐
  - 应看到 `bottomBar bottomMargin=...px (lift 42dp)` 和 `modeBar bottomMargin=...px (lift 31dp)`
  - 点击防抖图标：**不应**再出现 `skip ...playShowAnim (mShowAnimSet=null)`；应能看到抽屉展开（NubiaPopupDrawerButton 的 `show onAnimationStart`）
  - 抽屉应位于防抖按钮右侧，而不是覆盖整条顶部栏
  - 抽屉展开时点拍摄键开始录像，应看到 `collapsed antishake drawer on record start`，抽屉自动收起
  - 底部快门/相册/前后摄整体上移约 42dp，不再与小三角重合；模式栏上移约 31dp
  - 抽屉出现后，点其中的选项应写入偏好：`adb shell run-as com.android.camera cat shared_prefs/com.android.camera_preferences.xml` 中 `pref_antishake_key` 随选择变化，录像画面防抖生效
  - **不应再出现** `force VideoAntiShake=false` 或 `force SuperAntiShake=false`
- Morpho 仍应正常初始化（不再 SIGILL），相机进入预览。
- 录像/防抖：确认“视频防抖/超级防抖”开关可正常使用，录像画面稳定；与 v1.3 对比应恢复防抖。
- `adb logcat -b crash -d` 无新的 `NumberFormatException`，无 `libmorpho_video_stabilizer.so` SIGILL。

## 回滚
- 回滚 v2.0 的四项界面修复：覆盖安装 v1.9。
- 回滚抽屉垂直对齐（保留 v1.7 其它微调）：覆盖安装 v1.7。
- 回滚全部界面微调：覆盖安装 v1.6。
- 回滚到“不闪退但抽屉不出现”：覆盖安装 v1.5。
- 只修 ImmersionMode、不关防抖：覆盖安装 v1.2。
- 若确实需要回到 v1.3 的“强制关防抖”行为：覆盖安装 v1.3（不推荐，会关闭防抖）。
- 停用模块：LSPosed 中停用，或 `adb uninstall com.camera.immersionfix`，然后强制停止相机/重启。

## 关闭日志
把 `src\com\camera\immersionfix\CameraImmersionHook.java` 里的 `DEBUG` 改为 `false`，重新构建。

## 已知风险
- LSPosed 为 `dev.hyperglass.lsposed` 1.0.2（第三方 fork），需确认其 legacy Xposed API 在 Android 17/SDK 37 上可用。
- 只处理 `getIntValue` 的非数字值；若后续还有其它类型漂移，可能仍需扩展。
- Morpho 过期问题由独立的 kernel 模块解决；本 APK 不包含、也不修改 vendor 库。
- 抽屉宿主固定为 `TopBarFragment.mTopBarParent`；若相机切模式/旋转重建 TopBar 视图，按钮上的 `mDrawerParent` 可能仍指向旧容器。当前版本不自动重建，若发现模式切换后抽屉点不出来，需要再补 `onCreateView/onResume` 的重新绑定。
- 抽屉初始 `mPositionY` 为 0（移植包未调用 `setPosition`），v1.8 改为按按钮窗口坐标直接设置 `topMargin`；抽屉向下超出顶栏时依赖 `setClipChildren(false)` 才完整显示。
- v1.8 关闭了顶栏的 `clipChildren`，理论上会让顶栏子 view 的越界部分可见；顶栏子 view 本身不越界，风险低。
- v1.7 的布局偏移只在竖屏生效；横屏保持原厂位置。若后续需要横屏调整，要分别读取横屏的控件 bounds 后再加偏移。
- 底部 `mBottomLayout` 的 margin 修改影响拍照/视频所有模式；模式栏偏移在每次 `updateMemberScrollerParentLayout` 后重新叠加，不会累积。
- 42dp / 31dp 是按 1116x2480、density 3.0 的标注图换算；换机型或改分辨率后需要按同样的像素测量重新换算。

## 故障排查
- LSPosed 模块列表看不到：确认 APK 已安装、LSPosed 已激活；查看 `adb logcat | findstr LSPosed`。
- 日志里没有 `CameraImmersionFix`：确认作用域已勾选 `com.android.camera`，并强制停止相机后重开。
- 相机仍崩溃：`adb logcat -b crash -d` 看新的堆栈；若仍是 `Config.getIntValue`，检查模块是否启用、DEBUG 是否输出 `hooked ...`。
- 想确认是否命中 ImmersionMode：日志应出现 `ImmersionMode=true (feature default) -> 1`。
- 若又出现 `force VideoAntiShake=false`：说明装的仍是 v1.3，覆盖安装 v1.7 后强制停止相机。
- 点击防抖图标仍闪退：确认日志出现 `hooked ...NubiaPopupDrawerButton#playShowAnim`；若出现 `skip ... (mShowAnimSet=null)` 说明初始化没成功，继续看前面是否有 `drawer host not found` / `initAnime hook error`。
- 日志有 `hooked ...initialize` 但没有 `antishake drawer init scheduled`：说明命中的不是防抖按钮，检查 `mPreference.getKey()` 是否为 `pref_antishake_key`。
- 日志出现 `drawer host not found`：说明 `mTopBarHelper.getTopBarFragment()` 或 `mTopBarParent` 尚未就绪，且按钮父链没有 RelativeLayout；抓完整启动日志再定位时机。

## 与 KSU 模块模板的取舍
本项目交付物是 **LSPosed 模块 APK**，不是 KSU 模块 zip，因此模板中的 `module.prop`、`customize.sh`、`service.sh`、LF/0755、Python zipfile 打包、APK 放 `files/` 等规范不适用。
已遵循的相关规范：
- 中文路径：构建前把工程复制到 `%TEMP%\camera_immersion_fix_build`（ASCII），在暂存目录用相对路径调用 aapt2/zipalign/apksigner，产物再拷回 outputs。
- 版本化产物命名：`CameraImmersionFix-LSPosed-v2.3.apk`。
- 不使用 PowerShell `Compress-Archive`；dex 写入用 .NET ZipFile，签名用 apksigner。
- README 包含功能、构建、安装、验证、回滚、故障排查。





