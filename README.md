# 🅿️ BilPark - Next-Generation Parking Management System

![Language](https://img.shields.io/badge/Language-Java%2021-orange) ![Framework](https://img.shields.io/badge/Framework-Spring%20Boot%203-brightgreen) ![Database](https://img.shields.io/badge/Database-PostgreSQL%20(Neon)-blue) ![Mobile](https://img.shields.io/badge/Mobile-Flutter-blue) ![Hosting](https://img.shields.io/badge/Hosting-Render-purple) ![Status](https://img.shields.io/badge/status-Active%20Development-green.svg) ![License](https://img.shields.io/badge/license-MIT-blue.svg)

> A cloud-based, mobile-first smart parking solution digitizing urban parking management.

---

## 🎯 Vision

**The Problem:**
Traditional parking systems based on fixed capacities, paper tickets, and handheld terminals increase operational workload and lead to revenue loss by making vehicle tracking and collection more difficult.

**The Solution:**
**BilPark** replaces physical tickets and static grids with **Dynamic Capacity, On-Device OCR, and Cloud Analytics**.
* **Backend (The Brain):** A robust Java Spring Boot architecture handling infinite vehicle tracking, directional logic, and complex pricing algorithms, hosted 24/7 on **Render**.
* **Database (The Memory):** Secure, serverless data storage powered by **Neon.tech (PostgreSQL)**.
* **Mobile (The Field):** A modern, device-agnostic Flutter application featuring a dynamic street-view layout, real-time timers, and offline-capable plate reading.
* **Admin Web (The HQ):** A Business Intelligence (BI) dashboard for real-time monitoring and financial reporting.

---

## ⚙️ Key Features

* **Dynamic Street-View Parking:** Removed fixed capacity limits (no more static boxes). Vehicles are dynamically assigned to the left or right curb of the street, expanding infinitely as needed.
* **Live Operation Timer:** Real-time on-screen counter for each parked vehicle to track duration and live fee calculations directly on the mobile app.
* **Runaway & Blacklist Management:** Dedicated tracking for vehicles that leave without payment. The system isolates history views to instantly identify runaway (penalty) vehicles.
* **Business Intelligence (BI) Dashboard:** A web-based admin panel featuring real-time occupancy, revenue distribution charts, and one-click Excel data exports.
* **On-Device OCR:** Instant license plate recognition using Google ML Kit, processing data locally to ensure high performance. Complies with GDPR.
* **Location-Based Auth:** Field staff securely log in and exclusively manage their assigned zones/streets based on the real database schema.
* **SOLID Architecture:** Clean, maintainable, and scalable codebases utilizing N-Tier architecture on the backend and State Management on the frontend.

---
![System_Architecture_Diagram.png](System_Architecture_Diagram.png)
## 🛠️ Tech Stack

The architectural design focuses on modularity and scalability.

| Layer | Technology | Description |
| :--- | :--- | :--- |
| **Backend** | ☕ **Java 21 & Spring Boot 3** | Enterprise-grade, high-performance REST API. |
| **Database** | 🐘 **PostgreSQL (Neon.tech)** | Serverless Cloud Database infrastructure. |
| **Cloud/DevOps**| ☁️ **Render & UptimeRobot** | Cloud hosting with automated keep-alive mechanisms. |
| **ORM** | 🍃 **Spring Data JPA** | Hibernate-based data access layer. |
| **Mobile** | 💙 **Flutter (Dart)** | Cross-platform mobile app built with SOLID principles. |
| **Web / BI** | 📊 **HTML/JS, Chart.js, SheetJS** | Admin dashboard with dynamic charts and `.xlsx` export. |
| **Tools** | 🛠️ **Google ML Kit, Maven, Lombok** | On-device image processing and clean code utilities. |

---

## 💰 Pricing Engine (Business Logic)

The system automatically calculates parking fees based on municipal tariffs. The current active algorithm:

| Rule | Description |
| :--- | :--- |
| **Grace Period** | First 5 Minutes: **FREE** |
| **Standard Vehicle** | 1st Hour: **25.00 TL** <br> Subsequent Hours: **+15.00 TL/hr** |
| **Large/Commercial**| 1st Hour: **50.00 TL** <br> Subsequent Hours: **+30.00 TL/hr** |

> *Note: Time is calculated dynamically; any partial hour beyond the first 60 minutes is rounded up to a full hour.*

---

## ⚙️ Installation & Deployment

Since the backend is deployed in the cloud, you can test the mobile app and web dashboard directly without running a local server.

### Option 1: Live Cloud Testing (Mobile & Web)
1. **Mobile:** Navigate to the `mobile` directory, run `flutter pub get`, and execute `flutter run` on your emulator/device.
2. **Web Dashboard:** Open `web/admin-panel.html` in your browser to monitor real-time data.

### Option 2: Full Development Environment (Local)
To modify the core backend business logic:
1. Clone the repository and open the `backend` folder in IntelliJ IDEA.
2. Right-click `pom.xml` -> "Add as Maven Project".
3. Add your Neon.tech PostgreSQL credentials inside `src/main/resources/application.properties`.
4. Run `BackendApplication.java`.
5. Update the `globalBaseUrl` in the Flutter app to your local machine's IP address.

---

## 🗺️ Roadmap

- [x] Phase 1: Monorepo Setup & Spring Boot Initialization
- [x] Phase 2: Database Design (PostgreSQL Neon Migration)
- [x] Phase 3: Repository Layer & Data Seeding
- [x] Phase 4: Service Layer (Business Logic & Pricing Engine)
- [x] Phase 5: Controller Layer (REST API Exposing)
- [x] Phase 6: Mobile App Development (Flutter, SOLID, UI/UX)
- [x] Phase 7: Camera OCR Integration & Dynamic Street-View UX
- [x] Phase 8: Cloud Deployment (Render)
- [x] Phase 9: Admin Web Dashboard (BI Charts & Excel Export)
- [x] Phase 10: Runaway/Blacklist Status Management
- [ ] Phase 11: Self-Service QR Code Payment Portal for Citizens ⚡ **(CURRENTLY HERE)**

---

Developed by Kadir Kacır

Copyright © 2026 Kadir Kacır. All Rights Reserved.