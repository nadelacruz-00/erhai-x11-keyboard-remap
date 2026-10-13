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
 * Progress so far:
 *   v1  hook point proven - PhoneWindowManager.interceptKeyBeforeQueueing sees the real Meta
 *       press (code=117 scan=125 dev=17), but forcing ACTION_PASS_TO_USER was not enough.
 *   v2  the real Oplus classes were found in /system/framework/oplus-services.jar and blocked;
 *       this DID stop Oplus's Meta behaviour (Gemini long-press no longer fires) - but the app
 *       still receives nothing.
 *
 * v3 is a DIAGNOSTIC build. The key question: does the Meta event survive as far as
 * interceptKeyBeforeDispatching (i.e. was it actually dispatched to a window)?
 *   - if YES -> the event reaches the dispatcher but not the app
 *   - if NO  -> something between queueing and dispatching consumes it
 *
 * Live flags (no reboot needed):
 *     settings put global kbremap_flags <n>
 *
 *   1   reclaim Meta -> ACTION_PASS_TO_USER              (default ON)
 *   2   block OplusModifierShortcutManager                (default ON)
 *   4   block KeyGestureControllerExtlmpl (Oplus)         (default ON)
 *   8   block PhoneWindowManagerExtImpl launcher keys     (default ON)
 *   16  log every key event                               (default OFF)
 *   32  allow object-returning methods to be nulled       (default ON)
 *   64  block AOSP KeyGestureController.handleKeyGestureEvent (default ON)
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "KbRemap";
    private static final String SYSTEM = "android";
    private static final int ACTION_PASS_TO_USER = 1;

    private static final String CLS_PWM = "com.android.server.policy.PhoneWindowManager";
    private static final String CLS_PWM_EXT = "com.android.server.policy.PhoneWindowManagerExtImpl";
    private static final String CLS_OPLUS_MOD = "com.android.server.policy.OplusModifierShortcutManager";
    private static final String CLS_KGESTURE = "com.android.server.input.KeyGestureController";

    // From /system/framework/oplus-services.jar. "Extlmpl" really is spelled with a lowercase L -
    // that is Oplus's own typo and it is part of the real class name.
    private static final String CLS_KG_EXT = "com.android.server.input.KeyGestureControllerExtlmpl";
    private static final String CLS_KG_EXT_META = CLS_KG_EXT + "$MetaKeyHandler";
    private static final String CLS_KG_MGR = "com.android.server.input.OplusKeyGestureManager";

    private static final int F_META = 1;
    private static final int F_OPLUS_MOD = 2;
    private static final int F_KGESTURE = 4;
    private static final int F_PWM_EXT = 8;
    private static final int F_LOG_ALL = 16;
    private static final int F_NULL_OBJECTS = 32;
    private static final int F_BLOCK_AOSP_KG = 64;
    private static final int DEFAULT_FLAGS =
            F_META | F_OPLUS_MOD | F_KGESTURE | F_PWM_EXT | F_NULL_OBJECTS | F_BLOCK_AOSP_KG;

    private static volatile int sFlags = DEFAULT_FLAGS;
    private static volatile long sFlagsAt = 0L;

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
            // keep the previous value
        }
        return sFlags;
    }

    private static void log(String s) {
        XposedBridge.log(TAG + ": " + s);
    }

    private static boolean isMeta(KeyEvent e) {
        int c = e.getKeyCode();
        return c == KeyEvent.KEYCODE_META_LEFT || c == KeyEvent.KEYCODE_META_RIGHT;
    }

    private static String ev(KeyEvent k) {
        if (k == null) {
            return "null";
        }
        return "KeyEvent(a=" + k.getAction() + ", code=" + k.getKeyCode()
                + ", scan=" + k.getScanCode() + ", dev=" + k.getDeviceId()
                + ", meta=0x" + Integer.toHexString(k.getMetaState())
                + ", flags=0x" + Integer.toHexString(k.getFlags()) + ")";
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
                b.append(ev((KeyEvent) a));
            } else {
                b.append(a);
            }
        }
        return b.append(']').toString();
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!SYSTEM.equals(lp.packageName)) {
            return;
        }
        log("=== v3 loaded into " + lp.packageName + " process=" + lp.processName + " ===");

        hookQueueing(lp);
        hookDispatching(lp);

        // --- Oplus consumers (v2) --------------------------------------------
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

        blockAll(lp, CLS_KG_EXT, "interceptOldShortcuts", F_KGESTURE);
        blockAll(lp, CLS_KG_EXT, "interceptWinShortcuts", F_KGESTURE);
        blockAll(lp, CLS_KG_EXT, "interceptMacShortcuts", F_KGESTURE);
        blockAll(lp, CLS_KG_EXT, "getOplusMetaKeyGestureEvent", F_KGESTURE);
        blockAll(lp, CLS_KG_EXT, "handleKeyGestureEvent", F_KGESTURE);
        blockAll(lp, CLS_KG_EXT_META, "handleKeyGesture", F_KGESTURE);
        blockAll(lp, CLS_KG_MGR, "handleKeyGestureEvent", F_KGESTURE);

        // --- AOSP key-gesture layer: this is what turns a key into a "gesture" and
        //     consumes the plain KeyEvent. v2 only observed it.
        blockAll(lp, CLS_KGESTURE, "handleKeyGestureEvent", F_BLOCK_AOSP_KG);
        blockAll(lp, CLS_KGESTURE, "getOplusMetaKeyGestureEvent", F_BLOCK_AOSP_KG);

        dumpMethods(lp, CLS_KGESTURE);
        dumpMethods(lp, CLS_KG_EXT);
        dumpMethods(lp, "com.android.server.policy.SingleKeyGestureDetector");
        dumpMethods(lp, "com.android.server.policy.OplusSingleKeyGestureDetector");
    }

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
                                log("QUEUE-IN " + ev(e));
                            }
                            if (isMeta(e) && (f & F_META) != 0) {
                                p.setResult(ACTION_PASS_TO_USER);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            KeyEvent e = (KeyEvent) p.args[0];
                            if (e != null && isMeta(e)) {
                                log("QUEUE-OUT " + ev(e) + " returned=0x"
                                        + Integer.toHexString(p.getResult() == null ? -1
                                        : ((Integer) p.getResult())));
                            }
                        }
                    });
            log("hooked interceptKeyBeforeQueueing OK");
        } catch (Throwable t) {
            log("!! interceptKeyBeforeQueueing FAILED: " + t);
        }
    }

    /** The decisive probe: is this called for Meta at all? */
    private void hookDispatching(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(CLS_PWM, lp.classLoader,
                    "interceptKeyBeforeDispatching", IBinder.class, KeyEvent.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            KeyEvent e = (KeyEvent) p.args[1];
                            if (e == null || !isMeta(e)) {
                                return;
                            }
                            log("DISPATCH-IN " + ev(e));
                            if ((flags() & F_META) != 0) {
                                p.setResult(0L);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            KeyEvent e = (KeyEvent) p.args[1];
                            if (e != null && isMeta(e)) {
                                log("DISPATCH-OUT " + ev(e) + " returned=" + p.getResult());
                            }
                        }
                    });
            log("hooked interceptKeyBeforeDispatching OK");
        } catch (Throwable t) {
            log("!! interceptKeyBeforeDispatching FAILED: " + t);
        }
    }

    /** Hook every overload; skip the original when its owning flag is set. */
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
                    int f = flags();
                    boolean on = (f & flag) != 0;
                    log((on ? "BLOCK " : "SEE ") + cls + "#" + method
                            + " args=" + describeArgs(p.args));
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
                    } else if (!rt.isPrimitive() && (f & F_NULL_OBJECTS) != 0) {
                        // e.g. getOplusMetaKeyGestureEvent() -> returning null stops the caller
                        // from getting a gesture object at all.
                        log("   -> nulling " + rt.getSimpleName() + " result");
                        p.setResult(null);
                    }
                }
            }).size();
            log("hooked " + cls + "#" + method + " (" + n + " overload(s))");
        } catch (Throwable t) {
            log("hook " + cls + "#" + method + " FAILED: " + t);
        }
    }

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
                log("  inner " + inner.getSimpleName());
                for (Method m : inner.getDeclaredMethods()) {
                    log("    " + inner.getSimpleName() + "#" + m.getName() + " : "
                            + m.getReturnType().getSimpleName());
                }
            }
        } catch (Throwable t) {
            log("dumpMethods " + cls + " FAILED: " + t);
        }
    }
}
