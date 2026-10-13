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
import re
import shutil
import subprocess
import sys

SRC = "/var/minis/attachments/Generic-device.kl"
OUTDIR = "/var/minis/shared/pad3-keyboard/ci-repo/ksu-keymap"
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
    186: ("F16", "F4"),
    99: ("SYSRQ", "F5"),
    194: ("F24", "F6"),
    165: ("MEDIA_PREVIOUS", "F7"),
    164: ("MEDIA_PLAY_PAUSE", "F8"),
    163: ("MEDIA_NEXT", "F9"),
    113: ("VOLUME_MUTE", "F10"),
    114: ("VOLUME_DOWN", "F11"),
    115: ("VOLUME_UP", "F12"),
}

MODULE_PROP = """id=pad3_keymap
name=Pad3 Keymap (Layer A)
version=1.0.0
versionCode=100
author=Minis
description=Static keylayout for the OnePlus Pad 3 keyboard: physical Esc->ESCAPE, Search->FUNCTION, top row->F1..F12. Companion to the LSPosed Meta-routing module.
"""

README = """# Layer A - keymap (KernelSU)

Overlays a device-specific keylayout for the Pad 3 keyboard:

    system/usr/keylayout/Vendor_22d9_Product_3869.kl

Changes, all relative to the device's own `Generic.kl`:

| scan | was | now | why |
|---|---|---|---|
| 158 | BACK | ESCAPE | the physical Esc key becomes a real Escape |
| 193 | F23 | FUNCTION | the Search key becomes the Fn modifier |
| 224 225 183 186 99 194 165 164 163 113 114 115 | brightness / F13 / F16 / SYSRQ / F24 / media / volume | F1..F12 | the top row becomes the function row |

`Del` (scan 111) is deliberately left alone.

## Why this layer exists

A Java/LSPosed hook cannot change a keycode - the native InputDispatcher dispatches its own
copy of the event, so a Java-side rewrite never reaches the app. Keycode changes must happen
in the keylayout that InputReader parses. This is exactly why `Drun555/oneplus-meta-gate`
patches `key 158 BACK` for Escape while doing Meta routing in code.

## Notes

- The file is a FULL replacement of Generic.kl, not a patch. One .kl serves all four
  sub-devices that share VID:PID 22d9:3869, so a slim file would break the touchpad.
- No `.kcm` is shipped in v1.0.0 on purpose: your device's Generic.kcm has zero `fn:`
  entries, so the Fn-fallback mechanism is unproven here. Restoring brightness/volume on
  Fn-held is planned as Layer A2 once A1 is verified.
- Rollback: disable or delete the module in KernelSU and reboot. No partition is written.
"""


def main():
    src = open(SRC, "r", errors="replace").read()
    lines = src.split("\n")

    # Build a scancode -> line index map, asserting single occurrence.
    index = {}
    for i, line in enumerate(lines):
        s = line.split("#", 1)[0].strip()
        if not s.startswith("key "):
            continue
        parts = s.split()
        if len(parts) < 3:
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

    if problems:
        print("BASELINE MISMATCH - refusing to write:")
        for p in problems:
            print("  " + p)
        return 1

    for code, (was, now) in EDITS.items():
        lines[index[code][0]] = "key %-5d %s" % (code, now)

    out = "\n".join(lines)

    # sanity: same number of lines, and every scancode still present exactly once
    assert out.count("\n") == src.count("\n"), "line count changed"
    for code in EDITS:
        hits = len(re.findall(r"(?m)^key\s+%d\s+" % code, out))
        assert hits == 1, f"scan {code} appears {hits} times after edit"

    os.makedirs(os.path.dirname(OUT_KL), exist_ok=True)
    with open(OUT_KL, "w") as fh:
        fh.write(out)
    with open(os.path.join(OUTDIR, "module.prop"), "w") as fh:
        fh.write(MODULE_PROP)
    with open(os.path.join(OUTDIR, "README.md"), "w") as fh:
        fh.write(README)

    print(f"wrote {OUT_KL} ({len(out)} bytes, {out.count(chr(10))} lines)")
    print(f"  baseline {SRC}: {len(src)} bytes")

    # show exactly what changed
    diff = subprocess.run(["diff", SRC, OUT_KL], capture_output=True, text=True).stdout
    changed = [l for l in diff.split("\n") if l.startswith(("<", ">"))]
    print(f"  changed lines: {len(changed)}")
    for l in changed:
        print("    " + l)
    return 0


if __name__ == "__main__":
    sys.exit(main())
