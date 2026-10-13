package com.minis.kbremap;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityDelegateCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The module's settings screen - Material 3 Expressive.
 *
 * Writes straight to Settings.Global, which the hook in system_server re-reads within a couple of
 * seconds, so every change here is live: no Save button, and none is needed.
 *
 * Writing Settings.Global needs WRITE_SECURE_SETTINGS, a privileged permission that can only be
 * granted from a root shell once:
 *
 *     pm grant com.minis.kbremap android.permission.WRITE_SECURE_SETTINGS
 */
public class SettingsActivity extends AppCompatActivity {

    private static final class AppEntry {
        final String label;
        final String pkg;
        final Drawable icon;
        boolean checked;

        AppEntry(String label, String pkg, Drawable icon) {
            this.label = label;
            this.pkg = pkg;
            this.icon = icon;
        }
    }

    private final List<AppEntry> mAll = new ArrayList<>();
    /** The list as currently rendered - kept in sync through DiffUtil so changes animate. */
    private final List<AppEntry> mShown = new ArrayList<>();
    /**
     * Allow-listed packages that are not currently visible (uninstalled, or hidden by package
     * visibility rules). Not shown, but preserved on save - "hide if not installed", not
     * "forget if not installed".
     */
    private final Set<String> mHidden = new HashSet<>();

    private AppAdapter mAdapter;
    private EditText mSearch;
    private TextView mSummary;
    private TextView mAppsCount;
    private TextView mEmpty;
    private Switch mSwMeta;
    private Switch mSwSearch;
    private Switch mSwFn;
    private Switch mSwNormal;
    private Switch mSwLog;

    private int mFlags;
    private boolean mCanWrite;
    /** Guards the switch listeners while populating them from stored values. */
    private boolean mBinding;
    /** The entrance animation should play once, not on every filter keystroke. */
    private boolean mPlayedEntrance;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mSummary = findViewById(R.id.summary);
        mAppsCount = findViewById(R.id.appsCount);
        mEmpty = findViewById(R.id.empty);
        mSearch = findViewById(R.id.search);
        mSwMeta = findViewById(R.id.swMeta);
        mSwSearch = findViewById(R.id.swSearch);
        mSwFn = findViewById(R.id.swFn);
        mSwNormal = findViewById(R.id.swNormal);
        mSwLog = findViewById(R.id.swLog);

        View permWarning = findViewById(R.id.permWarning);
        mCanWrite = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        if (!mCanWrite) {
            permWarning.setVisibility(View.VISIBLE);
        }

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new AppAdapter();
        list.setAdapter(mAdapter);

        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                rebuild();
            }
        });

        CompoundButton.OnCheckedChangeListener flags = (button, checked) -> {
            if (mBinding) {
                return;
            }
            int bit = button.getId() == R.id.swMeta ? Config.META
                    : button.getId() == R.id.swSearch ? Config.SEARCH
                    : button.getId() == R.id.swFn ? Config.FN_RESTORE
                    : button.getId() == R.id.swNormal ? Config.NORMAL_MODE
                    : Config.LOG_ALL;
            if (checked) {
                mFlags |= bit;
            } else {
                mFlags &= ~bit;
            }
            saveFlags();
            updateSummary();
        };
        mSwMeta.setOnCheckedChangeListener(flags);
        mSwSearch.setOnCheckedChangeListener(flags);
        mSwFn.setOnCheckedChangeListener(flags);
        mSwNormal.setOnCheckedChangeListener(flags);
        mSwLog.setOnCheckedChangeListener(flags);

        ((MaterialButton) findViewById(R.id.btnDefaults)).setOnClickListener(v -> {
            applyFlags(Config.DEFAULT_FLAGS);
            toast(R.string.defaults_done);
        });

        // Destructive and global, so it asks first, and Cancel is the safe default.
        ((MaterialButton) findViewById(R.id.btnKill)).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.kill_switch_title)
                        .setMessage(R.string.kill_switch_body)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.kill_switch_confirm,
                                (d, w) -> {
                                    applyFlags(0);
                                    toast(R.string.kill_switch_done);
                                })
                        .show());

        loadApps();
        loadConfig();
        rebuild();
    }

    private void toast(int res) {
        Snackbar.make(findViewById(android.R.id.content), res, Snackbar.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ config

    private void loadConfig() {
        mFlags = Config.readFlags(getContentResolver());

        Set<String> allowed = new HashSet<>();
        for (String p : Config.readAppsRaw(getContentResolver()).split(",")) {
            p = p.trim();
            if (!p.isEmpty()) {
                allowed.add(p);
            }
        }

        mHidden.clear();
        Set<String> visible = new HashSet<>();
        for (AppEntry e : mAll) {
            visible.add(e.pkg);
            e.checked = allowed.contains(e.pkg);
        }
        for (String p : allowed) {
            if (!visible.contains(p)) {
                mHidden.add(p);
            }
        }

        bindSwitches();
        updateSummary();
    }

    /** Applies a whole flag set at once (Restore defaults / Disable everything). */
    private void applyFlags(int flags) {
        mFlags = flags;
        bindSwitches();
        saveFlags();
        updateSummary();
    }

    private void bindSwitches() {
        mBinding = true;
        mSwMeta.setChecked((mFlags & Config.META) != 0);
        mSwSearch.setChecked((mFlags & Config.SEARCH) != 0);
        mSwFn.setChecked((mFlags & Config.FN_RESTORE) != 0);
        mSwNormal.setChecked((mFlags & Config.NORMAL_MODE) != 0);
        mSwLog.setChecked((mFlags & Config.LOG_ALL) != 0);
        mBinding = false;
    }

    private void saveFlags() {
        if (mCanWrite) {
            Config.writeFlags(getContentResolver(), mFlags);
        }
    }

    /** Writes the ticked packages, plus any allow-listed package we cannot see. */
    private void saveApps() {
        if (!mCanWrite) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (AppEntry e : mAll) {
            if (e.checked) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(e.pkg);
            }
        }
        // Preserve entries we cannot see, so incomplete package visibility can never silently
        // delete a working entry such as com.termux.x11.
        for (String p : mHidden) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p);
        }
        Config.writeAppsRaw(getContentResolver(), sb.toString());
    }

    private void updateSummary() {
        mSummary.setText(getString(R.string.switch_meta) + ": "
                + getString(onOff((mFlags & Config.META) != 0))
                + "   ·   " + getString(R.string.switch_search) + ": "
                + getString((mFlags & Config.SEARCH) != 0 ? R.string.summary_blocked : R.string.summary_off)
                + "   ·   " + getString(R.string.switch_fn) + ": "
                + getString(onOff((mFlags & Config.FN_RESTORE) != 0))
                + (mCanWrite ? "" : "   ·   " + getString(R.string.read_only)));
        // A quiet spring on the line that just changed, so a live edit is visibly acknowledged.
        Motion.emphasise(mSummary);
    }

    private static int onOff(boolean b) {
        return b ? R.string.summary_on : R.string.summary_off;
    }

    // ------------------------------------------------------------------ app list

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(main, 0);

        Set<String> seen = new HashSet<>();
        mAll.clear();
        for (ResolveInfo ri : resolved) {
            if (ri.activityInfo == null || ri.activityInfo.applicationInfo == null) {
                continue;
            }
            String pkg = ri.activityInfo.applicationInfo.packageName;
            if (pkg == null || !seen.add(pkg)) {
                continue;
            }
            CharSequence label = ri.loadLabel(pm);
            Drawable icon;
            try {
                icon = ri.loadIcon(pm);
            } catch (Throwable t) {
                icon = null;
            }
            mAll.add(new AppEntry(label == null ? pkg : label.toString(), pkg, icon));
        }
    }

    /**
     * Re-sorts, re-filters and animates the delta. Uses DiffUtil rather than notifyDataSetChanged
     * so that a newly ticked row visibly MOVES to the top instead of the list blinking.
     */
    private void rebuild() {
        String needle = mSearch.getText() == null
                ? "" : mSearch.getText().toString().trim().toLowerCase();

        Collections.sort(mAll, (a, b) -> {
            if (a.checked != b.checked) {
                return a.checked ? -1 : 1;   // ticked apps float to the top
            }
            return String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label);
        });

        final List<AppEntry> next = new ArrayList<>();
        for (AppEntry e : mAll) {
            if (needle.isEmpty()
                    || e.label.toLowerCase().contains(needle)
                    || e.pkg.toLowerCase().contains(needle)) {
                next.add(e);
            }
        }

        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return mShown.size();
            }

            @Override
            public int getNewListSize() {
                return next.size();
            }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                return mShown.get(o).pkg.equals(next.get(n).pkg);
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                AppEntry a = mShown.get(o);
                AppEntry b = next.get(n);
                return a.checked == b.checked && a.label.equals(b.label);
            }
        });

        mShown.clear();
        mShown.addAll(next);
        diff.dispatchUpdatesTo(mAdapter);

        updateCount(needle);
    }

    private void updateCount(String needle) {
        int checked = 0;
        for (AppEntry e : mAll) {
            if (e.checked) {
                checked++;
            }
        }
        mAppsCount.setText(getString(R.string.apps_checked, checked, mAll.size()));

        if (!mShown.isEmpty()) {
            mEmpty.setVisibility(View.GONE);
        } else {
            mEmpty.setVisibility(View.VISIBLE);
            // Distinguish "nothing matched your search" from "no apps at all".
            mEmpty.setText(mAll.isEmpty()
                    ? getString(R.string.no_apps_installed)
                    : getString(R.string.no_apps_match, needle));
        }
    }

    // ------------------------------------------------------------------ adapter

    private final class AppAdapter extends RecyclerView.Adapter<AppVH> {

        @Override
        public AppVH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(SettingsActivity.this)
                    .inflate(R.layout.row_app, parent, false);
            return new AppVH(v);
        }

        @Override
        public void onBindViewHolder(AppVH h, int position) {
            AppEntry e = mShown.get(position);
            if (e.icon != null) {
                h.icon.setImageDrawable(e.icon);
            } else {
                h.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            h.label.setText(e.label);
            h.pkg.setText(e.pkg);
            h.check.setChecked(e.checked);
            h.position = position;

            if (!mPlayedEntrance) {
                if (position < 12) {
                    Motion.enterRow(h.itemView, position);
                } else {
                    // The first screen has finished binding - stop animating so that filtering and
                    // scrolling do not flicker.
                    mPlayedEntrance = true;
                }
            }
        }

        @Override
        public int getItemCount() {
            return mShown.size();
        }
    }

    private final class AppVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView pkg;
        final CheckBox check;
        int position = RecyclerView.NO_POSITION;

        AppVH(View v) {
            super(v);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            pkg = v.findViewById(R.id.pkg);
            check = v.findViewById(R.id.check);

            v.setFocusable(true);
            v.setOnClickListener(view -> {
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) {
                    return;
                }
                AppEntry e = mShown.get(pos);
                e.checked = !e.checked;
                Motion.press(itemView);
                saveApps();
                rebuild();
            });

            // One accessible control per row: the row carries the checkable state, and the
            // checkbox is hidden from accessibility in the layout so TalkBack announces each app
            // once rather than twice.
            ViewCompat.setAccessibilityDelegate(v, new AccessibilityDelegateCompat() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host,
                                                              AccessibilityNodeInfoCompat info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    int pos = getBindingAdapterPosition();
                    boolean checked = pos != RecyclerView.NO_POSITION
                            && pos < mShown.size() && mShown.get(pos).checked;
                    info.setCheckable(true);
                    info.setChecked(checked);
                    info.setClassName("android.widget.CheckBox");
                }
            });
        }
    }
}
