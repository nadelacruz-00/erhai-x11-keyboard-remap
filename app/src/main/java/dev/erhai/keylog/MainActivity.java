package dev.erhai.keylog;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * KeyLog — shows exactly what Android's *application layer* receives for each key press.
 *
 * Purpose: prove whether a key reaches apps at all, and with which keyCode / scanCode /
 * metaState. This settles the Meta question before any LSPosed hook is written.
 *
 * Everything is observed in dispatchKeyEvent() and then handed to super, so this app
 * does not swallow anything.
 */
public class MainActivity extends Activity {

    private static final int MAX_LINES = 300;

    private final Deque<String> mLines = new ArrayDeque<>();
    private TextView mLog;
    private TextView mLast;
    private TextView mStats;
    private int mCount;
    private final long mStart = System.currentTimeMillis();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mLog = findViewById(R.id.log);
        mLast = findViewById(R.id.last);
        mStats = findViewById(R.id.stats);
        mLog.setMovementMethod(new ScrollingMovementMethod());

        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            mLines.clear();
            mCount = 0;
            render();
            mLast.setText(R.string.idle);
        });

        findViewById(R.id.btn_copy).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("keylog", String.join("\n", mLines)));
                Toast.makeText(this, "Copied " + mLines.size() + " lines", Toast.LENGTH_SHORT).show();
            }
        });

        mLast.setText(R.string.idle);
        render();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        record(e);
        return super.dispatchKeyEvent(e);
    }

    private void record(KeyEvent e) {
        mCount++;
        mLines.addFirst(describe(e));
        while (mLines.size() > MAX_LINES) {
            mLines.removeLast();
        }
        mLast.setText(describe(e));
        render();
    }

    private void render() {
        mLog.setText(String.join("\n\n", mLines));
        long secs = (System.currentTimeMillis() - mStart) / 1000;
        mStats.setText(String.format(Locale.US, "%d events  |  %d s  |  newest first", mCount, secs));
    }

    private String describe(KeyEvent e) {
        String action = e.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP  ";
        String sys = switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> "META";
            case KeyEvent.KEYCODE_ESCAPE -> "ESCAPE";
            case KeyEvent.KEYCODE_BACK -> "BACK";
            case KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3,
                 KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6,
                 KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8, KeyEvent.KEYCODE_F9,
                 KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12 -> "F-KEY";
            default -> "-";
        };

        String devName = "?";
        int devId = e.getDeviceId();
        InputDevice dev = InputDevice.getDevice(devId);
        if (dev != null && dev.getName() != null) devName = dev.getName();

        StringBuilder m = new StringBuilder();
        if (e.isMetaPressed()) m.append("META ");
        if (e.isCtrlPressed()) m.append("CTRL ");
        if (e.isAltPressed()) m.append("ALT ");
        if (e.isShiftPressed()) m.append("SHIFT ");
        if (e.isSymPressed()) m.append("SYM ");
        if (e.isFunctionPressed()) m.append("FN ");
        if (e.isCapsLockOn()) m.append("CAPS ");
        if (e.isNumLockOn()) m.append("NUM ");
        if (e.isScrollLockOn()) m.append("SCROLL ");
        if (m.length() == 0) m.append("none");

        String flags = flagString(e.getFlags());

        return String.format(Locale.US,
                "%s  %s   %s\n"
                        + "   keyCode=%d   scanCode=%d   repeat=%d   source=0x%08X\n"
                        + "   metaState=0x%08X  [%s]\n"
                        + "   flags=0x%08X  [%s]\n"
                        + "   deviceId=%d  \"%s\"",
                action, KeyEvent.keyCodeToString(e.getKeyCode()), sys,
                e.getKeyCode(), e.getScanCode(), e.getRepeatCount(), e.getSource(),
                e.getMetaState(), m.toString(),
                e.getFlags(), flags,
                devId, devName);
    }

    private String flagString(int flags) {
        StringBuilder b = new StringBuilder();
        if ((flags & KeyEvent.FLAG_SOFT_KEYBOARD) != 0) b.append("SOFT ");
        if ((flags & KeyEvent.FLAG_FROM_SYSTEM) != 0) b.append("FROM_SYSTEM ");
        if ((flags & KeyEvent.FLAG_CANCELED) != 0) b.append("CANCELED ");
        if ((flags & KeyEvent.FLAG_LONG_PRESS) != 0) b.append("LONG_PRESS ");
        if ((flags & KeyEvent.FLAG_TRACKING) != 0) b.append("TRACKING ");
        if ((flags & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) b.append("VIRTUAL_HARD ");
        if (b.length() == 0) b.append("0");
        return b.toString().trim();
    }
}
