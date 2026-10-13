#!/usr/bin/env python3
"""Cross-check every R.<type>.<name> reference against the actual resource files.

Catches the class of typo (R.id.foo vs @+id/foo, or a missing string) that would otherwise
only surface as a CI compile failure several minutes later.
"""
import os

# Paths resolve relative to this file, so the tool runs from anywhere.
_REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "remap", "src", "main")
RES = os.path.join(ROOT, "res")
JAVA = os.path.join(ROOT, "java")

# Style names that come from libraries rather than this project's own res/values. Referencing them
# is intentional - each one was read out of the library's own resource table before use - so they
# must not be reported as missing. Without this the checker cries wolf and stops being trusted.
LIBRARY_STYLE_PREFIXES = (
    "Widget.Material3", "Widget.MaterialComponents", "Widget.AppCompat", "Widget.Material",
    "Theme.Material3", "Theme.AppCompat", "ThemeOverlay.", "TextAppearance.",
    "Base.", "Platform.",
)


def read(path):
    with open(path, "r", errors="replace") as fh:
        return fh.read()


def collect():
    ids, strings, arrays, layouts, drawables, mipmaps, styles = set(), set(), set(), set(), set(), set(), set()

    for dirpath, _, files in os.walk(RES):
        for f in files:
            p = os.path.join(dirpath, f)
            rel = os.path.relpath(p, RES)
            if rel.startswith("layout/"):
                layouts.add(f[:-4])
                body = read(p)
                ids.update(re.findall(r'@\+id/([A-Za-z0-9_]+)', body))
                ids.update(re.findall(r'@id/([A-Za-z0-9_]+)', body))
            elif rel.startswith("drawable"):
                drawables.add(f[:-4])
            elif rel.startswith("mipmap"):
                mipmaps.add(f[:-4])
            if rel.startswith("values/") or rel.endswith(".xml"):
                body = read(p)
                strings.update(re.findall(r'<string name="([A-Za-z0-9_]+)"', body))
                arrays.update(re.findall(r'<string-array name="([A-Za-z0-9_]+)"', body))
                styles.update(re.findall(r'<style name="([A-Za-z0-9_.]+)"', body))
    return {"id": ids, "string": strings, "array": arrays, "layout": layouts,
            "drawable": drawables, "mipmap": mipmaps, "style": styles}


def main():
    have = collect()
    problems = []
    checked = 0

    for dirpath, _, files in os.walk(JAVA):
        for f in files:
            if not f.endswith(".java"):
                continue
            p = os.path.join(dirpath, f)
            # negative lookbehind: android.R.* are framework resources, not ours
            for kind, name in re.findall(r"(?<!android\.)\bR\.([a-z]+)\.([A-Za-z0-9_]+)", read(p)):
                if kind not in have:
                    continue
                checked += 1
                if name not in have[kind]:
                    problems.append(f"{os.path.basename(p)}: R.{kind}.{name} - not found")

    # also check @string/ @array/ references inside XML (excluding @+id)
    for dirpath, _, files in os.walk(RES):
        for f in files:
            if not f.endswith(".xml"):
                continue
            p = os.path.join(dirpath, f)
            body = read(p)
            for kind, name in re.findall(r'"@(string|array|style)/([A-Za-z0-9_.]+)"', body):
                checked += 1
                # Styles provided by libraries (Material, AppCompat) are not in our res/ and must
                # not be reported. Everything else under @style/ is ours and must resolve.
                if kind == "style" and name.startswith(LIBRARY_STYLE_PREFIXES):
                    continue
                if name not in have[kind]:
                    problems.append(f"{f}: @{kind}/{name} - not found")

    # XML well-formedness, including the manifest. This is the check that catches illegal comment
    # content such as "--" inside a comment: Gradle's XML parser rejects it outright while aapt2
    # can be more forgiving, so it otherwise slips through to CI.
    for dirpath, _, files in os.walk(os.path.dirname(RES)):
        for f in files:
            if f.endswith(".xml"):
                p = os.path.join(dirpath, f)
                checked += 1
                try:
                    ET.parse(p)
                except Exception as e:
                    problems.append(f"{f}: XML parse error - {e}")

    print(f"checked {checked} references")
    if problems:
        print("PROBLEMS:")
        for x in sorted(set(problems)):
            print("  " + x)
        return 1

    print("all resource references resolve")
    print(f"  ids={len(have['id'])} strings={len(have['string'])} "
          f"layouts={len(have['layout'])} drawables={len(have['drawable'])} "
          f"mipmaps={len(have['mipmap'])} arrays={len(have['array'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
