import { useState, useEffect } from 'react'

interface KPI {
  title: string;
  value: string;
  prefix?: string;
  suffix?: string;
}

interface Record {
  id: number;
  licensePlate: string;
  street: string;
  vehicleType: string;
  fee: number;
  status: 'PAID' | 'RUNAWAY';
  exitTime: string;
}

function App() {
  const [activeTab, setActiveTab] = useState('dashboard');
  
  // Dummy data for initial UI scaffolding (Will be replaced with API calls)
  const kpis: KPI[] = [
    { title: "Bugünkü Ciro", value: "14,250", suffix: "₺" },
    { title: "Haftalık Ciro", value: "86,400", suffix: "₺" },
    { title: "Kaçak Araçlar", value: "12", suffix: " Adet" },
    { title: "Aktif Araç", value: "148" }
  ];

  const recentRecords: Record[] = [
    { id: 1, licensePlate: "34 ABC 123", street: "Tevfik Bey Caddesi", vehicleType: "SMALL", fee: 50.0, status: "PAID", exitTime: "14:30" },
    { id: 2, licensePlate: "06 XYZ 987", street: "Ali Rıza Özkay", vehicleType: "LARGE", fee: 160.0, status: "RUNAWAY", exitTime: "14:15" },
    { id: 3, licensePlate: "11 BLC 11", street: "Cumhuriyet Caddesi", vehicleType: "SMALL", fee: 80.0, status: "PAID", exitTime: "14:00" },
  ];

  return (
    <div className="dashboard-container">
      {/* Sidebar */}
      <div className="sidebar">
        <div className="sidebar-logo">
          🅿️ BilPark Admin
        </div>
        <div className={`sidebar-item ${activeTab === 'dashboard' ? 'active' : ''}`} onClick={() => setActiveTab('dashboard')}>
          📊 Dashboard
        </div>
        <div className={`sidebar-item ${activeTab === 'users' ? 'active' : ''}`} onClick={() => setActiveTab('users')}>
          👥 Personel Yönetimi
        </div>
        <div className={`sidebar-item ${activeTab === 'reports' ? 'active' : ''}`} onClick={() => setActiveTab('reports')}>
          📄 Detaylı Raporlar
        </div>
        <div className={`sidebar-item ${activeTab === 'settings' ? 'active' : ''}`} onClick={() => setActiveTab('settings')}>
          ⚙️ Ayarlar
        </div>
      </div>

      {/* Main Content */}
      <div className="main-content">
        <div className="header">
          <h1>Yönetici Özeti</h1>
          <div className="user-profile">
            <span style={{ fontWeight: 600 }}>Admin</span>
          </div>
        </div>

        {/* KPI Cards */}
        <div className="kpi-grid">
          {kpis.map((kpi, index) => (
            <div key={index} className="kpi-card glass">
              <div className="kpi-title">{kpi.title}</div>
              <div className="kpi-value">
                {kpi.prefix}{kpi.value}
                <span style={{ fontSize: '1rem', color: 'var(--text-light)', marginLeft: '4px' }}>
                  {kpi.suffix}
                </span>
              </div>
            </div>
          ))}
        </div>

        {/* Recent Transactions Table */}
        <div className="table-container">
          <h2>Son İşlemler</h2>
          <table>
            <thead>
              <tr>
                <th>Plaka</th>
                <th>Lokasyon</th>
                <th>Araç Tipi</th>
                <th>Tutar</th>
                <th>Durum</th>
                <th>Çıkış Saati</th>
              </tr>
            </thead>
            <tbody>
              {recentRecords.map(record => (
                <tr key={record.id}>
                  <td style={{ fontWeight: 600 }}>{record.licensePlate}</td>
                  <td>{record.street}</td>
                  <td>{record.vehicleType === 'LARGE' ? 'Kamyonet' : 'Otomobil'}</td>
                  <td>{record.fee} ₺</td>
                  <td>
                    <span className={`badge ${record.status === 'PAID' ? 'badge-paid' : 'badge-runaway'}`}>
                      {record.status === 'PAID' ? 'Ödendi' : 'Kaçak'}
                    </span>
                  </td>
                  <td>{record.exitTime}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

export default App
