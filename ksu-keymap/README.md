# Layer A - keymap (KernelSU)

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
