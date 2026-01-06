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

import com.fasterxml.jackson.annotation.JsonProperty;

public class RetryQueryRunnerConfig
{
  @JsonProperty
  private int numTries = 1;

  /**
   * When true, requireFullCoverage operates in "dry-run" mode:
   * - Logs what would have failed at WARN level with [COVERAGE-DRYRUN] prefix
   * - Does NOT actually fail the query
   * - Allows gathering data about coverage issues without impacting availability
   * 
   * This is a SERVER-SIDE setting that affects all queries with requireFullCoverage=true.
   * No client-side changes are required to enable/disable this mode.
   * 
   * Default is TRUE (dry-run enabled) to safely gather data before enforcing failures.
   * Set to false to enable actual query failures: druid.broker.retryPolicy.requireFullCoverageDryRun=false
   */
  @JsonProperty
  private boolean requireFullCoverageDryRun = true;

  public int getNumTries()
  {
    return numTries;
  }

  // exists for testing and overrides
  public boolean isReturnPartialResults()
  {
    return false;
  }

  /**
   * Returns true if requireFullCoverage should operate in dry-run mode,
   * logging what would have failed without actually failing queries.
   */
  public boolean isRequireFullCoverageDryRun()
  {
    return requireFullCoverageDryRun;
  }
}
