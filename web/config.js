// Global Configuration for Web Apps
// API adresi ortama gore otomatik secilir (oncelik sirasiyla):
//  1) window.BILPARK_API_BASE (config.js'den ONCE tanimlanirsa), ornek:
//       <script>window.BILPARK_API_BASE = 'https://api.ornek.com';</script>
//  2) Adres cubugunda ?api=... (ornek: admin-panel.html?api=http://localhost:8080)
//  3) localhost / 127.0.0.1 / file://      -> yerel demo backend (http://localhost:8081, docker compose)
//  4) Yerel ag IP'si (192.168.x, 10.x, ...) -> ayni bilgisayardaki demo backend (http://<IP>:8081)
//  5) Diger tum hostlar                    -> uretim (Render) backend
// IDE'den 8080'de calisan backend icin: ?api=http://localhost:8080
const CONFIG = {
    BASE_URL: (function () {
        if (typeof window !== 'undefined' && window.BILPARK_API_BASE) {
            return String(window.BILPARK_API_BASE).replace(/\/+$/, '');
        }
        if (typeof location !== 'undefined' && location.search) {
            const fromQuery = new URLSearchParams(location.search).get('api');
            if (fromQuery && /^https?:\/\//.test(fromQuery)) {
                return fromQuery.replace(/\/+$/, '');
            }
        }
        const h = (typeof location !== 'undefined' && location.hostname) || '';
        if (h === '' || h === 'localhost' || h === '127.0.0.1') {
            return 'http://localhost:8081';
        }
        if (/^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(h)) {
            return 'http://' + h + ':8081';
        }
        return 'https://bilpark-api-rtdl.onrender.com';
    })()
};
