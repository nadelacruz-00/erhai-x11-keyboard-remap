package com.minis.kbremap;

import android.content.ContentResolver;
import android.provider.Settings;

/**
 * Canonical configuration for the module: the flag bits, the Settings keys, and read/write
 * helpers. The hook (running in system_server) and the settings Activity (running in the app
 * process) both use this class, so there is exactly one definition of every bit.
 */
public final class Config {

    private Config() {
    }

    // ---------------------------------------------------------------- behaviour bits
    // Stored as an integer in Settings.Global["kbremap_flags"].

    /** Route the Meta/Super key to the allow-listed apps instead of Oplus's gesture. */
    public static final int META = 1;
    /** Drop the Search key everywhere. */
    public static final int SEARCH = 2;
    /** Legacy belt-and-braces: also force ACTION_PASS_TO_USER at PhoneWindowManager. */
    public static final int LEGACY_PWM = 4;
    /** Diagnostic: log every key event the hook sees. */
    public static final int LOG_ALL = 16;
    /** Fn (the Search key) re-injects the top row's original key code. */
    public static final int FN_RESTORE = 256;
    /**
     * Apps NOT in the list get the keyboard's ORIGINAL behaviour back: the remapped keys are
     * swallowed and their original key codes re-injected (Esc -> BACK, top row -> brightness /
     * media / volume, trackpad key -> F16 / F17).
     */
    public static final int NORMAL_MODE = 512;

    public static final int DEFAULT_FLAGS = META | SEARCH | FN_RESTORE | NORMAL_MODE;

    // ---------------------------------------------------------------- keys

    public static final String KEY_FLAGS = "kbremap_flags";
    public static final String KEY_APPS = "kbremap_apps";

    /** Second config source read by the hook (union with the Settings value). */
    public static final String CONFIG_FILE = "/data/system/minis_kbremap/apps.conf";

    // ---------------------------------------------------------------- helpers

    public static int readFlags(ContentResolver cr) {
        try {
            String v = Settings.Global.getString(cr, KEY_FLAGS);
            if (v == null || v.trim().isEmpty()) {
                return DEFAULT_FLAGS;
            }
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return DEFAULT_FLAGS;
        }
    }

    public static void writeFlags(ContentResolver cr, int flags) {
        Settings.Global.putString(cr, KEY_FLAGS, Integer.toString(flags));
    }

    /** Raw comma-separated value, or "" when unset. */
    public static String readAppsRaw(ContentResolver cr) {
        try {
            String v = Settings.Global.getString(cr, KEY_APPS);
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    public static void writeAppsRaw(ContentResolver cr, String csv) {
        Settings.Global.putString(cr, KEY_APPS, csv);
    }
}
