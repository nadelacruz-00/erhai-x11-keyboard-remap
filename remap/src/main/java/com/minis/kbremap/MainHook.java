package com.minis.kbremap;

import android.os.IBinder;
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
 * Everything is done per key EVENT, inside system_server, because keylayout files
 * (*.kl / *.kcm) are global and static - there is no per-app keymap in Android.
 *
 * Measured on the device (getevent on /dev/input/event9 + Oplus's own KEYLOG_ traces):
 *   bot          scan 125  KEYCODE_META_LEFT   -> consumed by Oplus "draw mode" gesture
 *   Esc          scan 158  KEYCODE_BACK        -> reaches apps fine
 *   Search       scan 193  KEYCODE_F23         -> consumed, opens global search
 *   top row      scan 224,225,183,186,99,194,165,164,163,113,114,115,111
 *
 * This first version only reclaims the Meta key; the other mappings come next.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "KbRemap";
    private static final String SYSTEM = "android";

    /** WindowManagerPolicy.ACTION_PASS_TO_USER */
    private static final int ACTION_PASS_TO_USER = 1;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!SYSTEM.equals(lp.packageName)) {
            return;
        }
        log("=== loaded into " + lp.packageName + " process=" + lp.processName + " ===");
        hookQueueing(lp);
        hookDispatching(lp);
        dumpClasses(lp);
    }

    private static void log(String s) {
        XposedBridge.log(TAG + ": " + s);
    }

    private static boolean isMeta(KeyEvent e) {
        int c = e.getKeyCode();
        return c == KeyEvent.KEYCODE_META_LEFT || c == KeyEvent.KEYCODE_META_RIGHT;
    }

    /**
     * interceptKeyBeforeQueueing runs BEFORE Oplus's KeyGestureControllerExtImpl and
     * OplusModifierShortcutManager see the event, so returning ACTION_PASS_TO_USER here
     * both delivers the Meta key to the app and suppresses the "draw mode" gesture.
     */
    private void hookQueueing(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.policy.PhoneWindowManager",
                    lp.classLoader, "interceptKeyBeforeQueueing",
                    KeyEvent.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            KeyEvent e = (KeyEvent) p.args[0];
                            if (e == null || !isMeta(e)) {
                                return;
                            }
                            log("QUEUE meta action=" + e.getAction()
                                    + " code=" + e.getKeyCode()
                                    + " scan=" + e.getScanCode()
                                    + " dev=" + e.getDeviceId()
                                    + " injected=" + e.getFlags()
                                    + " -> ACTION_PASS_TO_USER");
                            p.setResult(ACTION_PASS_TO_USER);
                        }
                    });
            log("hooked interceptKeyBeforeQueueing OK");
        } catch (Throwable t) {
            log("!! interceptKeyBeforeQueueing FAILED: " + t);
        }
    }

    /** Make sure nothing downstream consumes the Meta key either. */
    private void hookDispatching(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.policy.PhoneWindowManager",
                    lp.classLoader, "interceptKeyBeforeDispatching",
                    IBinder.class, KeyEvent.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            KeyEvent e = (KeyEvent) p.args[1];
                            if (e == null || !isMeta(e)) {
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

    /**
     * Diagnostic: list the methods of the Oplus classes we may need to neutralise in v2.
     * The log lines show up in LSPosed's log / logcat under tag "LSPosed-Bridge".
     */
    private void dumpClasses(XC_LoadPackage.LoadPackageParam lp) {
        String[] names = {
                "com.android.server.policy.KeyGestureControllerExtImpl",
                "com.android.server.policy.OplusModifierShortcutManager",
                "com.android.server.policy.PhoneWindowManagerExtImpl",
        };
        for (String n : names) {
            try {
                Class<?> c = XposedHelpers.findClassIfExists(n, lp.classLoader);
                if (c == null) {
                    log("class NOT FOUND: " + n);
                    continue;
                }
                for (Method m : c.getDeclaredMethods()) {
                    log("  " + n + "#" + m.getName() + " : "
                            + m.getReturnType().getSimpleName());
                }
            } catch (Throwable t) {
                log("dump " + n + " failed: " + t);
            }
        }
    }
}
