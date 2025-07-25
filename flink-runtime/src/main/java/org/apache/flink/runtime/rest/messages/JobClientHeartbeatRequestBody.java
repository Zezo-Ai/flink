/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.rest.messages;

import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.annotation.JsonCreator;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.annotation.JsonProperty;

/** {@link RequestBody} to report heartbeat for client. */
public class JobClientHeartbeatRequestBody implements RequestBody {
    private static final String EXPIRED_TIMESTAMP = "expiredTimestamp";

    @JsonProperty(EXPIRED_TIMESTAMP)
    private final long expiredTimestamp;

    @JsonCreator
    public JobClientHeartbeatRequestBody(@JsonProperty(EXPIRED_TIMESTAMP) long expiredTimestamp) {
        this.expiredTimestamp = expiredTimestamp;
    }

    @JsonIgnore
    public long getExpiredTimestamp() {
        return expiredTimestamp;
    }
}
