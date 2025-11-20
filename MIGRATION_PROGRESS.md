# Netty 3 to Netty 4 Migration - Progress Report

## Completed Work

### Phase 1: Processing Module ✅
**Status:** Complete and tested

**Files Migrated:**
1. **Core HTTP Client:**
   - `NettyHttpClient.java` - Main HTTP client, migrated from Netty 3 to Netty 4 event model
   - `HttpClientInit.java` - Bootstrap initialization using `NioEventLoopGroup` instead of `NioClientBossPool`/`NioWorkerPool`
   - `Request.java` - Changed from `ChannelBuffer` to `ByteBuf`, updated HTTP headers API
   - `ChannelResourceFactory.java` - Updated from `ClientBootstrap` to `Bootstrap`, SSL handling updated
   - `HttpClientPipelineFactory.java` - Changed from `ChannelPipelineFactory` to `ChannelInitializer<SocketChannel>`

2. **Response Handlers:**
   - `HttpResponseHandler.java` - Updated interface signature to use `HttpContent` instead of `HttpChunk`
   - `BytesFullResponseHandler.java` - Updated to work with `ByteBuf`
   - `InputStreamFullResponseHandler.java` - Updated to work with Netty 4
   - `InputStreamResponseHandler.java` - Updated to work with Netty 4
   - `SequenceInputStreamResponseHandler.java` - Added `ByteBuf.retain()` calls for proper reference counting
   - `ObjectOrErrorResponseHandler.java` - Updated imports
   - `StatusResponseHandler.java` - Updated to use `HttpContent`
   - Various `*ResponseHolder.java` classes - Updated to Netty 4 APIs

3. **Utilities:**
   - `AppendableByteArrayInputStream.java` - Fixed single byte read to return unsigned value

4. **Test Files:**
   - `BytesFullResponseHolderTest.java`
   - `InputStreamFullResponseHandlerTest.java`
   - `SequenceInputStreamResponseHandlerTest.java`
   - `ObjectOrErrorResponseHandlerTest.java`
   - `FriendlyServersTest.java`
   - `JankyServersTest.java`
   - `FrameFileHttpResponseHandlerTest.java`

**Dependencies:**
- ✅ Removed `io.netty:netty` (Netty 3) from processing/pom.xml
- ✅ Added `netty-handler` and `netty-transport` Netty 4 modules

**Tests:** Passing ✅

### Phase 2: Server Module ✅
**Status:** Complete and compiling

**Files Migrated:**
1. **RPC Framework:**
   - `RequestBuilder.java` - Updated `HttpMethod` import
   - `ServiceClientImpl.java` - Updated `HttpResponseStatus` import
   - `ServiceRetryPolicy.java` - Updated interface to use Netty 4 `HttpResponse`
   - `StandardRetryPolicy.java` - Updated retry logic to use `status().code()` API
   - `SpecificTaskRetryPolicy.java` - Updated HTTP status checks
   - `IgnoreHttpResponseHandler.java` - Changed `HttpChunk` to `HttpContent`

2. **Client Implementations:**
   - `DirectDruidClient.java` - Already using Netty 4 (needed updated processing jar)
   - `BrokerClient.java` - Updated imports
   - `DataServerClient.java` - Updated imports
   - `DataServerResponseHandler.java` - Fixed indentation
   - `DruidLeaderClient.java` - Already using Netty 4
   - `CoordinatorClientImpl.java` - Updated imports

3. **Coordinator/Lookup:**
   - `BytesAccumulatingResponseHandler.java` - Updated to use `status().code()` and `reasonPhrase()`
   - `CoordinatorDutyUtils.java` - Updated HTTP status check
   - `HttpLoadQueuePeon.java` - Updated `HttpMethod` and `HttpHeaderNames` imports
   - `LookupCoordinatorManager.java` - Replaced `HttpHeaders.Names.*` with string literals
   - `LookupReferencesManager.java` - Updated imports
   - `ChangeRequestHttpSyncer.java` - Replaced `HttpHeaders.Names.*` with string literals

4. **Messages:**
   - `MessageRelayClientImpl.java` - Updated imports
   - `OverlordClientImpl.java` - Updated imports

**Compilation:** Successful ✅

## Remaining Work

### Phase 3: Other Modules (Pending)
The following modules still have Netty 3 dependencies and need migration:

1. **sql** module
2. **services** module  
3. **indexing-service** module
4. **integration-tests** and **integration-tests-ex**
5. **extensions-core:** 
   - `multi-stage-query`
   - `druid-kerberos`
   - `druid-basic-security`
   - `druid-catalog`
   - `druid-ranger-security`
   - `kafka-indexing-service`
   - `kinesis-indexing-service`
6. **extensions-contrib:**
   - `kubernetes-overlord-extensions`
   - `rabbit-stream-indexing-service`
   - `druid-iceberg-extensions`

### Phase 4: Final Cleanup (Pending)
- Remove `netty3.version` from root pom.xml
- Remove all `io.netty:netty` (Netty 3) exclusions and dependencies
- Verify no Netty 3 jars remain in the build

## Key API Changes Made

| Netty 3 | Netty 4 |
|---------|---------|
| `org.jboss.netty.buffer.ChannelBuffer` | `io.netty.buffer.ByteBuf` |
| `org.jboss.netty.handler.codec.http.HttpChunk` | `io.netty.handler.codec.http.HttpContent` |
| `org.jboss.netty.handler.codec.http.HttpHeaders.Names.*` | String literals or `HttpHeaderNames.*` |
| `response.getStatus().getCode()` | `response.status().code()` |
| `response.getStatus().getReasonPhrase()` | `response.status().reasonPhrase()` |
| `channel.setReadable(true/false)` | `channel.config().setAutoRead(true/false)` |
| `SimpleChannelUpstreamHandler` | `ChannelInboundHandlerAdapter` |
| `ClientBootstrap` | `Bootstrap` |
| `NioClientBossPool`/`NioWorkerPool` | `NioEventLoopGroup` |

## Next Steps
Continue migration of the remaining modules in the order listed above, starting with the most critical ones that directly depend on processing and server modules.




