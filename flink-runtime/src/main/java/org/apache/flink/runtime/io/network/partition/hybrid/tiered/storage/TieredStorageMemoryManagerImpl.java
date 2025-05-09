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

package org.apache.flink.runtime.io.network.partition.hybrid.tiered.storage;

import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.BufferBuilder;
import org.apache.flink.runtime.io.network.buffer.BufferPool;
import org.apache.flink.runtime.io.network.buffer.LocalBufferPool;
import org.apache.flink.runtime.metrics.TimerGauge;
import org.apache.flink.runtime.metrics.groups.TaskIOMetricGroup;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FatalExitExceptionHandler;

import org.apache.flink.shaded.guava33.com.google.common.util.concurrent.ThreadFactoryBuilder;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * The implementation for {@link TieredStorageMemoryManager}. This is to request or recycle buffers
 * from {@link LocalBufferPool} for different memory owners, for example, the tiers, the buffer
 * accumulator, etc.
 *
 * <p>Note that the {@link TieredStorageMemorySpec}s of the tiered storages should be ready when
 * setting up the memory manager. Only after the setup process is finished, the tiered storage can
 * request buffers from this manager.
 */
public class TieredStorageMemoryManagerImpl implements TieredStorageMemoryManager {

    /** Time to wait for requesting new buffers before triggering buffer reclaiming. */
    private static final int INITIAL_REQUEST_BUFFER_TIMEOUT_FOR_RECLAIMING_MS = 50;

    /** The maximum delay time before triggering buffer reclaiming. */
    private static final int MAX_DELAY_TIME_TO_TRIGGER_RECLAIM_BUFFER_MS = 1000;

    /** The tiered storage memory specs of each memory user owner. */
    private final Map<Object, TieredStorageMemorySpec> tieredMemorySpecs;

    /** Listeners used to listen the requests for reclaiming buffer in different tiered storage. */
    private final List<Runnable> bufferReclaimRequestListeners;

    /** The buffer pool usage ratio of triggering the registered storages to reclaim buffers. */
    private final float numTriggerReclaimBuffersRatio;

    /**
     * Indicates whether reclaiming of buffers is supported. If supported, when there's a
     * contention, we may try reclaim buffers from the memory owners.
     */
    private final boolean mayReclaimBuffer;

    /**
     * The number of requested buffers from {@link BufferPool}. This field can be touched both by
     * the task thread and the netty thread, so it is an atomic type.
     */
    private final AtomicInteger numRequestedBuffers;

    /**
     * The number of requested buffers from {@link BufferPool} for each memory owner. This field
     * should be thread-safe because it can be touched both by the task thread and the netty thread.
     */
    private final Map<Object, Integer> numOwnerRequestedBuffers;

    /**
     * The queue that contains all available buffers. This field should be thread-safe because it
     * can be touched both by the task thread and the netty thread.
     */
    private final BlockingQueue<MemorySegment> bufferQueue;

    /** The lock guarding concurrency issues during releasing. */
    private final ReadWriteLock releasedStateLock;

    /** The number of buffers that are guaranteed to be reclaimed. */
    private int numGuaranteedReclaimableBuffers;

    /**
     * Time gauge to measure that hard backpressure time. Pre-create it to avoid checkNotNull in
     * hot-path for performance purpose.
     */
    private TimerGauge hardBackpressureTimerGauge = new TimerGauge();

    /**
     * This is for triggering buffer reclaiming while blocked on requesting new buffers.
     *
     * <p>Note: This can be null iff buffer reclaiming is not supported.
     */
    @Nullable private ScheduledExecutorService executor;

    /** The buffer pool where the buffer is requested or recycled. */
    private BufferPool bufferPool;

    /**
     * Indicates whether the {@link TieredStorageMemoryManagerImpl} is initialized. Before setting
     * up, this field is false.
     *
     * <p>Note that before requesting buffers or getting the maximum allowed buffers, this
     * initialized state should be checked.
     */
    private boolean isInitialized;

    /**
     * Indicates whether the {@link TieredStorageMemoryManagerImpl} is released.
     *
     * <p>Note that before recycling buffers, this released state should be checked to determine
     * whether to recycle the buffer back to the internal queue or to the buffer pool.
     */
    @GuardedBy("readWriteLock")
    private boolean isReleased;

    /**
     * The constructor of the {@link TieredStorageMemoryManagerImpl}.
     *
     * @param numTriggerReclaimBuffersRatio the buffer pool usage ratio of requesting each tiered
     *     storage to reclaim buffers
     * @param mayReclaimBuffer indicate whether buffer reclaiming is supported
     */
    public TieredStorageMemoryManagerImpl(
            float numTriggerReclaimBuffersRatio, boolean mayReclaimBuffer) {
        this.numTriggerReclaimBuffersRatio = numTriggerReclaimBuffersRatio;
        this.mayReclaimBuffer = mayReclaimBuffer;
        this.tieredMemorySpecs = new HashMap<>();
        this.numRequestedBuffers = new AtomicInteger(0);
        this.numOwnerRequestedBuffers = new ConcurrentHashMap<>();
        this.bufferReclaimRequestListeners = new ArrayList<>();
        this.bufferQueue = new LinkedBlockingQueue<>();
        this.releasedStateLock = new ReentrantReadWriteLock();
        this.isReleased = false;
        this.isInitialized = false;
    }

    @Override
    public void setup(BufferPool bufferPool, List<TieredStorageMemorySpec> storageMemorySpecs) {
        this.bufferPool = bufferPool;
        for (TieredStorageMemorySpec memorySpec : storageMemorySpecs) {
            checkState(
                    !tieredMemorySpecs.containsKey(memorySpec.getOwner()),
                    "Duplicated memory spec.");
            tieredMemorySpecs.put(memorySpec.getOwner(), memorySpec);
            numGuaranteedReclaimableBuffers +=
                    memorySpec.isGuaranteedReclaimable() ? memorySpec.getNumGuaranteedBuffers() : 0;
        }

        if (mayReclaimBuffer) {
            this.executor =
                    Executors.newSingleThreadScheduledExecutor(
                            new ThreadFactoryBuilder()
                                    .setNameFormat("buffer reclaim checker")
                                    .setUncaughtExceptionHandler(FatalExitExceptionHandler.INSTANCE)
                                    .build());
        }

        this.isInitialized = true;
    }

    @Override
    public void setMetricGroup(TaskIOMetricGroup metricGroup) {
        this.hardBackpressureTimerGauge =
                checkNotNull(metricGroup.getHardBackPressuredTimePerSecond());
    }

    @Override
    public void listenBufferReclaimRequest(Runnable onBufferReclaimRequest) {
        bufferReclaimRequestListeners.add(onBufferReclaimRequest);
    }

    @Override
    public BufferPool getBufferPool() {
        return bufferPool;
    }

    @Override
    public BufferBuilder requestBufferBlocking(Object owner) {
        checkIsInitialized();

        reclaimBuffersIfNeeded(0);

        MemorySegment memorySegment = bufferQueue.poll();
        if (memorySegment == null) {
            memorySegment = requestBufferBlockingFromPool();
        }
        if (memorySegment == null) {
            memorySegment = checkNotNull(requestBufferBlockingFromQueue());
        }

        incNumRequestedBuffer(owner);
        return new BufferBuilder(
                checkNotNull(memorySegment), segment -> recycleBuffer(owner, segment));
    }

    @Override
    public int getMaxNonReclaimableBuffers(Object owner) {
        checkIsInitialized();

        int numBuffersUsedOrReservedForOtherOwners = 0;
        for (Map.Entry<Object, TieredStorageMemorySpec> memorySpecEntry :
                tieredMemorySpecs.entrySet()) {
            Object userOwner = memorySpecEntry.getKey();
            TieredStorageMemorySpec storageMemorySpec = memorySpecEntry.getValue();
            if (!userOwner.equals(owner)) {
                int numGuaranteed = storageMemorySpec.getNumGuaranteedBuffers();
                int numRequested = numOwnerRequestedBuffer(userOwner);
                numBuffersUsedOrReservedForOtherOwners += Math.max(numGuaranteed, numRequested);
            }
        }
        // Note that a sudden reduction in the size of the buffer pool may result in non-reclaimable
        // buffer memory occupying the guaranteed buffers of other users. However, this occurrence
        // is limited to the memory tier, which is only utilized when downstream registration is in
        // effect. Furthermore, the buffers within the memory tier can be recycled quickly enough,
        // thereby minimizing the impact on the guaranteed buffers of other tiers.
        return bufferPool.getNumBuffers() - numBuffersUsedOrReservedForOtherOwners;
    }

    @Override
    public boolean ensureCapacity(int numAdditionalBuffers) {
        checkIsInitialized();

        final int numRequestedByGuaranteedReclaimableOwners =
                tieredMemorySpecs.values().stream()
                        .filter(TieredStorageMemorySpec::isGuaranteedReclaimable)
                        .mapToInt(spec -> numOwnerRequestedBuffer(spec.getOwner()))
                        .sum();

        while (bufferQueue.size() + numRequestedByGuaranteedReclaimableOwners
                < numGuaranteedReclaimableBuffers + numAdditionalBuffers) {
            if (numRequestedBuffers.get() >= bufferPool.getNumBuffers()) {
                return false;
            }

            MemorySegment memorySegment = requestBufferBlockingFromPool();
            if (memorySegment == null) {
                return false;
            }
            bufferQueue.add(memorySegment);
        }
        return true;
    }

    @Override
    public int numOwnerRequestedBuffer(Object owner) {
        return numOwnerRequestedBuffers.getOrDefault(owner, 0);
    }

    @Override
    public void transferBufferOwnership(Object oldOwner, Object newOwner, Buffer buffer) {
        checkState(buffer.isBuffer(), "Only buffer supports transfer ownership.");
        decNumRequestedBuffer(oldOwner);
        incNumRequestedBuffer(newOwner);
        buffer.setRecycler(memorySegment -> recycleBuffer(newOwner, memorySegment));
    }

    @Override
    public void release() {
        try {
            releasedStateLock.writeLock().lock();
            isReleased = true;
        } finally {
            releasedStateLock.writeLock().unlock();
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5L, TimeUnit.MINUTES)) {
                    throw new TimeoutException(
                            "Timeout for shutting down the buffer reclaim checker executor.");
                }
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
        }
        while (!bufferQueue.isEmpty()) {
            MemorySegment segment = bufferQueue.poll();
            bufferPool.recycle(segment);
            numRequestedBuffers.decrementAndGet();
        }
    }

    /**
     * @return a memory segment from the buffer pool or null if the memory manager has requested all
     *     segments of the buffer pool.
     */
    @Nullable
    private MemorySegment requestBufferBlockingFromPool() {
        MemorySegment memorySegment = null;

        hardBackpressureTimerGauge.markStart();
        while (numRequestedBuffers.get() < bufferPool.getNumBuffers()) {
            memorySegment = bufferPool.requestMemorySegment();
            if (memorySegment == null) {
                try {
                    // Wait until a buffer is available or timeout before entering the next loop
                    // iteration.
                    bufferPool.getAvailableFuture().get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                } catch (Exception e) {
                    ExceptionUtils.rethrow(e);
                }
            } else {
                numRequestedBuffers.incrementAndGet();
                break;
            }
        }
        hardBackpressureTimerGauge.markEnd();

        return memorySegment;
    }

    /**
     * @return a memory segment from the internal buffer queue.
     */
    private MemorySegment requestBufferBlockingFromQueue() {
        CompletableFuture<Void> requestBufferFuture = new CompletableFuture<>();
        scheduleCheckRequestBufferFuture(
                requestBufferFuture, INITIAL_REQUEST_BUFFER_TIMEOUT_FOR_RECLAIMING_MS);

        MemorySegment memorySegment = null;
        try {
            memorySegment = bufferQueue.take();
        } catch (InterruptedException e) {
            ExceptionUtils.rethrow(e);
        } finally {
            requestBufferFuture.complete(null);
        }

        return memorySegment;
    }

    private void scheduleCheckRequestBufferFuture(
            CompletableFuture<Void> requestBufferFuture, long delayMs) {
        if (!mayReclaimBuffer || requestBufferFuture.isDone()) {
            return;
        }
        checkNotNull(executor)
                .schedule(
                        // The delay time will be doubled after each check to avoid checking the
                        // future too frequently.
                        () -> internalCheckRequestBufferFuture(requestBufferFuture, delayMs * 2),
                        delayMs,
                        TimeUnit.MILLISECONDS);
    }

    private void internalCheckRequestBufferFuture(
            CompletableFuture<Void> requestBufferFuture, long delayForNextCheckMs) {
        if (requestBufferFuture.isDone()) {
            return;
        }
        reclaimBuffersIfNeeded(delayForNextCheckMs);
        scheduleCheckRequestBufferFuture(requestBufferFuture, delayForNextCheckMs);
    }

    private void incNumRequestedBuffer(Object owner) {
        numOwnerRequestedBuffers.compute(
                owner, (ignore, numRequested) -> numRequested == null ? 1 : numRequested + 1);
    }

    private void decNumRequestedBuffer(Object owner) {
        numOwnerRequestedBuffers.compute(
                owner, (ignore, numRequested) -> checkNotNull(numRequested) - 1);
    }

    private void reclaimBuffersIfNeeded(long delayForNextCheckMs) {
        if (shouldReclaimBuffersBeforeRequesting(delayForNextCheckMs)) {
            bufferReclaimRequestListeners.forEach(Runnable::run);
        }
    }

    private boolean shouldReclaimBuffersBeforeRequesting(long delayForNextCheckMs) {
        // The accuracy of the memory usage ratio may be compromised due to the varying buffer pool
        // sizes. However, this only impacts a single iteration of the buffer usage check. Upon the
        // next iteration, the buffer reclaim will eventually be triggered.
        int numTotal = bufferPool.getNumBuffers();
        int numRequested = numRequestedBuffers.get();

        // Because we do the checking before requesting buffers, we need add additional one
        // buffer when calculating the usage ratio.
        return (numRequested + 1 - bufferQueue.size()) * 1.0 / numTotal
                        > numTriggerReclaimBuffersRatio
                || delayForNextCheckMs > MAX_DELAY_TIME_TO_TRIGGER_RECLAIM_BUFFER_MS
                        && bufferQueue.size() == 0;
    }

    /** Note that this method may be called by the netty thread. */
    private void recycleBuffer(Object owner, MemorySegment buffer) {
        try {
            releasedStateLock.readLock().lock();
            if (!isReleased && numRequestedBuffers.get() <= bufferPool.getNumBuffers()) {
                bufferQueue.add(buffer);
            } else {
                bufferPool.recycle(buffer);
                numRequestedBuffers.decrementAndGet();
            }
        } finally {
            releasedStateLock.readLock().unlock();
        }
        decNumRequestedBuffer(owner);
    }

    private void checkIsInitialized() {
        checkState(isInitialized, "The memory manager is not in the running state.");
    }
}
