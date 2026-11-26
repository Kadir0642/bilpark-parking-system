// Vatandaş QR / borç sorgu sayfası (customer-payment.html).
// URL parametreleri: ?plate=34ABC123 (otomatik sorgu), ?zone= / ?street= (başlıktaki bölge), ?api= (config.js),
// ?kamera=1 (deneme: kamerayla plaka okuma; yoksa kamera kodu ve model hiç yüklenmez).
(function () {
    'use strict';

    const API = CONFIG.BASE_URL + '/api/parking';
    const $ = (id) => document.getElementById(id);
    const params = new URLSearchParams(window.location.search);
    const CAMERA_FLAG = params.get('kamera') === '1';

    let isProcessing = false;
    let lastPlate = '';
    let cooldownTimer = null;
    let cooldownUntil = 0;

    function showView(id) {
        document.querySelectorAll('.view').forEach(el => el.classList.remove('active'));
        $(id).classList.add('active');
        document.body.classList.toggle('has-result', id === 'view-result');
        window.scrollTo(0, 0);
    }

    function setBusy(busy) {
        isProcessing = busy;
        ['btnQuery', 'btnRefresh'].forEach(b => {
            $(b).disabled = busy || (b === 'btnQuery' && Date.now() < cooldownUntil);
            $(b).setAttribute('aria-busy', busy ? 'true' : 'false');
        });
        $('txtQuery').textContent = busy ? 'Sorgulanıyor…' : 'Ücreti göster';
        $('txtRefresh').textContent = busy ? 'Yenileniyor…' : 'Yenile';
        const cam = $('btnCamera');
        if (cam) cam.disabled = busy;
    }

    function showFieldError(msg) {
        const el = $('plateError');
        el.textContent = msg || '';
        el.hidden = !msg;
        $('plateField').classList.toggle('invalid', !!msg);
        $('plateInput').setAttribute('aria-invalid', msg ? 'true' : 'false');
    }
    function showError(msg) {
        const el = $('errorMsg');
        el.textContent = msg || '';
        el.hidden = !msg;
    }
    function showNotFound(msg) {
        $('notFoundText').textContent = msg || '';
        $('notFoundCard').hidden = !msg;
    }
    function clearMessages() {
        showError('');
        showNotFound('');
        $('refreshError').hidden = true;
    }

    // --- 429 RATE_LIMITED: sunucu {error, code} ve Retry-After (saniye) döner ---
    function parseRetryAfter(v) {
        if (v == null || v === '') return null;
        const n = Number(v);
        if (Number.isFinite(n) && n >= 0) return Math.min(600, Math.ceil(n));
        const d = Date.parse(v);
        if (!isNaN(d)) return Math.max(0, Math.min(600, Math.ceil((d - Date.now()) / 1000)));
        return null;
    }
    function showRateLimit(message, retryAfter) {
        const el = $('rateMsg');
        if (cooldownTimer) { clearInterval(cooldownTimer); cooldownTimer = null; }
        const base = message || 'Çok fazla sorgu yapıldı. Lütfen biraz bekleyip tekrar deneyin.';
        if (retryAfter == null || retryAfter <= 0) {
            cooldownUntil = 0;
            el.textContent = base;
            el.hidden = false;
            return;
        }
        cooldownUntil = Date.now() + retryAfter * 1000;
        const tick = () => {
            const left = Math.ceil((cooldownUntil - Date.now()) / 1000);
            if (left <= 0) {
                clearInterval(cooldownTimer); cooldownTimer = null;
                cooldownUntil = 0;
                el.textContent = base + ' Şimdi tekrar deneyebilirsiniz.';
                if (!isProcessing) $('btnQuery').disabled = false;
                return;
            }
            el.textContent = base + ' ' + left + ' saniye sonra tekrar deneyebilirsiniz.';
            $('btnQuery').disabled = true;
        };
        el.hidden = false;
        tick();
        cooldownTimer = setInterval(tick, 1000);
    }
    function hideRateLimit() {
        if (Date.now() < cooldownUntil) return; // bekleme sürüyorsa uyarı kalır
        $('rateMsg').hidden = true;
    }

    function fmtDuration(mins) {
        mins = Math.max(0, Math.round(Number(mins) || 0));
        const h = Math.floor(mins / 60), m = mins % 60;
        if (h === 0) return m + ' dk';
        return m === 0 ? h + ' sa' : h + ' sa ' + m + ' dk';
    }
    function fmtMoney(v) {
        const n = Number(v) || 0;
        return '₺' + n.toLocaleString('tr-TR', { maximumFractionDigits: 2 });
    }
    function fmtEntry(iso) {
        const d = new Date(iso);
        if (isNaN(d)) return '';
        const day = d.toLocaleDateString('tr-TR', { day: 'numeric', month: 'long' });
        const time = d.toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit', hour12: false });
        return day + ' ' + time;
    }
    // 11KM4471 -> 11 KM 4471 (tabeladaki gibi okunur); yabancı / özel plaka (foreign) olduğu gibi kalır
    function fmtPlate(p, foreign) {
        const s = String(p || '').replace(/\s+/g, '').toUpperCase();
        if (foreign) return s;
        const m = s.match(/^(\d{2})([A-Z]{1,3})(\d{2,5})$/);
        return m ? m[1] + ' ' + m[2] + ' ' + m[3] : s;
    }

    function row(cls, cells) {
        const r = document.createElement('div');
        r.className = 'breakdown-row ' + cls;
        cells.forEach(c => { const s = document.createElement('span'); s.textContent = c; r.appendChild(s); });
        return r;
    }

    // Sunucu saatleri Europe/Istanbul yerel saatidir (saat dilimi eki yok); dönüşüm yapılmaz, metinden okunur.
    function hhmm(iso) { return String(iso).slice(11, 16); }
    function dayOf(iso) { return String(iso).slice(0, 10); }
    // Ertesi günün 00:00'ı aralık sonunda 24:00 yazılır
    function endLabel(seg) {
        const t = hhmm(seg.end);
        return (t === '00:00' && dayOf(seg.end) > String(seg.date)) ? '24:00' : t;
    }
    function fmtDate(iso) {
        const p = String(iso).split(/[-T:]/).map(Number);
        const MONTHS = ['Ocak', 'Şubat', 'Mart', 'Nisan', 'Mayıs', 'Haziran', 'Temmuz', 'Ağustos', 'Eylül', 'Ekim', 'Kasım', 'Aralık'];
        return p.length >= 3 && p[1] ? p[2] + ' ' + MONTHS[p[1] - 1] : '';
    }

    // Gün gün döküm: segments gün (date) bazında gruplanır
    function renderBreakdown(data) {
        const box = $('breakdown');
        box.textContent = '';
        const segs = Array.isArray(data.segments) ? data.segments : [];
        if (!segs.length) {
            const total = row('breakdown-total', ['Toplam süre', fmtDuration(data.durationMinutes)]);
            total.style.gridTemplateColumns = '1fr auto';
            box.appendChild(total);
            return;
        }
        const days = [];
        const byDate = {};
        segs.forEach(sg => {
            let d = byDate[sg.date];
            if (!d) { d = byDate[sg.date] = { date: sg.date, paid: 0, free: 0, list: [] }; days.push(d); }
            if (sg.paid) d.paid += Number(sg.minutes) || 0; else d.free += Number(sg.minutes) || 0;
            d.list.push(sg);
        });
        box.appendChild(row('breakdown-head', ['Gün', 'Ücretli', 'Ücretsiz']));
        let paid = 0, free = 0;
        days.forEach(d => {
            paid += d.paid; free += d.free;
            box.appendChild(row('', [fmtDate(d.date), fmtDuration(d.paid), fmtDuration(d.free)]));
            const rg = document.createElement('div');
            rg.className = 'breakdown-ranges';
            d.list.forEach(sg => {
                const it = document.createElement('span');
                it.className = sg.paid ? 'range range-paid' : 'range range-free';
                const b = document.createElement('b');
                b.textContent = hhmm(sg.start) + '–' + endLabel(sg);
                it.appendChild(b);
                it.appendChild(document.createTextNode(sg.paid ? ' ücretli' : ' ücretsiz'));
                rg.appendChild(it);
            });
            box.appendChild(rg);
        });
        // Sunucunun toplamları varsa onlar kullanılır
        if (data.paidMinutes != null) paid = Number(data.paidMinutes);
        if (data.freeMinutes != null) free = Number(data.freeMinutes);
        box.appendChild(row('breakdown-total', ['Toplam', fmtDuration(paid), fmtDuration(free)]));
    }

    // Etkin araç tiplerinin ücret satırları; vehicleTypes yoksa (eski sunucu) small/large'a düşülür
    function tariffRows(t) {
        if (!t) return [];
        if (Array.isArray(t.vehicleTypes) && t.vehicleTypes.length) {
            return t.vehicleTypes.filter(v => v && v.enabled && v.firstHour != null)
                .slice().sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
                .map(v => ({ name: v.displayName || v.code, firstHour: v.firstHour, extraHour: v.extraHour }));
        }
        const rows = [];
        if (t.small) rows.push({ name: 'Otomobil', firstHour: t.small.firstHour, extraHour: t.small.extraHour });
        if (t.large) rows.push({ name: 'Kamyonet', firstHour: t.large.firstHour, extraHour: t.large.extraHour });
        return rows;
    }
    function tariffText(t) {
        return tariffRows(t).map(r => r.name + ': ilk saat ' + fmtMoney(r.firstHour) + ', sonraki her saat ' + fmtMoney(r.extraHour)).join(' · ');
    }
    function tariffTable(t) {
        const tbl = document.createElement('div');
        tbl.className = 'tariff';
        tbl.setAttribute('role', 'table');
        tbl.setAttribute('aria-label', 'Araç tipine göre ücretler');
        const head = document.createElement('div');
        head.className = 'tariff-row tariff-head';
        head.setAttribute('role', 'row');
        ['Araç', 'İlk saat', 'Sonraki saat'].forEach(h => {
            const s = document.createElement('span'); s.setAttribute('role', 'columnheader'); s.textContent = h; head.appendChild(s);
        });
        tbl.appendChild(head);
        tariffRows(t).forEach(r => {
            const tr = document.createElement('div');
            tr.className = 'tariff-row';
            tr.setAttribute('role', 'row');
            [r.name, fmtMoney(r.firstHour), fmtMoney(r.extraHour)].forEach((c, i) => {
                const s = document.createElement('span');
                s.setAttribute('role', 'cell');
                if (i > 0) s.className = 'num';
                s.textContent = c;
                tr.appendChild(s);
            });
            tbl.appendChild(tr);
        });
        return tbl;
    }
    // Tarife özeti; hata/eksik veri durumunda kart sessizce gizlenir
    function renderTariff(t, tariffId) {
        const card = $('tariffCard'), body = $('tariffBody');
        body.textContent = '';
        $('tariffNote').hidden = true;
        try {
            if (!t || !tariffRows(t).length) throw new Error('tarife yok');
            // Tutar farklı bir tarife sürümüyle hesaplandıysa özet gösterilmez
            if (tariffId != null && t.id != null && tariffId !== t.id) {
                $('tariffNote').hidden = false;
                throw new Error('tarife sürümü farklı');
            }
            body.appendChild(tariffTable(t));
            const p2 = document.createElement('p');
            p2.className = 'tariff-note';
            const ps = t.paidStart || '08:00', pe = t.paidEnd || '22:00';
            p2.textContent = 'İlk ' + (t.freeMinutes != null ? t.freeMinutes : 5) + ' dakika ücretsiz · Ücretli saatler ' + ps + '–' + (pe === '00:00' ? '24:00' : pe) + ', dışında ücretsiz. Aboneler ücret ödemez.';
            body.appendChild(p2);
            const u = t.upcoming;
            if (u && tariffRows(u).length && u.effectiveFrom) {
                const p3 = document.createElement('p');
                p3.className = 'alert alert-info';
                p3.textContent = fmtDate(u.effectiveFrom) + ' ' + hhmm(u.effectiveFrom) + ' itibarıyla yeni tarife: ' + tariffText(u);
                body.appendChild(p3);
            }
            card.hidden = false;
        } catch (e) {
            card.hidden = true;
        }
    }

    function renderResult(data, tariff) {
        // yabancı plakada isteğe bağlı ülke kodu (V11) plakanın yanında: "MAB1234 · D"
        const foreign = data.foreignPlate === true;
        const country = foreign && data.foreignCountryCode ? ' · ' + data.foreignCountryCode : '';
        $('resPlate').textContent = fmtPlate(data.plate || lastPlate, foreign) + country;
        $('resStreet').textContent = data.streetName || '';
        $('factStreetWrap').hidden = !data.streetName;
        const sub = !!data.hasSubscription;
        $('resFee').hidden = sub;
        $('resSub').hidden = !sub;
        $('resSubNote').hidden = !sub;
        $('view-result').classList.toggle('is-sub', sub);
        $('amountLabel').textContent = sub ? 'Ödenecek tutar' : 'Şu anki tutar';
        if (!sub) $('resFee').textContent = fmtMoney(data.fee);
        const entry = fmtEntry(data.entryTime);
        $('resEntry').textContent = entry;
        $('factEntryWrap').hidden = !entry;
        $('resDuration').textContent = fmtDuration(data.durationMinutes);
        $('resAsOf').textContent = new Date().toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit', hour12: false }) + ' itibarıyla';
        renderBreakdown(data);
        renderTariff(tariff, data.tariffId);
        const wasResult = $('view-result').classList.contains('active');
        showView('view-result');
        if (!wasResult) $('amountLabel').focus({ preventScroll: true });
    }

    // Tarife sayfa ömrü boyunca bir kez başarıyla alınırsa önbellekte tutulur
    let tariffCache = null;
    async function fetchTariff() {
        if (tariffCache) return tariffCache;
        try {
            const r = await fetch(CONFIG.BASE_URL + '/api/tariff/current');
            if (!r.ok) return null;
            tariffCache = await r.json();
            return tariffCache;
        } catch (e) { return null; }
    }
    async function fetchDebt(plate) {
        const response = await fetch(API + '/debt?plate=' + encodeURIComponent(plate));
        let data = {};
        const ct = response.headers.get('content-type');
        if (ct && ct.includes('application/json')) {
            try { data = await response.json(); } catch (e) { data = {}; }
        } else if (response.ok) {
            data = { error: 'Beklenmeyen bir sunucu yanıtı alındı.' };
        }
        return { ok: response.ok, status: response.status, data: data || {}, retryAfter: parseRetryAfter(response.headers.get('Retry-After')) };
    }

    function errorTarget(fromRefresh) {
        // Yenilemede hata sonuç ekranında gösterilir; sonuç kaybolmaz
        return fromRefresh && $('view-result').classList.contains('active');
    }

    async function queryDebt(fromRefresh) {
        if (isProcessing) return;
        if (!fromRefresh && Date.now() < cooldownUntil) return;
        // Boşluk ve tire atılır; yabancı / özel plakalar da (A-Z0-9, 2-12 karakter) sorgulanabilir
        const plate = fromRefresh ? lastPlate : $('plateInput').value.replace(/[\s-]+/g, '').toUpperCase();

        clearMessages();
        hideCamConfirm();
        if (plate.length < 2 || plate.length > 12) {
            showFieldError('Plaka 2 ile 12 karakter arasında olmalıdır.');
            $('plateInput').focus();
            return;
        }
        showFieldError('');
        hideRateLimit();
        lastPlate = plate;
        setBusy(true);

        try {
            // Borç ve tarife paralel istenir; tarife hatası tutarı etkilemez
            const [debt, tariff] = await Promise.all([fetchDebt(plate), fetchTariff()]);
            const data = debt.data;
            if (debt.ok && !data.error) {
                renderResult(data, (data.tariff && tariffRows(data.tariff).length) ? data.tariff : tariff);
            } else if (debt.status === 429 || data.code === 'RATE_LIMITED') {
                if (!errorTarget(fromRefresh)) showView('view-search');
                showRateLimit(data.error, debt.retryAfter);
                if (errorTarget(fromRefresh)) {
                    $('refreshError').textContent = data.error || 'Çok fazla sorgu yapıldı. Lütfen biraz bekleyip tekrar deneyin.';
                    $('refreshError').hidden = false;
                }
            } else if (debt.status === 404 || data.code === 'VEHICLE_NOT_FOUND') {
                showView('view-search');
                showNotFound(data.error || 'Bu plakaya ait aktif park kaydı bulunamadı.');
                $('notFoundCard').focus({ preventScroll: true });
            } else if (errorTarget(fromRefresh)) {
                $('refreshError').textContent = data.error || 'Tutar yenilenemedi. Lütfen tekrar deneyin.';
                $('refreshError').hidden = false;
            } else {
                showView('view-search');
                showError(data.error || 'Bu plakaya ait aktif park kaydı bulunamadı.');
            }
        } catch (e) {
            const msg = 'Bağlantı kurulamadı. İnternetinizi kontrol edip tekrar deneyin.';
            if (errorTarget(fromRefresh)) {
                $('refreshError').textContent = msg;
                $('refreshError').hidden = false;
            } else {
                showView('view-search');
                showError(msg);
            }
        } finally {
            setBusy(false);
        }
    }

    // --- Kamerayla okuma (yalnızca ?kamera=1) ---
    function hideCamConfirm() {
        $('camConfirm').hidden = true;
        $('camNotice').hidden = true;
        $('plateField').classList.remove('highlight');
    }
    function camNotice(msg) {
        const el = $('camNotice');
        el.textContent = msg;
        el.hidden = !msg;
    }
    let cameraModule = null;
    async function startCamera() {
        if (isProcessing) return;
        clearMessages();
        hideCamConfirm();
        showFieldError('');
        const btn = $('btnCamera');
        btn.disabled = true;
        let result;
        try {
            if (!cameraModule) cameraModule = await import(new URL('js/kamera/kamera.js', document.baseURI).href);
            result = await cameraModule.openCamera();
        } catch (e) {
            result = { kind: 'error', reason: 'yukleme' };
        } finally {
            btn.disabled = isProcessing;
        }
        const input = $('plateInput');
        if (result.kind === 'confirm') {
            input.value = fmtPlate(result.plate, result.foreign);
            $('camConfirmPlate').textContent = fmtPlate(result.plate, result.foreign);
            $('camConfirm').hidden = false;
            $('plateField').classList.add('highlight');
            $('btnCamYes').focus();
        } else if (result.kind === 'edit') {
            input.value = fmtPlate(result.guess, result.foreign);
            camNotice('Plakayı tam okuyamadık. Lütfen kontrol edip gerekirse düzeltin.');
            $('plateField').classList.add('highlight');
            input.focus();
            input.select();
        } else if (result.kind === 'none') {
            camNotice('Plaka okunamadı. Lütfen plakanızı yazın.');
            input.focus();
        } else if (result.kind === 'error') {
            const msgs = {
                izin: 'Kamera izni verilmedi. Plakanızı yazarak devam edebilirsiniz.',
                desteklenmiyor: 'Bu tarayıcıda kamera açılamıyor. Plakanızı yazarak devam edebilirsiniz.',
                kamera: 'Kamera açılamadı. Plakanızı yazarak devam edebilirsiniz.',
                motor: 'Okuma aracı yüklenemedi (internet bağlantısını kontrol edin). Plakanızı yazarak devam edebilirsiniz.',
                yukleme: 'Kamera özelliği yüklenemedi. Plakanızı yazarak devam edebilirsiniz.',
            };
            camNotice(msgs[result.reason] || msgs.kamera);
            input.focus();
        } else {
            input.focus(); // vazgeç / elle yaz
        }
    }

    function initCamera() {
        $('camEntry').hidden = false;
        $('btnCamera').addEventListener('click', startCamera);
        $('btnCamYes').addEventListener('click', () => queryDebt(false));
        $('btnCamFix').addEventListener('click', () => {
            hideCamConfirm();
            const input = $('plateInput');
            input.focus();
            input.select();
        });
    }

    document.addEventListener('DOMContentLoaded', () => {
        const zone = params.get('zone') || params.get('street');
        if (zone) $('zoneLine').textContent = zone.slice(0, 80);

        const input = $('plateInput');
        input.addEventListener('input', () => {
            input.value = input.value.toUpperCase().replace(/[^A-Z0-9\s-]/g, '');
            if (!$('plateError').hidden) showFieldError('');
            if (!$('camConfirm').hidden) hideCamConfirm();
        });
        $('searchForm').addEventListener('submit', (e) => { e.preventDefault(); queryDebt(false); });
        $('btnRefresh').addEventListener('click', () => queryDebt(true));
        $('btnBack').addEventListener('click', () => {
            clearMessages(); showFieldError('');
            showView('view-search');
            input.focus();
        });
        $('subToggle').addEventListener('click', () => {
            const t = $('subText');
            t.hidden = !t.hidden;
            $('subToggle').setAttribute('aria-expanded', t.hidden ? 'false' : 'true');
        });

        if (CAMERA_FLAG) initCamera();

        const plateFromUrl = params.get('plate');
        if (plateFromUrl) {
            input.value = plateFromUrl.toUpperCase().replace(/[^A-Z0-9\s-]/g, '').slice(0, 16);
            setTimeout(() => queryDebt(false), 300);
        }
    });
})();
