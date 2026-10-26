// Raporlar sayfası: personel / kaçak / dönem / kayıt dökümü indirmeleri + performans ve kira ayarları.
// Okunur modda indirmeler çalışır; yalnızca ayar kaydetme düğmeleri data-write taşır.
(() => {
    const $ = id => document.getElementById(id);
    let drawn = false;
    let tab = 'reports';
    let periodShown = false;   // dönem özeti ekranda mı (SSE ile tazeleme için)
    let periodRange = null;    // {from, to}

    const WEIGHTS = [
        ['hours', 'Çalışılan saat'],
        ['punctuality', 'Zamanında başlama'],
        ['throughput', 'Saat başına işlem'],
        ['collection', 'Saat başına tahsilat'],
        ['runaway', 'Kaçak oranı'],
        ['cancellation', 'İptal oranı']
    ];

    // ---- Tarih yardımcıları ----
    function ymd(d) { return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`; }
    function monthStart() { const d = new Date(); return ymd(new Date(d.getFullYear(), d.getMonth(), 1)); }
    function thisMonth() { return monthStart().slice(0, 7); }
    function num1(v) { return (Number(v) || 0).toLocaleString('tr-TR', { minimumFractionDigits: 1, maximumFractionDigits: 1 }); }
    function pct(v) { return `%${num1(v)}`; }

    // Aralık doğrulaması: from <= to ve en çok 366 gün. Hata alanın altına yazılır.
    function checkRange(p) {
        const from = $(p + 'From').value, to = $(p + 'To').value;
        let msg = '';
        if (!from || !to) msg = 'Başlangıç ve bitiş tarihini seçin.';
        else if (from > to) msg = 'Başlangıç tarihi bitişten sonra olamaz.';
        else if ((new Date(to) - new Date(from)) / 86400000 > 366) msg = 'Aralık en çok 366 gün olabilir.';
        setFieldError(p + 'To', p + 'Err', msg);
        return msg ? null : { from, to };
    }

    // İndirme: düğme pasif + "Hazırlanıyor…", bitince geri al.
    async function runDownload(btn, path, fallback) {
        const old = btn.innerHTML;
        btn.disabled = true;
        btn.textContent = 'Hazırlanıyor…';
        try { await downloadFile(path, fallback); }
        finally { btn.innerHTML = old; btn.disabled = false; }
    }

    // ---- İskelet ----
    function rangeFields(p, extra) {
        return `<div class="filters">
            <label class="field">Başlangıç<input class="input" type="date" id="${p}From" value="${monthStart()}"></label>
            <label class="field">Bitiş<input class="input" type="date" id="${p}To" value="${ymd(new Date())}"></label>
            ${extra || ''}
        </div>
        <span class="field-error" id="${p}Err" hidden></span>`;
    }
    function csvPick(id, opts) {
        return `<label class="field" style="min-width:150px;">CSV sayfası
            <select class="select" id="${id}">${opts.map(o => `<option value="${o[0]}">${o[1]}</option>`).join('')}</select></label>`;
    }
    const XLSX_BTN = '<i class="fa-solid fa-file-excel" aria-hidden="true"></i> Excel indir';
    const CSV_BTN = '<i class="fa-solid fa-file-csv" aria-hidden="true"></i> CSV indir';

    function draw() {
        const sec = $('sec-raporlar');
        sec.innerHTML = `
        <div class="page-head"><div><h1>Raporlar</h1>
            <div class="page-sub">Excel ve CSV indirmeleri, belediye ve muhasebe için.</div></div></div>
        <div class="tabs" role="tablist" style="margin-top:12px;">
            <button class="tab" role="tab" id="rpTabReports" aria-selected="true">Raporlar</button>
            <button class="tab" role="tab" id="rpTabSettings" aria-selected="false">Ayarlar</button>
        </div>

        <div id="rpPaneReports">
        <div class="card" style="margin-top:16px;">
            <div class="card-head"><h2>Aylık personel raporu</h2></div>
            <div class="card-body">
                <div class="alert alert-info">Puan yalnızca yöneticiye görünür; saha uygulamasında gösterilmez.
                    Puan, kişiyi kendi sahasının son 3 ay ortalamasıyla kıyaslar.</div>
                <div class="filters" style="margin-top:12px;">
                    <label class="field">Ay<input class="input" type="month" id="rpMonth" value="${thisMonth()}"></label>
                    ${csvPick('rpPSheet', [['summary', 'Özet'], ['shifts', 'Mesai'], ['method', 'Puan yöntemi']])}
                </div>
                <div class="toolbar" style="margin-top:8px;">
                    <button class="btn btn-secondary" id="rpPShow">Göster</button>
                    <button class="btn btn-success" id="rpPXlsx">${XLSX_BTN}</button>
                    <button class="btn btn-secondary" id="rpPCsv">${CSV_BTN}</button>
                </div>
            </div>
            <div class="table-wrap"><table class="table">
                <thead><tr><th>Personel</th><th>Saha</th><th class="right">Mesai günü</th><th class="right">Saat</th>
                    <th class="right">Zamanında başlama</th><th class="right">İşlem</th><th class="right">Saat başına işlem</th>
                    <th class="right">Tahsilat</th><th class="right">Kaçak</th><th class="right">İptal</th><th class="right">Puan</th><th>Değerlendirme</th></tr></thead>
                <tbody id="rpPBody"><tr><td colspan="12" class="table-empty">Yükleniyor…</td></tr></tbody>
            </table></div>
        </div>

        <div class="card" style="margin-top:16px;">
            <div class="card-head"><h2>Belediye için kaçak araç raporu</h2></div>
            <div class="card-body">
                ${rangeFields('rpR', `
                    <label class="field">Cadde<select class="select" id="rpRStreet"><option value="">Tümü</option>
                        ${Object.keys(streetNames).map(k => `<option value="${escapeHtml(k)}">${escapeHtml(streetNames[k])}</option>`).join('')}</select></label>
                    <label class="check" style="align-self:end;"><input type="checkbox" id="rpRUnpaid"> Yalnız ödenmemişler</label>`)}
                <p class="muted" style="margin:8px 0;">Sütunlar: Plaka, Cadde, Saha, Giriş, Kaçak işaretleme, Tutar, Memur, Borç durumu.
                    İptal edilen kaçaklar rapora girmez.</p>
                <div class="toolbar">
                    <button class="btn btn-success" id="rpRXlsx">${XLSX_BTN}</button>
                    <button class="btn btn-secondary" id="rpRCsv">${CSV_BTN}</button>
                </div>
            </div>
        </div>

        <div class="card" style="margin-top:16px;">
            <div class="card-head"><h2>Dönem raporu: ciro, kira, net</h2></div>
            <div class="card-body">
                ${rangeFields('rpD', csvPick('rpDSheet', [['summary', 'Özet'], ['zones', 'Saha bazında'], ['daily', 'Günlük']]))}
                <div class="toolbar" style="margin-top:8px;">
                    <button class="btn btn-primary" id="rpDShow">Göster</button>
                    <button class="btn btn-success" id="rpDXlsx">${XLSX_BTN}</button>
                    <button class="btn btn-secondary" id="rpDCsv">${CSV_BTN}</button>
                </div>
                <div id="rpDOut" style="margin-top:16px;"></div>
            </div>
        </div>

        <div class="card" style="margin-top:16px;">
            <div class="card-head"><h2>Kayıt dökümü</h2></div>
            <div class="card-body">
                ${rangeFields('rpK')}
                <p class="muted" style="margin:8px 0;">Giriş zamanı bu aralıkta olan tüm kayıtlar, iptaller dahil.
                    En fazla 100 000 satır indirilir; daha büyük dönemleri bölün.</p>
                <div class="toolbar">
                    <button class="btn btn-success" id="rpKXlsx">${XLSX_BTN}</button>
                    <button class="btn btn-secondary" id="rpKCsv">${CSV_BTN}</button>
                </div>
            </div>
        </div>
        </div>

        <div id="rpPaneSettings" hidden>
        <div class="card" style="margin-top:16px;">
            <div class="card-head"><h2>Ayarlar: performans ağırlıkları</h2></div>
            <div class="card-body">
                <p class="muted" style="margin:0 0 12px;">Altı ölçütün ağırlığı toplam 100 olmalı. Kaçak ve iptal oranında düşük olan iyidir.</p>
                <div class="form-grid">
                    ${WEIGHTS.map(w => `<label class="field">${w[1]} (0-100)
                        <input class="input" type="number" min="0" max="100" step="1" inputmode="numeric" id="rpW_${w[0]}"></label>`).join('')}
                </div>
                <div id="rpWTotal" style="margin:12px 0;font-weight:700;"></div>
                <div class="form-grid">
                    <label class="field">Mesai başlangıcı<input class="input" type="time" id="rpStart"></label>
                    <label class="field">Tolerans (dakika)<input class="input" type="number" min="0" max="120" step="1" id="rpGrace"></label>
                </div>
                <p class="muted" id="rpLimit" style="margin:8px 0;"></p>
                <div id="rpWMsg" class="alert alert-danger" hidden></div>
                <div class="toolbar"><button class="btn btn-primary" id="rpWSave" data-write>Kaydet</button></div>
            </div>
        </div>

        <div class="card" style="margin-top:16px;">
            <div class="card-head"><h2>Ayarlar: yıllık kira bedeli</h2></div>
            <div class="card-body">
                <p class="muted" style="margin:0 0 12px;">3 caddenin tek ihalesi olduğu için toplam tutar girilir;
                    geçmiş raporlar o yılın kirasıyla hesaplanır.</p>
                <div class="filters">
                    <label class="field">Yıl<input class="input" type="number" min="2000" max="2100" id="rpYear" value="${new Date().getFullYear()}"></label>
                    <label class="field">Yıllık kira (TL)<input class="input" type="number" min="0" step="0.01" inputmode="decimal" id="rpRent"></label>
                </div>
                <span class="field-error" id="rpRentErr" hidden></span>
                <div class="toolbar" style="margin-top:8px;"><button class="btn btn-primary" id="rpRentSave" data-write>Kaydet</button></div>
            </div>
            <div class="table-wrap"><table class="table">
                <thead><tr><th>Yıl</th><th class="right">Yıllık kira</th><th class="right">Aylık pay</th></tr></thead>
                <tbody id="rpRentBody"><tr><td colspan="3" class="table-empty">Yükleniyor…</td></tr></tbody>
            </table></div>
        </div>
        </div>`;
        bind();
        drawn = true;
    }

    function setTab(t) {
        tab = t;
        $('rpTabReports').setAttribute('aria-selected', String(t === 'reports'));
        $('rpTabSettings').setAttribute('aria-selected', String(t === 'settings'));
        $('rpPaneReports').hidden = t !== 'reports';
        $('rpPaneSettings').hidden = t !== 'settings';
    }

    function bind() {
        $('rpTabReports').onclick = () => setTab('reports');
        $('rpTabSettings').onclick = () => setTab('settings');

        // Personel
        $('rpMonth').onchange = loadPersonnel;
        $('rpPShow').onclick = loadPersonnel;
        const pPath = fmt => {
            const m = $('rpMonth').value || thisMonth();
            const sheet = fmt === 'csv' ? `&sheet=${$('rpPSheet').value}` : '';
            return [`/api/admin/reports/personnel?month=${m}&format=${fmt}${sheet}`, `personel-raporu-${m}.${fmt}`];
        };
        $('rpPXlsx').onclick = e => { const [p, n] = pPath('xlsx'); runDownload(e.currentTarget, p, n); };
        $('rpPCsv').onclick = e => { const [p, n] = pPath('csv'); runDownload(e.currentTarget, p, n); };

        // Kaçak
        const rPath = fmt => {
            const r = checkRange('rpR'); if (!r) return null;
            const q = new URLSearchParams({ from: r.from, to: r.to, format: fmt });
            if ($('rpRStreet').value) q.set('street', $('rpRStreet').value);
            if ($('rpRUnpaid').checked) q.set('unpaidOnly', 'true');
            return [`/api/admin/reports/runaways?${q}`, `kacak-raporu-${r.from}_${r.to}.${fmt}`];
        };
        $('rpRXlsx').onclick = e => { const x = rPath('xlsx'); if (x) runDownload(e.currentTarget, x[0], x[1]); };
        $('rpRCsv').onclick = e => { const x = rPath('csv'); if (x) runDownload(e.currentTarget, x[0], x[1]); };

        // Dönem
        $('rpDShow').onclick = () => loadPeriod(false);
        const dPath = fmt => {
            const r = checkRange('rpD'); if (!r) return null;
            const sheet = fmt === 'csv' ? `&sheet=${$('rpDSheet').value}` : '';
            return [`/api/admin/reports/period?from=${r.from}&to=${r.to}&format=${fmt}${sheet}`, `donem-raporu-${r.from}_${r.to}.${fmt}`];
        };
        $('rpDXlsx').onclick = e => { const x = dPath('xlsx'); if (x) runDownload(e.currentTarget, x[0], x[1]); };
        $('rpDCsv').onclick = e => { const x = dPath('csv'); if (x) runDownload(e.currentTarget, x[0], x[1]); };

        // Kayıt dökümü
        const kPath = fmt => {
            const r = checkRange('rpK'); if (!r) return null;
            return [`/api/admin/reports/records?from=${r.from}&to=${r.to}&format=${fmt}`, `kayitlar-${r.from}_${r.to}.${fmt}`];
        };
        $('rpKXlsx').onclick = e => { const x = kPath('xlsx'); if (x) runDownload(e.currentTarget, x[0], x[1]); };
        $('rpKCsv').onclick = e => { const x = kPath('csv'); if (x) runDownload(e.currentTarget, x[0], x[1]); };

        // Ayarlar
        WEIGHTS.forEach(w => { $('rpW_' + w[0]).oninput = updateTotal; });
        $('rpStart').oninput = updateTotal;
        $('rpGrace').oninput = updateTotal;
        $('rpWSave').onclick = saveWeights;
        $('rpRentSave').onclick = saveRent;
    }

    // ---- Personel özeti ----
    function ratingBadge(score) {
        const s = Number(score) || 0;
        if (s >= 85) return '<span class="badge badge-success">Çok iyi</span>';
        if (s >= 65) return '<span class="badge badge-primary">İyi</span>';
        return '<span class="badge badge-warning">Geliştirilmeli</span>';
    }
    async function loadPersonnel() {
        const body = $('rpPBody');
        const m = $('rpMonth').value || thisMonth();
        try {
            const d = await apiJson('GET', `/api/admin/reports/personnel/summary?month=${encodeURIComponent(m)}`);
            const list = Array.isArray(d) ? d : ((d && (d.content || d.rows)) || []);
            body.innerHTML = list.map(r => `<tr>
                <td><b>${escapeHtml(r.fullName || r.username || '-')}</b><div class="muted" style="font-size:13px;">${escapeHtml(r.username || '')}</div></td>
                <td>${escapeHtml(r.zoneName || '-')}</td>
                <td class="right num">${r.workDays ?? 0}</td>
                <td class="right num">${num1(r.hours)}</td>
                <td class="right num">${pct(r.onTimeRate)}</td>
                <td class="right num">${r.transactions ?? 0}</td>
                <td class="right num">${num1(r.throughput)}</td>
                <td class="right num">${money(r.collectionTotal)}</td>
                <td class="right num">${r.runawayCount ?? 0} <span class="muted">(${pct(r.runawayRate)})</span></td>
                <td class="right num">${r.cancelCount ?? 0}</td>
                <td class="right num"><b style="font-size:20px;">${Math.round(Number(r.score) || 0)}</b></td>
                <td>${ratingBadge(r.score)}</td>
            </tr>`).join('') || '<tr><td colspan="12" class="table-empty">Bu ay için personel verisi yok.</td></tr>';
        } catch (e) {
            if (e.name !== 'AuthError') body.innerHTML = `<tr><td colspan="12" class="table-empty">${escapeHtml(e.message)}</td></tr>`;
        }
    }

    // ---- Dönem özeti ----
    function kpi(label, value, note, cls) {
        return `<div class="card kpi"><div class="kpi-label">${label}</div>
            <div class="kpi-value ${cls || ''}">${value}</div>${note ? `<div class="kpi-note">${note}</div>` : ''}</div>`;
    }
    async function loadPeriod(silent) {
        const r = silent && periodRange ? periodRange : checkRange('rpD');
        if (!r) return;
        const out = $('rpDOut');
        try {
            const d = await apiJson('GET', `/api/admin/reports/period/summary?from=${r.from}&to=${r.to}`);
            periodShown = true; periodRange = r;
            const net = Number(d.netRemaining) || 0;
            const missing = d.rentMissingYears || [];
            const months = d.rentMonths || [];
            out.innerHTML = `
            ${missing.length ? `<div class="alert alert-warning" style="margin-bottom:12px;">${escapeHtml(missing.join(', '))} için kira bedeli girilmemiş; o yılın payı 0 sayıldı. Ayarlar sekmesindeki Kira bölümünden girin.</div>` : ''}
            <div class="grid grid-kpi">
                ${kpi('Toplam ciro', money(d.totalRevenue))}
                ${kpi('Kira payı', money(d.rentShare))}
                ${kpi('Net kalan', money(net), '', net < 0 ? 'text-danger' : '')}
                ${kpi('Nakit', money(d.cashRevenue))}
                ${kpi('POS', money(d.posRevenue))}
                ${kpi('Abonelik satışı', money(d.subscriptionSales))}
                ${kpi('Diğer', money(d.otherRevenue))}
            </div>
            <dl class="tar-grid" style="margin-top:12px;">
                <div class="tar-item"><dt>Abonelikle çıkış</dt><dd>${d.subscriptionExitCount ?? 0} araç</dd></div>
                <div class="tar-item"><dt>İptal edilen kayıt</dt><dd>${d.cancelledCount ?? 0} kayıt · ${money(d.cancelledFees)}</dd></div>
                <div class="tar-item"><dt>Ödenmemiş kaçak</dt><dd>${d.unpaidRunawayCount ?? 0} araç · ${money(d.unpaidRunawayDebt)}</dd></div>
            </dl>
            ${months.length ? `<div class="sub-h" style="margin-top:12px;">Kira payı ayrıntısı</div>
            <div class="table-wrap"><table class="table"><thead><tr><th>Ay</th><th class="right">Gün</th><th class="right">Aylık pay</th><th class="right">Dönem payı</th></tr></thead>
            <tbody>${months.map(m => `<tr><td>${escapeHtml(m.month)}</td><td class="right num">${m.overlapDays ?? 0}</td>
                <td class="right num">${money(m.monthlyShare)}</td><td class="right num">${money(m.share)}</td></tr>`).join('')}</tbody></table></div>` : ''}`;
            // Negatif net için danger rengi: tema sınıfı yoksa satır içi yedek
            if (net < 0) { const v = out.querySelectorAll('.kpi-value')[2]; if (v) v.style.color = 'var(--danger)'; }
        } catch (e) {
            if (e.name === 'AuthError') return;
            if (!silent) out.innerHTML = `<div class="alert alert-danger">${escapeHtml(e.message)}</div>`;
        }
    }

    // ---- Ayarlar: ağırlıklar ----
    function weightSum() { return WEIGHTS.reduce((s, w) => s + (Number($('rpW_' + w[0]).value) || 0), 0); }
    function updateTotal() {
        const sum = weightSum();
        const ok = sum === 100;
        const t = $('rpWTotal');
        t.textContent = `Toplam: ${sum} / 100`;
        t.style.color = ok ? '' : 'var(--danger)';
        $('rpWSave').disabled = !ok || isReadOnly();
        const st = $('rpStart').value || '08:00', g = Number($('rpGrace').value) || 0;
        const [h, mi] = st.split(':').map(Number);
        const tot = (h || 0) * 60 + (mi || 0) + g;
        $('rpLimit').textContent = `Zamanında başlama sınırı: ${pad2(Math.floor(tot / 60) % 24)}:${pad2(tot % 60)}`;
    }
    async function loadWeights() {
        try {
            const d = await apiJson('GET', '/api/admin/settings/performance-weights');
            WEIGHTS.forEach(w => { $('rpW_' + w[0]).value = d[w[0]] ?? 0; });
            $('rpStart').value = (d.startTime || '08:00').slice(0, 5);
            $('rpGrace').value = d.graceMinutes ?? 5;
            updateTotal();
        } catch (e) {
            if (e.name !== 'AuthError') toast('Ağırlıklar yüklenemedi: ' + e.message, true);
        }
    }
    async function saveWeights() {
        const msg = $('rpWMsg');
        msg.hidden = true;
        const body = { startTime: $('rpStart').value, graceMinutes: Number($('rpGrace').value) || 0 };
        WEIGHTS.forEach(w => { body[w[0]] = Number($('rpW_' + w[0]).value) || 0; });
        const btn = $('rpWSave');
        btn.disabled = true;
        try {
            await apiJson('PUT', '/api/admin/settings/performance-weights', body);
            toast('Performans ayarları kaydedildi.');
        } catch (e) {
            if (e.name === 'AuthError') return;
            msg.textContent = e.message; msg.hidden = false;
        } finally {
            updateTotal();
        }
    }

    // ---- Ayarlar: kira ----
    async function loadRent() {
        try {
            const d = await apiJson('GET', '/api/admin/settings/rent');
            const years = (d && d.years) || [];
            $('rpRentBody').innerHTML = years.map(y => `<tr><td><b>${y.year}</b></td>
                <td class="right num">${money(y.annualRent)}</td><td class="right num">${money(y.monthlyShare)}</td></tr>`).join('')
                || '<tr><td colspan="3" class="table-empty">Henüz kira bedeli girilmemiş.</td></tr>';
        } catch (e) {
            if (e.name !== 'AuthError') $('rpRentBody').innerHTML = `<tr><td colspan="3" class="table-empty">${escapeHtml(e.message)}</td></tr>`;
        }
    }
    async function saveRent() {
        const year = Number($('rpYear').value), rent = Number($('rpRent').value);
        let msg = '';
        if (!year || year < 2000 || year > 2100) msg = 'Geçerli bir yıl girin.';
        else if ($('rpRent').value === '' || !(rent >= 0)) msg = 'Yıllık kira tutarını girin.';
        setFieldError('rpRent', 'rpRentErr', msg);
        if (msg) return;
        const btn = $('rpRentSave');
        btn.disabled = true;
        try {
            await apiJson('PUT', '/api/admin/settings/rent', { year, annualRent: rent });
            toast(`${year} yılı kira bedeli kaydedildi.`);
            await loadRent();
            if (periodShown) loadPeriod(true);
        } catch (e) {
            if (e.name !== 'AuthError') setFieldError('rpRent', 'rpRentErr', e.message);
        } finally {
            btn.disabled = isReadOnly();
        }
    }

    window.PAGES = window.PAGES || {};
    window.PAGES.raporlar = {
        onShow() {
            if (!drawn) {
                draw();
                loadPersonnel();
                loadWeights();
                loadRent();
                return;
            }
            // Sonraki açılışlar: yalnızca görünür veriler
            loadPersonnel();
            loadRent();
            if (periodShown) loadPeriod(true);
        },
        onEvent(type) {
            if (type === 'RECORD_CANCELLED' && drawn && periodShown) loadPeriod(true);
        }
    };
})();
