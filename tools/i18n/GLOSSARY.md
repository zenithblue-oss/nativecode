# NativeCode translation glossary

Machine translation, needs a native-speaker review pass per language (owner). Priority for review: `onb_*`
(onboarding), upgrade / Pro / billing strings (`main_*pro*`, `main_upgrade*`, `main_free*`), error messages.

## Stays in English (never translate, never transliterate)
terminal, shell (as in "Linux shell"), PRoot, chroot, rootfs, glibc, Alpaquita, Linux, Android, Termux-X11, X11, XFCE, Phosh,
Pro (the plan name, also PRO), Free is translated, agent / CLI tool names: opencode, Codex, Claude Code, Qwen Code, Antigravity,
agy, Grok, Kiro, Hermes, Gemini, GitHub, Git, gh, SSH, SFTP, APK, SDK, NDK, JDK, MCP, ACP, API, URL, GPU, CPU, RAM, VPU, Vulkan,
OpenGL, Mesa, Turnip, PanVK, VirGL, Zink, Wi-Fi, USB, ADB, npm, Node.js, Python, Gradle, NativeCode (product name), Play Store,
Google Play, file names, paths, commands, key caps (Ctrl, Alt, Esc, Tab), version numbers, units (MB, GB, ms).

## Rules
- Keep every placeholder: `%1$s`, `%2$s`, ... exactly once each (order of words may change, the number identity must not), `%%` stays `%%`.
- XML escaping as in the English file: `\'` for apostrophes, `\"` for quotes, `&amp;` `&lt;` `&gt;`, `\n` for newlines.
- Fully UPPERCASE English strings stay UPPERCASE in Latin/Cyrillic scripts (labels on buttons); not applicable for ja, ko, zh, ar.
- Short UI labels must stay short: buttons and tabs are narrow. Prefer the shortest natural word.
- Keep punctuation style: "…", "·", "→", "•" and leading bullets.
- Arabic: right-to-left; leave Latin technical terms and numbers as they are.
- Formal "you" where the language has it (de Sie, fr vous, es usted-neutral/tú is fine for apps: use tú/usted consistently; ru вы; tr siz; it Lei/tu consistent).
