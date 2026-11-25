// Personel sayfası (ADMIN): kullanıcı listesi, ekle, düzenle, saha ata, pasife al, şifre sıfırla, bu ay puanı
(() => {
    let built = false;
    let users = [];
    let zones = [];
    let scores = {};        // username -> {score, rating}
    let scoresOk = false;
    let loadSeq = 0;

    const USERNAME_RE = /^[a-z0-9._-]{3,50}$/;

    // ---- Yardımcılar ----
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
    function me() { return (localStorage.getItem('username') || '').toLowerCase(); }
    function isSelf(u) { return String(u.username || '').toLowerCase() === me(); }
    function zoneLabel(z) { return z.zoneName || `${streetNames[z.street] || z.street || ''} ${z.zoneNumber || ''}`.trim(); }
    function zoneOptions(selected) {
        return '<option value="">Saha seçin</option>' + zones.map(z =>
            `<option value="${z.id}"${String(z.id) === String(selected) ? ' selected' : ''}>${escapeHtml(zoneLabel(z))}</option>`).join('');
    }
    function thisMonth() { const d = new Date(); return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}`; }

    // ---- Tek diyalog, içerik her açılışta yeniden çizilir ----
    let dlg = null;
    function ensureDialog() {
        if (dlg) return dlg;
        dlg = document.createElement('dialog');
        dlg.id = 'pDlg';
        document.body.appendChild(dlg);
        return dlg;
    }
    // html: <form> içi; onSubmit(form, submitBtn) asenkron olabilir
    function openDlg(html, onSubmit) {
        const d = ensureDialog();
        d.innerHTML = `<form class="dlg-body" novalidate>${html}</form>`;
        const form = d.querySelector('form');
        const cancel = form.querySelector('[data-cancel]');
        if (cancel) cancel.addEventListener('click', () => d.close());
        form.addEventListener('submit', async e => {
            e.preventDefault();
            const btn = form.querySelector('[type=submit]');
            btn.disabled = true;
            try { await onSubmit(form); }
            finally { btn.disabled = isReadOnly() && btn.hasAttribute('data-write'); }
        });
        applyReadOnly(d);
        d.showModal();
        return form;
    }
    function pwFieldHtml(id, label) {
        return `<label class="field" for="${id}">${label}
                <span style="display:flex; gap:8px;">
                    <input type="text" class="input" id="${id}" autocomplete="off" spellcheck="false" style="flex:1; min-width:0;" aria-describedby="${id}Err">
                    <button type="button" class="btn btn-secondary" id="${id}Gen"><i class="fa-solid fa-dice" aria-hidden="true"></i> Rastgele</button>
                </span>
                <span class="muted" style="font-weight:500; font-size:13px;">En az 8 karakter, harf ve rakam içermeli, kullanıcı adını içermemeli.</span>
                <span class="field-error" id="${id}Err" hidden></span>
            </label>`;
    }
    function wireGen(id) { $(id + 'Gen').addEventListener('click', () => { $(id).value = genPassword(); setFieldError(id, id + 'Err', ''); }); }

    // Oluşturulan/sıfırlanan bilgiyi bir kez göster
    function showCredentials(title, username, password) {
        const form = openDlg(`
            <h2>${escapeHtml(title)}</h2>
            <div class="alert alert-warning"><i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i> Bu bilgileri personele iletin; şifre bir daha gösterilmez.</div>
            <dl class="tar-grid">
                <div class="tar-item"><dt>Kullanıcı adı</dt><dd>${escapeHtml(username)}</dd></div>
                <div class="tar-item"><dt>Şifre</dt><dd style="user-select:all;">${escapeHtml(password)}</dd></div>
            </dl>
            <div class="dlg-actions">
                <button type="button" class="btn btn-secondary" id="pCopy"><i class="fa-regular fa-copy" aria-hidden="true"></i> Kopyala</button>
                <button type="submit" class="btn btn-primary">Tamam</button>
            </div>`, async () => { dlg.close(); });
        form.querySelector('#pCopy').addEventListener('click', async () => {
            try { await navigator.clipboard.writeText(`Kullanıcı adı: ${username}\nŞifre: ${password}`); toast('Panoya kopyalandı.'); }
            catch (e) { toast('Kopyalanamadı; bilgiyi elle kopyalayın.', true); }
        });
    }

    // ---- İskelet ----
    function build() {
        $('sec-personel').innerHTML = `
            <style>
                #sec-personel .row-actions { display: flex; gap: 6px; flex-wrap: nowrap; }
                #sec-personel .row-actions .btn { flex: none; }
                #sec-personel .table td { vertical-align: middle; }
            </style>
            <div class="page-head">
                <div><h1>Personel</h1><div class="page-sub">Memur ve yönetici hesapları. Pasife alınan kişi giriş yapamaz.</div></div>
                <div class="toolbar"><button type="button" class="btn btn-primary" id="pAdd" data-write><i class="fa-solid fa-user-plus" aria-hidden="true"></i> Memur ekle</button></div>
            </div>
            <div class="card">
                <div class="card-body">
                    <div class="filters">
                        <label class="field">Ara (ad veya kullanıcı adı)<input type="search" class="input" id="pSearch" placeholder="Ad veya kullanıcı adı" autocomplete="off"></label>
                        <label class="check" style="align-self:end;"><input type="checkbox" id="pInactive"> Pasifleri göster</label>
                    </div>
                </div>
            </div>
            <div class="card" style="margin-top:16px;">
                <div class="table-wrap">
                    <table class="table">
                        <thead><tr>
                            <th>Personel</th><th>Rol</th><th>Saha</th><th>Son giriş</th><th>Son görülme</th><th>Uygulama sürümü</th>
                            <th class="right" title="Performans puanı yalnızca bu panelde görünür; saha uygulamasında gösterilmez.">Bu ay puanı</th>
                            <th>Durum</th><th>İşlemler</th>
                        </tr></thead>
                        <tbody id="pBody"><tr><td colspan="9" class="table-empty">Yükleniyor...</td></tr></tbody>
                    </table>
                </div>
            </div>`;
        $('pAdd').addEventListener('click', openAdd);
        $('pSearch').addEventListener('input', render);
        $('pInactive').addEventListener('change', render);
        $('pBody').addEventListener('click', onRowClick);
        built = true;
    }

    // ---- Veri ----
    async function load() {
        const seq = ++loadSeq;
        try {
            const [list, zs] = await Promise.all([apiJson('GET', '/api/admin/users'), apiJson('GET', '/api/zones')]);
            if (seq !== loadSeq) return;
            users = list || [];
            zones = zs || [];
            render();
        } catch (e) {
            if (seq !== loadSeq || e.name === 'AuthError') return;
            $('pBody').innerHTML = `<tr><td colspan="9" class="table-empty">${escapeHtml(e.message)}</td></tr>`;
            return;
        }
        // Puanlar ayrı gelir; hata olursa "-" gösterilir
        try {
            const sum = await apiJson('GET', `/api/admin/reports/personnel/summary?month=${thisMonth()}`);
            if (seq !== loadSeq) return;
            scores = {};
            (sum || []).forEach(r => { scores[r.username] = r; });
            scoresOk = true;
        } catch (e) {
            if (e.name === 'AuthError') return;
            scores = {}; scoresOk = false;
        }
        render();
    }

    function scoreHtml(u) {
        const s = scoresOk ? scores[u.username] : null;
        if (!s || s.score == null) return '<span class="muted">-</span>';
        const v = Math.round(s.score);
        const cls = v >= 85 ? 'badge-success' : (v >= 65 ? 'badge-primary' : 'badge-warning');
        return `<span class="badge ${cls}" title="${escapeHtml(s.rating || '')}">${v}</span>`;
    }

    function actionsHtml(u) {
        if (u.role === 'DEVELOPER') return '<span class="muted">Değiştirilemez</span>';
        const self = isSelf(u);
        const btn = (act, icon, label, extra) =>
            `<button type="button" class="btn btn-icon btn-secondary" data-act="${act}" data-id="${u.id}" aria-label="${label}" title="${(extra && extra.title) || label}" data-write${extra && extra.disabled ? ' disabled' : ''}><i class="fa-solid ${icon}" aria-hidden="true"></i></button>`;
        let h = btn('edit', 'fa-pen', 'Düzenle');
        if (u.role === 'OFFICER') h += btn('zone', 'fa-location-dot', 'Saha ata');
        if (u.role === 'OFFICER') h += btn('reset', 'fa-key', 'Şifre sıfırla');
        else h += `<button type="button" class="btn btn-icon btn-secondary" disabled aria-label="Şifre sıfırla" title="Yönetici şifreleri geliştirici tarafından sıfırlanır"><i class="fa-solid fa-key" aria-hidden="true"></i></button>`;
        h += u.active
            ? `<button type="button" class="btn btn-icon btn-danger-outline" data-act="deactivate" data-id="${u.id}" aria-label="Pasife al" title="${self ? 'Kendi hesabınızı pasife alamazsınız' : 'Pasife al'}" data-write${self ? ' disabled' : ''}><i class="fa-solid fa-user-slash" aria-hidden="true"></i></button>`
            : btn('activate', 'fa-user-check', 'Aktifleştir');
        return `<div class="row-actions">${h}</div>`;
    }

    function render() {
        if (!built) return;
        const q = $('pSearch').value.trim().toLocaleLowerCase('tr-TR');
        const showInactive = $('pInactive').checked;
        const list = users.filter(u => (showInactive || u.active)
            && (!q || String(u.fullName || '').toLocaleLowerCase('tr-TR').includes(q) || String(u.username || '').toLowerCase().includes(q)));
        $('pBody').innerHTML = list.map(u => {
            const dev = u.role === 'DEVELOPER';
            const roleBadge = dev ? '<span class="badge badge-sub">Geliştirici</span>'
                : `<span class="badge ${u.role === 'ADMIN' ? 'badge-primary' : 'badge-neutral'}">${escapeHtml(ROLE_LABELS[u.role] || u.role)}</span>`;
            return `<tr>
                <td><b>${escapeHtml(u.fullName || u.username)}</b>${isSelf(u) ? ' <span class="badge badge-neutral">Siz</span>' : ''}<div class="muted" style="font-size:13px;">${escapeHtml(u.username)}</div></td>
                <td>${roleBadge}${dev ? '<div class="muted" style="font-size:13px;">Değiştirilemez</div>' : ''}</td>
                <td>${escapeHtml(u.zoneName || '-')}</td>
                <td class="col-muted num">${u.lastLoginTime ? escapeHtml(fmtFull(u.lastLoginTime)) : '-'}</td>
                <td class="col-muted">${u.lastSeenAt ? escapeHtml(fmtFull(u.lastSeenAt)) : '-'}</td>
                <td class="col-muted">${u.appVersion || u.device ? `${escapeHtml(u.appVersion || '-')}<div style="font-size:13px;">${escapeHtml(u.device || '-')}</div>` : '-'}</td>
                <td class="right num">${dev ? '<span class="muted">-</span>' : scoreHtml(u)}</td>
                <td>${u.active ? '<span class="badge badge-success">Aktif</span>' : '<span class="badge badge-neutral">Pasif</span>'}</td>
                <td>${actionsHtml(u)}</td>
            </tr>`;
        }).join('') || '<tr><td colspan="9" class="table-empty">Personel bulunamadı.</td></tr>';
        applyReadOnly($('pBody'));
    }

    function onRowClick(e) {
        const b = e.target.closest('[data-act]');
        if (!b || b.disabled) return;
        const u = users.find(x => String(x.id) === b.dataset.id);
        if (!u) return;
        ({ edit: openEdit, zone: openZone, reset: openReset, deactivate: toggleActive, activate: toggleActive })[b.dataset.act](u);
    }

    // ---- Ekle ----
    function openAdd() {
        const form = openDlg(`
            <h2>Memur ekle</h2>
            <label class="field" for="paUser">Kullanıcı adı
                <input type="text" class="input" id="paUser" autocomplete="off" autocapitalize="none" spellcheck="false" aria-describedby="paUserErr">
                <span class="muted" style="font-weight:500; font-size:13px;">3-50 karakter: küçük harf, rakam, nokta, tire, alt çizgi.</span>
                <span class="field-error" id="paUserErr" hidden></span>
            </label>
            <label class="field" for="paName">Ad soyad
                <input type="text" class="input" id="paName" autocomplete="off" maxlength="100" aria-describedby="paNameErr">
                <span class="field-error" id="paNameErr" hidden></span>
            </label>
            <label class="field" for="paRole">Rol
                <select class="select" id="paRole"><option value="OFFICER">Memur</option><option value="ADMIN">Yönetici</option></select>
            </label>
            <label class="field" for="paZone" id="paZoneWrap">Saha
                <select class="select" id="paZone" aria-describedby="paZoneErr">${zoneOptions('')}</select>
                <span class="field-error" id="paZoneErr" hidden></span>
            </label>
            ${pwFieldHtml('paPw', 'Başlangıç şifresi')}
            <div class="dlg-actions">
                <button type="button" class="btn btn-secondary" data-cancel>Vazgeç</button>
                <button type="submit" class="btn btn-primary" data-write>Ekle</button>
            </div>`, async () => {
            const username = $('paUser').value.trim().toLowerCase();
            const fullName = $('paName').value.trim();
            const role = $('paRole').value;
            const zoneId = $('paZone').value;
            const pw = $('paPw').value;
            ['paUser', 'paName', 'paZone', 'paPw'].forEach(id => setFieldError(id, id + 'Err', ''));
            let bad = false;
            if (!USERNAME_RE.test(username)) { setFieldError('paUser', 'paUserErr', 'Kullanıcı adı 3-50 karakter olmalı; yalnızca a-z, 0-9, nokta, tire ve alt çizgi.'); bad = true; }
            if (!fullName) { setFieldError('paName', 'paNameErr', 'Ad soyad girin.'); bad = true; }
            if (role === 'OFFICER' && !zoneId) { setFieldError('paZone', 'paZoneErr', 'Memur için saha seçin.'); bad = true; }
            const pp = pwProblem(pw, username);
            if (pp) { setFieldError('paPw', 'paPwErr', pp); bad = true; }
            if (bad) return;
            try {
                const body = { username, fullName, password: pw, role };
                if (role === 'OFFICER') body.zoneId = Number(zoneId);
                await apiJson('POST', '/api/admin/users', body);
                dlg.close();
                toast('Kullanıcı eklendi.');
                showCredentials('Kullanıcı oluşturuldu', username, pw);
                load();
            } catch (err) {
                if (err.name === 'AuthError') return;
                if (err.code === 'USERNAME_TAKEN') setFieldError('paUser', 'paUserErr', err.message);
                else if (err.code === 'WEAK_PASSWORD') setFieldError('paPw', 'paPwErr', err.message);
                else toast('Hata: ' + err.message, true);
            }
        });
        wireGen('paPw');
        const syncRole = () => { $('paZoneWrap').hidden = $('paRole').value !== 'OFFICER'; };
        $('paRole').addEventListener('change', syncRole);
        syncRole();
        $('paUser').focus();
    }

    // ---- Düzenle ----
    function openEdit(u) {
        const self = isSelf(u);
        openDlg(`
            <h2>Düzenle: ${escapeHtml(u.username)}</h2>
            <label class="field" for="peName">Ad soyad
                <input type="text" class="input" id="peName" maxlength="100" value="${escapeHtml(u.fullName || '')}" aria-describedby="peNameErr">
                <span class="field-error" id="peNameErr" hidden></span>
            </label>
            <label class="field" for="peRole">Rol
                <select class="select" id="peRole"${self ? ' disabled title="Kendi rolünüzü değiştiremezsiniz"' : ''}>
                    <option value="OFFICER"${u.role === 'OFFICER' ? ' selected' : ''}>Memur</option>
                    <option value="ADMIN"${u.role === 'ADMIN' ? ' selected' : ''}>Yönetici</option>
                </select>
                ${self ? '<span class="muted" style="font-weight:500; font-size:13px;">Kendi rolünüzü değiştiremezsiniz.</span>' : ''}
            </label>
            <label class="field" for="peZone" id="peZoneWrap">Saha
                <select class="select" id="peZone" aria-describedby="peZoneErr">${zoneOptions(u.zoneId)}</select>
                <span class="field-error" id="peZoneErr" hidden></span>
            </label>
            <div class="alert alert-info" id="peNote" hidden><i class="fa-solid fa-circle-info" aria-hidden="true"></i> Yöneticiye çevrilen kişinin sahası boşaltılır, açık vardiyası kapanır.</div>
            <div class="dlg-actions">
                <button type="button" class="btn btn-secondary" data-cancel>Vazgeç</button>
                <button type="submit" class="btn btn-primary" data-write>Kaydet</button>
            </div>`, async () => {
            const fullName = $('peName').value.trim();
            const role = $('peRole').value;
            const zoneId = $('peZone').value;
            ['peName', 'peZone'].forEach(id => setFieldError(id, id + 'Err', ''));
            let bad = false;
            if (!fullName) { setFieldError('peName', 'peNameErr', 'Ad soyad girin.'); bad = true; }
            if (role === 'OFFICER' && !zoneId) { setFieldError('peZone', 'peZoneErr', 'Memur için saha seçin.'); bad = true; }
            if (bad) return;
            // Kısmi güncelleme: yalnız değişen alanlar
            const body = {};
            if (fullName !== (u.fullName || '')) body.fullName = fullName;
            if (role !== u.role) body.role = role;
            if (role === 'OFFICER' && String(zoneId) !== String(u.zoneId || '')) body.zoneId = Number(zoneId);
            if (!Object.keys(body).length) { dlg.close(); toast('Değişiklik yok.'); return; }
            try {
                await apiJson('PUT', `/api/admin/users/${u.id}`, body);
                dlg.close();
                toast('Kullanıcı güncellendi.');
                load();
            } catch (err) {
                if (err.name !== 'AuthError') toast('Hata: ' + err.message, true);
            }
        });
        const sync = () => {
            const officer = $('peRole').value === 'OFFICER';
            $('peZoneWrap').hidden = !officer;
            $('peNote').hidden = officer || u.role === 'ADMIN';
        };
        $('peRole').addEventListener('change', sync);
        sync();
    }

    // ---- Saha ata ----
    function openZone(u) {
        openDlg(`
            <h2>Saha ata: ${escapeHtml(u.fullName || u.username)}</h2>
            <label class="field" for="pzZone">Saha
                <select class="select" id="pzZone" aria-describedby="pzZoneErr">${zoneOptions(u.zoneId)}</select>
                <span class="field-error" id="pzZoneErr" hidden></span>
            </label>
            <div class="alert alert-info"><i class="fa-solid fa-circle-info" aria-hidden="true"></i> Açık vardiya eski sahada kalır; personel bir sonraki girişte yeni sahada başlar.</div>
            <div class="dlg-actions">
                <button type="button" class="btn btn-secondary" data-cancel>Vazgeç</button>
                <button type="submit" class="btn btn-primary" data-write>Ata</button>
            </div>`, async () => {
            const zoneId = $('pzZone').value;
            setFieldError('pzZone', 'pzZoneErr', '');
            if (!zoneId) { setFieldError('pzZone', 'pzZoneErr', 'Saha seçin.'); return; }
            try {
                await apiJson('PUT', `/api/admin/users/${u.id}/zone`, { zoneId: Number(zoneId) });
                dlg.close();
                toast('Saha atandı.');
                load();
            } catch (err) {
                if (err.name !== 'AuthError') toast('Hata: ' + err.message, true);
            }
        });
    }

    // ---- Pasife al / aktifleştir ----
    async function toggleActive(u) {
        const deactivate = u.active;
        if (deactivate && isSelf(u)) return;
        const ok = await confirmDialog(deactivate
            ? { title: 'Pasife al', message: `${u.fullName || u.username} oturumdan atılır, açık vardiyaları kapanır ve giriş yapamaz.`, confirmLabel: 'Pasife al', danger: true }
            : { title: 'Aktifleştir', message: `${u.fullName || u.username} yeniden giriş yapabilecek.`, confirmLabel: 'Aktifleştir' });
        if (!ok) return;
        try {
            await apiJson('PUT', `/api/admin/users/${u.id}/active`, { active: !deactivate });
            toast(deactivate ? 'Kullanıcı pasife alındı.' : 'Kullanıcı aktifleştirildi.');
            load();
        } catch (err) {
            if (err.name !== 'AuthError') toast('Hata: ' + err.message, true);
        }
    }

    // ---- Şifre sıfırla (yalnız OFFICER) ----
    function openReset(u) {
        openDlg(`
            <h2>Şifre sıfırla: ${escapeHtml(u.fullName || u.username)}</h2>
            <p>Kişinin açık oturumları kapanır; yeni şifreyle yeniden giriş yapar.</p>
            ${pwFieldHtml('prPw', 'Yeni şifre')}
            <div class="dlg-actions">
                <button type="button" class="btn btn-secondary" data-cancel>Vazgeç</button>
                <button type="submit" class="btn btn-primary" data-write>Sıfırla</button>
            </div>`, async () => {
            const pw = $('prPw').value;
            setFieldError('prPw', 'prPwErr', '');
            const pp = pwProblem(pw, u.username);
            if (pp) { setFieldError('prPw', 'prPwErr', pp); return; }
            try {
                await apiJson('POST', `/api/admin/users/${u.id}/reset-password`, { newPassword: pw });
                dlg.close();
                toast('Şifre sıfırlandı.');
                showCredentials('Şifre sıfırlandı', u.username, pw);
            } catch (err) {
                if (err.name === 'AuthError') return;
                if (err.code === 'WEAK_PASSWORD' || err.code === 'PROTECTED_ACCOUNT') setFieldError('prPw', 'prPwErr', err.message);
                else toast('Hata: ' + err.message, true);
            }
        });
        wireGen('prPw');
        $('prPw').focus();
    }

    PAGES.personel = {
        onShow() {
            if (!built) build();
            load();
        },
        onEvent(type) {
            if (!built || currentSection !== 'personel') return;
            if (type === 'USER_UPDATED' || type === 'USER_DEACTIVATED') load();
        }
    };
})();
