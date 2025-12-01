# Jetty 9.4 → Jetty 12.1 Migration Plan

## Objective
Upgrade Jetty from 9.4.57.v20241219 (EOL) to Jetty 12.1.x (EE10), including servlet/JAX-RS namespace migration to Jakarta and aligning the embedded server, HTTP client, and proxy usage across Apache Druid.

## Current State
- **Jetty Version:** 9.4.57.v20241219
- **Servlet API:** `javax.servlet` (3.1/4)
- **JAX-RS API:** `javax.ws.rs` (Jersey 1.x, Guice module)
- **Jetty Usage:** Embedded servers (all services), Jetty HTTP client, proxy servlets, rewrite/QoS/gzip handlers, TLS reload, HSTS, request logging
- **Major Entry Points:**
  - `server/src/main/java/org/apache/druid/server/initialization/jetty/*`
  - `services/src/main/java/org/apache/druid/cli/*JettyServerInitializer*`
  - Proxying: `AsyncQueryForwardingServlet`, `AsyncManagementForwardingServlet`, `OverlordProxyServlet`
  - HTTP client: `JettyHttpClientModule`
  - Tests/harness: `server/src/test/...Jetty*`, `processing/...FriendlyServersTest`, integration tests

## Affected Modules
- **Core:** `server`, `services`, `processing` (Jetty client tests), `sql`
- **Indexing:** `indexing-service`
- **Extensions-Core:** kerberos, pac4j, ranger, MSQ, catalog (servlet/JAX-RS usage)
- **Extensions-Contrib:** iceberg, kubernetes-overlord-extensions (if Jetty/servlet references)
- **Integration:** `integration-tests`, `integration-tests-ex`, test mocks under `server/src/test/java/org/apache/druid/server/mocks`
- **Docs:** TLS/Jetty references in `docs/operations`, `docs/configuration`, release notes

## Migration Steps

### 1) Dependencies
- [ ] Set `jetty.version` to 12.1.x and import the Jetty 12 BOM.
- [ ] Swap artifacts to EE10 variants where required:
  - `org.eclipse.jetty.ee10:jetty-ee10-servlet`, `jetty-ee10-servlets` (QoS), `jetty-ee10-proxy`, `jetty-ee10-rewrite`, `jetty-ee10-webapp`
  - Keep core modules (`jetty-server`, `jetty-http`, `jetty-io`, `jetty-util`, `jetty-security`, `jetty-client`) at 12.1.x coordinates.
- [ ] Replace `javax.servlet:javax.servlet-api` with `jakarta.servlet:jakarta.servlet-api` (6.0).
- [ ] Replace `javax.ws.rs` artifacts with `jakarta.ws.rs:jakarta.ws.rs-api` (3.x).
- [ ] Update all module POMs (root, server, services, sql, processing, indexing-service, extensions, integration-tests) and clean exclusions to avoid mixed javax/jakarta.
- [ ] Update `licenses.yaml` for new Jetty/Jakarta artifacts.

### 2) Server & API Migration (Jakarta)
- [ ] Replace all `javax.servlet.*` imports/usages with `jakarta.servlet.*` across main and tests (including mocks).
- [ ] Replace all `javax.ws.rs.*` imports/usages with `jakarta.ws.rs.*`.
- [ ] Update Jersey stack: move off Jersey 1 (`com.sun.jersey.guice.JerseyServletModule`) to a Jakarta-compatible stack (e.g., Jersey 3 + HK2/Guice bridge) while preserving Guice bindings, exception mappers, and filters.
- [ ] Update providers to Jakarta (`JacksonJsonProvider`, `JacksonSmileProvider`).

### 3) Jetty API Changes
- [ ] Update servlet/rewrite/proxy/QoS imports:
  - `ServletContextHandler`, `ServletHolder`, `FilterHolder`, `FilterMapping`, `DefaultServlet` → `org.eclipse.jetty.ee10.servlet.*`
  - `QoSFilter` → `org.eclipse.jetty.ee10.servlets.QoSFilter`
  - Proxy classes → `org.eclipse.jetty.ee10.proxy`
  - Rewrite handlers → `org.eclipse.jetty.ee10.rewrite`
- [ ] Revisit TLS pieces (`SslContextFactory.Server/Client`, `KeyStoreScanner`) for signature changes; adjust `JettyServerModule`, `JettyHttpClientModule`, and TLS tests.
- [ ] Validate request logging (`JettyRequestLog`) and connection monitoring (`JettyMonitoringConnectionFactory`) against Jetty 12 interfaces.
- [ ] Preserve/init parameters: `Default.dirAllowed`, `Default.redirectWelcome`, QoS `suspendMs`, gzip settings, HSTS rules.

### 4) Third-Party Compatibility
- [ ] Bump servlet/JAX-RS–aware dependencies to Jakarta-ready versions (pac4j, ranger, kerberos, Avatica, Hadoop HTTP auth helpers, web console static handling if needed).
- [ ] Review transitive Jetty/servlet pulls to avoid downgrades or javax leakage.

### 5) Tests & Tooling
- [ ] Update embedded Jetty usages in tests (`JettyTest`, `JettyQosTest`, `BaseJettyTest`, `FriendlyServersTest`, integration harnesses) to EE10 imports.
- [ ] Update servlet/request/response mocks to Jakarta types.
- [ ] Adjust proxy-related tests for EE10 proxy packages.
- [ ] Run module-by-module test suites; fix compilation and behavioral regressions.

### 6) Documentation & Release Notes
- [ ] Refresh TLS/Jetty references in `docs/operations` and `docs/configuration` to Jetty 12 links.
- [ ] Add release/upgrade notes describing the Jakarta namespace change and dependency updates.

### 7) Final Cleanup & Verification
- [ ] Ensure no `javax.servlet` or Jetty 9 artifacts remain in dependency trees.
- [ ] Verify runtime behavior: router/broker forwarding, async cancel, management proxying, web console static assets, TLS (including reload), HSTS headers, QoS throttling, gzip, request logging, forwarded headers.
- [ ] Performance sanity check on Jetty thread pools and gzip behavior.
- [ ] Stage → production rollout plan with monitoring for Jetty/servlet errors.

## Specific Challenges / Risks
- Replacing Jersey 1 with a Jakarta-capable stack while retaining Guice integration and existing filters.
- Ensuring extensions (kerberos, pac4j, ranger) have Jakarta-compatible versions; otherwise, they may block the upgrade.
- Avoiding mixed javax/jakarta classloading from third-party transitive dependencies.
- Adjusting proxy servlet behavior if Jetty 12 changes header/cookie handling defaults.

## Progress Tracking
Use the existing pattern: record completed phases in `MIGRATION_PROGRESS.md`, current state in `MIGRATION_STATUS.md`, and finalize in `MIGRATION_FINAL_SUMMARY.md` once Jetty 12 is fully integrated.
