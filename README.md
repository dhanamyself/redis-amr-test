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

## Prerequisites (platform team owned — not provisioned by this app)

- **RBAC on both AMR instances** for the same managed identity. This app never provisions
  access; someone with Redis Enterprise / AMR admin rights must grant the identity the AMR data
  actions/RBAC role on **both** the Canada Central and Canada East members before this harness
  can authenticate.
- **AKS Workload Identity federation**: the ServiceAccount this app runs under must be annotated
  by the platform team with the federated identity credential, and pods must carry the
  `azure.workload.identity/use: "true"` label. This app authenticates purely via
  `DefaultAzureCredential`, resolving from the env vars the workload-identity mutating webhook
  injects (`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_FEDERATED_TOKEN_FILE`,
  `AZURE_AUTHORITY_HOST`) — there is no client ID, secret, or Redis access key anywhere in this
  app's config or code.
- **Clustering policy check**: confirm whether both AMR instances use the **Enterprise**
  clustering policy (single endpoint, standard client) or **OSS cluster policy**
  (cluster-aware client required) before deploying. This app is built for **Enterprise policy**
  with Jedis's `UnifiedJedis`/`MultiDbClient` by default (`amr.clustering-policy: enterprise` in
  `application.yml`). A policy/client mismatch produces confusing MOVED/connection errors that
  will contaminate every KPI. All members of a geo-replication group must share the same
  clustering policy, so this is a one-time check, not a per-environment one.

## Local-run caveats

`DefaultAzureCredential` will **not** resolve automatically off-cluster. To run this locally
against a real AMR instance you need one of:
- `az login` as a principal with the same AMR RBAC grant (DefaultAzureCredential falls back to
  the Azure CLI credential), or
- a separate local federated-credential/service-principal setup mirroring the AKS workload
  identity, exported as the same env vars the AKS webhook would inject.

Without either, **the app will fail to start at all** — verified directly: `AuthXManager.start()`
(`AmrAuthConfig`) blocks during context startup on an initial token fetch, and if every credential
in `DefaultAzureCredential`'s chain is unavailable, it throws and Spring Boot's context refresh
aborts before Tomcat, the dashboard, or any KPI endpoint comes up. This is standard fail-fast
behavior, not a bug — but it means a broken RBAC grant or missing workload-identity federation in
AKS will show up as a **CrashLoopBackOff**, not a degraded-but-reachable pod. The pod logs will
show the full `ChainedTokenCredential` attempt sequence and the specific failure reason (check
those first if you see a crash loop after deploying). Once workload identity resolves a token
successfully, startup completes normally and the AuthXManager's own background retry/renewal loop
takes over from there (KPI 8 territory) — this fail-fast check only applies to the very first
token acquisition.

## Deploy steps

This build intentionally **does not include Kubernetes/Helm manifests** (Deployment, Service,
etc.) — those are left for you to author against your cluster's conventions. What they need to
set, based on this app's requirements:

1. **Container**: build and push the image (`docker build -t <registry>/amr-kpi-harness:<tag> .`
   — multi-stage, `eclipse-temurin:21-jre`, runs as a non-root user, listens on 8080).
2. **ServiceAccount**: reference the platform team's federated-identity-bound ServiceAccount by
   name; do not create a new one from this app's manifests.
3. **Pod labels**: `azure.workload.identity/use: "true"` is required on the pod template, or
   `DefaultAzureCredential` has nothing to resolve.
4. **Resource requests/limits**: the load generator is CPU-hungry by design — set real limits or
   an unbounded pod will get evicted mid-test and ruin a run. Size for your concurrency/rate
   targets; start with ~1 CPU / 1Gi request as a floor and tune from there.
5. **Probes**: wire `GET /actuator/health/readiness` and `GET /actuator/health/liveness`
   (Spring Boot Actuator probe groups are enabled in `application.yml`).
6. **Graceful shutdown**: `server.shutdown=graceful` is already set with a 45s phase timeout.
   Give the pod a `preStop` sleep (e.g. 5-10s) plus a `terminationGracePeriodSeconds` comfortably
   above that, so an in-flight load test drains and flushes its final rollups instead of losing
   the tail of a run.
7. **Environment variables**: every `amr.*` / `amrkpi.*` value in `application.yml` is env-var
   overridable (see the `${VAR:default}` placeholders) — at minimum you'll set `AMR_CC_HOST` and
   `AMR_CE_HOST` per environment. `AMR_H2_PATH` should point at a writable volume if you want
   results to survive pod restarts (defaults to `./data/amrkpi`, which is ephemeral without one).
8. **Ingress/Service**: expose port 8080; the dashboard and all REST endpoints are unauthenticated
   by design (internal test tool) — put it behind whatever network boundary your cluster expects
   for internal tooling.

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

### Why Jedis 7.5.3, not 6.x

The original design brief for this harness referenced Jedis 6.x's `MultiClusterClientConfig` /
`MultiClusterPooledConnectionProvider`. Jedis 7.0 **renamed and improved** these
(`redis.clients.jedis.MultiDbConfig` / `redis.clients.jedis.MultiDbClient`, with supporting types
in `redis.clients.jedis.mcf`) — same weighted-failover/breaker/health-check capability, new
names. This build targets current stable (7.5.3) with the new API rather than pinning to a
superseded major version. If you're comparing this code against older Jedis blog posts or
samples, that's why the class names won't match — always check the current
`redis.clients.jedis.mcf` package before trusting a class name from an older source; this
library's failover API moved once already and could again.

`MultiDbClient` doesn't expose the underlying Resilience4j `CircuitBreaker` or
`Database.setDisabled()` publicly — this app builds its own `MultiDbConnectionProvider` and
passes it into `MultiDbClient.builder().connectionProvider(...)` so it can retain a handle to it
(`AmrRedisClientConfig`). That handle is what KPI 4 (induced failure via `setDisabled()`) and
KPI 5 (live breaker state via `CircuitBreaker.getState()`) are built on — both real, unmodified
public API, not reflection.

### Known risks

- **`redis-authx-entraid` is beta** (`0.1.1-beta2` as of this build, first published March 2025,
  no GA release since). It's the only supported path for Entra ID token auth + automatic
  re-authentication of pooled connections with Jedis, so this app depends on it regardless — but
  budget time to re-validate against whatever version is current when you actually run this, and
  watch for breaking API changes on upgrade.
- **Token scope pitfall**: some Jedis/managed-identity combinations against AMR fail token
  validation (`WRONGPASS`) with one of `https://redis.azure.com/.default` vs
  `https://redis.azure.com` (no `/.default`) depending on library/credential-type versions, while
  official docs and this app's default both use the `/.default` form. It's a single config
  property (`amr.auth.token-scope` / `AMR_TOKEN_SCOPE` env var) — flip it if you see `WRONGPASS`
  after confirming RBAC is actually correct.
- **Replication lag has no SLA.** Every cross-region timing figure in this app (KPI 2, KPI 9) is
  an empirically measured, timestamped sample — nothing in the code assumes a fixed lag.

## Technology choices

- **Java 21**, Spring Boot 3.5.x (latest 3.x — the brief called for "3.2+"), Maven.
- **Jedis 7.5.3** — virtual threads make simple blocking Jedis calls scale to high concurrency
  without a reactive client, per-operation timing is trivial to capture accurately, and
  `MultiDbClient` is the reference implementation of the client-side geographic failover pattern
  Microsoft recommends for AMR active geo-replication. This app configures and instruments that
  built-in mechanism rather than hand-rolling a parallel one.
- `redis-authx-entraid` + `azure-identity` for Entra ID auth.
- **Micrometer + HdrHistogram** for latency capture: HdrHistogram `Recorder`s (keyed by
  run/region/operation) are the source of truth for persisted rollups; Micrometer timers/counters
  (tagged only by `{region, operation, outcome}` — no run ID, to keep cardinality bounded) are an
  always-on live cross-check surfaced at `/actuator/metrics`.
- **H2 in file mode** for rollups, low-frequency raw events, and run metadata
  (`spring.datasource.url` is externalized so it can be swapped for Postgres/Azure SQL later).
- **Apache POI** / **PDFBox** / plain CSV for report export, all fed by one shared query layer
  (`ReportQueryService`) — see "Known simplifications" below.
- Dashboard: a static page (`/dashboard`) + REST API, **Chart.js from CDN**, 5s auto-refresh.

## Metrics architecture

- **High-frequency paths** (load generator ops, network-time pings) are never persisted per
  sample — only 1-second rollups (count, error breakdown, min/p50/p95/p99/p99.9/max), via
  `RollupSnapshotTask`. Reports and charts are built from rollups.
- **Low-frequency paths** (uptime pings, geo-replication probes, failover events, breaker
  transitions, token renewals, consistency outcomes) persist every raw sample as a `RawEvent`,
  since these are rare and their individual timelines matter.
- **Error taxonomy** (`ErrorCategory`): every failure is classified — connect timeout,
  socket/read timeout, auth failure, circuit-breaker rejection, pool exhaustion, other.
- **Probe isolation**: uptime/network-time/geo-replication probes run on dedicated Jedis
  connections (`ProbeConnectionFactory`), never through the load generator's pool.
- **Run identity**: every load test gets a run ID; always-on background probes are tagged
  `"background"`. The full effective configuration of a run is persisted with it
  (`Run.configJson`).
- **Warm-up**: load tests exclude a configurable warm-up period (default 30s) from reported
  aggregates (still visible on charts via the rollup's `warmUp` flag).

## The nine KPIs

| # | KPI | Endpoints |
|---|-----|-----------|
| 1 | Uptime | `GET /kpi/uptime?region=&from=&to=` |
| 2 | Geo-replication time | `POST /kpi/geo-replication-time/run`, `GET /kpi/geo-replication-time?direction=` |
| 3 | Throughput (load generator) | `POST /loadgen/start`, `GET /loadgen/status`, `POST /loadgen/stop`, `GET /kpi/throughput/{runId}` |
| 4 | Failover ability | `POST /kpi/failover/induce`, `POST /kpi/failover/restore`, `GET /kpi/failover/report?region=` |
| 5 | Circuit breaker | `GET /kpi/circuit-breaker/status`, `GET /kpi/circuit-breaker/report?region=` |
| 6 | Cache-aside / read-miss | `GET /kpi/cache-aside/get?key=`, `POST /kpi/cache-aside/run`, `GET /kpi/cache-aside/report` |
| 7 | Network time | `GET /kpi/network-time?region=` |
| 8 | Token lifecycle | `GET /kpi/token-lifecycle?runId=&windowSeconds=` |
| 9 | Consistency & conflict | `POST /kpi/consistency/staleness`, `POST /kpi/consistency/conflict`, `GET /kpi/consistency/report` |

### KPI 4 — what "controlled failure simulation" actually does here

Application code cannot take a real AMR instance down. This harness forces the targeted endpoint
unhealthy via `MultiDbConnectionProvider.Database.setDisabled(true)` — real, public Jedis API,
not a fake unreachable host — which drives the client through the same failover-selection path a
genuine health-check failure would. `restore()` flips it back and the built-in failback grace
period (`amr.failback.grace-period-millis`) governs when the client actually switches back.

Two **real, non-simulated** tests the infra team can drive while this harness's uptime/failover
instrumentation (KPI 1, KPI 4, KPI 5) measures them:
1. An **Azure-initiated reboot/maintenance event** on one AMR member.
2. A **region-level exercise via force-unlink** of the geo-replication link.

### KPI 9 — architectural conclusion

Active geo-replication is eventually consistent and resolves concurrent writes via CRDT conflict
resolution — for opaque session blobs, that means one concurrent write wins and the other's data
is silently gone. The staleness and conflict tests quantify that risk window. The conclusion this
should inform: prefer **region-affinity** for session writes (a given session's writes go to one
region, the other is failover-only) — exactly the behavior the weighted client-side failover in
KPI 4 already produces. This test suite quantifies the risk window when affinity is broken during
a failover.

## Test presets

One-click via `POST /presets/{name}/run`, or the buttons on `/dashboard`:

| Preset | What it does |
|---|---|
| `smoke` | 60s light load (concurrency 8) + one geo-replication round-trip + one staleness/conflict probe, all sanity-checking every KPI quickly. |
| `session-peak` | 15-minute closed-loop run at concurrency 128, full session workload shape. |
| `failover-under-load` | `session-peak`, plus the local region forced unhealthy 120s in and restored 60s later — check `GET /kpi/failover/report` afterward. |
| `soak` | Moderate load (concurrency 24) for `2 × amrkpi.token-lifecycle.assumed-token-lifetime-seconds` (default 2 hours) — the point is spanning at least one real Entra ID token renewal under sustained load; check `GET /kpi/token-lifecycle?runId=` afterward. |
| `consistency-probe` | Runs the KPI 9 suite. Automatically runs "under load" if a load test is currently active, otherwise idle. |

## Known simplifications (read before trusting a number)

- **Rollup percentiles are a weighted-average approximation** across 1-second windows (min/max
  are exact), not a true merged-histogram percentile — persisting raw per-op samples at
  session-store intensity would make the harness bottleneck on its own instrumentation, so this
  is the explicit tradeoff the Metrics architecture spec calls for.
- **`/reports/export`'s `kpis=` filter param is accepted but not yet applied** — every export
  currently includes the full report. Scoping it down is a natural, contained follow-up.
- **Exported reports (CSV/XLSX/PDF) are data-complete tables, not rendered charts.** Visual
  charts live on the live dashboard (Chart.js); building chart-image embedding into the exports
  was out of scope for this pass.
- **Cache-aside's source-of-truth is an H2-backed stand-in**, not a real downstream dependency —
  it exists purely to demonstrate a genuine miss-populate cycle and the degraded-mode latency
  comparison (see `SourceOfTruthService`).

## First 15 minutes

1. **Build & run** (locally, with `az login` completed against an RBAC'd identity — see
   "Local-run caveats"):
   ```bash
   AMR_CC_HOST=<your-cc-host> AMR_CE_HOST=<your-ce-host> ./mvnw spring-boot:run
   ```
2. Open **`http://localhost:8080/dashboard`** — you should see both regions' circuit breaker
   state as `CLOSED` and uptime climbing toward 100% within the first uptime-probe interval (5s).
3. Click **"Run smoke"**. It returns a `runId` immediately and finishes in under a minute; refresh
   the dashboard to watch uptime, network time, geo-replication lag, and the consistency table
   populate.
4. Click **"Export PDF"** (or hit `GET /reports/export?format=pdf`) to download a point-in-time
   report covering everything the dashboard shows, plus the full effective run configuration —
   this is what you'd attach to a KPI sign-off.
