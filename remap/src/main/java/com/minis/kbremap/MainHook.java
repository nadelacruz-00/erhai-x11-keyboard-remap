package com.minis.kbremap;

import android.app.KeyguardManager;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module for the OnePlus Pad 3 official (pogo) keyboard.
 *
 * WHY THIS LAYER
 * --------------
 * v1-v3 hooked PhoneWindowManager.interceptKeyBeforeQueueing. That runs too late: Android's
 * key-gesture layer (KeyGestureController, which on this device carries an Oplus "MetaKeyHandler")
 * consumes the Meta key AFTER PhoneWindowManager returns and BEFORE the event is dispatched.
 * That is why forcing ACTION_PASS_TO_USER never reached the app.
 *
 * This version hooks com.android.server.input.InputManagerService instead - before the gesture
 * policy - which is the approach proven by Drun555/oneplus-meta-gate on this same tablet.
 *
 * WHAT IT DOES
 * ------------
 *   interceptKeyBeforeQueueing(KeyEvent, int) -> int
 *       Meta / Meta-combos : return 1 = pass to dispatcher, bypassing Android's gestures
 *       Search             : return 0 = drop
 *       anything else      : -2 = leave stock policy completely alone
 *   interceptKeyBeforeDispatching(IBinder, KeyEvent, int) -> long
 *       Meta combos are delivered only when the FOCUSED window belongs to an allowed package,
 *       otherwise consumed. -2 leaves stock behaviour alone.
 *   dispatchUnhandledKey(IBinder, KeyEvent, int) -> KeyEvent
 *       suppress the fallback for the keys we own.
 *
 * ESCAPE and the TOP ROW are NOT handled here on purpose: a Java hook cannot change a keycode,
 * because the native InputDispatcher dispatches its own copy of the event. Those need the keymap
 * layer (the KernelSU module: key 158 ESCAPE, key 193 FUNCTION, top row -> F1..F12).
 *
 * SAFETY
 * ------
 *   - only Meta/Search events are ever overridden; every other key is untouched
 *   - kill switch:  settings put global kbremap_flags 0     (takes effect within a second)
 *   - allow-list empty  =>  Meta is blocked everywhere (stock-ish, never worse)
 *   - if the focused window cannot be resolved, Meta is blocked rather than leaked
 *
 * LIVE FLAGS  (settings put global kbremap_flags <n>)
 *     1    route Meta through the dispatcher        (default ON)
 *     2    drop the Search key everywhere           (default ON)
 *     4    legacy: also force PASS_TO_USER at PhoneWindowManager (default OFF)
 *     8    neutralise the Oplus shortcut classes    (default OFF)
 *     16   log every key event                      (default OFF)
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "KbRemap";
    private static final String SYSTEM = "android";

    private static final String CLS_IMS = "com.android.server.input.InputManagerService";
    private static final String CLS_PWM = "com.android.server.policy.PhoneWindowManager";

    private static final int ACTION_PASS_TO_USER = 1;
    private static final int POLICY_FLAG_INTERACTIVE = 0x20000000;

    /** "leave stock policy alone" - shorter than an int, so it can never be a real result. */
    private static final int PASSTHROUGH = -2;

    private static final int F_META = 1;
    private static final int F_SEARCH = 2;
    private static final int F_LEGACY_PWM = 4;
    private static final int F_OPLUS_BLOCK = 8;
    private static final int F_LOG_ALL = 16;
    private static final int DEFAULT_FLAGS = F_META | F_SEARCH;

    private static final String CFG_FILE = "/data/system/minis_kbremap/apps.conf";
    private static final String[] DEFAULT_APPS = {"com.termux.x11"};

    // ---------------------------------------------------------------- state

    private static final Set<Long> sQueued = new HashSet<>();
    private static final Map<Long, IBinder> sRouted = new HashMap<>();

    private static volatile int sFlags = DEFAULT_FLAGS;
    private static volatile long sFlagsAt = 0L;

    private static volatile Set<String> sApps = new HashSet<>(Arrays.asList(DEFAULT_APPS));
    private static volatile long sAppsAt = 0L;

    private static Method sGetInfo;
    private static Field sOwnerUid;
    private static boolean sFocusErrorLogged;

    // ---------------------------------------------------------------- helpers

    private static void log(String s) {
        XposedBridge.log(TAG + ": " + s);
    }

    private static int flags() {
        long now = SystemClock.uptimeMillis();
        if (now - sFlagsAt < 1000L) {
            return sFlags;
        }
        sFlagsAt = now;
        try {
            Context c = AndroidAppHelper.currentApplication();
            if (c != null) {
                String v = Settings.Global.getString(c.getContentResolver(), "kbremap_flags");
                sFlags = (v == null || v.trim().isEmpty()) ? DEFAULT_FLAGS
                        : Integer.parseInt(v.trim());
            }
        } catch (Throwable ignored) {
            // keep the previous value
        }
        return sFlags;
    }

    /** Allowed packages: Settings.Global wins, otherwise the config file. Cached 2 s. */
    private static Set<String> apps(Context ctx) {
        long now = SystemClock.uptimeMillis();
        if (now - sAppsAt < 2000L) {
            return sApps;
        }
        sAppsAt = now;
        Set<String> next = null;
        try {
            if (ctx != null) {
                String v = Settings.Global.getString(ctx.getContentResolver(), "kbremap_apps");
                if (v != null && !v.trim().isEmpty()) {
                    next = new HashSet<>();
                    for (String p : v.split(",")) {
                        p = p.trim();
                        if (isPackage(p)) {
                            next.add(p);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (next == null) {
            try (BufferedReader r = new BufferedReader(new FileReader(CFG_FILE))) {
                Set<String> file = new HashSet<>();
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (isPackage(line)) {
                        file.add(line);
                    }
                }
                next = file;
            } catch (Throwable ignored) {
                // keep the previous list
            }
        }
        if (next != null) {
            if (!next.equals(sApps)) {
                log("allow-list = " + next);
            }
            sApps = next;
        }
        return sApps;
    }

    private static boolean isPackage(String s) {
        return s != null && s.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }

    private static long id(KeyEvent e) {
        return ((long) e.getDeviceId() << 32) | (e.getKeyCode() & 0xffffffffL);
    }

    /** KEYCODE_F23 is @hide in the SDK but exists at runtime; resolve it reflectively once. */
    private static volatile int sF23 = Integer.MIN_VALUE;

    private static int f23() {
        if (sF23 == Integer.MIN_VALUE) {
            int v = -1;
            try {
                v = XposedHelpers.getStaticIntField(KeyEvent.class, "KEYCODE_F23");
            } catch (Throwable ignored) {
                // leave -1: fall back to matching on the scan code
            }
            sF23 = v;
        }
        return sF23;
    }

    private static boolean isSearch(KeyEvent e) {
        // The physical Search key emits hidden KEYCODE_F23 at Linux scan code 193 here.
        if (e.getScanCode() == 193) {
            return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_SEARCH) {
            return true;
        }
        int f23 = f23();
        return f23 != -1 && e.getKeyCode() == f23;
    }

    private static boolean isMetaRelated(KeyEvent e) {
        int k = e.getKeyCode();
        return k == KeyEvent.KEYCODE_META_LEFT || k == KeyEvent.KEYCODE_META_RIGHT
                || e.isMetaPressed();
    }

    private static boolean relevant(KeyEvent e) {
        return isSearch(e) || isMetaRelated(e);
    }

    private static String ev(KeyEvent k) {
        if (k == null) {
            return "null";
        }
        return "a=" + k.getAction() + " code=" + k.getKeyCode() + " scan=" + k.getScanCode()
                + " dev=" + k.getDeviceId() + " meta=0x" + Integer.toHexString(k.getMetaState());
    }

    // ---------------------------------------------------------------- queue policy

    /** @return PASSTHROUGH to leave stock policy alone, else the policy-flags result. */
    private static synchronized int queue(KeyEvent e, int policyFlags) {
        long id = id(e);
        boolean special = relevant(e) || sQueued.contains(id);
        if (!special) {
            return PASSTHROUGH;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            sQueued.remove(id);
        } else {
            sQueued.add(id);
        }

        int f = flags();
        if ((f & F_LOG_ALL) != 0) {
            log("QUEUE " + ev(e) + " policy=0x" + Integer.toHexString(policyFlags));
        }

        if (isSearch(e)) {
            if ((f & F_SEARCH) == 0) {
                return PASSTHROUGH;
            }
            log("QUEUE drop SEARCH " + ev(e));
            return 0;
        }

        if (isMetaRelated(e)) {
            if ((f & F_META) == 0) {
                return PASSTHROUGH;
            }
            boolean interactive = (policyFlags & POLICY_FLAG_INTERACTIVE) != 0;
            log("QUEUE meta " + ev(e) + " interactive=" + interactive
                    + " -> " + (interactive ? "PASS_TO_USER(bypass gestures)" : "drop"));
            return interactive ? ACTION_PASS_TO_USER : 0;
        }

        return PASSTHROUGH;
    }

    /** @return PASSTHROUGH, or 0 = deliver to focused window, -1 = consume. */
    private static synchronized int dispatch(Context ctx, Object wm, IBinder token, KeyEvent e) {
        long id = id(e);
        boolean tracked = sRouted.containsKey(id);
        if (!relevant(e) && !tracked) {
            return PASSTHROUGH;
        }
        if (isSearch(e)) {
            return -1; // Search is blocked everywhere
        }

        boolean plainEscape = e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE && !e.isMetaPressed();
        boolean allow = token != null && (plainEscape || allowed(ctx, wm, token));

        boolean permit;
        if (tracked) {
            IBinder bound = sRouted.get(id);
            permit = allow && bound != null && bound.equals(token);
        } else {
            permit = allow && e.getAction() == KeyEvent.ACTION_DOWN;
        }

        if (e.getAction() == KeyEvent.ACTION_UP) {
            sRouted.remove(id);
        } else if (!tracked) {
            sRouted.put(id, permit ? token : null);
        }

        int f = flags();
        if ((f & F_LOG_ALL) != 0 || !permit) {
            log("DISPATCH " + ev(e) + " token=" + token + " allow=" + allow
                    + " -> " + (permit ? "deliver" : "consume"));
        }
        return permit ? 0 : -1;
    }

    private static boolean allowed(Context ctx, Object wm, IBinder token) {
        if (ctx == null || wm == null || token == null) {
            return false;
        }
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null || km.isKeyguardLocked()) {
                return false;
            }
            if (sGetInfo == null) {
                sGetInfo = Class.forName("com.android.server.wm.WindowManagerInternal")
                        .getMethod("getKeyInterceptionInfoFromToken", IBinder.class);
                sOwnerUid = Class.forName("com.android.internal.policy.KeyInterceptionInfo")
                        .getField("windowOwnerUid");
            }
            Object info = sGetInfo.invoke(wm, token);
            if (info == null) {
                return false;
            }
            int uid = sOwnerUid.getInt(info);
            PackageManager pm = ctx.getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            Set<String> allow = apps(ctx);
            if (packages != null) {
                for (String p : packages) {
                    if (allow.contains(p)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            if (!sFocusErrorLogged) {
                sFocusErrorLogged = true;
                log("focus lookup failed; Meta will be blocked - " + t);
            }
        }
        return false;
    }

    private static boolean suppressFallback(KeyEvent e) {
        return relevant(e) || sRouted.containsKey(id(e));
    }

    // ---------------------------------------------------------------- hook wiring

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!SYSTEM.equals(lp.packageName)) {
            return;
        }
        log("=== v4 loaded into " + lp.packageName + " ===");

        hookImsQueueing(lp);
        hookImsDispatching(lp);
        hookImsFallback(lp);

        if ((flags() & F_LEGACY_PWM) != 0) {
            hookLegacyPwm(lp);
        }

        log("allow-list at load = " + apps(AndroidAppHelper.currentApplication()));
    }

    private void hookImsQueueing(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(CLS_IMS, lp.classLoader,
                    "interceptKeyBeforeQueueing", KeyEvent.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            KeyEvent e = (KeyEvent) p.args[0];
                            if (e == null) {
                                return;
                            }
                            int r = queue(e, (Integer) p.args[1]);
                            if (r != PASSTHROUGH) {
                                p.setResult(r);
                            }
                        }
                    });
            log("hooked IMS.interceptKeyBeforeQueueing OK");
        } catch (Throwable t) {
            log("!! IMS.interceptKeyBeforeQueueing FAILED: " + t);
        }
    }

    private void hookImsDispatching(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(CLS_IMS, lp.classLoader,
                    "interceptKeyBeforeDispatching",
                    IBinder.class, KeyEvent.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            KeyEvent e = (KeyEvent) p.args[1];
                            if (e == null) {
                                return;
                            }
                            Context ctx = contextOf(p.thisObject);
                            Object wm = windowManagerOf(p.thisObject);
                            int r = dispatch(ctx, wm, (IBinder) p.args[0], e);
                            if (r != PASSTHROUGH) {
                                p.setResult((long) r);
                            }
                        }
                    });
            log("hooked IMS.interceptKeyBeforeDispatching OK");
        } catch (Throwable t) {
            log("!! IMS.interceptKeyBeforeDispatching FAILED: " + t);
        }
    }

    private void hookImsFallback(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(CLS_IMS, lp.classLoader,
                    "dispatchUnhandledKey",
                    IBinder.class, KeyEvent.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            KeyEvent e = (KeyEvent) p.args[1];
                            if (e != null && suppressFallback(e)) {
                                p.setResult(null);
                            }
                        }
                    });
            log("hooked IMS.dispatchUnhandledKey OK");
        } catch (Throwable t) {
            log("!! IMS.dispatchUnhandledKey FAILED: " + t);
        }
    }

    /** Belt and braces: if the IMS hook ever fails, this still frees Meta at the policy layer. */
    private void hookLegacyPwm(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(CLS_PWM, lp.classLoader,
                    "interceptKeyBeforeQueueing", KeyEvent.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            KeyEvent e = (KeyEvent) p.args[0];
                            if (e != null && isMetaRelated(e) && (flags() & F_META) != 0) {
                                p.setResult(ACTION_PASS_TO_USER);
                            }
                        }
                    });
            log("hooked legacy PhoneWindowManager OK");
        } catch (Throwable t) {
            log("!! legacy PhoneWindowManager FAILED: " + t);
        }
    }

    private static Context contextOf(Object ims) {
        try {
            Object ctx = XposedHelpers.getObjectField(ims, "mContext");
            if (ctx instanceof Context) {
                return (Context) ctx;
            }
        } catch (Throwable ignored) {
        }
        return AndroidAppHelper.currentApplication();
    }

    /** InputManagerService -> mKeyGestureController -> mWindowManagerInternal */
    private static Object windowManagerOf(Object ims) {
        try {
            Object kgc = XposedHelpers.getObjectField(ims, "mKeyGestureController");
            return XposedHelpers.getObjectField(kgc, "mWindowManagerInternal");
        } catch (Throwable t) {
            return null;
        }
    }
}
