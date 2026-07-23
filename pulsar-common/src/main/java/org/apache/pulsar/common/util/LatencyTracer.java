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
package org.apache.pulsar.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

public class LatencyTracer {

    private final Queue<Timepoint> timepoints;
    private final NanoTimeSupplier nanoTimeSupplier;
    private final long startNs;

    public LatencyTracer(Queue<Timepoint> timepoints, NanoTimeSupplier nanoTimeSupplier) {
        this.timepoints = timepoints;
        this.nanoTimeSupplier = nanoTimeSupplier;
        this.startNs = nanoTimeSupplier.getNanos();
    }

    public <T> CompletableFuture<T> trace(String message, CompletableFuture<T> future) {
        if (future.isDone()) {
            return future;
        }
        return future.whenComplete((__, ___) -> trace(message));
    }

    public void trace(String action) {
        timepoints.add(new Timepoint(action, nanoTimeSupplier.getNanos()));
    }

    public Snapshot getLatency() {
        final List<Timepoint> timepoints = new ArrayList<>(this.timepoints);
        if (timepoints.isEmpty()) {
            return new Snapshot(startNs, 0, "total: 0 ms");
        }
        final StringBuilder sb = new StringBuilder();
        final long totalLatencyNs = TimeUnit.NANOSECONDS.toMillis(timepoints.get(timepoints.size() - 1).timeInNanos
                - startNs);
        sb.append("total: ").append(totalLatencyNs).append(" ms");
        long prevNs = startNs;
        for (final Timepoint tp : timepoints) {
            sb.append(", ").append(tp.name).append(": ");
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(tp.timeInNanos - prevNs);
            if (latencyMs > 0) {
                sb.append(latencyMs).append(" ms");
            } else {
                sb.append(TimeUnit.NANOSECONDS.toMicros(tp.timeInNanos - prevNs)).append(" us");
            }
            prevNs = tp.timeInNanos;
        }
        return new Snapshot(prevNs, totalLatencyNs, sb.toString());
    }

    public Snapshot traceAndGetLatency(String action) {
        trace(action);
        return getLatency();
    }

    public interface NanoTimeSupplier {

        long getNanos();
    }

    @Accessors(fluent = true)
    @Getter
    @RequiredArgsConstructor
    public static final class Timepoint {

        private final String name;
        private final long timeInNanos;
    }

    /**
     * A snapshot of the latency tracer at a given moment.
     * `endTimeInNanos` the latest traced timestamp in nanoseconds
     * `elapsedInMillis` the elapsed time in milliseconds from when the tracer was created
     * `description` the detailed description for traced latencies, e.g. "total: 100 ms, A: 60 ms, B: 40 ms"
     */
    @Accessors(fluent = true)
    @Getter
    @RequiredArgsConstructor
    public static final class Snapshot {

        private final long endTimeInNanos;
        private final long elapsedInMillis;
        private final String description;
    }
}
