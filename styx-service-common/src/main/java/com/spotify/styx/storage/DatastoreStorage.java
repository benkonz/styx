/*-
 * -\-\-
 * Spotify Styx Common
 * --
 * Copyright (C) 2016 Spotify AB
 * --
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
 * -/-/-
 */

package com.spotify.styx.storage;

import static com.google.api.client.util.BackOff.STOP;
import static com.spotify.styx.serialization.Json.OBJECT_MAPPER;
import static com.spotify.styx.storage.Storage.GLOBAL_RESOURCE_ID;
import static com.spotify.styx.util.CloserUtil.register;
import static com.spotify.styx.util.FutureUtil.gatherIO;
import static com.spotify.styx.util.ShardedCounter.KIND_COUNTER_LIMIT;
import static com.spotify.styx.util.ShardedCounter.KIND_COUNTER_SHARD;
import static com.spotify.styx.util.ShardedCounter.NUM_SHARDS;
import static com.spotify.styx.util.ShardedCounter.PROPERTY_COUNTER_ID;
import static com.spotify.styx.util.ShardedCounter.PROPERTY_LIMIT;
import static com.spotify.styx.util.ShardedCounter.PROPERTY_SHARD_INDEX;
import static com.spotify.styx.util.ShardedCounter.PROPERTY_SHARD_VALUE;
import static java.util.concurrent.CompletableFuture.delayedExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.client.util.Sets;
import com.google.cloud.Timestamp;
import com.google.cloud.datastore.DatastoreException;
import com.google.cloud.datastore.DatastoreReader;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.EntityQuery;
import com.google.cloud.datastore.Key;
import com.google.cloud.datastore.KeyFactory;
import com.google.cloud.datastore.KeyQuery;
import com.google.cloud.datastore.PathElement;
import com.google.cloud.datastore.Query;
import com.google.cloud.datastore.StringValue;
import com.google.cloud.datastore.StructuredQuery.CompositeFilter;
import com.google.cloud.datastore.StructuredQuery.Filter;
import com.google.cloud.datastore.StructuredQuery.PropertyFilter;
import com.google.cloud.datastore.Value;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.io.Closer;
import com.spotify.styx.model.Backfill;
import com.spotify.styx.model.BackfillBuilder;
import com.spotify.styx.model.ExecutionDescription;
import com.spotify.styx.model.Resource;
import com.spotify.styx.model.Schedule;
import com.spotify.styx.model.StyxConfig;
import com.spotify.styx.model.TriggerParameters;
import com.spotify.styx.model.Workflow;
import com.spotify.styx.model.WorkflowId;
import com.spotify.styx.model.WorkflowInstance;
import com.spotify.styx.model.WorkflowState;
import com.spotify.styx.model.WorkflowWithState;
import com.spotify.styx.state.Message;
import com.spotify.styx.state.RunState;
import com.spotify.styx.state.RunState.State;
import com.spotify.styx.state.StateData;
import com.spotify.styx.util.MDCUtil;
import com.spotify.styx.util.TimeUtil;
import com.spotify.styx.util.TriggerInstantSpec;
import com.spotify.styx.util.TriggerUtil;
import io.grpc.Context;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A backend for {@link AggregateStorage} backed by Google Datastore
 */
public class DatastoreStorage implements Closeable {

  private static final Logger LOG = LoggerFactory.getLogger(DatastoreStorage.class);

  private static final String KIND_STYX_CONFIG = "StyxConfig";
  private static final String KIND_WORKFLOW = "Workflow";
  static final String KIND_ACTIVE_WORKFLOW_INSTANCE = "ActiveWorkflowInstance";
  private static final String KIND_ACTIVE_WORKFLOW_INSTANCE_INDEX_SHARD = "ActiveWorkflowInstanceIndexShard";
  private static final String KIND_ACTIVE_WORKFLOW_INSTANCE_INDEX_SHARD_ENTRY = "ActiveWorkflowInstanceIndexShardEntry";
  private static final String KIND_BACKFILL = "Backfill";

  private static final String PROPERTY_CONFIG_ENABLED = "enabled";
  static final String PROPERTY_CONFIG_DOCKER_RUNNER_ID = "dockerRunnerId";
  static final String PROPERTY_CONFIG_FLYTE_RUNNER_ID = "flyteRunnerId";
  private static final String PROPERTY_CONFIG_CONCURRENCY = "concurrency";
  static final String PROPERTY_CONFIG_CLIENT_BLACKLIST = "clientBlacklist";
  private static final String PROPERTY_CONFIG_DEBUG_ENABLED = "debug";

  static final String PROPERTY_WORKFLOW_JSON = "json";
  private static final String PROPERTY_WORKFLOW_ENABLED = "enabled";
  private static final String PROPERTY_NEXT_NATURAL_TRIGGER = "nextNaturalTrigger";
  private static final String PROPERTY_NEXT_NATURAL_OFFSET_TRIGGER = "nextNaturalOffsetTrigger";
  static final String PROPERTY_COUNTER = "counter";
  static final String PROPERTY_COMPONENT = "component";
  static final String PROPERTY_WORKFLOW = "workflow";
  static final String PROPERTY_PARAMETER = "parameter";
  static final String PROPERTY_CONCURRENCY = "concurrency";
  static final String PROPERTY_START = "start";
  static final String PROPERTY_END = "end";
  static final String PROPERTY_NEXT_TRIGGER = "nextTrigger";
  static final String PROPERTY_SCHEDULE = "schedule";
  static final String PROPERTY_ALL_TRIGGERED = "allTriggered";
  static final String PROPERTY_HALTED = "halted";
  static final String PROPERTY_REVERSE = "reverse";
  static final String PROPERTY_DESCRIPTION = "description";
  static final String PROPERTY_TRIGGER_PARAMETERS = "triggerParameters";
  private static final String PROPERTY_SUBMISSION_RATE_LIMIT = "submissionRateLimit";
  static final String PROPERTY_CREATED = "created";
  static final String PROPERTY_LAST_MODIFIED = "lastModified";

  private static final String PROPERTY_STATE = "state";
  private static final String PROPERTY_STATE_TIMESTAMP = "stateTimestamp";
  private static final String PROPERTY_STATE_TRIGGER_TYPE = "triggerType";
  private static final String PROPERTY_STATE_TRIGGER_ID = "triggerId";
  private static final String PROPERTY_STATE_TRIES = "tries";
  private static final String PROPERTY_STATE_CONSECUTIVE_FAILURES = "consecutiveFailures";
  private static final String PROPERTY_STATE_RETRY_COST = "retryCost";
  private static final String PROPERTY_STATE_MESSAGES = "messages";
  private static final String PROPERTY_STATE_RETRY_DELAY_MILLIS = "retryDelayMillis";
  private static final String PROPERTY_STATE_LAST_EXIT = "lastExit";
  private static final String PROPERTY_STATE_EXECUTION_ID = "executionId";
  private static final String PROPERTY_STATE_RUNNER_ID = "runnerId";
  private static final String PROPERTY_STATE_EXECUTION_DESCRIPTION = "executionDescription";
  private static final String PROPERTY_STATE_RESOURCE_IDS = "resourceIds";
  private static final String PROPERTY_STATE_TRIGGER_PARAMETERS = "triggerParameters";

  private static final String KEY_GLOBAL_CONFIG = "styxGlobal";

  private static final boolean DEFAULT_CONFIG_ENABLED = true;
  private static final String DEFAULT_CONFIG_DOCKER_RUNNER_ID = "default";
  private static final String DEFAULT_CONFIG_FLYTE_RUNNER_ID = "default";
  private static final boolean DEFAULT_WORKFLOW_ENABLED = false;
  private static final boolean DEFAULT_CONFIG_DEBUG_ENABLED = false;

  private static final int ACTIVE_WORKFLOW_INSTANCE_INDEX_SHARDS = 128;

  private static final int MAX_NUMBER_OF_ENTITIES_IN_ONE_BATCH_READ = 1000;
  private static final int MAX_NUMBER_OF_ENTITIES_IN_ONE_BATCH_WRITE = 500;

  private static final int REQUEST_CONCURRENCY = 32;

  private final Closer closer = Closer.create();

  private final CheckedDatastore datastore;
  private final Function<CheckedDatastoreTransaction, DatastoreStorageTransaction> storageTransactionFactory;
  private final Executor executor;
  private final Logger log;

  DatastoreStorage(CheckedDatastore datastore) {
    this(datastore, DatastoreStorageTransaction::new, new ForkJoinPool(REQUEST_CONCURRENCY), LOG);
  }

  @VisibleForTesting
  DatastoreStorage(CheckedDatastore datastore,
                   Function<CheckedDatastoreTransaction, DatastoreStorageTransaction> storageTransactionFactory,
                   ExecutorService executor, Logger log) {
    this.datastore = Objects.requireNonNull(datastore);
    this.storageTransactionFactory = Objects.requireNonNull(storageTransactionFactory);
    this.executor = MDCUtil.withMDC(Context.currentContextExecutor(
        register(closer, Objects.requireNonNull(executor, "executor"), "datastore-storage")));
    this.log = Objects.requireNonNull(log, "log");
  }

  @Override
  public void close() throws IOException {
    closer.close();
  }

  StyxConfig config() throws IOException {
    final Entity entity = asBuilderOrNew(
        getOpt(datastore, globalConfigKey(datastore.newKeyFactory())),
        globalConfigKey(datastore.newKeyFactory()))
        .build();
    return entityToConfig(entity);
  }

  private StyxConfig entityToConfig(Entity entity) {
    return StyxConfig.newBuilder()
        .globalConcurrency(readOpt(entity, PROPERTY_CONFIG_CONCURRENCY))
        .globalEnabled(read(entity, PROPERTY_CONFIG_ENABLED, DEFAULT_CONFIG_ENABLED))
        .debugEnabled(read(entity, PROPERTY_CONFIG_DEBUG_ENABLED, DEFAULT_CONFIG_DEBUG_ENABLED))
        .submissionRateLimit(readOpt(entity, PROPERTY_SUBMISSION_RATE_LIMIT))
        .globalDockerRunnerId(
            read(entity, PROPERTY_CONFIG_DOCKER_RUNNER_ID, DEFAULT_CONFIG_DOCKER_RUNNER_ID))
        .globalFlyteRunnerId(
            read(entity, PROPERTY_CONFIG_FLYTE_RUNNER_ID, DEFAULT_CONFIG_FLYTE_RUNNER_ID))
        .clientBlacklist(this.<String>readStream(entity, PROPERTY_CONFIG_CLIENT_BLACKLIST)
            .collect(toSet()))
        .build();
  }

  boolean enabled(WorkflowId workflowId) throws IOException {
    return getWorkflowOpt(datastore, datastore::newKeyFactory, workflowId)
        .filter(w -> w.contains(PROPERTY_WORKFLOW_ENABLED))
        .map(workflow -> workflow.getBoolean(PROPERTY_WORKFLOW_ENABLED))
        .orElse(DEFAULT_WORKFLOW_ENABLED);
  }

  Set<WorkflowId> enabled() throws IOException {
    var queryWorkflows = EntityQuery.newEntityQueryBuilder().setKind(KIND_WORKFLOW).build();
    var enabledWorkflows = new HashSet<WorkflowId>();
    datastore.query(queryWorkflows, entity -> {
      var enabled = entity.contains(PROPERTY_WORKFLOW_ENABLED)
                    && entity.getBoolean(PROPERTY_WORKFLOW_ENABLED);
      if (enabled) {
        enabledWorkflows.add(parseWorkflowId(entity));
      }
    });

    return enabledWorkflows;
  }

  static Optional<Entity> getWorkflowOpt(final CheckedDatastoreTransaction tx,
                                         final WorkflowId workflowId) throws IOException {
    return getWorkflowOpt(tx, tx.getDatastore()::newKeyFactory, workflowId);
  }

  static Optional<Entity> getWorkflowOpt(final CheckedDatastoreReaderWriter datastore,
                                         final Supplier<KeyFactory> keyFactory,
                                         final WorkflowId workflowId) throws IOException {
    var key = DatastoreStorage.workflowKey(keyFactory, workflowId);
    return DatastoreStorage.getOpt(datastore, key);
  }

  void store(Workflow workflow) throws IOException {
    runInTransactionWithRetries(tx -> tx.store(workflow));
  }

  Optional<Workflow> workflow(WorkflowId workflowId) throws IOException {
    final Optional<Entity> entityOptional = getWorkflowOpt(datastore, datastore::newKeyFactory, workflowId);
    if (entityOptional.isPresent()) {
      return Optional.of(parseWorkflowJson(entityOptional.get(), workflowId));
    } else {
      return Optional.empty();
    }
  }

  void delete(WorkflowId workflowId) throws IOException {
    runInTransactionWithRetries(tx -> {
      tx.deleteWorkflow(workflowId);
      return null;
    });
  }

  public void updateNextNaturalTrigger(WorkflowId workflowId, TriggerInstantSpec triggerSpec) throws IOException {
    runInTransactionWithRetries(tx -> tx.updateNextNaturalTrigger(workflowId, triggerSpec));
  }

  Map<Workflow, TriggerInstantSpec> workflowsWithNextNaturalTrigger() throws IOException {
    final Set<WorkflowId> workflowIds = Sets.newHashSet();
    final KeyQuery query =
        Query.newKeyQueryBuilder()
            .setKind(KIND_WORKFLOW)
            .build();
    datastore.query(query, key -> workflowIds.add(WorkflowId.parseKey(key.getName())));
    return workflowsWithNextNaturalTrigger(workflowIds);
  }

  Map<Workflow, TriggerInstantSpec> workflowsWithNextNaturalTrigger(Set<WorkflowId> workflowIds) {
    final Iterable<List<WorkflowId>> batches = Iterables.partition(workflowIds,
        MAX_NUMBER_OF_ENTITIES_IN_ONE_BATCH_READ);
    return StreamSupport.stream(batches.spliterator(), false)
        .map(batch -> asyncIO(() -> this.workflowsWithNextNaturalTrigger(batch)))
        // `collect and stream` is crucial to make tasks running in parallel, otherwise they will
        // be processed sequentially. Without `collect`, it will try to submit and wait for each task
        // while iterating through the stream. This is somewhat subtle, so think twice.
        .collect(toList())
        .stream()
        .flatMap(task -> task.join().entrySet().stream())
        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  Map<Workflow, TriggerInstantSpec> workflowsWithNextNaturalTrigger(final List<WorkflowId> batch) throws IOException {
    final Map<Workflow, TriggerInstantSpec> map = Maps.newHashMap();
    final List<Key> keys = batch.stream()
        .map(workflowId -> workflowKey(datastore::newKeyFactory, workflowId))
        .collect(toList());
    datastore.get(keys, entity -> {
      final Workflow workflow;
      try {
        workflow = OBJECT_MAPPER.readValue(entity.getString(PROPERTY_WORKFLOW_JSON), Workflow.class);
      } catch (IOException e) {
        log.warn("Failed to read workflow {}.", entity.getKey(), e);
        return;
      }

      if (entity.contains(PROPERTY_NEXT_NATURAL_TRIGGER)) {
        Instant instant = timestampToInstant(entity.getTimestamp(PROPERTY_NEXT_NATURAL_TRIGGER));
        final Instant triggerInstant;

        // todo: this check is only needed during a transition period
        if (!entity.contains(PROPERTY_NEXT_NATURAL_OFFSET_TRIGGER)) {
          // instant has to be moved one schedule interval back
          final Schedule schedule = workflow.configuration().schedule();
          if (TimeUtil.isAligned(instant, schedule)) {
            instant = TimeUtil.previousInstant(instant, schedule);
          }
          triggerInstant = workflow.configuration().addOffset(instant);
        } else {
          triggerInstant = timestampToInstant(entity.getTimestamp(PROPERTY_NEXT_NATURAL_OFFSET_TRIGGER));
        }

        map.put(workflow, TriggerInstantSpec.create(instant, triggerInstant));
      }
    });
    return map;
  }

  public Map<WorkflowId, Workflow> workflows() throws IOException {
    var workflows = new HashMap<WorkflowId, Workflow>();
    var query = Query.newEntityQueryBuilder().setKind(KIND_WORKFLOW).build();
    datastore.query(query, entity -> {
      Workflow workflow;
      try {
        workflow = OBJECT_MAPPER.readValue(entity.getString(PROPERTY_WORKFLOW_JSON), Workflow.class);
      } catch (IOException e) {
        log.warn("Failed to read workflow {}.", entity.getKey(), e);
        return;
      }
      workflows.put(workflow.id(), workflow);
    });
    return workflows;
  }

  public Map<WorkflowId, Workflow> workflows(Set<WorkflowId> workflowIds) {
    final Iterable<List<WorkflowId>> batches = Iterables.partition(workflowIds,
        MAX_NUMBER_OF_ENTITIES_IN_ONE_BATCH_READ);
    return StreamSupport.stream(batches.spliterator(), false)
        .map(batch -> asyncIO(() -> this.getBatchOfWorkflows(batch)))
        // `collect and stream` is crucial to make tasks running in parallel, otherwise they will
        // be processed sequentially. Without `collect`, it will try to submit and wait for each task
        // while iterating through the stream. This is somewhat subtle, so think twice.
        .collect(toList())
        .stream()
        .flatMap(task -> task.join().stream())
        .collect(toMap(Workflow::id, Function.identity()));
  }

  private List<Workflow> getBatchOfWorkflows(final List<WorkflowId> batch) throws IOException {
    final List<Key> keys = batch.stream()
        .map(workflowId -> workflowKey(datastore::newKeyFactory, workflowId))
        .collect(toList());
    final List<Workflow> workflows = new ArrayList<>();
    datastore.get(keys, entity -> {
      try {
        workflows.add(OBJECT_MAPPER.readValue(entity.getString(PROPERTY_WORKFLOW_JSON), Workflow.class));
      } catch (IOException e) {
        log.warn("Failed to read workflow {}.", entity.getKey(), e);
      }
    });
    return workflows;
  }

  public List<Workflow> workflows(String componentId) throws IOException {
    final List<Workflow> workflows = Lists.newArrayList();
    final EntityQuery query = Query.newEntityQueryBuilder()
        .setKind(KIND_WORKFLOW)
        .setFilter(PropertyFilter.eq(PROPERTY_COMPONENT, componentId))
        .build();
    datastore.query(query, entity -> {
      final Workflow workflow;
      if (entity.contains(PROPERTY_WORKFLOW_JSON)) {
        try {
          workflow = OBJECT_MAPPER.readValue(entity.getString(PROPERTY_WORKFLOW_JSON), Workflow.class);
        } catch (IOException e) {
          log.warn("Failed to read workflow {}.", entity.getKey(), e);
          return;
        }
        workflows.add(workflow);
      }
    });
    return workflows;
  }

  Set<WorkflowInstance> listActiveInstances() throws IOException {
    var timeout = CompletableFuture.runAsync(() -> {}, delayedExecutor(30, SECONDS));
    return listActiveInstances0(timeout);
  }

  private Set<WorkflowInstance> listActiveInstances0(CompletionStage<Void> timeout) throws IOException {
    // Strongly read active state keys from index shards in parallel
    return gatherIO(activeWorkflowInstanceIndexShardKeys(datastore.newKeyFactory()).stream()
        .map(key -> asyncIO(() -> datastore.query(Query.newKeyQueryBuilder()
            .setFilter(PropertyFilter.hasAncestor(key))
            .setKind(KIND_ACTIVE_WORKFLOW_INSTANCE_INDEX_SHARD_ENTRY)
            .build())))
        .collect(toList()), timeout)
        .stream()
        .flatMap(Collection::stream)
        .map(Key::getName)
        .map(WorkflowInstance::parseKey)
        .collect(toSet());
  }

  /**
   * Strongly consistently read all active states
   */
  Map<WorkflowInstance, RunState> readActiveStates() throws IOException {
    var timeout = CompletableFuture.runAsync(() -> {}, delayedExecutor(30, SECONDS));

    var instances = listActiveInstances0(timeout);

    // Strongly consistently read values for the instances in parallel
    var states = gatherIO(Lists.partition(List.copyOf(instances), MAX_NUMBER_OF_ENTITIES_IN_ONE_BATCH_READ).stream()
        .map(batch -> asyncIO(() -> readRunStateBatch(batch)))
        .collect(toList()), timeout)
        .stream()
        .flatMap(Collection::stream)
        .collect(toMap(RunState::workflowInstance, Function.identity()));

    timeout.cancel(true);

    return states;
  }

  /**
   * Strongly consistently read a batch of {@link RunState}s.
   */
  private List<RunState> readRunStateBatch(List<WorkflowInstance> instances) throws IOException {
    assert instances.size() <= MAX_NUMBER_OF_ENTITIES_IN_ONE_BATCH_READ;
    final List<RunState> runStates = new ArrayList<>();
    var keyFactory = datastore.newKeyFactory();
    var keys = instances.stream().map(wfi -> activeWorkflowInstanceKey(keyFactory, wfi)).collect(toList());
    datastore.get(keys, entity ->
        runStates.add(entityToRunState(entity, parseWorkflowInstance(entity))));
    return runStates;
  }

  Map<WorkflowInstance, RunState> readActiveStates(String componentId) throws IOException {
    final EntityQuery query =
        Query.newEntityQueryBuilder().setKind(KIND_ACTIVE_WORKFLOW_INSTANCE)
            .setFilter(PropertyFilter.eq(PROPERTY_COMPONENT, componentId))
            .build();

    return queryActiveStates(query);
  }

  Map<WorkflowInstance, RunState> readActiveStates(String componentId, String workflowId) throws IOException {
    final EntityQuery query =
        Query.newEntityQueryBuilder().setKind(KIND_ACTIVE_WORKFLOW_INSTANCE)
            .setFilter(CompositeFilter.and(PropertyFilter.eq(PROPERTY_COMPONENT, componentId),
                PropertyFilter.eq(PROPERTY_WORKFLOW, workflowId)))
            .build();

    return queryActiveStates(query);
  }

  Map<WorkflowInstance, RunState> activeStatesByTriggerId(
      String triggerId) throws IOException {
    final EntityQuery query =
        Query.newEntityQueryBuilder().setKind(KIND_ACTIVE_WORKFLOW_INSTANCE)
            .setFilter(PropertyFilter.eq(PROPERTY_STATE_TRIGGER_ID, triggerId))
            .build();

    return queryActiveStates(query);
  }

  private Map<WorkflowInstance, RunState> queryActiveStates(EntityQuery activeStatesQuery)
      throws IOException {
    final ImmutableMap.Builder<WorkflowInstance, RunState> mapBuilder = ImmutableMap.builder();
    datastore.query(activeStatesQuery, entity -> {
      final WorkflowInstance instance = parseWorkflowInstance(entity);
      mapBuilder.put(instance, entityToRunState(entity, instance));
    });

    return mapBuilder.build();
  }

  Optional<RunState> readActiveState(WorkflowInstance instance) throws IOException {
    final Entity entity = datastore.get(activeWorkflowInstanceKey(instance));
    if (entity == null) {
      return Optional.empty();
    } else {
      return Optional.of(entityToRunState(entity, instance));
    }
  }

  static RunState entityToRunState(Entity entity, WorkflowInstance instance)
      throws IOException {
    final long counter = entity.getLong(PROPERTY_COUNTER);
    final State state = State.valueOf(entity.getString(PROPERTY_STATE));
    final long timestamp = entity.getLong(PROPERTY_STATE_TIMESTAMP);
    final StateData data = StateData.newBuilder()
        .tries((int) entity.getLong(PROPERTY_STATE_TRIES))
        .consecutiveFailures((int) entity.getLong(PROPERTY_STATE_CONSECUTIVE_FAILURES))
        .retryCost(entity.getDouble(PROPERTY_STATE_RETRY_COST))
        .trigger(DatastoreStorage.<String>readOpt(entity, PROPERTY_STATE_TRIGGER_TYPE).map(type ->
            TriggerUtil.trigger(type, entity.getString(PROPERTY_STATE_TRIGGER_ID))))
        .messages(OBJECT_MAPPER.<List<Message>>readValue(entity.getString(PROPERTY_STATE_MESSAGES),
            new TypeReference<List<Message>>() {}))
        .retryDelayMillis(readOpt(entity, PROPERTY_STATE_RETRY_DELAY_MILLIS))
        .lastExit(DatastoreStorage.<Long>readOpt(entity, PROPERTY_STATE_LAST_EXIT).map(Long::intValue))
        .executionId(readOpt(entity, PROPERTY_STATE_EXECUTION_ID))
        .runnerId(readOpt(entity, PROPERTY_STATE_RUNNER_ID))
        .executionDescription(readOptJson(entity, PROPERTY_STATE_EXECUTION_DESCRIPTION,
            ExecutionDescription.class))
        .resourceIds(readOptJson(entity, PROPERTY_STATE_RESOURCE_IDS,
            new TypeReference<Set<String>>() {}))
        .triggerParameters(readOptJson(entity, PROPERTY_STATE_TRIGGER_PARAMETERS, TriggerParameters.class))
        .build();
    return RunState.create(instance, state, data, Instant.ofEpochMilli(timestamp), counter);
  }

  WorkflowInstance writeActiveState(WorkflowInstance workflowInstance, RunState state)
      throws IOException {
    return runInTransactionWithRetries(tx -> tx.writeActiveState(workflowInstance, state));
  }

  private static List<Key> activeWorkflowInstanceIndexShardKeys(KeyFactory keyFactory) {
    return IntStream.range(0, ACTIVE_WORKFLOW_INSTANCE_INDEX_SHARDS)
        .mapToObj(DatastoreStorage::activeWorkflowInstanceIndexShardName)
        .map(name -> keyFactory.setKind(KIND_ACTIVE_WORKFLOW_INSTANCE_INDEX_SHARD).newKey(name))
        .collect(toList());
  }

  private static String activeWorkflowInstanceIndexShardName(String workflowInstanceKey) {
    final long hash = Hashing.murmur3_32().hashString(workflowInstanceKey, StandardCharsets.UTF_8).asInt();
    final long index = Long.remainderUnsigned(hash, ACTIVE_WORKFLOW_INSTANCE_INDEX_SHARDS);
    return activeWorkflowInstanceIndexShardName(index);
  }

  private static String activeWorkflowInstanceIndexShardName(long index) {
    return "shard-" + index;
  }

  static Key activeWorkflowInstanceIndexShardEntryKey(KeyFactory keyFactory, WorkflowInstance workflowInstance) {
    final String workflowInstanceKey = workflowInstance.toKey();
    return activeWorkflowInstanceIndexShardEntryKey(keyFactory, workflowInstanceKey);
  }

  private static Key activeWorkflowInstanceIndexShardEntryKey(KeyFactory keyFactory, String workflowInstanceKey) {
    return keyFactory.setKind(KIND_ACTIVE_WORKFLOW_INSTANCE_INDEX_SHARD_ENTRY)
        .addAncestor(PathElement.of(KIND_ACTIVE_WORKFLOW_INSTANCE_INDEX_SHARD,
            activeWorkflowInstanceIndexShardName(workflowInstanceKey)))
        .newKey(workflowInstanceKey);
  }

  static Entity runStateToEntity(KeyFactory keyFactory, WorkflowInstance wfi, RunState state)
      throws JsonProcessingException {
    final Key key = activeWorkflowInstanceKey(keyFactory, wfi);
    final Entity.Builder entity = Entity.newBuilder(key)
        .set(PROPERTY_COMPONENT, wfi.workflowId().componentId())
        .set(PROPERTY_WORKFLOW, wfi.workflowId().id())
        .set(PROPERTY_PARAMETER, wfi.parameter())
        .set(PROPERTY_COUNTER, state.counter());

    entity
        .set(PROPERTY_STATE, state.state().toString())
        .set(PROPERTY_STATE_TIMESTAMP, state.timestamp())
        .set(PROPERTY_STATE_TRIES, state.data().tries())
        .set(PROPERTY_STATE_CONSECUTIVE_FAILURES, state.data().consecutiveFailures())
        .set(PROPERTY_STATE_RETRY_COST, state.data().retryCost())
        // TODO: consider making this list bounded or not storing it here to avoid exceeding entity size limit
        .set(PROPERTY_STATE_MESSAGES, jsonValue(state.data().messages()));

    state.data().retryDelayMillis().ifPresent(v -> entity.set(PROPERTY_STATE_RETRY_DELAY_MILLIS, v));
    state.data().lastExit().ifPresent(v -> entity.set(PROPERTY_STATE_LAST_EXIT, v));
    state.data().trigger().ifPresent(trigger -> {
      entity.set(PROPERTY_STATE_TRIGGER_TYPE, TriggerUtil.triggerType(trigger));
      entity.set(PROPERTY_STATE_TRIGGER_ID, TriggerUtil.triggerId(trigger));
    });
    state.data().executionId().ifPresent(v -> entity.set(PROPERTY_STATE_EXECUTION_ID, v));
    state.data().runnerId().ifPresent(v -> entity.set(PROPERTY_STATE_RUNNER_ID, v));
    if (state.data().triggerParameters().isPresent()) {
      entity.set(PROPERTY_STATE_TRIGGER_PARAMETERS, jsonValue(state.data().triggerParameters().get()));
    }

    if (state.data().executionDescription().isPresent()) {
      entity.set(PROPERTY_STATE_EXECUTION_DESCRIPTION, jsonValue(state.data().executionDescription().get()));
    }
    if (state.data().resourceIds().isPresent()) {
      entity.set(PROPERTY_STATE_RESOURCE_IDS, jsonValue(state.data().resourceIds().get()));
    }

    return entity.build();
  }

  @VisibleForTesting
  static Entity workflowToEntity(Workflow workflow, WorkflowState state, Optional<Entity> existing, Key key)
      throws JsonProcessingException {
    var json = OBJECT_MAPPER.writeValueAsString(workflow);

    var builder = asBuilderOrNew(existing, key)
        .set(PROPERTY_COMPONENT, workflow.componentId())
        .set(PROPERTY_WORKFLOW_JSON, StringValue.newBuilder(json).setExcludeFromIndexes(true).build());

    state.enabled()
        .ifPresent(x -> builder.set(PROPERTY_WORKFLOW_ENABLED, x));
    state.nextNaturalTrigger()
        .ifPresent(x -> builder.set(PROPERTY_NEXT_NATURAL_TRIGGER, instantToTimestamp(x)));
    state.nextNaturalOffsetTrigger()
        .ifPresent(x -> builder.set(PROPERTY_NEXT_NATURAL_OFFSET_TRIGGER, instantToTimestamp(x)));

    return builder.build();
  }

  void deleteActiveState(WorkflowInstance workflowInstance) throws IOException {
    runInTransactionWithRetries(tx -> tx.deleteActiveState(workflowInstance));
  }

  void patchState(WorkflowId workflowId, WorkflowState state) throws IOException {
    runInTransactionWithRetries(tx -> tx.patchState(workflowId, state));
  }

  WorkflowState workflowState(WorkflowId workflowId) throws IOException {
    var workflowEntity = getWorkflowOpt(datastore, datastore::newKeyFactory, workflowId);
    return workflowState(workflowEntity);
  }

  Optional<WorkflowWithState> workflowWithState(WorkflowId workflowId) throws IOException {
    final Optional<Entity> workflowEntity = getWorkflowOpt(datastore, datastore::newKeyFactory, workflowId);

    if (workflowEntity.isEmpty()) {
      return Optional.empty();
    }

    var workflow = parseWorkflowJson(workflowEntity.orElseThrow(), workflowId);
    var workflowState = workflowState(workflowEntity);
    return Optional.of(WorkflowWithState.create(workflow, workflowState));
  }

  private WorkflowState workflowState(Optional<Entity> workflowEntity) {
    var builder = WorkflowState.builder();

    builder.enabled(workflowEntity.filter(w -> w.contains(PROPERTY_WORKFLOW_ENABLED))
        .map(workflow -> workflow.getBoolean(PROPERTY_WORKFLOW_ENABLED))
        .orElse(DEFAULT_WORKFLOW_ENABLED));
    getOptInstantProperty(workflowEntity, PROPERTY_NEXT_NATURAL_TRIGGER)
        .ifPresent(builder::nextNaturalTrigger);
    getOptInstantProperty(workflowEntity, PROPERTY_NEXT_NATURAL_OFFSET_TRIGGER)
        .ifPresent(builder::nextNaturalOffsetTrigger);

    return builder.build();
  }

  private Key activeWorkflowInstanceKey(WorkflowInstance workflowInstance) {
    return activeWorkflowInstanceKey(datastore.newKeyFactory(), workflowInstance);
  }

  static Key activeWorkflowInstanceKey(KeyFactory keyFactory, WorkflowInstance workflowInstance) {
    final String name = workflowInstance.toKey();
    return activeWorkflowInstanceKey(keyFactory, name);
  }

  private static Key activeWorkflowInstanceKey(KeyFactory keyFactory, String name) {
    return keyFactory
        .setKind(KIND_ACTIVE_WORKFLOW_INSTANCE)
        .newKey(name);
  }

  private WorkflowInstance parseWorkflowInstance(Entity activeWorkflowInstance) {
    final String componentId = activeWorkflowInstance.getString(PROPERTY_COMPONENT);
    final String workflowId = activeWorkflowInstance.getString(PROPERTY_WORKFLOW);
    final String parameter = activeWorkflowInstance.getString(PROPERTY_PARAMETER);

    return WorkflowInstance.create(WorkflowId.create(componentId, workflowId), parameter);
  }

  private WorkflowId parseWorkflowId(Entity workflow) {
    return WorkflowId.parseKey(workflow.getKey().getName());
  }

  static Workflow parseWorkflowJson(Entity entity, WorkflowId workflowId) throws IOException {
    try {
      return OBJECT_MAPPER
          .readValue(entity.getString(PROPERTY_WORKFLOW_JSON), Workflow.class);
    } catch (IOException e) {
      LOG.error("Failed to read workflow for {}, {}", workflowId.componentId(), workflowId.id(), e);
      throw e;
    }
  }

  /**
   * Optionally get an {@link Entity} from a {@link DatastoreReader}.
   *
   * @param datastore The reader to get from
   * @param key       The key to get
   *
   * @return an optional containing the entity if it existed, empty otherwise.
   */
  static Optional<Entity> getOpt(CheckedDatastoreReaderWriter datastore, Key key) throws IOException {
    return Optional.ofNullable(datastore.get(key));
  }

  /**
   * Optionally get an {@link Instant} value for an {@link Entity}'s property.
   *
   * @return an optional containing the property value if it existed, empty otherwise.
   */
  private static Optional<Instant> getOptInstantProperty(Optional<Entity> entity, String property) {
    return entity
        .filter(w -> w.contains(property))
        .map(workflow -> timestampToInstant(workflow.getTimestamp(property)));
  }

  /**
   * Convert an optional {@link Entity} into a builder if it exists, otherwise create a new builder.
   *
   * @param entityOpt The optional entity
   * @param key       The key for which to create a new builder if the entity is not present
   *
   * @return an entity builder either based of the given entity or a new one using the key.
   */
  private static Entity.Builder asBuilderOrNew(Optional<Entity> entityOpt, Key key) {
    return entityOpt
        .map(c -> Entity.newBuilder(key, c))
        .orElse(Entity.newBuilder(key));
  }

  static Key workflowKey(Supplier<KeyFactory> keyFactory, WorkflowId workflowId) {
    return keyFactory.get().setKind(KIND_WORKFLOW).newKey(workflowId.toKey());
  }

  static Key backfillKey(KeyFactory keyFactory, String backfillId) {
    return keyFactory.setKind(KIND_BACKFILL).newKey(backfillId);
  }

  static Key globalConfigKey(KeyFactory keyFactory) {
    return keyFactory.setKind(KIND_STYX_CONFIG).newKey(KEY_GLOBAL_CONFIG);
  }

  void setEnabled(WorkflowId workflowId1, boolean enabled) throws IOException {
    patchState(workflowId1, WorkflowState.patchEnabled(enabled));
  }

  static Timestamp instantToTimestamp(Instant instant) {
    return Timestamp.of(Date.from(instant));
  }

  private static Instant timestampToInstant(Timestamp ts) {
    return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
  }

  Optional<Resource> getResource(String id) throws IOException {
    Entity entity = datastore.get(datastore.newKeyFactory().setKind(KIND_COUNTER_LIMIT).newKey(id));
    if (entity == null) {
      return Optional.empty();
    }
    return Optional.of(entityToResource(entity));
  }

  void storeResource(Resource resource) throws IOException {
    runInTransactionWithRetries(transaction -> {
      transaction.store(resource);
      return null;
    });
  }

  List<Resource> getResources() throws IOException {
    final EntityQuery query = Query.newEntityQueryBuilder().setKind(KIND_COUNTER_LIMIT).build();
    final List<Resource> resources = Lists.newArrayList();
    datastore.query(query, entity ->
        resources.add(entityToResource(entity)));
    return resources;
  }

  /**
   * Delete resource by id. Deletes both counter shards and counter limit if it exists.
   *
   * <p>Due to Datastore limitations (modify max 25 entity groups per transaction),
   * we cannot do everything in one transaction.
   */
  void deleteResource(String id) throws IOException {
    datastore.delete(datastore.newKeyFactory().setKind(KIND_COUNTER_LIMIT).newKey(id));
    deleteShardsForCounter(id);
  }

  private void deleteShardsForCounter(String counterId) throws IOException {
    final List<Key> shards = new ArrayList<>();
    datastore.query(EntityQuery.newEntityQueryBuilder()
        .setKind(KIND_COUNTER_SHARD)
        .setFilter(PropertyFilter.eq(PROPERTY_COUNTER_ID, counterId))
        .build(), entity -> shards.add(entity.getKey()));

    // this is a safe guard to not to exceed max number of entities in one batch write
    // because in practice number of shards is much smaller
    for (List<Key> batch : Lists.partition(shards, MAX_NUMBER_OF_ENTITIES_IN_ONE_BATCH_WRITE)) {
      datastore.delete(batch.toArray(new Key[0]));
    }
  }

  private Resource entityToResource(Entity entity) {
    return Resource.create(entity.getKey().getName(), entity.getLong(PROPERTY_LIMIT));
  }

  Optional<Backfill> getBackfill(String id) throws IOException {
    final Entity entity = datastore.get(datastore.newKeyFactory().setKind(KIND_BACKFILL).newKey(id));
    if (entity == null) {
      return Optional.empty();
    }
    return Optional.of(entityToBackfill(entity));
  }

  private EntityQuery.Builder backfillQueryBuilder(boolean showAll, Filter... filters) {
    final EntityQuery.Builder queryBuilder = Query.newEntityQueryBuilder().setKind(KIND_BACKFILL);

    final List<Filter> andedFilters = Lists.newArrayList(filters);

    if (!showAll) {
      andedFilters.add(PropertyFilter.eq(PROPERTY_ALL_TRIGGERED, false));
      andedFilters.add(PropertyFilter.eq(PROPERTY_HALTED, false));
    }

    if (!andedFilters.isEmpty()) {
      final Filter head = andedFilters.get(0);
      final Filter[] tail = andedFilters.stream().skip(1).toArray(Filter[]::new);
      queryBuilder.setFilter(CompositeFilter.and(head, tail));
    }

    return queryBuilder;
  }

  private List<Backfill> backfillsForQuery(EntityQuery query) throws IOException {
    final List<Backfill> backfills = Lists.newArrayList();
    datastore.query(query, entity -> backfills.add(entityToBackfill(entity)));
    return backfills;
  }

  List<Backfill> getBackfills(boolean showAll) throws IOException {
    return backfillsForQuery(backfillQueryBuilder(showAll).build());
  }

  List<Backfill> getBackfillsForComponent(boolean showAll, String component) throws IOException {
    final EntityQuery query = backfillQueryBuilder(showAll,
        PropertyFilter.eq(PROPERTY_COMPONENT, component))
        .build();

    return backfillsForQuery(query);
  }

  List<Backfill> getBackfillsForWorkflow(boolean showAll, String workflow) throws IOException {
    final EntityQuery query = backfillQueryBuilder(showAll,
        PropertyFilter.eq(PROPERTY_WORKFLOW, workflow))
        .build();

    return backfillsForQuery(query);
  }

  List<Backfill> getBackfillsForWorkflowId(boolean showAll, WorkflowId workflowId, Optional<Instant> start)
      throws IOException {

    final List<Filter> filters = new ArrayList<>();
    filters.add(PropertyFilter.eq(PROPERTY_COMPONENT, workflowId.componentId()));
    filters.add(PropertyFilter.eq(PROPERTY_WORKFLOW, workflowId.id()));
    start.ifPresent(
        instant -> filters.add(PropertyFilter.ge(PROPERTY_START, instantToTimestamp(instant))));

    final EntityQuery query = backfillQueryBuilder(
        showAll,
        filters.toArray(new Filter[0]))
        .build();

    return backfillsForQuery(query);
  }

  static Backfill entityToBackfill(Entity entity) throws IOException {
    final WorkflowId workflowId = WorkflowId.create(entity.getString(PROPERTY_COMPONENT),
        entity.getString(PROPERTY_WORKFLOW));

    final BackfillBuilder builder = Backfill.newBuilder()
        .id(entity.getKey().getName())
        .start(timestampToInstant(entity.getTimestamp(PROPERTY_START)))
        .end(timestampToInstant(entity.getTimestamp(PROPERTY_END)))
        .workflowId(workflowId)
        .concurrency((int) entity.getLong(PROPERTY_CONCURRENCY))
        .nextTrigger(timestampToInstant(entity.getTimestamp(PROPERTY_NEXT_TRIGGER)))
        .schedule(Schedule.parse(entity.getString(PROPERTY_SCHEDULE)))
        .allTriggered(entity.getBoolean(PROPERTY_ALL_TRIGGERED))
        .halted(entity.getBoolean(PROPERTY_HALTED))
        .reverse(read(entity, PROPERTY_REVERSE, Boolean.FALSE));

    if (entity.contains(PROPERTY_DESCRIPTION)) {
      builder.description(entity.getString(PROPERTY_DESCRIPTION));
    }

    if (entity.contains(PROPERTY_TRIGGER_PARAMETERS)) {
      builder.triggerParameters(OBJECT_MAPPER.readValue(
          entity.getString(PROPERTY_TRIGGER_PARAMETERS), TriggerParameters.class));
    }

    if (entity.contains(PROPERTY_CREATED)) {
      builder.created(timestampToInstant(entity.getTimestamp(PROPERTY_CREATED)));
    }

    if (entity.contains(PROPERTY_LAST_MODIFIED)) {
      builder.lastModified(timestampToInstant(entity.getTimestamp(PROPERTY_LAST_MODIFIED)));
    }

    return builder.build();
  }

  void storeBackfill(Backfill backfill) throws IOException {
    runInTransactionWithRetries(tx -> tx.store(backfill));
  }

  private <T> Stream<T> readStream(Entity entity, String property) {
    return read(entity, property, Collections.<Value<T>>emptyList()).stream()
        .map(Value::get);
  }

  private static <T> Optional<T> readOpt(Entity entity, String property) {
    return entity.contains(property)
           ? Optional.of(entity.<Value<T>>getValue(property).get())
           : Optional.empty();
  }

  private static <T> Optional<T> readOptJson(Entity entity, String property, Class<T> cls) throws IOException {
    return entity.contains(property)
           ? Optional.of(OBJECT_MAPPER.readValue(entity.getString(property), cls))
           : Optional.empty();
  }

  private static <T> Optional<T> readOptJson(Entity entity, String property, TypeReference<T> valueTypeRef)
      throws IOException {
    return entity.contains(property)
           ? Optional.of(OBJECT_MAPPER.readValue(entity.getString(property), valueTypeRef))
           : Optional.empty();
  }

  private static <T> T read(Entity entity, String property, T defaultValue) {
    return DatastoreStorage.<T>readOpt(entity, property).orElse(defaultValue);
  }

  private static StringValue jsonValue(Object o) throws JsonProcessingException {
    return StringValue
        .newBuilder(OBJECT_MAPPER.writeValueAsString(o))
        .setExcludeFromIndexes(true)
        .build();
  }

  public <T, E extends Exception> T runInTransactionWithRetries(TransactionFunction<T, E> f)
      throws IOException, E {

    var retrySettings = datastore.getOptions().getRetrySettings();
    var backoff = new ExponentialBackOff.Builder()
        .setInitialIntervalMillis((int) retrySettings.getInitialRetryDelay().toMillis())
        .setMaxIntervalMillis((int) retrySettings.getMaxRetryDelay().toMillis())
        .setMultiplier(retrySettings.getRetryDelayMultiplier())
        .setMaxElapsedTimeMillis((int) retrySettings.getTotalTimeout().toMillis())
        .build();

    for (int attempt = 1; ; attempt++) {
      try {
        return runInTransaction(f);
      } catch (DatastoreIOException e) {
        if (!isRetryableTransactionException(e.getCause())) {
          throw e;
        }
        if (retrySettings.getMaxAttempts() > 0 && attempt >= retrySettings.getMaxAttempts()) {
          throw e;
        }
        // Back-off sleep before retrying
        var sleepMillis = backoff.nextBackOffMillis();
        if (sleepMillis == STOP) {
          throw e;
        }
        log.debug("datastore transaction exception, attempt #{}, retrying in {}ms", attempt, sleepMillis, e);
        sleepMillis(sleepMillis);
      }
    }
  }

  private <T, E extends Exception> T runInTransaction(TransactionFunction<T, E> f) throws IOException, E {
    var transaction = datastore.newTransaction();
    try {
      var result = f.apply(storageTransactionFactory.apply(transaction));
      transaction.commit();
      return result;
    } finally {
      if (transaction.isActive()) {
        try {
          transaction.rollback();
        } catch (DatastoreIOException ex) {
          log.debug("Exception on rollback", ex);
        }
      }
    }
  }

  private boolean isRetryableTransactionException(DatastoreException e) {
    return
        // code 10 ABORTED means that the transaction was aborted due to a conflict and can be retried.
        e.getCode() == 10
        || "ABORTED".equals(e.getReason())
        // The datastore client has a retry bug where it incorrectly retries ABORTED lookup operations
        // in transactions. This results in 3 INVALID_ARGUMENT "transaction closed" errors.
        // TODO: Remove after this bug has been fixed in datastore
        || String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).startsWith("transaction closed");
  }

  Map<Integer, Long> shardsForCounter(String counterId) throws IOException {
    final List<Key> shardKeys = IntStream.range(0, NUM_SHARDS).mapToObj(
            index -> datastore.newKeyFactory().setKind(KIND_COUNTER_SHARD).newKey(
                String.format("%s-%d", counterId, index)))
        .collect(toList());

    final Map<Integer, Long> fetchedShards = new HashMap<>();
    datastore.get(shardKeys, shard -> fetchedShards.put(
        (int) shard.getLong(PROPERTY_SHARD_INDEX),
        shard.getLong(PROPERTY_SHARD_VALUE)));
    return fetchedShards;
  }

  long getLimitForCounter(String counterId) throws IOException {
    if (GLOBAL_RESOURCE_ID.equals(counterId)) {
      // missing global resource means free to go
      return config().globalConcurrency().orElse(Long.MAX_VALUE);
    }

    final Key limitKey = datastore.newKeyFactory().setKind(KIND_COUNTER_LIMIT).newKey(counterId);
    final Entity limitEntity = datastore.get(limitKey);
    if (limitEntity == null) {
      throw new IllegalArgumentException("No limit found in Datastore for " + counterId);
    } else {
      return limitEntity.getLong(PROPERTY_LIMIT);
    }
  }

  private <T> CompletableFuture<T> asyncIO(IOOperation<T> f) {
    return f.executeAsync(executor);
  }

  static void sleepMillis(long sleepMillis) {
    try {
      Thread.sleep(sleepMillis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
