import sys
import json
import urllib.request
import subprocess

def run_op13r(cmd):
    payload = json.dumps({"cmd": cmd}).encode("utf-8")
    # Execute through adb shell curl to bypass any adb port forwarding requirement
    p = subprocess.run([
        "adb", "-s", "192.168.1.38:44029", "shell",
        "curl -s -X POST -H 'Content-Type: application/json' --data-binary @- http://127.0.0.1:9090/exec"
    ], input=payload, capture_output=True)
    try:
        res = json.loads(p.stdout.decode("utf-8"))
        return res.get("exit_code", -1), res.get("stdout", ""), res.get("stderr", "")
    except Exception as e:
        return -1, p.stdout.decode("utf-8", errors="replace"), f"{e}: {p.stderr.decode('utf-8', errors='replace')}"

def run_poco(cmd):
    # Run in Poco guest container via proot login
    p_uid = subprocess.run([
        "adb", "-s", "Y5WWBMJVOZSK4HU8", "shell",
        "dumpsys package com.zenithblue.nativecode | grep userId= | head -n 1"
    ], capture_output=True, text=True)
    uid_str = p_uid.stdout.strip().split("=")[-1] if "=" in p_uid.stdout else "10333"
    app_user = f"u0_a{int(uid_str) - 10000}" if uid_str.isdigit() and int(uid_str) >= 10000 else "u0_a333"

    p_lib = subprocess.run([
        "adb", "-s", "Y5WWBMJVOZSK4HU8", "shell",
        "su -c 'ls -d /data/app/*/*com.zenithblue.nativecode*/lib/arm64 2>/dev/null | head -n 1'"
    ], capture_output=True, text=True)
    lib_dir = p_lib.stdout.strip()
    if not lib_dir:
        lib_dir = "/data/data/com.zenithblue.nativecode/lib"
    
    proot_cmd = (
        "TERMUX_APP__PACKAGE_NAME=com.zenithblue.nativecode "
        "TERMUX__PREFIX=/data/data/com.zenithblue.nativecode/files/usr "
        "TERMUX_VERSION=flux-host "
        f"PD_PROOT_BIN={lib_dir}/libproot.so "
        f"PROOT_LOADER={lib_dir}/libloader.so "
        "LD_LIBRARY_PATH=/data/data/com.zenithblue.nativecode/files/usr/lib "
        "PATH=/data/data/com.zenithblue.nativecode/files/usr/bin:/system/bin "
        "/system/bin/linker64 /data/data/com.zenithblue.nativecode/files/usr/bin/python "
        "/data/data/com.zenithblue.nativecode/files/usr/bin/proot-distro login alpaquita --user flux -- /bin/bash"
    )
    p = subprocess.run([
        "adb", "-s", "Y5WWBMJVOZSK4HU8", "shell",
        f"su -c 'su {app_user} -c \"{proot_cmd}\"'"
    ], input=cmd.encode("utf-8"), capture_output=True)
    return p.returncode, p.stdout.decode("utf-8", errors="replace"), p.stderr.decode("utf-8", errors="replace")

if __name__ == "__main__":
    target = sys.argv[1]
    cmd = sys.argv[2]
    if target == "op13r":
        code, out, err = run_op13r(cmd)
    elif target == "poco":
        code, out, err = run_poco(cmd)
    else:
        print("Unknown target")
        sys.exit(1)
    if out: sys.stdout.write(out)
    if err: sys.stderr.write(err)
    sys.exit(code)
