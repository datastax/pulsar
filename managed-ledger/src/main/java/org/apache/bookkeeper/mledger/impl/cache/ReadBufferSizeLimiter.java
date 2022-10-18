/**
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
package org.apache.bookkeeper.mledger.impl.cache;

import com.google.common.annotations.VisibleForTesting;
import io.prometheus.client.Gauge;
import lombok.AllArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ReadBufferSizeLimiter {

    private static final Gauge PULSAR_ML_READS_BUFFER_SIZE = Gauge
            .build()
            .name("pulsar_ml_reads_buffer_size_bytes")
            .help("Estimated number of bytes retained by data read from storage or cache")
            .register();

    private static final Gauge PULSAR_ML_READS_AVAILABLE_BUFFER_SIZE = Gauge
            .build()
            .name("pulsar_ml_reads_available_buffer_size")
            .help("Available space on the reads buffer")
            .register();

    private final long maxReadsBufferSize;
    private long remainingRequestsBytes;

    public ReadBufferSizeLimiter(long maxReadsBufferSize) {
        if (maxReadsBufferSize <= 0) {
            // set it to -1 in order to show in the metrics that the metric is not available
            PULSAR_ML_READS_BUFFER_SIZE.set(-1);
            PULSAR_ML_READS_AVAILABLE_BUFFER_SIZE.set(-1);
        }
        this.maxReadsBufferSize = maxReadsBufferSize;
        this.remainingRequestsBytes = maxReadsBufferSize;
    }

    @VisibleForTesting
    public synchronized long getRemainingRequestsBytes() {
        return remainingRequestsBytes;
    }

    @AllArgsConstructor
    @ToString
    static class Handle {
        final long acquiredPermits;
        final boolean success;
        final int trials;

        final long creationTime;
    }

    private static final Handle DISABLED = new Handle(0, true, 0, -1);

    Handle acquire(long permits, Handle current) {
        if (maxReadsBufferSize <= 0) {
            // feature is disabled
            return DISABLED;
        }
        synchronized (this) {
            try {
                if (current == null) {
                    if (remainingRequestsBytes == 0) {
                        return new Handle(0, false, 1, System.currentTimeMillis());
                    }
                    if (remainingRequestsBytes >= permits) {
                        remainingRequestsBytes -= permits;
                        return new Handle(permits, true, 1, System.currentTimeMillis());
                    } else {
                        long possible = remainingRequestsBytes;
                        remainingRequestsBytes = 0;
                        return new Handle(possible, false, 1, System.currentTimeMillis());
                    }
                } else {
                    if (current.trials >= 4 && current.acquiredPermits > 0) {
                        remainingRequestsBytes += current.acquiredPermits;
                        return new Handle(0, false, 1, current.creationTime);
                    }
                    if (remainingRequestsBytes == 0) {
                        return new Handle(current.acquiredPermits, false, current.trials + 1,
                                current.creationTime);
                    }
                    long needed = permits - current.acquiredPermits;
                    if (remainingRequestsBytes >= needed) {
                        remainingRequestsBytes -= needed;
                        return new Handle(permits, true, current.trials + 1, current.creationTime);
                    } else {
                        long possible = remainingRequestsBytes;
                        remainingRequestsBytes = 0;
                        return new Handle(current.acquiredPermits + possible, false,
                                current.trials + 1, current.creationTime);
                    }
                }
            } finally {
                updateMetrics();
            }
        }
    }

    void release(Handle handle) {
        if (handle == DISABLED) {
            return;
        }
        synchronized (this) {
            remainingRequestsBytes += handle.acquiredPermits;
            updateMetrics();
        }
    }

    private synchronized void updateMetrics() {
        PULSAR_ML_READS_BUFFER_SIZE.set(maxReadsBufferSize - remainingRequestsBytes);
        PULSAR_ML_READS_AVAILABLE_BUFFER_SIZE.set(remainingRequestsBytes);
    }

    public boolean isDisabled() {
        return maxReadsBufferSize <= 0;
    }


}
