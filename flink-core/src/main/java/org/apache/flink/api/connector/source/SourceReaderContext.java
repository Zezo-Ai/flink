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

package org.apache.flink.api.connector.source;

import org.apache.flink.annotation.Public;
import org.apache.flink.api.common.watermark.Watermark;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.groups.SourceReaderMetricGroup;
import org.apache.flink.util.UserCodeClassLoader;

/** The interface that exposes some context from runtime to the {@link SourceReader}. */
@Public
public interface SourceReaderContext {

    /**
     * @return The metric group this source belongs to.
     */
    SourceReaderMetricGroup metricGroup();

    /** Gets the configuration with which Flink was started. */
    Configuration getConfiguration();

    /**
     * Gets the hostname of the machine where this reader is executed. This can be used to request
     * splits local to the machine, if needed.
     */
    String getLocalHostName();

    /**
     * @return The index of this subtask.
     */
    int getIndexOfSubtask();

    /**
     * Sends a split request to the source's {@link SplitEnumerator}. This will result in a call to
     * the {@link SplitEnumerator#handleSplitRequest(int, String)} method, with this reader's
     * parallel subtask id and the hostname where this reader runs.
     */
    void sendSplitRequest();

    /**
     * Send a source event to the source coordinator.
     *
     * @param sourceEvent the source event to coordinator.
     */
    void sendSourceEventToCoordinator(SourceEvent sourceEvent);

    /**
     * Gets the {@link UserCodeClassLoader} to load classes that are not in system's classpath, but
     * are part of the jar file of a user job.
     *
     * @see UserCodeClassLoader
     */
    UserCodeClassLoader getUserCodeClassLoader();

    /**
     * Get the current parallelism of this Source.
     *
     * @return the parallelism of the Source.
     */
    default int currentParallelism() {
        throw new UnsupportedOperationException();
    }

    /**
     * Send the watermark to source output.
     *
     * <p>This should only be used for datastream v2.
     */
    default void emitWatermark(Watermark watermark) {
        throw new UnsupportedOperationException();
    }
}
