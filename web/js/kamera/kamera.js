// Kamerayla plaka okuma katmanı (vatandaş sayfası, yalnızca ?kamera=1).
// openCamera() tam ekran bir katman açar, kamera önizlemesinde 3,2:1 plaka çerçevesi gösterir,
// "Çek"e basılınca yalnızca çerçevenin içini cihazda okur ve sayfaya bir karar döner.
// Görüntü hiçbir yere gönderilmez ve saklanmaz; katman kapanınca kamera durdurulur.

import { loadEngine, readPlateImage } from './okuyucu.js';
import { decide } from './plaka-kurallari.js';

let open = null; // aynı anda tek katman

function el(tag, attrs, children) {
  const e = document.createElement(tag);
  for (const k in attrs || {}) {
    if (k === 'text') e.textContent = attrs[k];
    else if (k === 'class') e.className = attrs[k];
    else e.setAttribute(k, attrs[k]);
  }
  (children || []).forEach((c) => e.appendChild(c));
  return e;
}

function buildOverlay() {
  const video = el('video', { class: 'cam-video', playsinline: '', muted: '', autoplay: '', 'aria-hidden': 'true' });
  video.muted = true;
  const frame = el('div', { class: 'cam-frame', 'aria-hidden': 'true' });
  const stage = el('div', { class: 'cam-stage' }, [video, el('div', { class: 'cam-mask', 'aria-hidden': 'true' }, [frame])]);
  const title = el('h2', { class: 'cam-title', id: 'camTitle', text: 'Plakayı çerçeveye hizalayın' });
  const status = el('p', { class: 'cam-status', id: 'camStatus', role: 'status', 'aria-live': 'polite', text: 'Kamera açılıyor…' });
  const bar = el('div', { class: 'cam-progress', role: 'progressbar', 'aria-label': 'Okuma aracı indiriliyor', 'aria-valuemin': '0', 'aria-valuemax': '100' }, [el('span')]);
  const foreign = el('input', { type: 'checkbox', id: 'camForeign' });
  const foreignLbl = el('label', { class: 'cam-check', for: 'camForeign' }, [foreign, el('span', { text: 'Yabancı plaka' })]);
  const shot = el('button', { type: 'button', class: 'btn btn-primary btn-lg cam-shot', id: 'camShot', disabled: '' }, [el('span', { text: 'Çek' })]);
  const cancel = el('button', { type: 'button', class: 'btn btn-ghost cam-cancel', id: 'camCancel', text: 'Vazgeç, elle yaz' });
  const panel = el('div', { class: 'cam-panel' }, [title, status, bar, foreignLbl, shot, cancel]);
  const root = el('div', { class: 'cam-overlay', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'camTitle', id: 'camOverlay' }, [stage, panel]);
  return { root, video, frame, status, bar, foreign, shot, cancel };
}

/** Çerçevenin ekrandaki yerini video karesindeki piksellere çevirip kırpar (video object-fit: cover). */
function cropFrame(video, frame) {
  const vw = video.videoWidth;
  const vh = video.videoHeight;
  const vr = video.getBoundingClientRect();
  const fr = frame.getBoundingClientRect();
  const scale = Math.max(vr.width / vw, vr.height / vh);
  const ox = vr.left + (vr.width - vw * scale) / 2;
  const oy = vr.top + (vr.height - vh * scale) / 2;
  const sx = Math.max(0, (fr.left - ox) / scale);
  const sy = Math.max(0, (fr.top - oy) / scale);
  const sw = Math.min(vw - sx, fr.width / scale);
  const sh = Math.min(vh - sy, fr.height / scale);
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(sw));
  c.height = Math.max(1, Math.round(sh));
  c.getContext('2d', { willReadFrequently: true }).drawImage(video, sx, sy, sw, sh, 0, 0, c.width, c.height);
  return c;
}

/**
 * Kamera katmanını açar. Dönüş (Promise):
 *  { kind: 'confirm', plate, foreign } | { kind: 'edit', guess, foreign } | { kind: 'none', foreign }
 *  | { kind: 'cancel' } | { kind: 'error', reason: 'izin' | 'desteklenmiyor' | 'kamera' | 'motor' }
 */
export function openCamera() {
  if (open) return open.promise;
  if (!window.isSecureContext || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    return Promise.resolve({ kind: 'error', reason: 'desteklenmiyor' });
  }
  const ui = buildOverlay();
  const prevFocus = document.activeElement;
  let stream = null;
  let done = false;
  let engineReady = false;
  let videoReady = false;
  let resolveFn;
  const promise = new Promise((r) => { resolveFn = r; });

  function stopStream() {
    if (stream) stream.getTracks().forEach((t) => t.stop());
    stream = null;
    ui.video.srcObject = null;
  }
  function finish(result) {
    if (done) return;
    done = true;
    stopStream();
    document.removeEventListener('visibilitychange', onVisibility);
    window.removeEventListener('pagehide', onHide);
    document.removeEventListener('keydown', onKey, true);
    ui.root.remove();
    document.body.classList.remove('cam-open');
    open = null;
    if (prevFocus && prevFocus.focus) prevFocus.focus({ preventScroll: true });
    resolveFn(result);
  }
  function onVisibility() { if (document.visibilityState === 'hidden') finish({ kind: 'cancel' }); }
  function onHide() { finish({ kind: 'cancel' }); }
  function onKey(e) {
    if (e.key === 'Escape') { e.preventDefault(); finish({ kind: 'cancel' }); return; }
    if (e.key === 'Tab') {
      // Odak katmanın içinde kalır
      const f = [ui.foreign, ui.shot, ui.cancel].filter((x) => !x.disabled);
      if (!f.length) return;
      const i = f.indexOf(document.activeElement);
      if (e.shiftKey && (i <= 0)) { e.preventDefault(); f[f.length - 1].focus(); }
      else if (!e.shiftKey && i === f.length - 1) { e.preventDefault(); f[0].focus(); }
    }
  }
  function refresh() {
    if (done) return;
    ui.shot.disabled = !(engineReady && videoReady);
    if (engineReady && videoReady) {
      ui.status.textContent = 'Hazır. Plaka çerçevenin içindeyken Çek\'e basın.';
      ui.bar.hidden = true;
    } else if (engineReady) {
      ui.status.textContent = 'Kamera açılıyor…';
    }
  }
  function setProgress(p) {
    const pct = Math.round(p * 100);
    ui.bar.firstChild.style.width = pct + '%';
    ui.bar.setAttribute('aria-valuenow', String(pct));
    if (!engineReady && !done) ui.status.textContent = 'Okuma aracı hazırlanıyor… %' + pct + (pct < 100 ? ' (yalnızca ilk kullanımda iner)' : '');
  }

  document.body.appendChild(ui.root);
  document.body.classList.add('cam-open');
  document.addEventListener('visibilitychange', onVisibility);
  window.addEventListener('pagehide', onHide);
  document.addEventListener('keydown', onKey, true);
  ui.cancel.addEventListener('click', () => finish({ kind: 'cancel' }));
  ui.cancel.focus();

  // Model indirme kamera açılırken arka planda başlar
  loadEngine(setProgress).then(() => { engineReady = true; refresh(); },
    () => finish({ kind: 'error', reason: 'motor' }));

  // Kamera akışı; sistem akışı kendiliğinden bitirirse (başka uygulama kamerayı aldı vb.) bir kez yeniden açılır
  let restarts = 0;
  function startStream() {
    videoReady = false;
    refresh();
    navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 1080 } }, audio: false })
      .then(async (s) => {
        if (done) { s.getTracks().forEach((t) => t.stop()); return; }
        stream = s;
        s.getVideoTracks().forEach((t) => t.addEventListener('ended', () => {
          if (done || stream !== s) return;
          stopStream();
          if (restarts++ < 1) startStream();
          else finish({ kind: 'error', reason: 'kamera' });
        }));
        ui.video.srcObject = s;
        try { await ui.video.play(); } catch (e) { /* sessiz video: genelde gerekmez */ }
        const ready = () => { if (stream !== s) return; videoReady = ui.video.videoWidth > 0; refresh(); };
        // Bazı tarayıcılarda boyut ilk karede 0 gelir: boyut ve oynatma olaylarında da yeniden bakılır
        ['loadeddata', 'playing', 'resize'].forEach((ev) => ui.video.addEventListener(ev, ready));
        if (ui.video.readyState >= 2) ready();
        if (!engineReady) ui.status.textContent = 'Okuma aracı hazırlanıyor… (yalnızca ilk kullanımda iner)';
      })
      .catch((e) => {
        const reason = e && (e.name === 'NotAllowedError' || e.name === 'SecurityError') ? 'izin' : 'kamera';
        finish({ kind: 'error', reason });
      });
  }
  startStream();

  ui.shot.addEventListener('click', async () => {
    if (ui.shot.disabled || done || !stream) return;
    ui.shot.disabled = true;
    ui.status.textContent = 'Okunuyor…';
    const foreign = ui.foreign.checked;
    try {
      const crop = cropFrame(ui.video, ui.frame);
      stopStream(); // kare alındı; kamera hemen kapanır
      const r = await readPlateImage(crop);
      const d = decide(r, foreign);
      finish({ kind: d.kind, plate: d.plate, guess: d.guess, foreign });
    } catch (e) {
      finish({ kind: 'none', foreign });
    }
  });

  open = { promise };
  return promise;
}
