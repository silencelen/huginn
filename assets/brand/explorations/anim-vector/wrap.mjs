#!/usr/bin/env node
// wrap.mjs <anim.svg> <anim.html> "<title>"
// Wraps an animated SVG into a self-contained preview page with the window.seek(t)
// hook capture.mjs relies on. The SVG is the single source; the page is derived.
//   ?t=<seconds>     freeze the animation at t (for screenshots)
//   ?theme=dark      bone mark on the app's dark ground instead of ink on bone
//   ?freeze=1        start with every animation paused (capture.mjs passes this)
//   ?static=1        disable every animation: what a renderer that applies CSS but no
//                    animations (thumbnailers, librsvg, Batik) shows, i.e. the resting state
import { readFileSync, writeFileSync } from 'node:fs';
const [,, svgPath, htmlPath, title = 'Huginn animation'] = process.argv;
const svg = readFileSync(svgPath, 'utf8').replace(/<\?xml[^>]*>\s*/, '');
const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title>
<script>
  // ?freeze=1 (used by capture.mjs): pause every animation before it ever runs, so a
  // later seek(t) renders from a clean initial state rather than a stale compositor frame.
  // ?static=1: no animations at all (the resting state a non-animating renderer shows).
  const q0 = new URLSearchParams(location.search);
  if (q0.has('freeze') || q0.has('static')) {
    const s = document.createElement('style');
    s.textContent = q0.has('static') ? '* { animation: none !important; }' : '* { animation-play-state: paused !important; }';
    document.head.appendChild(s);
  }
</script>
<style>
  html { --ground: #E8E2DA; }
  html[data-theme="dark"] { --ground: #12100F; }
  /* the SVG scopes its own --ink on the svg element; the dark page overrides it there */
  html[data-theme="dark"] svg.stage { --ink: #E8E2DA; }
  html, body { margin: 0; height: 100%; background: var(--ground); }
  body { display: grid; place-items: center; overflow: hidden; }
  svg.stage { display: block; width: min(100vw, 100vh); height: min(100vw, 100vh); }
</style>
</head>
<body>
${svg.replace(/<svg /, '<svg class="stage" ')}
<script>
  // Freeze every animation on the page at t seconds (delays included, so t=0 is the
  // start of the timeline). Returns how many animations were seeked.
  window.seek = function (t) {
    const anims = document.getAnimations();
    // never exactly 0: Chromium treats a paused animation at currentTime 0 as not started and leaves
    // the compositor layer at its last rasterised (pre-seek) state; 1 ms is invisible and correct
    for (const a of anims) { a.pause(); a.currentTime = Math.max(t, 0.001) * 1000; }
    return anims.length;
  };
  const q = new URLSearchParams(location.search);
  if (q.get('theme') === 'dark') document.documentElement.dataset.theme = 'dark';
  if (q.has('t')) addEventListener('load', () => window.seek(parseFloat(q.get('t'))));
</script>
</body>
</html>
`;
writeFileSync(htmlPath, html);
console.log(htmlPath);
