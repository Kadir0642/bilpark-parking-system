// Vardiyalar sayfası (ADMIN): GET /api/admin/shifts
(() => {
    let built = false;
    let page = 0;
    let loadSeq = 0;      // geç gelen eski yanıtı yok saymak için
    let usersLoaded = false;

    function ymd(d) { return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`; }

    function build() {
        const today = new Date();
        const weekAgo = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 7);
        $('sec-vardiyalar').innerHTML = `
            <div class="page-head">
                <div><h1>Vardiyalar</h1><div class="page-sub">Personelin mesai kayıtları. Tarih, saha ve personele göre süzebilirsiniz.</div></div>
                <div class="toolbar"><button type="button" class="btn btn-secondary" id="shRefresh"><i class="fa-solid fa-rotate" aria-hidden="true"></i> Yenile</button></div>
            </div>
            <div class="card">
                <div class="card-body">
                    <div class="filters">
                        <label class="field">Başlangıç tarihi<input type="date" class="input" id="shFrom" value="${ymd(weekAgo)}"></label>
                        <label class="field">Bitiş tarihi<input type="date" class="input" id="shTo" value="${ymd(today)}"></label>
                        <label class="field">Saha<select class="select" id="shZone"><option value="">Tüm sahalar</option></select></label>
                        <label class="field">Personel<select class="select" id="shUser"><option value="">Tüm personel</option></select></label>
                    </div>
                </div>
            </div>
            <div class="grid grid-kpi" style="margin-top:16px;">
                <div class="card kpi"><div class="kpi-label">Listelenen vardiya</div><div class="kpi-value" id="shKpiCount">-</div><div class="kpi-note" id="shKpiNote">&nbsp;</div></div>
            </div>
            <div class="card" style="margin-top:16px;">
                <div class="table-wrap">
                    <table class="table">
                        <thead><tr><th>Personel</th><th>Saha</th><th>Cihaz</th><th>Başlangıç</th><th>Bitiş</th><th class="right">Süre</th></tr></thead>
                        <tbody id="shBody"><tr><td colspan="6" class="table-empty">Yükleniyor...</td></tr></tbody>
                    </table>
                </div>
                <div class="pager"><span id="shPagerInfo"></span>
                    <span class="toolbar">
                        <button type="button" class="btn btn-secondary" id="shPrev">Önceki</button>
                        <button type="button" class="btn btn-secondary" id="shNext">Sonraki</button>
                    </span>
                </div>
            </div>`;
        const reset = () => { page = 0; load(); };
        ['shFrom', 'shTo', 'shZone', 'shUser'].forEach(id => $(id).addEventListener('change', reset));
        $('shRefresh').addEventListener('click', () => load());
        $('shPrev').addEventListener('click', () => { if (page > 0) { page--; load(); } });
        $('shNext').addEventListener('click', () => { page++; load(); });
        built = true;
    }

    // Saha ve memur seçeneklerini doldur; seçimi koru
    async function loadFilters() {
        try {
            const [zones, users] = await Promise.all([apiJson('GET', '/api/zones'), apiJson('GET', '/api/admin/users')]);
            const zSel = $('shZone'), uSel = $('shUser');
            const zv = zSel.value, uv = uSel.value;
            zSel.innerHTML = '<option value="">Tüm sahalar</option>' + (zones || []).map(z =>
                `<option value="${z.id}">${escapeHtml(z.zoneName || ((streetNames[z.street] || z.street) + ' ' + z.zoneNumber))}</option>`).join('');
            uSel.innerHTML = '<option value="">Tüm personel</option>' + (users || []).filter(u => u.role === 'OFFICER').map(u =>
                `<option value="${u.id}">${escapeHtml((u.fullName || u.username) + ' (' + u.username + ')')}</option>`).join('');
            zSel.value = zv; uSel.value = uv;
            usersLoaded = true;
        } catch (e) {
            if (e.name !== 'AuthError') console.error('Vardiya filtreleri alınamadı:', e);
        }
    }

    async function load() {
        const seq = ++loadSeq;
        const q = new URLSearchParams({ page, size: PAGE_SIZE });
        if ($('shFrom').value) q.set('from', $('shFrom').value);
        if ($('shTo').value) q.set('to', $('shTo').value);
        if ($('shZone').value) q.set('zoneId', $('shZone').value);
        if ($('shUser').value) q.set('userId', $('shUser').value);
        try {
            const d = await apiJson('GET', `/api/admin/shifts?${q}`);
            if (seq !== loadSeq) return;
            const list = (d && d.content) || [];
            $('shBody').innerHTML = list.map(s => `<tr>
                <td><b>${escapeHtml(s.fullName || s.username || '-')}</b>${s.fullName ? `<div class="muted" style="font-size:13px;">${escapeHtml(s.username || '')}</div>` : ''}</td>
                <td>${escapeHtml(s.zoneName || streetNames[s.street] || s.street || '-')}</td>
                <td class="col-muted num" title="${escapeHtml(s.deviceId || '')}">${escapeHtml(shortDevice(s.deviceId))}</td>
                <td class="num">${s.startTime ? escapeHtml(fmtFull(s.startTime)) : '-'}</td>
                <td class="num">${s.open ? '<span class="badge badge-success">Açık</span>'
                    : (s.endTime ? escapeHtml(fmtFull(s.endTime)) : '-') + (s.autoClosed ? ' <span class="badge badge-neutral">Otomatik kapandı</span>' : '')}</td>
                <td class="right num">${s.durationMinutes != null ? escapeHtml(fmtMinutes(s.durationMinutes)) : '-'}</td>
            </tr>`).join('') || '<tr><td colspan="6" class="table-empty">Bu aralıkta vardiya yok.</td></tr>';
            const total = d ? d.totalElements : 0, pages = Math.max(1, d ? d.totalPages : 1);
            $('shPagerInfo').textContent = total ? `${total} kayıt · Sayfa ${page + 1} / ${pages}` : '0 kayıt';
            $('shPrev').disabled = page <= 0;
            $('shNext').disabled = page >= pages - 1;
            // Sunucu toplam süre vermediği için yalnızca vardiya sayısı gösterilir
            $('shKpiCount').textContent = total;
            const open = list.filter(s => s.open).length;
            $('shKpiNote').textContent = open ? `Bu sayfada ${open} açık vardiya` : 'Seçili tarih aralığı';
        } catch (e) {
            if (seq !== loadSeq || e.name === 'AuthError') return;
            $('shBody').innerHTML = `<tr><td colspan="6" class="table-empty">${escapeHtml(e.message)}</td></tr>`;
        }
    }

    function visible() { return currentSection === 'vardiyalar'; }

    PAGES.vardiyalar = {
        onShow() {
            if (!built) build();
            loadFilters();
            load();
        },
        onEvent(type) {
            if (!built || !visible()) return;
            if (type === 'USER_DEACTIVATED' || type === 'USER_UPDATED') {
                load();
                if (usersLoaded) loadFilters();
            }
        }
    };
})();
