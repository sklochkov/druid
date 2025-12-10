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

import com.google.common.collect.ImmutableSet;
import org.junit.Assert;
import org.junit.Test;

import java.util.Set;

public class IncompleteCoverageExceptionTest
{
  @Test
  public void testExceptionProperties()
  {
    Set<String> missingIds = ImmutableSet.of("segment1", "segment2");
    IncompleteCoverageException exception = new IncompleteCoverageException(
        "test_datasource",
        10,
        8,
        100.0f,
        missingIds
    );

    Assert.assertEquals("test_datasource", exception.getDataSource());
    Assert.assertEquals(10, exception.getTotalSegments());
    Assert.assertEquals(8, exception.getAvailableSegments());
    Assert.assertEquals(80.0f, exception.getCoveragePercent(), 0.1f);
    Assert.assertEquals(100.0f, exception.getRequiredPercent(), 0.1f);
    Assert.assertEquals(missingIds, exception.getMissingSegmentIds());
  }

  @Test
  public void testFailType()
  {
    IncompleteCoverageException exception = new IncompleteCoverageException(
        "test_datasource",
        10,
        5,
        100.0f,
        ImmutableSet.of()
    );

    Assert.assertEquals(QueryException.FailType.SERVICE_UNAVAILABLE, exception.getFailType());
    Assert.assertEquals(503, exception.getFailType().getExpectedStatus());
  }

  @Test
  public void testErrorCode()
  {
    IncompleteCoverageException exception = new IncompleteCoverageException(
        "test_datasource",
        10,
        5,
        100.0f,
        ImmutableSet.of()
    );

    Assert.assertEquals(QueryException.INCOMPLETE_COVERAGE_ERROR_CODE, exception.getErrorCode());
    Assert.assertEquals("incompleteCoverage", exception.getErrorCode());
  }

  @Test
  public void testErrorMessage()
  {
    IncompleteCoverageException exception = new IncompleteCoverageException(
        "my_table",
        100,
        75,
        90.0f,
        ImmutableSet.of()
    );

    String message = exception.getMessage();
    Assert.assertTrue(message.contains("my_table"));
    Assert.assertTrue(message.contains("75/100"));
    Assert.assertTrue(message.contains("75.0%"));
    Assert.assertTrue(message.contains("90.0%"));
  }

  @Test
  public void testFromErrorCode()
  {
    QueryException.FailType failType = QueryException.fromErrorCode(
        QueryException.INCOMPLETE_COVERAGE_ERROR_CODE
    );
    Assert.assertEquals(QueryException.FailType.SERVICE_UNAVAILABLE, failType);
  }

  @Test
  public void testZeroTotalSegments()
  {
    IncompleteCoverageException exception = new IncompleteCoverageException(
        "empty_datasource",
        0,
        0,
        100.0f,
        ImmutableSet.of()
    );

    // Coverage percent should be 0 when totalSegments is 0 (avoid division by zero)
    Assert.assertEquals(0.0f, exception.getCoveragePercent(), 0.1f);
  }
}

