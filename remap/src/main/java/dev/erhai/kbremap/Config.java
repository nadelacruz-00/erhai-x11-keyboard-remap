package dev.erhai.kbremap;

import android.content.ContentResolver;
import android.provider.Settings;

/**
 * Configuration for the module.
 *
 * Deliberately minimal: ONE master switch, applied at boot. The earlier per-app allow-list was
 * removed because the keymap layer is global and static - a per-app list implied granularity that
 * could only ever be partly true, which made the screen misleading.
 *
 * How the two halves learn about the switch:
 *
 *   - the LSPosed hook runs inside system_server, which CAN read Settings.Global, so it reads
 *     KEY_ENABLED from there;
 *   - the KernelSU side is handled by the app enabling/disabling the module itself, so nothing
 *     needs to read a file at boot.
 *
 * Absent means ENABLED, so an existing install keeps working until the app writes a value.
 */
public final class Config {

    private Config() {
    }

    /** Master switch: "1" the keyboard remap is active, "0" the keyboard is stock. */
    public static final String KEY_ENABLED = "kbremap_enabled";

    /** Diagnostic only: bit 16 logs every key event the hook sees. */
    public static final String KEY_LOG = "kbremap_log";

    /**
     * Written by the hook (which runs inside system_server and can reach Settings) with the current
     * boot id, so the settings screen can tell whether LSPosed actually loaded the module this
     * boot - instead of leaving the user guessing. Enabling a module is a security boundary
     * LSPosed deliberately keeps manual, so this is how the app detects that step.
     */
    public static final String KEY_HOOK_ACTIVE = "kbremap_hook_active";

    /** The KernelSU module that carries the keylayout. */
    public static final String KSU_MODULE_ID = "pad3_keymap";

    /** Settings.Global value granting the settings screen write access, for the warning banner. */
    public static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";

    // ---------------------------------------------------------------- helpers

    public static boolean isEnabled(ContentResolver cr) {
        try {
            String v = Settings.Global.getString(cr, KEY_ENABLED);
            // Absent means enabled: an existing install must keep working until told otherwise.
            return v == null || v.trim().isEmpty() || "1".equals(v.trim());
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setEnabled(ContentResolver cr, boolean enabled) {
        Settings.Global.putString(cr, KEY_ENABLED, enabled ? "1" : "0");
    }

    public static boolean isLogging(ContentResolver cr) {
        try {
            return "1".equals(Settings.Global.getString(cr, KEY_LOG));
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setLogging(ContentResolver cr, boolean on) {
        Settings.Global.putString(cr, KEY_LOG, on ? "1" : "0");
    }
}
