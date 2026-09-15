/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.server.coordination;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ForwardingListenableFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.apache.druid.java.util.common.RE;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.java.util.http.client.Request;
import org.apache.druid.java.util.http.client.response.HttpResponseHandler;
import org.apache.druid.segment.TestHelper;
import org.apache.druid.testing.junit.LoggerCaptureRule;
import org.easymock.EasyMock;
import org.joda.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 */
public class ChangeRequestHttpSyncerTest
{
  @Rule
  public final LoggerCaptureRule abstractFutureLogger = new LoggerCaptureRule(AbstractFuture.class);

  @Test(timeout = 60_000L)
  public void testSimple() throws Exception
  {
    ObjectMapper jsonMapper = TestHelper.makeJsonMapper();
    TypeReference<ChangeRequestsSnapshot<String>> typeRef = new TypeReference<ChangeRequestsSnapshot<String>>()
    {
    };

    TestHttpClient httpClient = new TestHttpClient(
        ImmutableList.of(
            Futures.immediateFuture(
                new ByteArrayInputStream(
                    jsonMapper.writerWithType(typeRef).writeValueAsBytes(
                        new ChangeRequestsSnapshot(
                            false,
                            null,
                            ChangeRequestHistory.Counter.ZERO,
                            ImmutableList.of("s1")
                        )
                    )
                )
            ),
            Futures.immediateFuture(
                new ByteArrayInputStream(
                    jsonMapper.writerWithType(typeRef).writeValueAsBytes(
                        new ChangeRequestsSnapshot(
                            false,
                            null,
                            ChangeRequestHistory.Counter.ZERO,
                            ImmutableList.of("s2")
                        )
                    )
                )
            ),
            Futures.immediateFuture(
                new ByteArrayInputStream(
                    jsonMapper.writerWithType(typeRef).writeValueAsBytes(
                        new ChangeRequestsSnapshot(
                            true,
                            "reset the counter",
                            ChangeRequestHistory.Counter.ZERO,
                            ImmutableList.of()
                        )
                    )
                )
            ),
            Futures.immediateFuture(
                new ByteArrayInputStream(
                    jsonMapper.writerWithType(typeRef).writeValueAsBytes(
                        new ChangeRequestsSnapshot(
                            false,
                            null,
                            ChangeRequestHistory.Counter.ZERO,
                            ImmutableList.of("s3")
                        )
                    )
                )
            ),
            Futures.immediateFuture(
                new ByteArrayInputStream(
                    jsonMapper.writerWithType(typeRef).writeValueAsBytes(
                        new ChangeRequestsSnapshot(
                            false,
                            null,
                            ChangeRequestHistory.Counter.ZERO,
                            ImmutableList.of("s4")
                        )
                    )
                )
            )
        )
    );

    ChangeRequestHttpSyncer.Listener<String> listener = EasyMock.mock(ChangeRequestHttpSyncer.Listener.class);
    listener.fullSync(ImmutableList.of("s1"));
    listener.deltaSync(ImmutableList.of("s2"));
    listener.fullSync(ImmutableList.of("s3"));
    listener.deltaSync(ImmutableList.of("s4"));
    EasyMock.replay(listener);

    ChangeRequestHttpSyncer<String> syncer = new ChangeRequestHttpSyncer<>(
        jsonMapper,
        httpClient,
        Execs.scheduledSingleThreaded("ChangeRequestHttpSyncerTest"),
        new URL("http://localhost:8080/"),
        "/xx",
        typeRef,
        50000,
        10000,
        listener
    );

    syncer.start();

    while (httpClient.results.size() != 0) {
      Thread.sleep(100);
    }

    syncer.stop();

    EasyMock.verify(listener);

  }

  @Test(timeout = 60_000L)
  public void testRequestFailureAfterSyncerAndExecutorStopDoesNotRejectCallback() throws Exception
  {
    final SettableFuture<InputStream> delegateFuture = SettableFuture.create();
    final AtomicBoolean cancellationAttempted = new AtomicBoolean(false);
    final ListenableFuture<InputStream> nonCancellableFuture =
        new ForwardingListenableFuture.SimpleForwardingListenableFuture<InputStream>(delegateFuture)
        {
          @Override
          public boolean cancel(boolean mayInterruptIfRunning)
          {
            cancellationAttempted.set(true);
            return false;
          }
        };
    final ScheduledExecutorService executor = Execs.scheduledSingleThreaded("ChangeRequestHttpSyncerTest");
    final ChangeRequestHttpSyncer.Listener<String> listener = EasyMock.mock(ChangeRequestHttpSyncer.Listener.class);
    EasyMock.replay(listener);

    final ChangeRequestHttpSyncer<String> syncer = new ChangeRequestHttpSyncer<>(
        TestHelper.makeJsonMapper(),
        new SingleFutureHttpClient(nonCancellableFuture),
        executor,
        new URL("http://localhost:8080/"),
        "/xx",
        new TypeReference<ChangeRequestsSnapshot<String>>() {},
        50_000,
        10_000,
        listener
    );

    syncer.start();
    executor.submit(() -> null).get(5, TimeUnit.SECONDS);
    syncer.stop();
    Assert.assertTrue(cancellationAttempted.get());
    executor.shutdownNow();
    Assert.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

    Assert.assertTrue(delegateFuture.setException(new RuntimeException("late channel failure")));
    Assert.assertTrue(abstractFutureLogger.getLogEvents().isEmpty());
    EasyMock.verify(listener);
  }

  private static class SingleFutureHttpClient implements HttpClient
  {
    private final ListenableFuture<InputStream> future;

    SingleFutureHttpClient(ListenableFuture<InputStream> future)
    {
      this.future = future;
    }

    @Override
    public <Intermediate, Final> ListenableFuture<Final> go(
        Request request,
        HttpResponseHandler<Intermediate, Final> httpResponseHandler
    )
    {
      return go(request, httpResponseHandler, Duration.ZERO);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <Intermediate, Final> ListenableFuture<Final> go(
        Request request,
        HttpResponseHandler<Intermediate, Final> httpResponseHandler,
        Duration duration
    )
    {
      return (ListenableFuture<Final>) future;
    }
  }

  private static class TestHttpClient implements HttpClient
  {
    BlockingQueue<ListenableFuture> results;
    AtomicInteger requestNum = new AtomicInteger(0);

    TestHttpClient(List<ListenableFuture> resultsList)
    {
      results = new LinkedBlockingQueue<>();
      results.addAll(resultsList);
    }

    @Override
    public <Intermediate, Final> ListenableFuture<Final> go(
        Request request,
        HttpResponseHandler<Intermediate, Final> httpResponseHandler
    )
    {
      throw new UnsupportedOperationException("Not Implemented.");
    }

    @Override
    public <Intermediate, Final> ListenableFuture<Final> go(
        Request request,
        HttpResponseHandler<Intermediate, Final> httpResponseHandler,
        Duration duration
    )
    {
      if (requestNum.getAndIncrement() == 0) {
        //fail first request immediately
        throw new RuntimeException("simulating couldn't send request to server for some reason.");
      }

      if (requestNum.get() == 2) {
        //fail scenario where request is sent to server but we got an unexpected response.
        HttpResponse httpResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.INTERNAL_SERVER_ERROR,
            Unpooled.buffer(0)
        );
        httpResponseHandler.handleResponse(httpResponse, null);
        return Futures.immediateFailedFuture(new RuntimeException("server error"));
      }

      HttpResponse httpResponse = new DefaultFullHttpResponse(
          HttpVersion.HTTP_1_1,
          HttpResponseStatus.OK,
          Unpooled.buffer(0)
      );
      httpResponseHandler.handleResponse(httpResponse, null);
      try {
        return results.take();
      }
      catch (InterruptedException ex) {
        throw new RE(ex, "Interrupted.");
      }
    }
  }
}
