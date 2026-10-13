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
    /** flag 256: Fn (Search) re-injects the top row's original key code. */
    private static final int F_FN_RESTORE = 256;

    private static final int DEFAULT_FLAGS = F_META | F_SEARCH | F_FN_RESTORE;

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

    /** Resolved WindowManagerInternal, and whether we have already explained how. */
    private static volatile Object sWmCache;
    private static volatile boolean sWmLogged;

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

    /**
     * Allowed packages = UNION of the Settings.Global override and the config file, so either
     * route works on its own. Cached 2 s. If BOTH sources fail to read, the previous list is kept
     * (never silently becomes empty and locks Meta out everywhere).
     */
    private static Set<String> apps(Context ctx) {
        long now = SystemClock.uptimeMillis();
        if (now - sAppsAt < 2000L) {
            return sApps;
        }
        sAppsAt = now;

        Set<String> next = new HashSet<>();
        boolean read = false;
        String settingVal = "<not read: ctx was null>";

        try {
            if (ctx != null) {
                settingVal = Settings.Global.getString(ctx.getContentResolver(), "kbremap_apps");
                read = true;
                if (settingVal != null) {
                    for (String p : settingVal.split(",")) {
                        p = p.trim();
                        if (isPackage(p)) {
                            next.add(p);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            settingVal = "<threw: " + t + ">";
        }

        try (BufferedReader r = new BufferedReader(new FileReader(CFG_FILE))) {
            read = true;
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (isPackage(line)) {
                    next.add(line);
                }
            }
        } catch (Throwable ignored) {
            // no config file - perfectly normal
        }

        if (!read) {
            log("allow-list read FAILED from both sources; keeping " + sApps);
            return sApps;
        }
        if (!next.equals(sApps)) {
            log("allow-list changed = " + next + "   (settings.kbremap_apps=" + settingVal
                    + ", file=" + CFG_FILE + ")");
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
            // The DOWN was already routed. Permit the matching UP whenever the focused window is
            // still allowed, WITHOUT requiring BinderProxy identity: consuming the UP while the
            // DOWN was delivered would leave the app seeing a permanently-held Meta modifier.
            permit = allow && sRouted.get(id) != null;
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

    /**
     * Is the focused window owned by an allowed package?
     * Every reason for a "no" is logged, because "allow=false" with no explanation was exactly
     * what stalled the previous round. Add flag 128 to ignore the keyguard for testing.
     */
    private static boolean allowed(Context ctx, Object wm, IBinder token) {
        final boolean ignoreKeyguard = (flags() & 128) != 0;

        if (token == null) {
            log("ALLOW: token=null -> false");
            return false;
        }
        if (ctx == null) {
            log("ALLOW: ctx=null -> false");
            return false;
        }
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            boolean locked = km == null || km.isKeyguardLocked();
            if (locked && !ignoreKeyguard) {
                log("ALLOW: keyguard is locked -> false (flag 128 would ignore this)");
                return false;
            }
            if (wm == null) {
                log("ALLOW: mKeyGestureController.mWindowManagerInternal=null -> false");
                return false;
            }
            if (sGetInfo == null) {
                // MUST resolve through the HOST classloader. A bare Class.forName() uses the
                // MODULE's classloader, which cannot see com.android.server.* - that mistake
                // already cost one build cycle in windowManagerOf().
                ClassLoader cl = wm.getClass().getClassLoader();
                Class<?> wmi = XposedHelpers.findClassIfExists(
                        "com.android.server.wm.WindowManagerInternal", cl);
                Class<?> kii = XposedHelpers.findClassIfExists(
                        "com.android.internal.policy.KeyInterceptionInfo", cl);
                if (wmi == null || kii == null) {
                    log("ALLOW: class lookup failed - WindowManagerInternal=" + wmi
                            + " KeyInterceptionInfo=" + kii + " cl=" + cl);
                    return false;
                }
                sGetInfo = wmi.getMethod("getKeyInterceptionInfoFromToken", IBinder.class);
                sOwnerUid = kii.getField("windowOwnerUid");
                log("ALLOW: reflection ready via " + wmi.getName());
            }
            Object info = sGetInfo.invoke(wm, token);
            if (info == null) {
                log("ALLOW: getKeyInterceptionInfoFromToken returned null -> false");
                return false;
            }
            int uid = sOwnerUid.getInt(info);
            PackageManager pm = ctx.getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            Set<String> allow = apps(ctx);
            boolean ok = false;
            if (packages != null) {
                for (String p : packages) {
                    if (allow.contains(p)) {
                        ok = true;
                        break;
                    }
                }
            }
            log("ALLOW: uid=" + uid + " pkgs=" + Arrays.toString(packages)
                    + " keyguardLocked=" + locked + " allow=" + allow + " -> " + ok);
            return ok;
        } catch (Throwable t) {
            if (!sFocusErrorLogged) {
                sFocusErrorLogged = true;
                log("ALLOW: focus lookup FAILED -> " + t);
            }
            return false;
        }
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

                            // ---- Layer A2: Fn (Search) restores the ORIGINAL top-row behaviour ----
                            // A Java hook cannot change a key code (the native dispatcher dispatches
                            // its own copy), so instead of rewriting we re-inject the original code
                            // and swallow the F-key. Injection is verified working on this device:
                            //   input keyevent 220  ->  screen_brightness 1270 -> 1095
                            // The injected event carries a different key code and no scan code, so it
                            // cannot re-enter this branch - no recursion.
                            if ((flags() & F_FN_RESTORE) != 0 && isTopRow(e) && e.isFunctionPressed()) {
                                int orig = topRowOriginal(e);
                                if (orig != -1) {
                                    injectKey(p.thisObject, e, orig);
                                    p.setResult(0); // never deliver the F-key itself
                                    return;
                                }
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

    /**
     * Resolve WindowManagerInternal.
     *
     * The reference module reads InputManagerService.mKeyGestureController.mWindowManagerInternal,
     * but Oplus ships its OWN InputManagerService (it is in /system/framework/oplus-services.jar)
     * and that field is null on this build. LocalServices is the portable AOSP route and needs no
     * private field names at all, so it is tried first.
     */
    private static Object windowManagerOf(Object ims) {
        Object cached = sWmCache;
        if (cached != null) {
            return cached;
        }
        if (ims == null) {
            return null;
        }
        // Must use the HOST classloader: Class.forName() from the module cannot see
        // com.android.server.* and failed with ClassNotFoundException earlier.
        ClassLoader cl = ims.getClass().getClassLoader();

        // route 1: LocalServices.getService(WindowManagerInternal.class)
        try {
            Class<?> wmi = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.WindowManagerInternal", cl);
            Class<?> ls = wmi == null ? null
                    : XposedHelpers.findClassIfExists("com.android.server.LocalServices", cl);
            if (ls != null) {
                Method get = ls.getMethod("getService", Class.class);
                Object wm = get.invoke(null, wmi);
                if (wm != null) {
                    log("windowManager resolved via LocalServices (" + wm.getClass().getName() + ")");
                    sWmCache = wm;
                    return wm;
                }
            }
        } catch (Throwable t) {
            log("LocalServices route FAILED: " + t);
        }

        // route 2: a DIRECT field on InputManagerService - this is the OxygenOS layout
        // (confirmed by dumping the fields: InputManagerService.mWindowManagerInternal = LocalService)
        try {
            Object wm = XposedHelpers.getObjectField(ims, "mWindowManagerInternal");
            if (wm != null) {
                log("windowManager resolved via InputManagerService.mWindowManagerInternal");
                sWmCache = wm;
                return wm;
            }
        } catch (Throwable t) {
            log("IMS.mWindowManagerInternal FAILED: " + t);
        }

        // route 3: mKeyGestureController.mWindowManagerInternal - the crDroid layout used by
        // the reference module
        try {
            Object kgc = XposedHelpers.getObjectField(ims, "mKeyGestureController");
            Object wm = XposedHelpers.getObjectField(kgc, "mWindowManagerInternal");
            if (wm != null) {
                log("windowManager resolved via mKeyGestureController.mWindowManagerInternal");
                sWmCache = wm;
                return wm;
            }
        } catch (Throwable t) {
            log("mKeyGestureController route FAILED: " + t);
        }

        return null;
    }

    /** One-off diagnostic: which fields does this build's InputManagerService actually have? */
    private static void dumpFields(Object o, String label) {
        try {
            for (Field f : o.getClass().getDeclaredFields()) {
                String n = f.getName().toLowerCase();
                if (!n.contains("gesture") && !n.contains("window") && !n.contains("context")) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(o);
                log("  " + label + "." + f.getName() + " : " + f.getType().getSimpleName()
                        + " = " + (v == null ? "null" : v.getClass().getSimpleName()));
            }
        } catch (Throwable t) {
            log("  field dump failed: " + t);
        }
    }

    // ------------------------------------------------------------- Fn restore (Layer A2)

    /** Top row left-to-right is F1..F12 in the keylayout; these are its ORIGINAL key codes. */
    // Scans 183 and 194 (the lock and mic keys, originally F13 and F24) are deliberately NOT
    // listed here. Their original key codes resolve correctly (326 and 337 on this build) and
    // the injection is accepted (ok=true), but the platform does not act on them: those two
    // actions are only honoured from real hardware events. Injecting them achieved nothing, so
    // they are left out and Fn+F3 / Fn+F6 now simply behave like plain F3 / F6 (chosen trade-off
    // to keep the full F1..F12 row). Do not re-add them without re-testing.
    private static final int[] TOP_ROW_SCANS = {
            224, 225, 186, 99, 165, 164, 163, 113, 114, 115};
    private static final String[] TOP_ROW_ORIGINAL_NAMES = {
            "BRIGHTNESS_DOWN", "BRIGHTNESS_UP", "F16", "SYSRQ",
            "MEDIA_PREVIOUS", "MEDIA_PLAY_PAUSE", "MEDIA_NEXT",
            "VOLUME_MUTE", "VOLUME_DOWN", "VOLUME_UP"};
    private static final int[] TOP_ROW_ORIGINAL = new int[TOP_ROW_SCANS.length];

    /** Several KEYCODE_* constants are @hide in the SDK, so resolve them by name. */
    private static int kc(String name) {
        try {
            return XposedHelpers.getStaticIntField(KeyEvent.class, "KEYCODE_" + name);
        } catch (Throwable t) {
            log("kc(" + name + ") unresolved: " + t);
            return -1;
        }
    }

    private static boolean isTopRow(KeyEvent e) {
        int s = e.getScanCode();
        for (int scan : TOP_ROW_SCANS) {
            if (scan == s) {
                return true;
            }
        }
        return false;
    }

    private static int topRowOriginal(KeyEvent e) {
        int s = e.getScanCode();
        for (int i = 0; i < TOP_ROW_SCANS.length; i++) {
            if (TOP_ROW_SCANS[i] == s) {
                if (TOP_ROW_ORIGINAL[i] == 0) {
                    TOP_ROW_ORIGINAL[i] = kc(TOP_ROW_ORIGINAL_NAMES[i]);
                }
                return TOP_ROW_ORIGINAL[i];
            }
        }
        return -1;
    }

    /**
     * Re-inject the key with its original code, using the InputManagerService instance we are
     * already hooked on. Mode 0 is INJECT_INPUT_EVENT_MODE_ASYNC.
     */
    private static void injectKey(Object ims, KeyEvent src, int keyCode) {
        try {
            KeyEvent out = new KeyEvent(src.getDownTime(), src.getEventTime(),
                    src.getAction(), keyCode, src.getRepeatCount());
            out.setSource(0x00000101); // InputDevice.SOURCE_KEYBOARD
            Object r = XposedHelpers.callMethod(ims, "injectInputEvent", out, 0);
            log("FN-RESTORE scan=" + src.getScanCode() + " action=" + src.getAction()
                    + " -> injected code=" + keyCode + " ok=" + r);
        } catch (Throwable t) {
            log("FN-RESTORE inject FAILED: " + t);
        }
    }
}
