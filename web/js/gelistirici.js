// Geliştirici sayfası (yalnız DEVELOPER): lisans, sistem sağlığı, son hatalar, yönetici şifresi sıfırlama
// Bu sayfadaki düğmeler data-write almaz: geliştirici uçları okunur modda da açıktır (lisansı yenilemenin yolu budur).
(() => {
    let built = false;
    let errors = [];
    let lastFetch = 0;      // sağlık/hata isteklerini peş peşe tekrar atmamak için
    let fetching = false;
    let licDraft = null;    // form alanları yeniden çizimde silinmesin

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
            </style>
            <div class="page-head">
                <div><h1>Geliştirici</h1>
                    <div class="page-sub">Bu sayfada yapılan her işlem denetim kaydına düşer ve yönetici tarafından görülür. Gizli düğme yoktur.</div></div>
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
