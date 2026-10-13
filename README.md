# pad3-keyboard-remap

Per-app keyboard remapper for the **OnePlus Pad 3** official (pogo) keyboard.

This repo is primarily a **CI build sandbox** — the APKs are built by GitHub Actions
(`ubuntu-latest` ships a full Android SDK with a native `aapt2`), so nothing heavy has to be
installed or cross-compiled on the tablet. Delete the repo whenever you like.

## Current contents

`app/` — **KeyLog**, a diagnostic app.

It displays exactly what the Android *application layer* receives for every key press:
`keyCode`, `scanCode`, `metaState`, `flags`, `source`, `deviceId` and the input device name.

It exists to answer one question before any hook is written:

> when the ⊞ key (which we measured on the wire as Linux `KEY_LEFTMETA` / scan code 125) is
> pressed, does `KEYCODE_META_LEFT` actually reach an app — or does Oplus consume it first?

If KeyLog shows nothing for that key, the interception happens outside the Java layer and a
different approach is required.

## Device measurements this is based on

Measured on the Pad 3 with `getevent` on `/dev/input/event9` (the pogo keyboard):

| Physical key | Linux scan code | Android keycode today |
|---|---|---|
| ⊞ (the "AI" key) | 125 | `META_LEFT` |
| Esc | 158 | `BACK` |
| Search | 193 | `F23` |
| Top row | 224, 225, 183, 186, 99, 194, 165, 164, 163, 113, 114, 115, 111 | brightness / F13 / F16 / SYSRQ / F24 / media / volume / forward-del |

## Build

```sh
gradle assembleDebug
```

The workflow uploads `app/build/outputs/apk/debug/app-debug.apk` as the `keylog-debug-apk` artifact.
