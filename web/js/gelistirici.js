// Geliştirici sayfası (yalnız DEVELOPER): uyarılar, sahalar/cihazlar, senkron, hata eğilimi, sağlık, hatalar, denetim kaydı (salt okunur), lisans, yönetici şifresi sıfırlama
// Bu sayfadaki düğmeler data-write almaz: geliştirici uçları okunur modda da açıktır (lisansı yenilemenin yolu budur).
(() => {
    let built = false;
    let errors = [];
    let lastFetch = 0;      // sağlık/hata isteklerini peş peşe tekrar atmamak için
    let fetching = false;
    let licDraft = null;
    let auPage = 0, auTimer = null, auSeq = 0, auLabels = {}, auActionsLoaded = false, actTimer = null;    // form alanları yeniden çizimde silinmesin

    function genPassword() {
        const letters = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ';
        const digits = '23456789';
        const all = letters + digits;
        const rnd = n => { const a = new Uint32Array(1); crypto.getRandomValues(a); return a[0] % n; };
        const chars = [letters[rnd(letters.length)], digits[rnd(digits.length)]];
        while (chars.length < 12) chars.push(all[rnd(all.length)]);
        for (let i = chars.length - 1; i > 0; i--) { const j = rnd(i + 1); [chars[i], chars[j]] = [chars[j], chars[i]]; }
        return chars.join('');
    }
    function pwProblem(pw, username) {
        if (pw.length < 8) return 'Şifre en az 8 karakter olmalı.';
        if (!/\p{L}/u.test(pw) || !/\d/.test(pw)) return 'Şifre en az bir harf ve bir rakam içermeli.';
        if (username && pw.toLowerCase().includes(username.toLowerCase())) return 'Şifre kullanıcı adını içeremez.';
        return '';
    }
    function ymd(d) { return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`; }
    function parseYmd(s) { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); }
    function addDays(s, n) { const d = parseYmd(s); d.setDate(d.getDate() + n); return ymd(d); }
    function fmtUptime(sec) {
        sec = Math.max(0, Math.round(sec || 0));
        const d = Math.floor(sec / 86400), h = Math.floor(sec % 86400 / 3600), m = Math.floor(sec % 3600 / 60);
        return d > 0 ? `${d} gün ${h} sa` : (h > 0 ? `${h} sa ${m} dk` : `${m} dk`);
    }
    function shortClass(c) { return c ? String(c).split('.').pop() : '-'; }
    function kv(label, value) { return `<div class="tar-item"><dt>${escapeHtml(label)}</dt><dd>${escapeHtml(value == null || value === '' ? '-' : value)}</dd></div>`; }
    function objToText(v) {
        if (v == null) return '-';
        if (typeof v !== 'object') return String(v);
        return Object.entries(v).map(([k, x]) => `${k}: ${typeof x === 'object' ? JSON.stringify(x) : x}`).join(', ');
    }

    function build() {
        $('sec-gelistirici').innerHTML = `
            <style>
                #sec-gelistirici pre.stack { margin: 0; padding: var(--s-3); overflow-x: auto; max-width: 100%; font-size: 12px; background: var(--surface-2, var(--surface)); border: 1px solid var(--border); border-radius: var(--r-md, 8px); white-space: pre; }
                #sec-gelistirici tr.err-row { cursor: pointer; }
                #sec-gelistirici .inline-pw { display: flex; gap: 8px; }
                #sec-gelistirici .g-alert { display: flex; gap: 8px; align-items: baseline; margin-bottom: 8px; }
                #sec-gelistirici .g-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: var(--text-2); margin-right: 6px; }
                #sec-gelistirici .g-dot.on { background: var(--success); }
                #sec-gelistirici .g-trend { display: flex; align-items: flex-end; gap: 8px; height: 120px; }
                #sec-gelistirici .g-bar { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: flex-end; height: 100%; font-size: 12px; color: var(--text-2); }
                #sec-gelistirici .g-bar > i { display: block; width: 100%; max-width: 48px; min-height: 2px; background: var(--primary); border-radius: 4px 4px 0 0; }
                #sec-gelistirici .g-bar.has > i { background: var(--danger); }
            </style>
            <div class="page-head">
                <div><h1>Geliştirici</h1>
                    <div class="page-sub">Bu sayfada yapılan her işlem denetim kaydına düşer ve yönetici tarafından görülür. Gizli düğme yoktur.</div></div>
            </div>
            <div class="card" style="margin-top:16px;">
                <div class="card-head"><h2>Uyarılar</h2>
                    <div class="toolbar">
                        <label class="field" for="gWin" style="flex-direction:row; align-items:center; gap:8px;"><span class="muted">Pencere</span>
                            <select class="select" id="gWin"><option value="1">1 saat</option><option value="24" selected>24 saat</option><option value="168">7 gün</option></select></label>
                        <button type="button" class="btn btn-secondary" id="gActRefresh"><i class="fa-solid fa-rotate" aria-hidden="true"></i> Yenile</button>
                    </div></div>
                <div class="card-body">
                    <div class="muted" style="font-size:13px; margin-bottom:8px;" id="gActMeta">Sayfa açıkken dakikada bir kendiliğinden yenilenir.</div>
                    <div id="gAlerts">Yükleniyor...</div>
                </div>
            </div>
            <div class="card" style="margin-top:16px;">
                <div class="card-head"><h2>Sahalar ve cihazlar</h2></div>
                <div class="table-wrap"><table class="table">
                    <thead><tr><th>Saha</th><th class="right">Araç</th><th class="right">Giriş</th><th class="right">Çıkış</th><th class="right">Kaçak</th><th class="right">İptal</th><th>Son hareket</th><th>Mesai</th><th>Memur / cihaz</th></tr></thead>
                    <tbody id="gZones"><tr><td colspan="9" class="table-empty">Yükleniyor...</td></tr></tbody>
                </table></div>
            </div>
            <div class="card" style="margin-top:16px;">
                <div class="card-head"><h2>Senkron ve girişler</h2></div>
                <div class="card-body"><dl class="tar-grid" id="gSync"></dl></div>
            </div>
            <div class="card" style="margin-top:16px;">
                <div class="card-head"><h2>Hata eğilimi (7 gün)</h2></div>
                <div class="card-body">
                    <div id="gTrend" class="g-trend"></div>
                    <h3 class="sub-h" style="margin-top:16px;">En sık hatalar</h3>
                    <div class="table-wrap"><table class="table">
                        <thead><tr><th>Kod</th><th>İstek yolu</th><th class="right">Adet</th></tr></thead>
                        <tbody id="gTop"><tr><td colspan="3" class="table-empty">-</td></tr></tbody>
                    </table></div>
                </div>
            </div>
            <div class="card" style="margin-top:16px;">
                <div class="card-head"><h2>Sistem sağlığı</h2>
                    <button type="button" class="btn btn-secondary" id="gHealthRefresh"><i class="fa-solid fa-rotate" aria-hidden="true"></i> Yenile</button></div>
                <div class="card-body">
                    <div class="muted" style="font-size:13px; margin-bottom:8px;">Her görüntüleme denetim kaydına düşer; bu yüzden kendiliğinden yenilenmez.</div>
                    <div id="gHealth">Yükleniyor...</div>
                </div>
            </div>
            <div class="card" style="margin-top:16px;">
                <div class="card-head"><h2>Son hatalar</h2>
                    <button type="button" class="btn btn-secondary" id="gErrRefresh"><i class="fa-solid fa-rotate" aria-hidden="true"></i> Yenile</button></div>
                <div class="table-wrap">
                    <table class="table">
                        <thead><tr><th>Zaman</th><th>İstek</th><th>Kod</th><th>İstisna</th><th>Mesaj</th><th>Kullanıcı</th></tr></thead>
                        <tbody id="gErrBody"><tr><td colspan="6" class="table-empty">Yükleniyor...</td></tr></tbody>
                    </table>
                </div>
            </div>
            <div class="card" style="margin-top:16px;">
                <div class="card-head"><h2>Denetim kaydı</h2><span class="muted" style="font-size:13px;">Salt okunur · plakalar maskeli · görüntülemeniz kayda geçer</span></div>
                <div class="filters">
                    <label class="field">Başlangıç<input class="input" type="date" id="gAuFrom" value="${ymd(new Date(Date.now() - 7 * 86400000))}"></label>
                    <label class="field">Bitiş<input class="input" type="date" id="gAuTo" value="${ymd(new Date())}"></label>
                    <label class="field">İşlem<select class="select" id="gAuAction"><option value="">Tümü</option></select></label>
                    <label class="field">Kişi<input class="input" type="text" id="gAuActor" placeholder="Kullanıcı adı" autocomplete="off"></label>
                    <label class="field">Rol<select class="select" id="gAuRole"><option value="">Tümü</option><option value="DEVELOPER">Geliştirici</option><option value="ADMIN">Yönetici</option><option value="OFFICER">Memur</option></select></label>
                    <label class="field">Ara<input class="input" type="search" id="gAuQ" placeholder="Özet / plaka" autocomplete="off"></label>
                </div>
                <div class="alert alert-danger" id="gAuErr" hidden style="margin:0 16px 12px;"></div>
                <div class="table-wrap"><table class="table">
                    <thead><tr><th>Zaman</th><th>Kişi</th><th>İşlem</th><th>Özet</th><th>Cihaz / IP</th></tr></thead>
                    <tbody id="gAuBody"><tr><td colspan="5" class="table-empty">Yükleniyor...</td></tr></tbody>
                </table></div>
                <div class="pager"><span id="gAuInfo"></span>
                    <span class="toolbar"><button type="button" class="btn btn-secondary" id="gAuPrev">Önceki</button>
                    <button type="button" class="btn btn-secondary" id="gAuNext">Sonraki</button></span></div>
            </div>
            <div class="card" style="margin-top:16px;">
                <div class="card-head"><h2>Lisans</h2></div>
                <div class="card-body">
                    <dl class="tar-grid" id="gLicInfo"></dl>
                    <h3 class="sub-h" style="margin-top:16px;">Lisansı güncelle</h3>
                    <form id="gLicForm" novalidate>
                        <div class="form-grid">
                            <label class="field" for="gLicExp">Bitiş tarihi<input type="date" class="input" id="gLicExp" aria-describedby="gLicExpErr"><span class="field-error" id="gLicExpErr" hidden></span></label>
                            <label class="field" for="gLicGrace">Ek süre (gün)<input type="number" class="input" id="gLicGrace" min="0" max="90" step="1" aria-describedby="gLicGraceErr"><span class="field-error" id="gLicGraceErr" hidden></span></label>
                            <label class="field" for="gLicOwner">Lisans sahibi<input type="text" class="input" id="gLicOwner" maxlength="120"></label>
                            <label class="field form-wide" for="gLicNote">Not<textarea class="input textarea" id="gLicNote" maxlength="300"></textarea></label>
                        </div>
                        <div class="toolbar" style="margin-top:12px;">
                            <button type="button" class="btn btn-secondary" id="gLicPlus">+1 yıl</button>
                            <button type="submit" class="btn btn-primary" id="gLicSave">Lisansı kaydet</button>
                        </div>
                    </form>
                </div>
            </div>
            <div class="card" style="margin-top:16px;">
                <div class="card-head"><h2>Yönetici şifresi sıfırla</h2></div>
                <div class="card-body">
                    <form id="gPwForm" novalidate>
                        <div class="form-grid">
                            <label class="field" for="gPwUser">Kullanıcı adı<input type="text" class="input" id="gPwUser" value="admin" autocomplete="off" aria-describedby="gPwUserErr"><span class="field-error" id="gPwUserErr" hidden></span></label>
                            <label class="field" for="gPwNew">Yeni şifre
                                <span class="inline-pw"><input type="text" class="input" id="gPwNew" autocomplete="off" spellcheck="false" style="flex:1; min-width:0;" aria-describedby="gPwNewErr">
                                <button type="button" class="btn btn-secondary" id="gPwGen"><i class="fa-solid fa-dice" aria-hidden="true"></i> Rastgele</button></span>
                                <span class="muted" style="font-weight:500; font-size:13px;">Yalnızca yönetici hesapları. En az 8 karakter, harf ve rakam içermeli.</span>
                                <span class="field-error" id="gPwNewErr" hidden></span></label>
                        </div>
                        <div class="toolbar" style="margin-top:12px;"><button type="submit" class="btn btn-primary" id="gPwSubmit">Şifreyi sıfırla</button></div>
                    </form>
                </div>
            </div>`;
        $('gLicForm').addEventListener('submit', saveLicense);
        $('gLicPlus').addEventListener('click', () => {
            const base = $('gLicExp').value || (licenseStatus && licenseStatus.expiresOn) || ymd(new Date());
            $('gLicExp').value = addDays(base, 365);
        });
        ['gLicExp', 'gLicGrace', 'gLicOwner', 'gLicNote'].forEach(id => $(id).addEventListener('input', () => { licDraft = true; }));
        $('gHealthRefresh').addEventListener('click', loadHealth);
        $('gActRefresh').addEventListener('click', loadActivity);
        $('gWin').addEventListener('change', loadActivity);
        ['gAuFrom', 'gAuTo', 'gAuAction', 'gAuRole'].forEach(id => $(id).addEventListener('change', () => { auPage = 0; loadAudit(); }));
        ['gAuActor', 'gAuQ'].forEach(id => $(id).addEventListener('input', () => { clearTimeout(auTimer); auTimer = setTimeout(() => { auPage = 0; loadAudit(); }, 500); }));
        $('gAuPrev').addEventListener('click', () => { if (auPage > 0) { auPage--; loadAudit(); } });
        $('gAuNext').addEventListener('click', () => { auPage++; loadAudit(); });
        $('gErrRefresh').addEventListener('click', loadErrors);
        $('gErrBody').addEventListener('click', e => {
            const tr = e.target.closest('tr[data-i]');
            if (!tr) return;
            const next = tr.nextElementSibling;
            if (next && next.classList.contains('err-detail')) { next.remove(); return; }
            const er = errors[Number(tr.dataset.i)];
            const d = document.createElement('tr');
            d.className = 'err-detail';
            d.innerHTML = `<td colspan="6"><pre class="stack">${escapeHtml(er.stackExcerpt || 'Ayrıntı yok.')}</pre></td>`;
            tr.after(d);
        });
        $('gPwGen').addEventListener('click', () => { $('gPwNew').value = genPassword(); setFieldError('gPwNew', 'gPwNewErr', ''); });
        $('gPwForm').addEventListener('submit', resetAdminPassword);
        built = true;
    }

    // ---- Lisans ----
    function renderLicenseCard() {
        const s = licenseStatus;
        if (!s) { $('gLicInfo').innerHTML = kv('Durum', 'Alınamadı'); return; }
        const stateLabel = { ACTIVE: 'Etkin', GRACE: 'Ek süre', READ_ONLY: 'Okunur mod' }[s.state] || s.state;
        const left = s.state === 'ACTIVE' ? `${s.daysLeft} gün` : (s.state === 'GRACE' ? `Ek süre: ${s.graceDaysLeft} gün` : '0');
        $('gLicInfo').innerHTML = kv('Lisans sahibi', s.licensee) + kv('Durum', stateLabel) + kv('Bitiş', fmtDay(s.expiresOn))
            + kv('Ek süre', `${s.graceDays} gün (${fmtDay(s.graceEndsOn)})`) + kv('Kalan', left)
            + kv('Son güncelleyen', s.updatedBy) + kv('Son güncelleme', s.updatedAt ? fmtFull(s.updatedAt) : '-') + kv('Not', s.note);
        if (!licDraft) { // kullanıcı yazmaya başlamadıysa formu mevcut değerlerle doldur
            $('gLicExp').value = s.expiresOn || '';
            $('gLicGrace').value = s.graceDays != null ? s.graceDays : 15;
            $('gLicOwner').value = s.licensee || '';
            $('gLicNote').value = s.note || '';
        }
    }
    async function loadLicenseCard() {
        await loadLicense(); // çekirdek durumu ve bandı da günceller
        renderLicenseCard();
    }
    async function saveLicense(e) {
        e.preventDefault();
        const expiresOn = $('gLicExp').value;
        const graceRaw = $('gLicGrace').value;
        const graceDays = Number(graceRaw);
        setFieldError('gLicExp', 'gLicExpErr', '');
        setFieldError('gLicGrace', 'gLicGraceErr', '');
        let bad = false;
        if (!expiresOn) { setFieldError('gLicExp', 'gLicExpErr', 'Bitiş tarihini girin.'); bad = true; }
        if (graceRaw === '' || !Number.isInteger(graceDays) || graceDays < 0 || graceDays > 90) { setFieldError('gLicGrace', 'gLicGraceErr', 'Ek süre 0-90 gün arasında olmalı.'); bad = true; }
        if (bad) return;
        const today = ymd(new Date());
        const state = today <= expiresOn ? 'Etkin' : (today <= addDays(expiresOn, graceDays) ? 'Ek süre' : 'Okunur mod');
        const ok = await confirmDialog({
            title: 'Lisansı kaydet',
            message: `Yeni bitiş: ${fmtDay(expiresOn)}; ek süre: ${graceDays} gün; durum: ${state}. Bu işlem denetim kaydına yazılır.`,
            confirmLabel: 'Kaydet'
        });
        if (!ok) return;
        const body = { expiresOn, graceDays, licensee: $('gLicOwner').value.trim(), note: $('gLicNote').value.trim() };
        const btn = $('gLicSave');
        btn.disabled = true;
        try {
            await apiJson('PUT', '/api/developer/license', body);
            toast('Lisans güncellendi.');
            licDraft = null;
            await loadLicenseCard();
        } catch (err) {
            if (err.name !== 'AuthError') toast('Hata: ' + err.message, true);
        } finally {
            btn.disabled = false;
        }
    }

    // ---- Sağlık ----
    async function loadHealth() {
        const box = $('gHealth');
        try {
            const h = await apiJson('GET', '/api/developer/health');
            const up = h.status === 'UP';
            const mem = h.memory || {}, db = h.database || {}, c = h.counts || {};
            box.innerHTML = `<div style="margin-bottom:12px;"><span class="badge ${up ? 'badge-success' : 'badge-warning'}">${up ? 'Çalışıyor (UP)' : 'Sorunlu (' + escapeHtml(h.status || '?') + ')'}</span></div>
                <dl class="tar-grid">
                    ${kv('Çalışma süresi', fmtUptime(h.uptimeSeconds))}
                    ${kv('Java', h.javaVersion)}
                    ${kv('Bellek', `${mem.usedMb ?? '-'} / ${mem.maxMb ?? '-'} MB`)}
                    ${kv('Veritabanı', db.ok ? `Tamam · ${db.latencyMs} ms` : 'Ulaşılamıyor')}
                    ${kv('Flyway sürümü', h.flywayVersion)}
                    ${kv('Aktif araç', c.activeSpots)}
                    ${kv('Park kaydı', c.parkingRecords)}
                    ${kv('Kullanıcı', c.users)}
                    ${kv('Açık vardiya', c.openShifts)}
                    ${kv('Son 24 saatte hata', h.errorsLast24h)}
                    ${kv('Son saklama işi', objToText(h.lastRetentionRun))}
                    ${kv('Ölçüm zamanı', h.time ? fmtFull(h.time) : '-')}
                </dl>`;
        } catch (e) {
            if (e.name !== 'AuthError') box.innerHTML = `<div class="alert alert-danger">${escapeHtml(e.message)}</div>`;
        }
    }

    // ---- Hatalar ----
    async function loadErrors() {
        try {
            errors = (await apiJson('GET', '/api/developer/errors?limit=50')) || [];
            $('gErrBody').innerHTML = errors.map((r, i) => `<tr class="err-row" data-i="${i}" title="Ayrıntı için tıklayın">
                <td class="col-muted num">${r.createdAt ? escapeHtml(fmtFull(r.createdAt)) : '-'}</td>
                <td><b>${escapeHtml(r.method || '')}</b> ${escapeHtml(r.path || '')}</td>
                <td>${escapeHtml(r.errorCode || '-')}</td>
                <td title="${escapeHtml(r.exceptionClass || '')}">${escapeHtml(shortClass(r.exceptionClass))}</td>
                <td style="min-width:200px;">${escapeHtml(r.message || '-')}</td>
                <td>${escapeHtml(r.username || '-')}</td>
            </tr>`).join('') || '<tr><td colspan="6" class="table-empty">Kayıtlı hata yok.</td></tr>';
        } catch (e) {
            if (e.name !== 'AuthError') $('gErrBody').innerHTML = `<tr><td colspan="6" class="table-empty">${escapeHtml(e.message)}</td></tr>`;
        }
    }

    // ---- Etkinlik (uyarılar, sahalar, senkron, eğilim) ----
    function relTime(iso) {
        if (!iso) return 'hiç';
        const t = new Date(iso).getTime();
        if (isNaN(t)) return '-';
        const m = Math.max(0, Math.round((Date.now() - t) / 60000));
        if (m < 1) return 'şimdi';
        if (m < 60) return `${m} dk önce`;
        const h = Math.floor(m / 60);
        if (h < 24) return `${h} sa önce`;
        return `${Math.floor(h / 24)} gün önce`;
    }
    const LEVEL = {
        CRIT: { cls: 'alert-danger', badge: 'badge-danger', label: 'Kritik' },
        WARN: { cls: 'alert-warning', badge: 'badge-warning', label: 'Uyarı' },
        INFO: { cls: 'alert-info', badge: 'badge-primary', label: 'Bilgi' }
    };
    function renderActivity(a) {
        const alerts = a.alerts || [];
        $('gAlerts').innerHTML = alerts.length ? alerts.map(x => {
            const l = LEVEL[x.level] || LEVEL.INFO;
            return `<div class="alert ${l.cls} g-alert"><span class="badge ${l.badge}">${l.label}</span><span>${escapeHtml(x.message || x.code || '')}</span></div>`;
        }).join('') : '<div class="alert alert-success"><i class="fa-solid fa-circle-check" aria-hidden="true"></i> Her şey yolunda.</div>';
        const wh = a.workingHours;
        $('gActMeta').textContent = `Ölçüm: ${a.generatedAt ? fmtFull(a.generatedAt) : '-'} · pencere ${a.windowHours} sa`
            + (wh ? ` · ücretli saat ${wh.start}-${wh.end} (${wh.now ? 'şu an içinde' : 'şu an dışında'})` : '')
            + ' · sayfa açıkken dakikada bir yenilenir';

        const latest = a.versions && a.versions.latest;
        const outdated = new Set((a.versions && a.versions.outdatedUsers) || []);
        $('gZones').innerHTML = (a.zones || []).map(z => {
            const shift = z.openShift
                ? `<span class="badge badge-success">Açık</span><div class="muted" style="font-size:13px;">${escapeHtml(z.openShift.username || '')} · ${z.openShift.startedAt ? escapeHtml(fmtFull(z.openShift.startedAt)) : ''}</div>`
                : '<span class="badge badge-neutral">Kapalı</span>';
            const offs = (z.officers || []).map(o => {
                const old = outdated.has(o.username);
                return `<div><span class="g-dot${o.liveConnected ? ' on' : ''}" title="${o.liveConnected ? 'Canlı bağlı' : 'Bağlı değil'}"></span><b>${escapeHtml(o.username || '')}</b>
                    <span class="muted"> · ${escapeHtml(relTime(o.lastSeenAt))}</span>
                    <div class="muted" style="font-size:13px;">${escapeHtml(o.device || '-')} · ${escapeHtml(o.appVersion || '-')}${old ? ` <span class="badge badge-warning" title="${latest ? 'Güncel: ' + escapeHtml(latest) : ''}">Eski sürüm</span>` : ''}</div></div>`;
            }).join('') || '<span class="muted">Atanmış memur yok</span>';
            return `<tr><td><b>${escapeHtml(z.zoneName || '-')}</b><div class="muted" style="font-size:13px;">${escapeHtml(z.street || '')}</div></td>
                <td class="right num">${escapeHtml(z.activeVehicles ?? 0)}</td><td class="right num">${escapeHtml(z.checkIns ?? 0)}</td><td class="right num">${escapeHtml(z.checkOuts ?? 0)}</td>
                <td class="right num">${escapeHtml(z.runaways ?? 0)}</td><td class="right num">${escapeHtml(z.cancellations ?? 0)}</td>
                <td class="col-muted">${escapeHtml(relTime(z.lastActivityAt))}</td><td>${shift}</td><td style="min-width:220px;">${offs}</td></tr>`;
        }).join('') || '<tr><td colspan="9" class="table-empty">Saha yok.</td></tr>';

        const sy = a.sync || {}, lg = a.logins || {};
        const delay = sy.maxOfflineDelaySeconds || 0;
        $('gSync').innerHTML = kv('Canlı bağlantı', sy.liveConnections) + kv('Çevrimdışı giriş', sy.offlineCheckIns)
            + kv('En uzun gecikme', delay ? (delay >= 60 ? `${Math.round(delay / 60)} dk` : `${delay} sn`) : '0')
            + kv('Başarılı giriş', lg.success) + kv('Başarısız giriş', lg.failed)
            + kv('Güncel sürüm', latest) + kv('Eski sürümdekiler', [...outdated].join(', ') || '-');

        const er = a.errors || {};
        const days = er.byDay || [];
        const max = Math.max(1, ...days.map(d => d.count || 0));
        $('gTrend').innerHTML = days.map(d => `<div class="g-bar${d.count ? ' has' : ''}" title="${escapeHtml(d.date)}: ${escapeHtml(d.count)} hata">
            <span>${escapeHtml(d.count || 0)}</span><i style="height:${Math.round((d.count || 0) / max * 90)}px"></i><span>${escapeHtml(String(d.date || '').slice(5))}</span></div>`).join('')
            || '<span class="muted">Veri yok.</span>';
        $('gTop').innerHTML = (er.topCodes || []).map(t => `<tr><td>${escapeHtml(t.code || '-')}</td><td>${escapeHtml(t.path || '-')}</td><td class="right num">${escapeHtml(t.count)}</td></tr>`).join('')
            || '<tr><td colspan="3" class="table-empty">Pencerede hata yok.</td></tr>';
    }
    async function loadActivity() {
        try {
            renderActivity(await apiJson('GET', `/api/developer/activity?hours=${encodeURIComponent($('gWin').value)}`) || {});
        } catch (e) {
            if (e.name !== 'AuthError') $('gAlerts').innerHTML = `<div class="alert alert-danger">${escapeHtml(e.message)}</div>`;
        }
    }
    // Yalnızca sayfa açık ve sekme görünürken 60 sn'de bir
    function startTimer() {
        if (actTimer) return;
        actTimer = setInterval(() => {
            const sec = $('sec-gelistirici');
            if (!sec || sec.hidden) { clearInterval(actTimer); actTimer = null; return; }
            if (document.visibilityState === 'visible') loadActivity();
        }, 60000);
    }
    document.addEventListener('visibilitychange', () => {
        const sec = $('sec-gelistirici');
        if (built && sec && !sec.hidden && document.visibilityState === 'visible') { loadActivity(); startTimer(); }
    });

    // ---- Denetim kaydı (salt okunur) ----
    function auRoleBadge(role) {
        if (role === 'DEVELOPER') return '<span class="badge badge-warning">Geliştirici</span>';
        if (role === 'ADMIN') return '<span class="badge badge-primary">Yönetici</span>';
        if (role === 'OFFICER') return '<span class="badge badge-neutral">Memur</span>';
        return '<span class="badge badge-sub">Sistem</span>';
    }
    async function loadAuditActions() {
        try {
            const raw = await apiJson('GET', '/api/developer/audit/actions');
            const list = Array.isArray(raw) ? raw : [];
            auLabels = {};
            list.forEach(x => { auLabels[x.action] = x.label; });
            const sel = $('gAuAction'), cur = sel.value;
            sel.innerHTML = '<option value="">Tümü</option>' + list.map(x => `<option value="${escapeHtml(x.action)}">${escapeHtml(x.label || x.action)}</option>`).join('');
            sel.value = cur;
            auActionsLoaded = true;
        } catch (e) { /* ham kodlar gösterilir */ }
    }
    async function loadAudit() {
        const seq = ++auSeq;
        const q = new URLSearchParams();
        const map = { from: 'gAuFrom', to: 'gAuTo', action: 'gAuAction', actor: 'gAuActor', actorRole: 'gAuRole', q: 'gAuQ' };
        Object.keys(map).forEach(k => { const v = $(map[k]).value.trim(); if (v) q.set(k, v); });
        q.set('page', auPage); q.set('size', 25);
        const err = $('gAuErr');
        try {
            const d = await apiJson('GET', `/api/developer/audit?${q}`);
            if (seq !== auSeq) return;
            err.hidden = true;
            const rows = (d && d.content) || [];
            $('gAuBody').innerHTML = rows.map(r => `<tr>
                <td class="col-muted num">${r.createdAt ? escapeHtml(fmtFull(r.createdAt)) : '-'}</td>
                <td>${r.actorUsername ? `<b>${escapeHtml(r.actorUsername)}</b> ` : '<span class="muted">—</span> '}${auRoleBadge(r.actorRole)}</td>
                <td>${escapeHtml(auLabels[r.action] || r.action || '-')}</td>
                <td style="min-width:220px;">${escapeHtml(r.summary || '')}</td>
                <td class="col-muted">${escapeHtml(r.deviceId ? shortDevice(r.deviceId) : '-')}<div style="font-size:13px;">${escapeHtml(r.ipAddress || '')}</div></td>
            </tr>`).join('') || '<tr><td colspan="5" class="table-empty">Bu filtreyle kayıt yok.</td></tr>';
            const total = d ? d.totalElements : 0, pages = Math.max(1, d ? d.totalPages : 1);
            $('gAuInfo').textContent = total ? `${total} kayıt · Sayfa ${auPage + 1} / ${pages}` : '0 kayıt';
            $('gAuPrev').disabled = auPage <= 0;
            $('gAuNext').disabled = auPage >= pages - 1;
        } catch (e) {
            if (e.name === 'AuthError' || seq !== auSeq) return;
            if (e.code === 'VALIDATION_ERROR') { err.textContent = e.message; err.hidden = false; }
            else $('gAuBody').innerHTML = `<tr><td colspan="5" class="table-empty">${escapeHtml(e.message)}</td></tr>`;
        }
    }

    // ---- Yönetici şifresi ----
    async function resetAdminPassword(e) {
        e.preventDefault();
        const username = $('gPwUser').value.trim().toLowerCase();
        const pw = $('gPwNew').value;
        setFieldError('gPwUser', 'gPwUserErr', '');
        setFieldError('gPwNew', 'gPwNewErr', '');
        let bad = false;
        if (!username) { setFieldError('gPwUser', 'gPwUserErr', 'Kullanıcı adını girin.'); bad = true; }
        const pp = pwProblem(pw, username);
        if (pp) { setFieldError('gPwNew', 'gPwNewErr', pp); bad = true; }
        if (bad) return;
        const ok = await confirmDialog({
            title: 'Yönetici şifresini sıfırla',
            message: `"${username}" hesabının şifresi değiştirilecek ve açık oturumları kapanacak. Bu işlem denetim kaydına yazılır.`,
            confirmLabel: 'Sıfırla', danger: true
        });
        if (!ok) return;
        const btn = $('gPwSubmit');
        btn.disabled = true;
        try {
            const res = await apiJson('POST', '/api/developer/admin-password-reset', { username, newPassword: pw });
            $('gPwNew').value = '';
            toast((res && res.message) || 'Şifre sıfırlandı.');
            showCredentials((res && res.username) || username, pw);
        } catch (err) {
            if (err.name === 'AuthError') return;
            if (err.code === 'WEAK_PASSWORD') setFieldError('gPwNew', 'gPwNewErr', err.message);
            else if (err.code === 'PROTECTED_ACCOUNT' || err.status === 404) setFieldError('gPwUser', 'gPwUserErr', err.message);
            else toast('Hata: ' + err.message, true);
        } finally {
            btn.disabled = false;
        }
    }

    // Yeni şifreyi bir kez göster
    let credDlg = null;
    function showCredentials(username, password) {
        if (!credDlg) { credDlg = document.createElement('dialog'); credDlg.id = 'gCredDlg'; document.body.appendChild(credDlg); }
        credDlg.innerHTML = `<form class="dlg-body" novalidate>
            <h2>Şifre sıfırlandı</h2>
            <div class="alert alert-warning"><i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i> Bu bilgileri yöneticiye iletin; şifre bir daha gösterilmez.</div>
            <dl class="tar-grid">
                <div class="tar-item"><dt>Kullanıcı adı</dt><dd>${escapeHtml(username)}</dd></div>
                <div class="tar-item"><dt>Yeni şifre</dt><dd style="user-select:all;">${escapeHtml(password)}</dd></div>
            </dl>
            <div class="dlg-actions">
                <button type="button" class="btn btn-secondary" id="gCredCopy"><i class="fa-regular fa-copy" aria-hidden="true"></i> Kopyala</button>
                <button type="submit" class="btn btn-primary">Tamam</button>
            </div></form>`;
        const form = credDlg.querySelector('form');
        form.addEventListener('submit', ev => { ev.preventDefault(); credDlg.close(); });
        form.querySelector('#gCredCopy').addEventListener('click', async () => {
            try { await navigator.clipboard.writeText(`Kullanıcı adı: ${username}\nŞifre: ${password}`); toast('Panoya kopyalandı.'); }
            catch (err) { toast('Kopyalanamadı; bilgiyi elle kopyalayın.', true); }
        });
        credDlg.showModal();
    }

    PAGES.gelistirici = {
        async onShow() {
            if (!built) build();
            loadLicenseCard();
            loadActivity();
            startTimer();
            if (!auActionsLoaded) loadAuditActions();
            auPage = 0; loadAudit();
            // Sağlık ve hatalar her görüntülemede denetim kaydına düşer: kısa süre içinde tekrar sorma
            const now = Date.now();
            if (fetching || now - lastFetch < 3000) return;
            fetching = true;
            try { await Promise.all([loadHealth(), loadErrors()]); }
            finally { fetching = false; lastFetch = Date.now(); }
        },
        onLicense() { if (built) renderLicenseCard(); }
    };
})();
