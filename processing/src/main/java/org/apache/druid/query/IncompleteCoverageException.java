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

import org.apache.druid.java.util.common.StringUtils;

import java.util.Set;

/**
 * Thrown when a query cannot be executed because segment coverage is below the required threshold.
 * This exception is thrown when the query context includes {@code requireFullCoverage=true} or
 * {@code minCoveragePercent} and the available segments don't meet the coverage requirement.
 */
public class IncompleteCoverageException extends QueryException
{

  private final String dataSource;
  private final int totalSegments;
  private final int availableSegments;
  private final float coveragePercent;
  private final float requiredPercent;
  private final Set<String> missingSegmentIds;

  public IncompleteCoverageException(
      String dataSource,
      int totalSegments,
      int availableSegments,
      float requiredPercent,
      Set<String> missingSegmentIds
  )
  {
    super(
        INCOMPLETE_COVERAGE_ERROR_CODE,
        StringUtils.format(
            "Insufficient segment coverage for datasource [%s]: %d/%d segments available (%.1f%%), required %.1f%%",
            dataSource,
            availableSegments,
            totalSegments,
            totalSegments > 0 ? (availableSegments * 100.0f / totalSegments) : 0,
            requiredPercent
        ),
        null,
        null
    );
    this.dataSource = dataSource;
    this.totalSegments = totalSegments;
    this.availableSegments = availableSegments;
    this.coveragePercent = totalSegments > 0 ? (availableSegments * 100.0f / totalSegments) : 0;
    this.requiredPercent = requiredPercent;
    this.missingSegmentIds = missingSegmentIds;
  }

  @Override
  public FailType getFailType()
  {
    return FailType.SERVICE_UNAVAILABLE;
  }

  public String getDataSource()
  {
    return dataSource;
  }

  public int getTotalSegments()
  {
    return totalSegments;
  }

  public int getAvailableSegments()
  {
    return availableSegments;
  }

  public float getCoveragePercent()
  {
    return coveragePercent;
  }

  public float getRequiredPercent()
  {
    return requiredPercent;
  }

  public Set<String> getMissingSegmentIds()
  {
    return missingSegmentIds;
  }
}

