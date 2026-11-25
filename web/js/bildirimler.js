// Yönetici bildirim zili (yalnız ADMIN): üst çubukta zil + okunmamış rozeti + açılır liste.
// Veri girişte bir kez yüklenir; sonra NOTIFICATION_CREATED olayında ve sekme görünür olunca (en fazla 5 dk'da bir) yenilenir.
(() => {
    const $ = id => document.getElementById(id);
    const MIN_GAP = 5 * 60 * 1000;
    const TYPES = {
        SYSTEM_UPDATED: { label: 'Güncelleme', cls: 'badge-primary' },
        ANNOUNCEMENT: { label: 'Duyuru', cls: 'badge-sub' },
        MAINTENANCE: { label: 'Planlı bakım', cls: 'badge-warning' },
        ADMIN_PASSWORD_RESET: { label: 'Şifre', cls: 'badge-danger' },
        LICENSE_UPDATED: { label: 'Lisans', cls: 'badge-success' }
    };
    let active = false, items = [], unread = 0, lastLoad = 0, seq = 0, open = false, failed = false;

    const BELL = '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/></svg>';

    function relTime(iso) {
        const t = new Date(iso).getTime();
        if (isNaN(t)) return '';
        const m = Math.max(0, Math.round((Date.now() - t) / 60000));
        if (m < 1) return 'şimdi';
        if (m < 60) return `${m} dk önce`;
        const h = Math.floor(m / 60);
        if (h < 24) return `${h} sa önce`;
        const d = Math.floor(h / 24);
        return d < 30 ? `${d} gün önce` : new Date(iso).toLocaleDateString('tr-TR', { day: 'numeric', month: 'short', year: 'numeric' });
    }
    function expired(n) { return n.type === 'MAINTENANCE' && n.validUntil && new Date(n.validUntil).getTime() < Date.now(); }

    function build() {
        const host = $('notifHost');
        if (!host || host.dataset.built) return;
        host.dataset.built = '1';
        host.innerHTML = `
            <style>
                #notifHost { position: relative; }
                .nf-btn { position: relative; }
                .nf-count { position: absolute; top: 2px; right: 2px; min-width: 18px; height: 18px; padding: 0 5px; border-radius: 999px; background: var(--danger); color: var(--on-danger); font-size: 11px; font-weight: 800; line-height: 18px; text-align: center; }
                .nf-count[hidden] { display: none; }
                .nf-panel { position: absolute; right: 0; top: calc(100% + 8px); z-index: 60; width: min(380px, calc(100vw - 32px)); max-height: min(70vh, 520px); display: flex; flex-direction: column; background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-md); box-shadow: var(--shadow); }
                .nf-panel[hidden] { display: none; }
                .nf-head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-2); padding: var(--s-3) var(--s-4); border-bottom: 1px solid var(--border); }
                .nf-head h2 { margin: 0; font-size: 16px; }
                .nf-list { list-style: none; margin: 0; padding: 0; overflow-y: auto; }
                .nf-empty { padding: var(--s-5) var(--s-4); color: var(--text-2); text-align: center; font-size: 14px; }
                .nf-item { display: block; width: 100%; text-align: left; background: none; border: 0; border-bottom: 1px solid var(--border); padding: var(--s-3) var(--s-4); min-height: var(--tap); font: inherit; color: var(--text); cursor: pointer; }
                .nf-list li:last-child .nf-item { border-bottom: 0; }
                .nf-item:hover, .nf-item:focus-visible { background: var(--surface-2); }
                .nf-item.unread { background: var(--primary-soft); color: var(--on-primary-soft); }
                .nf-item.old { opacity: .55; }
                .nf-row { display: flex; align-items: center; justify-content: space-between; gap: var(--s-2); margin-bottom: 4px; }
                .nf-time { font-size: 12px; color: var(--text-2); white-space: nowrap; }
                .nf-item.unread .nf-time, .nf-item.unread .nf-body { color: inherit; }
                .nf-title { display: block; font-weight: 700; font-size: 14px; overflow-wrap: anywhere; }
                .nf-body { display: block; margin-top: 2px; font-size: 13px; color: var(--text-2); overflow-wrap: anywhere; white-space: pre-wrap; }
                .nf-sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
                @media (max-width: 480px) { .nf-panel { position: fixed; left: 16px; right: 16px; top: 64px; width: auto; } }
            </style>
            <button type="button" class="btn btn-secondary btn-icon nf-btn" id="nfBtn" aria-haspopup="true" aria-expanded="false" aria-controls="nfPanel" aria-label="Bildirimler">${BELL}<span class="nf-count" id="nfCount" hidden></span></button>
            <div class="nf-panel" id="nfPanel" role="region" aria-label="Bildirimler" hidden>
                <div class="nf-head"><h2>Bildirimler</h2><button type="button" class="btn btn-secondary" id="nfAll">Tümünü okundu say</button></div>
                <ul class="nf-list" id="nfList"></ul>
            </div>`;
        $('nfBtn').addEventListener('click', () => (open ? close(true) : openPanel()));
        $('nfAll').addEventListener('click', markAll);
        $('nfList').addEventListener('click', e => {
            const b = e.target.closest('button[data-id]');
            if (b) markOne(Number(b.dataset.id));
        });
        document.addEventListener('keydown', e => { if (open && e.key === 'Escape') { e.stopPropagation(); close(true); } });
        document.addEventListener('click', e => { if (open && !$('notifHost').contains(e.target)) close(false); });
        $('notifHost').addEventListener('focusout', e => {
            if (open && e.relatedTarget && !$('notifHost').contains(e.relatedTarget)) close(false);
        });
    }

    function render() {
        const c = $('nfCount');
        if (!c) return;
        c.hidden = unread <= 0;
        c.textContent = unread > 99 ? '99+' : String(unread);
        $('nfBtn').setAttribute('aria-label', unread > 0 ? `Bildirimler, ${unread} okunmamış` : 'Bildirimler');
        $('nfAll').disabled = unread <= 0;
        $('nfList').innerHTML = items.length ? items.map(n => {
            const t = TYPES[n.type] || { label: n.type || 'Bildirim', cls: 'badge-neutral' };
            const old = expired(n);
            return `<li><button type="button" class="nf-item${n.read ? '' : ' unread'}${old ? ' old' : ''}" data-id="${escapeHtml(n.id)}">
                <span class="nf-row"><span class="badge ${t.cls}">${escapeHtml(t.label)}${old ? ' · geçmiş' : ''}</span><span class="nf-time">${escapeHtml(relTime(n.createdAt))}</span></span>
                <span class="nf-title">${n.read ? '' : '<span class="nf-sr">Okunmamış: </span>'}${escapeHtml(n.title)}</span>
                ${n.body ? `<span class="nf-body">${escapeHtml(n.body)}</span>` : ''}
            </button></li>`;
        }).join('') : `<li class="nf-empty">${failed ? 'Bildirimler alınamadı.' : 'Bildirim yok.'}</li>`;
    }

    function openPanel() {
        open = true;
        $('nfPanel').hidden = false;
        $('nfBtn').setAttribute('aria-expanded', 'true');
        render();
        const first = $('nfList').querySelector('button') || $('nfAll');
        first.focus();
    }
    function close(restoreFocus) {
        open = false;
        $('nfPanel').hidden = true;
        $('nfBtn').setAttribute('aria-expanded', 'false');
        if (restoreFocus) $('nfBtn').focus();
    }

    async function load(force) {
        if (!active) return;
        const now = Date.now();
        if (!force && now - lastLoad < MIN_GAP) return;
        lastLoad = now;
        const my = ++seq;
        try {
            const d = await apiJson('GET', '/api/admin/notifications?limit=30');
            if (my !== seq || !active) return;
            items = (d && d.items) || [];
            unread = d && d.unread != null ? Number(d.unread) : items.filter(n => !n.read).length;
            failed = false;
        } catch (e) {
            if (e.name === 'AuthError' || my !== seq) return;
            failed = true;
        }
        render();
    }

    async function markOne(id) {
        const n = items.find(x => x.id === id);
        if (!n || n.read) return;
        n.read = true; unread = Math.max(0, unread - 1); render();
        try { await apiJson('POST', `/api/admin/notifications/${encodeURIComponent(id)}/read`); }
        catch (e) { if (e.name !== 'AuthError') load(true); }
    }
    async function markAll() {
        if (unread <= 0) return;
        items.forEach(n => { n.read = true; }); unread = 0; render();
        try { await apiJson('POST', '/api/admin/notifications/read-all'); }
        catch (e) { if (e.name !== 'AuthError') load(true); }
    }

    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') load(false); });

    window.Bildirimler = {
        start() {
            if (currentRole() !== 'ADMIN') return;
            active = true; items = []; unread = 0; lastLoad = 0;
            build(); render();
            load(true);
        },
        stop() {
            active = false; seq++; items = []; unread = 0;
            if ($('nfPanel')) { close(false); render(); }
        }
    };
    window.PAGES = window.PAGES || {};
    window.PAGES.bildirimler = {
        onEvent(type) { if (type === 'NOTIFICATION_CREATED') load(true); }
    };
})();
