# AMR KPI Test Harness

A standalone Spring Boot microservice whose sole purpose is to empirically validate **Azure
Managed Redis (AMR)** as a candidate **session/context data store** for high-intensity
applications, run from inside AKS against an AMR active geo-replication group (Canada Central
priority-1 / Canada East priority-2).

This is not a caching layer — it's an instrumented test harness. Priorities, in order:
measurement accuracy (the harness must never become the bottleneck it's measuring), honest
reporting of failures and tail latency, and reproducibility (every run's configuration is
captured with its results).

Everything is viewable from this one service: a live dashboard at `/dashboard`, nine KPI REST
endpoints, and CSV/XLSX/PDF report export — no Prometheus/Grafana required.

📊 **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** has sequence diagrams (rendered PNGs, PlantUML
source included) for the four flows that matter most: Entra ID auth, Redis weighted
failover/circuit-breaker/failback, the KPI 3 load-test → metrics-rollup pipeline, and the KPI 6
cache-aside hit/miss/degraded paths. The prose sections below cover the same ground with code
references; the diagrams are the fast way to get oriented before reading either.

## Table of contents

1. [What this project actually does](#what-this-project-actually-does)
2. [Architecture at a glance](#architecture-at-a-glance)
3. [How Redis connectivity works](#how-redis-connectivity-works)
4. [How Entra ID managed identity auth works](#how-entra-id-managed-identity-auth-works)
5. [The nine KPIs](#the-nine-kpis)
6. [Local setup guide](#local-setup-guide)
7. [Guide for developers](#guide-for-developers)
8. [Guide for DevOps / operators](#guide-for-devops--operators)

See also: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for sequence diagrams.
9. [Known risks and simplifications](#known-risks-and-simplifications)

---

## What this project actually does

Your team is evaluating AMR as the backing store for session/context data in a high-intensity
application. That decision needs real numbers, not vendor claims: how fast is it really, what
happens during a regional failover, does the client library's token-renewal story actually hold
up under load, and what does "eventually consistent" cost you in practice for session data.

This service exists to answer those questions and nothing else. It:

- **Drives realistic traffic** against AMR using a session-shaped workload (hot-key skew, TTL
  churn, log-normal payload sizes) rather than uniform-random GET/SET, because that's what
  actually stresses a session store differently than a generic cache benchmark would.
- **Instruments the client library's own failover machinery** (Jedis's `MultiDbClient`) instead
  of hand-rolling a parallel one, so what you measure is what a real application using this
  client would actually experience.
- **Never assumes a number** — every latency, replication-lag, or consistency figure in this app
  is an empirically measured, timestamped sample. Nothing is hardcoded or estimated.
- **Persists every run's full configuration** alongside its results (`Run.configJson`), so a
  KPI number is always traceable back to exactly what produced it.
- **Reports honestly on failure**, not just success — every failed operation is classified by
  cause (connect timeout vs. auth failure vs. circuit-breaker rejection vs. pool exhaustion), so
  a report can tell "the breaker was doing its job" apart from "reads were timing out."

What it is **not**: a production caching layer, a general Redis benchmarking tool, or a
replacement for real infrastructure drills. Some KPIs (see [KPI 4](#kpi-4--failover-ability))
explicitly simulate conditions application code cannot itself induce (a real regional outage) —
the README calls those out where they apply, and describes the real, non-simulated drills your
infra team can run instead with this harness as the measurement instrument.

## Architecture at a glance

```
com.example.amrkpi
├── config/          AmrProperties (AMR endpoints/TLS/auth/pool/retry/breaker/health-check),
│                     AmrKpiProperties (metrics, session workload, probes, presets)
├── redis/            MultiDbClient wiring (weighted failover, breaker, health checks,
│                     switch/breaker-transition listeners), Entra ID auth, probe connection pools
├── kpi/               one package per KPI (uptime, geolatency, throughput, failover,
│                     circuitbreaker, cachemiss, networktime, consistency, tokenlifecycle)
├── loadgen/          session workload model + virtual-thread load generator
├── metrics/          HdrHistogram/Micrometer capture, rollup snapshotter, error taxonomy, run registry
├── persistence/      H2 entities: Run, MetricRollup (high-frequency), RawEvent (low-frequency)
├── report/           shared query layer (RollupAggregator, ReportQueryService) + CSV/XLSX/PDF writers
└── web/              dashboard controller, one-click presets
```

Every request path funnels through the same small set of building blocks, regardless of which
KPI it's serving:

- **`redis/`** owns exactly one long-lived `MultiDbClient` (the load generator's and cache-aside
  path's connection to AMR) plus two small dedicated probe connections (one per region) used only
  by the low-traffic KPIs (uptime, network-time, geo-replication). This separation is
  deliberate — see [probe isolation](#connection-pools-load-generator-vs-probes) below.
- **`metrics/`** is the only place latency/error samples get recorded (`OperationRecorder`) and
  turned into persisted rollups (`RollupSnapshotTask`). Every KPI service calls into this layer;
  none of them touch HdrHistogram or the database directly.
- **`report/`** is the one query layer every REST endpoint, the dashboard, and the CSV/XLSX/PDF
  exporters read from — so a number never has two different code paths that could disagree.

### Why Jedis 7.5.3, not 6.x

Jedis 7.0 renamed its client-side failover API (`MultiClusterClientConfig` /
`MultiClusterPooledConnectionProvider` in 6.x became `redis.clients.jedis.MultiDbConfig` /
`redis.clients.jedis.MultiDbClient`, with supporting types in `redis.clients.jedis.mcf`) — same
capability, new names. This build targets current stable (7.5.3). If you're comparing this code
against older Jedis blog posts or samples, that's why the class names won't match — this
library's failover API has moved once already and could again, so always check the current
`redis.clients.jedis.mcf` package before trusting a class name from an older source.

## How Redis connectivity works

This section is the deep dive on how the app actually talks to AMR — read it before touching
`AmrRedisClientConfig.java` or `ProbeConnectionFactory.java`. Sequence diagram:
[docs/ARCHITECTURE.md § 2](docs/ARCHITECTURE.md#2-redis-connectivity-weighted-failover-circuit-breaker-failback).

### Topology

AMR is deployed as an **active geo-replication group** of two members, both active primaries
(CRDT-based active-active, not primary/replica):

| Role | Region | Config property | Weight |
|---|---|---|---|
| Priority 1 (local) | Canada Central | `amr.endpoints.local` | `1.0` |
| Priority 2 (failover) | Canada East | `amr.endpoints.failover` | `0.5` |

Both members accept writes; replication between them is **eventually consistent with no SLA on
sync time**. The app never assumes a fixed lag anywhere in code — every cross-region timing
figure (KPI 2, KPI 9) is an empirically measured sample.

`AmrEndpoints.java` is the single place that resolves these two `HostAndPort`s from config and
gives the rest of the app a way to translate between a logical region name (`"canada-central"`)
and the Jedis `Endpoint` objects the client library deals in — every other class asks
`AmrEndpoints` rather than reading `AmrProperties` directly.

### The client: one `MultiDbClient`, wired once

All AMR traffic that isn't a low-frequency probe (see below) goes through exactly one
`MultiDbClient` bean, built in `AmrRedisClientConfig.multiDbConnectionProvider(...)` and
`AmrRedisClientConfig.amrRedisClient(...)`. This is Jedis's own reference implementation of
client-side geographic failover — the app configures and instruments it rather than writing a
parallel failover mechanism, deliberately:

```java
MultiDbConfig.DatabaseConfig localDb = MultiDbConfig.DatabaseConfig
        .builder(endpoints.local(), clientConfig)
        .connectionPoolConfig(poolConfig)
        .weight(props.getEndpoints().getLocal().getWeight())          // 1.0
        .healthCheckStrategySupplier((hostAndPort, cfg) -> new PingStrategy(hostAndPort, cfg, healthCheckConfig))
        .build();
// ...same for the Canada East failoverDb, weight 0.5

MultiDbConfig multiDbConfig = MultiDbConfig.builder()
        .database(localDb)
        .database(failoverDb)
        .commandRetry(retryConfig)
        .failureDetector(circuitBreakerConfig)
        .failbackSupported(props.getFailback().isSupported())
        .gracePeriod(props.getFailback().getGracePeriodMillis())
        .build();

MultiDbConnectionProvider provider = new MultiDbConnectionProvider(multiDbConfig);
```

What this buys you, all from the client library rather than custom code:

- **Weighted endpoint selection** — the client always prefers the highest-weight *healthy*
  endpoint, so Canada Central is used until it's unhealthy, then Canada East takes over.
- **Per-endpoint health checks** — a `PingStrategy` on each endpoint runs independently
  (`amr.health-check.interval-millis`, default 1s), so an unhealthy endpoint is detected without
  waiting for a real command to fail against it.
- **A real Resilience4j circuit breaker per endpoint** — configured via
  `amr.circuit-breaker.*` (failure-rate threshold, sliding window, minimum calls). Jedis doesn't
  bundle Resilience4j itself; it's an explicit `build.gradle` dependency this config wires in.
- **Bounded retry with backoff** (`amr.retry.*`) — never infinite.
- **Failback with a grace period** (`amr.failback.grace-period-millis`, default 60s) — once the
  higher-priority endpoint recovers, the client waits out the grace period before switching back,
  to avoid flapping.

One deliberate wiring choice worth knowing: `MultiDbConnectionProvider` is built **explicitly**
(`new MultiDbConnectionProvider(multiDbConfig)`) and handed to `MultiDbClient.builder()` via
`.connectionProvider(...)`, rather than letting the builder construct one internally. This is the
*only* way to retain a handle to it afterward — and that handle is what two KPIs are built on:

- **KPI 5** (circuit breaker) reads the real `CircuitBreaker` per endpoint via
  `provider.getDatabase(endpoint).getCircuitBreaker()` — `MultiDbClient` itself only exposes a
  boolean `isHealthy()`, not the underlying breaker state.
- **KPI 4** (failover simulation) calls `provider.getDatabase(endpoint).setDisabled(true/false)`
  to force an endpoint unhealthy on demand — real, public Jedis API, not a hack.

Both mechanisms are documented in detail under their KPI entries below.

### Connection pools: load generator vs. probes

There are **two separate connection pools** to AMR, and this separation is load-bearing, not
incidental:

1. **The `MultiDbClient` pool** (`amr.pool.*`, default `maxTotal: 64`) — used by the load
   generator (KPI 3) and the cache-aside path (KPI 6). This is where real traffic volume goes.
2. **Dedicated probe connections** (`ProbeConnectionFactory.java`, fixed at `maxTotal: 4` per
   region) — used *only* by the low-frequency KPIs: uptime pings (KPI 1), network-time pings
   (KPI 7), and geo-replication lag probes (KPI 2).

If probes shared the load generator's pool, they'd queue behind load traffic under saturation and
end up measuring pool contention instead of AMR itself — silently corrupting exactly the KPIs
that are supposed to be your ground truth during a load test. `ProbeConnectionFactory` builds two
plain `RedisClient` instances (Jedis's current single-endpoint pooled client — see the note in
that file about `JedisPooled` being deprecated in 7.x), one per region, each authenticated the
same way as the main client but never touching its pool.

### TLS

TLS is mandatory and never disabled, including in load-test paths — `amr.tls.enabled: true` is
the only toggle, and even it exists mainly so `SslOptions.defaults()` is applied consistently.
`SslOptions.defaults()` uses the JVM's default trust manager, so certificate validation is never
turned off anywhere in this codebase. AMR listens on port 10000 by default
(`amr.endpoints.*.port`).

### Clustering policy

AMR instances can run **Enterprise clustering policy** (single endpoint, standard client) or
**OSS cluster policy** (cluster-aware client required) — this is a one-time, config-time decision
that must match on **both** members of a geo-replication group. This app is built for
**Enterprise policy** (`amr.clustering-policy: enterprise`) using `UnifiedJedis`/`MultiDbClient`.
A policy/client mismatch produces confusing `MOVED`/connection errors that would contaminate
every KPI — confirm this with whoever owns the AMR instances before pointing this app at them.

### Command hygiene

The load generator and cache-aside path only ever use O(1)/O(small) commands (`GET`, `SET`,
`SETEX`/`EX`, `GETEX`, `EXPIRE`, `DEL`, `PING`). Nothing in a hot path issues `KEYS`, `SCAN`, or
`FLUSHALL`-style commands (the latter is blocked by AMR geo-replication anyway). Cleanup between
runs relies on TTL expiry or targeted `DEL` of a run's own known keys.

## How Entra ID managed identity auth works

This is the part most likely to bite you if you're setting this up fresh, so it gets its own
section with the full flow end to end. Sequence diagram:
[docs/ARCHITECTURE.md § 1](docs/ARCHITECTURE.md#1-entra-id-managed-identity-auth-flow).

### The short version

There is **no client ID, secret, or Redis access key anywhere in this app's config or code**.
Authentication is 100% Entra ID (Azure AD) token-based, using workload identity federation. The
identity the app runs as must be granted RBAC on both AMR members by whoever administers them —
this app never provisions that access itself.

### The full flow, step by step

**1. Outside this codebase: the platform team federates an identity to a Kubernetes
ServiceAccount.** In AKS, this means an Entra ID application/managed identity has a *federated
credential* trusting a specific Kubernetes ServiceAccount (by namespace + name) as a valid
subject. The pod that runs this app must use that ServiceAccount, and its pod template must carry
the label `azure.workload.identity/use: "true"`. When both are true, AKS's workload-identity
mutating admission webhook injects four environment variables into the pod at scheduling time:
`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_FEDERATED_TOKEN_FILE`, `AZURE_AUTHORITY_HOST`. None
of this is configured by this app — it's a precondition the platform team owns.

**2. Inside this codebase: `DefaultAzureCredential` picks those env vars up automatically.**
`AmrAuthConfig.authXManager(...)` does exactly this and nothing more:

```java
DefaultAzureCredential credential = new DefaultAzureCredentialBuilder().build();
```

No `.managedIdentityClientId(...)`, no explicit client ID — `DefaultAzureCredential` is a *chain*
of credential types it tries in order (environment vars, workload identity, managed identity,
Azure CLI, ...), and it's specifically the **`WorkloadIdentityCredential`** link in that chain
that picks up the four env vars from step 1. This is why the app has zero AKS-specific code: the
same `DefaultAzureCredentialBuilder().build()` call also works locally via `az login` (falls
through to the Azure CLI credential further down the same chain) with no code changes.

**3. That credential is wrapped into a Redis-specific token config.** `redis-authx-entraid`
(Redis's own library for Entra ID token auth with Jedis) needs more than a bare credential — it
needs to know *which scope* to request a token for:

```java
TokenAuthConfig tokenAuthConfig = AzureTokenAuthConfigBuilder.builder()
        .defaultAzureCredential(credential)
        .scopes(Set.of(props.getAuth().getTokenScope()))   // amr.auth.token-scope
        .tokenRequestExecTimeoutInMs(props.getAuth().getTokenRequestTimeoutMs())
        .build();
```

The scope defaults to `https://redis.azure.com/.default` (`amr.auth.token-scope` /
`AMR_TOKEN_SCOPE` env var) — see the [token scope pitfall](#token-scope-pitfall) below if
authentication fails with `WRONGPASS` despite correct RBAC.

**4. That config drives an `AuthXManager`, which is the actual token lifecycle engine.**

```java
AuthXManager manager = new AuthXManager(tokenAuthConfig);
manager.setListener(/* records TOKEN_RENEWAL failure events — see KPI 8 */);
manager.addPostAuthenticationHook(/* records TOKEN_RENEWAL success events — see KPI 8 */);
manager.start();   // must be called explicitly — nothing in Jedis does this for you
```

`manager.start()` is not optional decoration — it's what actually kicks off the token manager's
background fetch/refresh loop. Skip it and every connection auth attempt fails with no token ever
acquired (this was verified directly against the library source while building this app).
**One `AuthXManager` instance is shared by every Jedis connection in the app** — both AMR
endpoints' pools (via the main `MultiDbClient`) and both probe connections — so a single token
lifecycle re-authenticates everything at once rather than each pool managing its own tokens
independently.

**5. The `AuthXManager` is wired into every `JedisClientConfig`, not just one connection.**
`AmrRedisClientConfig.amrJedisClientConfig(...)` is the one place a `JedisClientConfig` gets
built, and it's shared by both the main client and the probe connections:

```java
DefaultJedisClientConfig.builder()
        .connectionTimeoutMillis(...)
        .socketTimeoutMillis(...)
        .authXManager(authXManager)   // not .credentialsProvider(...) — see note below
        .sslOptions(SslOptions.defaults())
        .build();
```

Note this is `.authXManager(...)`, not the more generic `.credentialsProvider(...)` — the latter
handles static or manually-rotated credentials with no renewal logic; `.authXManager(...)` is
specifically the integration point for a token lifecycle that renews itself and re-authenticates
already-open connections.

**6. Token renewal re-authenticates pooled connections *in place* — this is the property KPI 8
exists to prove.** Internally, every `Connection` Jedis opens under a `JedisClientConfig` carrying
an `AuthXManager` registers itself with that manager via a weak reference. When the token
manager renews a token in the background (Entra ID access tokens are typically valid ~1 hour),
`AuthXManager.authenticateConnections(token)` iterates every live registered connection and calls
`connection.setCredentials(...)` on each **without closing or recreating the pooled connection**.
Whether this is actually seamless under sustained load — no error blip, no latency spike — is
exactly what KPI 8 and the `soak` preset measure, by correlating each renewal's timestamp against
the concurrent load test's rollups.

### Local vs. cluster credential resolution

Because step 2 above is just `DefaultAzureCredential`'s normal chain, **the exact same code path
works locally with zero modification** — you don't need workload identity federation to run this
on your laptop, you just need *some* link in that chain to succeed:

- **In AKS**: `WorkloadIdentityCredential` succeeds (steps 1–2 above).
- **Locally**: run `az login` as a principal that has the same AMR RBAC grant, and
  `DefaultAzureCredential` falls through to the `AzureCliCredential` link in its chain instead.
  No env vars, no code changes, no separate "local mode."

If **every** credential in the chain fails — no AKS federation, no `az login`, nothing — the app
does not start in a degraded state. See
[why local setup needs `az login`](#why-you-actually-need-az-login-not-optional) for exactly what
happens and why.

### Token scope pitfall

Some Jedis/managed-identity combinations against AMR fail token validation (`WRONGPASS`) with one
of `https://redis.azure.com/.default` vs. `https://redis.azure.com` (no `/.default`) depending on
library/credential-type versions, while official Microsoft docs and this app's default both use
the `/.default` form. This is deliberately a single config property
(`amr.auth.token-scope` / `AMR_TOKEN_SCOPE`), not hardcoded — if you see `WRONGPASS` after
confirming RBAC is actually correct on both AMR members, flip this value before assuming
something else is broken.

### `redis-authx-entraid` is beta

As of this build, `redis-authx-entraid` is on `0.1.1-beta2`, first published March 2025, with no
GA release since. It's the only supported path for Entra ID token auth with automatic
re-authentication of pooled Jedis connections, so this app depends on it regardless — but budget
time to re-validate against whatever version is current when you actually run this, and watch for
breaking API changes on upgrade.

## The nine KPIs

| # | KPI | What it answers | Endpoints |
|---|---|---|---|
| 1 | **Uptime** | What % of the time was each region reachable, and what did the outage timeline look like? | `GET /kpi/uptime?region=&from=&to=` |
| 2 | **Geo-replication time** | How long does a write in one region take to become visible in the other — idle, and under load? | `POST /kpi/geo-replication-time/run`, `GET /kpi/geo-replication-time?direction=` |
| 3 | **Throughput** | What ops/sec did the session workload actually achieve vs. target, and what did latency look like through p99.9? | `POST /loadgen/start`, `GET /loadgen/status`, `POST /loadgen/stop`, `GET /kpi/throughput/{runId}` |
| 4 | **Failover ability** | How long from an induced regional failure to the client failing over, and back after recovery? | `POST /kpi/failover/induce`, `POST /kpi/failover/restore`, `GET /kpi/failover/report?region=` |
| 5 | **Circuit breaker** | What state is the breaker in per endpoint right now, and how much time has it spent in each state? | `GET /kpi/circuit-breaker/status`, `GET /kpi/circuit-breaker/report?region=` |
| 6 | **Cache-aside / read-miss** | What's the real hit/miss ratio, and what does it cost to run "cache-less" when Redis is unavailable? | `GET /kpi/cache-aside/get?key=`, `POST /kpi/cache-aside/run`, `GET /kpi/cache-aside/report` |
| 7 | **Network time** | What's the bare round-trip time to each region, isolated from command processing? | `GET /kpi/network-time?region=` |
| 8 | **Token lifecycle** | Does Entra ID token renewal under sustained load cause any error or latency blip? | `GET /kpi/token-lifecycle?runId=&windowSeconds=` |
| 9 | **Consistency & conflict** | How stale can a cross-region read be, and what happens when both regions write the same key concurrently? | `POST /kpi/consistency/staleness`, `POST /kpi/consistency/conflict`, `GET /kpi/consistency/report` |

Sequence diagrams for KPI 3 (load test → rollups → report) and KPI 6 (cache-aside hit/miss/degraded):
[docs/ARCHITECTURE.md § 3](docs/ARCHITECTURE.md#3-kpi-3-load-test-session-workload--rollups--report) and
[§ 4](docs/ARCHITECTURE.md#4-kpi-6-cache-aside-hit-miss-and-degraded-paths).

### KPI 4 — failover ability

Application code cannot take a real AMR instance down. This harness forces the targeted endpoint
unhealthy via `MultiDbConnectionProvider.Database.setDisabled(true)` — real, public Jedis API,
not a fake unreachable host (see [the client section above](#the-client-one-multidbclient-wired-once))
— which drives the client through the same failover-selection path a genuine health-check failure
would. `restore()` flips it back and the built-in failback grace period
(`amr.failback.grace-period-millis`) governs when the client actually switches back.

Run this **while the load generator is active** — the report correlates the induced window
against that run's rollups to show the error/latency spike during the actual transition.

Two **real, non-simulated** tests your infra team can drive while this harness's
uptime/failover/breaker instrumentation (KPIs 1, 4, 5) measures them:
1. An **Azure-initiated reboot/maintenance event** on one AMR member.
2. A **region-level exercise via force-unlink** of the geo-replication link.

### KPI 9 — the architectural conclusion this informs

Active geo-replication is eventually consistent and resolves concurrent writes via CRDT conflict
resolution — for opaque session blobs, that means one concurrent write wins and the other's data
is silently gone. The staleness and conflict tests quantify that risk window. The conclusion this
should inform: prefer **region-affinity** for session writes (a given session's writes go to one
region, the other is failover-only) — exactly the behavior the weighted client-side failover in
KPI 4 already produces. This test suite quantifies the risk window when affinity is broken during
a failover.

### Test presets — one-click runs of the above

`POST /presets/{name}/run`, or the buttons on `/dashboard`:

| Preset | What it does |
|---|---|
| `smoke` | 60s light load (concurrency 8) + one geo-replication round-trip + one staleness/conflict probe — sanity-checks every KPI quickly. |
| `session-peak` | 15-minute closed-loop run at concurrency 128, full session workload shape. |
| `failover-under-load` | `session-peak`, plus the local region forced unhealthy 120s in and restored 60s later — check `GET /kpi/failover/report` afterward. |
| `soak` | Moderate load (concurrency 24) for `2 × amrkpi.token-lifecycle.assumed-token-lifetime-seconds` (default 2 hours) — spans at least one real Entra ID token renewal under sustained load; check `GET /kpi/token-lifecycle?runId=` afterward. |
| `consistency-probe` | Runs the KPI 9 suite. Automatically runs "under load" if a load test is currently active, otherwise idle. |

## Local setup guide

### Prerequisites

| Requirement | Why |
|---|---|
| **Java 21** | Virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) are load-bearing for the load generator, not optional. |
| **Nothing else to install for the build** — just use the wrapper: `./gradlew` | The wrapper downloads the exact pinned Gradle version (see `gradle/wrapper/gradle-wrapper.properties`) on first run; no separate Gradle install needed. |
| **Azure CLI (`az`), logged in** (`az login`) | See [below](#why-you-actually-need-az-login-not-optional) — this is not optional. |
| **An AMR instance (or two) reachable from your machine, with RBAC granted to your `az login` identity** | Without this the app starts but every Redis operation fails auth. |
| **Docker** (optional) | Only needed if you want to build/run the container image locally instead of via Gradle. |

### Why you actually need `az login` (not optional)

This is the single most common local-setup surprise, so it's worth being blunt: **the app will
not start at all** without a working Azure credential — not "start in a degraded mode with Redis
KPIs broken," but refuse to boot, full stop. This was verified directly while building this app:
`AuthXManager.start()` (`AmrAuthConfig`) blocks during Spring context startup on an initial token
fetch, and if every credential in `DefaultAzureCredential`'s chain fails (no AKS federation
locally, no `az login`, nothing), it throws and Spring's context refresh aborts before Tomcat, the
dashboard, or any endpoint comes up. You'll see the full `ChainedTokenCredential` attempt sequence
in the logs (`EnvironmentCredential unavailable`, `WorkloadIdentityCredential unavailable`,
`ManagedIdentityCredential unavailable`, ..., `AzureCliCredential unavailable`) followed by a
`BeanCreationException`.

`az login` is the fix: it makes the `AzureCliCredential` link in the chain succeed, and
`DefaultAzureCredential` needs exactly one link to succeed.

```bash
az login
az account show   # confirm you're logged in as the identity with AMR RBAC
```

If you don't have a real AMR instance available to test against yet, you can still confirm this
part works: the app will get *past* startup and fail later with a Redis-specific auth error
(`WRONGPASS` or a connection error) instead of failing at the credential-chain step — that
distinction tells you whether the Azure identity side or the Redis RBAC side is the problem.

### Getting AMR access for local development

You need RBAC on the AMR instance(s) granted to whatever identity `az login` authenticates as —
someone with Redis Enterprise/AMR admin rights sets this up; this app never provisions it. If you
only have **one** AMR instance available (not a full two-region geo-replication group), you can
still exercise most of the app by pointing both `AMR_CC_HOST` and `AMR_CE_HOST` at the same
instance — KPIs 1, 3, 5, 6, 7, and 8 all work fine against a single endpoint. KPI 2
(geo-replication lag) and KPI 9 (consistency/conflict) specifically need two real, independently
writable members to produce a meaningful signal — against a single instance they'll still run
without erroring, but every "replication lag" sample will just be near-zero same-instance latency,
not a real cross-region measurement.

### Build and run

```bash
git clone <this-repo>
cd redis-amr-test

# Build (runs the full test suite — see "Guide for developers" below)
./gradlew clean build

# Run, pointing at your AMR instance(s)
AMR_CC_HOST=<your-cc-host>.canadacentral.redis.azure.net \
AMR_CE_HOST=<your-ce-host>.canadaeast.redis.azure.net \
./gradlew bootRun
```

Or run the packaged jar directly:

```bash
AMR_CC_HOST=<your-cc-host> AMR_CE_HOST=<your-ce-host> \
java -jar build/libs/amr-kpi-harness.jar
```

Or via Docker (the multi-stage `Dockerfile` builds and runs it in one shot, using the project's
own Gradle wrapper inside the build stage):

```bash
docker build -t amr-kpi-harness .
docker run -p 8080:8080 \
  -e AMR_CC_HOST=<your-cc-host> -e AMR_CE_HOST=<your-ce-host> \
  amr-kpi-harness
```

**Note on Docker + `az login`**: `az login`'s credential cache lives on your host machine, not
inside the container, so `DefaultAzureCredential` won't find it from within `docker run` the way
it does when running via `./gradlew bootRun` directly on your machine. For local Docker testing
you'll need a separate local federated-credential/service-principal setup exported as the same env
vars AKS would inject (`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_FEDERATED_TOKEN_FILE`,
`AZURE_AUTHORITY_HOST`), or just run via Gradle locally where the `az login` session is directly
visible.

### Verify it's working

1. Open **`http://localhost:8080/dashboard`**. You should see both regions' circuit breaker state
   as `CLOSED` and uptime climbing toward 100% within the first uptime-probe interval (5s).
2. Click **"Run smoke"**. It returns a `runId` immediately and finishes in under a minute; refresh
   the dashboard to watch uptime, network time, geo-replication lag, and the consistency table
   populate.
3. Click **"Export PDF"** (or hit `GET /reports/export?format=pdf`) to download a point-in-time
   report covering everything the dashboard shows, plus the full effective run configuration.
4. `GET http://localhost:8080/actuator/health` should report `UP`.

If step 1 shows breaker state stuck on something other than `CLOSED`, or uptime never climbs,
check the startup logs for the `WRONGPASS` vs. connection-error distinction described above.

## Guide for developers

### Running the tests

```bash
./gradlew test
```

The suite (88 tests as of this writing) mixes pure unit tests (`ErrorCategory`, `PercentileUtil`,
`RollupAggregator`, `KeyGenerator`/`ValueGenerator`), Mockito-based service tests
(`UptimeService`, `CacheAsideService`, `RollupSnapshotTask`), a `@DataJpaTest` that persists one
of every JPA entity (this is what catches schema/DDL bugs — see below), and one full
`@SpringBootTest` integration test (`UptimeIntegrationTest`) that boots the entire Spring context
with only the four Redis-I/O beans mocked (`AuthXManager`, `MultiDbConnectionProvider`,
`MultiDbClient`, `ProbeConnectionFactory.ProbeClients`) and exercises a real REST call through to
real H2/JPA persistence. **No test requires a real AMR instance or Azure credentials** — that's
deliberate, so `./gradlew test` works in CI with zero external dependencies. Test reports land in
`build/reports/tests/test/index.html`.

If you add a bean that performs real I/O at construction time (anything that opens a connection,
calls a remote API, etc.), it needs to join that mocked set in `UptimeIntegrationTest` or any new
`@SpringBootTest` you write, or the test context won't start.

### Project conventions

- **One package per KPI** under `kpi/`, each typically holding a `*Service` (the logic + query
  layer), a `*Controller` (REST surface), and one or more DTO records for its report shape. Look
  at `kpi/uptime/` as the simplest complete example before adding a new one.
- **`RollupAggregator` and `PercentileUtil` are the only places percentile math happens.** If
  you're computing p50/p95/p99 anywhere else, you're duplicating logic that already exists — route
  through one of these instead (see the "never duplicate aggregation logic per format" comment in
  `RollupAggregator`).
- **High-frequency data goes through `OperationRecorder`, never straight to the database.**
  Anything that could fire more than a few times a second (load-generator ops, network pings)
  must go through `OperationRecorder.recordSuccess/recordError` and get rolled up by
  `RollupSnapshotTask`, not persisted per-sample — see [Metrics architecture](#metrics-architecture-internals)
  below for why.
- **Low-frequency data goes through `EventRecorder`, straight to `RawEvent`.** Uptime pings,
  failover transitions, breaker transitions, token renewals, consistency outcomes — anything rare
  enough that its individual timeline matters gets one `EventRecorder.record(...)` call per
  occurrence.
- **Every KPI report DTO is a Java record.** Watch out for derived/computed methods on records
  used as JSON payloads (like `LoadGenConfig.isOpenLoop()`) — Jackson serializes bean-style
  getters on records just like on classes, which broke a real round-trip once (see the
  `@JsonIgnore` on that method and `LoadGenConfigJsonTest` for the regression test it left
  behind). If you add a derived method to a record that gets serialized, ask whether it needs the
  same annotation.

### Metrics architecture internals

- **High-frequency paths** (load generator ops, network-time pings) are never persisted per
  sample — only 1-second rollups (count, error breakdown, min/p50/p95/p99/p99.9/max), via
  `RollupSnapshotTask`. Persisting every raw sample at session-store intensity (tens of thousands
  of ops/sec) would make the harness bottleneck on its own instrumentation. Reports and charts are
  built from rollups, and rollup percentiles are therefore a **weighted-average approximation**
  across windows (min/max are exact) — not a true merged-histogram percentile. This is a
  deliberate, documented tradeoff, not an oversight.
- **Low-frequency paths** persist every raw sample as a `RawEvent`, since these are rare and their
  individual timelines matter.
- **Error taxonomy** (`ErrorCategory`): every failure is classified — connect timeout,
  socket/read timeout, auth failure, circuit-breaker rejection, pool exhaustion, other.
- **Run identity**: every load test gets a run ID; always-on background probes are tagged
  `"background"`. The full effective configuration of a run is persisted with it
  (`Run.configJson`).
- **Warm-up**: load tests exclude a configurable warm-up period (default 30s) from reported
  aggregates (still visible on charts via the rollup's `warmUp` flag).

### Adding a new KPI

1. New package under `kpi/<name>/`.
2. A `*Service` that records via `OperationRecorder` (high-frequency) or `EventRecorder`
   (low-frequency) — never invent a third persistence path.
3. A `*Controller` exposing whatever REST surface makes sense; follow the `from`/`to` query-param
   convention the existing controllers use (`@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)`)
   for consistency.
4. If it needs a chart on the dashboard, add a fetch call in `static/dashboard.html` — it's plain
   JS polling the REST endpoints, no build step.
5. Wire it into `ReportQueryService`/`ReportData` if it should appear in CSV/XLSX/PDF exports.
6. Write both a unit test for the service logic and, if it introduces a new bean with real I/O at
   construction time, extend the mocked-bean list in the integration test.

### Technology choices (and why)

- **Java 21**, Spring Boot 3.5.x, Gradle (wrapper committed, pinned to 9.6.1 — see
  `gradle/wrapper/gradle-wrapper.properties`).
- **Jedis 7.5.3** — virtual threads make simple blocking Jedis calls scale to high concurrency
  without a reactive client, and `MultiDbClient` is the reference implementation of the
  client-side geographic failover pattern Microsoft recommends for AMR active geo-replication.
- `redis-authx-entraid` + `azure-identity` for Entra ID auth.
- **Micrometer + HdrHistogram** for latency capture: HdrHistogram `Recorder`s (keyed by
  run/region/operation) are the source of truth for persisted rollups; Micrometer timers/counters
  (tagged only by `{region, operation, outcome}` — no run ID, to keep cardinality bounded) are an
  always-on live cross-check surfaced at `/actuator/metrics`.
- **H2 in file mode** for rollups, low-frequency raw events, and run metadata
  (`spring.datasource.url` is externalized so it can be swapped for Postgres/Azure SQL later).
- **Apache POI** / **PDFBox** / plain CSV for report export, all fed by `ReportQueryService`.
- Dashboard: a static page (`/dashboard`) + REST API, **Chart.js from CDN**, 5s auto-refresh.

## Guide for DevOps / operators

### Deploying

This build intentionally **does not include Kubernetes/Helm manifests** — those are left for you
to author against your cluster's conventions. What they need to set, based on this app's
requirements:

1. **Container**: build and push the image (`docker build -t <registry>/amr-kpi-harness:<tag> .`
   — multi-stage, `eclipse-temurin:21-jre`, runs as a non-root user, listens on 8080).
2. **ServiceAccount**: reference the platform team's federated-identity-bound ServiceAccount by
   name; do not create a new one from this app's manifests.
3. **Pod labels**: `azure.workload.identity/use: "true"` is required on the pod template, or
   `DefaultAzureCredential` has nothing to resolve (see
   [managed identity auth](#how-entra-id-managed-identity-auth-works) above).
4. **Resource requests/limits**: the load generator is CPU-hungry by design — set real limits or
   an unbounded pod will get evicted mid-test and ruin a run. See
   [Sizing resource requests/limits and JVM flags](#sizing-resource-requestslimits-and-jvm-flags)
   below for concrete numbers and the reasoning behind them.
5. **Probes**: wire `GET /actuator/health/readiness` and `GET /actuator/health/liveness`
   (Spring Boot Actuator probe groups are enabled in `application.yml`).
6. **Graceful shutdown**: `server.shutdown=graceful` is already set with a 45s phase timeout.
   Give the pod a `preStop` sleep (e.g. 5-10s) plus a `terminationGracePeriodSeconds` comfortably
   above that, so an in-flight load test drains and flushes its final rollups instead of losing
   the tail of a run.
7. **Environment variables**: see the full reference table below. At minimum you'll set
   `AMR_CC_HOST` and `AMR_CE_HOST` per environment. `AMR_H2_PATH` should point at a writable
   volume if you want results to survive pod restarts (defaults to `./data/amrkpi`, which is
   ephemeral without one).
8. **Ingress/Service**: expose port 8080; the dashboard and all REST endpoints are unauthenticated
   by design (internal test tool) — put it behind whatever network boundary your cluster expects
   for internal tooling.

### Sizing resource requests/limits and JVM flags

The Dockerfile's `ENTRYPOINT` already sets `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`
with no fixed `-Xmx`, so heap size is derived from the container's cgroup memory **limit** at JVM
startup, not from a hardcoded value. That has one hard consequence for the pod spec: **you must
set an explicit `resources.limits.memory`, not just a request.** If you only set a request and
skip the limit, the container has no cgroup memory ceiling, so the JVM sizes its heap off the
*node's* total memory instead of the pod's — a latent OOM-kill risk the moment the node is
bin-packed with other pods. Leave the existing JVM flags as they are; nothing about this app needs
a fixed `-Xmx` instead, since container-aware sizing is exactly what you want across differently
sized node pools.

Recommended starting point:

| | CPU | Memory |
|---|---|---|
| **Request** | `1` | `1Gi` |
| **Limit** | `2` | `2Gi` |

```yaml
resources:
  requests:
    cpu: "1"
    memory: "1Gi"
  limits:
    cpu: "2"
    memory: "2Gi"
```

Why these numbers, based on what actually drives CPU/memory in this codebase:

- **CPU is the real bottleneck, by design.** The load generator's virtual-thread executor can
  drive concurrency up to 128 (the `session-peak` preset, `PresetService`) against a 64-connection
  pool (`AMR_POOL_MAX_TOTAL`), and every operation sits on the hot path of the very latency numbers
  this harness exists to produce. Under-provisioning CPU doesn't just slow the app down, it
  contaminates the measurement — so give it room to burst (`limits.cpu: 2`) rather than throttling
  it at the request value.
- **Memory pressure is dominated by framework/classloading baseline, not the workload itself.**
  Session payloads top out at 20KB (`AMRKPI_VALUE_SIZE_MAX`), and the HdrHistogram recorders
  (keyed per `runId`/`region`/`operation`, 3 significant digits, 60s trackable range — see
  `OperationRecorder`) add only a few MB total even across a 2-hour `soak` preset. The real weight
  is Spring MVC + JPA/Hibernate + Thymeleaf + Actuator + the Azure Identity SDK + Apache POI +
  PDFBox + Jedis + Resilience4j all resident at once.
- **Report export is the one place memory can spike.** `poi-ooxml`'s `XSSFWorkbook` builds the
  whole workbook in memory (not the streaming `SXSSF` variant), so a PDF/XLSX export (`GET
  /reports/export`) covering a long soak run's full rollup history can transiently push well above
  steady-state. The 2Gi limit (→ a ~1.5Gi heap ceiling at 75%) exists mainly to give that spike
  headroom, not for steady-state load-test traffic.
- **At a 1Gi limit, heap is only ~768Mi**, leaving ~256Mi for metaspace, thread stacks, direct
  buffers, and JVM native overhead — survivable at idle but thin the moment a report export lands
  on top of an active run. That's why the limit is set above the request rather than equal to it.

If a run being evicted mid-test is worse for you than the cost of a slightly oversized pod, set
`limits` equal to `requests` (`1` CPU / `1Gi`) instead — that buys Kubernetes' Guaranteed QoS
class (lowest priority for eviction under node memory pressure) at the cost of losing the
CPU/memory burst headroom above. Whichever you pick, validate it: run the `session-peak` preset
and a PDF/XLSX export concurrently and watch `kubectl top pod`, then adjust. These numbers are
sized from reading the code's actual resource-usage patterns, not from a profiled run against a
real AMR instance.

### Environment variable reference

Every `amr.*` / `amrkpi.*` value in `application.yml` is env-var overridable. The ones you're
most likely to actually touch:

| Env var | Default | Controls |
|---|---|---|
| `AMR_CC_HOST` / `AMR_CE_HOST` | (none — required) | The two AMR endpoint hostnames. |
| `AMR_PORT` | `10000` | AMR port (same for both endpoints). |
| `AMR_TOKEN_SCOPE` | `https://redis.azure.com/.default` | Entra ID token scope — flip this first if you see `WRONGPASS` with correct RBAC. |
| `AMR_H2_PATH` | `./data/amrkpi` | H2 database file path — point at a persistent volume in production. |
| `AMR_POOL_MAX_TOTAL` | `64` | Main connection pool size (load generator + cache-aside). |
| `AMR_CONNECT_TIMEOUT_MS` / `AMR_SOCKET_TIMEOUT_MS` | `2000` / `1000` | Connection/socket timeouts. |
| `AMR_RETRY_MAX_ATTEMPTS` | `3` | Command retry attempts before giving up. |
| `AMR_CB_FAILURE_RATE_THRESHOLD` | `10.0` | Circuit breaker trip threshold (%). |
| `AMR_FAILBACK_GRACE_MS` | `60000` | How long after a recovered endpoint is healthy before failing back to it. |
| `AMRKPI_ROLLUP_INTERVAL_S` | `1` | Rollup snapshot interval for high-frequency metrics. |
| `AMRKPI_WARMUP_SECONDS` | `30` | Warm-up period excluded from load-test aggregates. |
| `AMRKPI_TOKEN_LIFETIME_SECONDS` | `3600` | Assumed Entra ID token lifetime — sizes the `soak` preset's duration (2×). |

Every other property in `application.yml` follows the same `${ENV_VAR:default}` pattern — read
that file directly for the complete set (pool/socket/retry/breaker/health-check tuning, session
workload defaults, probe cadence, consistency-test windows).

### Operational runbook

**Pod won't start / CrashLoopBackOff.** Check logs for the `ChainedTokenCredential` attempt
sequence (see [why you actually need az login](#why-you-actually-need-az-login-not-optional) for
the equivalent local symptom). This means the very first Entra ID token acquisition failed
entirely — check workload-identity federation and the ServiceAccount annotation first, before
assuming anything about AMR itself. This fail-fast behavior is intentional: a broken auth
prerequisite should be loud, not a silently degraded pod.

**Pod starts but circuit breaker never goes `CLOSED` / uptime stays low.** Startup succeeded (so
Entra ID auth resolved a token), but AMR itself is rejecting or unreachable. Check for `WRONGPASS`
in the logs specifically — if present, it's the [token scope pitfall](#token-scope-pitfall), not
an RBAC problem, and is a one-property fix (`AMR_TOKEN_SCOPE`). If it's a connection error
instead, check network policy / firewall rules between the pod and the AMR endpoints, and confirm
`amr.clustering-policy` matches what the AMR instances actually run.

**A load test won't stop.** `POST /loadgen/stop` sets a flag the workers check every operation
(sub-millisecond loop iteration), so it should stop within a second or two even under heavy load.
If it doesn't, check `GET /loadgen/status` — the harness only ever runs one load test at a time,
so if `running: true` persists after a stop request, something in the run's op loop is blocked
(most likely a hung Redis call — check `amr.socket.socket-timeout-millis` isn't set too high).

**Presets `failover-under-load` or `soak` seem to hang.** They don't — both are long-running by
design (the failover preset waits 120s before inducing failure and 60s after before restoring;
`soak` runs for 2× the assumed token lifetime, ~2 hours by default). Check `GET /loadgen/status`
for `achievedOpsPerSec` to confirm the run is actually progressing, not stalled.

**Disk fills up in `AMR_H2_PATH`.** Long-running deployments with `soak`-style presets running
repeatedly will accumulate rollup/event history in H2 indefinitely — there's no built-in
retention/pruning in this version. Either point `AMR_H2_PATH` at a volume sized for your retention
needs, or periodically export what you need via `/reports/export` and reset the volume.

**Monitoring beyond the dashboard.** `/actuator/health` (liveness/readiness probe groups) and
`/actuator/metrics` (Micrometer cross-check, tagged by `{region, operation, outcome}`) are both
live and unauthenticated, suitable for wiring into whatever the cluster's standard scraping setup
is, even though this app doesn't require Prometheus/Grafana to be useful on its own.

## Known risks and simplifications

- **`redis-authx-entraid` is beta** — see [above](#redis-authx-entraid-is-beta).
- **Token scope pitfall** — see [above](#token-scope-pitfall).
- **Replication lag has no SLA.** Every cross-region timing figure in this app (KPI 2, KPI 9) is
  an empirically measured, timestamped sample — nothing in the code assumes a fixed lag.
- **Rollup percentiles are a weighted-average approximation** across 1-second windows (min/max
  are exact), not a true merged-histogram percentile — see
  [Metrics architecture internals](#metrics-architecture-internals).
- **`/reports/export`'s `kpis=` filter param is accepted but not yet applied** — every export
  currently includes the full report. Scoping it down is a natural, contained follow-up.
- **Exported reports (CSV/XLSX/PDF) are data-complete tables, not rendered charts.** Visual
  charts live on the live dashboard (Chart.js); chart-image embedding in exports was out of scope
  for this pass.
- **Cache-aside's source-of-truth is an H2-backed stand-in**, not a real downstream dependency —
  it exists purely to demonstrate a genuine miss-populate cycle and the degraded-mode latency
  comparison (see `SourceOfTruthService`).
- **No built-in data retention/pruning** — see the disk-fills-up runbook entry above.
