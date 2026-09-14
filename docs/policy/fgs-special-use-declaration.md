# Foreground Service `specialUse` — Play Console declaration

**App:** NativeCode (`com.zenithblue.nativecode`)  
**Type:** `specialUse`  
**Services:** `BackgroundService`, `TerminalService`
**Demo video:** [`fgs-special-use-demo.mp4`](./fgs-special-use-demo.mp4) — **~50s live UI**  
Path: open app → Terminal → Alpaquita Shell (session + FGS) → shade shows **NativeCode — Terminal** → Settings → **START XFCE DESKTOP** → shade shows **Desktop Session** + Terminal together. Onboarding install uses `BackgroundService` with **Environment Setup**.

## Justification (≤ 300 characters — paste into Console)

```
NativeCode keeps user-started Linux terminals, project shells, XFCE desktop, and onboarding Alpaquita installation alive after leaving the app. specialUse fits this developer environment. Ongoing notifications show status; close sessions, Stop Desktop, or finish setup ends it.
```

**Character count:** 273

## User-visible stop paths

| Session | Notification title | How user stops |
|---------|-------------------|----------------|
| App terminal | NativeCode — Terminal | Close all terminal tabs in app |
| Project terminal | NativeCode — Project Terminal | Close project terminal tabs |
| XFCE desktop | NativeCode — Desktop Session | Settings hub → Stop XFCE Desktop |
| Onboarding install | NativeCode — Environment Setup | Auto-stops on setup finish or fail |

## Manifest properties

Each FGS declares `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` with a short subtype string (see `app/src/main/AndroidManifest.xml`).

## Reviewer notes

- Local-first: guest data stays in the app-private PRoot environment on device.
- FGS starts only after the user opens a terminal session, starts XFCE, or starts onboarding installation.
- `POST_NOTIFICATIONS` requested on Android 13+ so the ongoing notification is visible.
