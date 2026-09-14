# Host packages (NativeCode prefix)

Termux recipes rebuilt for `com.zenithblue.nativecode` (`/data/data/com.zenithblue.nativecode/files/usr`).

| Package | Role |
|---|---|
| `virglrenderer-android` | Venus (`-Dvenus=true`) + VirGL vtest. No ANGLE. Android Vulkan via `0011-use-Android-Vulkan`. |
| `pulseaudio` | Upstream recipe in `~/repos/termux-packages`. TCP module at runtime. |

Build:

```sh
scripts/build_nativecode_host_servers.sh
```

Debs land in `termux-packages/output/`. `assemble_bootstrap.py` unpacks them into `bootstrap.tar`.
