package com.minis.kbremap;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module for the OnePlus Pad 3 official (pogo) keyboard.
 *
 * v1 proved the hook point works: PhoneWindowManager.interceptKeyBeforeQueueing sees the real
 * Meta keypress (code=117 scan=125 deviceId=17). But forcing ACTION_PASS_TO_USER alone was not
 * enough - Oplus consumes it further down.
 *
 * v2 additionally neutralises the Oplus consumers, and every behaviour is switchable AT RUNTIME
 * through Settings.Global so we never need a reboot to iterate:
 *
 *     settings put global kbremap_flags <n>
 *
 *   bit 1 (1)   reclaim Meta  -> ACTION_PASS_TO_USER          (default ON)
 *   bit 2 (2)   block OplusModifierShortcutManager            (default ON)
 *   bit 4 (4)   block KeyGestureController meta gesture       (default ON)
 *   bit 8 (8)   block PhoneWindowManagerExtImpl launcher keys (default ON)
 *   bit 16      log every key event                           (default OFF)
 *
 * default flags = 15
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "KbRemap";
    private static final String SYSTEM = "android";
    private static final int ACTION_PASS_TO_USER = 1;

    private static final String CLS_PWM = "com.android.server.policy.PhoneWindowManager";
    private static final String CLS_PWM_EXT = "com.android.server.policy.PhoneWindowManagerExtImpl";
    private static final String CLS_OPLUS_MOD = "com.android.server.policy.OplusModifierShortcutManager";
    private static final String CLS_KGESTURE = "com.android.server.input.KeyGestureController";

    // Found in /system/framework/oplus-services.jar. NOTE: "Extlmpl" really is spelled with a
    // lowercase L - that is Oplus's own typo, and it is part of the real class name.
    private static final String CLS_KG_EXT = "com.android.server.input.KeyGestureControllerExtlmpl";
    private static final String CLS_KG_EXT_META = CLS_KG_EXT + "$MetaKeyHandler";
    private static final String CLS_KG_MGR = "com.android.server.input.OplusKeyGestureManager";

    private static final int F_META = 1;
    private static final int F_OPLUS_MOD = 2;
    private static final int F_KGESTURE = 4;
    private static final int F_PWM_EXT = 8;
    private static final int F_LOG_ALL = 16;
    private static final int DEFAULT_FLAGS = F_META | F_OPLUS_MOD | F_KGESTURE | F_PWM_EXT;

    private static volatile int sFlags = DEFAULT_FLAGS;
    private static volatile long sFlagsAt = 0L;

    /** Live config: re-read Settings.Global at most once a second. */
    private static int flags() {
        long now = SystemClock.uptimeMillis();
        if (now - sFlagsAt < 1000L) {
            return sFlags;
        }
        sFlagsAt = now;
        try {
            Context ctx = AndroidAppHelper.currentApplication();
            if (ctx != null) {
                String v = Settings.Global.getString(ctx.getContentResolver(), "kbremap_flags");
                sFlags = (v == null || v.trim().isEmpty()) ? DEFAULT_FLAGS
                        : Integer.parseInt(v.trim());
            }
        } catch (Throwable ignored) {
            // keep previous value
        }
        return sFlags;
    }

    private static void log(String s) {
        XposedBridge.log(TAG + ": " + s);
    }

    private static String describeArgs(Object[] args) {
        if (args == null) {
            return "[]";
        }
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                b.append(", ");
            }
            Object a = args[i];
            if (a instanceof KeyEvent) {
                KeyEvent k = (KeyEvent) a;
                b.append("KeyEvent(action=").append(k.getAction())
                        .append(", code=").append(k.getKeyCode())
                        .append(", scan=").append(k.getScanCode())
                        .append(", dev=").append(k.getDeviceId()).append(')');
            } else {
                b.append(a);
            }
        }
        return b.append(']').toString();
    }

    private static boolean isMeta(KeyEvent e) {
        int c = e.getKeyCode();
        return c == KeyEvent.KEYCODE_META_LEFT || c == KeyEvent.KEYCODE_META_RIGHT;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!SYSTEM.equals(lp.packageName)) {
            return;
        }
        log("=== loaded into " + lp.packageName + " process=" + lp.processName + " ===");

        hookQueueing(lp);
        hookDispatching(lp);

        // --- Oplus consumers -------------------------------------------------
        blockAll(lp, CLS_OPLUS_MOD, "enterDrawerMode", F_OPLUS_MOD);
        blockAll(lp, CLS_OPLUS_MOD, "handleKeyGestureEvent", F_OPLUS_MOD);
        blockAll(lp, CLS_OPLUS_MOD, "executeShortcutKey", F_OPLUS_MOD);
        blockAll(lp, CLS_OPLUS_MOD, "launchIntentShortcut", F_OPLUS_MOD);
        blockAll(lp, CLS_OPLUS_MOD, "launchAssistant", F_OPLUS_MOD);
        blockAll(lp, CLS_OPLUS_MOD, "toggleControlPanel", F_OPLUS_MOD);
        blockAll(lp, CLS_OPLUS_MOD, "takeAreaScreenShot", F_OPLUS_MOD);

        blockAll(lp, CLS_PWM_EXT, "interceptKeyEventToLauncherIfNeed", F_PWM_EXT);
        blockAll(lp, CLS_PWM_EXT, "interceptKeyEventForAppShareModeIfNeed", F_PWM_EXT);
        blockAll(lp, CLS_PWM_EXT, "interceptHomeKeyEventToLauncherIfNeed", F_PWM_EXT);

        // --- Oplus gesture controller (the "enter draw mode" caller) ----------
        // The exact class that logged "enter draw mode when short press meta in export".
        blockAll(lp, CLS_KG_EXT, "interceptOldShortcuts", F_KGESTURE);
        blockAll(lp, CLS_KG_EXT, "interceptWinShortcuts", F_KGESTURE);
        blockAll(lp, CLS_KG_EXT, "interceptMacShortcuts", F_KGESTURE);
        blockAll(lp, CLS_KG_EXT, "getOplusMetaKeyGestureEvent", F_KGESTURE);
        blockAll(lp, CLS_KG_EXT, "handleKeyGestureEvent", F_KGESTURE);
        blockAll(lp, CLS_KG_EXT_META, "handleKeyGesture", F_KGESTURE);
        blockAll(lp, CLS_KG_MGR, "handleKeyGestureEvent", F_KGESTURE);

        // AOSP side (observe only - other features use it)
        logOnly(lp, CLS_KGESTURE, "handleKeyGestureEvent", F_KGESTURE);

        dumpMethods(lp, CLS_KG_EXT);
    }

    /** Diagnostic: list every method of a class so the next round has exact names. */
    private void dumpMethods(XC_LoadPackage.LoadPackageParam lp, String cls) {
        try {
            Class<?> c = XposedHelpers.findClassIfExists(cls, lp.classLoader);
            if (c == null) {
                log("class NOT FOUND: " + cls);
                return;
            }
            for (Method m : c.getDeclaredMethods()) {
                log("  " + c.getSimpleName() + "#" + m.getName() + " : "
                        + m.getReturnType().getSimpleName());
            }
            for (Class<?> inner : c.getDeclaredClasses()) {
                log("  inner class " + inner.getName());
                for (Method m : inner.getDeclaredMethods()) {
                    log("    " + inner.getSimpleName() + "#" + m.getName() + " : "
                            + m.getReturnType().getSimpleName());
                }
            }
        } catch (Throwable t) {
            log("dumpMethods " + cls + " FAILED: " + t);
        }
    }

    /** Meta must reach the app, and Oplus's gesture layer must not see it first. */
    private void hookQueueing(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(CLS_PWM, lp.classLoader,
                    "interceptKeyBeforeQueueing", KeyEvent.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            KeyEvent e = (KeyEvent) p.args[0];
                            if (e == null) {
                                return;
                            }
                            int f = flags();
                            if ((f & F_LOG_ALL) != 0) {
                                log("KEY " + describeArgs(p.args));
                            }
                            if (!isMeta(e) || (f & F_META) == 0) {
                                return;
                            }
                            log("META pass-to-user action=" + e.getAction()
                                    + " scan=" + e.getScanCode() + " dev=" + e.getDeviceId());
                            p.setResult(ACTION_PASS_TO_USER);
                        }
                    });
            log("hooked interceptKeyBeforeQueueing OK");
        } catch (Throwable t) {
            log("!! interceptKeyBeforeQueueing FAILED: " + t);
        }
    }

    private void hookDispatching(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(CLS_PWM, lp.classLoader,
                    "interceptKeyBeforeDispatching", IBinder.class, KeyEvent.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            KeyEvent e = (KeyEvent) p.args[1];
                            if (e == null || !isMeta(e) || (flags() & F_META) == 0) {
                                return;
                            }
                            p.setResult(0L);
                        }
                    });
            log("hooked interceptKeyBeforeDispatching OK");
        } catch (Throwable t) {
            log("!! interceptKeyBeforeDispatching FAILED: " + t);
        }
    }

    /** Hook every overload; skip the original when the owning flag is set. */
    private void blockAll(XC_LoadPackage.LoadPackageParam lp, String cls, String method,
                          int flag) {
        try {
            Class<?> c = XposedHelpers.findClassIfExists(cls, lp.classLoader);
            if (c == null) {
                log("class NOT FOUND: " + cls);
                return;
            }
            int n = XposedBridge.hookAllMethods(c, method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    boolean on = (flags() & flag) != 0;
                    int f = flags();
                    log((on ? "BLOCK " : "SEE ") + cls + "#" + method
                            + " args=" + describeArgs(p.args)
                            + (on ? "" : "  (flag off, passing through)"));
                    if (!on) {
                        return;
                    }
                    Class<?> rt = ((Method) p.method).getReturnType();
                    if (rt == boolean.class) {
                        p.setResult(false);
                    } else if (rt == void.class) {
                        p.setResult(null);
                    } else if (rt == long.class) {
                        p.setResult(0L);
                    } else if (rt == int.class) {
                        p.setResult(0);
                    }
                    // other return types: let the original run
                }
            }).size();
            log("hooked " + cls + "#" + method + " (" + n + " overload(s))");
        } catch (Throwable t) {
            log("hook " + cls + "#" + method + " FAILED: " + t);
        }
    }

    /** Observe only - never skip. Used for AOSP methods that other features rely on. */
    private void logOnly(XC_LoadPackage.LoadPackageParam lp, String cls, String method, int flag) {
        try {
            Class<?> c = XposedHelpers.findClassIfExists(cls, lp.classLoader);
            if (c == null) {
                log("class NOT FOUND: " + cls);
                return;
            }
            int n = XposedBridge.hookAllMethods(c, method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    log("SEE " + cls + "#" + method + " args=" + describeArgs(p.args));
                }
            }).size();
            log("observed " + cls + "#" + method + " (" + n + " overload(s))");
        } catch (Throwable t) {
            log("observe " + cls + "#" + method + " FAILED: " + t);
        }
    }
}
