# NativeCode AI Agent Environment & Hardware Bridge Architecture Plan

## 1. Objective
Equip all AI coding CLI tools (`opencode`, `codex`, `agy` [Antigravity], `claude` [Claude Code], `grok`, `qwen`, `kiro`) running inside the NativeCode PRoot Linux guest environment with comprehensive, concise, and accurate awareness of:
1. **Container Isolation & Host Relationship:** PRoot rootless Linux on Android ARM64, user `flux` (home `/home/flux`) and `root` (home `/root`), process limits, and network sharing (`localhost`).
2. **Host Agent Bridge (`nativecode-agent`):** In-app browser preview cards (`open-link`), on-device APK testing/install dialogs (`install-apk`), system notifications (`notify`), toasts (`toast`), task status bars (`status`), and real-time device telemetry (`telemetry`).
3. **GPU Hardware Acceleration (`gpu-launch`):** Mesa Turnip KGSL direct rendering for Snapdragon/Adreno, Venus virtio-gpu socket over Vulkan for MediaTek Dimensity/Mali/Exynos, VirGL, and diagnostics.
4. **VPU MediaCodec Hardware Video Bridge (`nativecode-media`):** Hardware zero-copy H.264/HEVC/AV1/VP9 decoding and live FFmpeg-to-MediaCodec streaming encoding.

---

## 2. CLI Tool Discovery Specifications & Paths

Different coding agents look for global system prompts and configuration in specific standard locations. By deploying both the universal root-level markdown and tool-specific configuration paths, all agents automatically discover and apply these capabilities upon execution:

| CLI Tool | Primary System Prompt / Memory Location | Secondary / Fallback Location |
|---|---|---|
| **Antigravity (`agy`)** | `~/.config/antigravity/AGENTS.md` | `~/.antigravity/AGENTS.md` |
| **OpenCode (`opencode`)** | `~/.config/opencode/AGENTS.md` | `~/AGENTS.md` |
| **Claude Code (`claude`)** | `~/.claude/CLAUDE.md` | `~/.config/claude/CLAUDE.md` |
| **OpenAI Codex (`codex`)** | `~/.codex/instructions.md` | `~/.codex/AGENTS.md` |
| **Grok Build (`grok`)** | `~/.grok/AGENTS.md` | `~/.config/grok/AGENTS.md` |
| **Qwen Code (`qwen`)** | `~/.qwen/AGENTS.md` | `~/.config/qwen/AGENTS.md` |
| **Kiro CLI (`kiro` / `kiro-cli`)** | `~/.kiro/AGENTS.md` | `~/.config/kiro/AGENTS.md` |
| **Global Guest Fallback** | `~/AGENTS.md` (for `flux` & `root`) | `/etc/AGENTS.md` |
| **Shell Environment** | `/etc/profile.d/nativecode-agent.sh` | N/A |

---

## 3. Post-Install Injection Strategy (Why App Control Beats Static Rootfs)

Instead of baking static documentation files permanently into the heavy 130MB+ prebuilt rootfs tarball:
1. **Dynamic Updatability:** When new bridge features, flags, or CLI tools are added, updating the Android app immediately updates the agent instructions inside the guest rootfs on next launch or upgrade—without requiring users to re-download or wipe their rootfs.
2. **Multi-User Consistency:** The app installer writes identical configurations to both `/home/flux/` (default unprivileged user) and `/root/` (root shell user).
3. **App Controlled Lifecycle:**
   - **Onboarding Integration:** Deployed immediately during Step C ("Verifying environment...") right after rootfs extraction in `OnboardingActivity`.
   - **Cold Start / Upgrade Integration:** Re-applied idempotently in `LinuxGuestDefaults.apply()` during `AppUpgrade.runIfNeeded()`, ensuring existing installations immediately receive updates.

---

## 4. Hardware Bridge Reference Summary for Agents

### 4.1 NativeCode Host Agent Bridge (`nativecode-agent`)
- **Transport:** Unprivileged `AF_UNIX` Linux abstract socket `@nativecode.agent.v1`.
- **Location:** `/usr/local/bin/nativecode-agent`.
- **Core Commands:**
  - `nativecode-agent ping`: Test IPC bridge health.
  - `nativecode-agent open-link --url "http://localhost:<port>" [--title "..."]`: Open local web servers in the Android browser via an interactive card with translucent dimming over the workspace (with OPEN BROWSER / COPY / DISMISS actions).
  - `nativecode-agent install-apk --path "<path/to/apk>" [--title "..."]`: Save built Android APK to Downloads and prompt user installation without requiring ADB.
  - `nativecode-agent dismiss-alert`: Remotely dismiss active on-screen alert card (open-link or install-apk).
  - **Single Active Alert Rule:** Exactly one alert can be active on screen at a time. If an alert is already visible, new requests return `{"ok": false, "error": "alert_already_active"}`.
  - `nativecode-agent notify --title "..." --message "..." [--priority high|default|low]`: Android system notification.
  - `nativecode-agent toast --text "..."`: Android system toast.
  - `nativecode-agent telemetry`: Return battery %, battery temperature °C, RAM available/total, power save status.
  - `nativecode-agent status --key "<task_id>" --text "..." --progress 0-100`: Update persistent Android foreground status notification.

### 4.2 GPU Acceleration (`gpu-launch`)
- **Location:** `/usr/local/bin/gpu-launch`.
- **Snapdragon / Adreno:** Direct Turnip KGSL over `/dev/kgsl-3d0` (`gpu-launch --turnip <cmd>`).
- **Mali / Dimensity / Exynos:** Venus virtio-gpu socket (`gpu-launch --venus <cmd>`).
- **Auto Selection:** `gpu-launch --auto <cmd>`.
- **Note:** `/dev/dri/card0` returns `EACCES` under PRoot by Android SELinux design; do not try to chmod or access it.

### 4.3 VPU MediaCodec Acceleration (`nativecode-media`)
- **Transport:** Unprivileged `AF_UNIX` Linux abstract socket `@nativecode.media.v1`.
- **Location:** `/usr/local/bin/nativecode-media`.
- **Core Commands:**
  - `nativecode-media capabilities`: Query hardware codecs (H.264, HEVC, AV1, VP9).
  - `nativecode-media benchmark_file --path "<video_path>"`: Hardware decoding.
  - `ffmpeg ... -f rawvideo - | nativecode-media encode_stream --width 1280 --height 720 --output "<out.mp4>"`: High-speed hardware video encoding directly from guest FFmpeg.

---

## 5. Implementation Roadmap
1. `docs/agents/plan.md`: This architecture plan.
2. `docs/agents/AGENTS.md`: Master agent guidelines markdown file.
3. `docs/agents/CLAUDE.md`: Claude Code guidelines markdown file.
4. `docs/agents/codex-instructions.md`: OpenAI Codex instructions markdown file.
5. Assets in `app/src/main/assets/agents/`: Bundled into APK for offline installation.
6. `AgentConfigInstaller.kt`: Kotlin utility deploying configs to `/home/flux/`, `/root/`, and `/etc/`.
7. `OnboardingActivity.kt`: Hook into Phase C setup progress.
8. `LinuxGuestDefaults.kt`: Hook into startup defaults verification.
9. Verification on physical devices (`Y5WWBMJVOZSK4HU8` and `d30a1726`).
