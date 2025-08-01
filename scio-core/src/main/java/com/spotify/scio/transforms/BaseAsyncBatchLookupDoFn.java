/*
 * Copyright 2023 Spotify AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.spotify.scio.transforms;

import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;

import com.google.common.cache.Cache;
import com.spotify.scio.transforms.BaseAsyncLookupDoFn.CacheSupplier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.ValueInSingleWindow;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions;
import org.apache.commons.lang3.tuple.Pair;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link DoFn} that performs asynchronous lookup using the provided client. Lookup requests may
 * be deduplicated.
 *
 * @param <Input> input element type.
 * @param <BatchRequest> batched input element type
 * @param <BatchResponse> batched output element type
 * @param <Output> client lookup value type.
 * @param <ClientType> client type.
 * @param <FutureType> future type.
 * @param <TryWrapper> client lookup value type wrapped in a Try.
 */
public abstract class BaseAsyncBatchLookupDoFn<
        Input, BatchRequest, BatchResponse, Output, ClientType, FutureType, TryWrapper>
    extends DoFnWithResource<Input, KV<Input, TryWrapper>, Pair<ClientType, Cache<String, Output>>>
    implements FutureHandlers.Base<FutureType, BatchResponse> {

  private static final Logger LOG = LoggerFactory.getLogger(BaseAsyncBatchLookupDoFn.class);

  // Data structures for handling async requests
  private final int batchSize;
  private final SerializableFunction<List<Input>, BatchRequest> batchRequestFn;
  private final SerializableFunction<BatchResponse, List<Pair<String, Output>>> batchResponseFn;
  private final SerializableFunction<Input, String> idExtractorFn;
  private final int maxPendingRequests;
  private final CacheSupplier<String, Output> cacheSupplier;

  private final Semaphore semaphore;
  private final ConcurrentMap<UUID, FutureType> futures = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, List<ValueInSingleWindow<Input>>> inputs =
      new ConcurrentHashMap<>();

  private final Queue<Input> batch = new ArrayDeque<>();
  private final ConcurrentLinkedQueue<Pair<UUID, List<ValueInSingleWindow<KV<Input, TryWrapper>>>>>
      results = new ConcurrentLinkedQueue<>();
  private long inputCount;
  private long outputCount;

  public BaseAsyncBatchLookupDoFn(
      int batchSize,
      SerializableFunction<List<Input>, BatchRequest> batchRequestFn,
      SerializableFunction<BatchResponse, List<Pair<String, Output>>> batchResponseFn,
      SerializableFunction<Input, String> idExtractorFn,
      int maxPendingRequests) {
    this(
        batchSize,
        batchRequestFn,
        batchResponseFn,
        idExtractorFn,
        maxPendingRequests,
        new BaseAsyncLookupDoFn.NoOpCacheSupplier<>());
  }

  public BaseAsyncBatchLookupDoFn(
      int batchSize,
      SerializableFunction<List<Input>, BatchRequest> batchRequestFn,
      SerializableFunction<BatchResponse, List<Pair<String, Output>>> batchResponseFn,
      SerializableFunction<Input, String> idExtractorFn,
      int maxPendingRequests,
      CacheSupplier<String, Output> cacheSupplier) {
    this.batchSize = batchSize;
    this.batchRequestFn = batchRequestFn;
    this.batchResponseFn = batchResponseFn;
    this.idExtractorFn = idExtractorFn;
    this.maxPendingRequests = maxPendingRequests;
    this.semaphore = new Semaphore(maxPendingRequests);
    this.cacheSupplier = cacheSupplier;
  }

  protected abstract ClientType newClient();

  public abstract FutureType asyncLookup(ClientType client, BatchRequest input);

  public abstract TryWrapper success(Output output);

  public abstract TryWrapper failure(Throwable throwable);

  @Override
  public Pair<ClientType, Cache<String, Output>> createResource() {
    return Pair.of(newClient(), cacheSupplier.get());
  }

  @Override
  public void closeResource(Pair<ClientType, Cache<String, Output>> resource) throws Exception {
    final ClientType client = resource.getLeft();
    if (client instanceof AutoCloseable) {
      ((AutoCloseable) client).close();
    }
  }

  public ClientType getResourceClient() {
    return getResource().getLeft();
  }

  public Cache<String, Output> getResourceCache() {
    return getResource().getRight();
  }

  @StartBundle
  public void startBundle(StartBundleContext context) {
    futures.clear();
    results.clear();
    inputs.clear();
    batch.clear();
    inputCount = 0;
    outputCount = 0;
    semaphore.drainPermits();
    semaphore.release(maxPendingRequests);
  }

  // kept for binary compatibility. Must not be used
  // TODO: remove in 0.15.0
  @Deprecated
  public void processElement(
      Input input,
      Instant timestamp,
      OutputReceiver<KV<Input, TryWrapper>> out,
      BoundedWindow window) {
    processElement(input, timestamp, window, null, out);
  }

  @ProcessElement
  public void processElement(
      @Element Input input,
      @Timestamp Instant timestamp,
      BoundedWindow window,
      PaneInfo pane,
      OutputReceiver<KV<Input, TryWrapper>> out) {
    inputCount++;
    flush(
        r -> {
          final KV<Input, TryWrapper> io = r.getValue();
          final Instant ts = r.getTimestamp();
          final Collection<BoundedWindow> ws = Collections.singleton(r.getWindow());
          final PaneInfo p = r.getPaneInfo();
          out.outputWindowedValue(io, ts, ws, p);
        });
    final Cache<String, Output> cache = getResourceCache();

    try {
      final String id = this.idExtractorFn.apply(input);
      requireNonNull(id, "idExtractorFn returned null");

      final Output cached = cache.getIfPresent(id);

      if (cached != null) {
        // found in cache
        out.output(KV.of(input, success(cached)));
        outputCount++;
      } else {
        inputs.compute(
            id,
            (k, v) -> {
              if (v == null) {
                v = new LinkedList<>();
                batch.add(input);
              }
              v.add(ValueInSingleWindow.of(input, timestamp, window, pane));
              return v;
            });
      }

      if (batch.size() >= batchSize) {
        createRequest();
      }

    } catch (InterruptedException e) {
      LOG.error("Failed to acquire semaphore", e);
      throw new RuntimeException("Failed to acquire semaphore", e);
    } catch (Exception e) {
      LOG.error("Failed to process element", e);
      throw e;
    }
  }

  @FinishBundle
  public void finishBundle(FinishBundleContext context) {

    // send remaining
    try {
      if (!batch.isEmpty()) {
        createRequest();
      }
      if (!futures.isEmpty()) {
        // Block until all pending futures are complete
        waitForFutures(futures.values());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.error("Failed to process futures", e);
      throw new RuntimeException("Failed to process futures", e);
    } catch (ExecutionException | TimeoutException e) {
      LOG.error("Failed to process futures", e);
      throw new RuntimeException("Failed to process futures", e);
    }
    flush(r -> context.output(r.getValue(), r.getTimestamp(), r.getWindow()));

    // Make sure all requests are processed
    Preconditions.checkState(
        inputCount == outputCount,
        "Expected requestCount == responseCount, but %s != %s",
        inputCount,
        outputCount);
  }

  private void createRequest() throws InterruptedException {
    final ClientType client = getResourceClient();
    final Cache<String, Output> cache = getResourceCache();
    final UUID key = UUID.randomUUID();
    final List<Input> elems = new ArrayList<>(batch);
    final BatchRequest request = batchRequestFn.apply(elems);

    // semaphore release is not performed on exception.
    // let beam retry the bundle. startBundle will reset the semaphore to the
    // maxPendingRequests permits.
    semaphore.acquire();
    final FutureType future = asyncLookup(client, request);
    // handle cache in fire & forget way
    handleCache(future, cache);
    // make sure semaphore are released when waiting for futures in finishBundle
    final FutureType unlockedFuture = handleSemaphore(future);

    futures.put(key, handleOutput(unlockedFuture, elems, key));
    batch.clear();
  }

  private FutureType handleOutput(FutureType future, List<Input> batchInput, UUID key) {
    final Map<String, Input> keyedInputs =
        batchInput.stream().collect(Collectors.toMap(idExtractorFn::apply, identity()));
    return addCallback(
        future,
        response -> {
          final Map<String, Output> keyedOutput =
              batchResponseFn.apply(response).stream()
                  .collect(Collectors.toMap(Pair::getKey, Pair::getValue));

          keyedInputs.forEach(
              (id, input) -> {
                final List<ValueInSingleWindow<Input>> processInputs = inputs.remove(id);
                if (processInputs == null) {
                  // no need to fail future here as we're only interested in its completion
                  // finishBundle will fail the checkState as we do not produce any result
                  LOG.error(
                      "The ID '{}' received in the gRPC batch response does not "
                          + "match any IDs extracted via the idExtractorFn for the requested  "
                          + "batch sent to the gRPC endpoint. Please ensure that the IDs returned "
                          + "from the gRPC endpoints match the IDs extracted using the provided"
                          + "idExtractorFn for the same input.",
                      id);
                } else {
                  List<ValueInSingleWindow<KV<Input, TryWrapper>>> batchResult =
                      processInputs.stream()
                          .map(
                              processInput -> {
                                final Input i = processInput.getValue();
                                final Output output = keyedOutput.get(id);
                                final TryWrapper o =
                                    output == null
                                        ? failure(new UnmatchedRequestException(id))
                                        : success(output);
                                final Instant ts = processInput.getTimestamp();
                                final BoundedWindow w = processInput.getWindow();
                                final PaneInfo p = processInput.getPaneInfo();
                                return ValueInSingleWindow.of(KV.of(i, o), ts, w, p);
                              })
                          .collect(Collectors.toList());
                  results.add(Pair.of(key, batchResult));
                }
              });
          return null;
        },
        throwable -> {
          keyedInputs.forEach(
              (id, element) -> {
                final List<ValueInSingleWindow<KV<Input, TryWrapper>>> batchResult =
                    inputs.remove(id).stream()
                        .map(
                            processInput -> {
                              final Input i = processInput.getValue();
                              final TryWrapper o = failure(throwable);
                              final Instant ts = processInput.getTimestamp();
                              final BoundedWindow w = processInput.getWindow();
                              final PaneInfo p = processInput.getPaneInfo();
                              return ValueInSingleWindow.of(KV.of(i, o), ts, w, p);
                            })
                        .collect(Collectors.toList());
                results.add(Pair.of(key, batchResult));
              });
          return null;
        });
  }

  private FutureType handleSemaphore(FutureType future) {
    return addCallback(
        future,
        ouput -> {
          semaphore.release();
          return null;
        },
        throwable -> {
          semaphore.release();
          return null;
        });
  }

  private FutureType handleCache(FutureType future, Cache<String, Output> cache) {
    return addCallback(
        future,
        response -> {
          batchResponseFn
              .apply(response)
              .forEach(
                  pair -> {
                    final String id = pair.getLeft();
                    final Output output = pair.getRight();
                    cache.put(id, output);
                  });
          return null;
        },
        throwable -> null);
  }

  // Flush pending elements errors and results
  private void flush(Consumer<ValueInSingleWindow<KV<Input, TryWrapper>>> outputFn) {
    Pair<UUID, List<ValueInSingleWindow<KV<Input, TryWrapper>>>> r = results.poll();
    while (r != null) {
      final UUID key = r.getKey();
      final List<ValueInSingleWindow<KV<Input, TryWrapper>>> batchResult = r.getValue();
      batchResult.forEach(outputFn);
      outputCount += batchResult.size();
      futures.remove(key);
      r = results.poll();
    }
  }
}
