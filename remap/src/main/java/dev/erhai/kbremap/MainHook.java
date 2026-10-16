package dev.erhai.kbremap;

import android.app.AndroidAppHelper;
import android.content.Context;
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
 * ONE master switch, applied at boot. The per-app allow-list was removed deliberately: the keymap
 * layer (*.kl / *.kcm) is global and static, so a per-app list could only ever be partly true and
 * made the screen misleading.
 *
 *   switch ON   the keyboard is remapped:
 *                 - the bot/AI key is delivered as a real Meta/Super
 *                 - the Search key is blocked (it is the Fn modifier)
 *                 - Esc is a real Escape, the top row is F1..F12   <- done by the keylayout
 *                 - holding Fn (Search) restores a top-row key's original action
 *   switch OFF  no hooks are installed at all, and the KernelSU module that carries the
 *               keylayout is disabled, so the keyboard is genuinely stock. Nothing is
 *               reconstructed, so "off" is exactly "off".
 *
 * Because the switch is defined as "applied by a reboot" there is nothing to re-read at runtime,
 * which is what lets OFF be a true no-op rather than an un-doing.
 *
 * WHICH HOOK DOES THE WORK
 * ------------------------
 * interceptKeyBeforeQueueing frees the key from the ROM's queueing-time policy, but on this build
 * that is NOT enough: the Oplus KeyGestureController (KeyGestureControllerExtlmpl, shipped in
 * oplus-services.jar) still runs at DISPATCH time and turns a Meta long-press into the AI gesture.
 * So interceptKeyBeforeDispatching is hooked as well - returning 0 from it delivers the event
 * without ever running the ROM's own body, which is what the gesture lives in.
 *
 * WHAT A HOOK CAN AND CANNOT DO
 * -----------------------------
 * A Java hook cannot change a key code - the native InputDispatcher dispatches its own copy of the
 * event. That is why Esc and the F-row live in the keylayout, and why Fn-restore works by
 * consuming the key and re-injecting the original code rather than by rewriting it.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "KbRemap";
    private static final String SYSTEM = "android";

    private static final String CLS_IMS = "com.android.server.input.InputManagerService";

    /** WindowManagerPolicy.ACTION_PASS_TO_USER */
    private static final int ACTION_PASS_TO_USER = 1;
    private static final int POLICY_FLAG_INTERACTIVE = 0x20000000;

    /** interceptKeyBeforeDispatching: dispatch the event now / do not dispatch it. */
    private static final long DELIVER_NOW = 0L;
    private static final long CONSUME = -1L;

    /** The ROM class that owns the Meta long-press gesture on this build. */
    private static final String CLS_OPLUS_GESTURE =
            "com.android.server.input.KeyGestureControllerExtlmpl";

    // ------------------------------------------------------------------ entry point

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!SYSTEM.equals(lp.packageName)) {
            return;
        }

        // THE MASTER SWITCH CANNOT BE READ HERE.
        //
        // This runs very early in boot, before the Settings provider is up. Reading it throws
        // NullPointerException from IActivityManager.getContentProvider, and any "default to
        // enabled" fallback then silently installs hooks even when the switch is OFF - which is
        // exactly what happened on device: the switch said off, the keymap was stock, and the
        // Search key was still being swallowed.
        //
        // So the hooks are installed unconditionally, and each of them asks enabled() - which
        // resolves the switch LAZILY on the first key event, when Settings is definitely
        // available, then caches it for the rest of the boot.
        log("=== KbRemap loaded - master switch resolved on first key event ===");

        hookQueueing(lp);
        hookDispatching(lp);
        hookOplusBlock(lp);
        hookFallback(lp);
    }

    /** -1 unknown, 0 off, 1 on. Resolved on first use; the switch is defined as boot-applied. */
    private static volatile int sEnabled = -1;

    /**
     * Fn/Search state, tracked by us instead of read from the event.
     *
     * Android keeps modifier (meta) state PER INPUT DEVICE - each KeyboardInputMapper owns its own
     * mMetaState - and a Bluetooth-connected keyboard exposes the Search key on one input device
     * while the brightness and volume keys arrive from its separate "Consumer Control" collection
     * as a second device. So an event from that second device never carries AMETA_FUNCTION_ON, and
     * isFunctionPressed() is false on it no matter how firmly Fn is held on the first. That is why
     * Fn+the brightness pair did nothing over Bluetooth while Fn+a same-device key (the trackpad /
     * F16 key, SYSRQ) did restore.
     */
    private static volatile boolean sFnDown = false;

    private static boolean enabled() {
        int cached = sEnabled;
        if (cached >= 0) {
            return cached == 1;
        }
        boolean on = true;
        boolean resolved = false;
        try {
            Context ctx = AndroidAppHelper.currentApplication();
            if (ctx != null) {
                on = Config.isEnabled(ctx.getContentResolver());
                resolved = true;
                // Announce that the hook is loaded this boot, so the settings screen can confirm
                // the LSPosed half is live rather than leaving it to guesswork.
                try {
                    android.provider.Settings.Global.putString(ctx.getContentResolver(),
                            Config.KEY_HOOK_ACTIVE, bootId());
                } catch (Throwable ignored) {
                    // purely informational
                }
            }
        } catch (Throwable t) {
            log("master switch not readable yet, will retry: " + t);
        }
        if (!resolved) {
            // Never cache an assumption: if Settings was not up yet, try again on the next key.
            return on;
        }
        sEnabled = on ? 1 : 0;
        log("=== master switch: remap is " + (on ? "ENABLED" : "DISABLED") + " ===");
        return on;
    }

    private static String bootId() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/sys/kernel/random/boot_id"))) {
            String line = r.readLine();
            return line == null ? "" : line.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void log(String s) {
        XposedBridge.log(TAG + ": " + s);
    }

    // ------------------------------------------------------------------ policy

    /**
     * Runs BEFORE Android's key-gesture layer, which on this device carries an Oplus
     * MetaKeyHandler. Returning ACTION_PASS_TO_USER here is what delivers <the bot key> to apps
     * instead of letting that handler turn it into the draw-mode gesture.
     */
    private void hookQueueing(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(CLS_IMS, lp.classLoader,
                    "interceptKeyBeforeQueueing", KeyEvent.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!enabled()) {
                                return; // switch off: intercept nothing at all
                            }
                            KeyEvent e = (KeyEvent) p.args[0];
                            if (e == null) {
                                return;
                            }
                            int policyFlags = (Integer) p.args[1];

                            // The Search key is the Fn modifier, and is blocked everywhere.
                            // Handle it FIRST so we can record its state: the Fn flag is what lets
                            // the top-row branch below work for keys on the OTHER input device.
                            if (isSearch(e)) {
                                sFnDown = e.getAction() == KeyEvent.ACTION_DOWN;
                                log("QUEUE drop SEARCH scan=" + e.getScanCode()
                                        + " fnDown=" + sFnDown);
                                p.setResult(0);
                                return;
                            }

                            // Fn held restores the original action of a top-row key by re-injecting
                            // its original code. Two signals are accepted: the platform's own meta
                            // state (only ever set for keys on the same device as Fn) and our own
                            // cross-device flag.
                            if (isTopRow(e) && (e.isFunctionPressed() || sFnDown)) {
                                int orig = originalFor(e);
                                if (orig != -1) {
                                    injectKey(p.thisObject, e, orig);
                                    p.setResult(0); // never deliver the F-key itself
                                    return;
                                }
                            }

                            if (isMetaRelated(e)) {
                                boolean interactive = (policyFlags & POLICY_FLAG_INTERACTIVE) != 0;
                                log("QUEUE meta a=" + e.getAction() + " code=" + e.getKeyCode()
                                        + " scan=" + e.getScanCode()
                                        + (interactive ? " -> PASS_TO_USER" : " -> drop"));
                                p.setResult(interactive ? ACTION_PASS_TO_USER : 0);
                            }
                        }
                    });
            log("hooked IMS.interceptKeyBeforeQueueing OK");
        } catch (Throwable t) {
            log("!! IMS.interceptKeyBeforeQueueing FAILED: " + t);
        }
    }

    /** Belt and braces: stop the unhandled-key fallback from re-processing the keys we own. */
    private void hookFallback(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(CLS_IMS, lp.classLoader,
                    "dispatchUnhandledKey", android.os.IBinder.class, KeyEvent.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!enabled()) {
                                return; // switch off: intercept nothing at all
                            }
                            KeyEvent e = (KeyEvent) p.args[1];
                            if (e != null && (isMetaRelated(e) || isSearch(e) || isTopRow(e))) {
                                p.setResult(null);
                            }
                        }
                    });
            log("hooked IMS.dispatchUnhandledKey OK");
        } catch (Throwable t) {
            log("!! IMS.dispatchUnhandledKey FAILED: " + t);
        }
    }

    /**
     * The hook that actually frees the key.
     *
     * interceptKeyBeforeDispatching is where the ROM decides what a Meta press MEANS, and on this
     * build the Oplus KeyGestureController turns a long press into the AI gesture. Returning
     * 0 = "deliver it now" hands the event to the focused window and SKIPS the ROM's own body, so
     * that gesture never gets a chance to run. Meta is delivered in both directions on purpose:
     * consuming the UP after the DOWN was delivered would leave the app holding a stuck modifier.
     */
    private void hookDispatching(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(CLS_IMS, lp.classLoader,
                    "interceptKeyBeforeDispatching",
                    android.os.IBinder.class, KeyEvent.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!enabled()) {
                                return; // switch off: intercept nothing at all
                            }
                            KeyEvent e = (KeyEvent) p.args[1];
                            if (e == null) {
                                return;
                            }
                            if (isMetaRelated(e)) {
                                log("DISPATCH meta a=" + e.getAction() + " code=" + e.getKeyCode()
                                        + " scan=" + e.getScanCode() + " -> deliver");
                                p.setResult(DELIVER_NOW);
                                return;
                            }
                            if (isSearch(e)) {
                                p.setResult(CONSUME); // the Fn modifier is never delivered
                            }
                        }
                    });
            log("hooked IMS.interceptKeyBeforeDispatching OK");
        } catch (Throwable t) {
            log("!! IMS.interceptKeyBeforeDispatching FAILED: " + t);
        }
    }

    /**
     * Opt-in second line of defence, default OFF (settings put global kbremap_oplus 1).
     *
     * Skips the ROM's own body for every {@code intercept*Shortcuts} method on the Oplus gesture
     * controller - that is where a Meta long press becomes the AI gesture. The methods are found
     * reflectively and the RETURN TYPE decides how the body is skipped, so an unexpected signature
     * on another firmware degrades to "nothing hooked" instead of a crash.
     */
    private void hookOplusBlock(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> kgc = XposedHelpers.findClassIfExists(CLS_OPLUS_GESTURE, lp.classLoader);
            if (kgc == null) {
                log("oplus block: " + CLS_OPLUS_GESTURE + " not present on this build");
                return;
            }
            int n = 0;
            for (final Method m : kgc.getDeclaredMethods()) {
                String name = m.getName();
                if (!name.startsWith("intercept") || !name.endsWith("Shortcuts")) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (!enabled() || !oplusBlock()) {
                            return;
                        }
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) {
                            p.setResult(Boolean.FALSE);
                        } else if (rt == int.class) {
                            p.setResult(0);
                        } else {
                            p.setResult(null); // void: null means "do not run the body"
                        }
                        log("OPLUS-BLOCK " + m.getName() + " skipped");
                    }
                });
                n++;
            }
            log("oplus block: " + n + " intercept*Shortcuts method(s) armed");
        } catch (Throwable t) {
            log("!! oplus block FAILED: " + t);
        }
    }

    /** Live opt-in read; absent means OFF, because this one is a fallback, not the main switch. */
    private static boolean oplusBlock() {
        try {
            Context ctx = AndroidAppHelper.currentApplication();
            if (ctx == null) {
                return false;
            }
            return "1".equals(android.provider.Settings.Global.getString(
                    ctx.getContentResolver(), Config.KEY_OPLUS_BLOCK));
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ key classification

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

    /**
     * The physical Search key emits hidden KEYCODE_F23 at Linux scan code 193.
     * The keylayout now maps it to FUNCTION, so the scan code is the reliable match.
     */
    private static boolean isSearch(KeyEvent e) {
        if (e.getScanCode() == 193) {
            return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_SEARCH) {
            return true;
        }
        int f = f23();
        return f != -1 && e.getKeyCode() == f;
    }

    /** The Meta key itself, or any key pressed while Meta is held. */
    private static boolean isMetaRelated(KeyEvent e) {
        int k = e.getKeyCode();
        return k == KeyEvent.KEYCODE_META_LEFT || k == KeyEvent.KEYCODE_META_RIGHT
                || e.isMetaPressed();
    }

    /**
     * Top row in the order the keylayout maps it to F1..F12, with the key code each key had
     * BEFORE the keylayout was changed. Used by the Fn-restore path.
     *
     * Scans 183 and 194 (lock and mic) are deliberately absent: their codes resolve correctly but
     * the platform does not act on them - those two actions only fire from real hardware events.
     *
     * Scan 187 IS present: 186 and 187 are ONE physical key (the trackpad toggle) whose scan code
     * depends on trackpad state - 186 when off, 187 when on.
     */
    private static final int[] TOP_ROW_SCANS = {
            224, 225, 186, 187, 99, 165, 164, 163, 113, 114, 115};
    private static final String[] TOP_ROW_ORIGINAL_NAMES = {
            "BRIGHTNESS_DOWN", "BRIGHTNESS_UP", "F16", "F17", "SYSRQ",
            "MEDIA_PREVIOUS", "MEDIA_PLAY_PAUSE", "MEDIA_NEXT",
            "VOLUME_MUTE", "VOLUME_DOWN", "VOLUME_UP"};
    private static final int[] TOP_ROW_ORIGINAL = new int[TOP_ROW_SCANS.length];

    private static boolean isTopRow(KeyEvent e) {
        int s = e.getScanCode();
        for (int scan : TOP_ROW_SCANS) {
            if (scan == s) {
                return true;
            }
        }
        return false;
    }

    /** Several KEYCODE_* constants are @hide in the SDK, so resolve them by name. */
    private static int kc(String name) {
        try {
            return XposedHelpers.getStaticIntField(KeyEvent.class, "KEYCODE_" + name);
        } catch (Throwable t) {
            log("kc(" + name + ") unresolved: " + t);
            return -1;
        }
    }

    private static int originalFor(KeyEvent e) {
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
            log("FN-RESTORE scan=" + src.getScanCode() + " a=" + src.getAction()
                    + " -> injected code=" + keyCode + " ok=" + r);
        } catch (Throwable t) {
            log("FN-RESTORE inject FAILED: " + t);
        }
    }
}
