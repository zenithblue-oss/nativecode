#!/usr/bin/env python3
"""Fail unless NativeCode's termux-x11 Java `native` declarations equal those of the pinned upstream
source libXlorie.so is built from.  usage: python3 -I check_jni.py <nativecode-java-dir> <upstream-java-dir>"""
import re, sys
def natives(root):
    out = set()
    for f in ("LorieView.java", "CmdEntryPoint.java"):
        t = open(f"{root}/com/termux/x11/{f}").read()
        out |= {(f, re.sub(r"\s+", " ", re.sub(r"@\w+ ", "", m))) for m in re.findall(r"\bnative [^;()]+\([^)]*\);", t)}
    return out
a, b = natives(sys.argv[1]), natives(sys.argv[2])
if a != b:
    for x in sorted(a ^ b): print("MISMATCH", "nativecode" if x in a else "upstream", x)
    sys.exit(1)
print(f"JNI native declarations match ({len(a)})")
