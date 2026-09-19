# Interview prep: Subscription Insight System

Everything here is traceable to code in this repo. Where a number or a claim appears, the
file that proves it is named, so you can open it mid-interview if pressed.

**Ground rule for the whole interview: never claim the app has been run end to end on a
device.** It compiles, the backend suite passes, and no device walkthrough has happened
yet. Saying "built and unit-tested; the device walkthrough is my next step" is a stronger
answer than a bluff that unravels on one follow-up question.

## How to use this document

| § | Section | Read it for |
|---|---|---|
| 1 | The 60-second pitch | Memorise this one. It is the answer to "tell me about your project" |
| 2 | Architecture | The shape, and why a monolith |
| 3 | The five stories | The bulk of what you will actually be asked about |
| 4 | Android specifics | Anything client-side |
| 5 | Security | Present as fixes, never as confessions |
| 6 | Questions and answers | Rehearse out loud |
| 7 | Traps | Read last, the night before |
| 8 | Whiteboard version | If they hand you a marker |
| **9** | **Complete request walkthrough** | **"Walk me through what happens when…"** — the deepest technical section |
| **10** | **Data model, table by table** | Schema questions, and why each column exists |
| **11** | **Keyword bank** | The vocabulary to use, each anchored to real code |
| **12** | **How to explain any of it** | A four-move formula that works on every concept here |

If you only have an hour: §1, §3, §11, §12.


---

## 1. The 60-second pitch

> Subscription Insight answers one question: *which of my subscriptions am I paying for and
> not using?* An Android app measures per-app foreground time with Android's
> `UsageStatsManager`, uploads it for the apps the user actually has subscriptions for, and
> a Spring Boot backend turns price plus measured usage into a cost-per-hour and a verdict —
> KEEP, CONSIDER, DOWNGRADE or CANCEL — with the numbers behind it stored alongside.
>
> Two things I'd point at as the interesting engineering. First, the recommendation engine
> is a pure function from a facts object to a verdict, no Spring and no database, which is
> why 23 unit tests cover it without any infrastructure. Second, the system is careful
> about the difference between *"I measured zero usage"* and *"I didn't measure"* — an
> unmeasured subscription can never be recommended for cancellation, because that would be
> a fabricated conclusion presented as a measurement.

Scale, if asked: ~5,700 lines of Java across 53 backend and 19 Android source files, 8
Flyway migrations, 12 REST endpoints, 32 tests.

---

## 2. Architecture, in the order you should explain it

```
Android app  ──Retrofit/JSON over HTTP, JWT──▶  Spring Boot :8081  ──JPA──▶  MySQL 8.0
UsageStatsManager                               modular monolith             schema owned
per-package                                     Auth / Subscriptions /       by Flyway,
foreground time                                 Usage / Billing /            ddl-auto=validate
                                                Recommendation / Dashboard
```

**Modular monolith, packages by domain** (`Auth`, `Users`, `Subscriptions`, `UserSnapShot`,
`BillingRecord`, `Recommendation`, `common`, `Config`). Say *why*: one deployable, one
transaction boundary, no network hop between usage ingest and recommendation, but the
package split means the seams are already drawn if it ever needed splitting. Microservices
here would add distributed-transaction problems to a single-writer workload.

**Layering:** Controller → Service → Repository, DTOs at the boundary, entities never
serialised out. That last point has a security story attached — see §5.

---

## 3. The five stories to have ready

Interviewers remember stories, not feature lists. Each of these is a real defect or a real
decision in this codebase, with the file to prove it.

### Story 1 — The migration that reported success and changed nothing

**The strongest technical story in the project. Lead with this one.**

V6 tried to normalise enum casing:

```sql
UPDATE subscriptions SET category = UPPER(category) WHERE category <> UPPER(category);
```

MySQL's default collation `utf8mb4_0900_ai_ci` is case-insensitive, so `'Music' <> 'MUSIC'`
is **FALSE**. Zero rows updated, migration green.

The consequence was not cosmetic. `Category` is `@Enumerated(EnumType.STRING)`, so reading
`category = 'Video'` calls `Enum.valueOf(Category.class, "Video")` → `No enum constant`.
**Every dashboard request for a seeded user returned HTTP 500.**

V7 fixes it by dropping the `WHERE` entirely (running `UPPER()` on already-uppercase text is
a harmless no-op, so it is safe to re-run) and adds CHECK constraints comparing
`CAST(col AS BINARY)` — without the cast the case-insensitive collation would accept
`'Music'` and the constraint would not do its job.

Three follow-ups you will get:

- *"Why not just edit V6?"* Its checksum is already recorded in `flyway_schema_history` on
  databases that applied it. Editing it fails validation on exactly the databases that need
  the fix. Every correction is a forward migration.
- *"Why is `repair-on-migrate` off?"* It rewrites checksums to match edited files, which
  hides precisely this class of problem.
- *"What's the general lesson?"* A migration that runs without error is not a migration
  that did something. Assert the post-condition — that is what the CHECK constraint is for.

File: `Backend/src/main/resources/db/migration/V7__normalize_enum_case_and_guard.sql`

### Story 2 — Absence of evidence is not evidence of absence

The bug: a user syncs "Today" on the 3rd of the month. Netflix shows 0 minutes today. The
engine concluded zero monthly usage and returned **CANCEL** — advising someone to cancel a
subscription on the strength of one unmeasured day.

The fix is a schema change plus an ordering rule. V8 adds `observed_window`, `observed_days`
and `month_days_elapsed` to `user_snapshots`, so a row records *how much of the month it
saw*. The engine then treats a partial scan as a **lower bound**:

| Partial scan | Verdict | Why |
|---|---|---|
| minutes > 0 **and** score ≥ 70 | `KEEP` | The lower bound already justifies the price — unscanned days can only add usage |
| anything else, **including zero minutes** | `CONSIDER` | A short scan proves usage was *at least* X. It can confirm value; it can never condemn |

The `minutes > 0` guard is load-bearing, and worth quoting exactly
(`RecommendationEngine.java:163`): a zero-minute partial scan must not reach KEEP either.
Zero measured over one day says nothing in *either* direction, so it waits.

Confidence is `0.20 + 0.45 × coverage`: floored at the 0.20 given to no data at all, capped
below the 0.65 a full month earns, so **a provisional verdict can never outrank a measured
one.** Nothing is extrapolated to fill unscanned days, and no saving is claimed — neither
would be a measurement.

The asymmetry is the point, and it is worth saying out loud: a false KEEP costs the user one
month of a subscription they might not need. A false CANCEL destroys trust in the product
permanently. So the system is built to be wrong in only one direction.

Two details that show rigour:

- **Ordering carries meaning.** The partial-scan rule sits *ahead* of both CANCEL branches,
  and *behind* the started-mid-month rule — "started on the 20th" is the more specific
  explanation when both apply.
- **Backward compatibility is additive, not a flip.** NULL coverage on a pre-V8 row means
  "unknown, assume complete", preserving how those rows were written. Two tests pin both
  sides: `partialScanWithZeroMinutesDoesNotCancel` and `absentWindowIsTreatedAsComplete`.

Files: `V8__record_usage_scan_window.sql`, `Recommendation/RecommendationEngine.java`,
`Recommendation/RecommendationEngineTest.java`

### Story 3 — `max()`, not `+=` and not `=`

`UserSnapShotService` merges usage with `max(existing, reported)`. Both alternatives are
wrong, and being able to say why fast is a good signal:

- **`+=` double-counts.** The device reports the window *total*, not a delta since the last
  sync. Pressing Sync twice in a minute would double the month.
- **`=` lets a "Today" scan clobber the month's total** with a smaller number.

`max()` is correct in both directions because the windows nest: `TODAY ⊆ WEEK ⊆ MONTH`.

One extra clause matters: a new observation also wins when it reports **the same** minutes
over **more** of the month. Without it, a user who scans Today and then This Month and gets
the same figure (common — the day they scanned was the only day they used the app) keeps
one-day coverage forever, and the verdict stays provisional no matter how many full-month
scans follow. Minutes and coverage always move **together**: a full-month figure labelled as
one day is as misleading as one day's figure labelled as a full month.

### Story 4 — A client bug that was also a privacy leak

The Android sync only uploads apps the user has subscriptions for. When the subscription
fetch failed, an early version fell back to uploading **every installed app**.

That is two bugs in one. Functionally it papers over a failure; but it also means a network
blip turns a subscription tracker into a device-wide app-usage profile leaving the phone.
The fix is that the failure path reports the failure and uploads nothing, with a code
comment saying why, so the next person to "improve" the error handling doesn't reintroduce
it. The comment is the fix's durability.

Related, same theme: the Retrofit logging interceptor was at `Level.BODY`, which wrote the
plaintext login password and the JWT into Logcat in **every build type**. Now `Level.BASIC`
with `redactHeader("Authorization")`.

File: `MainActivity.java`, `loadSubscriptionsThenScan`'s `onFailure`

### Story 5 — Picked, never typed

`subscriptions.package_name` is the key the entire usage matcher turns on
(`UserSnapShotService.resolveSubscription`). A typo there does not error — it silently never
matches. So the add form reads launchable apps from `PackageManager` and the user picks:

```java
Intent launcher = new Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER);
for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) { ... }
```

The stored string is by construction the same one the scanner will report. Two neat details:
the list is loaded on a background thread (`loadLabel` on every package is slow enough to
jank or ANR), and it is backed by an `ArrayAdapter<AppEntry>` whose `toString()` is
`"label — package"`, because `ArrayAdapter`'s built-in filter matches on `toString()` — so
one `TextWatcher` searches both the visible name and the package id, and `getItem(position)`
still returns the right entry after filtering.

**The design principle to name:** make the invalid state unrepresentable rather than
validating against it. A free-text package field would need validation that cannot actually
work — you cannot tell a typo from a legitimately-not-installed app.

---

## 4. Android specifics they will probe

### Why there is no `java.time` anywhere in the app module

`minSdk 24`, and `app/build.gradle` does **not** set `coreLibraryDesugaringEnabled`. So
`LocalDate` compiles fine and throws `NoClassDefFoundError` on API 24–25 — a crash that
never appears on a modern emulator. Dates are `String` in ISO `yyyy-MM-dd` on the wire
(Spring Boot's default Jackson serialises `LocalDate` exactly that way, and there is no
`spring.jackson` override), and the form uses `Calendar` + `DatePickerDialog`.

`BigDecimal` for price, because `java.math` is available at every API level and Gson writes
it as an unquoted number preserving scale — `199.00` stays `199.00` instead of arriving as
`199.0`.

If asked what you'd change: enable desugaring. The right fix is to make the modern API
available, not to route around it forever.

### The permission that has no runtime dialog

`PACKAGE_USAGE_STATS` is **special access** — it cannot be granted by
`requestPermissions()`. The user must enable it in Settings → Apps → Special app access →
Usage access. So the app checks the real grant state before every sync rather than trusting
a stored flag:

```java
AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                                 android.os.Process.myUid(), getPackageName());
return mode == AppOpsManager.MODE_ALLOWED;
```

A stored flag goes stale the moment the user revokes access in Settings. Also:
`QUERY_ALL_PACKAGES` is required on Android 11+ or `getApplicationLabel()` cannot resolve
other apps' names at all.

### Gson has no `@SerializedName` here

Plain `GsonConverterFactory.create()`, no custom adapters. So Java field names must match
JSON keys exactly, and a mismatch is **silently null, not an error**. Worth naming as a
known fragility with a concrete mitigation: `@SerializedName` on every DTO field would make
the contract explicit and survive obfuscation.

Gson also ignores undeclared JSON fields, which is why `SubscriptionResponse` can declare
11 of the DTO's 13 fields safely.

### `SubscriptionRequestBody.statusOnly("ACTIVE")`

Reactivating a cancelled subscription reuses `PUT /api/subscriptions/{id}` with a body
carrying only a status. It works because the server's `apply()` skips null fields, so every
other column is left alone. The static factory exists to make the partial body *deliberate*
rather than an accident of which setters happened to be called.

Why it matters as product thinking: `DELETE` is a **soft** cancel — it sets
`status = CANCELLED` and keeps snapshots and billing rows, because those are the evidence
behind the verdict. But with no way back, a mis-tap would strand the subscription. One
Retrofit method turns a trap into a reversible action.

---

## 5. Security — answer with the fix, not the vulnerability

These were real and are fixed. Presenting them as *"here is what I found and what I did"* is
much stronger than pretending the first draft was perfect.

| Issue | Fix |
|---|---|
| `/api/dashboard/**` was `permitAll()` — anyone could read any user's spending via `/api/dashboard/1` | Authenticated + ownership-checked |
| Every endpoint trusted the user id in the URL | Id comes from the token; path ids are verified against it |
| `/users/**` returned the `User` entity **including `passwordhash`** | Returns `UserResponseDTO` |
| Lookup by email and username let one user enumerate all accounts | Removed; `/users/{id}` answers only for the caller |
| `POST /usage` took `userId` from the **body** — usage could be written into any account | Taken from the token |
| 500 responses carried a full Java stack trace (devtools defaults `include-stacktrace` to `always`) | `include-stacktrace: never` |
| Retrofit logged at `Level.BODY` — plaintext password and JWT in Logcat, every build | `Level.BASIC` + `redactHeader("Authorization")` |
| `jwt.expiration` was configuration that did nothing — lifetime hardcoded to 10h | Property is now read |
| Token subject was whatever the user typed, so username logins minted tokens that 401'd on every request | Subject is the canonical email |

**The single sentence that ties them together:** every one of these is the same root cause —
trusting client-supplied identity. The id in the URL, the id in the body, the username in
the token subject. Identity comes from the token, and the path is only ever *verified*
against it.

Note `include-message: always` is deliberately **kept**: the messages this code raises are
non-revealing by design — "Subscription not found" for both an absent record and someone
else's. That is an authorization decision expressed as an error message.

Still development-only, and say so unprompted: `JWT_SECRET` has a committed default (a
signing key in source control is not a secret — anyone with the repo can mint a token for
any account), the Android client uses plain HTTP, and `SEED_FIX_PASSWORDS` must never run
against real data.

---

## 6. Questions you will get, and the answers

**"Why is the recommendation engine a pure function?"**
No Spring, no repository, no clock. It maps `UsageFacts → Verdict`. That makes 23 tests
runnable with nothing but a JVM, which means they run in CI without a database — and it
means every decision is reproducible from its inputs. Testability was the goal; the
reproducibility was a bonus I'd now call the bigger one, because "why did it recommend
this?" is answerable from the stored row alone.

**"How do you test something time-dependent without flakiness?"**
`UsageWindowTest` uses fixed dates, never `LocalDate.now()` — a test anchored to today
passes or fails depending on the day of the week it runs. Fixtures are March 2026 (starts
Sunday) and April 2026 (starts Wednesday), covering both month-boundary alignments, and one
test sweeps every day of both months asserting `1 ≤ observedDays ≤ monthDaysElapsed`.

**"Why is one test skipped?"**
`SubscriptionBackendApplicationTests` is gated on
`@EnabledIfEnvironmentVariable(named = "INTEGRATION_DB", matches = ".+")`. It opens a real
DataSource, applies Flyway V1→V8, and has Hibernate `validate` every entity against the
actual schema — it catches entity/column mismatches no unit test can. But `mvnw test` is
what the CI pipeline runs, and unconditional meant **the pipeline failed for a missing
database rather than a code fault**. The signal was noise, so it became opt-in.

**"`ddl-auto: validate` — why not `update`?"**
`update` lets Hibernate silently mutate production schema, in an order nobody reviewed, with
no rollback and no record. Flyway owns the schema; `validate` makes Hibernate a *checker*
rather than an author. If an entity and a column disagree, the app refuses to start — which
is the correct time to find out.

**"What would you do differently?"**
Three, in order of how much I'd want them:
1. **Enable core library desugaring** and use `java.time` throughout instead of `String`
   dates on the client. Right now the type system does not stop a date bug.
2. **`@SerializedName` on every client DTO field.** A rename on either side is currently a
   silent null.
3. **Repository/controller integration tests with Testcontainers.** The engine is well
   covered; the persistence and security layers are covered by one opt-in test. Testcontainers
   would remove the "needs a local MySQL" excuse entirely.

**"What's the weakest part?"**
Two honest answers. The client trusts the server's validation rather than mirroring it —
deliberate, because two copies of the same rules drift, but it means a bad category costs a
round trip to discover. And billing is *generated* from subscription price, not reconciled
against real payment data, so it is a model of spend rather than a record of it.

**"How would you scale it?"**
Ask what is actually growing first — this is a per-user, single-writer workload, and users
sync a handful of times a month, so it is nowhere near needing it. If pushed: the ingest is
the only write-heavy path, so it would batch and move off the request thread; the
recommendation engine is stateless and horizontally scalable as-is; the dashboard rollups
are the first thing I'd cache, keyed by user and month, since they are recomputed per
request from data that changes only on sync.

**"How do you know it works?"**
The answer that shows judgement: the backend has 32 tests and they pass, the Android module
compiles, and **the end-to-end device walkthrough has not been run yet** — so I would not
claim the product works, only that its pieces are tested. Then name what the run would
catch that compilation cannot: a `findViewById` bound against the wrong layout returns null
and NPEs, a Gson field-name mismatch is silently null, a Retrofit path typo 404s. All three
compile cleanly. And API 24–25 specifically, because that is the only place a stray
`java.time` reference would surface.

---

## 7. Traps to avoid

- **Do not say the app is "fully tested."** It is compiled and the backend is unit-tested.
  The distinction is exactly what an interviewer is listening for.
- **Do not present the fixed vulnerabilities as if they were never there.** "I found and
  fixed an IDOR in the dashboard endpoint" is a good answer; discovering mid-interview that
  the code comments still describe the bug is not.
- **Do not claim `usage_count` is a count.** It is minutes; the column name is a V1 choice
  kept to avoid a rename migration for no functional gain. Own it as a known wart.
- **Do not oversell the recommendation engine as ML.** It is a deterministic scoring
  function with calibrated thresholds, and that is a feature — every verdict is explainable
  from its stored inputs. Say that before they ask.
- **Do not forget the thresholds are INR-calibrated.** `maxAcceptableCostPerHour = 200`
  is an amount of money. Another currency needs `EngineThresholds` overridden or the scores
  are meaningless. Volunteering this reads as calibration awareness.

---

## 8. The 90-second whiteboard version

If they hand you a marker, draw this and talk over it:

```
1. Android measures per-app foreground time     UsageStatsManager, special-access permission
2. Filter to subscribed packages only           privacy + relevance; never upload everything
3. PUT /usage with the window it covered        TODAY | WEEK | MONTH, and observed_days
4. Merge with max(), coverage moves with it     windows nest, so max() is safe both ways
5. RecommendationEngine: UsageFacts -> Verdict  pure function, 23 tests, no DB
6. Partial scan is a LOWER BOUND                can confirm value, never condemn
7. Dashboard: per-row verdicts + account rollup cost/hour, savings, wasted-spend %
```

The one line to land: **step 6 is the whole product.** Anyone can divide price by hours. The
work is in knowing when you have measured enough to tell someone to cancel something.

---

## 9. Complete request walkthrough

This is the section that separates "I built a CRUD app" from "I understand my system." Pick
`PUT /usage` — it is the request that touches every layer.

### The full path of one sync

```
Android: SyncButton tap
  │
  ├─ 1. MainActivity.checkUsageAccess()          AppOpsManager.checkOpNoThrow
  ├─ 2. ApiService.getSubscriptions(token)       GET /api/subscriptions
  ├─ 3. filter device apps → subscribed only     privacy boundary
  └─ 4. ApiService.syncUsage(token, request)     PUT /usage
        │
        ▼  HTTP, Authorization: Bearer <jwt>
  Spring Security filter chain
  ├─ 5. JwtAuthenticationFilter (OncePerRequestFilter, before
  │     UsernamePasswordAuthenticationFilter)
  │       reads "Authorization", strips "Bearer ", JwtService validates signature+expiry,
  │       CustomUserDetailsService loads UserDetails by email,
  │       SecurityContextHolder.setAuthentication(UsernamePasswordAuthenticationToken)
  └─ 6. authorizeHttpRequests → anyRequest().authenticated() passes
        │
        ▼
  UsageController.updateUsage(@RequestBody UsageRequest)
  ├─ 7. reject empty app list → 400
  ├─ 8. currentUser.requireId()                  ← identity from token, NOT from body
  └─ 9. userSnapShotService.processUsage(userId, request.getPeriod(), request.getApps())
        │
        ▼  @Transactional
  UserSnapShotService.processUsage
  ├─ 10. period = YearMonth.now(); today = LocalDate.now()   resolved ONCE per request
  ├─ 11. window = UsageWindow.fromClient(raw)                TODAY | WEEK | MONTH
  ├─ 12. observedDays, monthDaysElapsed                      the coverage pair
  └─ for each reported app:
        ├─ 13. resolveSubscription(userId, app)   match on package_name
        │        null → skipped list, continue    (never throw: one stray app
        │                                          used to fail the whole batch)
        ├─ 14. find snapshot by (subscription, period)
        │        absent  → record() directly       take the observation AND its coverage
        │        present → shouldReplace()?        max(existing, reported)
        ├─ 15. setSource(DataSource.ANDROID)       NOT NULL — omitting it failed every insert
        └─ 16. billingRecordService.createBill(sub, period)
  ├─ 17. if anything synced → recommendationService.recomputeForUser(userId, period)
  │         └─ RecommendationEngine.evaluate(UsageFacts) → Verdict   ← pure function
  └─ 18. return UsageSyncResult(period, synced, skipped)
        │
        ▼  200 + JSON
  Android: Toast "synced N, skipped M"
```

### How to narrate that in an interview

Don't recite 18 steps. Narrate **four beats**, and let them pick one to drill into:

1. *"The client filters before it uploads"* — a privacy boundary, not an optimisation.
2. *"The filter chain authenticates, then the controller re-derives identity from the
   token"* — the body's `userId` is ignored. Belt and braces.
3. *"The service resolves the window once, then merges each app with `max()`"* — the
   coverage travels with the figure.
4. *"Fresh usage invalidates the verdicts, so the engine re-runs"* — a pure function, so
   re-running is free and deterministic.

### The three details worth volunteering

**`final LocalDate today` is resolved once**, before the loop. If it were read per-app, a
long batch crossing midnight would write rows describing two different observation days.
That is the kind of bug that appears once a month at 00:00 and never reproduces.

**A brand-new snapshot bypasses `shouldReplace()`** and calls `record()` directly. Routing
it through the comparison would leave a fresh zero-minute row with *null* coverage — read
as a complete month — and zero minutes over a complete month is exactly the false `CANCEL`
the V8 work exists to prevent. Say this one if they ask "any edge cases?"

**An unmatched app is `skipped`, not an error.** The device reports every app; most are not
subscriptions. The earlier version threw, which failed the entire batch because one entry
in the list happened not to be a subscription.

### The one-sentence version of each layer

| Layer | Class | Responsibility in one line |
|---|---|---|
| Filter | `JwtAuthenticationFilter` | Turn a bearer token into a `SecurityContext`, or leave it anonymous |
| Config | `SecurityConfig` | Stateless, CSRF off, two public paths, everything else authenticated |
| Controller | `UsageController` | Validate shape, resolve identity, delegate. No business logic |
| Identity | `CurrentUserService` | The only place a user id is derived; `requireOwnership` compares path to principal |
| Service | `UserSnapShotService` | The transaction boundary and all the merge rules |
| Domain | `RecommendationEngine` | `UsageFacts → Verdict`. No Spring, no DB, no clock |
| Repository | `UserSnapShotRepository` | Spring Data JPA; derived query methods only |
| Schema | Flyway `V1`–`V8` | Owns the DDL; Hibernate only validates against it |

---

## 10. The data model, table by table

Five tables. Being able to say *why each column exists* is worth more than reciting types.

| Table | Holds | The column worth talking about |
|---|---|---|
| `users` | Accounts | `passwordhash` (BCrypt) — and the story that `/users/**` used to serialise the whole entity, including this |
| `subscriptions` | What the user pays for | `package_name` — the join key the entire matcher turns on, which is why the app picks it rather than typing it |
| `user_snapshots` | Measured usage per (subscription, month) | `usage_count` is **minutes**, not a count (V1 naming wart, owned deliberately); plus V8's `observed_window` / `observed_days` / `month_days_elapsed` |
| `billing_records` | One row per (subscription, period) | Generated from price, **not** reconciled against real payments — say this before they assume otherwise |
| `recommendations` | Current verdict per (user, subscription) | `reason` stores the numbers the decision actually used, so "why this verdict?" is answerable from the row alone |

**Relationships:** `users 1—N subscriptions 1—N user_snapshots`, and
`subscriptions 1—N billing_records`. `recommendations` is one row per
(user, subscription) — **current state only, no history**. That is a deliberate limitation,
not an oversight; volunteering it is better than being caught by it.

**Why `@Enumerated(EnumType.STRING)` and not `ORDINAL`:** ordinal stores 0, 1, 2, so
inserting a new enum value in the middle silently reinterprets every existing row. String
costs a few bytes and survives reordering. This also sets up Story 1 — string storage is
what made the case-sensitivity bug possible in the first place, and the CHECK constraints
comparing `CAST(col AS BINARY)` are what stop it recurring.

---

## 11. Keyword bank

Keywords only help if you can attach each to something you actually built. Every term below
is followed by the sentence that anchors it to this project. Use the anchor — a term with a
concrete referent reads as experience; a term without one reads as a flashcard.

### Architecture and design

| Term | Say it like this |
|---|---|
| **Modular monolith** | "One deployable, packages split by domain, so the seams are drawn if it ever needs splitting" |
| **Layered architecture** | "Controller → Service → Repository, with DTOs at the boundary" |
| **Separation of concerns** | "The controller validates shape and delegates; all merge rules live in the service" |
| **Pure function** | "`RecommendationEngine` maps `UsageFacts → Verdict` with no Spring, no DB and no clock" |
| **Domain-driven packaging** | "`Auth`, `Subscriptions`, `UserSnapShot`, `Recommendation` — by domain, not by `controllers/`, `services/`" |
| **Transaction boundary** | "`@Transactional` sits on the service method, so one sync is atomic across snapshots, billing and recommendations" |
| **Idempotency** | "Syncing twice doesn't double-count, because usage is merged with `max()` rather than `+=`" |
| **DTO** | "Entities are never serialised out — that's how `passwordhash` used to leak" |
| **Single source of truth** | "Flyway owns the schema; Hibernate only validates against it" |
| **Make invalid states unrepresentable** | "The package name is picked from `PackageManager`, so a typo can't exist" |
| **Fail-safe / asymmetric error cost** | "A false KEEP costs one month; a false CANCEL destroys trust — so the system is built to be wrong in only one direction" |

### Spring Boot and Spring core

| Term | Say it like this |
|---|---|
| **Dependency injection / IoC** | "`@Autowired` on `CurrentUserService`, `UserSnapShotService` — Spring wires the graph" |
| **Bean / `@Component` / `@Service` / `@Repository`** | "`CurrentUserService` is a `@Component`; the ingest logic is a `@Service`" |
| **`@RestController` / `@RequestMapping`** | "`@RequestMapping(\"/api/subscriptions\")` at class level, verb mappings per method" |
| **`@RequestBody` / `@PathVariable`** | "`@RequestBody UsageRequest`, and path ids are *verified*, never trusted" |
| **`ResponseEntity`** | "Explicit status codes — 201 on create, 409 on duplicate, 400 on bad enum" |
| **`ResponseStatusException`** | "How the service raises 400/403/404 without a custom exception hierarchy" |
| **Auto-configuration** | "Spring Boot wires the DataSource and Flyway from `application.yml`" |
| **Externalised configuration** | "`${DB_URL:default}` — env-overridable, so credentials never need editing a tracked file" |
| **Profiles / property placeholders** | "`${JWT_SECRET:dev-only-...}` makes the dev default self-documenting" |
| **`OncePerRequestFilter`** | "`JwtAuthenticationFilter` extends it, so it runs exactly once even with forwards" |

### Spring Security and auth

| Term | Say it like this |
|---|---|
| **`SecurityFilterChain`** | "Declared as a `@Bean`; two `permitAll` paths, `anyRequest().authenticated()`" |
| **Stateless session** | "`SessionCreationPolicy.STATELESS` — no `JSESSIONID`, the token *is* the session" |
| **JWT / HS256 / claims / subject** | "jjwt 0.11.5; the subject is the canonical email, which fixed username logins minting tokens that 401'd" |
| **`SecurityContextHolder`** | "The filter sets the authentication there; `CurrentUserService` reads it back" |
| **`UserDetailsService`** | "`CustomUserDetailsService` loads by email — the same value the token carries" |
| **BCrypt / password hashing / salt** | "`BCryptPasswordEncoder`; adaptive and salted, so it isn't a rainbow-table target" |
| **CSRF** | "Disabled deliberately — there are no cookies, so there's no CSRF vector; a token in a header can't be sent cross-site by a form" |
| **IDOR** | "`/api/dashboard/1` used to return anyone's spending. Now ownership-checked" |
| **Principle of least privilege** | "`/users/{id}` answers only for the caller; lookup-by-email was removed as account enumeration" |
| **Account enumeration** | "One message for 'no such user' and 'wrong password', so the response can't map which accounts exist" |
| **Information disclosure** | "`include-stacktrace: never` — devtools defaults it to `always`, which shipped the package layout in every 500" |

### JPA, Hibernate, persistence

| Term | Say it like this |
|---|---|
| **ORM / `@Entity`** | "Five entities: `User`, `Subscription`, `UserSnapShots`, `BillingRecord`, `Recommendation`" |
| **`ddl-auto: validate`** | "Hibernate is a *checker*, not an author. If an entity and a column disagree, the app refuses to start" |
| **`@Enumerated(EnumType.STRING)`** | "Not `ORDINAL` — ordinal means inserting an enum value silently reinterprets existing rows" |
| **Spring Data JPA derived queries** | "`findByUserIdAndPackageNameIgnoreCase` — the method name *is* the query" |
| **`Optional` return** | "Repositories return `Optional`, so 'absent' is in the type, not a null check" |
| **`LazyInitializationException`** | "Why the response DTO is a flat record instead of the entity — a lazy owner association would blow up outside the session" |
| **N+1 query problem** | "Worth naming as the thing I'd profile first if the dashboard got slow" |
| **Collation (`utf8mb4_0900_ai_ci`)** | "Case-**insensitive** by default, which is the root of Story 1" |

### Database and migrations

| Term | Say it like this |
|---|---|
| **Schema migration / Flyway** | "V1 through V8, versioned and forward-only" |
| **Forward-only migration** | "V6 was broken; V7 fixes it. Editing V6 would change its recorded checksum and fail validation on exactly the databases that need the fix" |
| **`flyway_schema_history` / checksum** | "How Flyway knows what ran; editing an applied file is what checksums exist to catch" |
| **`repair-on-migrate`** | "Deliberately **off** — it rewrites checksums to match edited files, hiding precisely that class of problem" |
| **`baseline-on-migrate`** | "On, so an existing database adopts the baseline rather than refusing to start" |
| **Idempotent migration** | "V7 drops the `WHERE` entirely — `UPPER()` on already-uppercase text is a safe no-op, so it can re-run" |
| **CHECK constraint** | "Asserts the post-condition. A migration that runs without error is not a migration that did something" |
| **Additive / backward-compatible change** | "V8's three columns are nullable, and NULL means 'unknown, assume complete' — which is how pre-V8 rows were written" |

### REST and API design

| Term | Say it like this |
|---|---|
| **Resource-oriented / HTTP verb semantics** | "`POST` creates, `PUT` partially updates, `DELETE` is a *soft* cancel" |
| **Soft delete** | "`DELETE` sets `status = CANCELLED` and keeps snapshots and billing, because those are the evidence behind the verdict" |
| **Partial update** | "`apply()` skips null fields, which is what lets the app reactivate with a status-only body" |
| **Meaningful status codes** | "201 create, 409 duplicate package, 400 bad enum, 403 wrong owner, 401 anonymous" |
| **Conflict / uniqueness** | "One subscription per package per user — two rows would double-count the same usage" |
| **OpenAPI / Swagger** | "springdoc-openapi 2.5.0 serves the spec and UI at `/swagger-ui/**`" |

### Android

| Term | Say it like this |
|---|---|
| **Activity lifecycle** | "`onResume` reloads the list, so it's correct after an add with no result plumbing" |
| **`RecyclerView` / `ViewHolder` / adapter** | "`SubscriptionAdapter` with a `Listener` interface, so the adapter stays dumb" |
| **Material Design components** | "`MaterialCardView`, outlined `TextInputLayout`, `ExtendedFloatingActionButton`" |
| **Special-access permission** | "`PACKAGE_USAGE_STATS` has no runtime dialog — it's Settings-only, checked via `AppOpsManager.checkOpNoThrow`" |
| **`UsageStatsManager`** | "Wrapped by AppsUsageMonitorAPI; gives per-package foreground time" |
| **`PackageManager.queryIntentActivities`** | "`ACTION_MAIN` + `CATEGORY_LAUNCHER` — launchable apps only, not every installed package" |
| **`QUERY_ALL_PACKAGES`** | "Required on Android 11+ or other apps' labels can't be resolved at all" |
| **minSdk / API level** | "24. Which is why there's no `java.time` anywhere in the module" |
| **Core library desugaring** | "**Not** enabled, so `LocalDate` compiles and then `NoClassDefFoundError`s on API 24–25" |
| **ANR / main-thread work** | "The app list loads on a background thread — `loadLabel` on every package is slow enough to jank" |
| **`ArrayAdapter` filtering** | "The built-in filter matches on `toString()`, so one `TextWatcher` searches both label and package id" |

### Networking

| Term | Say it like this |
|---|---|
| **Retrofit / declarative HTTP** | "An annotated interface; Retrofit generates the implementation" |
| **`Call` / `enqueue` / async callback** | "`enqueue` keeps the network off the main thread" |
| **OkHttp interceptor** | "Logging at `BASIC` with `redactHeader(\"Authorization\")` — at `BODY` it wrote the password and JWT into Logcat in every build" |
| **Gson serialisation** | "No `@SerializedName`, so field names must match JSON keys exactly — a mismatch is silently null, not an error" |
| **`BigDecimal` vs `double` for money** | "`BigDecimal` preserves scale, so `199.00` doesn't arrive as `199.0`" |
| **`10.0.2.2`** | "The emulator's alias for the host's loopback — `localhost` inside the emulator is the emulator" |

### Testing

| Term | Say it like this |
|---|---|
| **Unit test / JUnit 5** | "23 tests on the engine, 8 on the window logic, no infrastructure needed" |
| **Deterministic / non-flaky tests** | "Fixed fixture dates, never `LocalDate.now()` — a test anchored to today passes or fails by day of week" |
| **Boundary / edge-case testing** | "March 2026 starts Sunday, April starts Wednesday — both month-boundary alignments" |
| **Property-style sweep** | "One test walks every day of both months asserting `1 ≤ observedDays ≤ monthDaysElapsed`" |
| **Integration test** | "One opt-in test opens a real DataSource, runs Flyway V1→V8 and has Hibernate validate every entity" |
| **Conditional execution** | "`@EnabledIfEnvironmentVariable(named = \"INTEGRATION_DB\")` — unconditional meant CI failed for a missing database rather than a code fault" |
| **Regression test** | "`partialScanWithZeroMinutesDoesNotCancel` pins the exact bug that caused a false CANCEL" |
| **Testcontainers** | "What I'd add next — it removes the 'needs a local MySQL' excuse entirely" |

### Build and tooling

| Term | Say it like this |
|---|---|
| **Maven / Gradle wrapper** | "`mvnw` and `gradlew` pinned, so the build doesn't depend on what's installed" |
| **AGP / Gradle version** | "AGP 9.1.0, Gradle 9.3.1, compileSdk 34" |
| **`BuildConfig` field injection** | "The base URL is injected at build time from `local.properties`, not hardcoded — it used to be a stale DHCP address" |
| **CI pipeline** | "`mvnw test` is what runs, which is why the DB-dependent test is opt-in" |
| **Lombok** | "`@Data`, `@Builder` on DTOs to cut boilerplate" |

### Domain and product

| Term | Say it like this |
|---|---|
| **Cost per hour** | "`monthlyPrice / (usageMinutes / 60)` — the core metric" |
| **Value score** | "0–100, falling linearly to zero as cost/hour approaches the threshold" |
| **Threshold calibration** | "`maxAcceptableCostPerHour = 200` is an *amount of money*, so it's INR-specific — another currency needs it overridden" |
| **Lower bound vs point estimate** | "A partial scan proves usage was *at least* X. It can confirm value; it can never condemn" |
| **Confidence score** | "`0.20 + 0.45 × coverage` — floored at 'no data', capped below a full month, so provisional never outranks measured" |
| **Explainability** | "Every number in `reason` is one the decision actually used, so 'why this verdict?' is answerable from the stored row" |
| **Deterministic scoring, not ML** | "Say this *before* they ask. It's a feature: every verdict is reproducible from its inputs" |

### Words to avoid

- **"Microservices"** — it's a monolith, and correctly so. Say "modular monolith."
- **"AI-powered" / "machine learning"** — it is a deterministic scoring function. Claiming ML invites questions about training data that doesn't exist.
- **"Fully tested" / "production-ready"** — it is compiled and unit-tested, with a committed
  JWT default and plain HTTP. Both are disqualifying if contradicted.
- **"Real-time"** — sync is user-initiated and batched.

---

## 12. How to explain any of it: the four-move formula

Most candidates describe *what* the code does. The ones who stand out explain *why it is
that way and not the obvious alternative*. Every concept in this project fits four moves:

> **1. Name it** — the term, in one sentence.
> **2. Why chosen** — the problem it solves *here*.
> **3. Alternative rejected** — what you didn't do, and the specific cost.
> **4. Evidence** — a file, a test, or a number.

Move 3 is the one that signals engineering judgement, and it's the one people skip.

### Worked example: `max()` merge

> **Name:** "Usage is merged with `max(existing, reported)` per subscription per month."
> **Why:** "The device reports the *total* for the window it scanned, not a delta."
> **Rejected:** "`+=` would double-count — sync twice in a minute and you double the month.
> `=` would let a 'Today' scan overwrite the month's total with a smaller number. `max()` is
> safe in both directions because the windows nest: `TODAY ⊆ WEEK ⊆ MONTH`."
> **Evidence:** "`UserSnapShotService.shouldReplace`, and the coverage columns move with the
> figure — there's a test for the same-minutes-more-coverage case."

### Worked example: `ddl-auto: validate`

> **Name:** "Hibernate validates the schema; Flyway owns it."
> **Why:** "One source of truth for DDL, and it's reviewed and versioned."
> **Rejected:** "`update` lets Hibernate mutate the schema at startup, in an order nobody
> reviewed, with no rollback and no record. `create-drop` loses data."
> **Evidence:** "If an entity and a column disagree, the app refuses to start — which is the
> correct time to find out. That's what the opt-in integration test exercises."

### Worked example: CSRF disabled

> **Name:** "CSRF protection is off."
> **Why:** "The API is stateless and token-based — `SessionCreationPolicy.STATELESS`, no
> cookies."
> **Rejected:** "Leaving it on would break every client for no gain. CSRF exploits the
> browser automatically attaching cookies; a browser won't attach an `Authorization` header
> to a cross-site form post, so there's no vector to protect."
> **Evidence:** "`SecurityConfig` line 28, and there's no session cookie anywhere."

That last one matters because "I disabled CSRF" sounds careless until you explain the threat
model. **Same fact, opposite impression, purely from move 3.**

### When you don't know

Say so, then reason. "I haven't measured that — my guess is the dashboard rollup, because
it's recomputed per request from data that only changes on sync, and I'd cache it keyed by
user and month. But I'd profile before assuming." That answer beats a confident wrong one
every time, and interviewers are explicitly listening for it.

### The two things to say unprompted

1. **The device walkthrough hasn't run yet.** Volunteering the limit of your own verification
   is the single strongest credibility move available, and it costs you nothing because the
   compiled build and 32 passing tests are real.
2. **The thresholds are INR-calibrated.** It shows you know the difference between a tuned
   constant and a universal one.

