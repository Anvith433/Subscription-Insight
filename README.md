# 📱📊 Subscription Insight

A full-stack subscription analytics platform. An **Android usage collector** measures how
much you actually use the apps behind your paid subscriptions; a **Spring Boot backend**
turns that into a cost-per-hour analysis and tells you, per subscription, whether to
**KEEP**, **CONSIDER**, **DOWNGRADE**, or **CANCEL** it.

The question it answers: *is this ₹199/month actually worth it?*

---

## Table of contents

- [Why this exists](#why-this-exists)
- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Repository layout](#repository-layout)
- [Prerequisites](#prerequisites)
- [Quick start](#quick-start)
- [Configuration reference](#configuration-reference)
- [Database schema](#database-schema)
- [Migrations](#migrations)
- [API reference](#api-reference)
- [Authentication and authorization](#authentication-and-authorization)
- [The recommendation engine](#the-recommendation-engine)
- [Usage sync semantics](#usage-sync-semantics)
- [Android app](#android-app)
- [Testing](#testing)
- [CI pipeline](#ci-pipeline)
- [Troubleshooting](#troubleshooting)
- [Security notes](#security-notes)
- [Known limitations](#known-limitations)
- [Roadmap](#roadmap)
- [Author](#author)
- [Appendix: command reference](#appendix-command-reference)

---

## Why this exists

Subscription spend is invisible by design. Each charge is small, recurring, and detached
from whether you used the thing. Bank statements show the money; they don't show the
usage. This system joins the two.

The core metric is **cost per hour of actual use**. A ₹129/month subscription used for
20 hours costs ₹6.45/hour and is excellent value. The same subscription used for 25
minutes costs ₹310/hour, and the analysis says so with the arithmetic attached.

---

## Architecture

```text
┌─────────────────────────────┐
│  Android app (Java)         │
│  UsageReader                │  minutes in use per app, per day,
│  (UsageStatsManager)        │  read from Android's own usage log
└──────────────┬──────────────┘
               │  Retrofit / JSON over HTTP, JWT in Authorization header
               ▼
┌─────────────────────────────┐
│  Spring Boot backend :8081  │
│  ┌───────────────────────┐  │
│  │ Auth (JWT, BCrypt)    │  │
│  │ Subscriptions (CRUD)  │  │
│  │ Usage ingest          │  │  match package_name → subscription
│  │ Billing               │  │  one record per (subscription, period)
│  │ Recommendation engine │  │  pure function: UsageFacts → Verdict
│  │ Dashboard             │  │  per-row + account-level rollups
│  └───────────────────────┘  │
└──────────────┬──────────────┘
               │  JPA / Hibernate, schema owned by Flyway
               ▼
┌─────────────────────────────┐
│  MySQL 8.0                  │
│  users, subscriptions,      │
│  user_snapshots,            │
│  billing_records,           │
│  recommendations            │
└─────────────────────────────┘
```

The backend is a **modular monolith**: one deployable, packages split by domain
(`Auth`, `Users`, `Subscriptions`, `UserSnapShot`, `BillingRecord`, `Recommendation`,
`common`), with `Config` holding the security and JWT plumbing.

---

## Tech stack

### Backend

| Component | Version / detail |
|---|---|
| Java | Source/target **17** (`<java.version>17</java.version>`); builds and runs fine on 21 |
| Spring Boot | **3.4.3** (`spring-boot-starter-parent`) |
| Modules | `starter-web`, `starter-data-jpa`, `starter-security`, `starter-test`, `devtools` |
| Database | **MySQL 8.0**, driver `com.mysql:mysql-connector-j` |
| Migrations | **Flyway** (`flyway-core` + `flyway-mysql`) |
| JWT | **jjwt 0.11.5** (`api` / `impl` / `jackson`), HS256 |
| API docs | **springdoc-openapi 2.5.0** → Swagger UI |
| Boilerplate | Lombok |
| Build | Maven wrapper (`mvnw` / `mvnw.cmd`) |

### Android

| Component | Version / detail |
|---|---|
| Language | Java |
| Gradle | **9.3.1** (wrapper) |
| AGP | **9.1.0** |
| compileSdk / targetSdk | **34** |
| minSdk | **24** (Android 7.0) |
| Networking | Retrofit + Gson converter + OkHttp logging interceptor |
| UI | AppCompat, Material 1.10.0, ConstraintLayout, RecyclerView adapter |
| Usage data | `UsageStatsManager` directly (`UsageReader` + `ForegroundTimeline`), per calendar day; needs `PACKAGE_USAGE_STATS` + `QUERY_ALL_PACKAGES` |
| Charts | MPAndroidChart 3.1.0 (`HorizontalBarChart` on the dashboard) |
| Type | Fraunces (headlines, figures) and DM Sans (UI), bundled in `res/font`, both SIL OFL 1.1 (licences in `assets/licenses`) |

---

## Repository layout

```text
subscription-Insight-main/
├── Backend/                        # Spring Boot service (Maven)
│   ├── src/main/java/com/yourorg/
│   │   ├── Auth/                   # AuthController + auth DTOs
│   │   ├── Users/                  # User entity, CurrentUserService, seed repair
│   │   ├── Subscriptions/          # Subscription CRUD, enums, DTOs
│   │   ├── UserSnapShot/           # usage ingest + monthly snapshots
│   │   ├── BillingRecord/          # billing generation
│   │   ├── Recommendation/         # engine, thresholds, verdicts
│   │   ├── common/                 # dashboard + shared DataSource enum
│   │   ├── Config/                 # SecurityConfig, JwtService, JWT filter
│   │   └── SubscriptionBackendApplication.java
│   ├── src/main/resources/
│   │   ├── application.yml         # all config, env-overridable
│   │   └── db/migration/           # V1 … V10
│   ├── src/test/java/com/yourorg/  # unit + opt-in integration test
│   ├── docker-compose.yml          # MySQL 8.0 on host port 3308
│   ├── Jenkinsfile
│   └── pom.xml
├── Frontend/MyJavaApplication/     # Android app (Gradle)
│   ├── app/src/main/java/…         # activities, Retrofit client, models
│   ├── app/src/main/res/layout/    # main, subscriptions, add-subscription, dashboard
│   ├── app/build.gradle            # injects BACKEND_BASE_URL at build time
│   └── local.properties            # untracked: sdk.dir, optional backendBaseUrl
└── README.md
```

---

## Prerequisites

- **JDK 17 or newer** (`JAVA_HOME` must be set)
- **MySQL 8.0** — either via Docker Compose (below) or a local install
- **Android SDK** with platform 34, for the app
- No global Maven or Gradle install needed; both wrappers are committed

---

## Quick start

### 1. Start the database

**Option A — Docker Compose (recommended).** Publishes MySQL on host port **3308** to
avoid colliding with a local MySQL on 3306:

```bash
cd Backend && docker compose up -d
```

Override the host port if 3308 is also taken:

```bash
MYSQL_HOST_PORT=3309 docker compose up -d
```

**Option B — an existing local MySQL on 3306.** Create the database and the user the
app defaults to, then point `DB_URL` at 3306:

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS subscription_db CHARACTER SET utf8mb4; CREATE USER IF NOT EXISTS 'postgres'@'localhost' IDENTIFIED BY 'postgres'; GRANT ALL PRIVILEGES ON subscription_db.* TO 'postgres'@'localhost'; FLUSH PRIVILEGES;"
```

> The MySQL user is called `postgres` for historical reasons. It is a MySQL user. The
> name is kept because it matches the application's default credentials.

### 2. Run the backend

Flyway applies V1→V10 on startup; Hibernate then validates every entity against the
resulting schema (`ddl-auto: validate`).

```bash
cd Backend && ./mvnw spring-boot:run
```

PowerShell, with the seed passwords repaired so the seeded accounts can log in:

```powershell
cd Backend
$env:SEED_FIX_PASSWORDS = "true"
.\mvnw.cmd spring-boot:run
```

Against a local MySQL on 3306 instead of Compose:

```bash
DB_URL="jdbc:mysql://localhost:3306/subscription_db?serverTimezone=Asia/Kolkata&useSSL=false&allowPublicKeyRetrieval=true" ./mvnw spring-boot:run
```

The service listens on **http://localhost:8081**. Swagger UI:
**http://localhost:8081/swagger-ui/index.html**

### 3. Build and run the Android app

```bash
cd Frontend/MyJavaApplication && ./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. On an emulator the app
reaches the host at `http://10.0.2.2:8081/` with no configuration.

For a **physical device**, add this to the untracked
`Frontend/MyJavaApplication/local.properties`, using your laptop's current Wi-Fi IPv4:

```properties
backendBaseUrl=http\://192.168.1.42\:8081/
```

Phone and laptop must share a network, and Windows Firewall must allow inbound TCP 8081
for the Java process.

### 4. Sign in

Create an account with **Sign Up**. To use the seeded data, start the backend with
`SEED_FIX_PASSWORDS=true` and log in as:

```text
Username: anvith          (or anvith@gmail.com)
Password: password123
```

Seeded users are `anvith` and `rahul`. Login accepts **either** a username or an email.

### 5. Add a subscription before syncing

A fresh account has no subscriptions, and the usage sync has nothing to match against
until it does — it will tell you so rather than upload anything. Work down the four
steps on the home screen in order:

1. **Allow usage access** — sends you to the system settings page for it. Android has no
   API to grant this on the user's behalf. The step shows a tick once it is on.
2. **Add your subscriptions** → **Add subscription** — pick the app the subscription belongs
   to from the installed-app list. See
   [Adding a subscription](#adding-a-subscription-why-the-package-name-is-picked-not-typed)
   for why it is a picker and not a text field.
3. **Sync your usage** — reads the last ten days or so from the phone and uploads them,
   for the apps you have subscriptions for only. Android keeps about ten days, so sync at
   least once a week to cover a whole month.
4. **See what's worth keeping** — the summary card and the per-subscription verdicts.

Steps 2 and 3 are order-dependent in one direction only: adding a subscription after a
sync is fine, the next sync picks it up.

---

## Configuration reference

Everything is environment-overridable — no tracked file needs editing to change hosts
or credentials.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3308/subscription_db?serverTimezone=Asia/Kolkata&useSSL=false&allowPublicKeyRetrieval=true` | JDBC URL. Port must match the **host** port MySQL is published on. |
| `DB_USERNAME` | `postgres` | Database user (also used by Flyway). |
| `DB_PASSWORD` | `postgres` | Database password. |
| `SERVER_PORT` | `8081` | HTTP port. Not 8080, which Jenkins commonly occupies. |
| `JWT_SECRET` | dev-only placeholder | HS256 signing key. **Must** be ≥32 bytes. Set this outside local dev. |
| `JWT_EXPIRATION` | `36000000` | Token lifetime in ms (10 hours). |
| `SEED_FIX_PASSWORDS` | `false` | Re-hashes the V2 seed placeholder passwords. Never run against real data. |
| `SEED_PASSWORD` | `password123` | Password written by the repair above. |
| `MYSQL_HOST_PORT` | `3308` | Compose-only: host port for the MySQL container. |
| `INTEGRATION_DB` | *(unset)* | Set to any non-empty value to enable the DB-backed test. |
| `BACKEND_BASE_URL` | `http://10.0.2.2:8081/` | Android: backend base URL. Also settable as `backendBaseUrl` in `local.properties` or as a Gradle property. |

`allowPublicKeyRetrieval=true` matters: MySQL 8 defaults to `caching_sha2_password`, and
the connector needs it to complete the handshake over a non-TLS local connection.

---

## Database schema

Six tables. Below is the **final** state after V10 — not the V1 shape.

### `users`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK AI | |
| `username` | VARCHAR(255) | **UNIQUE** |
| `email` | VARCHAR(255) | **UNIQUE**, indexed. The canonical identity; the JWT subject. |
| `passwordhash` | VARCHAR(255) | BCrypt |
| `status` | VARCHAR(50) | `UserStatus`, uppercase |
| `created_at` | TIMESTAMP | |
| `last_login_at` | TIMESTAMP NULL | Written on every successful login |

### `subscriptions`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK AI | |
| `user_id` | BIGINT | FK → `users(id)` **ON DELETE CASCADE**, indexed |
| `provider_name` | VARCHAR(255) | e.g. `Netflix` |
| `package_name` | VARCHAR(255) NOT NULL | Android package id — the usage matcher's key. **UNIQUE per user.** |
| `category` | VARCHAR(50) | CHECK-constrained enum |
| `start_date` | DATE | Used to detect a partial first month |
| `renewal_cycle` | VARCHAR(20) | `FREE` / `MONTHLY` / `YEARLY` |
| `renewal_date` | DATE | |
| `price` | DECIMAL(10,2) | |
| `currency` | VARCHAR(3) | e.g. `INR` |
| `status` | VARCHAR(20) | `ACTIVE` / `CANCELLED` / `PAUSED` |
| `created_at` / `updated_at` | TIMESTAMP | |

### `user_snapshots`

Monthly usage, one row per subscription per month.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK AI | |
| `subscription_id` | BIGINT | FK → `subscriptions(id)` |
| `period` | VARCHAR(7) | `YYYY-MM` |
| `usage_count` | INTEGER | **Minutes** in use — on screen or playing |
| `observed_window` | VARCHAR(10) NULL | `DAY` / `WEEK` / `MONTH` — which span the scan covered (V8) |
| `observed_days` | INT NULL | Days of `period` the scan covered (V8) |
| `month_days_elapsed` | INT NULL | Days elapsed in `period` when the scan was taken (V8) |
| `last_used_at` | TIMESTAMP NULL | |
| `source` | VARCHAR(50) | `ANDROID` / `SYSTEM` |
| `created_at` | TIMESTAMP | |
| | | **UNIQUE (`subscription_id`, `period`)** — makes repeated syncs idempotent |
| | | CHECK `ck_user_snapshots_observed_window`: the three V8 columns are all NULL or all present, and `observed_days ≤ month_days_elapsed` |

The three V8 columns describe **the same observation** as `usage_count` and only move with
it. All NULL means "written before V8, assume a complete month" — see
[Sync windows](#sync-windows-older-clients-only).

For the current app the row is **rebuilt from `usage_days`** on every sync: `usage_count` is
the sum of the month's recorded days, `observed_days` how many days have a row, and
`month_days_elapsed` the day of the month (a finished month's full length).

### `usage_days`

Minutes per subscription per calendar day (V9) — what months are built from.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK AI | |
| `subscription_id` | BIGINT | FK → `subscriptions(id)` |
| `usage_date` | DATE | The phone's calendar day |
| `minutes` | INT | 0–1440, CHECK `ck_usage_days_minutes`. **Zero is a measurement**: scanned, not used |
| `updated_at` | TIMESTAMP | |
| | | **UNIQUE (`subscription_id`, `usage_date`)** |

A day with no row was never scanned. That difference is what lets a month with gaps read
as partial instead of as a month of low usage.

### `billing_records`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK AI | |
| `subscription_id` | BIGINT | FK → `subscriptions(id)` |
| `amount` | DECIMAL(10,2) NOT NULL | |
| `currency` | VARCHAR(3) | |
| `billing_period` | VARCHAR(7) | `YYYY-MM` |
| `paid_at` | TIMESTAMP | |
| `payment_method` | VARCHAR(50) | `AUTO_SYNC` — inferred by the server from the subscription's price |
| `source` | VARCHAR(50) | |
| `created_at` | TIMESTAMP | |
| | | **UNIQUE (`subscription_id`, `billing_period`)** — one bill per period |

### `recommendations`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK AI | |
| `user_id` | BIGINT | FK → `users(id)` |
| `subscription_id` | BIGINT | FK → `subscriptions(id)` |
| `period` | VARCHAR(7) NULL | Month assessed |
| `type` | VARCHAR(50) | CHECK: `KEEP` / `CANCEL` / `CONSIDER` / `DOWNGRADE` / `UPGRADE` |
| `reason` | VARCHAR(500) | Human-readable explanation (was 100 — too small) |
| `usage_minutes` | INT NULL | |
| `monthly_cost` | DECIMAL(10,2) NULL | |
| `cost_per_hour` | DECIMAL(12,2) NULL | |
| `value_score` | DECIMAL(5,2) NULL | 0–100 |
| `estimated_monthly_savings` | DECIMAL(10,2) NULL | |
| `confidence_score` | DECIMAL(5,2) | 0–1 |
| `status` | VARCHAR(50) | |
| `generated_at` | TIMESTAMP | |
| | | **UNIQUE (`user_id`, `subscription_id`)** — the engine upserts |

The metric columns exist so a verdict is **explainable from the stored row alone** —
no recomputation, no guessing which inputs produced it.

### Enum values

| Enum | Values |
|---|---|
| `Category` | `OTT`, `CLOUD`, `VIDEO`, `MUSIC`, `UTILITIES`, `EDUCATION`, `FINANCE`, `SOCIAL`, `SHOPPING`, `UTILITY`, `SYSTEM`, `DEVELOPMENT` |
| `Renewal_Cycle` | `FREE`, `MONTHLY`, `YEARLY` |
| `SubscriptionStatus` | `ACTIVE`, `CANCELLED`, `PAUSED` |
| `Type` (recommendation) | `KEEP`, `CANCEL`, `CONSIDER`, `DOWNGRADE`, `UPGRADE` |
| `DataSource` | `ANDROID`, `SYSTEM` |

All are stored as **uppercase strings** (`@Enumerated(EnumType.STRING)`) and enforced by
CHECK constraints added in V7.

---

## Migrations

Flyway owns the schema. `baseline-on-migrate: true`, `repair-on-migrate: false`.

| Version | What it does |
|---|---|
| **V1** | Creates all five tables, FKs, `idx_users_email`, and the `(subscription_id, period)` unique key. |
| **V2** | Seeds 2 users, 3 subscriptions, and sample snapshots / bills / recommendations. The snapshots and bills were invented; V10 removes them. |
| **V3** | Adds `subscriptions.package_name` (nullable) + `uk_user_package`; backfills Spotify / YouTube / Netflix package ids. |
| **V4** | `billing_records.amount` → `DECIMAL(10,2) NOT NULL`. |
| **V5** | Widens `source` columns to `VARCHAR(50)` so enum names fit. |
| **V6** | Repairs V1/V2 defects: uppercases categories, backfills and enforces `package_name NOT NULL`, adds `idx_subscriptions_user`, adds the billing period unique key, widens `reason` to 500, adds the six metric columns, adds the `(user_id, subscription_id)` unique key. |
| **V7** | Re-applies the enum-case fix **unconditionally** and adds CHECK constraints on every enum column. |
| **V8** | Adds `user_snapshots.observed_window` / `observed_days` / `month_days_elapsed` (all nullable) + a CHECK constraint, so a partial scan is no longer stored as a whole month. No backfill: NULL means "unknown, assume complete", which preserves how existing rows were written. |
| **V9** | Creates `usage_days` — minutes per subscription per day — because Android keeps only ten days of usage log, so months have to be assembled from days across syncs. |
| **V10** | Deletes V2's invented usage (180 / 90 / 30 min labelled `ANDROID`), its made-up `UPI` / `CARD` payments, and the seeded verdicts quoting them. Each `DELETE` matches the seed rows exactly, so real rows are never caught. The demo users and subscriptions stay. |

### Why V7 exists — a worked example of a silent migration failure

V6 tried to normalise casing with:

```sql
UPDATE subscriptions SET category = UPPER(category) WHERE category <> UPPER(category);
```

MySQL's default collation `utf8mb4_0900_ai_ci` is **case-insensitive**, so
`'Music' <> 'MUSIC'` evaluates to `FALSE`. Zero rows were updated — and the migration
reported success.

The consequence wasn't cosmetic. `Category` is mapped `@Enumerated(EnumType.STRING)`, so
reading a row with `category = 'Video'` calls `Enum.valueOf(Category.class, "Video")` and
throws `No enum constant …Category.Video`. **Every dashboard request for a seeded user
returned HTTP 500.**

V7 drops the `WHERE` clause entirely (running `UPPER()` on an already-uppercase value is a
harmless no-op, so it is safe to re-run) and adds CHECK constraints that compare
`CAST(col AS BINARY)` — without the cast, the case-insensitive collation would accept
`'Music'` and the constraint would not do its job.

**V1–V6 are deliberately left untouched.** They have already been applied to existing
databases; editing them would change their recorded checksums and fail validation. Every
correction is a forward migration. This is also why `repair-on-migrate` is off — it
rewrites checksums to match edited files, hiding exactly this class of problem.

---

## API reference

Base URL `http://localhost:8081`. All endpoints except signup, login, and the docs
require `Authorization: Bearer <token>`.

### Public

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/signup` | Register |
| `POST` | `/api/login` | Obtain a JWT |
| `GET` | `/swagger-ui/**`, `/v3/api-docs/**` | API docs |

### Authenticated

The **App** column says whether the Android client reaches the endpoint; see
[Endpoint coverage](#endpoint-coverage) for where. Everything the product needs is
reachable — the three gaps are endpoints no screen has a use for.

| Method | Path | Purpose | App |
|---|---|---|---|
| `GET` | `/users/me` | Caller's profile — no id needed | — |
| `GET` | `/users/{id}` | Caller's own id only; other ids → **403** | — |
| `GET` | `/api/subscriptions?activeOnly=false` | List the caller's subscriptions | ✅ |
| `GET` | `/api/subscriptions/{id}` | One subscription, if owned | — |
| `POST` | `/api/subscriptions` | Create → **201** | ✅ |
| `PUT` | `/api/subscriptions/{id}` | Update | ✅ status only |
| `DELETE` | `/api/subscriptions/{id}` | **Cancels** — see below | ✅ |
| `PUT` | `/usage` | Submit measured app usage | ✅ |
| `GET` | `/api/dashboard/{userId}?period=YYYY-MM` | Per-subscription rows | ✅ |
| `GET` | `/api/dashboard/{userId}/summary?period=YYYY-MM` | Account-level totals | ✅ |

> `DELETE /api/subscriptions/{id}` sets status to `CANCELLED` rather than deleting the
> row. The usage and billing history behind a subscription is the user's own record of
> what they spent — destroying it to represent "I cancelled this" loses information.

> Subscription endpoints take **no user id**. The account always comes from the token.

### `POST /api/signup`

```json
{ "username": "anvith", "email": "anvith@gmail.com", "password": "secret123" }
```

`200` → `"User registered successfully!"`. `400` if any field is blank, the username is
taken, **or the email is already registered** — the email is `UNIQUE`, and checking only
the username let a duplicate reach the database and surface as a 500.

### `POST /api/login`

```json
{ "username": "anvith", "password": "password123" }
```

`username` accepts a username **or** an email.

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9…",
  "username": "anvith",
  "userId": 1,
  "email": "anvith@gmail.com"
}
```

| Status | Meaning |
|---|---|
| `200` | Authenticated; `last_login_at` updated |
| `401` | Wrong credentials — one message for both "no such user" and "wrong password", so responses can't be used to enumerate accounts |
| `503` | The **auth backend** failed (e.g. MySQL unreachable) — deliberately distinct from 401, so a database outage isn't reported as a bad password |

### `POST /api/subscriptions`

```json
{
  "providerName": "Netflix",
  "packageName": "com.netflix.mediaclient",
  "category": "VIDEO",
  "startDate": "2026-01-15",
  "renewalCycle": "MONTHLY",
  "renewalDate": "2026-09-15",
  "price": 199.00,
  "currency": "INR",
  "status": "ACTIVE"
}
```

Enums arrive as **strings**, matched case-insensitively, so a bad value yields a clear
`400` instead of a Jackson deserialisation failure.

Three fields are required: `providerName`, `packageName` and `category`. There is no
default for the category — the column is `NOT NULL` and the enum has no neutral member, so
guessing one would mislabel the subscription in every category breakdown it appears in.
The rest default: `price` to `0`, `currency` to `INR`, `startDate` to today,
`renewalCycle` to `MONTHLY`, `status` to `ACTIVE`, and `renewalDate` to the next cycle
boundary after the start date. The Android form relies on those last two defaults rather
than sending its own guesses.

Responses other than `201`:

| Code | When |
|---|---|
| `400` | Missing provider, package or category; an unknown category, cycle or status; a negative price; or a currency that is not exactly three characters |
| `409` | The caller already has a subscription for that package (compared case-insensitively) — one row per package per user, because two would double-count the same usage |

`PUT /api/subscriptions/{id}` applies only the fields present in the body; a `null` leaves
the stored column alone. That is what lets the app reactivate a cancelled subscription
with `{"status": "ACTIVE"}` and nothing else.

### `PUT /usage`

```json
{
  "apps": [
    {
      "packageName": "com.spotify.music", "appName": "Spotify", "usageMinutes": 245,
      "days": [ { "date": "2026-10-09", "minutes": 120 }, { "date": "2026-10-10", "minutes": 125 } ]
    },
    {
      "packageName": "com.netflix.mediaclient", "appName": "Netflix", "usageMinutes": 0,
      "days": [ { "date": "2026-10-09", "minutes": 0 }, { "date": "2026-10-10", "minutes": 0 } ]
    }
  ]
}
```

Response:

```json
{
  "period": "2026-10",
  "synced": ["Spotify", "Netflix"],
  "skipped": ["com.whatsapp"],
  "rejected": []
}
```

`days` is the measurement: minutes for every calendar day the phone could fully account
for, **zeros included** — a day the app sat unused is a fact, not a gap. Each day is stored
in `usage_days` and every month the days fall in is rebuilt from them; see
[Usage sync semantics](#usage-sync-semantics). `usageMinutes` is only their total.

An app's days are **all rejected** if any one of them is impossible: a date more than a
day after the server's today, minutes outside 0–1440, or a malformed or repeated date. (One
day ahead is a phone in a later time zone; that day is accepted but only stored once the
server reaches it.) It is listed under `rejected` and
nothing of it is stored.

Older clients send a single `usageMinutes` and no `days`, with `period` naming the span they
scanned — `TODAY`, `WEEK` or `MONTH`, case-insensitive; absent or unrecognised reads as
`MONTH`. That path still works (see [Sync windows](#sync-windows-older-clients-only)) and is
now checked too: a negative total, or one larger than the window's days × 1440, is rejected
rather than stored. Negatives used to be clamped to zero, which turned a broken report into
"not used at all". `period` on the **response** is the current month, `YYYY-MM`.

The `userId` is never read from the body — it comes from the token. It used to be, which
meant anyone could post usage into any account by changing a number. (The field still
exists on `UsageRequest` for wire compatibility with older clients; the controller ignores
it.) `synced` / `skipped` / `rejected` exist because the old handler answered
`"Usage synced successfully"` even when it matched nothing.

### `GET /api/dashboard/{userId}`

One row per subscription:

```json
[
  {
    "providerName": "Spotify",
    "packageName": "com.spotify.music",
    "price": 119.00,
    "currency": "INR",
    "monthlyCost": 119.00,
    "usageMinutes": 1240,
    "billingPeriod": "2026-08",
    "hasUsageData": true,
    "recommendationType": "KEEP",
    "reason": "Used 1240 min (20.7 h) in 2026-08 at ₹5.76 per hour on a ₹119.00/month plan - good value (score 97/100).",
    "confidenceScore": 0.85,
    "valueScore": 97.12,
    "costPerHour": 5.76,
    "estimatedMonthlySavings": 0.00
  }
]
```

`period` accepts `YYYY-MM`. Omitted or unrecognised values — including the legacy
`TODAY` / `WEEK` / `MONTH` — fall back to the most recent month with data. Every row
states the period it describes, so the client never has to assume.

`hasUsageData: false` means **no snapshot exists** — "unknown", not "zero". The
distinction matters: zero usage is a cancel signal, missing data is not.

### `GET /api/dashboard/{userId}/summary`

```json
{
  "period": "2026-08",
  "currency": "INR",
  "subscriptionCount": 3,
  "activeSubscriptionCount": 3,
  "totalMonthlySpend": 447.00,
  "totalYearlySpend": 5364.00,
  "totalUsageMinutes": 1295,
  "unusedCount": 1,
  "noDataCount": 0,
  "potentialMonthlySavings": 199.00,
  "potentialYearlySavings": 2388.00,
  "wastedSpendPercent": 44.52,
  "recommendationCounts": { "KEEP": 2, "CANCEL": 1 },
  "bestValueProvider": "Spotify"
}
```

`potentialMonthlySavings` counts **only CANCEL verdicts**, where the saving is exactly
the price and therefore certain. DOWNGRADE appears as a count, not a rupee figure —
the real saving depends on the provider's cheaper tier, and this system has no plan
catalogue to look one up. Inventing that number would be a guess dressed up as analysis.

---

## Authentication and authorization

- **Stateless.** `SessionCreationPolicy.STATELESS`; CSRF, HTTP Basic, and form login all
  disabled. The only credential is the bearer token.
- **JWT HS256**, signed with `jwt.secret` (≥32 bytes required by HS256).
- **The token subject is the user's email** — always. `CustomUserDetailsService` builds
  `UserDetails` with the email, so the subject and the principal always agree.
- **Unauthenticated requests get 401**, not 403, via `HttpStatusEntryPoint`. For a token
  API, 401 is the honest answer: the caller has no credentials rather than insufficient
  ones.
- **Ownership is enforced server-side.** `CurrentUserService.requireOwnership(id)`
  compares the path id against the authenticated principal and returns **403** for a
  mismatch — deliberately not 404, which would confirm which ids exist.
- **Passwords are BCrypt-hashed**; `/users/**` returns `UserResponseDTO`, never the
  `User` entity, so the hash cannot leak.

### Fixed vulnerabilities

These were real and are worth stating plainly, since the code still carries the comments:

| Issue | Fix |
|---|---|
| `/api/dashboard/**` was `permitAll()` — anyone could read any user's spending by trying `/api/dashboard/1` | Authenticated + ownership-checked |
| Every endpoint trusted the user id in the URL | Id comes from the token; path ids are verified |
| `/users/**` returned the `User` entity including `passwordhash` | Returns `UserResponseDTO` |
| Lookup by email and username let one user enumerate all accounts | Removed; `/users/{id}` answers only for the caller |
| `POST /usage` took `userId` from the body — usage could be written into any account | Taken from the token |
| A 500 response body carried a full Java stack trace (devtools defaults `include-stacktrace` to `always`) | `include-stacktrace: never` |
| Retrofit logged at `Level.BODY` — the plaintext login password and the JWT went to Logcat in every build | `Level.BASIC` + `redactHeader("Authorization")` |
| `jwt.expiration` was configuration that did nothing — the lifetime was hardcoded to 10h | Property is now read |
| Token subject was whatever the user typed, so username logins produced tokens that 401'd on every request | Subject is the canonical email |

---

## The recommendation engine

`RecommendationEngine` is a **pure function** from `UsageFacts` to `Verdict` — no Spring
wiring, no database access. That is what makes it unit-testable without a database, and
why 23 tests cover it directly.

### The model

**Cost per hour** = `monthlyPrice / (usageMinutes / 60)`

That becomes a **0–100 value score** falling linearly to zero as cost per hour approaches
`maxAcceptableCostPerHour`.

> **Calibration caveat.** `maxAcceptableCostPerHour` is an amount of money, so it is
> currency-specific. The defaults are calibrated for **INR**: at ₹200/hour, a ₹199 video
> subscription needs roughly one hour of viewing a month to stop looking wasteful. **A
> deployment in another currency must override this or the scores are meaningless.**

### Thresholds (`EngineThresholds.defaults()`)

| Threshold | Default | Meaning |
|---|---|---|
| `maxAcceptableCostPerHour` | `200` | Cost/hour at which the score hits 0 |
| `minMonthlyHoursForValue` | `1.0` | Below this, a paid subscription counts as unused regardless of price |
| `keepScore` | `70` | Score ≥ this → KEEP |
| `considerScore` | `40` | Score ≥ this → CONSIDER |

### Decision table

| Condition | Verdict | Confidence | Reasoning |
|---|---|---|---|
| No snapshot for the month | `CONSIDER` | 0.20 | We genuinely don't know. Reporting this as CANCEL would be a fabricated conclusion. |
| Price is zero (free plan) | `KEEP` | 0.90 | Nothing to justify |
| Started during the month | `CONSIDER` | 0.35 | Partial month — don't act yet |
| **Partial scan**, score ≥ 70 | `KEEP` | 0.85 | The lower bound already justifies the price; unscanned days can only add usage |
| **Partial scan**, anything else | `CONSIDER` | 0.20 + 0.45 × coverage | A short scan proves usage was *at least* X. It can confirm value but never condemn. Includes **zero minutes** |
| Zero minutes used | `CANCEL` | 0.95 | Saving equals the full price |
| Under 1 hour used | `CANCEL` | 0.85 | Paying for something effectively unused |
| Score ≥ 70 | `KEEP` | 0.85 | Good value |
| Score ≥ 40 | `CONSIDER` | 0.65 | Paying for itself, but only just |
| Score < 40, priced **above** portfolio median | `DOWNGRADE` | 0.70 | A cheaper tier would likely cover this much use |
| Score < 40, priced at/below median | `CONSIDER` | 0.60 | Already one of your cheaper subscriptions — there may be no lower tier |

Cases are evaluated **in this order**, and the ordering carries meaning. The partial-scan
rule sits ahead of both `CANCEL` branches because *"I opened nothing today"* is not *"I
opened nothing this month"* — and behind the started-mid-month rule, because "started on
the 20th" is the more specific explanation when both apply.

A partial scan's confidence floors at the 0.20 given to no data at all (a one-day scan is
barely more informative) and tops out below the 0.65 a full month earns, so a provisional
verdict can never outrank a measured one. Nothing is extrapolated to fill the unscanned
days, and no saving is claimed — neither would be a measurement.

Month-over-month **trend never overrides the verdict**. It appears in the explanation and
nudges confidence up or down depending on whether it agrees.

Every number quoted in `reason` is one the decision actually used, so "why is it
recommending this?" is answerable from the stored row alone.

### Deliberately not implemented

`Type.UPGRADE` is never returned, and `DOWNGRADE` never claims a specific saving. Both
would need a catalogue of each provider's plans and prices, which this system does not
have.

### A precision detail worth keeping

`costPerHour` is derived from **minutes**, not from the rounded `hours` value:

```java
price.multiply(SIXTY).divide(BigDecimal.valueOf(minutes), 2, RoundingMode.HALF_UP)
```

Dividing by a 4-decimal approximation of ⅓ h turned an exact `1947.00` into `1947.19`.
`hours` is kept for display and for the minimum-hours threshold only.

---

## Usage sync semantics

1. The phone reads Android's usage log and reports, per subscribed app that is installed,
   the minutes it was in use on each calendar day it can fully account for — about the
   last ten. See [How usage is measured](#how-usage-is-measured).
2. The backend matches `packageName` against the caller's `subscriptions.package_name`,
   falling back to `providerName` vs `appName` only if no package name was sent.
3. Each day is upserted into `usage_days`, keyed by `(subscription_id, usage_date)`. A day
   seen before keeps the larger figure: a finished day reads the same on every scan, today
   only grows, and a scan that has lost part of a day to pruning cannot erase it.
4. For every month the days fall in, the `user_snapshots` row is **rebuilt from the days**:
   the sum of their minutes, how many of the month's days have a row, and how many days
   the month has had. A month with gaps therefore reads as partial.
5. Billing records are generated, one per `(subscription_id, billing_period)`.
6. Recommendations are regenerated and upserted, one per `(user_id, subscription_id)`.
7. The response lists what was `synced`, `skipped` and `rejected`.

Unmatched apps are **skipped, not an error**. An earlier version threw on the first
unmatched app, which failed the whole batch because one entry in the list happened not to
be a subscription.

### Why months are built from days

Android deletes its detailed usage log after **ten days**: `UsageStatsDatabase.prune()`
drops `INTERVAL_DAILY` files older than `addDays(-10)` (AOSP `android14-release`), and
`queryEvents()` reads exactly those files. No query, however it is phrased, can see further
back, so one scan cannot describe a month. Each sync reports the days it can see, the
server keeps them, and a month fills in across syncs.

**Syncing at least once a week leaves no gaps.** A gap is never guessed at: it makes the
month partial, and the engine does not return `CANCEL` on a partial month — it says
`CONSIDER` and tells the user to sync weekly.

Month-over-month trends compare **daily rates**, and only against a previous month that
was seen in full: ten days of this month against ten days' worth of last month, not all
thirty (which read as a steep fall every month), and no trend at all against a month
with gaps (which once produced "up 925%" against a single recorded day).

### Single totals from older clients: why they merge with `max()`

An older client sends one total for a window instead of days. For those,
`UserSnapShotService` stores `max(existing, reported)`. Each of the alternatives is wrong:

- **`+=`** would double-count. The device reports the *total* for the window, not the delta
  since the last sync, so pressing Sync twice in a minute would double the month's minutes.
- **`=`** would let a "Today" scan overwrite the month's total with a much smaller number.

`max()` is correct for both directions: a month-to-date scan raises the total as the month
progresses, and a shorter scan can never shrink it. This holds because the windows nest —
`TODAY ⊆ WEEK ⊆ MONTH`.

One extra clause: a new observation also wins when it reports **the same** minutes over
**more** of the month. Without it, a user who scans "Today" and then "This Month" and gets
the same figure back — common, because the day they scanned was the only day they used the
app — would keep the one-day coverage forever, and the verdict would stay provisional no
matter how many full-month scans followed.

The minutes and the coverage columns always move **together**. Splitting them would
reintroduce the same class of bug from the other side: a full-month figure labelled as one
day is as misleading as one day's figure labelled as a full month.

---

## Android app

### Screens

Four activities. Login, signup, and the post-login controls all live in `MainActivity`,
which switches between them by toggling view visibility rather than navigating.

| Activity | Layout | Purpose |
|---|---|---|
| `MainActivity` | `activity_main.xml` | Login **and** signup (same form, `isSignupMode` reveals the extra fields), then the post-login home: a four-step checklist (usage access / subscriptions / sync / dashboard) |
| `SubscriptionsActivity` | `activity_subscriptions.xml` | The user's subscriptions via `SubscriptionAdapter` / `item_subscription.xml`, with cancel and reactivate on each card and an extended FAB to add one |
| `AddSubscriptionActivity` | `activity_add_subscription.xml` | The add form, with the app picker in `dialog_app_picker.xml` |
| `DashboardActivity` | `activity_dashboard.xml` | Account summary card, a `HorizontalBarChart` (MPAndroidChart) with bars coloured by verdict, then a `RecyclerView` of per-subscription verdicts via `RecommendationAdapter` / `item_recommendation.xml` |

The post-login steps are numbered in the order they have to happen:

```
1. Allow usage access
2. Add your subscriptions
3. Sync your usage
4. See what's worth keeping
```

Step 2 used to be missing, and its absence made the rest unreachable. There was no screen
that called `POST /api/subscriptions`, so a phone-only user tapped **Sync**, was told to
"add a subscription first", and had nowhere to do it — the usage matcher, the
cost-per-hour figures and the whole dashboard sat behind a door with no handle.
Subscriptions could only be created with curl, Swagger UI or an `INSERT`.

### Look and feel

Warm paper background, ink-black buttons, one highlighter-yellow accent for the savings
figure, and muted verdict colours (sage, ochre, rust, brick). Everything is set up so
layouts only describe what is particular to them:

| File | What it holds |
|---|---|
| `res/values/colors.xml` / `res/values-night/colors.xml` | The whole palette, with the same names in both, so dark mode needs no code |
| `res/values/themes.xml` | The app theme; it points every `TextInputLayout`, `MaterialButton`, `MaterialCardView`, toolbar and FAB at its style |
| `res/values/styles.xml` | Text styles (`Text.Insight.*`) and those widget styles (`Widget.Insight.*`) |
| `Formats.java` | Display formatting: `₹1,046`, `1h 12m`, `October 2026` |
| `AppIcons.java` | A subscription's real app icon, or a lettered circle if the app is not installed |

Text styles are applied with `style=`, not `android:textAppearance`: the theme sets the
app-wide font, and a theme-level font outranks a text appearance.

### Adding a subscription: why the package name is picked, not typed

`subscriptions.package_name` is the key the entire usage matcher turns on. If it does not
exactly match what the device reports, nothing errors — the subscription simply never
matches any usage and sits at zero minutes forever, which the engine reads as a `CANCEL`
candidate. A free-text field would put a typo one keystroke away from a wrong verdict.

So the App field is not typeable. It opens a picker over the device's launchable apps:

```java
Intent launcher = new Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER);
for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) { … }   // deduped by package
```

The stored package is therefore the same string Android's usage log will later report,
by construction rather than by validation. This is the Gap 2 fix one layer earlier: the
sync stopped guessing which apps to report by deriving the list from the user's
subscriptions, and the add form stops the subscription being created with a package the
sync can never match.

Three details worth knowing:

- **Launcher activities, not every installed package.** A subscription belongs to
  something the user can open; the full package list is mostly system components.
  `QUERY_ALL_PACKAGES` is declared, so this still sees third-party apps under the
  Android 11 package-visibility rules.
- **Searchable by name or id.** The list is an `ArrayAdapter<AppEntry>` whose
  `toString()` is `label — package`. `ArrayAdapter`'s built-in filter matches on
  `toString()`, so one `TextWatcher` searches both, and `getItem(position)` still
  resolves correctly against the filtered list.
- **The provider name is auto-filled from the app label and stays editable**, because
  "YouTube" the app is often "YouTube Premium" the subscription. A user's own edit is
  never overwritten by reopening the picker.

The form sends `providerName`, `packageName`, `category`, `renewalCycle`, `startDate`,
`price` and `currency`. It deliberately does **not** send `renewalDate` or `status`: the
server derives the next renewal from the start date and cycle, and defaults the status to
`ACTIVE`. Sending our own guesses would only create a chance to disagree with it.

Client-side validation is limited to two things — that an app was picked, and that the
price parses as a number. Everything else the server either defaults or rejects with a
specific message (a bad category is a `400` naming the field; a repeated package is a
`409`), and mirroring its rules here would create a second set to keep in step.

> **No `java.time` in the Android module.** `minSdk` is 24 and `app/build.gradle` does not
> set `coreLibraryDesugaringEnabled`, so a `LocalDate` reference compiles cleanly and then
> throws `NoClassDefFoundError` on API 24–25 — a crash that never appears on a modern
> emulator. Dates are `String` in ISO `yyyy-MM-dd`, built with `Calendar` and
> `DatePickerDialog`; Spring Boot's default Jackson configuration reads and writes
> `LocalDate` in exactly that format, so the wire contract is unchanged. Prices are
> `BigDecimal`, which is available at every API level, and Gson writes it as an unquoted
> number preserving scale — `199.00` stays `199.00` rather than arriving as `199.0`.

### Cancelling, and getting back

`DELETE /api/subscriptions/{id}` is a **soft** cancel: the server sets the status to
`CANCELLED` and keeps the snapshots and billing records, because those are the evidence
behind the verdict. The confirmation dialog says so rather than implying deletion.

Cancelled subscriptions stay in the list, greyed out with a `CANCELLED` badge, and their
overflow menu offers **Reactivate**. That reuses `PUT /api/subscriptions/{id}` with a
body carrying nothing but `{"status": "ACTIVE"}` — the server's `apply()` only overwrites
a column when its field is present, so the stored price, category and dates are untouched.
Without it a mis-tap would strand a subscription with no route back from the app, since
this client has no per-field edit form.

### How usage is measured

`UsageReader` asks `UsageStatsManager` directly. The app used to go through
`com.github.TheBotBox:AppsUsageMonitorAPI:1.1`, whose "this month" scan called
`queryEvents(INTERVAL_MONTHLY, now)`. `INTERVAL_MONTHLY` is the constant `2`, so the scan
started in 1970 and returned whatever the phone still held — about ten days late in a
month, and last month's days early in it — which the server then filed as the whole
month. It also counted screen time only.

A sync:

1. finds where the phone's log begins — the oldest daily bucket
   `queryUsageStats(INTERVAL_DAILY, …)` still returns — and starts at the first **whole**
   local day after that, or at the 1st of last month if the log reaches that far. The
   oldest day is usually cut off partway, so it is left out rather than reported short;
2. replays `queryEvents(firstDay, now)` through `ForegroundTimeline`, which turns the
   events into time in use per app per day;
3. sends every covered day for each subscription whose app is installed, **zeros
   included**. An app that is not installed is not sent: the phone knows nothing about it.

It runs on a background thread; walking ten days of events is slow enough to stall the
screen.

`ForegroundTimeline` counts an app as **in use** while either of these holds:

| | Starts | Ends |
|---|---|---|
| An activity is on screen | `ACTIVITY_RESUMED` | `ACTIVITY_STOPPED` on Android 10+, so picture-in-picture and split screen still count; `ACTIVITY_PAUSED` before that |
| It runs a foreground service | `FOREGROUND_SERVICE_START` | `FOREGROUND_SERVICE_STOP` |

The second row is what makes music real: Spotify plays through a foreground service, so
with the screen off it used to read as barely used. The two are combined as a **union**,
so playing while watching is not counted twice. Screen-off ends on-screen time (playback
carries on); a shutdown ends everything; a startup with no recorded shutdown — a flat
battery — ends everything at the last moment the phone was known to be on. Spans are cut
at local midnight. Service events only exist from Android 10, so on older phones only
screen time is counted, and the dashboard caption says which applies.

Minutes are rounded per day, and any use at all is at least one minute: zero is a
`CANCEL` signal, so rounding forty seconds down would invent one.

`ForegroundTimeline` is plain Java with no Android types, tested on the JVM by
`app/src/test/.../ForegroundTimelineTest`.

### Sync windows (older clients only)

A report that carries one total instead of days names the window it scanned — `TODAY`,
`WEEK` (Monday-anchored) or `MONTH` (1st-anchored). `UsageWindow` turns that into
`observed_days` / `month_days_elapsed`, clamped to the current month, so a short scan is
read as *at least* that much rather than as the month's total, and the engine will not
return `CANCEL` on it. The current app sends days and no window. A `MONTH` total from the
old library could not be trusted in either direction, for the reason above.

### What gets reported: the subscription-derived filter

The client filters before sending, but the filter is built from the user's own data. Each
sync calls `GET /api/subscriptions` first and collects two lowercase sets — every
`packageName` and every `providerName` — then starts the scan:

```java
for (Map.Entry<String, String> tracked : trackedPackages.entrySet()) {   // 1: by package
    String label = installedLabel(pm, pkg);
    if (label != null) toReport.put(pkg, label);                          // installed: report it
}
for (String pkg : report.usedPackages()) {                               // 2: by label
    String label = installedLabel(pm, pkg);
    if (label != null && trackedProviderNames.contains(label.trim().toLowerCase(Locale.ROOT))) {
        toReport.put(pkg, label);
    }
}
```

The two tiers mirror the server's own matching in `resolveSubscription`: package name
first, because it is a stable OS identifier, then the display label for subscriptions whose
`package_name` is unset.

The fetch has to finish before the scan starts, because the scan's result is filtered
against those sets. Kicking off both at once would race.
If the fetch fails the sync is abandoned with *"Couldn't load your subscriptions; sync
cancelled."* It deliberately does **not** fall back to reporting every installed app: that
would send the user's entire app-usage profile over the wire — in cleartext, on this build
— to work around a matching problem. An empty subscription list stops just as early, with
*"No subscriptions yet. Add one in step 2, then sync."*

This replaced a hardcoded nine-name allow-list, which dropped every other app *before the
request was sent*, so a subscription for anything outside those nine could never sync
however correctly the server matched it. Deriving the filter from real subscriptions is
both more capable and more private — only packages the user has told the app about ever
leave the device.

The device's real label is now sent unmodified. The old code did `appLabel = tracked` on a
substring hit, and because `"YouTube"` preceded `"YouTube Premium"` in the list,
`com.google.android.youtube` was always relabelled plain `"YouTube"` — not the provider
name the fallback lookup needs. Leaving the label alone keeps the two match tiers
independent.

### Retrofit interface

```java
@POST("api/login")   Call<AuthResponse> loginUser(@Body LoginRequest r);
@POST("api/signup")  Call<ResponseBody> signupUser(@Body SignupRequest r);
@PUT("usage")        Call<UsageSyncResponse> syncUsage(@Header("Authorization") String t, @Body UsageRequest r);

@GET("api/subscriptions")     Call<List<SubscriptionResponse>> getSubscriptions(@Header("Authorization") String t);
@POST("api/subscriptions")    Call<SubscriptionResponse> createSubscription(@Header("Authorization") String t, @Body SubscriptionRequestBody b);
@PUT("api/subscriptions/{id}")    Call<SubscriptionResponse> updateSubscription(@Header("Authorization") String t, @Path("id") Long id, @Body SubscriptionRequestBody b);
@DELETE("api/subscriptions/{id}") Call<SubscriptionResponse> cancelSubscription(@Header("Authorization") String t, @Path("id") Long id);

@GET("api/dashboard/{userId}")         Call<List<DashBoardResponseDTO>> getDashboard(@Header("Authorization") String t, @Path("userId") Long id);
@GET("api/dashboard/{userId}/summary") Call<DashboardSummaryResponse> getDashboardSummary(@Header("Authorization") String t, @Path("userId") Long id);
```

`getSubscriptions` is token-scoped, so no user id travels in the path. It is called
**without** `activeOnly`, whose server-side default is `false`, for two reasons:
`resolveSubscription` does not filter on status, so a paused or recently cancelled
subscription still accepts usage and `activeOnly=true` would silently drop usage the
server would have stored; and the subscriptions screen needs cancelled rows visible so
they can be reactivated.

`SubscriptionResponse` declares eleven of `SubscriptionResponseDTO`'s thirteen fields.
It used to declare three — enough for the sync, which only needed to know which packages
to report — but the subscriptions screen displays price, category, cycle, dates and
status, so those are mirrored now. `createdAt` and `updatedAt` are still omitted: nothing
shows them, and declaring them would mean parsing `Instant` without core library
desugaring. Gson ignores undeclared JSON fields, so a partial model stays safe.

`updateSubscription` is used for exactly one thing — reactivating a cancelled
subscription with `SubscriptionRequestBody.statusOnly("ACTIVE")`. The server's `apply()`
skips null fields, so a body carrying only a status leaves every other column alone. That
factory exists to make the partial body deliberate rather than an accident of which
setters happened to be called.

`syncUsage` returns `UsageSyncResponse` rather than `Void` so a sync that matched nothing
is distinguishable from one that stored data. The client acts on that: a `200` with
`syncedCount == 0` is surfaced as *"Nothing synced: none of these apps match a
subscription"*, not as success.

`loggedInUserId` starts as `null` and is only set from the login response. It previously
defaulted to `1L`, which meant every install silently operated on user 1's subscriptions —
a wrong-but-plausible id is worse than none, because nothing ever fails visibly. A login
response without an id is now reported as an error rather than papered over.

### Endpoint coverage

Every endpoint the product needs is now reachable from the app. The three that are not
called have no screen that would use them: the app already holds the list from
`getSubscriptions`, and the token carries the identity that `/users/me` would return.

| Endpoint | Called by the app | Where |
|---|---|---|
| `POST /api/signup` | ✅ | `MainActivity` |
| `POST /api/login` | ✅ | `MainActivity` |
| `PUT /usage` | ✅ | `MainActivity` |
| `GET /api/subscriptions` | ✅ | `MainActivity` (before each sync), `SubscriptionsActivity` |
| `POST /api/subscriptions` | ✅ | `AddSubscriptionActivity` |
| `PUT /api/subscriptions/{id}` | ✅ | `SubscriptionsActivity` (reactivate only) |
| `DELETE /api/subscriptions/{id}` | ✅ | `SubscriptionsActivity` |
| `GET /api/dashboard/{userId}` | ✅ | `DashboardActivity` |
| `GET /api/dashboard/{userId}/summary` | ✅ | `DashboardActivity` |
| `GET /api/subscriptions/{id}` | ❌ | nothing needs a single row |
| `GET /users/me`, `GET /users/{id}` | ❌ | the token already carries the identity |

### Base URL resolution

`app/build.gradle` injects `BuildConfig.BACKEND_BASE_URL`, resolved in this order:

1. Gradle property `-PbackendBaseUrl=…`
2. `backendBaseUrl` in `local.properties` *(untracked — the right place for a device IP)*
3. `BACKEND_BASE_URL` environment variable
4. Default `http://10.0.2.2:8081/` — the emulator's route to the host

A trailing `/` is appended if missing, because Retrofit requires one.

### Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Talk to the backend |
| `PACKAGE_USAGE_STATS` | Read the usage log. **Special-access** — see below |
| `QUERY_ALL_PACKAGES` | Android 11+ package visibility. Without it, `getApplicationLabel()` cannot resolve other apps' names, so Spotify/Netflix are invisible to the label lookup |

`PACKAGE_USAGE_STATS` cannot be granted by a runtime dialog. The user must enable it in
**Settings → Apps → Special app access → Usage access**. The app checks the real grant
state before every sync — not a stored flag:

```java
AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                                 android.os.Process.myUid(), getPackageName());
return mode == AppOpsManager.MODE_ALLOWED;
```

If it is not allowed, a dialog deep-links to `Settings.ACTION_USAGE_ACCESS_SETTINGS`.

---

## Testing

```bash
cd Backend && ./mvnw test
```

| Test | Count | Needs a DB? |
|---|---|---|
| `RecommendationEngineTest` | 23 | No — always runs |
| `DailyUsageTest` | 14 | No — always runs |
| `UsageWindowTest` | 8 | No — always runs |
| `SubscriptionBackendApplicationTests` | 1 | **Yes** — skipped unless `INTEGRATION_DB` is set |

46 tests, 45 of which need nothing but a JVM. On the app side, `ForegroundTimelineTest`
(12 tests, `./gradlew :app:testDebugUnitTest`) covers how usage events become minutes. Both unit suites test pure functions —
`RecommendationEngine` maps `UsageFacts → Verdict`, `UsageWindow` maps a window plus a date
to a day count — so neither needs Spring or MySQL, and both run in the Jenkins pipeline
as-is.

`UsageWindowTest` uses fixed dates rather than `LocalDate.now()`, deliberately: a test
anchored to today would pass or fail depending on the day of the week it ran. Its fixtures
are March 2026 (starts on a Sunday) and April 2026 (starts on a Wednesday), which between
them cover both month-boundary alignments, and one test sweeps every day of both months
asserting the invariant `1 ≤ observedDays ≤ monthDaysElapsed`.

`RecommendationEngineTest`'s most important case is `partialScanWithZeroMinutesDoesNotCancel`
— zero minutes on a one-day scan must reach `CONSIDER`, never `CANCEL`. Its counterpart
`absentWindowIsTreatedAsComplete` pins the backward-compatibility rule: null coverage on a
pre-V8 row still cancels, so the change is additive rather than a behaviour flip.

The integration test is opt-in via
`@EnabledIfEnvironmentVariable(named = "INTEGRATION_DB", matches = ".+")`. It is worth
running because it opens a real DataSource, applies Flyway V1→V10, and has Hibernate
`validate` every entity against the actual schema — catching entity/column mismatches no
unit test can. But it cannot pass without MySQL, and `mvnw test` is what the Jenkins
pipeline runs, so leaving it unconditional meant **the pipeline failed for a missing
database rather than a code fault**.

To run it:

```bash
cd Backend && docker compose up -d && INTEGRATION_DB=1 ./mvnw test
```

Against a local MySQL on 3306:

```bash
cd Backend && INTEGRATION_DB=1 DB_URL="jdbc:mysql://localhost:3306/subscription_db?serverTimezone=Asia/Kolkata&useSSL=false&allowPublicKeyRetrieval=true" ./mvnw test
```

Android unit and instrumented test stubs live in `app/src/test` and `app/src/androidTest`.

---

## CI pipeline

`Backend/Jenkinsfile` — Windows agent, JDK 17, local Maven repo at `.m2/repository`:

```text
Verify Java → Clean → Compile → Unit Tests → Package (-DskipTests) → Archive Artifact
```

`archiveArtifacts` fingerprints `target\*.jar`; the workspace is cleaned in `post.always`.
Because the DB-backed test self-skips, the pipeline is green without a database.

---

## Troubleshooting

### `PKIX path building failed` / `certificate_unknown` on Gradle or Maven downloads

TLS interception by antivirus. Check who issued the certificate:

```bash
powershell -NoProfile -Command "$c=New-Object Net.Sockets.TcpClient('dl.google.com',443);$s=New-Object Net.Security.SslStream($c.GetStream(),$false,({$true}-as[Net.Security.RemoteCertificateValidationCallback]));$s.AuthenticateAsClient('dl.google.com');$s.RemoteCertificate.Issuer"
```

If the issuer is something like `CN=Avast Web/Mail Shield Root`, the proxy's root CA is in
the **Windows** certificate store but not in the JDK's private `cacerts` — so browsers
work while every Java download fails. Point Java at the Windows store:

```bash
./gradlew -Dorg.gradle.jvmargs="-Djavax.net.ssl.trustStoreType=Windows-ROOT" :app:assembleDebug
```

Make it permanent in `~/.gradle/gradle.properties` (machine-specific, untracked, and it
takes precedence over the project's own):

```properties
org.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8 -Djavax.net.ssl.trustStoreType=Windows-ROOT
```

Do **not** reach for `--offline` as a workaround — the dependency cache is usually
incomplete, and offline mode fails with a different, misleading
`No cached version available for offline mode`.

### Gradle dies with `mmap failed to map … bytes / G1 virtual space`

Out of memory, not a code fault. `gradle.properties` requests `-Xmx2048m`; on a machine
with ~8 GB total and little free, that daemon cannot start. Use 1 GB:

```bash
./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m -Dfile.encoding=UTF-8" :app:assembleDebug
```

Any `hs_err_pid*.log` in the tree is residue from this and can be deleted.

### `docker compose up` fails to bind port 3308

Something else owns it. Republish:

```bash
MYSQL_HOST_PORT=3309 docker compose up -d
```

Then set `DB_URL` to the **same** host port. Inside the container MySQL always listens on
3306; only the published host port changes.

### Backend won't start: port 8081 in use

```bash
SERVER_PORT=8082 ./mvnw spring-boot:run
```

Remember to update the Android `backendBaseUrl` to match.

### `Access denied for user 'postgres'@'localhost'`

The app's default user doesn't exist on your MySQL. Create it — see
[Quick start, Option B](#1-start-the-database).

### Login returns 401 for a seeded user

V2 inserts the literal string `hashed_password`, which BCrypt cannot verify — no seeded
user can log in. Start with `SEED_FIX_PASSWORDS=true` once to re-hash them to
`password123`. **Never enable this against real data.**

### `No enum constant …Category.Video` or dashboard 500s

Mixed-case enum values in the database. V7 fixes this; confirm it applied:

```bash
mysql -u root -p subscription_db -e "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

### Sign-in returns 503

The auth backend failed — typically MySQL is unreachable. This is deliberately distinct
from 401 so a database outage doesn't send you resetting passwords that were never the
problem.

### Android emulator can't reach the backend

`10.0.2.2` is the emulator's alias for the host loopback — `localhost` inside the emulator
is the emulator itself. On a physical device you must use the laptop's LAN IP, share a
network, and open inbound TCP 8081 in the firewall.

---

## Security notes

Development defaults that **must** change before any real deployment:

- **`JWT_SECRET`.** A signing key committed to source control is not a secret: anyone with
  the repo can mint a valid token for any account. The default exists only so the app
  starts locally.
- **HTTP.** The Android client uses plain HTTP. A JWT crossing an untrusted network in the
  clear is a captured session. Use HTTPS.
- **`SEED_FIX_PASSWORDS`.** Rewrites password hashes to a known value. Must never run
  against real data.
- **Database credentials.** `postgres`/`postgres` are local-development values.
- **`include-message: always`** is retained because the messages this code raises are
  deliberately non-revealing — "Subscription not found" for both an absent record and
  someone else's. Stack traces are off.

---

## Known limitations

- **No plan catalogue**, so `UPGRADE` is never produced and `DOWNGRADE` cannot quantify a
  saving.
- **Thresholds are INR-calibrated**; another currency needs `EngineThresholds` overridden.
- **`usage_count` is minutes** despite the column name — a V1 naming choice kept to avoid
  a rename migration for no functional gain.
- **Billing is generated, not reconciled.** Records derive from subscription price, not
  from real payment data, and are marked `AUTO_SYNC` / `SYSTEM` to say so.
- **One recommendation per (user, subscription)** — current state only, no history.
- **No refresh tokens.** A 10-hour JWT expires and the user logs in again.
- **Only this phone is measured.** Watching Netflix on a TV or laptop is invisible to it,
  so a subscription used mainly elsewhere can look unused. The dashboard says so.
- **Background playback needs Android 10.** Below that, only on-screen time is counted.
- **A month is only complete if it is synced at least weekly.** Android keeps ten days of
  usage; days that age out unsynced leave a gap, and the month stays partial (`CONSIDER`,
  never `CANCEL`).
- **Days are the phone's calendar days.** A day the server has not reached yet (the phone is
  a time zone ahead) is accepted but not counted until it has; more than a day ahead is
  rejected. Running both in the same time zone avoids the lag.
- **An always-on service is invisible until it restarts.** Playback is counted from a
  foreground service's start and stop events; one that ran unbroken for longer than the
  ten-day log (an always-connected VPN) has neither in range.
- **Figures from before this change are not recomputed.** A month already stored from the
  old library's single total is replaced once day-based syncs reach it; until then it
  stands. Rows written before V8 have no window recorded and are read as complete.
- **The app can change a subscription's status, not its fields.** Price, category, cycle
  and dates are set at creation and afterwards only `PUT /api/subscriptions/{id}` can
  change them — which the app calls with a status-only body. Correcting a price means the
  API directly, or cancelling and re-adding. Per-field editing is on the roadmap.
- **The client does not mirror the server's validation.** Only "an app was picked" and
  "the price parses" are checked on the device; a bad category or a two-letter currency
  comes back as a `400` from the server. Deliberate — two copies of the same rules drift —
  but it means some mistakes cost a round trip to discover.
- **The app picker lists launcher activities only.** A subscription for something with no
  launchable icon on the device — a website, a device-bound plan, an app installed on a
  different phone — cannot be added from the app at all. `packageName` is required by the
  server (`requireText` in `SubscriptionService.create`) and the form can only supply one
  the picker offers, so these have to go in through the API. That is the narrowest gap
  left in the product, and the reason manual entry is on the roadmap.

---

## Roadmap

- Per-field subscription editing in the app (price, category, cycle, dates — the app can
  currently only change status)
- A manual-entry path in the add form for subscriptions with no installed app
- Scheduled monthly billing generation
- Renewal notifications
- Richer dashboard charts (trend over time; a bar chart and a summary card already ship)
- Recommendation history and trend graphs
- Provider plan catalogue → real `DOWNGRADE` savings and `UPGRADE` support
- Refresh tokens
- Multi-currency threshold calibration
- Web admin dashboard, export to CSV/PDF

---

## Author

**Anvith Shetty** — Student, PES University
Full-stack development · Spring Boot · Android · System design

---

## Appendix: command reference

```bash
# Database
cd Backend && docker compose up -d
cd Backend && docker compose down          # add -v to drop the volume

# Backend
cd Backend && ./mvnw spring-boot:run
cd Backend && ./mvnw test
cd Backend && ./mvnw package               # → target/subscription-backend-0.0.1-SNAPSHOT.jar
java -jar target/subscription-backend-0.0.1-SNAPSHOT.jar

# Integration test
cd Backend && INTEGRATION_DB=1 ./mvnw test

# Android
cd Frontend/MyJavaApplication && ./gradlew :app:assembleDebug
cd Frontend/MyJavaApplication && ./gradlew clean
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Migration status
mysql -u postgres -p subscription_db -e "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```
