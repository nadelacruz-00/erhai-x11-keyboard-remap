#!/usr/bin/env python3
"""Verify every androidx / Material import in our Java sources actually exists.

Three times in this project a class or style name was derived by analogy instead of being read
out of the library, and each time that cost a build cycle:

  - Theme.Material3Expressive.DynamicColors.DayNight.Dialog.Alert  (does not exist)
  - AccessibilityDelegateCompat in androidx.core.view.accessibility (wrong package)

This closes the gap for Java imports: it lists the real classes inside each dependency's
classes.jar and checks every import against them, so a wrong package or class name is caught
locally instead of in CI.

The AARs are fetched on demand and cached in /tmp/mat.
"""
import os

# Paths resolve relative to this file, so the tool runs from anywhere.
_REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
import re
import subprocess
import sys
import zipfile

CACHE = "/tmp/mat"
SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "remap", "src", "main", "java")
BASE = "https://dl.google.com/dl/android/maven2/"

AARS = {
    "androidx.core": "androidx/core/core/1.16.0/core-1.16.0.aar",
    "androidx.appcompat": "androidx/appcompat/appcompat/1.7.0/appcompat-1.7.0.aar",
    "androidx.recyclerview": "androidx/recyclerview/recyclerview/1.3.2/recyclerview-1.3.2.aar",
    "androidx.dynamicanimation":
        "androidx/dynamicanimation/dynamicanimation/1.0.0/dynamicanimation-1.0.0.aar",
    "com.google.android.material": "com/google/android/material/material/1.14.0/material-1.14.0.aar",
}

# Imports we never verify: platform and language classes.
SKIP_PREFIXES = ("android.", "androidx.annotation.", "java.", "javax.",
                 "dev.erhai.", "de.robv.android.xposed.")


def aar_path(rel, name):
    os.makedirs(CACHE, exist_ok=True)
    local = os.path.join(CACHE, os.path.basename(rel))
    if not os.path.exists(local) or os.path.getsize(local) == 0:
        subprocess.run(["curl", "-sL", "-o", local, BASE + rel], check=False)
    return local


def classes_of(aar):
    """Dotted class names inside an AAR's classes.jar."""
    names = set()
    try:
        with zipfile.ZipFile(aar) as z:
            inner = [n for n in z.namelist() if n.endswith("classes.jar")]
            if not inner:
                return names
            data = z.read(inner[0])
        with zipfile.ZipFile(__import__("io").BytesIO(data)) as cj:
            for n in cj.namelist():
                if n.endswith(".class"):
                    names.add(n[:-6].replace("/", "."))
    except Exception as e:
        print(f"  !! could not read {os.path.basename(aar)}: {e}")
    return names


def main():
    available = set()
    for name, rel in AARS.items():
        p = aar_path(rel, name)
        got = classes_of(p)
        print(f"{name:34s} {len(got):6d} classes")
        available |= got

    if not available:
        print("no library classes found - cannot verify")
        return 1

    problems = []
    checked = 0
    for dirpath, _, files in os.walk(SRC):
        for f in files:
            if not f.endswith(".java"):
                continue
            path = os.path.join(dirpath, f)
            with open(path, "r", errors="replace") as fh:
                for line in fh:
                    m = re.match(r"\s*import\s+(static\s+)?([A-Za-z0-9_.]+);", line)
                    if not m:
                        continue
                    cls = m.group(2)
                    if cls.startswith(SKIP_PREFIXES):
                        continue
                    checked += 1
                    if cls not in available:
                        problems.append(f"{f}: import {cls} - NOT FOUND in any dependency")

    print(f"\nchecked {checked} imports")
    if problems:
        print("PROBLEMS:")
        for p in sorted(set(problems)):
            print("  " + p)
        return 1
    print("every androidx / material import resolves")
    return 0


if __name__ == "__main__":
    sys.exit(main())
