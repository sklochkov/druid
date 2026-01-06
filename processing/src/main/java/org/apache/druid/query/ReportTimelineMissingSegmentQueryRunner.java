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

package org.apache.druid.query;

import com.google.common.collect.ImmutableList;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.query.context.ResponseContext;

import java.util.List;

/**
 */
public class ReportTimelineMissingSegmentQueryRunner<T> implements QueryRunner<T>
{
  private static final Logger LOG = new Logger(ReportTimelineMissingSegmentQueryRunner.class);

  private final List<SegmentDescriptor> descriptors;

  public ReportTimelineMissingSegmentQueryRunner(SegmentDescriptor descriptor)
  {
    this(ImmutableList.of(descriptor));
  }

  public ReportTimelineMissingSegmentQueryRunner(List<SegmentDescriptor> descriptors)
  {
    this.descriptors = descriptors;
  }

  @Override
  public Sequence<T> run(QueryPlus<T> queryPlus, ResponseContext responseContext)
  {
    final Query<T> query = queryPlus.getQuery();
    final boolean warnMode = query.context().isWarnOnIncompleteCoverage();
    
    if (warnMode) {
      // Detailed logging in warn mode to help identify all edge cases
      LOG.warn(
          "[COVERAGE-WARN] Query [%s] HISTORICAL-MISSING: Segment(s) not found in Historical's local timeline. "
          + "This Historical was asked for segments it doesn't have. count=%d, segments=%s, "
          + "datasource=%s, intervals=%s",
          query.getId(),
          descriptors.size(),
          descriptors,
          query.getDataSource(),
          query.getIntervals()
      );
    } else {
      // Standard INFO level logging
      LOG.info("Reporting missing segments[%s] for query[%s]", descriptors, query.getId());
    }
    
    responseContext.addMissingSegments(descriptors);
    return Sequences.empty();
  }
}
