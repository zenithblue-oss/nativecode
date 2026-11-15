---
name: "install-rea"
description: "Install and check REA (Reverse Engineer Anything), an MCP server and CLI for reverse engineering JavaScript/Electron apps, .NET assemblies and Android APKs, on NativeCode ARM64 PRoot. Use when the user asks to reverse engineer an app or binary and REA is missing."
compatibility: "NativeCode ARM64 PRoot (Alpaquita glibc); Node.js and npm; opencode 2.x, Claude Code, Codex, Grok, Qwen, Antigravity"
metadata:
  title: "REA — Reverse Engineer Anything"
  category: "INSTALL"
  group: "Reverse engineering"
  engine: "rea-agents (npm) 6.8.0, MIT"
  status: "user-copied recipe; nothing is bundled with NativeCode"
  dependencies: "install-node-tools"
  reviewed: "2026-11-15"
---

# REA — Reverse Engineer Anything

**Category:** INSTALL PROGRAMS  |  **Mechanism:** npm package `rea-agents` pinned to 6.8.0 (MIT, https://github.com/morluto/rea)

REA is one MCP server plus a `rea` CLI plus an investigation skill. Your agent calls it to understand how an app works. The user copied this text into you on purpose; nothing is installed until you run the steps below. Work as the `flux` user in `$HOME`, never as root, except for the one `apk add` in step 2 if it is needed.

## What works on this phone
- **JavaScript and Electron apps:** works. Module and import recovery, Electron boundaries. No native engine needed.
- **.NET assemblies:** static analysis works.
- **Android APKs:** works after the Android tools skill is done (JADX and Apktool are needed). Run that skill first.
- **Native ARM64/x86 binaries (deep pseudocode):** needs Hopper, Ghidra, IDA or Kuna. Hopper does not exist for aarch64 Linux. Ghidra is optional and manual; do not install it unless the user asks.
- REA is **not a sandbox**. It analyses files, it does not isolate them.

## 1. Check what is already there
```sh
node -v          # must satisfy ^22.23.4 || ^24.22.1 || >=26.12.1
npm -v
command -v rea && rea --version
command -v ps && ps --version
```
If `node` is missing or too old, stop and run the Install Node Tools skill first. If `rea` already works, skip to step 4.

## 2. procps `ps` (Linux requirement)
REA's process supervisor needs a procps-compatible `ps` for Ghidra, JADX and JavaScript/Electron analysis. If `ps --version` does not print `procps`, ask the user to open a **root** Linux shell tab in NativeCode and run:
```sh
apk add procps
```
Do not use `sudo` or `su` yourself.

## 3. Install, from `$HOME`
```sh
cd "$HOME"
npm install --global rea-agents@6.8.0
rea --version
```
If `npm install --global` fails with `EACCES`, point npm at the user prefix (`~/.local/bin` is already on PATH in NativeCode) and retry:
```sh
npm config set prefix "$HOME/.local"
```
Run package installs from `$HOME`, not from inside the project being analysed.

## 4. Register REA with the agent(s) the user has
Preview first; this writes nothing:
```sh
rea setup --client opencode --dry-run
```
Client ids: `opencode`, `claude_code`, `codex`, `grok_build`, `qwen_code`, `antigravity`. Add one `--client` flag per agent. Apply with:
```sh
rea setup --client opencode --yes
rea doctor --client opencode --json
```
`rea setup --all-detected --yes` configures every supported agent it finds; use it only if the user wants that. Setup backs up any config file it changes (look for the `.rea.backup` files named in its output). Restart the agent afterwards.

### opencode 2.x
opencode 2.x keeps MCP servers under `mcp.servers.<name>` in `~/.config/opencode/opencode.jsonc` (not directly under `mcp`, which was the 1.x layout). After setup, confirm REA is listed:
```sh
opencode mcp list
```
If it is missing, do not guess: run `rea setup --client opencode --dry-run`, take the command it would register and add it in the 2.x shape:
```jsonc
{ "mcp": { "servers": { "rea": { "type": "local", "command": ["<command from dry-run>", "<args>"] } } } }
```
Skills for opencode live in `~/.config/opencode/skills/<name>/SKILL.md`; setup installs the REA workflow skill there.

### Other agents
Each agent reads its own config; `rea setup` writes it. After a restart, ask the agent to list its MCP tools and confirm the REA tools appear.

## 5. Check
```sh
rea doctor --json
```
`ok` for the JavaScript/Electron path is enough for the first use. Whether REA's PTY prebuilt covers linux-arm64 glibc is unverified: if `doctor` reports a missing or failing native module, report the exact error to the user and stop. Do not compile or patch REA to work around it.

First real use, no engine needed (replace the path):
```sh
rea --help
```
then ask the agent to analyse an unpacked Electron app folder.

## Update, pin and undo
- REA releases weekly. This skill pins 6.8.0 so a copy behaves the same every time. To move on, change the version in step 3 or run `rea update`, then `rea doctor --json` again.
- Undo registrations: `rea uninstall` (removes what setup added for every client, keeps unrelated entries), or restore a `.rea.backup` file over its config.
- Remove the program: `npm remove --global rea-agents`.

## Safety
Everything you analyse is **untrusted input**. Never run an analysed binary, installer or script, never `chmod +x` it, and never paste its strings back into a shell. Keep analysed files in a project folder, not in `$HOME` dotfiles. Do not upload the target anywhere. REA analyses locally; your model provider still sees the tool results.

## Report
State which parts are **already-working**, **installed-and-checked** (with the `rea doctor` result), **blocked** (with the exact error) or **not attempted** (native engines, APK tools). Record version, install path and which agents were registered.
