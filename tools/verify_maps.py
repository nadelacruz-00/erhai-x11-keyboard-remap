#!/usr/bin/env python3
"""Verify the scan/original parallel arrays in MainHook.java line up.

A length mismatch between the two arrays would silently map a key to the WRONG original
key code - exactly the kind of bug that produces plausible-but-wrong behaviour.
Also prints the paired mapping so it can be eyeballed against the keylayout.
"""
import re
import os

# Paths resolve relative to this file, so the tool runs from anywhere.
_REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
import sys

SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "remap", "src", "main", "java", "dev", "erhai", "kbremap", "MainHook.java")


def arr(body, name):
    m = re.search(r"int\[\]\s+" + name + r"\s*=\s*\{(.*?)\};", body, re.S)
    if not m:
        return None
    return [int(x) for x in re.findall(r"-?\d+", m.group(1))]


def sarr(body, name):
    m = re.search(r"String\[\]\s+" + name + r"\s*=\s*\{(.*?)\};", body, re.S)
    if not m:
        return None
    return re.findall(r'"([^"]+)"', m.group(1))


body = open(SRC, "r", errors="replace").read()
ok = True

# (scans array, names array, title, required?). The normal-mode map was removed when the per-app
# feature was dropped, so only the Fn-restore map is required now.
for scans_n, names_n, title, required in (
        ("TOP_ROW_SCANS", "TOP_ROW_ORIGINAL_NAMES", "Fn-restore map", True),
        ("REMAP_SCANS", "REMAP_ORIGINAL_NAMES", "normal-mode map", False)):
    scans = arr(body, scans_n)
    names = sarr(body, names_n)
    print(f"=== {title} ({scans_n} / {names_n}) ===")
    if scans is None or names is None:
        if required:
            print("  !! could not parse")
            ok = False
        else:
            print("  (not present - fine)")
        continue
    print(f"  {len(scans)} scans, {len(names)} names", end="")
    if len(scans) != len(names):
        print("   !! MISMATCH")
        ok = False
    else:
        print("   ok")
    for s, n in zip(scans, names):
        print(f"    scan {s:4d} -> {n}")

sys.exit(0 if ok else 1)
