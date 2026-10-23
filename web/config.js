// Global Configuration for Web Apps
// API adresi ortama gore otomatik secilir:
//  - localhost / 127.0.0.1 / file:// -> yerel backend
//  - diger tum hostlar               -> uretim (Render) backend
// Dagitim basina degistirmek icin config.js'den ONCE su satiri ekleyin:
//   <script>window.BILPARK_API_BASE = 'https://api.ornek.com';</script>
const CONFIG = {
    BASE_URL: (function () {
        if (typeof window !== 'undefined' && window.BILPARK_API_BASE) {
            return String(window.BILPARK_API_BASE).replace(/\/+$/, '');
        }
        const h = (typeof location !== 'undefined' && location.hostname) || '';
        if (h === '' || h === 'localhost' || h === '127.0.0.1') {
            return 'http://localhost:8080';
        }
        return 'https://bilpark-api-rtdl.onrender.com';
    })()
};
