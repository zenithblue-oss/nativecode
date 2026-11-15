---
name: "install-hermes"
description: "Install and check Hermes Agent (Nous Research) in the NativeCode PRoot guest. Use when the user wants Hermes and it is not installed; NativeCode does not bundle it yet."
compatibility: "NativeCode ARM64 PRoot (Alpaquita glibc); needs several hundred MB of free space"
metadata:
  title: "Hermes Agent"
  category: "INSTALL"
  group: "AI agents"
  engine: "hermes-agent 0.23.1 (MIT) official installer"
  status: "user-copied recipe; not bundled, not in the NativeCode launchers yet"
  dependencies: "install-dev-utils"
  reviewed: "2026-11-15"
---

# Hermes Agent

**Category:** INSTALL PROGRAMS  |  **Mechanism:** official installer from https://hermes-agent.nousresearch.com, source https://github.com/NousResearch/hermes-agent (MIT, 0.23.1)

State of NativeCode today: **Hermes is not installed, not bundled and not in the NativeCode tool launchers or CLI login pages.** This skill is the manual route, run in a normal Linux shell tab as the `flux` user. A built-in Hermes pack is planned; do not claim it exists.

## 1. Check first
```sh
command -v hermes && hermes --version
df -h "$HOME" | tail -1
uname -m; getconf GNU_LIBC_VERSION
```
If `hermes` already works, skip to step 4. Hermes needs roughly 0.6-1.2 GB installed (estimate: it ships Python 3.14 and about 350 locked packages, some large). If free space is below 2 GB, stop and tell the user.

## 2. Download the installer and read it
Do not pipe the web into a shell. Download, show the user the size and head of the script, then run it:
```sh
cd "$HOME"
curl -fsSL https://hermes-agent.nousresearch.com/install.sh -o hermes-install.sh
wc -c hermes-install.sh; head -40 hermes-install.sh
```
The installer refuses to run when `TERMUX_VERSION` is set or `PREFIX` points into `com.termux`. NativeCode's guest does neither; if it complains anyway, report the exact message and stop.

## 3. Install without the interactive wizard and the browser tools
```sh
bash hermes-install.sh --skip-setup --skip-browser
exec "$SHELL" -l
hermes --version
```
`--dir` and `--hermes-home` relocate the install and the config; keep the defaults unless the user asks (config lives in `~/.hermes`). Time the install and note how long the first `hermes` start takes under PRoot; both are unmeasured.

## 4. Configure and check
Credentials are the user's job: never ask the user to paste a key into the chat and never print one.
```sh
hermes model        # choose provider and model (device-code login for OAuth providers)
hermes doctor       # diagnose problems
hermes              # interactive CLI
```
Other useful commands: `hermes setup` (full wizard), `hermes tools`, `hermes config get|set`, `hermes update`.

## 5. Notes for this phone
- Use the **Linux shell** tab; the Hermes launcher card does not exist yet.
- Free NativeCode plan: Hermes runs as an ordinary program in the guest, but the NativeCode agent tools (chat, login pages) do not manage it.
- Keep `~/.hermes/auth.json` and `~/.hermes/.env` out of backups, project folders and git.
- A Hermes auto-update can pull large downloads; ask before running `hermes update`.

## Undo
Remove the install directory the installer printed (default under `~/.hermes` and its program directory), the `hermes` link it created and any PATH line it added to `~/.profile`, `~/.zshrc` or `~/.bashrc`. List what you will delete and get the user's OK first. `rm -rf ~/.hermes` also deletes their sessions and keys.

## Report
Say **already-working**, **installed-and-checked** (version and `hermes doctor` result), **blocked** (exact error) or **planned-only**. Record version, install size (`du -sh`), install time and path.
