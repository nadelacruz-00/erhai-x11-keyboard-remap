#!/usr/bin/env python3
"""Build the KernelSU keymap module (Layer A).

Layer A is the STATIC keymap half of the project. It exists because a Java hook cannot
change a keycode: the native InputDispatcher dispatches its own copy of the event, so
Esc->Escape and top-row->F1..F12 have to happen in the keylayout that InputReader parses.

It starts from the device's OWN Generic.kl (captured from /system/usr/keylayout/Generic.kl)
and is a FULL replacement - never a partial patch. That matters: one .kl file serves all
four 22d9:3869 sub-devices (keyboard, consumer control, touchpad), so a slim file would
break the siblings.

Every replacement is asserted to match exactly once. If the firmware ever changes the
baseline, this fails loudly instead of writing a subtly wrong keymap.
"""
import os

# Paths resolve relative to this file, so the tool runs from anywhere.
_REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
import difflib
import re
import shutil
import subprocess
import sys
import zipfile

SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "reference", "Generic.kl")
OUTDIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "ksu-keymap")
KL_NAME = "Vendor_22d9_Product_3869.kl"
OUT_KL = os.path.join(OUTDIR, "system/usr/keylayout", KL_NAME)

# scancode -> (expected current keycode name, new keycode name)
EDITS = {
    158: ("BACK", "ESCAPE"),        # goal 2: physical Esc becomes a real Escape
    193: ("F23", "FUNCTION"),       # goal 3: Search becomes the Fn modifier
    # goal 4: the whole top row becomes F1..F12, left to right (positions 2..13)
    224: ("BRIGHTNESS_DOWN", "F1"),
    225: ("BRIGHTNESS_UP", "F2"),
    183: ("F13", "F3"),
    # 186 and 187 are ONE physical key - the trackpad toggle - whose scan code depends on the
    # trackpad state: 186 when the trackpad is OFF, 187 when it is ON (observed on the device).
    # Mapping both makes the key consistently F4. The toggle itself survives this because it
    # does not depend on the F16/F17 key code (proved by the trackpad still turning back on
    # after 186 was already remapped away from F16).
    186: ("F16", "F4"),
    187: ("F17", "F4"),
    99: ("SYSRQ", "F5"),
    194: ("F24", "F6"),
    165: ("MEDIA_PREVIOUS", "F7"),
    164: ("MEDIA_PLAY_PAUSE", "F8"),
    163: ("MEDIA_NEXT", "F9"),
    113: ("VOLUME_MUTE", "F10"),
    114: ("VOLUME_DOWN", "F11"),
    115: ("VOLUME_UP", "F12"),
}

# HID usage -> (expected current keycode name, new keycode name).
#
# Scan codes are NOT the whole story on this keyboard. Both 22d9:3869 devices report HID usages
# (`EV_MSC MSC_SCAN` on every press) and Android resolves a key that carries a usage through the
# USAGE map BEFORE the scan-code map (`KeyLayoutMap::getKey()` checks usageCode first), so a
# `key <scan> X` line is dead weight for any key that also has a `key usage <usage> Y` line.
#
# The stock file carries usage lines for the brightness pair only. Remapping the scan codes to
# F1/F2 while leaving those alone left exactly those two keys still changing the brightness -
# measured on the device - which is what this table fixes. The FALLBACK_USAGE_MAPPING flag is
# dropped: a fallback-flagged entry is the low-priority one, and we want these to win outright.
USAGE_EDITS = {
    0x0C006F: ("BRIGHTNESS_UP", "F2"),
    0x0C0070: ("BRIGHTNESS_DOWN", "F1"),
}

MODULE_PROP = """id=pad3_keymap
name=Erhai X-11 Keyboard Remap - keymap
version=1.2.0
versionCode=120
author=Erhai X-11
description=Static keylayout for the OnePlus Pad 3 keyboard: physical Esc->ESCAPE, Search->FUNCTION, top row->F1..F12. Companion to the LSPosed half of Erhai X-11 Keyboard Remap. Disabling this module reverts the keyboard to stock.
"""

README = """# Layer A - keymap (KernelSU)

Overlays a device-specific keylayout for the Pad 3 keyboard:

    system/usr/keylayout/Vendor_22d9_Product_3869.kl

Changes, all relative to the device's own `Generic.kl`:

| scan | was | now | why |
|---|---|---|---|
| 158 | BACK | ESCAPE | the physical Esc key becomes a real Escape |
| 193 | F23 | FUNCTION | the Search key becomes the Fn modifier |
| 224 225 183 186 187 99 194 165 164 163 113 114 115 | brightness / F13 / F16 / F17 / SYSRQ / F24 / media / volume | F1..F12 | the top row becomes the function row |

Note: `186` and `187` are ONE physical key (the trackpad toggle) whose scan code depends on the
trackpad state - `186` when the trackpad is off, `187` when it is on. Mapping both keeps that key
consistently `F4`. The toggle still works because it does not depend on the F16/F17 key code
(proved: the trackpad still turned back on after `186` had already been remapped away from `F16`).

Also note: the top row's lock key (`183`, was `F13`) and mic key (`194`, was `F24`) keep their
`F3`/`F6` mappings by choice. Their original actions are triggered by key codes that the system
only honours from real hardware events, so they are not recoverable via injection.

`Del` (scan 111) is deliberately left alone.

## HID usages

Android resolves a key that carries a HID usage through the usage map *before* the scan-code map
(`KeyLayoutMap::getKey()` checks `usageCode` first), and this keyboard reports usages from both of
its 22d9:3869 devices (`EV_MSC MSC_SCAN` on every press). So the brightness pair also has to be
remapped where the stock file declares it by usage:

| usage | was | now |
|---|---|---|
| `0x0c006F` | `BRIGHTNESS_UP` | `F2` |
| `0x0c0070` | `BRIGHTNESS_DOWN` | `F1` |

The `FALLBACK_USAGE_MAPPING` flag is dropped, which makes them *primary* usage mappings - a
fallback-flagged entry is the low-priority one. Without this, `key 224 F1` / `key 225 F2` are
never consulted and the first two top-row keys keep changing the brightness (measured on device).

## Why this layer exists

A Java/LSPosed hook cannot change a keycode - the native InputDispatcher dispatches its own
copy of the event, so a Java-side rewrite never reaches the app. Keycode changes must happen
in the keylayout that InputReader parses. This is exactly why `Drun555/oneplus-meta-gate`
patches `key 158 BACK` for Escape while doing Meta routing in code.

## Notes

- The file is a FULL replacement of Generic.kl, not a patch. One .kl serves all four
  sub-devices that share VID:PID 22d9:3869, so a slim file would break the touchpad.
- No `.kcm` is shipped on purpose: your device's Generic.kcm has zero `fn:` entries, so the
  Fn-fallback mechanism is unproven here.
- Restoring a top-row key's original action while Fn is held is done by the LSPosed half, not
  here, and that half has to track the Fn key itself: Android keeps modifier state **per input
  device**, Fn lives on the keyboard device while the brightness and volume keys are reported by
  the consumer-control device, so `isFunctionPressed()` is never true on those events.
- Rollback: disable or delete the module in KernelSU and reboot. No partition is written.
"""


def main():
    src = open(SRC, "r", errors="replace").read()
    lines = src.split("\n")

    # Build scancode and HID usage -> line index maps, asserting single occurrence.
    index = {}
    uindex = {}
    for i, line in enumerate(lines):
        s = line.split("#", 1)[0].strip()
        if not s.startswith("key "):
            continue
        parts = s.split()
        if len(parts) < 3:
            continue
        if parts[1] == "usage":
            try:
                usage = int(parts[2], 0)
            except ValueError:
                continue
            uindex.setdefault(usage, []).append(i)
            continue
        try:
            code = int(parts[1], 0)
        except ValueError:
            continue
        index.setdefault(code, []).append(i)

    problems = []
    for code, (was, now) in EDITS.items():
        idxs = index.get(code, [])
        if len(idxs) != 1:
            problems.append(f"scan {code}: expected exactly 1 baseline line, found {len(idxs)}")
            continue
        line = lines[idxs[0]]
        if not re.match(r"^\s*key\s+%d\s+%s\s*$" % (code, re.escape(was)), line):
            problems.append(f"scan {code}: baseline is {line.strip()!r}, expected key {code} {was}")

    for usage, (was, now) in USAGE_EDITS.items():
        idxs = uindex.get(usage, [])
        if len(idxs) != 1:
            problems.append(f"usage 0x{usage:06X}: expected exactly 1 baseline line, "
                            f"found {len(idxs)}")
            continue
        line = lines[idxs[0]]
        if not re.match(r"^\s*key\s+usage\s+0x0*%X\s+%s\b" % (usage, re.escape(was)),
                        line, re.IGNORECASE):
            problems.append(f"usage 0x{usage:06X}: baseline is {line.strip()!r}, "
                            f"expected key usage 0x{usage:06X} {was}")

    if problems:
        print("BASELINE MISMATCH - refusing to write:")
        for p in problems:
            print("  " + p)
        return 1

    for code, (was, now) in EDITS.items():
        lines[index[code][0]] = "key %-5d %s" % (code, now)

    for usage, (was, now) in USAGE_EDITS.items():
        # Match the baseline's own style: lowercase usage page, uppercase 16-bit usage id.
        lines[uindex[usage][0]] = "key usage 0x%02x%04X %s" % (
            usage >> 16, usage & 0xFFFF, now)

    out = "\n".join(lines)

    # sanity: same number of lines, and every scancode still present exactly once
    assert out.count("\n") == src.count("\n"), "line count changed"
    for code in EDITS:
        hits = len(re.findall(r"(?m)^key\s+%d\s+" % code, out))
        assert hits == 1, f"scan {code} appears {hits} times after edit"
    for usage in USAGE_EDITS:
        hits = len(re.findall(r"(?mi)^key\s+usage\s+0x0*%X\s+" % usage, out))
        assert hits == 1, f"usage 0x{usage:06X} appears {hits} times after edit"

    os.makedirs(os.path.dirname(OUT_KL), exist_ok=True)
    with open(OUT_KL, "w") as fh:
        fh.write(out)
    with open(os.path.join(OUTDIR, "module.prop"), "w") as fh:
        fh.write(MODULE_PROP)
    with open(os.path.join(OUTDIR, "README.md"), "w") as fh:
        fh.write(README)

    print(f"wrote {OUT_KL} ({len(out)} bytes, {out.count(chr(10))} lines)")
    print(f"  baseline {SRC}: {len(src)} bytes")

    # show exactly what changed (difflib, not the diff binary: this runs on Android too, where
    # an external diff may not exist and the report silently came out as "0 changed lines")
    changed = [l for l in difflib.unified_diff(src.split("\n"), out.split("\n"), lineterm="")
               if l[:1] in "-+" and not l.startswith(("---", "+++"))]
    print(f"  changed lines: {len(changed)}")
    for l in changed:
        print("    " + l)

    # Package the KernelSU module, and keep an identical copy inside the app's assets. A freshly
    # installed APK can then install the KernelSU half by itself once it has been given root, and
    # because both copies come from this one script they cannot drift apart.
    files = {
        "module.prop": os.path.join(OUTDIR, "module.prop"),
        "customize.sh": os.path.join(OUTDIR, "customize.sh"),
        "README.md": os.path.join(OUTDIR, "README.md"),
        "system/usr/keylayout/" + KL_NAME: OUT_KL,
    }
    asset = os.path.join(OUTDIR, "..", "remap", "src", "main", "assets", "erhai-keymap.zip")
    for dest in (os.path.join(_REPO, "ksu-keymap", "erhai-keymap.zip"), asset):
        dest = os.path.abspath(dest)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as z:
            for name, src_path in files.items():
                z.write(src_path, name)
        with zipfile.ZipFile(dest) as z:
            assert sorted(z.namelist()) == sorted(files.keys()), z.namelist()
        print(f"wrote {dest} ({os.path.getsize(dest)} bytes, {len(files)} entries)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
