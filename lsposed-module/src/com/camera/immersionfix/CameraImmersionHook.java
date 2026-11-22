package com.camera.immersionfix;

import android.content.res.Configuration;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 仅作用于 com.android.camera。
 *
 * hook cn.nubia.camera.nubiacameraconfig.Config#getIntValue(Map, Feature, String)：
 *   - 配置值为 "true"  -> 返回 1
 *   - 配置值为 "false" -> 返回 0
 *   - 配置值为 Boolean -> 返回 1/0
 *   - Map 中没有该 key 时，继续读 Feature.get(key) 的默认值并做同样归一化
 *     （Z60 Ultra 的 ImmersionMode="true" 就来自 Feature 默认值/机型覆盖，而不是 HAL Map）
 *   - 其它非数字字符串 -> 返回 Feature 默认值（拿不到则 0），避免 NumberFormatException
 *   - 合法数字字符串   -> 原样交给原方法解析
 *
 * v1.4：只保留 ImmersionMode 所需的 getIntValue 归一化。
 * v1.3 曾强制 SuperAntiShake / VideoAntiShake 为 false，试图让 HAL 跳过 Morpho EIS 节点；
 * 该做法已被证明无效（HAL 仍创建 Morpho 节点），且会关闭防抖、影响成像，故全部移除。
 *
 * v1.5：新增 NubiaPopupDrawerButton#playShowAnim 的空值防御。
 * 该按钮在当前移植包中 mShowAnimSet 可能为 null，点击时 playShowAnim 会
 * 调用 AnimatorSet.start() 触发 NPE；在 before hook 中检测到 null 时直接短路返回，
 * 保留调用方 zteOnClick 的后续逻辑，不吞目标方法抛出的其它异常。
 *
 * v1.6：修复顶栏防抖抽屉未初始化的问题。
 * 移植包的 TopBarFragment#initNubiaPopupDrawerButton 只设置监听器，没有像
 * BottomBarFragment 那样调用 initAnime，导致 NubiaPopupDrawerButton 的
 * mDrawerParent / mAnimeItems / mShowAnimSet / mDrawerLayout 全部为 null，
 * 点击只走到 playShowAnim 的 NPE（v1.5 短路）而抽屉从未被 inflate。
 * 本版在防抖按钮 initialize 之后补一次 initAnime(空列表, 顶栏 RelativeLayout)：
 *   - 宿主取 TopBarFragment.mTopBarParent（R.id.topbar，与 NubiaPopupSwitcherButton 相同）
 *   - initializeDrawerView 会 inflate popup_drawer_layout、绑定 drawer_tab_select、
 *     建立 mShowAnimSet/mHideAnimSet
 *   - 抽屉里的 tab 点击走 NubiaPopupDrawerButton$3.onTabSelected，
 *     即 mPreference.setValueIndex() + SwitcherListener.onSwitch()，
 *     会写入 pref_antishake_key 并触发 VideoMemberFragment 的防抖切换
 * v1.5 的 playShowAnim 空值保护保留：若初始化因任何原因未执行，
 * 点击仍只是无动画，不会闪退。
 *
 * v2.4：修复“近焦切换控制=关 后，35mm 主摄近距离仍自动切 18mm 广角”。
 * 反编译确认 APK 侧链路是通的：
 *   - NormalParametersItem#getSatFallBackMode 在 macro=off 时返回 4/5，
 *     由 ParametersSetter#setSatFallBackMode 写入 com.zte.chi.sat.fallbackmode；
 *   - QuickFallbackController 在 macro=off 时把 AF 阈值置 -1，APP 自身不切镜头；
 *   - 镜头切换由 CHI/HAL 的 SAT 回退完成。
 * 但 APK 内还有一个从未被置 true 的硬开关 com.zte.chi.sat.fallbackclose
 * （NormalParametersItem#isSatFallBackClose 永远返回字段默认 false），
 * 且 HAL 对 fallbackmode=4/5 的处理无法从 APK 侧证明。
 * 因此本版只在 macro=off 时做两件事：
 *   1. isSatFallBackClose() 返回 true -> 下发 com.zte.chi.sat.fallbackclose=1；
 *   2. getSatFallBackMode() 强制返回 4（暗光关）或 5（暗光开），
 *      避免因 cameraId/支持类型判断走了 0 分支而丢掉“微距关”的语义。
 * 其它取值（on / on_far / on_closer）完全不介入，保持原厂行为。
 *
 * 目标类/方法不存在时安静退出（仅 DEBUG 时打日志），不让相机进程因模块异常崩溃。
 */
public class CameraImmersionHook implements IXposedHookLoadPackage {

    private static final String TAG = "CameraImmersionFix";
    public  static final boolean DEBUG = true; // 验证时可看日志；稳定后改为 false 重新构建

    private static final String TARGET_PACKAGE = "com.android.camera";
    private static final String CONFIG_CLASS = "cn.nubia.camera.nubiacameraconfig.Config";
    private static final String FEATURE_CLASS = "cn.nubia.camera.nubiacameraconfig.gen.Feature";
    private static final String METHOD_NAME = "getIntValue";

    private static final String POPUP_CLASS = "cn.nubia.camera.street.NubiaPopupDrawerButton";
    private static final String POPUP_SHOW_METHOD = "playShowAnim";
    private static final String FIELD_SHOW_ANIM_SET = "mShowAnimSet";

    private static final String POPUP_INIT_METHOD = "initialize";
    private static final String TOP_BAR_CLASS = "cn.nubia.camera.fragment.TopBarFragment";
    private static final String TOP_BAR_DRAWER_INIT_METHOD = "initNubiaPopupDrawerButton";
    private static final String PREF_ICON_CLASS = "com.android.preference.IconListPreference";
    private static final String TOP_BAR_HELPER_CLASS =
            "cn.nubia.camera.fragment.TopBarFragment$TopBarHelper";
    private static final String MEMBER_INFO_CLASS = "cn.nubia.camera.memberinfo.MemberInfo";
    private static final String ANTISHAKE_PREF_KEY = "pref_antishake_key";
    private static final String FIELD_PREFERENCE = "mPreference";
    private static final String FIELD_DRAWER_PARENT = "mDrawerParent";
    private static final String FIELD_TOP_BAR_HELPER = "mTopBarHelper";
    private static final String FIELD_TOP_BAR_PARENT = "mTopBarParent";
    private static final String POPUP_INIT_ANIME_METHOD = "initAnime";
    private static final String FIELD_DRAW_ROOT = "mDrawRoot";
    private static final String FIELD_IS_EXPANDING = "isExpanding";
    private static final String FIELD_HIDE_ANIM_SET = "mHideAnimSet";
    private static final String POPUP_HIDE_METHOD = "playHideAnim";

    private static final String BOTTOM_BAR_CLASS = "cn.nubia.camera.fragment.BottomBarFragment";
    private static final String BOTTOM_PANEL_LAYOUT_METHOD = "initBottomPanelLayout";
    private static final String FIELD_BOTTOM_LAYOUT = "mBottomLayout";
    private static final String SCROLLER_CLASS =
            "cn/nubia/camera/memberscroller/MemberScrollerManager";
    private static final String SCROLLER_CLASS_DOT =
            "cn.nubia.camera.memberscroller.MemberScrollerManager";
    private static final String SCROLLER_LAYOUT_METHOD = "updateMemberScrollerParentLayout";
    private static final String FIELD_CAMERA_SELECT = "mCameraSelect";
    private static final String ACTIVITY_BASE_CLASS = "cn.nubia.camera.activity.ActivityBase";
    private static final String INIT_SCROLLER_METHOD = "initMemberScrollerUI";
    private static final String FIELD_MEMBER_SCROLLER = "mMemberScrollerManager";

    private static final String VIDEO_MEMBER_CLASS = "cn.nubia.camera.video.VideoMemberFragment";
    private static final String START_RECORD_METHOD = "startVideoRecord";
    private static final String FIELD_ANTISHAKE = "mAntiShake";

    private static final String HIGH_SETTING_METHOD = "updateHighSettingLayoutParam";
    private static final String FIELD_HIGH_SETTING_LAYOUT = "mHighSettingLayout";
    private static final String VIDEO_FRAGMENT_CLASS = "cn.nubia.camera.video.VideoFragment";
    private static final String ON_VIDEO_START_BEGIN = "onVideoStartBegin";
    private static final String FIELD_RECORDING_LAYOUT = "mBottomRecordingButtonLayout";
    private static final String FIELD_PRE_RECORDING_LAYOUT = "mBottomPreRecordingButtonLayout";
    private static final String MEMBER_FRAGMENT_CLASS = "cn.nubia.camera.fragment.MemberFragment";
    private static final String DISPATCH_TOUCH_METHOD = "dispatchTouchEvent";
    private static final String STREET_FRAGMENT_CLASS = "cn.nubia.camera.street.StreetFragment";
    private static final String STREET_EFFECT_LAYOUT_METHOD = "updateStreetEffectButtonLayout";
    private static final String FIELD_STREET_EFFECT_LAYOUT = "mStreetEffectLayout";
    private static final String PRO_FRAGMENT_CLASS = "cn.nubia.camera.pro.ProFragment";
    private static final String INIT_MODE_PICKER_METHOD = "initModePicker";
    private static final String MODE_PICKER_LAYOUT_ID = "mode_picker_button_layout";

    /** 近焦切换相关：偏好键与 SAT 回退下发通道。 */
    private static final String NORMAL_PARAMS_CLASS = "cn.nubia.camera.normal.NormalParametersItem";
    private static final String MACRO_PREF_KEY = "pref_camera_macro_ai_control_key";
    private static final String DARK_PREF_KEY = "pref_camera_dark_fallback_key";
    private static final String EXT_LENS_PREF_KEY = "pref_neo_bar_external_lens_focal_setting_key";
    private static final String METHOD_GET_SAT_FALLBACK_MODE = "getSatFallBackMode";
    private static final String METHOD_IS_SAT_FALLBACK_CLOSE = "isSatFallBackClose";
    private static final String FIELD_FORCE_STOP_MACRO = "forceStopMacroFallBackMode";
    private static final String FIELD_FORCE_STOP_DARK = "forceStopDarkFallBackMode";
    /** 与 NormalParametersItem 的常量保持一致：4=宏关/暗光关，5=宏关/暗光开。 */
    private static final int SAT_FALLBACK_MACRO_OFF_DARK_OFF = 4;
    private static final int SAT_FALLBACK_MACRO_OFF_DARK_ON = 5;

    /** 记录已经加过偏移的 View 及其最后一次 bottomMargin，避免重复叠加。 */
    private static final Map<View, Integer> APPLIED_MARGINS = new WeakHashMap<>();
    /** 抽屉等待 layout 的重试计数，避免重复注册 OnGlobalLayoutListener。 */
    private static final Map<View, Integer> DRAWER_LAYOUT_RETRY = new WeakHashMap<>();
    /** 抽屉展开期间被临时隐藏的右侧顶栏组件，收起时恢复。 */
    private static final List<View> HIDDEN_RIGHT_VIEWS = new ArrayList<>();
    /** 当前防抖抽屉按钮，供外部点击判定使用。 */
    private static WeakReference<Object> sAntishakeButton = new WeakReference<>(null);

    /** 底部快门/相册/前后摄整体上移量（dp），依据用户标注图绿圈：127px / 3.0 ≈ 42dp。 */
    private static final int BOTTOM_BAR_LIFT_DP = 42;
    /** 模式切换栏上移量（dp），依据用户标注图红线：92px / 3.0 ≈ 31dp。 */
    private static final int MODE_BAR_LIFT_DP = 31;
    /** 抽屉左边缘与防抖按钮右边缘的间距（dp）。 */
    private static final int DRAWER_GAP_DP = 8;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (lpparam == null || !TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        hookGetIntValue(lpparam);
        hookPopupShowAnim(lpparam);
        hookPopupHideAnim(lpparam);
        hookPopupInitialize(lpparam);
        hookTopBarDrawerInit(lpparam);
        hookBottomBarLift(lpparam);
        hookModeBarLift(lpparam);
        hookActivityScrollerInit(lpparam);
        hookRecordingLayouts(lpparam);
        hookStreetEffectLayout(lpparam);
        hookStreetHighSettingVisible(lpparam);
        hookProModePicker(lpparam);
        hookStartRecordCollapse(lpparam);
        hookOutsideTouch(lpparam);
        hookMacroFallbackOff(lpparam);
    }

    /**
     * 近焦切换控制=关 时，把“关”同时落实为两条 HAL 通道：
     *   - com.zte.chi.sat.fallbackclose = 1（硬关 SAT 回退）
     *   - com.zte.chi.sat.fallbackmode  = 4/5（宏关；暗光按用户设置保留）
     * 仅在偏好为 off 或框架强制停止宏回退时介入，其余设置保持原厂逻辑。
     */
    private static void hookMacroFallbackOff(LoadPackageParam lpparam) {
        hookSatFallbackClose(lpparam);
        hookSatFallbackMode(lpparam);
    }

    private static void hookSatFallbackClose(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    NORMAL_PARAMS_CLASS,
                    lpparam.classLoader,
                    METHOD_IS_SAT_FALLBACK_CLOSE,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (isMacroFallbackOff(param.thisObject)) {
                                    param.setResult(Boolean.TRUE);
                                    if (DEBUG) {
                                        XposedBridge.log(TAG
                                                + ": force sat.fallbackclose=1 (macro off)");
                                    }
                                }
                            } catch (Throwable t) {
                                logError(METHOD_IS_SAT_FALLBACK_CLOSE + "-after", t);
                            }
                        }
                    });
            logHooked(NORMAL_PARAMS_CLASS, METHOD_IS_SAT_FALLBACK_CLOSE);
        } catch (Throwable t) {
            logTargetMissing(METHOD_IS_SAT_FALLBACK_CLOSE, t);
        }
    }

    private static void hookSatFallbackMode(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    NORMAL_PARAMS_CLASS,
                    lpparam.classLoader,
                    METHOD_GET_SAT_FALLBACK_MODE,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!isMacroFallbackOff(param.thisObject)) {
                                    return;
                                }
                                int mode = buildMacroOffFallbackMode(param.thisObject);
                                Object old = param.getResult();
                                if (!(old instanceof Integer)
                                        || ((Integer) old).intValue() != mode) {
                                    param.setResult(Integer.valueOf(mode));
                                    if (DEBUG) {
                                        XposedBridge.log(TAG + ": sat.fallbackmode "
                                                + old + " -> " + mode + " (macro off)");
                                    }
                                }
                            } catch (Throwable t) {
                                logError(METHOD_GET_SAT_FALLBACK_MODE + "-after", t);
                            }
                        }
                    });
            logHooked(NORMAL_PARAMS_CLASS, METHOD_GET_SAT_FALLBACK_MODE);
        } catch (Throwable t) {
            logTargetMissing(METHOD_GET_SAT_FALLBACK_MODE, t);
        }
    }

    /**
     * 复刻 NormalParametersItem 在宏关闭时的取值：外接镜头存在时不做 SAT 回退（0），
     * 否则按暗光开关返回 4/5。原方法的 cameraId/支持类型前置判断若把结果降级成 0，
     * 这里会纠正过来，保证 HAL 收到明确的“宏关”语义。
     */
    private static int buildMacroOffFallbackMode(Object item) {
        String lens = prefString(item, EXT_LENS_PREF_KEY, "none");
        if (lens != null && !"none".equals(lens)) {
            return 0;
        }
        boolean darkOn;
        try {
            darkOn = Boolean.TRUE.equals(XposedHelpers.getObjectField(item, FIELD_FORCE_STOP_DARK))
                    ? false
                    : "on".equals(prefString(item, DARK_PREF_KEY, darkDefault(item)));
        } catch (Throwable t) {
            darkOn = "on".equals(prefString(item, DARK_PREF_KEY, darkDefault(item)));
        }
        return darkOn ? SAT_FALLBACK_MACRO_OFF_DARK_ON : SAT_FALLBACK_MACRO_OFF_DARK_OFF;
    }

    /**
     * 宏回退是否处于“关”：用户显式选择 off，或框架已强制停止宏回退。
     * 偏好读取失败时返回 false，保持原厂行为，不做任何接管。
     */
    private static boolean isMacroFallbackOff(Object item) {
        try {
            Object forced = XposedHelpers.getObjectField(item, FIELD_FORCE_STOP_MACRO);
            if (Boolean.TRUE.equals(forced)) {
                return true;
            }
        } catch (Throwable ignored) {
            // 字段缺失时继续看偏好
        }
        try {
            return "off".equals(prefString(item, MACRO_PREF_KEY, ""));
        } catch (Throwable t) {
            logError("isMacroFallbackOff", t);
            return false;
        }
    }

    private static String prefString(Object item, String key, String def) {
        Object prefs = XposedHelpers.callMethod(item, "getPreferences");
        if (prefs == null) {
            return null;
        }
        Object value = XposedHelpers.callMethod(prefs, "getString", key, def);
        return (value instanceof String) ? (String) value : null;
    }

    /** Config.getDarkFallBackDefaultValue() 的默认值；取不到时退回 off。 */
    private static String darkDefault(Object item) {
        try {
            ClassLoader cl = item != null ? item.getClass().getClassLoader() : null;
            Class<?> configClass = Class.forName(CONFIG_CLASS, false, cl);
            Object config = configClass.getMethod("getInstance").invoke(null);
            Object value = configClass.getMethod("getDarkFallBackDefaultValue").invoke(config);
            if (value instanceof String) {
                return (String) value;
            }
        } catch (Throwable ignored) {
            // 取不到默认值：按 off 处理，避免误开暗光回退
        }
        return "off";
    }

    private static void hookGetIntValue(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    CONFIG_CLASS,
                    lpparam.classLoader,
                    METHOD_NAME,
                    Map.class,
                    FEATURE_CLASS,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                intercept(param);
                            } catch (Throwable t) {
                                logError(METHOD_NAME, t);
                            }
                        }
                    });
            logHooked(CONFIG_CLASS, METHOD_NAME);
        } catch (Throwable t) {
            logTargetMissing(METHOD_NAME, t);
        }
    }

    /**
     * playShowAnim() 是 protected 无参方法，只有一个重载。
     * 反编译确认它只做：状态判断、日志、sPopAnimRunning=true、遍历 mAnimeItems bringToFront、
     * cancelAnim()、mShowAnimSet.start()；没有任何防抖/偏好写入。
     * 因此 mShowAnimSet == null 时短路返回，只会跳过弹出动画；
     * 调用方 zteOnClick 仍会继续执行它自己的后续逻辑。
     */
    private static void hookPopupShowAnim(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    POPUP_CLASS,
                    lpparam.classLoader,
                    POPUP_SHOW_METHOD,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object animSet = XposedHelpers.getObjectField(
                                        param.thisObject, FIELD_SHOW_ANIM_SET);
                                if (animSet == null) {
                                    if (DEBUG) {
                                        XposedBridge.log(TAG + ": skip "
                                                + POPUP_CLASS + "#" + POPUP_SHOW_METHOD
                                                + " (" + FIELD_SHOW_ANIM_SET + "=null)");
                                    }
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                // 字段读取失败按“动画不可用”处理，短路以避免原方法 NPE；
                                // 这里不捕获/吞掉原方法自身抛出的异常。
                                logError(POPUP_SHOW_METHOD + "-field", t);
                                param.setResult(null);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object animSet = XposedHelpers.getObjectField(
                                        param.thisObject, FIELD_SHOW_ANIM_SET);
                                Object expanding = XposedHelpers.getObjectField(
                                        param.thisObject, FIELD_IS_EXPANDING);
                                // 只有动画真正启动（isExpanding 已置 true）才隐藏按钮；
                                // before 短路时 isExpanding 仍为 false，图标保持可见。
                                if (animSet != null && Boolean.TRUE.equals(expanding)) {
                                    ((View) param.thisObject).setVisibility(View.INVISIBLE);
                                    if (DEBUG) {
                                        XposedBridge.log(TAG + ": antishake button hidden (drawer showing)");
                                    }
                                    // 初始化时抽屉还是 GONE，测不到高度，topMargin 只能先按顶边放。
                                    // 展开后（已 VISIBLE）再 post 一帧重新按中线对齐。
                                    final Object button = param.thisObject;
                                    ((View) button).post(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                Object host = XposedHelpers.getObjectField(
                                                        button, FIELD_DRAWER_PARENT);
                                                Object drawRoot = XposedHelpers.getObjectField(
                                                        button, FIELD_DRAW_ROOT);
                                                if (drawRoot instanceof View) {
                                                    DRAWER_LAYOUT_RETRY.remove((View) drawRoot);
                                                }
                                                if (host != null) {
                                                    positionDrawerNextToButton(button, host);
                                                }
                                                // 抽屉右侧与顶栏分辨率/镜头图标重叠，展开期间临时隐藏它们，
                                                // 保证三个档位完整可点；收起时恢复。
                                                hideRightTopViews(host, drawRoot);
                                            } catch (Throwable t) {
                                                logError("show-reposition", t);
                                            }
                                        }
                                    });
                                }
                            } catch (Throwable t) {
                                logError(POPUP_SHOW_METHOD + "-after", t);
                            }
                        }
                    });
            logHooked(POPUP_CLASS, POPUP_SHOW_METHOD);
        } catch (Throwable t) {
            logTargetMissing(POPUP_SHOW_METHOD, t);
        }
    }

    /**
     * 抽屉收起后恢复手型图标，保证可以再次点击展开。
     * playHideAnim 的 isExpanding 要等动画结束才置 false，after 时仍为 true，
     * 所以这里不再检查 isExpanding：只要 playHideAnim 被调用就恢复图标。
     * 若动画因冲突提前 return，图标会提前几百毫秒出现，不影响后续点击。
     */
    private static void hookPopupHideAnim(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    POPUP_CLASS,
                    lpparam.classLoader,
                    POPUP_HIDE_METHOD,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                ((View) param.thisObject).setVisibility(View.VISIBLE);
                                restoreRightTopViews();
                                if (DEBUG) {
                                    XposedBridge.log(TAG + ": antishake button restored");
                                }
                            } catch (Throwable t) {
                                logError(POPUP_HIDE_METHOD + "-after", t);
                            }
                        }
                    });
            logHooked(POPUP_CLASS, POPUP_HIDE_METHOD);
        } catch (Throwable t) {
            logTargetMissing(POPUP_HIDE_METHOD, t);
        }
    }

    private static void logHooked(String className, String method) {
        if (DEBUG) {
            XposedBridge.log(TAG + ": hooked " + className + "#" + method);
        }
    }

    /**
     * 防抖按钮初始化。VideoMemberFragment.getTopButtons() 会调用
     * mAntiShake.initialize(pref_antishake_key, null, topBarHelper, memberInfo)。
     * 此时按钮可能还没有 attach 到窗口，因此用 post 延迟到下一帧再补 initAnime。
     */
    private static void hookPopupInitialize(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    POPUP_CLASS,
                    lpparam.classLoader,
                    POPUP_INIT_METHOD,
                    PREF_ICON_CLASS,
                    PREF_ICON_CLASS,
                    TOP_BAR_HELPER_CLASS,
                    MEMBER_INFO_CLASS,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                ensureAntishakeDrawer(param.thisObject);
                            } catch (Throwable t) {
                                logError(POPUP_INIT_METHOD + "-after", t);
                            }
                        }
                    });
            logHooked(POPUP_CLASS, POPUP_INIT_METHOD);
        } catch (Throwable t) {
            logTargetMissing(POPUP_INIT_METHOD, t);
        }
    }

    /**
     * 兜底：若防抖按钮走的是顶栏“更多面板”路径，则在 TopBarFragment 完成
     * 监听器设置后再补一次初始化。ensureAntishakeDrawer 内部幂等。
     */
    private static void hookTopBarDrawerInit(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    TOP_BAR_CLASS,
                    lpparam.classLoader,
                    TOP_BAR_DRAWER_INIT_METHOD,
                    POPUP_CLASS,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                ensureAntishakeDrawer(param.args[0]);
                            } catch (Throwable t) {
                                logError(TOP_BAR_DRAWER_INIT_METHOD + "-after", t);
                            }
                        }
                    });
            logHooked(TOP_BAR_CLASS, TOP_BAR_DRAWER_INIT_METHOD);
        } catch (Throwable t) {
            logTargetMissing(TOP_BAR_DRAWER_INIT_METHOD, t);
        }
    }

    /**
     * 只处理 key 为 pref_antishake_key 的 NubiaPopupDrawerButton。
     * mDrawerParent 非 null 表示抽屉已经初始化过（重复调用直接跳过）。
     * 真正初始化在按钮 attach 后的下一帧执行，避免在未挂载的视图上 addView。
     */
    private static void ensureAntishakeDrawer(Object button) {
        if (!(button instanceof View)) {
            return;
        }
        try {
            Object pref = XposedHelpers.getObjectField(button, FIELD_PREFERENCE);
            if (pref == null) {
                return;
            }
            Object key = XposedHelpers.callMethod(pref, "getKey");
            if (!ANTISHAKE_PREF_KEY.equals(key)) {
                return;
            }
            Object drawerParent = XposedHelpers.getObjectField(button, FIELD_DRAWER_PARENT);
            if (drawerParent instanceof View && ((View) drawerParent).isAttachedToWindow()) {
                // 模式来回切换后按钮/顶栏可能复用，抽屉仍在，但位置或动画目标可能过期。
                // 这里重新计算一次位置，并重置 layout 重试计数，保证切换后仍然对齐。
                sAntishakeButton = new WeakReference<>(button);
                Object drawRoot = XposedHelpers.getObjectField(button, FIELD_DRAW_ROOT);
                if (drawRoot instanceof View) {
                    DRAWER_LAYOUT_RETRY.remove((View) drawRoot);
                }
                positionDrawerNextToButton(button, drawerParent);
                if (DEBUG) {
                    XposedBridge.log(TAG + ": drawer already initialized, repositioned");
                }
                return;
            }
            sAntishakeButton = new WeakReference<>(button);

            final Object target = button;
            ((View) button).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        initializeDrawer(target);
                    } catch (Throwable t) {
                        logError("initAnime", t);
                    }
                }
            });
            if (DEBUG) {
                XposedBridge.log(TAG + ": antishake drawer init scheduled");
            }
        } catch (Throwable t) {
            logError("ensureAntishakeDrawer", t);
        }
    }

    private static void initializeDrawer(Object button) {
        Object drawerParent = XposedHelpers.getObjectField(button, FIELD_DRAWER_PARENT);
        if (drawerParent != null) {
            return;
        }
        Object host = findDrawerHost(button);
        if (host == null) {
            if (DEBUG) {
                XposedBridge.log(TAG + ": drawer host not found, skip " + ANTISHAKE_PREF_KEY);
            }
            return;
        }
        // 让抽屉的展开动画保持原厂的从左侧滑入，不复用 setPosition 改动动画目标。
        XposedHelpers.callMethod(button, POPUP_INIT_ANIME_METHOD,
                new ArrayList<Object>(), host);
        positionDrawerNextToButton(button, host);
        if (DEBUG) {
            XposedBridge.log(TAG + ": drawer initialized, host=" + host.getClass().getName());
        }
    }

    /**
     * initializeDrawerView 只设置 topMargin（mPositionY + 4dp），水平位置由 XML 的
     * marginStart 固定，所以抽屉既没跟着按钮的水平位置，也没和按钮同高。
     * v1.8：抽屉顶边与防抖按钮顶边对齐，左边缘放在按钮右侧（8dp），
     * 并允许顶栏不裁剪抽屉（否则对齐后会缺一截）。
     * 这里延迟到下一帧，等抽屉完成测量后再算 topMargin，才能做高度夹取。
     */
    private static void positionDrawerNextToButton(Object button, Object host) {
        final View buttonView = (View) button;
        final View hostView = (View) host;
        // 横屏保持原厂布局：不改 topMargin/leftMargin，也不改 clipChildren。
        if (!isPortrait(buttonView)) {
            if (DEBUG) {
                XposedBridge.log(TAG + ": drawer pos skipped (landscape)");
            }
            return;
        }
        Object drawRoot = XposedHelpers.getObjectField(button, FIELD_DRAW_ROOT);
        if (!(drawRoot instanceof View)) {
            return;
        }
        final View drawer = (View) drawRoot;
        final int gap = dpToPx(buttonView, DRAWER_GAP_DP);

        // 顶栏高度只有 75dp，抽屉与按钮同高时会向下超出顶栏；
        // 不关闭裁剪的话超出部分会被裁掉，看起来就不像“同一高度”了。
        if (hostView instanceof ViewGroup) {
            ((ViewGroup) hostView).setClipChildren(false);
        }

        drawer.post(new Runnable() {
            @Override
            public void run() {
                try {
                    applyDrawerPosition(buttonView, hostView, drawer, gap);
                } catch (Throwable t) {
                    logError("drawer-position", t);
                }
            }
        });
    }

    /**
     * 对齐基准：以按钮的垂直中线为准。
     * 按钮中线 = 按钮窗口 Y − 宿主窗口 Y + 按钮高度/2；
     * 抽屉 topMargin = 按钮中线 − 抽屉高度/2。
     * 这样即使抽屉背景带阴影/内边距，视觉中心也与图标一致。
     * 抽屉首次 post 时高度可能还是 0，此时先按按钮顶边放一个临时值，
     * 等 OnGlobalLayout 拿到真实高度后再按中线重算一次。
     */
    private static void applyDrawerPosition(final View buttonView, final View hostView,
                                            final View drawer, final int gap) {
        ViewGroup.LayoutParams params = drawer.getLayoutParams();
        if (!(params instanceof RelativeLayout.LayoutParams)) {
            return;
        }
        int[] buttonPos = new int[2];
        int[] hostPos = new int[2];
        buttonView.getLocationOnScreen(buttonPos);
        hostView.getLocationOnScreen(hostPos);

        // 菜单左边界 = 手型按钮原本的左边界（紧贴闪光灯图标右侧），
        // 这样展开后菜单正好接替手型图标的位置并向右延伸。
        int left = buttonPos[0] - hostPos[0];
        int buttonTop = buttonPos[1] - hostPos[1];
        int buttonCenter = buttonTop + buttonView.getHeight() / 2;

        // 水平夹取：菜单宽度固定，若右边超出宿主宽度则左移，保证不出屏。
        boolean leftClamped = false;
        int hostWidth = hostView.getWidth();
        int drawerWidth = drawer.getWidth();
        if (drawerWidth > 0 && hostWidth > 0 && left + drawerWidth > hostWidth) {
            left = Math.max(0, hostWidth - drawerWidth);
            leftClamped = true;
        }

        // 可用区域：宿主根视图底部到宿主顶部的距离（顶栏场景就是整屏高度）。
        View root = hostView.getRootView();
        int[] rootPos = new int[2];
        root.getLocationOnScreen(rootPos);
        int available = root.getHeight() - (hostPos[1] - rootPos[1]);
        if (available <= 0) {
            available = hostView.getHeight();
        }

        int drawerHeight = drawer.getHeight();
        int top;
        boolean clamped = false;
        if (drawerHeight > 0) {
            top = buttonCenter - drawerHeight / 2;
            if (top < 0) {
                top = 0;
                clamped = true;
            } else if (top + drawerHeight > available) {
                top = Math.max(0, available - drawerHeight);
                clamped = true;
            }
        } else {
            // 还没测量出高度，先按顶边对齐，等 layout 完成后再重算。
            top = buttonTop;
        }

        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) params;
        lp.leftMargin = left;
        lp.topMargin = top;
        drawer.setLayoutParams(lp);

        if (DEBUG) {
            XposedBridge.log(TAG + ": drawer pos button=" + buttonPos[0] + "," + buttonPos[1]
                    + " host=" + hostPos[0] + "," + hostPos[1]
                    + " buttonSize=" + buttonView.getWidth() + "x" + buttonView.getHeight()
                    + " buttonCenterY=" + buttonCenter
                    + " drawerHeight=" + drawerHeight
                    + " left=" + left + " top=" + top
                    + (leftClamped ? " LEFT_CLAMPED hostWidth=" + hostWidth : "")
                    + (clamped ? " CLAMPED available=" + available : "")
                    + (drawerHeight <= 0 ? " (pending layout)" : ""));
        }

        if (drawerHeight <= 0) {
            Integer retried = DRAWER_LAYOUT_RETRY.get(drawer);
            int count = retried == null ? 0 : retried;
            if (count < 5) {
                DRAWER_LAYOUT_RETRY.put(drawer, count + 1);
                drawer.getViewTreeObserver().addOnGlobalLayoutListener(
                        new ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                drawer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                try {
                                    applyDrawerPosition(buttonView, hostView, drawer, gap);
                                } catch (Throwable t) {
                                    logError("drawer-position-layout", t);
                                }
                            }
                        });
            }
        }
    }


    /**
     * 底部三个按钮（快门/相册/前后摄）都在 BottomBarFragment.mBottomLayout
     * （R.id.camera_control_layout）里。initBottomPanelLayout 会在启动、旋转、
     * 折叠状态变化时重设 bottomMargin，所以 hook 它的 after，在原有值上再加 42dp。
     * 只处理竖屏，横屏保持原厂布局。
     */
    private static void hookBottomBarLift(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    BOTTOM_BAR_CLASS,
                    lpparam.classLoader,
                    BOTTOM_PANEL_LAYOUT_METHOD,
                    boolean.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object layout = XposedHelpers.getObjectField(
                                        param.thisObject, FIELD_BOTTOM_LAYOUT);
                                liftBottomMargin(layout, BOTTOM_BAR_LIFT_DP, "bottomBar");
                            } catch (Throwable t) {
                                logError(BOTTOM_PANEL_LAYOUT_METHOD + "-after", t);
                            }
                        }
                    });
            logHooked(BOTTOM_BAR_CLASS, BOTTOM_PANEL_LAYOUT_METHOD);
        } catch (Throwable t) {
            logTargetMissing(BOTTOM_PANEL_LAYOUT_METHOD, t);
        }
    }

    /**
     * 模式切换栏是 com.android.common.ui.CameraSelectButton，由
     * MemberScrollerManager.updateMemberScrollerParentLayout 每次重设
     * FrameLayout.LayoutParams（含 bottomMargin）。
     * hook 它的 after，在原有 bottomMargin 上加 31dp。只处理竖屏。
     */
    private static void hookModeBarLift(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    SCROLLER_CLASS_DOT,
                    lpparam.classLoader,
                    SCROLLER_LAYOUT_METHOD,
                    int.class,
                    boolean.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object view = XposedHelpers.getObjectField(
                                        param.thisObject, FIELD_CAMERA_SELECT);
                                liftBottomMargin(view, MODE_BAR_LIFT_DP, "modeBar");
                            } catch (Throwable t) {
                                logError(SCROLLER_LAYOUT_METHOD + "-after", t);
                            }
                        }
                    });
            logHooked(SCROLLER_CLASS, SCROLLER_LAYOUT_METHOD);
        } catch (Throwable t) {
            logTargetMissing(SCROLLER_LAYOUT_METHOD, t);
        }
    }

    /**
     * 实测启动/切模式时 updateMemberScrollerParentLayout 并不一定被调用，
     * 模式栏会保持 XML 的原始 bottomMargin。因此在 ActivityBase.initMemberScrollerUI
     * 之后直接取 MemberScrollerManager.mCameraSelect 再加一次偏移；
     * updateMemberScrollerParentLayout 的 hook 继续保留，作为以后重设时的兜底。
     */
    private static void hookActivityScrollerInit(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    ACTIVITY_BASE_CLASS,
                    lpparam.classLoader,
                    INIT_SCROLLER_METHOD,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object manager = XposedHelpers.getObjectField(
                                        param.thisObject, FIELD_MEMBER_SCROLLER);
                                if (manager == null) {
                                    return;
                                }
                                Object view = XposedHelpers.getObjectField(
                                        manager, FIELD_CAMERA_SELECT);
                                liftBottomMargin(view, MODE_BAR_LIFT_DP, "modeBar-init");
                            } catch (Throwable t) {
                                logError(INIT_SCROLLER_METHOD + "-after", t);
                            }
                        }
                    });
            logHooked(ACTIVITY_BASE_CLASS, INIT_SCROLLER_METHOD);
        } catch (Throwable t) {
            logTargetMissing(INIT_SCROLLER_METHOD, t);
        }
    }

    /**
     * 人文街拍/专业模式右侧设置按钮组在 BottomBarFragment.mHighSettingLayout
     * （R.id.high_setting_layout）里。v1.9 只偏移了 mBottomLayout，没有覆盖这个容器，
     * 所以这两个模式右下角的设置/专业视频按钮没有上移。
     * updateHighSettingLayoutParam 会在启动、模式切换和录像开始时重设 bottomMargin，
     * 因此 hook after 再叠加一次；只处理竖屏。
     */
    /**
     * 录像开始后底部会切换到 VideoFragment 自己的两套布局：
     *   mBottomRecordingButtonLayout     (R.id.bottom_recording_layout)
     *   mBottomPreRecordingButtonLayout  (R.id.bottom_pre_recording_layout)
     * 它们不经过 BottomBarFragment.initBottomPanelLayout，所以 v1.9 的 42dp 偏移
     * 没有覆盖，表现为“开拍后三个图标又掉回底部”。
     * onVideoStartBegin 是录制 UI 的统一切换点，after 时给两套布局补上偏移。
     */
    private static void hookRecordingLayouts(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    VIDEO_FRAGMENT_CLASS,
                    lpparam.classLoader,
                    ON_VIDEO_START_BEGIN,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object recording = XposedHelpers.getObjectField(
                                        param.thisObject, FIELD_RECORDING_LAYOUT);
                                liftBottomMargin(recording, BOTTOM_BAR_LIFT_DP, "recordingLayout");
                                Object preRecording = XposedHelpers.getObjectField(
                                        param.thisObject, FIELD_PRE_RECORDING_LAYOUT);
                                liftBottomMargin(preRecording, BOTTOM_BAR_LIFT_DP, "preRecordingLayout");
                            } catch (Throwable t) {
                                logError(ON_VIDEO_START_BEGIN + "-after", t);
                            }
                        }
                    });
            logHooked(VIDEO_FRAGMENT_CLASS, ON_VIDEO_START_BEGIN);
        } catch (Throwable t) {
            logTargetMissing(ON_VIDEO_START_BEGIN, t);
        }
    }

    /**
     * 人文街拍右侧的设置按钮组在 StreetFragment.mStreetEffectLayout
     * （R.id.street_effect_layout），位置由 updateStreetEffectButtonLayout
     * 按 realPhoneHeight 比例直接计算，不经过 BottomBarFragment 的布局，所以没被带上。
     * hook after 再叠加 42dp（竖屏）。
     */
    private static void hookStreetEffectLayout(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    STREET_FRAGMENT_CLASS,
                    lpparam.classLoader,
                    STREET_EFFECT_LAYOUT_METHOD,
                    int.class,
                    boolean.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object layout = XposedHelpers.getObjectField(
                                        param.thisObject, FIELD_STREET_EFFECT_LAYOUT);
                                liftBottomMargin(layout, BOTTOM_BAR_LIFT_DP, "streetEffect");
                            } catch (Throwable t) {
                                logError(STREET_EFFECT_LAYOUT_METHOD + "-after", t);
                            }
                        }
                    });
            logHooked(STREET_FRAGMENT_CLASS, STREET_EFFECT_LAYOUT_METHOD);
        } catch (Throwable t) {
            logTargetMissing(STREET_EFFECT_LAYOUT_METHOD, t);
        }
    }

    /**
     * 人文街拍切模式时 StreetFragment 会被复用，updateStreetEffectButtonLayout 不一定再调，
     * 因此再挂 setHighSettingVisible：切模式时它一定会被调用（日志
     * `[StreetFragment]setHighSettingVisible`），after 里给 mStreetEffectLayout 补偏移。
     */
    private static void hookStreetHighSettingVisible(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    STREET_FRAGMENT_CLASS,
                    lpparam.classLoader,
                    "setHighSettingVisible",
                    boolean.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object layout = XposedHelpers.getObjectField(
                                        param.thisObject, FIELD_STREET_EFFECT_LAYOUT);
                                liftBottomMargin(layout, BOTTOM_BAR_LIFT_DP, "streetEffect-visible");
                            } catch (Throwable t) {
                                logError("setHighSettingVisible-after", t);
                            }
                        }
                    });
            logHooked(STREET_FRAGMENT_CLASS, "setHighSettingVisible");
        } catch (Throwable t) {
            logTargetMissing("setHighSettingVisible", t);
        }
    }

    /**
     * 专业模式右下角的“专业视频模式切换”按钮在 ProFragment 的
     * mode_picker_button_layout 里，由 initModePicker 从 XML 取到，独立的布局参数。
     * hook after 给该容器 bottomMargin 加 42dp（竖屏），与拍摄键平齐。
     */
    private static void hookProModePicker(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    PRO_FRAGMENT_CLASS,
                    lpparam.classLoader,
                    INIT_MODE_PICKER_METHOD,
                    View.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                View root = (View) param.args[0];
                                int id = root.getResources().getIdentifier(
                                        MODE_PICKER_LAYOUT_ID, "id", TARGET_PACKAGE);
                                if (id != 0) {
                                    liftBottomMargin(root.findViewById(id),
                                            BOTTOM_BAR_LIFT_DP, "proModePicker");
                                }
                            } catch (Throwable t) {
                                logError(INIT_MODE_PICKER_METHOD + "-after", t);
                            }
                        }
                    });
            logHooked(PRO_FRAGMENT_CLASS, INIT_MODE_PICKER_METHOD);
        } catch (Throwable t) {
            logTargetMissing(INIT_MODE_PICKER_METHOD + "@" + PRO_FRAGMENT_CLASS, t);
        }
    }

    /**
     * 抽屉展开时，点击抽屉以外区域收起。
     * MemberFragment / TopBarFragment 各自覆写了 dispatchTouchEvent，分别覆盖
     * 预览+底部区域和顶栏区域；两处都挂，统一交给 handleOutsideTouch。
     * 只处理 ACTION_UP，不修改返回值，不吞正常触摸事件。
     */
    private static void hookOutsideTouch(LoadPackageParam lpparam) {
        hookDispatchTouch(MEMBER_FRAGMENT_CLASS, lpparam);
        hookDispatchTouch(TOP_BAR_CLASS, lpparam);
    }

    private static void hookDispatchTouch(String className, LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    className,
                    lpparam.classLoader,
                    DISPATCH_TOUCH_METHOD,
                    MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                handleOutsideTouch((MotionEvent) param.args[0]);
                            } catch (Throwable t) {
                                logError(DISPATCH_TOUCH_METHOD + "-after", t);
                            }
                        }
                    });
            logHooked(className, DISPATCH_TOUCH_METHOD);
        } catch (Throwable t) {
            logTargetMissing(DISPATCH_TOUCH_METHOD + "@" + className, t);
        }
    }

    private static void handleOutsideTouch(MotionEvent event) {
        if (event == null || event.getActionMasked() != MotionEvent.ACTION_UP) {
            return;
        }
        Object button = sAntishakeButton.get();
        if (!(button instanceof View)) {
            return;
        }
        Object expanding = XposedHelpers.getObjectField(button, FIELD_IS_EXPANDING);
        if (!Boolean.TRUE.equals(expanding)) {
            return;
        }
        Object drawRoot = XposedHelpers.getObjectField(button, FIELD_DRAW_ROOT);
        if (!(drawRoot instanceof View)) {
            return;
        }
        View drawer = (View) drawRoot;
        if (drawer.getVisibility() != View.VISIBLE || drawer.getWidth() <= 0) {
            return;
        }
        int[] pos = new int[2];
        drawer.getLocationOnScreen(pos);
        float x = event.getRawX();
        float y = event.getRawY();
        boolean inside = x >= pos[0] && x <= pos[0] + drawer.getWidth()
                && y >= pos[1] && y <= pos[1] + drawer.getHeight();
        if (inside) {
            return;
        }
        Object hideAnim = XposedHelpers.getObjectField(button, FIELD_HIDE_ANIM_SET);
        if (hideAnim == null) {
            return;
        }
        XposedHelpers.callMethod(button, POPUP_HIDE_METHOD);
        if (DEBUG) {
            XposedBridge.log(TAG + ": drawer collapsed by outside tap x=" + (int) x
                    + " y=" + (int) y);
        }
    }

    /**
     * 开始录像时收起防抖抽屉。
     * startVideoRecord() 由点击拍摄键触发；只在抽屉确实展开且隐藏动画已就绪时调用
     * playHideAnim()，避免 mHideAnimSet 为 null 时引入新的 NPE。
     */
    private static void hookStartRecordCollapse(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    VIDEO_MEMBER_CLASS,
                    lpparam.classLoader,
                    START_RECORD_METHOD,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object button = XposedHelpers.getObjectField(
                                        param.thisObject, FIELD_ANTISHAKE);
                                if (button == null) {
                                    return;
                                }
                                Object expanding = XposedHelpers.getObjectField(
                                        button, FIELD_IS_EXPANDING);
                                Object hideAnim = XposedHelpers.getObjectField(
                                        button, FIELD_HIDE_ANIM_SET);
                                if (Boolean.TRUE.equals(expanding) && hideAnim != null) {
                                    XposedHelpers.callMethod(button, POPUP_HIDE_METHOD);
                                    if (DEBUG) {
                                        XposedBridge.log(TAG + ": collapsed antishake drawer on record start");
                                    }
                                }
                            } catch (Throwable t) {
                                logError(START_RECORD_METHOD + "-after", t);
                            }
                        }
                    });
            logHooked(VIDEO_MEMBER_CLASS, START_RECORD_METHOD);
        } catch (Throwable t) {
            logTargetMissing(START_RECORD_METHOD, t);
        }
    }

    /**
     * 抽屉展开时，顶栏右侧的分辨率/镜头图标会与抽屉右端重叠。
     * 避让策略：临时把“完全位于抽屉右边界之外”的顶栏叶子 View 设为 INVISIBLE，
     * 收起时恢复。这样抽屉保持完整宽度，三个档位都能点到（优先保证可点性）。
     * 不使用收窄宽度，是因为 popup_drawer 内部的档位是固定宽度，收窄会把第三个裁掉。
     */
    private static void hideRightTopViews(Object hostObj, Object drawerObj) {
        restoreRightTopViews();
        if (!(hostObj instanceof ViewGroup) || !(drawerObj instanceof View)) {
            return;
        }
        View drawer = (View) drawerObj;
        if (drawer.getWidth() <= 0) {
            return;
        }
        int[] drawerPos = new int[2];
        drawer.getLocationOnScreen(drawerPos);
        int drawerRight = drawerPos[0] + drawer.getWidth();
        collectRightViews((ViewGroup) hostObj, drawer, drawerRight);
        if (DEBUG) {
            XposedBridge.log(TAG + ": hide right top views=" + HIDDEN_RIGHT_VIEWS.size()
                    + " drawerRight=" + drawerRight);
        }
    }

    private static void collectRightViews(ViewGroup group, View drawer, int drawerRight) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == drawer) {
                continue;
            }
            if (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() > 0) {
                collectRightViews((ViewGroup) child, drawer, drawerRight);
                continue;
            }
            if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0) {
                continue;
            }
            int[] childPos = new int[2];
            child.getLocationOnScreen(childPos);
            if (childPos[0] >= drawerRight) {
                child.setVisibility(View.INVISIBLE);
                HIDDEN_RIGHT_VIEWS.add(child);
            }
        }
    }

    private static void restoreRightTopViews() {
        if (!HIDDEN_RIGHT_VIEWS.isEmpty() && DEBUG) {
            XposedBridge.log(TAG + ": restore right top views=" + HIDDEN_RIGHT_VIEWS.size());
        }
        for (View view : HIDDEN_RIGHT_VIEWS) {
            try {
                view.setVisibility(View.VISIBLE);
            } catch (Throwable t) {
                logError("restore-right-view", t);
            }
        }
        HIDDEN_RIGHT_VIEWS.clear();
    }

    /**
     * 给任意 View 的 bottomMargin 增加 liftDp；非竖屏、非 MarginLayoutParams 时跳过。
     * bottomMargin 增加 = 视觉上移，横竖屏/分辨率由 dp 换算自适应。
     * 用 APPLIED_MARGINS 记住每个 View 上一次设置后的值：
     *   - 原方法重设了 margin -> 当前值 != 上次设置值，再加一次；
     *   - 没有重设（例如同一次录像里重复回调）-> 当前值 == 上次设置值，跳过，避免累积。
     */
    private static void liftBottomMargin(Object viewObj, int liftDp, String tag) {
        if (!(viewObj instanceof View)) {
            return;
        }
        View view = (View) viewObj;
        if (!isPortrait(view)) {
            return;
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) params;
        int current = lp.bottomMargin;
        Integer last = APPLIED_MARGINS.get(view);
        if (last != null && last == current) {
            return;
        }
        lp.bottomMargin = current + dpToPx(view, liftDp);
        view.setLayoutParams(lp);
        APPLIED_MARGINS.put(view, lp.bottomMargin);
        if (DEBUG) {
            XposedBridge.log(TAG + ": " + tag + " bottomMargin=" + lp.bottomMargin
                    + "px (lift " + liftDp + "dp)");
        }
    }

    private static boolean isPortrait(View view) {
        return view.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
    }

    private static int dpToPx(View view, int dp) {
        float density = view.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }


    /**
     * 优先使用 TopBarFragment.mTopBarParent（R.id.topbar 的 RelativeLayout，
     * 与 NubiaPopupSwitcherButton.initAnim 的宿主相同）；
     * 拿不到时再沿按钮的父链向上找第一个 RelativeLayout。
     */
    private static Object findDrawerHost(Object button) {
        try {
            Object helper = XposedHelpers.getObjectField(button, FIELD_TOP_BAR_HELPER);
            if (helper != null) {
                Object fragment = XposedHelpers.callMethod(helper, "getTopBarFragment");
                if (fragment != null) {
                    Object host = XposedHelpers.getObjectField(fragment, FIELD_TOP_BAR_PARENT);
                    if (host instanceof RelativeLayout) {
                        return host;
                    }
                }
            }
        } catch (Throwable t) {
            logError("findDrawerHost-helper", t);
        }

        ViewParent parent = ((View) button).getParent();
        while (parent instanceof View) {
            if (parent instanceof RelativeLayout) {
                return parent;
            }
            parent = ((View) parent).getParent();
        }
        return null;
    }

    private static void logTargetMissing(String method, Throwable t) {
        if (DEBUG) {
            XposedBridge.log(TAG + ": " + method + " not found, skip: " + t);
        }
    }

    private static void logError(String where, Throwable t) {
        if (DEBUG) {
            XposedBridge.log(TAG + ": " + where + " hook error: " + t);
        }
    }

    private static void intercept(XC_MethodHook.MethodHookParam param) {
        Object[] args = param.args;
        if (args == null || args.length < 3) {
            return;
        }
        Object mapObj = args[0];
        Object featureObj = args[1];
        Object keyObj = args[2];
        if (!(keyObj instanceof String)) {
            return;
        }

        String key = (String) keyObj;
        Map<?, ?> map = (mapObj instanceof Map) ? (Map<?, ?>) mapObj : null;
        Object raw = (map != null) ? map.get(key) : null;
        boolean fromFeature = false;
        if (raw == null && featureObj != null) {
            // Map 里没有该 key 时，getIntValue 会回退到 Feature.get(key)；
            // Z60 Ultra 的 ImmersionMode="true" 就在这个回退值里，必须一起归一化。
            try {
                raw = XposedHelpers.callMethod(featureObj, "get", key);
                fromFeature = true;
            } catch (Throwable t) {
                if (DEBUG) {
                    XposedBridge.log(TAG + ": feature fallback failed for " + key + ": " + t);
                }
                return;
            }
        }
        if (raw == null) {
            return;
        }

        Integer normalized = boolToInt(raw);
        if (normalized != null) {
            if (DEBUG) {
                XposedBridge.log(TAG + ": " + key + "=" + raw
                        + (fromFeature ? " (feature default)" : "") + " -> " + normalized);
            }
            param.setResult(normalized);
            return;
        }

        // 非 bool、但也不是合法 int 的字符串：回退到 Feature 默认值，避免解析异常
        if (raw instanceof String && !isIntString((String) raw)) {
            int fallback = defaultInt(featureObj, key);
            if (DEBUG) {
                XposedBridge.log(TAG + ": " + key + "=" + raw + " invalid int, fallback=" + fallback);
            }
            param.setResult(fallback);
        }
    }

    private static Integer boolToInt(Object raw) {
        if (raw instanceof Boolean) {
            return ((Boolean) raw) ? Integer.valueOf(1) : Integer.valueOf(0);
        }
        if (raw instanceof String) {
            String s = ((String) raw).trim();
            if ("true".equalsIgnoreCase(s)) {
                return Integer.valueOf(1);
            }
            if ("false".equalsIgnoreCase(s)) {
                return Integer.valueOf(0);
            }
        }
        return null;
    }

    private static boolean isIntString(String s) {
        try {
            Integer.parseInt(s.trim());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int defaultInt(Object featureObj, String key) {
        if (featureObj == null) {
            return 0;
        }
        try {
            Object def = XposedHelpers.callMethod(featureObj, "get", key);
            if (def instanceof String) {
                return Integer.parseInt(((String) def).trim());
            }
            if (def instanceof Boolean) {
                return ((Boolean) def) ? 1 : 0;
            }
        } catch (Throwable ignored) {
            // Feature.get 不可用：落到 0
        }
        return 0;
    }
}
