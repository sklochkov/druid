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

package org.apache.druid.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.RangeSet;
import com.google.common.collect.Sets;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Bytes;
import com.google.inject.Inject;
import org.apache.druid.client.cache.Cache;
import org.apache.druid.client.cache.CacheConfig;
import org.apache.druid.client.cache.CachePopulator;
import org.apache.druid.client.selector.QueryableDruidServer;
import org.apache.druid.client.selector.ServerSelector;
import org.apache.druid.guice.annotations.Client;
import org.apache.druid.guice.annotations.Merging;
import org.apache.druid.guice.annotations.Smile;
import org.apache.druid.guice.http.DruidHttpClientConfig;
import org.apache.druid.client.coordinator.CoordinatorClient;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.guava.BaseSequence;
import org.apache.druid.java.util.common.guava.LazySequence;
import org.apache.druid.java.util.common.guava.ParallelMergeCombiningSequence;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.SequenceWrapper;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.query.BrokerParallelMergeConfig;
import org.apache.druid.query.BySegmentResultValueClass;
import org.apache.druid.query.CacheStrategy;
import org.apache.druid.query.Queries;
import org.apache.druid.query.IncompleteCoverageException;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryContext;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.query.QueryMetrics;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QuerySegmentWalker;
import org.apache.druid.query.QueryToolChest;
import org.apache.druid.query.QueryToolChestWarehouse;
import org.apache.druid.query.Result;
import org.apache.druid.query.RetryQueryRunnerConfig;
import org.apache.druid.query.SegmentDescriptor;
import org.apache.druid.query.aggregation.MetricManipulatorFns;
import org.apache.druid.query.context.ResponseContext;
import org.apache.druid.query.filter.DimFilterUtils;
import org.apache.druid.query.planning.DataSourceAnalysis;
import org.apache.druid.query.spec.QuerySegmentSpec;
import org.apache.druid.server.QueryResource;
import org.apache.druid.server.QueryScheduler;
import org.apache.druid.server.coordination.DruidServerMetadata;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.Overshadowable;
import org.apache.druid.timeline.SegmentId;
import org.apache.druid.timeline.TimelineLookup;
import org.apache.druid.timeline.TimelineObjectHolder;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.apache.druid.timeline.VersionedIntervalTimeline.PartitionChunkEntry;
import org.apache.druid.timeline.partition.PartitionChunk;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * This is the class on the Broker that is responsible for making native Druid queries to a cluster of data servers.
 *
 * The main user of this class is {@link org.apache.druid.server.ClientQuerySegmentWalker}. In tests, its behavior
 * is partially mimicked by TestClusterQuerySegmentWalker.
 */
public class CachingClusteredClient implements QuerySegmentWalker
{
  private static final EmittingLogger log = new EmittingLogger(CachingClusteredClient.class);
  private final QueryToolChestWarehouse warehouse;
  private final TimelineServerView serverView;
  private final Cache cache;
  private final ObjectMapper objectMapper;
  private final CachePopulator cachePopulator;
  private final CacheConfig cacheConfig;
  private final DruidHttpClientConfig httpClientConfig;
  private final BrokerParallelMergeConfig parallelMergeConfig;
  private final ForkJoinPool pool;
  private final QueryScheduler scheduler;
  private final ServiceEmitter emitter;
  @Nullable
  private final CoordinatorClient coordinatorClient;
  private final RetryQueryRunnerConfig retryConfig;

  @Inject
  public CachingClusteredClient(
      QueryToolChestWarehouse warehouse,
      TimelineServerView serverView,
      Cache cache,
      @Smile ObjectMapper objectMapper,
      CachePopulator cachePopulator,
      CacheConfig cacheConfig,
      @Client DruidHttpClientConfig httpClientConfig,
      BrokerParallelMergeConfig parallelMergeConfig,
      @Merging ForkJoinPool pool,
      QueryScheduler scheduler,
      ServiceEmitter emitter,
      @Nullable CoordinatorClient coordinatorClient,
      RetryQueryRunnerConfig retryConfig
  )
  {
    this.warehouse = warehouse;
    this.serverView = serverView;
    this.cache = cache;
    this.objectMapper = objectMapper;
    this.cachePopulator = cachePopulator;
    this.cacheConfig = cacheConfig;
    this.httpClientConfig = httpClientConfig;
    this.parallelMergeConfig = parallelMergeConfig;
    this.pool = pool;
    this.scheduler = scheduler;
    this.emitter = emitter;
    this.coordinatorClient = coordinatorClient;
    this.retryConfig = retryConfig;

    if (cacheConfig.isQueryCacheable(Query.GROUP_BY) && (cacheConfig.isUseCache() || cacheConfig.isPopulateCache())) {
      log.warn(
          "Even though groupBy caching is enabled in your configuration, v2 groupBys will not be cached on the broker. "
          + "Consider enabling caching on your data nodes if it is not already enabled."
      );
    }

    serverView.registerSegmentCallback(
        Execs.singleThreaded("CCClient-ServerView-CB-%d"),
        new ServerView.BaseSegmentCallback()
        {
          @Override
          public ServerView.CallbackAction segmentRemoved(DruidServerMetadata server, DataSegment segment)
          {
            CachingClusteredClient.this.cache.close(segment.getId().toString());
            return ServerView.CallbackAction.CONTINUE;
          }
        }
    );
  }

  @Override
  public <T> QueryRunner<T> getQueryRunnerForIntervals(final Query<T> query, final Iterable<Interval> intervals)
  {
    return new QueryRunner<T>()
    {
      @Override
      public Sequence<T> run(final QueryPlus<T> queryPlus, final ResponseContext responseContext)
      {
        return CachingClusteredClient.this.run(queryPlus, responseContext, timeline -> timeline, false);
      }
    };
  }

  /**
   * Run a query. The timelineConverter will be given the "master" timeline and can be used to return a different
   * timeline, if desired. This is used by getQueryRunnerForSegments.
   */
  private <T> Sequence<T> run(
      final QueryPlus<T> queryPlus,
      final ResponseContext responseContext,
      final UnaryOperator<TimelineLookup<String, ServerSelector>> timelineConverter,
      final boolean specificSegments
  )
  {
    final ClusterQueryResult<T> result = new SpecificQueryRunnable<>(queryPlus, responseContext)
        .run(timelineConverter, specificSegments);
    initializeNumRemainingResponsesInResponseContext(queryPlus.getQuery(), responseContext, result.numQueryServers);
    return result.sequence;
  }

  private static <T> void initializeNumRemainingResponsesInResponseContext(
      final Query<T> query,
      final ResponseContext responseContext,
      final int numQueryServers
  )
  {
    responseContext.addRemainingResponse(query.getMostSpecificId(), numQueryServers);
  }

  @Override
  public <T> QueryRunner<T> getQueryRunnerForSegments(final Query<T> query, final Iterable<SegmentDescriptor> specs)
  {
    return new QueryRunner<T>()
    {
      @Override
      public Sequence<T> run(final QueryPlus<T> queryPlus, final ResponseContext responseContext)
      {
        return CachingClusteredClient.this.run(
            queryPlus,
            responseContext,
            new TimelineConverter(specs),
            true
        );
      }
    };
  }

  private static class ClusterQueryResult<T>
  {
    private final Sequence<T> sequence;
    private final int numQueryServers;

    private ClusterQueryResult(Sequence<T> sequence, int numQueryServers)
    {
      this.sequence = sequence;
      this.numQueryServers = numQueryServers;
    }
  }

  /**
   * This class essentially encapsulates the major part of the logic of {@link CachingClusteredClient}. It's state and
   * methods couldn't belong to {@link CachingClusteredClient} itself, because they depend on the specific query object
   * being run, but {@link QuerySegmentWalker} API is designed so that implementations should be able to accept
   * arbitrary queries.
   */
  private class SpecificQueryRunnable<T>
  {
    private final ResponseContext responseContext;
    private QueryPlus<T> queryPlus;
    private Query<T> query;
    private final QueryToolChest<T, Query<T>> toolChest;
    @Nullable
    private final CacheStrategy<T, Object, Query<T>> strategy;
    private final boolean useCache;
    private final boolean populateCache;
    private final boolean isBySegment;
    private final int uncoveredIntervalsLimit;
    private final float minCoveragePercent;
    private final Map<String, Cache.NamedKey> cachePopulatorKeyMap = new HashMap<>();
    private final DataSourceAnalysis dataSourceAnalysis;
    private final List<Interval> intervals;
    private final CacheKeyManager<T> cacheKeyManager;

    SpecificQueryRunnable(final QueryPlus<T> queryPlus, final ResponseContext responseContext)
    {
      this.queryPlus = queryPlus;
      this.responseContext = responseContext;
      this.query = queryPlus.getQuery();
      this.toolChest = warehouse.getToolChest(query);
      this.strategy = toolChest.getCacheStrategy(query, objectMapper);
      this.dataSourceAnalysis = query.getDataSource().getAnalysis();

      this.useCache = CacheUtil.isUseSegmentCache(query, strategy, cacheConfig, CacheUtil.ServerType.BROKER);
      this.populateCache = CacheUtil.isPopulateSegmentCache(query, strategy, cacheConfig, CacheUtil.ServerType.BROKER);
      final QueryContext queryContext = query.context();
      this.isBySegment = queryContext.isBySegment();
      // Note that enabling this leads to putting uncovered intervals information in the response headers
      // and might blow up in some cases https://github.com/apache/druid/issues/2108
      this.uncoveredIntervalsLimit = queryContext.getUncoveredIntervalsLimit();
      // Minimum coverage percentage required for this query (0 = no requirement, 100 = requireFullCoverage)
      this.minCoveragePercent = queryContext.getEffectiveMinCoveragePercent();
      // For nested queries, we need to look at the intervals of the inner most query.
      this.intervals = dataSourceAnalysis.getBaseQuerySegmentSpec()
                                         .map(QuerySegmentSpec::getIntervals)
                                         .orElseGet(() -> query.getIntervals());
      this.cacheKeyManager = new CacheKeyManager<>(
          query,
          strategy,
          useCache,
          populateCache
      );
    }

    private ImmutableMap<String, Object> makeDownstreamQueryContext()
    {
      final ImmutableMap.Builder<String, Object> contextBuilder = new ImmutableMap.Builder<>();

      final QueryContext queryContext = query.context();
      final int priority = queryContext.getPriority();
      contextBuilder.put(QueryContexts.PRIORITY_KEY, priority);
      final String lane = queryContext.getLane();
      if (lane != null) {
        contextBuilder.put(QueryContexts.LANE_KEY, lane);
      }

      if (populateCache) {
        // prevent down-stream nodes from caching results as well if we are populating the cache
        contextBuilder.put(CacheConfig.POPULATE_CACHE, false);
        contextBuilder.put(QueryContexts.BY_SEGMENT_KEY, true);
      }
      return contextBuilder.build();
    }

    /**
     * Builds a query distribution and merge plan.
     *
     * This method returns an empty sequence if the query datasource is unknown or there is matching result-level cache.
     * Otherwise, it creates a sequence merging sequences from the regular broker cache and remote servers. If parallel
     * merge is enabled, it can merge and *combine* the underlying sequences in parallel.
     *
     * @return a pair of a sequence merging results from remote query servers and the number of remote servers
     *         participating in query processing.
     */
    ClusterQueryResult<T> run(
        final UnaryOperator<TimelineLookup<String, ServerSelector>> timelineConverter,
        final boolean specificSegments
    )
    {
      final boolean traceQuery = query.context().isTraceQuery();
      
      if (traceQuery) {
        log.info(
            "[TRACE] Query [%s] starting. datasource=%s, intervals=%s, specificSegments=%s",
            query.getId(),
            dataSourceAnalysis.getBaseDataSource().getTableNames(),
            intervals,
            specificSegments
        );
      }

      final Optional<? extends TimelineLookup<String, ServerSelector>> maybeTimeline = serverView.getTimeline(
          dataSourceAnalysis
      );
      if (!maybeTimeline.isPresent()) {
        if (traceQuery) {
          log.info("[TRACE] Query [%s] no timeline present, returning empty", query.getId());
        }
        return new ClusterQueryResult<>(Sequences.empty(), 0);
      }

      final TimelineLookup<String, ServerSelector> timeline = timelineConverter.apply(maybeTimeline.get());
      
      // Detailed timeline dump for debugging segment availability issues
      if (traceQuery) {
        logTimelineContents(timeline, query.getId());
      }
      
      // Get expected segment count from Coordinator when requireFullCoverage is enabled
      // This is critical because the Broker's timeline only contains segments from LIVE Historicals.
      // When a Historical dies, its segments disappear from the timeline entirely, so timeline-only
      // coverage would be 100% even when half the segments are unavailable.
      int expectedSegmentCount = -1;
      if (minCoveragePercent > 0 || traceQuery) {
        try {
          expectedSegmentCount = getExpectedSegmentCountFromCoordinator(timeline, query.getId(), traceQuery || minCoveragePercent > 0);
        }
        catch (Exception e) {
          // Log but don't fail - we'll fall back to timeline-only check
          log.warn(e, "Query [%s] Failed to get expected segment count from Coordinator, using timeline-only check", query.getId());
        }
      }
      
      if (uncoveredIntervalsLimit > 0) {
        computeUncoveredIntervals(timeline);
      }

      // Compute segment coverage and validate if minCoveragePercent > 0
      // Uses Coordinator's expected count when available, otherwise falls back to timeline count
      computeAndValidateSegmentCoverage(timeline, specificSegments, expectedSegmentCount);

      final Set<SegmentServerSelector> segmentServers = computeSegmentsToQuery(timeline, specificSegments);
      
      if (traceQuery) {
        log.info(
            "[TRACE] Query [%s] computed %d segment-server pairs",
            query.getId(),
            segmentServers.size()
        );
        // Log each segment and its available servers
        for (SegmentServerSelector segmentServer : segmentServers) {
          ServerSelector selector = segmentServer.getServer();
          SegmentDescriptor segment = segmentServer.getSegmentDescriptor();
          QueryableDruidServer pickedServer = selector.pick(query);
          List<DruidServerMetadata> allServers = selector.getAllServers();
          log.info(
              "[TRACE] Query [%s] segment [%s_%s_%s] has %d servers %s, picked: %s",
              query.getId(),
              segment.getInterval(),
              segment.getVersion(),
              segment.getPartitionNumber(),
              allServers.size(),
              allServers.stream().map(DruidServerMetadata::getName).collect(Collectors.toList()),
              pickedServer != null ? pickedServer.getServer().getName() : "NONE"
          );
        }
      }

      @Nullable
      final byte[] queryCacheKey = cacheKeyManager.computeSegmentLevelQueryCacheKey();
      @Nullable
      final String prevEtag = (String) query.getContext().get(QueryResource.HEADER_IF_NONE_MATCH);
      if (prevEtag != null) {
        @Nullable
        final String currentEtag = cacheKeyManager.computeResultLevelCachingEtag(segmentServers, queryCacheKey);
        if (null != currentEtag) {
          responseContext.putEntityTag(currentEtag);
        }
        if (currentEtag != null && currentEtag.equals(prevEtag)) {
          if (traceQuery) {
            log.info("[TRACE] Query [%s] ETag match, returning cached", query.getId());
          }
          return new ClusterQueryResult<>(Sequences.empty(), 0);
        }
      }

      final List<Pair<Interval, byte[]>> alreadyCachedResults =
          pruneSegmentsWithCachedResults(queryCacheKey, segmentServers);

      query = scheduler.prioritizeAndLaneQuery(queryPlus, segmentServers);
      queryPlus = queryPlus.withQuery(query);
      queryPlus = queryPlus.withQueryMetrics(toolChest);
      queryPlus.getQueryMetrics().reportQueriedSegmentCount(segmentServers.size()).emit(emitter);

      final SortedMap<DruidServer, List<SegmentDescriptor>> segmentsByServer = groupSegmentsByServer(segmentServers);
      
      if (traceQuery) {
        log.info(
            "[TRACE] Query [%s] distributing to %d servers, %d from cache",
            query.getId(),
            segmentsByServer.size(),
            alreadyCachedResults.size()
        );
        segmentsByServer.forEach((server, segments) ->
            log.info(
                "[TRACE] Query [%s] -> server [%s] type=%s for %d segments: %s",
                query.getId(),
                server.getName(),
                server.getType(),
                segments.size(),
                segments
            )
        );
      }

      final boolean finalTraceQuery = traceQuery;
      LazySequence<T> mergedResultSequence = new LazySequence<>(() -> {
        if (finalTraceQuery) {
          log.info("[TRACE] Query [%s] executing merge sequence", query.getId());
        }
        List<Sequence<T>> sequencesByInterval = new ArrayList<>(alreadyCachedResults.size() + segmentsByServer.size());
        addSequencesFromCache(sequencesByInterval, alreadyCachedResults);
        addSequencesFromServer(sequencesByInterval, segmentsByServer);
        return merge(sequencesByInterval);
      });

      return new ClusterQueryResult<>(scheduler.run(query, mergedResultSequence), segmentsByServer.size());
    }

    private Sequence<T> merge(List<Sequence<T>> sequencesByInterval)
    {
      BinaryOperator<T> mergeFn = toolChest.createMergeFn(query);
      final QueryContext queryContext = query.context();
      if (parallelMergeConfig.useParallelMergePool() && queryContext.getEnableParallelMerges() && mergeFn != null) {
        final ParallelMergeCombiningSequence<T> parallelSequence = new ParallelMergeCombiningSequence<>(
            pool,
            sequencesByInterval,
            query.getResultOrdering(),
            mergeFn,
            queryContext.hasTimeout(),
            queryContext.getTimeout(),
            queryContext.getPriority(),
            queryContext.getParallelMergeParallelism(parallelMergeConfig.getDefaultMaxQueryParallelism()),
            queryContext.getParallelMergeInitialYieldRows(parallelMergeConfig.getInitialYieldNumRows()),
            queryContext.getParallelMergeSmallBatchRows(parallelMergeConfig.getSmallBatchNumRows()),
            parallelMergeConfig.getTargetRunTimeMillis(),
            reportMetrics -> {
              QueryMetrics<?> queryMetrics = queryPlus.getQueryMetrics();
              if (queryMetrics != null) {
                queryMetrics.parallelMergeParallelism(reportMetrics.getParallelism());
                queryMetrics.reportParallelMergeParallelism(reportMetrics.getParallelism()).emit(emitter);
                queryMetrics.reportParallelMergeInputSequences(reportMetrics.getInputSequences()).emit(emitter);
                queryMetrics.reportParallelMergeInputRows(reportMetrics.getInputRows()).emit(emitter);
                queryMetrics.reportParallelMergeOutputRows(reportMetrics.getOutputRows()).emit(emitter);
                queryMetrics.reportParallelMergeTaskCount(reportMetrics.getTaskCount()).emit(emitter);
                queryMetrics.reportParallelMergeTotalCpuTime(reportMetrics.getTotalCpuTime()).emit(emitter);
                queryMetrics.reportParallelMergeTotalTime(reportMetrics.getTotalTime()).emit(emitter);
                queryMetrics.reportParallelMergeSlowestPartitionTime(reportMetrics.getSlowestPartitionInitializedTime())
                            .emit(emitter);
                queryMetrics.reportParallelMergeFastestPartitionTime(reportMetrics.getFastestPartitionInitializedTime())
                            .emit(emitter);
              }
            }
        );
        scheduler.registerQueryFuture(query, parallelSequence.getCancellationFuture());
        return parallelSequence;
      } else {
        return Sequences
            .simple(sequencesByInterval)
            .flatMerge(seq -> seq, query.getResultOrdering());
      }
    }

    private Set<SegmentServerSelector> computeSegmentsToQuery(
        TimelineLookup<String, ServerSelector> timeline,
        boolean specificSegments
    )
    {
      final java.util.function.Function<Interval, List<TimelineObjectHolder<String, ServerSelector>>> lookupFn
          = specificSegments ? timeline::lookupWithIncompletePartitions : timeline::lookup;

      List<TimelineObjectHolder<String, ServerSelector>> timelineObjectHolders =
          intervals.stream().flatMap(i -> lookupFn.apply(i).stream()).collect(Collectors.toList());
      final List<TimelineObjectHolder<String, ServerSelector>> serversLookup = toolChest.filterSegments(
          query,
          timelineObjectHolders
      );

      final Set<SegmentServerSelector> segments = new LinkedHashSet<>();
      final Map<String, Optional<RangeSet<String>>> dimensionRangeCache;
      final Set<String> filterFieldsForPruning;

      final boolean trySecondaryPartititionPruning =
          query.getFilter() != null && query.context().isSecondaryPartitionPruningEnabled();

      if (trySecondaryPartititionPruning) {
        dimensionRangeCache = new HashMap<>();
        filterFieldsForPruning =
            DimFilterUtils.onlyBaseFields(query.getFilter().getRequiredColumns(), dataSourceAnalysis);
      } else {
        dimensionRangeCache = null;
        filterFieldsForPruning = null;
      }

      // Filter unneeded chunks based on partition dimension
      for (TimelineObjectHolder<String, ServerSelector> holder : serversLookup) {
        final Set<PartitionChunk<ServerSelector>> filteredChunks;
        if (trySecondaryPartititionPruning) {
          filteredChunks = DimFilterUtils.filterShards(
              query.getFilter(),
              filterFieldsForPruning,
              holder.getObject(),
              partitionChunk -> partitionChunk.getObject().getSegment().getShardSpec(),
              dimensionRangeCache
          );
        } else {
          filteredChunks = Sets.newLinkedHashSet(holder.getObject());
        }
        for (PartitionChunk<ServerSelector> chunk : filteredChunks) {
          ServerSelector server = chunk.getObject();
          final SegmentDescriptor segment = new SegmentDescriptor(
              holder.getInterval(),
              holder.getVersion(),
              chunk.getChunkNumber()
          );
          segments.add(new SegmentServerSelector(server, segment));
        }
      }
      return segments;
    }

    private void computeUncoveredIntervals(TimelineLookup<String, ServerSelector> timeline)
    {
      final List<Interval> uncoveredIntervals = new ArrayList<>(uncoveredIntervalsLimit);
      boolean uncoveredIntervalsOverflowed = false;

      for (Interval interval : intervals) {
        Iterable<TimelineObjectHolder<String, ServerSelector>> lookup = timeline.lookup(interval);
        long startMillis = interval.getStartMillis();
        long endMillis = interval.getEndMillis();
        for (TimelineObjectHolder<String, ServerSelector> holder : lookup) {
          Interval holderInterval = holder.getInterval();
          long intervalStart = holderInterval.getStartMillis();
          if (!uncoveredIntervalsOverflowed && startMillis != intervalStart) {
            if (uncoveredIntervalsLimit > uncoveredIntervals.size()) {
              uncoveredIntervals.add(Intervals.utc(startMillis, intervalStart));
            } else {
              uncoveredIntervalsOverflowed = true;
            }
          }
          startMillis = holderInterval.getEndMillis();
        }

        if (!uncoveredIntervalsOverflowed && startMillis < endMillis) {
          if (uncoveredIntervalsLimit > uncoveredIntervals.size()) {
            uncoveredIntervals.add(Intervals.utc(startMillis, endMillis));
          } else {
            uncoveredIntervalsOverflowed = true;
          }
        }
      }

      if (!uncoveredIntervals.isEmpty()) {
        // Record in the response context the interval for which NO segment is present.
        // Which is not necessarily an indication that the data doesn't exist or is
        // incomplete. The data could exist and just not be loaded yet.  In either
        // case, though, this query will not include any data from the identified intervals.
        responseContext.putUncoveredIntervals(uncoveredIntervals, uncoveredIntervalsOverflowed);
      }
    }

    /**
     * Computes segment coverage for the query and records it in the response context.
     * If minCoveragePercent > 0, validates that coverage meets the threshold and throws
     * {@link IncompleteCoverageException} if it doesn't.
     *
     * @param timeline the timeline to check for segment coverage
     * @param specificSegments whether to include incomplete partitions
     * @throws IncompleteCoverageException if coverage is below the required minimum percentage
     */
    /**
     * Computes segment coverage and validates against the minimum required percentage.
     *
     * @param timeline the timeline to check for segment coverage
     * @param specificSegments whether to include incomplete partitions
     * @param expectedSegmentCount the expected total segment count from Coordinator (-1 if unavailable)
     * @throws IncompleteCoverageException if coverage is below the required minimum percentage
     */
    private void computeAndValidateSegmentCoverage(
        TimelineLookup<String, ServerSelector> timeline,
        boolean specificSegments,
        int expectedSegmentCount
    )
    {
      final java.util.function.Function<Interval, List<TimelineObjectHolder<String, ServerSelector>>> lookupFn
          = specificSegments ? timeline::lookupWithIncompletePartitions : timeline::lookup;

      int timelineSegments = 0;
      int availableSegments = 0;
      final Set<String> unavailableSegmentIds = new HashSet<>();

      for (Interval interval : intervals) {
        List<TimelineObjectHolder<String, ServerSelector>> holders = lookupFn.apply(interval);
        for (TimelineObjectHolder<String, ServerSelector> holder : holders) {
          for (PartitionChunk<ServerSelector> chunk : holder.getObject()) {
            timelineSegments++;
            ServerSelector serverSelector = chunk.getObject();
            if (!serverSelector.isEmpty()) {
              availableSegments++;
            } else {
              // Track the ID of the unavailable segment for debugging
              unavailableSegmentIds.add(serverSelector.getSegment().getId().toString());
            }
          }
        }
      }

      // Use Coordinator's expected count if available and greater than timeline count
      // This handles the case where segments are missing from timeline due to Historical failure
      // We use max() because:
      // - If expectedSegmentCount > timelineSegments: Some segments are missing from timeline (Historical down)
      // - If expectedSegmentCount < timelineSegments: Coordinator may not have caught up with realtime segments
      // - If expectedSegmentCount == -1: Coordinator unavailable, use timeline count
      int totalSegments = expectedSegmentCount > timelineSegments ? expectedSegmentCount : timelineSegments;
      
      // Record coverage in response context (always, for visibility)
      responseContext.putSegmentCoverage(totalSegments, availableSegments);

      // Get datasource name for logging/error messages
      String dataSourceName = dataSourceAnalysis.getBaseDataSource()
          .getTableNames()
          .stream()
          .findFirst()
          .orElse("unknown");

      // Log coverage info at INFO level when requireFullCoverage is set
      if (minCoveragePercent > 0) {
        float actualCoveragePercent = totalSegments > 0 ? (availableSegments * 100.0f) / totalSegments : 0;
        log.info(
            "Query [%s] pre-execution coverage check: %d/%d segments available (%.1f%%), required %.1f%% "
            + "[timelineSegments=%d, coordinatorExpected=%d]",
            query.getId(),
            availableSegments,
            totalSegments,
            actualCoveragePercent,
            minCoveragePercent,
            timelineSegments,
            expectedSegmentCount
        );
        if (!unavailableSegmentIds.isEmpty()) {
          log.info("Query [%s] unavailable segments in timeline: %s", query.getId(), unavailableSegmentIds);
        }
        // Log if there's a discrepancy between timeline and expected
        if (expectedSegmentCount > timelineSegments) {
          log.warn(
              "Query [%s] SEGMENT-GAP: Coordinator expects %d segments but timeline only has %d. "
              + "%d segments are missing (likely due to Historical failure/restart).",
              query.getId(),
              expectedSegmentCount,
              timelineSegments,
              expectedSegmentCount - timelineSegments
          );
        }
      }

      // Warn mode: log details about any incomplete coverage (even if below threshold)
      final boolean warnMode = query.context().isWarnOnIncompleteCoverage();
      if (warnMode && totalSegments > 0 && availableSegments < totalSegments) {
        float actualCoveragePercent = (availableSegments * 100.0f) / totalSegments;
        log.warn(
            "[COVERAGE-WARN] Query [%s] PRE-EXECUTION: Incomplete segment coverage detected. "
            + "datasource=[%s], intervals=%s, totalSegments=%d (timeline=%d, coordExpected=%d), "
            + "availableSegments=%d, coverage=%.1f%%, unavailableSegments=%s",
            query.getId(),
            dataSourceName,
            intervals,
            totalSegments,
            timelineSegments,
            expectedSegmentCount,
            availableSegments,
            actualCoveragePercent,
            unavailableSegmentIds
        );
      }

      // Validate coverage if required
      if (minCoveragePercent > 0 && totalSegments > 0) {
        float actualCoveragePercent = (availableSegments * 100.0f) / totalSegments;
        if (actualCoveragePercent < minCoveragePercent) {
          // Check if we're in dry-run mode (server-side config)
          final boolean dryRunMode = retryConfig.isRequireFullCoverageDryRun();
          
          if (dryRunMode) {
            // Dry-run mode: log what WOULD have failed, but don't actually fail
            log.warn(
                "[COVERAGE-DRYRUN] Query [%s] WOULD-FAIL-PRE-EXECUTION: Query would have thrown "
                + "IncompleteCoverageException but dry-run mode is enabled. "
                + "datasource=[%s], intervals=%s, totalSegments=%d (timeline=%d, coordExpected=%d), "
                + "availableSegments=%d, coverage=%.1f%%, required=%.1f%%, unavailableSegments=%s",
                query.getId(),
                dataSourceName,
                intervals,
                totalSegments,
                timelineSegments,
                expectedSegmentCount,
                availableSegments,
                actualCoveragePercent,
                minCoveragePercent,
                unavailableSegmentIds
            );
          } else {
            throw new IncompleteCoverageException(
                dataSourceName,
                totalSegments,
                availableSegments,
                minCoveragePercent,
                unavailableSegmentIds
            );
          }
        }
      }
    }

    /**
     * Logs detailed timeline contents for debugging segment availability issues.
     * Shows all segments in the timeline along with which servers have them.
     */
    private void logTimelineContents(TimelineLookup<String, ServerSelector> timeline, String queryId)
    {
      try {
        // Get the datasource name
        String dataSourceName = dataSourceAnalysis.getBaseDataSource()
            .getTableNames()
            .stream()
            .findFirst()
            .orElse("unknown");
        
        // Iterate over all intervals to dump timeline contents
        // Use a very wide interval to capture everything
        List<TimelineObjectHolder<String, ServerSelector>> allHolders = 
            timeline.lookup(Intervals.ETERNITY);
        
        log.info(
            "[TRACE] Query [%s] Timeline dump for datasource [%s]: %d timeline entries",
            queryId,
            dataSourceName,
            allHolders.size()
        );
        
        for (TimelineObjectHolder<String, ServerSelector> holder : allHolders) {
          for (PartitionChunk<ServerSelector> chunk : holder.getObject()) {
            ServerSelector selector = chunk.getObject();
            DataSegment segment = selector.getSegment();
            List<DruidServerMetadata> servers = selector.getAllServers();
            
            log.info(
                "[TRACE] Query [%s] Timeline segment: id=[%s] interval=[%s] version=[%s] partition=[%d] "
                + "servers=%d %s isEmpty=%s",
                queryId,
                segment.getId(),
                holder.getInterval(),
                holder.getVersion(),
                chunk.getChunkNumber(),
                servers.size(),
                servers.stream().map(DruidServerMetadata::getName).collect(Collectors.toList()),
                selector.isEmpty()
            );
          }
        }
        
        // Also log what intervals we're querying
        log.info(
            "[TRACE] Query [%s] Requested intervals: %s",
            queryId,
            intervals
        );
      }
      catch (Exception e) {
        log.warn(e, "[TRACE] Query [%s] Failed to dump timeline contents", queryId);
      }
    }

    /**
     * Gets the expected segment count from the Coordinator for the query intervals.
     * This is used to detect when segments are missing from the Broker's timeline
     * due to Historical failures (segments are removed from timeline when Historical dies).
     *
     * @param timeline the current timeline (used to compare against Coordinator view)
     * @param queryId for logging
     * @param verbose whether to log detailed information
     * @return the expected segment count from Coordinator, or -1 if unavailable
     */
    private int getExpectedSegmentCountFromCoordinator(
        TimelineLookup<String, ServerSelector> timeline,
        String queryId,
        boolean verbose
    )
    {
      if (coordinatorClient == null) {
        // Always log at INFO level when requireFullCoverage is set - this is important to know
        log.info(
            "Query [%s] COVERAGE-SKIP: Coordinator client not available, falling back to timeline-only check. "
            + "This means coverage validation cannot detect segments missing from the cluster.",
            queryId
        );
        return -1;
      }

      try {
        String dataSourceName = dataSourceAnalysis.getBaseDataSource()
            .getTableNames()
            .stream()
            .findFirst()
            .orElse(null);
        
        if (dataSourceName == null) {
          return -1;
        }

        // Build list of intervals to query
        // For ETERNITY or very large intervals, use a practical bounded range
        // that covers all real-world data (1970-2100)
        List<Interval> intervalList = new ArrayList<>();
        final Interval PRACTICAL_ALL_TIME = new Interval(
            new org.joda.time.DateTime(0L, org.joda.time.DateTimeZone.UTC),  // 1970-01-01
            new org.joda.time.DateTime(4102444800000L, org.joda.time.DateTimeZone.UTC)  // 2100-01-01
        );
        
        for (Interval interval : intervals) {
          // For unbounded intervals (ETERNITY or very large ranges), use practical bounds
          if (interval.equals(Intervals.ETERNITY) || 
              interval.toDurationMillis() > 365L * 24 * 60 * 60 * 1000 * 50) {  // > 50 years
            if (verbose) {
              log.debug(
                  "Query [%s] Using practical bounds for large interval: %s -> %s",
                  queryId,
                  interval,
                  PRACTICAL_ALL_TIME
              );
            }
            intervalList.add(PRACTICAL_ALL_TIME);
          } else {
            intervalList.add(interval);
          }
        }

        if (intervalList.isEmpty()) {
          return -1;
        }

        // Fetch segments from Coordinator's METADATA (not server view!)
        // fetchUsedSegments returns segments that SHOULD exist based on metadata,
        // not just segments that are currently loaded. This is critical for detecting
        // missing segments when a Historical is down.
        // NOTE: This is a blocking call - acceptable for requireFullCoverage
        // since users are explicitly trading latency for correctness
        List<DataSegment> usedSegments;
        try {
          usedSegments = coordinatorClient.fetchUsedSegments(dataSourceName, intervalList).get(
              30, java.util.concurrent.TimeUnit.SECONDS
          );
        }
        catch (Exception e) {
          log.warn(
              e,
              "Query [%s] COVERAGE-SKIP: Failed to fetch expected segments from Coordinator metadata for datasource [%s], "
              + "intervals=%s. Falling back to timeline-only check.",
              queryId,
              dataSourceName,
              intervalList
          );
          return -1;
        }

        // Count segments from Coordinator metadata
        int expectedCount = usedSegments.size();
        Set<String> coordinatorSegmentIds = new HashSet<>();
        for (DataSegment segment : usedSegments) {
          coordinatorSegmentIds.add(segment.getId().toString());
        }

        // Always log the result at INFO level for requireFullCoverage queries
        log.info(
            "Query [%s] Coordinator reports %d segments for datasource [%s], intervals=%s",
            queryId,
            expectedCount,
            dataSourceName,
            intervalList
        );

        if (verbose) {
          // Also count timeline segments for comparison logging
          Set<String> timelineSegmentIds = new HashSet<>();
          for (Interval interval : intervalList) {
            List<TimelineObjectHolder<String, ServerSelector>> holders = timeline.lookup(interval);
            for (TimelineObjectHolder<String, ServerSelector> holder : holders) {
              for (PartitionChunk<ServerSelector> chunk : holder.getObject()) {
                timelineSegmentIds.add(chunk.getObject().getSegment().getId().toString());
              }
            }
          }

          // Find segments in Coordinator but not in timeline
          Set<String> missingFromTimeline = new HashSet<>(coordinatorSegmentIds);
          missingFromTimeline.removeAll(timelineSegmentIds);

          if (!missingFromTimeline.isEmpty()) {
            log.warn(
                "Query [%s] SEGMENT-DISCREPANCY: Coordinator has %d segments, timeline has %d. "
                + "Missing from timeline: %s",
                queryId,
                expectedCount,
                timelineSegmentIds.size(),
                missingFromTimeline
            );
          } else {
            log.debug(
                "Query [%s] Coordinator expected count: %d (matches timeline: %d)",
                queryId,
                expectedCount,
                timelineSegmentIds.size()
            );
          }
        }

        return expectedCount;
      }
      catch (Exception e) {
        log.warn(e, "Query [%s] Error getting expected segment count from Coordinator", queryId);
        return -1;
      }
    }

    private List<Pair<Interval, byte[]>> pruneSegmentsWithCachedResults(
        final byte[] queryCacheKey,
        final Set<SegmentServerSelector> segments
    )
    {
      if (queryCacheKey == null) {
        return Collections.emptyList();
      }
      final List<Pair<Interval, byte[]>> alreadyCachedResults = new ArrayList<>();
      Map<SegmentServerSelector, Cache.NamedKey> perSegmentCacheKeys = computePerSegmentCacheKeys(
          segments,
          queryCacheKey
      );
      // Pull cached segments from cache and remove from set of segments to query
      final Map<Cache.NamedKey, byte[]> cachedValues = computeCachedValues(perSegmentCacheKeys);

      perSegmentCacheKeys.forEach((segment, segmentCacheKey) -> {
        final Interval segmentQueryInterval = segment.getSegmentDescriptor().getInterval();

        final byte[] cachedValue = cachedValues.get(segmentCacheKey);
        if (cachedValue != null) {
          // remove cached segment from set of segments to query
          segments.remove(segment);
          alreadyCachedResults.add(Pair.of(segmentQueryInterval, cachedValue));
        } else if (populateCache) {
          // otherwise, if populating cache, add segment to list of segments to cache
          final SegmentId segmentId = segment.getServer().getSegment().getId();
          addCachePopulatorKey(segmentCacheKey, segmentId, segmentQueryInterval);
        }
      });
      return alreadyCachedResults;
    }

    private Map<SegmentServerSelector, Cache.NamedKey> computePerSegmentCacheKeys(
        Set<SegmentServerSelector> segments,
        byte[] queryCacheKey
    )
    {
      // cacheKeys map must preserve segment ordering, in order for shards to always be combined in the same order
      Map<SegmentServerSelector, Cache.NamedKey> cacheKeys = Maps.newLinkedHashMap();
      for (SegmentServerSelector segmentServer : segments) {
        final Cache.NamedKey segmentCacheKey = CacheUtil.computeSegmentCacheKey(
            segmentServer.getServer().getSegment().getId().toString(),
            segmentServer.getSegmentDescriptor(),
            queryCacheKey
        );
        cacheKeys.put(segmentServer, segmentCacheKey);
      }
      return cacheKeys;
    }

    private Map<Cache.NamedKey, byte[]> computeCachedValues(Map<SegmentServerSelector, Cache.NamedKey> cacheKeys)
    {
      if (useCache) {
        return cache.getBulk(Iterables.limit(cacheKeys.values(), cacheConfig.getCacheBulkMergeLimit()));
      } else {
        return ImmutableMap.of();
      }
    }

    private void addCachePopulatorKey(
        Cache.NamedKey segmentCacheKey,
        SegmentId segmentId,
        Interval segmentQueryInterval
    )
    {
      cachePopulatorKeyMap.put(StringUtils.format("%s_%s", segmentId, segmentQueryInterval), segmentCacheKey);
    }

    @Nullable
    private Cache.NamedKey getCachePopulatorKey(String segmentId, Interval segmentInterval)
    {
      return cachePopulatorKeyMap.get(StringUtils.format("%s_%s", segmentId, segmentInterval));
    }

    private SortedMap<DruidServer, List<SegmentDescriptor>> groupSegmentsByServer(Set<SegmentServerSelector> segments)
    {
      final SortedMap<DruidServer, List<SegmentDescriptor>> serverSegments = new TreeMap<>();
      for (SegmentServerSelector segmentServer : segments) {
        final QueryableDruidServer queryableDruidServer = segmentServer.getServer().pick(query);

        if (queryableDruidServer == null) {
          log.makeAlert(
              "No servers found for SegmentDescriptor[%s] for DataSource[%s]?! How can this be?!",
              segmentServer.getSegmentDescriptor(),
              query.getDataSource()
          ).emit();
        } else {
          final DruidServer server = queryableDruidServer.getServer();
          serverSegments.computeIfAbsent(server, s -> new ArrayList<>()).add(segmentServer.getSegmentDescriptor());
        }
      }
      return serverSegments;
    }

    private void addSequencesFromCache(
        final List<Sequence<T>> listOfSequences,
        final List<Pair<Interval, byte[]>> cachedResults
    )
    {
      if (strategy == null) {
        return;
      }

      final Function<Object, T> pullFromCacheFunction = strategy.pullFromSegmentLevelCache();
      final TypeReference<Object> cacheObjectClazz = strategy.getCacheObjectClazz();
      for (Pair<Interval, byte[]> cachedResultPair : cachedResults) {
        final byte[] cachedResult = cachedResultPair.rhs;
        Sequence<Object> cachedSequence = new BaseSequence<>(
            new BaseSequence.IteratorMaker<Object, Iterator<Object>>()
            {
              @Override
              public Iterator<Object> make()
              {
                try {
                  if (cachedResult.length == 0) {
                    return Collections.emptyIterator();
                  }

                  return objectMapper.readValues(
                      objectMapper.getFactory().createParser(cachedResult),
                      cacheObjectClazz
                  );
                }
                catch (IOException e) {
                  throw new RuntimeException(e);
                }
              }

              @Override
              public void cleanup(Iterator<Object> iterFromMake)
              {
              }
            }
        );
        listOfSequences.add(Sequences.map(cachedSequence, pullFromCacheFunction));
      }
    }

    /**
     * Create sequences that reads from remote query servers (historicals and tasks). Note that the broker will
     * hold an HTTP connection per server after this method is called.
     */
    private void addSequencesFromServer(
        final List<Sequence<T>> listOfSequences,
        final SortedMap<DruidServer, List<SegmentDescriptor>> segmentsByServer
    )
    {
      // Log query distribution for debugging incomplete coverage issues
      if (log.isInfoEnabled() && query.context().isRequireFullCoverage()) {
        log.info(
            "Query [%s] distributing to [%d] servers with requireFullCoverage=true",
            query.getId(),
            segmentsByServer.size()
        );
        segmentsByServer.forEach((srv, segs) ->
            log.info("Query [%s] -> server [%s] for [%d] segments", query.getId(), srv.getName(), segs.size())
        );
      }

      final boolean warnMode = query.context().isWarnOnIncompleteCoverage();
      
      segmentsByServer.forEach((server, segmentsOfServer) -> {
        final QueryRunner serverRunner = serverView.getQueryRunner(server);

        if (serverRunner == null) {
          if (warnMode) {
            log.warn(
                "[COVERAGE-WARN] Query [%s] NO-QUERY-RUNNER: Server [%s] has no query runner available. "
                + "Server may have gone offline after timeline lookup. markingMissing=%d segments: %s",
                query.getId(),
                server.getName(),
                segmentsOfServer.size(),
                segmentsOfServer
            );
          } else {
            log.warn("Query [%s] server [%s] doesn't have a query runner, marking [%d] segments as missing",
                query.getId(), server.getName(), segmentsOfServer.size());
          }
          // Mark these segments as missing so RetryQueryRunner can detect and handle them
          responseContext.addMissingSegments(segmentsOfServer);
          return;
        }

        // Divide user-provided maxQueuedBytes by the number of servers, and limit each server to that much.
        final long maxQueuedBytes = query.context().getMaxQueuedBytes(httpClientConfig.getMaxQueuedBytes());
        final long maxQueuedBytesPerServer = maxQueuedBytes / segmentsByServer.size();
        final Sequence<T> serverResults;

        if (isBySegment) {
          serverResults = getBySegmentServerResults(serverRunner, segmentsOfServer, maxQueuedBytesPerServer);
        } else if (!server.isSegmentReplicationTarget() || !populateCache) {
          serverResults = getSimpleServerResults(serverRunner, segmentsOfServer, maxQueuedBytesPerServer);
        } else {
          serverResults = getAndCacheServerResults(serverRunner, segmentsOfServer, maxQueuedBytesPerServer);
        }
        // Wrap the sequence with error handling to mark segments as missing on failure
        listOfSequences.add(wrapSequenceWithMissingSegmentHandler(serverResults, segmentsOfServer, server.getName()));
      });
    }

    /**
     * Wraps a sequence with error handling that marks segments as missing when the sequence
     * fails during iteration. This is critical for detecting failures during query execution
     * (e.g., when a Historical goes down mid-query or connection times out).
     *
     * @param sequence        the sequence to wrap
     * @param segmentsOfServer segments that this sequence is responsible for
     * @param serverName      the server name for logging
     * @return wrapped sequence that marks segments as missing on failure
     */
    private Sequence<T> wrapSequenceWithMissingSegmentHandler(
        final Sequence<T> sequence,
        final List<SegmentDescriptor> segmentsOfServer,
        final String serverName
    )
    {
      return Sequences.wrap(
          sequence,
          new SequenceWrapper()
          {
            @Override
            public void after(boolean isDone, Throwable thrown) throws Exception
            {
              final boolean traceQuery = query.context().isTraceQuery();
              final boolean warnMode = query.context().isWarnOnIncompleteCoverage();
              
              if (traceQuery) {
                log.info(
                    "[TRACE] Query [%s] server [%s] sequence completed: isDone=%s, thrown=%s",
                    query.getId(),
                    serverName,
                    isDone,
                    thrown != null ? thrown.getClass().getName() : "null"
                );
              }

              if (thrown != null) {
                // Connection failed, timeout, or other error - mark segments as missing
                if (warnMode) {
                  log.warn(
                      thrown,
                      "[COVERAGE-WARN] Query [%s] EXECUTION-FAILURE: Server [%s] threw exception. "
                      + "exceptionType=%s, message=%s, markingMissing=%d segments: %s",
                      query.getId(),
                      serverName,
                      thrown.getClass().getName(),
                      thrown.getMessage(),
                      segmentsOfServer.size(),
                      segmentsOfServer
                  );
                } else {
                  log.warn(
                      thrown,
                      "Query [%s] server [%s] failed (isDone=%s), marking [%d] segments as missing: %s",
                      query.getId(),
                      serverName,
                      isDone,
                      segmentsOfServer.size(),
                      thrown.getClass().getName()
                  );
                }
                responseContext.addMissingSegments(segmentsOfServer);
              } else if (!isDone) {
                // Sequence ended without completing - likely a clean disconnect
                if (warnMode) {
                  log.warn(
                      "[COVERAGE-WARN] Query [%s] PREMATURE-COMPLETION: Server [%s] sequence ended "
                      + "without completing (isDone=false, no exception). This typically indicates "
                      + "graceful shutdown mid-query. markingMissing=%d segments: %s",
                      query.getId(),
                      serverName,
                      segmentsOfServer.size(),
                      segmentsOfServer
                  );
                } else {
                  log.warn(
                      "Query [%s] server [%s] sequence ended prematurely (isDone=false, no exception), "
                      + "marking [%d] segments as missing. This may indicate a graceful shutdown mid-query.",
                      query.getId(),
                      serverName,
                      segmentsOfServer.size()
                  );
                }
                responseContext.addMissingSegments(segmentsOfServer);
              }
            }
          }
      );
    }

    @SuppressWarnings("unchecked")
    private Sequence<T> getBySegmentServerResults(
        final QueryRunner serverRunner,
        final List<SegmentDescriptor> segmentsOfServer,
        long maxQueuedBytesPerServer
    )
    {
      Sequence<Result<BySegmentResultValueClass<T>>> resultsBySegments = serverRunner
          .run(
              queryPlus.withQuery(
                  Queries.withSpecificSegments(queryPlus.getQuery(), segmentsOfServer)
              ).withMaxQueuedBytes(maxQueuedBytesPerServer),
              responseContext
          );
      // bySegment results need to be de-serialized, see DirectDruidClient.run()
      return (Sequence<T>) resultsBySegments
          .map(result -> result.map(
              resultsOfSegment -> resultsOfSegment.mapResults(
                  toolChest.makePreComputeManipulatorFn(query, MetricManipulatorFns.deserializing())::apply
              )
          ));
    }

    @SuppressWarnings("unchecked")
    private Sequence<T> getSimpleServerResults(
        final QueryRunner serverRunner,
        final List<SegmentDescriptor> segmentsOfServer,
        long maxQueuedBytesPerServer
    )
    {
      return serverRunner.run(
          queryPlus.withQuery(
              Queries.withSpecificSegments(queryPlus.getQuery(), segmentsOfServer)
          ).withMaxQueuedBytes(maxQueuedBytesPerServer),
          responseContext
      );
    }

    private Sequence<T> getAndCacheServerResults(
        final QueryRunner serverRunner,
        final List<SegmentDescriptor> segmentsOfServer,
        long maxQueuedBytesPerServer
    )
    {
      @SuppressWarnings("unchecked")
      final Query<T> downstreamQuery = query.withOverriddenContext(makeDownstreamQueryContext());
      final Sequence<Result<BySegmentResultValueClass<T>>> resultsBySegments = serverRunner.run(
          queryPlus
              .withQuery(
                  Queries.withSpecificSegments(
                      downstreamQuery,
                      segmentsOfServer
                  )
              )
              .withMaxQueuedBytes(maxQueuedBytesPerServer),
          responseContext
      );
      final Function<T, Object> cacheFn = strategy.prepareForSegmentLevelCache();

      return resultsBySegments
          .map(result -> {
            final BySegmentResultValueClass<T> resultsOfSegment = result.getValue();
            final Cache.NamedKey cachePopulatorKey =
                getCachePopulatorKey(resultsOfSegment.getSegmentId(), resultsOfSegment.getInterval());
            Sequence<T> res = Sequences.simple(resultsOfSegment.getResults());
            if (cachePopulatorKey != null) {
              res = cachePopulator.wrap(res, cacheFn::apply, cache, cachePopulatorKey);
            }
            return res.map(
                toolChest.makePreComputeManipulatorFn(downstreamQuery, MetricManipulatorFns.deserializing())::apply
            );
          })
          .flatMerge(seq -> seq, query.getResultOrdering());
    }
  }

  /**
   * An inner class that is used solely for computing cache keys. Its a separate class to allow extensive unit testing
   * of cache key generation.
   */
  @VisibleForTesting
  static class CacheKeyManager<T>
  {
    private final Query<T> query;
    private final CacheStrategy<T, Object, Query<T>> strategy;
    private final boolean isSegmentLevelCachingEnable;

    CacheKeyManager(
        final Query<T> query,
        final CacheStrategy<T, Object, Query<T>> strategy,
        final boolean useCache,
        final boolean populateCache
    )
    {

      this.query = query;
      this.strategy = strategy;
      this.isSegmentLevelCachingEnable = ((populateCache || useCache)
                                          && !query.context().isBySegment());   // explicit bySegment queries are never cached

    }

    @Nullable
    byte[] computeSegmentLevelQueryCacheKey()
    {
      if (isSegmentLevelCachingEnable) {
        return computeQueryCacheKeyWithJoin();
      }
      return null;
    }

    /**
     * It computes the ETAG which is used by {@link org.apache.druid.query.ResultLevelCachingQueryRunner} for
     * result level caches. queryCacheKey can be null if segment level cache is not being used. However, ETAG
     * is still computed since result level cache may still be on.
     */
    @Nullable
    String computeResultLevelCachingEtag(
        final Set<SegmentServerSelector> segments,
        @Nullable byte[] queryCacheKey
    )
    {
      Hasher hasher = Hashing.sha1().newHasher();
      boolean hasOnlyHistoricalSegments = true;
      for (SegmentServerSelector p : segments) {
        QueryableDruidServer queryableServer = p.getServer().pick(query);
        if (queryableServer == null || !queryableServer.getServer().isSegmentReplicationTarget()) {
          hasOnlyHistoricalSegments = false;
          break;
        }
        hasher.putString(p.getServer().getSegment().getId().toString(), StandardCharsets.UTF_8);
        // it is important to add the "query interval" as part ETag calculation
        // to have result level cache work correctly for queries with different
        // intervals covering the same set of segments
        hasher.putString(p.rhs.getInterval().toString(), StandardCharsets.UTF_8);
      }

      if (!hasOnlyHistoricalSegments) {
        return null;
      }

      // query cache key can be null if segment level caching is disabled
      final byte[] queryCacheKeyFinal = (queryCacheKey == null) ? computeQueryCacheKeyWithJoin() : queryCacheKey;
      if (queryCacheKeyFinal == null) {
        return null;
      }
      hasher.putBytes(queryCacheKeyFinal);
      String currEtag = StringUtils.encodeBase64String(hasher.hash().asBytes());
      return currEtag;
    }

    /**
     * Adds the cache key prefix for join data sources. Return null if its a join but caching is not supported
     */
    @Nullable
    private byte[] computeQueryCacheKeyWithJoin()
    {
      Preconditions.checkNotNull(strategy, "strategy cannot be null");
      byte[] dataSourceCacheKey = query.getDataSource().getCacheKey();
      if (null == dataSourceCacheKey) {
        return null;
      } else if (dataSourceCacheKey.length > 0) {
        return Bytes.concat(dataSourceCacheKey, strategy.computeCacheKey(query));
      } else {
        return strategy.computeCacheKey(query);
      }
    }
  }

  /**
   * Helper class to build a new timeline of filtered segments.
   */
  public static class TimelineConverter<T extends Overshadowable<T>> implements UnaryOperator<TimelineLookup<String, T>>
  {
    private final Iterable<SegmentDescriptor> specs;

    public TimelineConverter(final Iterable<SegmentDescriptor> specs)
    {
      this.specs = specs;
    }

    @Override
    public TimelineLookup<String, T> apply(TimelineLookup<String, T> timeline)
    {
      Iterator<PartitionChunkEntry<String, T>> unfilteredIterator =
          Iterators.transform(specs.iterator(), spec -> toChunkEntry(timeline, spec));
      Iterator<PartitionChunkEntry<String, T>> iterator = Iterators.filter(
          unfilteredIterator,
          Objects::nonNull
      );
      final VersionedIntervalTimeline<String, T> newTimeline =
          new VersionedIntervalTimeline<>(Ordering.natural(), true);
      // VersionedIntervalTimeline#addAll implementation is much more efficient than calling VersionedIntervalTimeline#add
      // in a loop when there are lot of segments to be added for same interval and version.
      newTimeline.addAll(iterator);
      return newTimeline;
    }

    @Nullable
    private PartitionChunkEntry<String, T> toChunkEntry(
        TimelineLookup<String, T> timeline,
        SegmentDescriptor spec
    )
    {
      PartitionChunk<T> chunk = timeline.findChunk(
          spec.getInterval(),
          spec.getVersion(),
          spec.getPartitionNumber()
      );
      if (null == chunk) {
        return null;
      }
      return new PartitionChunkEntry<>(spec.getInterval(), spec.getVersion(), chunk);
    }
  }
}
