---
name: make-vanilla-animation
description: Build animated shorts, title cards and explainers with plain HTML, CSS and JavaScript (canvas 2D, WebGL, CSS or Web Animations) and no animation library, then export a deterministic MP4 by capturing frames with headless Chrome and encoding with FFmpeg.
compatibility: NativeCode ARM64 PRoot; Node, the supplied Chrome wrapper and FFmpeg as installed in the guest
metadata:
  title: Make Vanilla Animation
  category: PROJECT
  group: Video and audio
  engine: plain HTML/CSS/JS + headless Chrome + FFmpeg
  status: authored; not independently device-tested
---

# Make Vanilla Animation

**Use for:** a short animated clip, loop, title sequence, chart or explainer when the user wants no framework or animation library (no GSAP, no Remotion). For the HyperFrames workflow use the make-motion-video skill instead.

**Deliver:** one editable `index.html` that plays live in a browser, a captured PNG sequence, and `out.mp4`.

## Rule 1: everything is a function of time

Write one `render(t)` (seconds) that draws the whole frame from `t` alone. No `Date.now()`, no `setInterval`, no unseeded `Math.random()`, no state that depends on earlier frames. The live preview calls `render(performance.now()/1000)`; the exporter calls `render(frame / fps)`. This makes the video identical on every run and lets you scrub.

## Template

```html
<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  html,body{margin:0;background:#101114}
  canvas{display:block;width:100vw;height:100vh;object-fit:contain}
</style>
<canvas id="c" width="1280" height="720"></canvas>
<script>
const W = 1280, H = 720, DURATION = 5, FPS = 30;
const ctx = document.getElementById('c').getContext('2d');
const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
const ease = x => 1 - Math.pow(1 - clamp(x), 3);          // easeOutCubic
const seg = (t, from, to) => clamp((t - from) / (to - from)); // 0..1 inside [from,to]
function rng(seed){ return () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296; }

function render(t) {
  ctx.fillStyle = '#101114'; ctx.fillRect(0, 0, W, H);
  const a = ease(seg(t, 0.2, 1.2));
  ctx.globalAlpha = a;
  ctx.fillStyle = '#f2f2f0';
  ctx.font = '700 96px system-ui, sans-serif';
  ctx.textAlign = 'center';
  ctx.fillText('Hello motion', W / 2, H / 2 + 32 * (1 - a));
  ctx.globalAlpha = 1;
}
window.render = render; window.DURATION = DURATION; window.FPS = FPS;
if (!new URLSearchParams(location.search).has('export')) {
  const t0 = performance.now();
  (function loop(){ render(((performance.now() - t0) / 1000) % DURATION); requestAnimationFrame(loop); })();
} else render(0);
</script>
```

## Building blocks

- **Timeline**: define scenes as `[start, end]` ranges and map time with `seg()`; stagger items with `seg(t, i * 0.08, i * 0.08 + 0.5)`.
- **Easing**: write the few curves you need (`ease`, easeInOutCubic, a spring `1 - Math.exp(-6 * x) * Math.cos(12 * x)`), keep them in one place.
- **Canvas 2D**: shapes, text, gradients, `ctx.save/restore`, `ctx.filter` for blur. Prefer vector drawing over images. Wait for fonts with `document.fonts.ready` before the first export frame.
- **CSS and WAAPI** alternative: create animations paused and seek them: `document.getAnimations().forEach(a => { a.pause(); a.currentTime = t * 1000; })`. Works for HTML layouts, text and SVG.
- **WebGL**: one fragment shader with a `u_time` uniform set from `t`; render into a canvas created with `preserveDrawingBuffer: true` so screenshots capture it.
- **Audio**: synthesize or supply a file; mux it in the FFmpeg step. Never rely on live `AudioContext` timing for export.
- **Design**: apply the frontend-design skill rules: one idea per scene, strong hierarchy, 5 to 7 second scenes at most, readable text (at least 48 px at 1280 x 720), safe margins of 5 percent.

## Export (headless Chrome + FFmpeg)

Work as the `flux` user. Reuse the supplied browser wrapper `/home/flux/.local/bin/google-chrome` and the installed `ffmpeg`; do not reinstall them.

```sh
cd <project> && npm init -y >/dev/null && npm i puppeteer-core
```

`export.mjs`:

```js
import puppeteer from 'puppeteer-core';
import { mkdirSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
const browser = await puppeteer.launch({
  executablePath: '/home/flux/.local/bin/google-chrome',
  args: ['--no-sandbox', '--force-device-scale-factor=1'],
});
const page = await browser.newPage();
await page.setViewport({ width: 1280, height: 720 });
await page.goto(pathToFileURL(process.cwd() + '/index.html').href + '?export');
await page.evaluate(() => document.fonts.ready);
const { d, fps } = await page.evaluate(() => ({ d: DURATION, fps: FPS }));
mkdirSync('frames', { recursive: true });
for (let i = 0; i < d * fps; i++) {
  await page.evaluate(t => render(t), i / fps);
  await page.screenshot({ path: `frames/${String(i).padStart(5, '0')}.png`, clip: { x: 0, y: 0, width: 1280, height: 720 } });
}
await browser.close();
```

```sh
node export.mjs
ffmpeg -y -framerate 30 -i frames/%05d.png -c:v libx264 -pix_fmt yuv420p -crf 18 -movflags +faststart out.mp4
ffprobe -v error -show_entries format=duration -of csv=p=0 out.mp4
```

Add `-i audio.wav -shortest -c:a aac` to include sound. For a GIF use `-vf "fps=15,scale=640:-1:flags=lanczos"`.

## Verify

1. Open `index.html` in the in-app preview or browser and scrub a few times: nothing flickers, nothing depends on wall-clock time.
2. Look at frames 0, the middle and the last one (`frames/00000.png` and so on). Text is readable, nothing is cut off, the end state is intentional.
3. Check the MP4 duration equals `DURATION` and that it plays.
4. Cost: a 5 s, 30 fps, 1280 x 720 clip is 150 screenshots. Lower `FPS` to 24 or the size to 960 x 540 on slow devices; do not skip frames in the middle.
