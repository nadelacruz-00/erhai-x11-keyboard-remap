package dev.erhai.kbremap;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * One master switch.
 *
 * The change is applied by a reboot, which is deliberate: it lets "off" be a genuine no-op. When
 * the switch is off, the LSPosed hook does nothing and the KernelSU module that carries the
 * keylayout is disabled, so no behaviour has to be reconstructed or un-done.
 *
 * A fresh install sets itself up. The KernelSU module ships inside this APK
 * (assets/erhai-keymap.zip) and is installed on first launch once root is granted, so the user
 * never has to hunt for a zip file. This app IS the LSPosed module, so that half is installed by
 * installing the APK - it only needs enabling once in the LSPosed manager, which is a security
 * boundary LSPosed deliberately does not let apps cross on their own.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS = "erhai";
    private static final String PREF_PENDING_BOOT = "pending_boot_id";
    private static final String BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id";
    private static final String ASSET_MODULE = "erhai-keymap.zip";

    private Switch mSwEnabled;
    private TextView mStateTitle;
    private TextView mStateDetail;
    private TextView mPending;
    private TextView mRootNote;
    private TextView mHookNote;

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
        mHookNote = findViewById(R.id.hookNote);
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

        ensureKsuModule();
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
        mHookNote.setVisibility(hookActive() ? View.GONE : View.VISIBLE);
    }

    /**
     * Has the hook reported itself during THIS boot?
     *
     * The hook runs inside system_server and stamps the current boot id into Settings when it
     * loads. If the marker is missing, or carries an older boot id, LSPosed has not loaded the
     * module yet - which is the one setup step that cannot be automated, because LSPosed keeps
     * enabling a module deliberately manual.
     */
    private boolean hookActive() {
        try {
            String marker = Settings.Global.getString(getContentResolver(),
                    Config.KEY_HOOK_ACTIVE);
            String now = bootId();
            return marker != null && now != null && marker.equals(now);
        } catch (Throwable t) {
            return false;
        }
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

    private void markPending() {
        String id = bootId();
        if (id != null) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_PENDING_BOOT, id).apply();
        }
    }

    // ------------------------------------------------------------------ actions

    private void apply(boolean on) {
        if (!mCanWrite) {
            return;
        }

        // The routing half: written straight to Settings, read by the hook.
        Config.setEnabled(getContentResolver(), on);

        markPending();
        render(on);
        Motion.emphasise(mStateTitle);

        // The keymap half: enable/disable the KernelSU module. Root is needed for this only, and
        // only so the user does not have to open the KernelSU manager themselves.
        runRootAsync("ksud module " + (on ? "enable " : "disable ") + Config.KSU_MODULE_ID,
                r -> mRootNote.setVisibility(r.ok() ? View.GONE : View.VISIBLE));

        toast(R.string.pending);
    }

    private void reboot() {
        toast(R.string.rebooting);
        runRootAsync("reboot", r -> {
            if (!r.ok()) {
                mRootNote.setVisibility(View.VISIBLE);
            }
        });
    }

    /**
     * Installs the bundled KernelSU module if it is not present yet.
     *
     * Without this the user would have to find the module zip and install it by hand, which is
     * exactly the fiddly step this app exists to remove. Silently does nothing when the module is
     * already there, or when root has not been granted.
     */
    private void ensureKsuModule() {
        runRootAsync("test -d /data/adb/modules/" + Config.KSU_MODULE_ID
                        + " && echo present || echo missing",
                r -> {
                    if (r.out.contains("present")) {
                        return; // already installed
                    }
                    if (!r.ok() || r.out.isEmpty()) {
                        return; // no root yet; the note stays hidden until something actually fails
                    }
                    installBundledModule();
                });
    }

    private void installBundledModule() {
        final File zip;
        try {
            zip = new File(getCacheDir(), ASSET_MODULE);
            try (InputStream in = getAssets().open(ASSET_MODULE);
                 OutputStream out = new FileOutputStream(zip)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        } catch (Throwable t) {
            mRootNote.setVisibility(View.VISIBLE);
            return;
        }

        runRootAsync("ksud module install " + zip.getAbsolutePath(), r -> {
            if (r.ok()) {
                markPending();
                render(Config.isEnabled(getContentResolver()));
                toast(R.string.ksu_installed);
            } else {
                mRootNote.setVisibility(View.VISIBLE);
            }
        });
    }

    private void toast(int res) {
        Snackbar.make(findViewById(android.R.id.content), res, Snackbar.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ root

    /** Exit code plus whatever the command printed. */
    private static final class RootResult {
        final int rc;
        final String out;

        RootResult(int rc, String out) {
            this.rc = rc;
            this.out = out;
        }

        boolean ok() {
            return rc == 0;
        }
    }

    /**
     * Runs a command as root off the main thread - the first KernelSU prompt blocks until the user
     * answers, which must not freeze the screen.
     */
    private void runRootAsync(String cmd, Consumer<RootResult> onDone) {
        new Thread(() -> {
            RootResult r = runRoot(cmd);
            runOnUiThread(() -> onDone.accept(r));
        }, "erhai-root").start();
    }

    private RootResult runRoot(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r =
                         new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor();
            return new RootResult(p.exitValue(), sb.toString().trim());
        } catch (Throwable t) {
            return new RootResult(-1, String.valueOf(t));
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
