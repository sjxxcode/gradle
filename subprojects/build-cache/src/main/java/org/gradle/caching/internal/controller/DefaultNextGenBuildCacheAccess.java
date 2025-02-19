/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.caching.internal.controller;

import com.google.common.io.Closer;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.UnsynchronizedByteArrayOutputStream;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.internal.NextGenBuildCacheService;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.concurrent.ManagedThreadPoolExecutor;
import org.gradle.internal.file.BufferProvider;
import org.gradle.internal.operations.BuildOperationRef;
import org.gradle.internal.operations.CurrentBuildOperationRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Coordinates loading and storing entries in a local and a remote cache.
 *
 * When loading, entries unavailable locally are loaded tried from the remote cache.
 * Entries found in the remote cache are missored in the local cache.
 *
 * Downloads and uploads to and from the remote cache are handled via a thread pool in parallel.
 * However, both {@link #load(Map, LoadHandler)} and {@link #store(Map, StoreHandler)} wait for all
 * async operations to finish before returning.
 */
public class DefaultNextGenBuildCacheAccess implements NextGenBuildCacheAccess {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultNextGenBuildCacheAccess.class);

    private final NextGenBuildCacheService local;
    private final RemoteNextGenBuildCacheServiceHandler remote;
    private final BufferProvider bufferProvider;
    private final ManagedThreadPoolExecutor remoteProcessor;
    private final ConcurrencyCounter counter;

    public DefaultNextGenBuildCacheAccess(
        NextGenBuildCacheService local,
        RemoteNextGenBuildCacheServiceHandler remote,
        BufferProvider bufferProvider,
        ExecutorFactory executorFactory
    ) {
        this.local = local;
        this.remote = remote;
        this.bufferProvider = bufferProvider;
        // TODO Configure this properly, or better yet, replace with async HTTP and no thread pool
        this.remoteProcessor = executorFactory.createThreadPool("Build cache access", 256, 256, 10, TimeUnit.SECONDS);
        this.counter = new ConcurrencyCounter(remoteProcessor);
    }

    @Override
    public <T> void load(Map<BuildCacheKey, T> entries, LoadHandler<T> handler) {
        CompletableFuture<?>[] asyncLoads = entries.entrySet().stream()
            .flatMap(entry -> {
                BuildCacheKey key = entry.getKey();
                T payload = entry.getValue();
                boolean foundLocally;
                try {
                    foundLocally = local.load(key, input -> handler.handle(input, payload));
                } catch (Exception e) {
                    handler.recordUnpackFailure(key, e);
                    throw UncheckedException.throwAsUncheckedException(e);
                }
                if (!foundLocally && remote.canLoad()) {
                    // TODO Improve error handling
                    return Stream.of(CompletableFuture.runAsync(counter.wrap(new RemoteDownload<>(key, payload, handler)), remoteProcessor));
                } else {
                    return Stream.empty();
                }
            })
            .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(asyncLoads)
            .join();
    }

    @Override
    public <T> void store(Map<BuildCacheKey, T> entries, StoreHandler<T> handler) {
        CompletableFuture<?>[] asyncStores = entries.entrySet().stream()
            .flatMap(entry -> {
                BuildCacheKey key = entry.getKey();
                T payload = entry.getValue();
                if (!local.contains(key)) {
                    try {
                        local.store(key, handler.createWriter(payload));
                    } catch (Exception e) {
                        handler.recordPackFailure(key, e);
                        throw UncheckedException.throwAsUncheckedException(e);
                    }
                }
                // TODO Improve error handling
                if (remote.canStore()) {
                    return Stream.of(CompletableFuture.runAsync(counter.wrap(new RemoteUpload(key, handler)), remoteProcessor));
                } else {
                    return Stream.empty();
                }
            })
            .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(asyncStores)
            .join();
    }

    @Override
    public void close() throws IOException {
        Closer closer = Closer.create();
        closer.register(() -> {
            if (!remoteProcessor.getQueue().isEmpty() || remoteProcessor.getActiveCount() > 0) {
                LOGGER.warn("Waiting for remote cache uploads to finish");
            }
            try {
                remoteProcessor.stop(5, TimeUnit.MINUTES);
            } catch (RuntimeException e) {
                throw new RuntimeException("Couldn't finish uploading all remote entries", e);
            }
        });
        closer.register(local);
        closer.register(remote);
        closer.register(counter);
        closer.close();
    }

    private static class ConcurrencyCounter implements Closeable {
        private final AtomicInteger maxQueueLength = new AtomicInteger(0);
        private final AtomicInteger maxActiveCount = new AtomicInteger(0);

        private final ManagedThreadPoolExecutor processor;

        public ConcurrencyCounter(ManagedThreadPoolExecutor processor) {
            this.processor = processor;
        }

        public Runnable wrap(Runnable delegate) {
            return () -> {
                maxQueueLength.updateAndGet(max -> Integer.max(max, processor.getQueue().size()));
                maxActiveCount.updateAndGet(max -> Integer.max(max, processor.getActiveCount()));
                delegate.run();
            };
        }

        @Override
        public void close() {
            LOGGER.warn("Max concurrency encountered while processing remote cache entries: {}, max queue length: {}",
                maxActiveCount.get(), maxQueueLength.get());
        }
    }

    private static abstract class OperationPreservingRunnable implements Runnable {
        private final BuildOperationRef mainThreadOperation;

        public OperationPreservingRunnable() {
            this.mainThreadOperation = CurrentBuildOperationRef.instance().get();
        }

        @Override
        public void run() {
            BuildOperationRef originalOperation = CurrentBuildOperationRef.instance().get();
            CurrentBuildOperationRef.instance().set(mainThreadOperation);
            try {
                doRun();
            } finally {
                CurrentBuildOperationRef.instance().set(originalOperation);
            }
        }

        abstract protected void doRun();
    }

    private class RemoteDownload<T> extends OperationPreservingRunnable {
        private final BuildCacheKey key;
        private final T payload;
        private final LoadHandler<T> handler;

        public RemoteDownload(BuildCacheKey key, T payload, LoadHandler<T> handler) {
            this.key = key;
            this.payload = payload;
            this.handler = handler;
        }

        @Override
        protected void doRun() {
            handler.startLoadOperation(key);
            try {
                long size = load();
                if (size >= 0) {
                    handler.recordLoadHit(key, size);
                } else {
                    handler.recordLoadMiss(key);
                }
            } catch (Exception e) {
                handler.recordLoadFailure(key, e);
                remote.disableOnError();
            }
        }

        private long load() {
            LOGGER.warn("Loading {} from remote", key);
            AtomicLong size = new AtomicLong(-1);
            remote.load(key, input -> {
                // TODO Make this work for large pieces of content, too
                UnsynchronizedByteArrayOutputStream data = new UnsynchronizedByteArrayOutputStream();
                byte[] buffer = bufferProvider.getBuffer();
                IOUtils.copyLarge(input, data, buffer);
                LOGGER.warn("Found {} in remote (size: {})", key, data.size());
                size.set(data.size());

                // Mirror data in local cache
                local.store(key, new NextGenBuildCacheService.NextGenWriter() {
                    @Override
                    public InputStream openStream() {
                        return data.toInputStream();
                    }

                    @Override
                    public void writeTo(OutputStream output) throws IOException {
                        data.writeTo(output);
                    }

                    @Override
                    public long getSize() {
                        return data.size();
                    }
                });
                handler.handle(data.toInputStream(), payload);
            });
            return size.get();
        }
    }

    private class RemoteUpload extends OperationPreservingRunnable {
        private final BuildCacheKey key;
        private final StoreHandler<?> handler;

        public RemoteUpload(BuildCacheKey key, StoreHandler<?> handler) {
            this.key = key;
            this.handler = handler;
        }

        @Override
        protected void doRun() {
            // TODO Check contains only above a threshold
            if (remote.contains(key)) {
                LOGGER.warn("Not storing {} in remote", key);
                return;
            }

            // TODO Use a buffer fit for large files
            UnsynchronizedByteArrayOutputStream data = new UnsynchronizedByteArrayOutputStream();
            boolean foundLocally = local.load(key, input -> IOUtils.copyLarge(input, data, bufferProvider.getBuffer()));
            // TODO Handle this better? Do we need to hadnle it at all?
            if (!foundLocally) {
                throw new IllegalStateException("Couldn't store " + key + " in remote cache because it was missing from local cache");
            }

            store(data);
        }

        private void store(UnsynchronizedByteArrayOutputStream data) {
            handler.startStoreOperation(key);
            try {
                boolean stored = storeInner(data);
                handler.recordStoreFinished(key, stored);
            } catch (Exception e) {
                handler.recordStoreFailure(key, e);
                remote.disableOnError();
            }
        }

        private boolean storeInner(UnsynchronizedByteArrayOutputStream data) {
            LOGGER.warn("Storing {} in remote (size: {})", key, data.size());
            AtomicBoolean stored = new AtomicBoolean(false);
            remote.store(key, new NextGenBuildCacheService.NextGenWriter() {
                @Override
                public InputStream openStream() {
                    return data.toInputStream();
                }

                @Override
                public void writeTo(OutputStream output) throws IOException {
                    data.writeTo(output);
                    stored.set(true);
                }

                @Override
                public long getSize() {
                    return data.size();
                }
            });
            return stored.get();
        }
    }
}
