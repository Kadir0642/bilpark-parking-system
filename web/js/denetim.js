// Denetim kaydı sayfası: kim, ne zaman, ne yaptı. Salt okunur; CSV/Excel indirme okunur modda da çalışır.
(() => {
    const $ = id => document.getElementById(id);
    let drawn = false;
    let page = 0;
    let actions = [];          // [{action,label}]
    let actionLabels = {};     // action -> label
    let rows = [];             // son yüklenen sayfa (ayrıntı için)
    let timer = null;
    let reqSeq = 0;            // geç gelen eski yanıtı yok saymak için

    function ymd(d) { return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`; }

    // ---- İskelet ----
    function draw() {
        const to = new Date(), from = new Date(Date.now() - 30 * 86400000);
        $('sec-denetim').innerHTML = `
        <div class="page-head"><div><h1>Denetim kaydı</h1>
            <div class="page-sub">Sistemde kim, ne zaman, ne yaptı; bakım işlemleri varsayılan olarak gizlidir.</div></div>
            <div class="toolbar">
                <button class="btn btn-success" id="auXlsx"><i class="fa-solid fa-file-excel" aria-hidden="true"></i> Excel</button>
                <button class="btn btn-secondary" id="auCsv"><i class="fa-solid fa-file-csv" aria-hidden="true"></i> CSV</button>
            </div></div>
        <div class="card" style="margin-top:16px;"><div class="card-body">
            <div class="filters">
                <label class="field">Başlangıç<input class="input" type="date" id="auFrom" value="${ymd(from)}"></label>
                <label class="field">Bitiş<input class="input" type="date" id="auTo" value="${ymd(to)}"></label>
                <label class="field">İşlem<select class="select" id="auAction"><option value="">Tümü</option></select></label>
                <label class="field">Kişi<input class="input" type="text" id="auActor" placeholder="Kullanıcı adı" autocomplete="off"></label>
                <label class="field">Rol<select class="select" id="auRole">
                    <option value="">Tümü</option>
                    <option value="DEVELOPER">Geliştirici işlemleri</option>
                    <option value="ADMIN">Yönetici</option>
                    <option value="OFFICER">Memur</option>
                </select></label>
                <label class="field">Ara<input class="input" type="search" id="auQ" placeholder="Özet metninde ara" autocomplete="off"></label>
            </div>
            <div style="display:flex; flex-wrap:wrap; align-items:center; gap:8px 16px; margin-top:12px;">
                <label style="display:inline-flex; align-items:center; gap:8px; min-height:var(--tap); font-weight:600; cursor:pointer;"><input type="checkbox" id="auMaint" style="width:18px; height:18px;"> Bakım işlemlerini göster</label>
                <span class="muted" id="auHidden" role="status" hidden style="font-size:14px;"></span>
            </div>
            <div class="alert alert-danger" id="auErr" hidden style="margin-top:12px;"></div>
        </div>
        <div class="table-wrap"><table class="table">
            <thead><tr><th>Zaman</th><th>Kişi</th><th>İşlem</th><th>Özet</th><th>Cihaz / IP</th></tr></thead>
            <tbody id="auBody"><tr><td colspan="5" class="table-empty">Yükleniyor…</td></tr></tbody>
        </table></div>
        <div class="pager"><span id="auInfo"></span>
            <span class="toolbar"><button class="btn btn-secondary" id="auPrev">Önceki</button>
            <button class="btn btn-secondary" id="auNext">Sonraki</button></span></div>
        </div>
        <dialog id="auDlg"><div class="dlg-body" id="auDlgBody"></div></dialog>`;

        ['auFrom', 'auTo', 'auAction', 'auRole', 'auMaint'].forEach(id => { $(id).onchange = () => { page = 0; load(); }; });
        ['auActor', 'auQ'].forEach(id => {
            $(id).oninput = () => { clearTimeout(timer); timer = setTimeout(() => { page = 0; load(); }, 400); };
        });
        // Rol "Geliştirici" seçilirse sunucu bakım işlemlerini kendiliğinden dahil eder; kutu da bunu yansıtır
        $('auRole').addEventListener('change', () => { if ($('auRole').value === 'DEVELOPER') $('auMaint').checked = true; });
        $('auHidden').onclick = e => { if (e.target.closest('button')) { $('auMaint').checked = true; page = 0; load(); } };
        $('auPrev').onclick = () => { if (page > 0) { page--; load(); } };
        $('auNext').onclick = () => { page++; load(); };
        $('auCsv').onclick = e => exportFile(e.currentTarget, 'csv');
        $('auXlsx').onclick = e => exportFile(e.currentTarget, 'xlsx');
        $('auBody').onclick = e => { const tr = e.target.closest('tr[data-i]'); if (tr) openDetail(Number(tr.dataset.i)); };
        $('auBody').onkeydown = e => {
            if (e.key !== 'Enter') return;
            const tr = e.target.closest('tr[data-i]'); if (tr) openDetail(Number(tr.dataset.i));
        };
        drawn = true;
    }

    function filterParams() {
        const q = new URLSearchParams();
        const map = { from: 'auFrom', to: 'auTo', action: 'auAction', actor: 'auActor', actorRole: 'auRole', q: 'auQ' };
        Object.keys(map).forEach(k => { const v = $(map[k]).value.trim(); if (v) q.set(k, v); });
        q.set('includeMaintenance', $('auMaint').checked || $('auRole').value === 'DEVELOPER' ? 'true' : 'false');
        return q;
    }

    async function loadActions() {
        try {
            const list = await apiJson('GET', '/api/admin/audit/actions');
            actions = Array.isArray(list) ? list : [];
            actionLabels = {};
            actions.forEach(a => { actionLabels[a.action] = a.label; });
            const sel = $('auAction'), cur = sel.value;
            sel.innerHTML = '<option value="">Tümü</option>' +
                actions.map(a => `<option value="${escapeHtml(a.action)}">${escapeHtml(a.label || a.action)}</option>`).join('');
            sel.value = cur;
        } catch (e) { /* liste gelmezse ham kodlar gösterilir */ }
    }

    function roleBadge(role) {
        if (role === 'DEVELOPER') return '<span class="badge badge-warning">Geliştirici</span>';
        if (role === 'ADMIN') return '<span class="badge badge-primary">Yönetici</span>';
        if (role === 'OFFICER') return '<span class="badge badge-neutral">Memur</span>';
        return '<span class="badge badge-sub">Sistem</span>';
    }
    function actionLabel(a) { return actionLabels[a] || a || '-'; }

    async function load() {
        const seq = ++reqSeq;
        const q = filterParams();
        q.set('page', page); q.set('size', PAGE_SIZE);
        const err = $('auErr');
        try {
            const d = await apiJson('GET', `/api/admin/audit?${q}`);
            if (seq !== reqSeq) return;
            err.hidden = true;
            rows = (d && d.content) || [];
            $('auBody').innerHTML = rows.map((r, i) => `<tr data-i="${i}" tabindex="0" style="cursor:pointer;">
                <td class="col-muted num">${r.createdAt ? escapeHtml(fmtFull(r.createdAt)) : '-'}</td>
                <td>${r.actorUsername ? `<b>${escapeHtml(r.actorUsername)}</b> ` : '<span class="muted">—</span> '}${roleBadge(r.actorRole)}</td>
                <td>${escapeHtml(actionLabel(r.action))}</td>
                <td style="min-width:220px;">${escapeHtml(r.summary || '')}</td>
                <td class="col-muted">${escapeHtml(r.deviceId ? shortDevice(r.deviceId) : '-')}<div style="font-size:13px;">${escapeHtml(r.ipAddress || '')}</div></td>
            </tr>`).join('') || '<tr><td colspan="5" class="table-empty">Bu filtreyle kayıt yok.</td></tr>';
            const hid = d && Number(d.maintenanceHidden) > 0 && !$('auMaint').checked ? Number(d.maintenanceHidden) : 0;
            const hn = $('auHidden');
            hn.hidden = !hid;
            hn.innerHTML = hid ? `${escapeHtml(hid)} bakım işlemi gizlendi · <button type="button" class="link-btn" style="min-height:0; color:var(--primary); text-decoration:underline;">Göster</button>` : '';
            const total = d ? d.totalElements : 0, pages = Math.max(1, d ? d.totalPages : 1);
            $('auInfo').textContent = total ? `${total} kayıt · Sayfa ${page + 1} / ${pages}` : '0 kayıt';
            $('auPrev').disabled = page <= 0;
            $('auNext').disabled = page >= pages - 1;
        } catch (e) {
            if (e.name === 'AuthError' || seq !== reqSeq) return;
            if (e.code === 'VALIDATION_ERROR') { err.textContent = e.message; err.hidden = false; }
            else $('auBody').innerHTML = `<tr><td colspan="5" class="table-empty">${escapeHtml(e.message)}</td></tr>`;
        }
    }

    async function exportFile(btn, fmt) {
        const q = filterParams();
        q.set('format', fmt);
        const old = btn.innerHTML;
        btn.disabled = true; btn.textContent = 'Hazırlanıyor…';
        try { await downloadFile(`/api/admin/audit/export?${q}`, `denetim-kaydi.${fmt}`); }
        finally { btn.innerHTML = old; btn.disabled = false; }
    }

    // ---- Ayrıntı ----
    function jsonBlock(obj, other) {
        if (obj == null) return '<span class="muted">Kayıt yok.</span>';
        if (typeof obj !== 'object') return `<pre style="margin:0;white-space:pre-wrap;">${escapeHtml(String(obj))}</pre>`;
        const keys = Object.keys(obj);
        if (!keys.length) return '<span class="muted">Boş.</span>';
        return `<dl class="tar-grid">${keys.map(k => {
            const v = obj[k];
            const s = v !== null && typeof v === 'object' ? JSON.stringify(v) : String(v);
            const changed = other && typeof other === 'object' && JSON.stringify(other[k]) !== JSON.stringify(v);
            return `<div class="tar-item"${changed ? ' style="background:var(--warning-soft);color:var(--on-warning-soft);border-radius:6px;padding:2px 6px;"' : ''}>
                <dt>${escapeHtml(k)}</dt><dd style="word-break:break-word;">${escapeHtml(s)}</dd></div>`;
        }).join('')}</dl>`;
    }
    function openDetail(i) {
        const r = rows[i];
        if (!r) return;
        $('auDlgBody').innerHTML = `
            <h2>${escapeHtml(actionLabel(r.action))}</h2>
            <p class="muted" style="margin:0 0 12px;">${r.createdAt ? escapeHtml(fmtFull(r.createdAt)) : ''} · ${escapeHtml(r.actorUsername || 'Sistem')} ${roleBadge(r.actorRole)}</p>
            <p>${escapeHtml(r.summary || '')}</p>
            <dl class="tar-grid">
                <div class="tar-item"><dt>Varlık</dt><dd>${escapeHtml(r.entityType || '-')}${r.entityId ? ' #' + escapeHtml(r.entityId) : ''}</dd></div>
                <div class="tar-item"><dt>Cihaz</dt><dd style="word-break:break-all;">${escapeHtml(r.deviceId || '-')}</dd></div>
                <div class="tar-item"><dt>IP adresi</dt><dd>${escapeHtml(r.ipAddress || '-')}</dd></div>
            </dl>
            <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:16px;margin-top:12px;">
                <div><div class="sub-h">Önce</div>${jsonBlock(r.before, r.after)}</div>
                <div><div class="sub-h">Sonra</div>${jsonBlock(r.after, r.before)}</div>
            </div>
            <div class="dlg-actions"><button type="button" class="btn btn-secondary" id="auClose">Kapat</button></div>`;
        $('auClose').onclick = () => $('auDlg').close();
        $('auDlg').showModal();
    }

    window.PAGES = window.PAGES || {};
    window.PAGES.denetim = {
        async onShow() {
            if (!drawn) {
                draw();
                await loadActions();
                load();
                return;
            }
            load();
        },
        onEvent() { /* canlı yenileme gerekmiyor */ }
    };
})();
