# 📱📊 Subscription Insight System

A **full-stack subscription analytics platform** that combines an **Android app usage collector** with a **Spring Boot backend** to provide:

* 📈 App usage analytics
* 💳 Subscription billing insights
* 🤖 Smart recommendations (KEEP / CONSIDER / CANCEL)
* 📊 Dashboard visualizations

This project helps users understand whether their premium subscriptions (Spotify, Netflix, YouTube Premium, etc.) are actually worth paying for.

---

# 🚀 Project Architecture

```text
Android App
   ↓
UsageStatsManager + AppsUsageMonitorAPI
   ↓
Retrofit API Calls
   ↓
Spring Boot Backend
   ↓
MySQL (Docker Container)
   ↓
Analytics + Recommendations
   ↓
Android Dashboard UI
```

---

# 🧠 Core Idea

The system collects **real mobile app usage data** from Android devices and maps it to user subscriptions.

Using this data, the backend:

1. Tracks monthly app usage
2. Compares usage against subscription cost
3. Generates monthly billing records
4. Produces recommendations
5. Sends analytics to the dashboard

---

# 🛠️ Tech Stack

## 📱 Android Frontend

* **Java**
* **XML layouts**
* **Retrofit** for API calls
* **AppsUsageMonitorAPI** for usage collection
* Android `UsageStatsManager`
* MPAndroidChart (planned dashboard charts)

### Android usage library

Used reference implementation:

```text
https://github.com/TheBotBox/AppsUsageMonitorAPI/tree/master/appusagemonitor/src
```

---

## 🌐 Backend

* **Java 17**
* **Spring Boot**
* Spring Web
* Spring Data JPA
* Spring Security
* JWT Authentication
* Flyway migrations

---

## 🗄️ Database

* **MySQL**
* Docker containerized setup
* Database name: `subscription_db`

---

# 🐳 Database Setup (Docker)

## Start MySQL container

```bash
docker run --name subscription-mysql \
-e MYSQL_ROOT_PASSWORD=root \
-e MYSQL_DATABASE=subscription_db \
-p 3306:3306 \
-d mysql:8
```

## Verify container

```bash
docker ps
```

## Open MySQL shell

```bash
docker exec -it subscription-mysql mysql -u root -p
```

---

# 🗂️ Database Schema

## 👤 users

Stores authenticated users.

Fields:

* id
* username
* email
* passwordhash
* status
* created_at
* last_login_at

---

## 📦 subscriptions

Stores user subscriptions.

Fields:

* id
* user_id
* provider_name
* package_name
* category
* renewal_cycle
* price
* currency
* status

---

## 📊 user_snapshots

Monthly app usage snapshots.

Fields:

* id
* subscription_id
* period
* usage_count
* last_used_at
* source

---

## 💳 billing_records

Stores generated billing data.

Fields:

* id
* subscription_id
* amount
* billing_period
* payment_method
* source

---

## 🤖 recommendations

AI-style recommendation engine output.

Fields:

* id
* user_id
* subscription_id
* type
* reason
* confidence_score

---

# 🔄 System Workflow

```text
User Login
   ↓
Grant Usage Permission
   ↓
Android fetches app usage
   ↓
Send JSON to Spring Boot
   ↓
Match app package → subscription
   ↓
Update user_snapshots
   ↓
Generate billing_records
   ↓
Generate recommendations
   ↓
Dashboard API response
   ↓
Android analytics graphs
```

---

# 📦 Backend Modules

## 🔐 1. Auth Module

Handles:

* Signup
* Login
* JWT token generation
* BCrypt password hashing

Endpoints:

```text
POST /auth/signup
POST /auth/login
```

---

## 📦 2. Subscription Module

Handles:

* Add subscription
* Fetch user subscriptions
* Package name mapping

Endpoints:

```text
GET /api/subscriptions/{userId}
POST /api/subscriptions/{userId}
```

---

## 📊 3. Usage Module

Processes Android usage sync.

Endpoint:

```text
POST /api/usage
```

Responsibilities:

* Receive app usage data
* Match package names
* Update `user_snapshots`

---

## 💳 4. Billing Module

Generates billing data from subscriptions.

Logic:

```text
subscription.price → billing_records
```

---

## 🤖 5. Recommendation Module

Recommendation rules:

```text
usage < 30      → CANCEL
30 <= usage <100 → CONSIDER
usage >=100      → KEEP
```

---

## 📊 6. Dashboard Module

Combines:

* usage analytics
* billing insights
* recommendations

Endpoint:

```text
GET /api/dashboard/{userId}
```

---

# 📱 Android App Flow

## Screen 1 — Login

* User authentication
* JWT token received

## Screen 2 — Home

* Allow Usage Permission
* Sync Usage button
* Dashboard button

## Screen 3 — Dashboard

* App usage charts
* Cost analytics
* KEEP / CANCEL recommendations

---

# 🔌 Android → Backend Payload

```json
{
  "userId": 1,
  "apps": [
    {
      "packageName": "com.spotify.music",
      "appName": "Spotify",
      "usageMinutes": 120
    }
  ]
}
```

---

# 🧱 Project Folder Structure

```text
subscription-backend/
│── src/main/java/com/yourorg
│   ├── Auth
│   ├── Users
│   ├── Subscriptions
│   ├── UserSnapshot
│   ├── Billing
│   ├── Recommendation
│   └── Dashboard
│
│── src/main/resources
│   ├── application.yml
│   └── db/migration
│       ├── V1__init_schema.sql
│       ├── V2__add_package_name.sql
│       └── V3__seed_data.sql
```

---

# ▶️ Run Backend

Start MySQL first. The supplied compose file publishes it on host port 3308 so it
does not collide with a locally-installed MySQL on 3306:

```powershell
cd Backend
docker compose up -d
```

Then start the Spring Boot server. The development default is **8081** (not 8080,
which is commonly occupied by Jenkins on this machine):

```powershell
$env:SEED_FIX_PASSWORDS = "true"  # repairs the deliberately-placeholder seed hashes
.\mvnw.cmd spring-boot:run
```

Backend URL:

```text
http://localhost:8081
```

### First sign-in

Use **Sign Up** to create a normal account. For the development seed data, when
`SEED_FIX_PASSWORDS=true` was supplied at startup, use:

```text
Username: anvith
Password: password123
```

The Android emulator uses `http://10.0.2.2:8081/` by default, which points back
to the host machine. For a physical Android device, add this untracked setting
to `Frontend/MyJavaApplication/local.properties`, replacing the IP with the
laptop's current Wi-Fi IPv4 address:

```properties
backendBaseUrl=http\://<laptop-LAN-IP>\:8081/
```

The phone and laptop must be on the same network, and Windows Firewall must allow
inbound TCP 8081 for the Java process. Do not use plain HTTP for a deployed build;
it is development-only because the JWT would travel unencrypted.

---

# 🔐 Security

* JWT-based authentication
* Spring Security filters
* BCrypt password hashing
* Protected APIs

---

# 🎯 Key Engineering Highlights

* ✅ Modular Monolith Architecture
* ✅ Android + Spring Boot integration
* ✅ Dockerized SQL database
* ✅ Flyway migration versioning
* ✅ Usage-based subscription intelligence
* ✅ Real-world mobile analytics use case

---

# 🚀 Future Enhancements

* Scheduled monthly billing generation
* Push notifications for renewals
* ML-based smarter recommendations
* Web admin dashboard
* Export analytics reports
* Multi-device sync

---

# 👨‍💻 Author

**Anvith Shetty**
Student, PES University
Full Stack Developer | Spring Boot | Android | System Design

---

# ⭐ Project Value

This project demonstrates:

* Backend architecture design
* Android device integration
* API engineering
* database version control
* analytics system design
* real-world subscription intelligence workflows

A strong **resume + interview-ready full-stack systems project**.
