// Flyt site: theme picker and the radial demo in the hero phone. No tracking, no external requests.
(() => {
  "use strict";

  // ---- Themes: the palettes bundled with Flyt (colors.toml) -------------------------------------
  const THEMES = [
    { id: "krets", name: "Krets", note: "Flyt's own", bg: "#101d25", bg2: "#1b3038", fg: "#c8d8d5", muted: "#31545c", accent: "#68d9d0", warm: "#f0cb83" },
    { id: "tokyo-night", name: "Tokyo Night", note: "enkia", bg: "#1a1b26", bg2: "#24283b", fg: "#a9b1d6", muted: "#414868", accent: "#7aa2f7", warm: "#e0af68" },
    { id: "gruvbox", name: "Gruvbox", note: "sainnhe", bg: "#282828", bg2: "#3c3836", fg: "#d4be98", muted: "#665c54", accent: "#7daea3", warm: "#d8a657" },
    { id: "everforest", name: "Everforest", note: "sainnhe", bg: "#2d353b", bg2: "#343f44", fg: "#d3c6aa", muted: "#475258", accent: "#7fbbb3", warm: "#dbbc7f" },
    { id: "kanagawa", name: "Kanagawa", note: "rebelot", bg: "#1f1f28", bg2: "#223249", fg: "#dcd7ba", muted: "#54546d", accent: "#7e9cd8", warm: "#c0a36e" },
    { id: "matte-black", name: "Matte Black", note: "Omarchy", bg: "#121212", bg2: "#1e1e1e", fg: "#bebebe", muted: "#333333", accent: "#e68e0d", warm: "#d35f5f" },
    { id: "catppuccin-latte", name: "Catppuccin Latte", note: "light", bg: "#eff1f5", bg2: "#dce0e8", fg: "#4c4f69", muted: "#acb0be", accent: "#1e66f5", warm: "#df8e1d", light: true },
    { id: "flexoki-light", name: "Flexoki Light", note: "light", bg: "#fffcf0", bg2: "#e6e4d9", fg: "#100f0f", muted: "#b7b5ac", accent: "#205ea6", warm: "#d0a215", light: true },
  ];
  const root = document.documentElement;
  const store = { get(k) { try { return localStorage.getItem(k); } catch { return null; } },
                  set(k, v) { try { localStorage.setItem(k, v); } catch { /* private mode */ } } };

  function applyTheme(id, remember) {
    const t = THEMES.find(x => x.id === id) || THEMES[0];
    for (const k of ["bg", "bg2", "fg", "muted", "accent", "warm"]) root.style.setProperty("--" + k, t[k]);
    root.dataset.theme = t.id;
    if (t.light) root.dataset.light = ""; else delete root.dataset.light;
    // Other themes get a ground drawn from their palette, as Flyt does on Home.
    root.style.setProperty("--ground", t.id === "krets" ? "" :
      `radial-gradient(120% 70% at 85% 95%, ${t.accent}55, transparent 60%), radial-gradient(90% 60% at 0% 60%, ${t.muted}, transparent 70%), linear-gradient(${t.bg2}, ${t.bg})`);
    if (t.id === "krets") root.style.removeProperty("--ground");
    document.querySelector('meta[name="theme-color"]')?.setAttribute("content", t.bg);
    document.querySelectorAll(".swatch").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.id === t.id)));
    if (remember) store.set("flyt-theme", t.id);
  }

  const swatches = document.getElementById("swatches");
  if (swatches) for (const t of THEMES) {
    const b = document.createElement("button");
    b.className = "swatch"; b.dataset.id = t.id; b.type = "button";
    b.style.setProperty("--sbg", t.bg); b.style.setProperty("--sfg", t.fg);
    b.innerHTML = `${t.name}<small>${t.note}</small><span class="dots">${[t.accent, t.warm, t.muted, t.fg].map(c => `<i style="background:${c}"></i>`).join("")}</span>`;
    b.addEventListener("click", () => { root.dataset.anim = ""; applyTheme(t.id, true); });
    swatches.append(b);
  }
  // ?theme=<id> opens the page in a palette, so a link can show it in someone's favourite theme.
  applyTheme(new URLSearchParams(location.search).get("theme") || store.get("flyt-theme") || "krets", false);

  // ---- Radial demo ------------------------------------------------------------------------------
  const svg = document.getElementById("demo");
  if (!svg) return;
  const NS = "http://www.w3.org/2000/svg";
  const el = (tag, attrs, parent) => { const e = document.createElementNS(NS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); parent?.append(e); return e; };
  const C = { x: 160, y: 430 }, R_IN = 26, R_OUT = 100, R_LABEL = 122, R_KIDS = 166;
  const rad = d => d * Math.PI / 180;
  const at = (deg, r) => ({ x: C.x + r * Math.cos(rad(deg)), y: C.y + r * Math.sin(rad(deg)) });
  const sectorAngle = i => -90 + i * 45;

  // Simple, brand-free glyphs for the demo apps.
  const ICONS = {
    camera: "M-8 -4h4l2-3h4l2 3h4v10h-16z M0 2m-3.5 0a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0",
    photos: "M-8 -6h16v12h-16z M-7 5l5-6 4 4 2-2 4 4 M4 -2m-1.6 0a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0",
    grid: "M-7 -7h6v6h-6z M1 -7h6v6h-6z M-7 1h6v6h-6z M1 1h6v6h-6z",
    pin: "M0 8c-5 -6 -7 -9 -7 -12a7 7 0 0 1 14 0c0 3 -2 6 -7 12z M0 -4m-2.5 0a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0",
    sun: "M0 0m-4 0a4 4 0 1 0 8 0a4 4 0 1 0 -8 0 M0 -9v3 M0 6v3 M-9 0h3 M6 0h3 M-6.4 -6.4l2 2 M4.4 4.4l2 2 M-6.4 6.4l2 -2 M4.4 -4.4l2 -2",
    clock: "M0 0m-8 0a8 8 0 1 0 16 0a8 8 0 1 0 -16 0 M0 -5v5l3.5 2",
  };
  const SLOTS = ["#photos", "#travel", "Messages", "#tools", "Search", "Phone", "Browser", "#music"];
  const KIDS = {
    "#photos": [["Camera", "camera"], ["Photos", "photos"], ["Gallery", "grid"]],
    "#travel": [["Maps", "pin"], ["Weather", "sun"], ["Clock", "clock"]],
  };
  // Each scene: drag toward a slot; for a tag, continue to one of its apps.
  const SCENES = [
    { slot: 0, kid: 0, words: ["Press where your thumb rests", "Drag toward <b>#photos</b>", "Its apps unfold", "Let go on <b>Camera</b>"] },
    { slot: 5, words: ["Press anywhere", "Drag toward <b>Phone</b>", "", "Let go. That's it."] },
    { slot: 1, kid: 2, words: ["Same move, different direction", "Drag toward <b>#travel</b>", "Its apps unfold", "Let go on <b>Clock</b>"] },
  ];

  const radial = document.getElementById("radial"), thumb = document.getElementById("thumb");
  const toast = document.getElementById("toast"), toastText = document.getElementById("toastText");
  const caption = document.getElementById("caption");

  const disc = el("g", {}, radial);
  el("circle", { cx: C.x, cy: C.y, r: R_OUT, style: "fill:var(--bg);opacity:.55" }, disc);
  const wedges = SLOTS.map((_, i) => {
    const a0 = sectorAngle(i) - 22.5, a1 = sectorAngle(i) + 22.5;
    const p = [at(a0, R_IN), at(a0, R_OUT), at(a1, R_OUT), at(a1, R_IN)];
    return el("path", { d: `M${p[0].x} ${p[0].y}L${p[1].x} ${p[1].y}A${R_OUT} ${R_OUT} 0 0 1 ${p[2].x} ${p[2].y}L${p[3].x} ${p[3].y}A${R_IN} ${R_IN} 0 0 0 ${p[0].x} ${p[0].y}Z`,
      style: "fill:var(--accent);opacity:0;stroke:var(--fg);stroke-opacity:.18" }, disc);
  });
  SLOTS.forEach((_, i) => { const a = sectorAngle(i) - 22.5, p = at(a, R_IN), q = at(a, R_OUT);
    el("line", { x1: p.x, y1: p.y, x2: q.x, y2: q.y, style: "stroke:var(--fg);stroke-opacity:.22" }, disc); });
  el("circle", { cx: C.x, cy: C.y, r: R_IN, style: "fill:var(--bg);stroke:var(--fg);stroke-opacity:.35" }, disc);
  const labels = SLOTS.map((name, i) => { const p = at(sectorAngle(i), R_LABEL);
    const t = el("text", { x: p.x, y: p.y + 4, "text-anchor": "middle", style: "fill:var(--fg);font:500 12.5px var(--sans);paint-order:stroke;stroke:var(--bg);stroke-width:3px;stroke-opacity:.6" }, disc);
    t.textContent = name; return t; });

  const kidLayer = el("g", {}, radial);
  function drawKids(slot) {
    kidLayer.replaceChildren();
    const list = KIDS[SLOTS[slot]] || [];
    return list.map(([label, icon], k) => {
      const p = at(sectorAngle(slot) + (k - (list.length - 1) / 2) * 21, R_KIDS);
      p.x = clamp(p.x, 26, 294);
      const g = el("g", { transform: `translate(${p.x} ${p.y})` }, kidLayer);
      const bg = el("circle", { r: 19, style: "fill:var(--bg2);stroke:var(--accent);stroke-width:1.5" }, g);
      el("path", { d: ICONS[icon], style: "fill:none;stroke:var(--fg);stroke-width:1.6;stroke-linejoin:round;stroke-linecap:round" }, g);
      return { g, bg, p, label };
    });
  }

  const ease = t => t < .5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2;
  const clamp = (v, a = 0, b = 1) => Math.min(b, Math.max(a, v));
  const lerp = (a, b, t) => a + (b - a) * t;
  const span = (t, a, b) => clamp((t - a) / (b - a));
  const SCENE_MS = 4300;

  let scene = -1, kids = [], lastWords = "";
  function setCaption(html) { if (html !== lastWords) { caption.innerHTML = html || "&nbsp;"; lastWords = html; } }

  function frame(t) {
    const idx = Math.floor(t / SCENE_MS) % SCENES.length, s = SCENES[idx], u = t % SCENE_MS;
    if (idx !== scene) { scene = idx; kids = drawKids(s.slot); }
    const tag = s.kid !== undefined;
    const target = at(sectorAngle(s.slot), tag ? 70 : 80);
    const kid = tag ? kids[s.kid] : null;

    // Timeline (ms): 0 idle · 600 press · 900 drag · 1500 at slot · 1800 kids · 2100 to kid · 2700 release · 3600 idle
    const press = span(u, 600, 850), out = span(u, 2750, 3050);
    const show = press * (1 - out);
    let pos = C;
    const m1 = ease(span(u, 900, 1500));
    pos = { x: lerp(C.x, target.x, m1), y: lerp(C.y, target.y, m1) };
    if (kid) { const m2 = ease(span(u, 1950, 2550)); pos = { x: lerp(pos.x, kid.p.x, m2), y: lerp(pos.y, kid.p.y, m2) }; }

    thumb.setAttribute("transform", `translate(${pos.x} ${pos.y})`);
    thumb.setAttribute("opacity", (press * (1 - span(u, 2700, 2850))).toFixed(3));
    radial.setAttribute("opacity", show.toFixed(3));
    const sc = lerp(.85, 1, ease(press));
    disc.setAttribute("transform", `translate(${C.x} ${C.y}) scale(${sc}) translate(${-C.x} ${-C.y})`);

    const reached = span(u, 1150, 1450);
    wedges.forEach((w, i) => w.setAttribute("style", w.getAttribute("style").replace(/opacity:[\d.]+/, "opacity:" + (i === s.slot ? (.12 + .45 * reached).toFixed(3) : "0"))));
    const kidsIn = tag ? span(u, 1600, 1900) : 0;
    kidLayer.setAttribute("opacity", kidsIn.toFixed(3));
    labels.forEach((l, i) => l.setAttribute("opacity", i === s.slot ? 1 : (1 - .7 * kidsIn).toFixed(3)));
    kids.forEach((k, i) => {
      const hot = tag && i === s.kid ? span(u, 2350, 2550) : 0;
      k.bg.setAttribute("style", `fill:${hot > .5 ? "var(--accent)" : "var(--bg2)"};stroke:var(--accent);stroke-width:1.5`);
      const z = lerp(.6, 1, ease(kidsIn)) * (1 + .15 * hot);
      k.g.setAttribute("transform", `translate(${k.p.x} ${k.p.y}) scale(${z})`);
    });

    const opened = tag ? kid.label : SLOTS[s.slot];
    toastText.textContent = "→ " + opened;
    toast.setAttribute("opacity", (span(u, 2800, 3000) * (1 - span(u, 3700, 3950))).toFixed(3));

    setCaption(u < 900 ? s.words[0] : u < 1650 ? s.words[1] : u < 2100 && tag ? s.words[2] : u < 4000 ? s.words[3] : s.words[0]);
  }

  const still = window.matchMedia("(prefers-reduced-motion: reduce)");
  let start = performance.now(), raf = 0, visible = true;
  function loop(now) { frame(now - start); raf = requestAnimationFrame(loop); }
  // ?t=<ms> freezes the demo at one moment (for posters and checks).
  const fixed = Number(new URLSearchParams(location.search).get("t"));
  function run() {
    cancelAnimationFrame(raf);
    document.querySelectorAll("video[autoplay]").forEach(v => still.matches ? v.pause() : v.play().catch(() => {}));
    if (fixed > 0) { frame(fixed); return; }
    if (still.matches) { frame(2500); setCaption("Press, drag toward a direction, let go."); return; }
    if (visible && !document.hidden) raf = requestAnimationFrame(loop);
  }
  new IntersectionObserver(e => { visible = e[0].isIntersecting; run(); }).observe(svg);
  document.addEventListener("visibilitychange", run);
  still.addEventListener?.("change", run);
  run();
})();
