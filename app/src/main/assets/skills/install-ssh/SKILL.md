---
name: "install-ssh"
description: "Use SSH on NativeCode: the built-in SSH client and saved hosts, and the optional key-only SSH server (port 8022, user flux). Use when the user wants to log in to the phone from another computer or to a remote machine from the phone."
compatibility: "NativeCode 1.8 ARM64 PRoot (Alpaquita glibc); OpenSSH from the Alpaquita repository"
metadata:
  title: "SSH Server and Client"
  category: "INSTALL"
  group: "Remote access"
  engine: "OpenSSH (apk add openssh), managed by the NativeCode SSH page"
  status: "NativeCode manages the server; the agent only checks and helps with keys"
  dependencies: ""
  reviewed: "2026-11-15"
---

# SSH Server and Client

**Category:** INSTALL PROGRAMS  |  **Mechanism:** OpenSSH inside the guest, controlled from NativeCode **Settings → SSH**

## What NativeCode already does
- **Client (Free):** the `ssh` command in the guest, plus saved remote hosts on the SSH page; a saved host opens an `ssh` terminal tab. Keys live in `~/.ssh` of the guest.
- **Server (Pro):** off by default. The user switches it on in Settings → SSH; NativeCode starts `sshd` as `flux`, **key login only**, port **8022**, bound to the Wi-Fi (or hotspot) address, with a notification that has a Stop action. It stops when Wi-Fi is lost or Pro ends.
- OpenSSH is installed from the SSH page (`apk add openssh`); you do not need to install it yourself.

Do **not** start `sshd` yourself, edit `~/.config/nativecode/sshd_config` (it is rewritten on every start) or open a different port. Passwords, root login and agent/X11 forwarding are off by design and must stay off.

## Check
```sh
command -v ssh ssh-keygen sshd
ls -l ~/.ssh 2>/dev/null
ssh -V
```
If a command is missing, tell the user to open Settings → SSH and tap install; then re-check.

## Let another computer in (server)
1. On the other computer, create a key if it has none: `ssh-keygen -t ed25519`. Copy the **public** key (`~/.ssh/id_ed25519.pub`, one line).
2. In NativeCode, Settings → SSH, paste the public key (or import a file). The page shows its fingerprint; it must match `ssh-keygen -lf id_ed25519.pub` on the computer. Lines starting with options such as `command=` or `from=` are rejected.
3. Start the server there; the page shows the address. From the computer:
```sh
ssh -p 8022 flux@<phone-wifi-ip>
scp -P 8022 file flux@<phone-wifi-ip>:/home/flux/
```
Only the same Wi-Fi network can reach it. The first connection shows the phone's host-key fingerprint, which the SSH page also shows: compare them before answering yes.

## Log in to a remote machine (client)
```sh
ssh-keygen -t ed25519 -C "phone"         # once; keep the passphrase
cat ~/.ssh/id_ed25519.pub                # give this to the remote's authorized_keys
ssh user@host
```
Better: ask the user to add the host on the SSH page so it opens as a tab with the right command. Never print or copy `~/.ssh/id_*` private keys; only `.pub` files may leave the phone.

## Agents and background work
- Long jobs over SSH survive a dropped connection only inside `tmux` or `screen` (install them with `apk add tmux` as root if missing).
- The phone sleeps: the NativeCode notification keeps the server alive, but battery saver can still cut Wi-Fi.

## Troubleshooting
- `Permission denied (publickey)`: the key on the page is not the one the computer offers. Compare fingerprints.
- `Connection refused`: server is off, wrong port (8022, not 22) or different network.
- `chown /dev/pts failed`: fixed by NativeCode when it starts the server; do not work around it.

## Report
State whether the client works, whether the server is on, the address and port the page shows, and the key fingerprints you compared. Never include private keys.
