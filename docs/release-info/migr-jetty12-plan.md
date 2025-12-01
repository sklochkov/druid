---
id: migr-jetty12-plan
title: "Migration plan: Jetty 12.1 upgrade"
sidebar_label: Jetty 12.1 plan
---

<!--
  ~ Licensed to the Apache Software Foundation (ASF) under one
  ~ or more contributor license agreements.  See the NOTICE file
  ~ distributed with this work for additional information
  ~ regarding copyright ownership.  The ASF licenses this file
  ~ to you under the Apache License, Version 2.0 (the
  ~ "License"); you may not use this file except in compliance
  ~ with the License.  You may obtain a copy of the License at
  ~
  ~   http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing,
  ~ software distributed under the License is distributed on an
  ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  ~ KIND, either express or implied.  See the License for the
  ~ specific language governing permissions and limitations
  ~ under the License.
-->

This document captures the work items to upgrade Apache Druid from Jetty 9.4.57 to the Jetty 12.1 (EE10) line.
Jetty 12 requires Jakarta namespaces, so the plan covers both dependency changes and the servlet/JAX-RS stack migration.

## Goals
- Adopt Jetty 12.1.x (EE10) for supported security posture and continued fixes.
- Keep embedded server, HTTP client, and proxying features functionally equivalent.
- Preserve operator-facing configuration flags and metrics semantics.
- Maintain compatibility for extensions and integration tests that embed Jetty.

## Scope and dependencies
- Replace `javax.servlet` with `jakarta.servlet` 6.0 and `javax.ws.rs` with `jakarta.ws.rs` 3.x across core, services, indexing-service, processing, sql, MSQ, security extensions (kerberos, pac4j, ranger), and integration tests/mocks.
- Move REST stack off Jersey 1 (`com.sun.jersey.*`) to a Jakarta-capable stack (Jersey 3.x + HK2/Guice bridge or an alternative approach that preserves current Guice bindings and resource discovery).
- Align third-party libraries that rely on the servlet/JAX-RS APIs (pac4j, ranger, kerb, avatica, Hadoop HTTP auth helpers, etc.) to Jakarta-ready versions with matching transitive deps.
- Update documentation that references Jetty versions and TLS guidance where the upstream doc URLs change.

## Dependency changes
- Set a Jetty 12.1.x property and import the Jetty 12 BOM.
- Swap Jetty artifacts to EE10 variants where needed:
  - `org.eclipse.jetty.ee10:jetty-ee10-servlet`, `jetty-ee10-servlets`, `jetty-ee10-proxy`, `jetty-ee10-rewrite`, `jetty-ee10-webapp` for servlet/proxy/rewrite usage.
  - Keep core modules (`jetty-server`, `jetty-http`, `jetty-io`, `jetty-util`, `jetty-security`, `jetty-client`) on 12.1 coordinates.
- Replace `javax.servlet:javax.servlet-api` with `jakarta.servlet:jakarta.servlet-api` and `javax.ws.rs` artifacts with `jakarta.ws.rs:jakarta.ws.rs-api`.
- Adjust exclusions to avoid mixed javax/jakarta transitive pulls.
- Update `licenses.yaml` to reflect new Jetty and Jakarta artifacts.

## Code migration
- Update imports and types:
  - `ServletContextHandler`, `ServletHolder`, `FilterHolder`, `FilterMapping`, `DefaultServlet`, `QoSFilter` move to `org.eclipse.jetty.ee10.servlet` (and `org.eclipse.jetty.ee10.servlets` for QoS).
  - Proxy classes move to `org.eclipse.jetty.ee10.proxy` (affects `AsyncQueryForwardingServlet`, `AsyncManagementForwardingServlet`, `OverlordProxyServlet`, related tests, and `StandardResponseHeaderFilterHolder.deduplicateHeadersInProxyServlet`).
  - Rewrite helpers move to `org.eclipse.jetty.ee10.rewrite`.
- Replace all `javax.servlet.*` and `javax.ws.rs.*` references (including test mocks in `server/src/test/java/org/apache/druid/server/mocks`, integration harnesses, and extensions) with Jakarta namespaces; update method signatures where they differ.
- Move off `org.eclipse.jetty.util.ConcurrentHashSet` to `ConcurrentHashMap.newKeySet()` if required (Jetty 12 removes deprecated classes).
- Review TLS pieces (`SslContextFactory.Server/Client`, `KeyStoreScanner`) for API shifts; update `JettyServerModule`, `JettyHttpClientModule`, and tests.
- Revisit request logging (`JettyRequestLog`) and connection monitoring (`JettyMonitoringConnectionFactory`) for interface changes.
- Preserve servlet init parameters used today (`Default.dirAllowed`, `Default.redirectWelcome`, QoS `suspendMs`) and verify equivalent configuration points in Jetty 12.

## REST stack migration
- Replace `com.sun.jersey.guice.JerseyServletModule` usage with a Jakarta-compatible solution (Jersey 3 or alternative) while keeping Guice bindings for resources, filters, and exception mappers.
- Update providers (`JacksonJsonProvider`, `JacksonSmileProvider`) to Jakarta variants.
- Adjust any Jersey-specific annotations/config that changed between 1.x and 3.x.
- Revalidate CORS/auth filters that depend on servlet/JAX-RS types.

## Tests and tooling
- Update embedded Jetty usages in tests (`server/src/test/java/...JettyTest`, `JettyQosTest`, `BaseJettyTest`, `processing/...FriendlyServersTest`, integration test harnesses under `integration-tests` and `integration-tests-ex`) to Jetty 12 EE10 APIs.
- Refresh mock servlet/request/response helpers to Jakarta interfaces.
- Run and stabilize full test suites (unit, integration, AVRO/ORC/MSQ, security, SQL) after migration.

## Validation and rollout
- Functional verification: router/broker query forwarding, async query cancel, management proxying, web console static assets, TLS (including certificate reload), HSTS headers, QoS throttling, gzip, request logging, and forwarded header handling.
- Performance regression check on Jetty thread pools, connection handling, and gzip behavior.
- Compatibility sign-off from security extensions (kerberos, pac4j, ranger) and Hadoop/Avatica integrations.
- Update operator docs to point to Jetty 12 references; add upgrade notes covering namespace and dependency changes.

## Open questions
- Final choice of Jakarta REST + Guice integration strategy (native Jersey 3 Guice bridge vs. lightweight custom bridge).
- Any extensions that hard-depend on javax-only libraries may need separate upgrade or exclusion.
