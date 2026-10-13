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
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The module's settings screen.
 *
 * Writes straight to Settings.Global, which the hook in system_server re-reads within a couple of
 * seconds, so every change here is live - no reboot.
 *
 * Writing Settings.Global needs WRITE_SECURE_SETTINGS, which is a privileged permission and can
 * only be granted from a root shell once:
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
    private final List<AppEntry> mShown = new ArrayList<>();
    /**
     * Allow-listed packages that are not currently visible (uninstalled, or hidden by package
     * visibility rules). They are not shown, but they ARE preserved when saving - "hide if not
     * installed", not "forget if not installed".
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
    /** Guards the switch listeners while we are populating them from stored values. */
    private boolean mBinding;

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

        mAdapter = new AppAdapter();
        ListView list = findViewById(R.id.list);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry e = mShown.get(position);
            e.checked = !e.checked;
            saveApps();
            resort();
        });

        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter(s == null ? "" : s.toString());
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

        ((Button) findViewById(R.id.btnDefaults)).setOnClickListener(v -> applyDefaults());
        ((Button) findViewById(R.id.btnKill)).setOnClickListener(v -> {
            mBinding = true;
            mSwMeta.setChecked(false);
            mSwSearch.setChecked(false);
            mSwFn.setChecked(false);
            mSwNormal.setChecked(false);
            mSwLog.setChecked(false);
            mBinding = false;
            mFlags = 0;
            saveFlags();
            updateSummary();
        });

        loadApps();
        loadConfig();
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

        mBinding = true;
        mSwMeta.setChecked((mFlags & Config.META) != 0);
        mSwSearch.setChecked((mFlags & Config.SEARCH) != 0);
        mSwFn.setChecked((mFlags & Config.FN_RESTORE) != 0);
        mSwNormal.setChecked((mFlags & Config.NORMAL_MODE) != 0);
        mSwLog.setChecked((mFlags & Config.LOG_ALL) != 0);
        mBinding = false;

        resort();
        updateSummary();
    }

    private void applyDefaults() {
        mFlags = Config.DEFAULT_FLAGS;
        mBinding = true;
        mSwMeta.setChecked((mFlags & Config.META) != 0);
        mSwSearch.setChecked((mFlags & Config.SEARCH) != 0);
        mSwFn.setChecked((mFlags & Config.FN_RESTORE) != 0);
        mSwNormal.setChecked((mFlags & Config.NORMAL_MODE) != 0);
        mSwLog.setChecked((mFlags & Config.LOG_ALL) != 0);
        mBinding = false;
        saveFlags();
        updateSummary();
    }

    private void saveFlags() {
        if (!mCanWrite) {
            return;
        }
        Config.writeFlags(getContentResolver(), mFlags);
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
        // Preserve entries we cannot see. Without this, incomplete package visibility could
        // silently delete a working entry such as com.termux.x11 the first time anything is
        // toggled.
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
                + getString((mFlags & Config.META) != 0 ? R.string.summary_on : R.string.summary_off)
                + "   ·   " + getString(R.string.switch_search) + ": "
                + getString((mFlags & Config.SEARCH) != 0
                        ? R.string.summary_blocked : R.string.summary_off)
                + "   ·   " + getString(R.string.switch_fn) + ": "
                + getString((mFlags & Config.FN_RESTORE) != 0
                        ? R.string.summary_on : R.string.summary_off)
                + (mCanWrite ? "" : "   ·   (read-only: permission missing)"));
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
        Collections.sort(mAll,
                (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label));
    }

    private void resort() {
        Collections.sort(mAll, (a, b) -> {
            if (a.checked != b.checked) {
                return a.checked ? -1 : 1;   // ticked apps float to the top
            }
            return String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label);
        });
        filter(mSearch.getText() == null ? "" : mSearch.getText().toString());
    }

    private void filter(String q) {
        String needle = q.trim().toLowerCase();
        mShown.clear();
        for (AppEntry e : mAll) {
            if (needle.isEmpty()
                    || e.label.toLowerCase().contains(needle)
                    || e.pkg.toLowerCase().contains(needle)) {
                mShown.add(e);
            }
        }
        mAdapter.notifyDataSetChanged();

        int checked = 0;
        for (AppEntry e : mAll) {
            if (e.checked) {
                checked++;
            }
        }
        mAppsCount.setText(getString(R.string.apps_checked, checked, mAll.size()));
        mEmpty.setVisibility(mShown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mShown.size();
        }

        @Override
        public Object getItem(int position) {
            return mShown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(SettingsActivity.this)
                        .inflate(R.layout.row_app, parent, false);
            }
            AppEntry e = mShown.get(position);
            ImageView icon = v.findViewById(R.id.icon);
            TextView label = v.findViewById(R.id.label);
            TextView pkg = v.findViewById(R.id.pkg);
            CheckBox check = v.findViewById(R.id.check);

            if (e.icon != null) {
                icon.setImageDrawable(e.icon);
            } else {
                icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            label.setText(e.label);
            pkg.setText(e.pkg);
            check.setChecked(e.checked);
            return v;
        }
    }
}
