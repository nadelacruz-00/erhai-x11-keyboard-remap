package com.minis.kbremap;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.function.IntConsumer;

/**
 * One master switch.
 *
 * The change is applied by a reboot, which is deliberate: it lets "off" be a genuine no-op. When
 * the switch is off, the LSPosed hook installs nothing and the KernelSU module that carries the
 * keylayout is disabled, so no behaviour has to be reconstructed or un-done.
 *
 * Two halves, two mechanisms:
 *   - the routing half reads Settings.Global, which the app can write with WRITE_SECURE_SETTINGS;
 *   - the keymap half is handled by enabling/disabling the KernelSU module, which needs root.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS = "kbremap";
    /** Boot id recorded when the switch was last changed, to know whether a reboot has applied it. */
    private static final String PREF_PENDING_BOOT = "pending_boot_id";
    private static final String BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id";

    private Switch mSwEnabled;
    private TextView mStateTitle;
    private TextView mStateDetail;
    private TextView mPending;
    private TextView mRootNote;

    private boolean mCanWrite;
    /** Guards the switch listener while populating it from stored state. */
    private boolean mBinding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mStateTitle = findViewById(R.id.stateTitle);
        mStateDetail = findViewById(R.id.stateDetail);
        mPending = findViewById(R.id.pending);
        mRootNote = findViewById(R.id.rootNote);
        mSwEnabled = findViewById(R.id.swEnabled);

        mCanWrite = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        if (!mCanWrite) {
            findViewById(R.id.permWarning).setVisibility(View.VISIBLE);
        }

        mSwEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (!mBinding) {
                apply(checked);
            }
        });

        ((MaterialButton) findViewById(R.id.btnReboot)).setOnClickListener(v -> reboot());
    }

    @Override
    protected void onResume() {
        super.onResume();
        bind();
    }

    // ------------------------------------------------------------------ state

    private void bind() {
        boolean on = Config.isEnabled(getContentResolver());
        mBinding = true;
        mSwEnabled.setChecked(on);
        mBinding = false;
        render(on);
    }

    private void render(boolean on) {
        mStateTitle.setText(on ? R.string.state_on : R.string.state_off);
        mStateDetail.setText(on ? R.string.detail_on : R.string.detail_off);
        mPending.setVisibility(isPending() ? View.VISIBLE : View.GONE);
    }

    /**
     * True when the switch was changed during this boot and so is not in effect yet. Detected by
     * comparing the boot id captured at change time with the current one - no guessing, and it
     * clears itself the moment a reboot happens.
     */
    private boolean isPending() {
        String recorded = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_PENDING_BOOT, null);
        if (recorded == null) {
            return false;
        }
        String now = bootId();
        if (now != null && now.equals(recorded)) {
            return true;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_PENDING_BOOT).apply();
        return false;
    }

    // ------------------------------------------------------------------ actions

    private void apply(boolean on) {
        if (!mCanWrite) {
            return;
        }

        // The routing half: written straight to Settings, read by the hook at load.
        Config.setEnabled(getContentResolver(), on);

        String id = bootId();
        if (id != null) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_PENDING_BOOT, id).apply();
        }
        render(on);
        Motion.emphasise(mStateTitle);

        // The keymap half: enable/disable the KernelSU module. Root is needed for this only, and
        // only for the convenience of not having to open the KernelSU manager.
        runRootAsync("ksud module " + (on ? "enable " : "disable ") + Config.KSU_MODULE_ID, rc -> {
            mRootNote.setVisibility(rc == 0 ? View.GONE : View.VISIBLE);
        });

        toast(R.string.pending);
    }

    private void reboot() {
        toast(R.string.rebooting);
        runRootAsync("reboot", rc -> {
            if (rc != 0) {
                mRootNote.setVisibility(View.VISIBLE);
            }
        });
    }

    private void toast(int res) {
        Snackbar.make(findViewById(android.R.id.content), res, Snackbar.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ root

    /**
     * Runs a command as root off the main thread - KernelSU's first prompt blocks until the user
     * answers, which must not freeze the screen.
     */
    private void runRootAsync(String cmd, IntConsumer onDone) {
        new Thread(() -> {
            int rc = runRoot(cmd);
            runOnUiThread(() -> onDone.accept(rc));
        }, "kbremap-root").start();
    }

    private int runRoot(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();
            return p.exitValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    private String bootId() {
        try (BufferedReader r = new BufferedReader(new FileReader(BOOT_ID_PATH))) {
            String line = r.readLine();
            return line == null ? null : line.trim();
        } catch (Throwable t) {
            return null;
        }
    }
}
