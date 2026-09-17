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
    proot_cmd = (
        "TERMUX_APP__PACKAGE_NAME=com.zenithblue.nativecode "
        "TERMUX__PREFIX=/data/data/com.zenithblue.nativecode/files/usr "
        "TERMUX_VERSION=flux-host "
        "PD_PROOT_BIN=/data/app/~~jaDx-XL46EEnL37_E5rFUw==/com.zenithblue.nativecode-cF9lP4BnTaF_sUznq6G3xg==/lib/arm64/libproot.so "
        "PROOT_LOADER=/data/app/~~jaDx-XL46EEnL37_E5rFUw==/com.zenithblue.nativecode-cF9lP4BnTaF_sUznq6G3xg==/lib/arm64/libloader.so "
        "LD_LIBRARY_PATH=/data/data/com.zenithblue.nativecode/files/usr/lib "
        "PATH=/data/data/com.zenithblue.nativecode/files/usr/bin:/system/bin "
        "/system/bin/linker64 /data/data/com.zenithblue.nativecode/files/usr/bin/python "
        "/data/data/com.zenithblue.nativecode/files/usr/bin/proot-distro login alpaquita --user flux -- /bin/bash"
    )
    p = subprocess.run([
        "adb", "-s", "Y5WWBMJVOZSK4HU8", "shell",
        f"su -c 'su u0_a332 -c \"{proot_cmd}\"'"
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
