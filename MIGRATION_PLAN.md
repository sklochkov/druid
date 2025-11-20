# Netty 3 to Netty 4.1 Migration Plan

## Objective
Migrate all Netty 3 (`org.jboss.netty.*`) usages to Netty 4.1 (`io.netty.*`) and upgrade Netty 4 version to `4.1.128.Final`.

## Current State
- **Netty 3 Version:** 3.10.6.Final (`io.netty:netty`)
- **Netty 4 Version:** 4.1.128.Final (Upgraded from 4.1.108.Final)
- **Affected Modules:**
  - `processing` (Core HttpClient implementation)
  - `server` (Druid clients, HTTP handling)
  - `services` (CoordinatorRuleManager, PullDependencies)
  - `sql` (SystemSchema)
  - `indexing-service` (WorkerTaskManager, RemoteTaskRunner, SeekableStreamIndexTaskClientAsyncImpl)
  - `extensions-core/multi-stage-query` (WorkerClient, DataServerSelector)
  - `extensions-core/druid-kerberos` (KerberosHttpClient)
  - `extensions-core/druid-basic-security`
  - `extensions-contrib/kubernetes-overlord-extensions`
  - `integration-tests` & `integration-tests-ex`

## Migration Steps

### 1. Dependency Updates
- [x] Update `pom.xml` `netty4.version` to `4.1.128.Final`.
- [ ] Add Netty 4 dependencies (`netty-all` or specific modules like `netty-handler`, `netty-codec-http`) to modules currently depending on Netty 3.
- [ ] Eventually remove `netty3.version` and `io.netty:netty` dependency.

### 2. Core HTTP Client Migration (`processing` module)
This is the most complex part. The custom `HttpClient` is built on Netty 3.
- **Package:** `org.apache.druid.java.util.http.client`
- **Classes to Refactor:**
  - `NettyHttpClient`: Switch from `ClientBootstrap` to `Bootstrap`. Use `NioEventLoopGroup`. Replace `SimpleChannelUpstreamHandler` with `ChannelInboundHandlerAdapter`.
  - `ChannelResourceFactory`: Update connection logic, SSL handling (`SslContextBuilder`), and proxy handling.
  - `HttpClientPipelineFactory` (in `netty` subpackage): Update pipeline configuration.
  - `Request`: Change `ChannelBuffer` content to `ByteBuf`.
  - `ResponseHandler` interfaces/implementations: Update to accept Netty 4 `HttpResponse` and `HttpContent`.

**Key API Changes:**
- `org.jboss.netty.buffer.ChannelBuffer` -> `io.netty.buffer.ByteBuf`
- `org.jboss.netty.channel.ChannelPipeline` -> `io.netty.channel.ChannelPipeline`
- `org.jboss.netty.handler.codec.http.HttpRequest` -> `io.netty.handler.codec.http.HttpRequest` (and `FullHttpRequest`)
- `org.jboss.netty.handler.codec.http.HttpResponse` -> `io.netty.handler.codec.http.HttpResponse` (and `FullHttpResponse`)
- `org.jboss.netty.handler.codec.http.HttpHeaders` -> `io.netty.handler.codec.http.HttpHeaders`
- `org.jboss.netty.bootstrap.ClientBootstrap` -> `io.netty.bootstrap.Bootstrap`

### 3. Server & Extension Module Migration
- Update imports in `DirectDruidClient`, `DruidLeaderClient`, `BrokerClient`, etc.
- Replace `org.jboss.netty.handler.codec.http.HttpMethod` with `io.netty.handler.codec.http.HttpMethod`.
- Replace `org.jboss.netty.handler.codec.http.HttpResponseStatus` with `io.netty.handler.codec.http.HttpResponseStatus`.
- Update any direct channel handling code.
- `KerberosHttpClient`: Update to wrap the new Netty 4 based HttpClient or handle Netty 4 objects.
- `SeekableStreamIndexTaskClientAsyncImpl`: Update usage of Netty classes.

### 4. Test Migration
- Update all tests using Netty 3 classes.
- Use `EmbeddedChannel` (Netty 4) for testing handlers if applicable.
- Mock Netty 4 objects instead of Netty 3 objects.

### 5. Cleanup
- Remove all `org.jboss.netty` imports.
- Remove `io.netty:netty` (Netty 3) from `pom.xml`.
- Verify no Netty 3 jars remain in the build.

## Specific Challenges
- **Event Model:** Netty 4 has a different event model (inbound/outbound handlers). `SimpleChannelUpstreamHandler` logic needs to be adapted to `ChannelInboundHandler`.
- **Threading:** `ChannelFactory` is replaced by `EventLoopGroup`. Lifecycle management of these groups needs to be ensured.
- **Memory Management:** Netty 4 uses reference counting for `ByteBuf`. We must ensure buffers are released to avoid leaks. Netty 3 `ChannelBuffer` relied more on GC (though it had some pooling).
