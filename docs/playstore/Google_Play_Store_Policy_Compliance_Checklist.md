
# Google Play Store Policy Compliance Validation

## 1. Quick Pre-Submission Checklist

| Area | Requirement | Status | Note |
|---|---|---|---|
| Build format | Signed AAB format | **[BLOCKED: SIGNING]** | Play-release AAB passes bundletool 1.18.3 validation and installs as device-targeted splits, but the local release upload-key credentials are not configured. |
| Target API level | Target API 36+ | **[PASS]** | Play artifact targets API 37 and compiles with API 37. |
| 64-bit / 16 KB support | ARM64 and 16 KB page-compatible binaries | **[PASS]** | Play is ARM64-only. Every shipped ELF is AArch64/ELF64 with LOAD alignment of at least `0x4000`; legacy `libfast_pty_guard.so` and the 32-bit helper are full-build-only. |
| Developer account | Identity verification | **[USER-CONFIRMED]** | Play Console is ready to apply for production. |
| Android Developer Verification | Package + Signing Key | **[USER-CONFIRMED]** | Play Console setup completed. |
| Closed testing | 12 testers, 14 days | **[PASS]** | Play Console production-access gate is unlocked. |
| Privacy Policy | URL in console + in-app | **[PENDING PUBLISH]** | Corrected policy builds with zero Astro diagnostics in `nativecode-ai-foss`; publish it to the configured GitHub Pages URL before production submission. |
| Data safety form | Completed in console | **[USER-CONFIRMED]** | Recheck answers against the corrected policy after publishing it. |
| Account deletion | N/A | **[PASS]** | NativeCode has no first-party account creation. Third-party CLI accounts are managed by their providers. |
| Content rating | IARC questionnaire | **[USER-CONFIRMED]** | Play Console setup completed. |
| Target audience declaration | Accurate age targeting | **[USER-CONFIRMED]** | Intended for adult developers; not child-directed. |
| Ads | Non-deceptive/appropriate | **[PASS]** | No ad SDKs found in code. |
| Payments | Play Billing used | **[PASS]** | No Play Billing integration found; free app. |
| External code execution | No downloaded Android executable code | **[WARNING]** | Runtime and Android SDK/aapt2 are delivered through Play Asset Delivery. Play variant excludes remote installer scripts; user-initiated code runs only inside the isolated Linux development environment. Explain this clearly during review. |
| Play Asset Delivery | Valid on-demand packs | **[PASS]** | Bundletool validates `nativecode_runtime` and `nativecode_android_dev` as on-demand packs. Poco local testing resolved both slices; initial device download is 66,519,562 bytes. |
| Installing other apps | No hostile downloading | **[PASS]** | Play artifact excludes APK installers and `REQUEST_INSTALL_PACKAGES`. |
| Disabling device security | No bypassing Play Protect | **[PASS]** | No such code found. |

## Permissions Validation

| Permission | Status | Note |
|---|---|---|
| `MANAGE_EXTERNAL_STORAGE` | **[PASS]** | Absent from the signed Play artifact. |
| `REQUEST_INSTALL_PACKAGES` | **[PASS]** | Absent from the signed Play artifact. |
| `FOREGROUND_SERVICE` / `DATA_SYNC` / `SPECIAL_USE` | **[WARNING]** | Present for visible terminal/Linux sessions. Keep the Play Console FGS declaration aligned with `docs/policy/fgs-special-use-declaration.md`. |
| `READ_MEDIA_IMAGES` | **[PASS]** | Absent from the signed Play artifact; app uses scoped selection/FileProvider flows. |

## Conclusion
The Play-release AAB passes automated build, bundletool, 16 KB native alignment, permission, script, DEX-string, lint, PAD, and Poco device-smoke gates. It is not upload-ready until signed with the existing Play upload key. Before applying for production, publish the corrected privacy site and make the in-app report endpoint reachable. The isolated Linux/IDE execution model remains a manual-review risk and must be described accurately.
