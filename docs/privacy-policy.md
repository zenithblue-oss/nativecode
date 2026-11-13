# Privacy Policy — NativeCode

**Last updated:** 2026-09-09
**App:** NativeCode (`com.zenithblue.nativecode`)  
**Developer:** ZenithBlue / NativeCode (GitHub: [zenithblue-oss](https://github.com/zenithblue-oss))  
**Repository:** [zenithblue-oss/nativecode-ai](https://github.com/zenithblue-oss/nativecode-ai)

This Privacy Policy describes how NativeCode handles information when you use the Android app. The Google Play build runs an Alpaquita Linux PRoot guest, optional X11 desktop, terminal sessions, and optional third-party AI/developer CLIs.

---

## 1. Summary

- NativeCode is **local-first**. We show no ads and operate no advertising backend.
- **Optional, off by default (1.8.0+):** crash reports (Firebase Crashlytics) and anonymous usage statistics (Google Analytics for Firebase). Nothing is collected until you opt in during onboarding or in Settings → Crash reports & usage stats, and you can turn either off there at any time. See §2.5.
- Your files, projects, packages, and credentials are stored **on this device** inside the Alpaquita Linux **PRoot** guest and app-private storage.
- Optional GitHub, AI CLI, and guest package-manager features contact **third parties you choose**. AI providers may receive prompts, selected source code or files, account identifiers, tool diagnostics, and generated output when you invoke their tools.
- The Play build receives the rootfs through Google Play Asset Delivery. It does not download executable marketplace scripts or request superuser access.
- Auth tokens/credentials, when used, are stored **on-device** only.

---

## 2. Information we process

### 2.1 Information on your device (local)

Depending on how you use the app, the following may be stored locally:

| Category | Examples |
|----------|----------|
| App preferences | UI, terminal, onboarding, and PRoot settings |
| Linux guest data | Alpaquita rootfs, packages, home-directory files, projects you create |
| Auth material (optional) | GitHub CLI tokens/config, AI CLI API keys or device-login session files under guest home |
| Session state | Terminal history, project paths, onboarding progress |

This local data is under your control. Clearing app storage or uninstalling the app removes app-owned data (subject to OS backup rules).

### 2.2 Information sent over the network

NativeCode initiates network requests only for features that require it, for example:

| Purpose | Typical destinations |
|---------|----------------------|
| Guest packages / optional tools | Alpaquita/apk, npm, and official tool hosts **when you install**; the rootfs is delivered by Google Play |
| GitHub authentication & API | `github.com` / GitHub API (when you connect GitHub) |
| AI CLI install or auth | Official vendor endpoints (e.g. Anthropic, OpenAI/Codex, xAI/Grok, and other tools you enable) |
| Package managers | apk repositories, npm registry, and official installer hosts (when you install tools) |
| AI use | The chosen AI vendor may receive prompts, selected code/files, account identifiers, diagnostics, and generated output needed to provide the requested service |
| NativeCode safety report | The configured HTTPS report service receives only app version, selected tool, category, isolation method, and the description you enter |

**We do not sell personal information.** Third parties process data under **their** privacy policies when you authenticate or download software from them.

### 2.3 Account identifiers

If you sign in to GitHub or an AI CLI:

- Usernames, emails, or account labels may appear in local CLI config or UI.
- OAuth/device codes and tokens are handled to complete login and then stored as required by those tools.
- NativeCode does not require a separate “NativeCode cloud account.”

### 2.4 Permissions

NativeCode may request Android permissions for its features (e.g. notifications for foreground services that keep terminal/AI sessions alive, network access). Permissions are used only for the stated in-app purpose.

### 2.5 Optional diagnostics (Google Firebase) — off until you opt in

DRAFT for 1.8.0 — publish to the hosted policy with the release; confirm categories against Google's SDK disclosure pages at submission.

| Switch | SDK | What is sent when ON | Purpose |
|--------|-----|----------------------|---------|
| Send crash reports | Firebase Crashlytics (+ NDK) | Stack traces of app crashes (Kotlin and the app's own native libraries), device model, OS version, app version, a Crashlytics installation ID, and two settings keys (Linux backend, GPU mode) | Fixing crashes |
| Share anonymous usage statistics | Google Analytics for Firebase | A fixed list of events: `setup_complete`, `first_ai_session`, `ai_session_start`, `free_limit_hit`, `paywall_view`, `pro_purchase`, `desktop_start`, plus Analytics' automatic events (e.g. `first_open`), with short identifiers only (tool id, product id, limit name) and an app-instance ID | Understanding which features are used |

Never sent: project names, file paths or contents, prompts, terminal input/output, account names, tokens or keys. The advertising ID is not collected (permission removed, ad-ID collection disabled) and ad-personalisation consent is always denied. Data is processed by Google under the Firebase terms; it is not sold and not used for advertising. Guest Linux processes (PRoot) are separate processes and are not covered by crash reporting.

---

## 3. How we use information

- Provide and maintain the developer environment (terminal, guest Linux, X11).
- Authenticate optional third-party CLIs **at your request**.
- Install guest packages and optional AI CLIs **when you trigger installation/setup**.
- Show status, storage size, and connection state in Settings.

We do **not** use on-device credentials for advertising or cross-app tracking.

---

## 4. Sharing

We do not sell or rent your data. Data leaves the device when:

1. You authenticate to a third party (GitHub, AI vendors).
2. You install guest packages or optional AI tools from remote hosts after you choose to.
3. The OS or a tool you run inside the guest makes its own network calls.

Third-party services are independent controllers for data they receive. Review their policies before connecting accounts.

---

## 5. Retention

- **Local app & guest data:** retained until you delete it, clear storage, or uninstall.
- **Tokens/keys:** retained until you sign out (where supported), overwrite config, clear guest home, clear app data, or uninstall.
- **Safety reports:** failed submissions are kept in private app storage (maximum 10) for retry. Successfully submitted reports may be retained by the configured report service for abuse review and support.

---

## 6. Security

- Traffic to third parties generally uses HTTPS where those services support it.
- Credentials live in app/guest storage; protect your device with a lock screen and avoid untrusted apps with backup/debug access.
- Android backup is disabled for NativeCode (`allowBackup=false`). Protect any separate device or filesystem backups that you create.

No method of electronic storage is 100% secure. Use official install/auth flows only.

---

## 7. Children’s privacy

NativeCode is a developer tool intended for adults and professional use. It is **not directed at children under 13** (or the age required by local law). Do not use the app if you are under the applicable age threshold.

---

## 8. AI-generated content

Optional AI CLIs run as third-party tools inside the guest environment. Outputs are generated by those vendors or local models you install. You are responsible for how you use those tools and for complying with each vendor’s terms.

**In-app:** Settings → **AI Safety & Report** provides third-party ToS/AUP links, vendor reporting links, and the NativeCode reporting form. Reports submit without leaving the app. Failed reports remain in a private, capped local retry queue. Source code, terminal history, files, and credentials are excluded unless you deliberately paste them into the description.

---

## 9. Your choices & deletion

| Action | Effect |
|--------|--------|
| Settings → GitHub Account → **LOGOUT** | Signs out GitHub CLI for current isolation method |
| Settings → AI CLI tools → per-tool **LOGOUT** or **CLEAR ALL AI CREDENTIALS** | Removes local AI tokens/keys for that method |
| Settings → AI CLI tools → **CLEAR SUITE FLAG** | Hides AI launchers until reinstall (bins may remain) |
| Settings → **AI Safety & Report** | In-app deletion guidance + vendor links |
| Delete projects / files in the guest | Removes those files from guest storage |
| **Clear app storage** (Android App info) | Wipes app data including the PRoot guest |
| **Uninstall** | Removes the app and its private data |

For account data held only by GitHub or AI vendors, manage deletion in those services’ account settings.

---

## 10. International users

Processing occurs on your device and at third-party servers wherever those providers operate. By using network features you understand data may cross borders under those providers’ terms.

---

## 11. Changes to this policy

We may update this file in the repository. The **Last updated** date at the top will change. Material changes should be reflected in the Play Console privacy policy URL and Data safety form when the app is distributed on Google Play.

---

## 12. Contact

Questions about this Privacy Policy or NativeCode data practices:

- **Email:** [zenithblue.dev@gmail.com](mailto:zenithblue.dev@gmail.com) (privacy, AI report, app concerns)
- **Developer:** zenithblue-oss (ZenithBlue / NativeCode)

---

## 13. Play Store note

When published on Google Play, the same policy text (or a hosted HTML copy of it) will be linked from:

1. Play Console → App content → Privacy policy  
2. In-app Settings → Privacy Policy  

Public policy URL: `https://zenithblue-dev.github.io/nativecode-ai/privacy/`
