package com.linkedin.venice.controller;

import static com.linkedin.venice.meta.Version.PushType.INCREMENTAL;
import static com.linkedin.venice.meta.Version.PushType.STREAM;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

import com.linkedin.venice.common.VeniceSystemStoreType;
import com.linkedin.venice.common.VeniceSystemStoreUtils;
import com.linkedin.venice.controller.kafka.consumer.AdminConsumerService;
import com.linkedin.venice.controller.kafka.protocol.serializer.AdminOperationSerializer;
import com.linkedin.venice.controller.multitaskscheduler.MultiTaskSchedulerService;
import com.linkedin.venice.controller.multitaskscheduler.StoreMigrationManager;
import com.linkedin.venice.controller.stats.DisabledPartitionStats;
import com.linkedin.venice.controller.stats.VeniceAdminStats;
import com.linkedin.venice.controllerapi.AdminOperationProtocolVersionControllerResponse;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.helix.HelixCustomizedViewOfflinePushRepository;
import com.linkedin.venice.helix.HelixExternalViewRepository;
import com.linkedin.venice.helix.SafeHelixDataAccessor;
import com.linkedin.venice.helix.SafeHelixManager;
import com.linkedin.venice.ingestion.control.RealTimeTopicSwitcher;
import com.linkedin.venice.meta.HybridStoreConfig;
import com.linkedin.venice.meta.Instance;
import com.linkedin.venice.meta.MaterializedViewParameters;
import com.linkedin.venice.meta.PartitionAssignment;
import com.linkedin.venice.meta.ReadWriteStoreRepository;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.meta.Version.PushType;
import com.linkedin.venice.meta.VersionStatus;
import com.linkedin.venice.meta.ViewConfig;
import com.linkedin.venice.meta.ViewConfigImpl;
import com.linkedin.venice.partitioner.DefaultVenicePartitioner;
import com.linkedin.venice.pubsub.PubSubTopicRepository;
import com.linkedin.venice.pubsub.api.PubSubTopic;
import com.linkedin.venice.pubsub.manager.TopicManager;
import com.linkedin.venice.pushmonitor.ExecutionStatus;
import com.linkedin.venice.utils.DataProviderUtils;
import com.linkedin.venice.utils.HelixUtils;
import com.linkedin.venice.utils.RegionUtils;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.locks.ClusterLockManager;
import com.linkedin.venice.views.MaterializedView;
import com.linkedin.venice.views.ViewUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.testng.annotations.Test;


public class TestVeniceHelixAdmin {
  private static final PubSubTopicRepository PUB_SUB_TOPIC_REPOSITORY = new PubSubTopicRepository();

  private static final String clusterName = "test-cluster";
  private static final String storeName = "test-store";

  @Test
  public void testDropResources() {
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    List<String> nodes = new ArrayList<>();
    String storeName = "abc";
    String instance = "node_1";
    String kafkaTopic = Version.composeKafkaTopic(storeName, 1);
    nodes.add(instance);
    Map<String, List<String>> listMap = new HashMap<>();
    List<String> partitions = new ArrayList<>(3);
    for (int partitionId = 0; partitionId < 3; partitionId++) {
      partitions.add(HelixUtils.getPartitionName(kafkaTopic, partitionId));
    }
    listMap.put(kafkaTopic, partitions);
    HelixAdminClient adminClient = mock(HelixAdminClient.class);
    HelixVeniceClusterResources veniceClusterResources = mock(HelixVeniceClusterResources.class);
    HelixExternalViewRepository repository = mock(HelixExternalViewRepository.class);
    PartitionAssignment partitionAssignment = mock(PartitionAssignment.class);
    doReturn(adminClient).when(veniceHelixAdmin).getHelixAdminClient();
    doReturn(listMap).when(adminClient).getDisabledPartitionsMap(clusterName, instance);
    doReturn(3).when(partitionAssignment).getExpectedNumberOfPartitions();
    doReturn(veniceClusterResources).when(veniceHelixAdmin).getHelixVeniceClusterResources(anyString());
    doReturn(repository).when(veniceClusterResources).getRoutingDataRepository();
    doReturn(nodes).when(veniceHelixAdmin).getStorageNodes(anyString());
    doReturn(partitionAssignment).when(repository).getPartitionAssignments(anyString());
    doReturn(mock(DisabledPartitionStats.class)).when(veniceHelixAdmin).getDisabledPartitionStats(anyString());
    doCallRealMethod().when(veniceHelixAdmin).deleteHelixResource(anyString(), anyString());

    veniceHelixAdmin.deleteHelixResource(clusterName, kafkaTopic);
    verify(veniceHelixAdmin, times(1)).enableDisabledPartition(clusterName, kafkaTopic, false);
  }

  /**
   * This test verify that in function {@link VeniceHelixAdmin#setUpMetaStoreAndMayProduceSnapshot},
   * meta store RT topic creation has to happen before any writings to meta store's rt topic.
   * As of today, topic creation and checks to make sure that RT exists are handled in function
   * {@link VeniceHelixAdmin#ensureRealTimeTopicExistsForUserSystemStores}. On the other hand, as {@link VeniceHelixAdmin#storeMetadataUpdate}
   * writes to the same RT topic, it should happen after the above function. The following test enforces
   * such order at the statement level.
   *
   * Notice that if function semantics change over time, as long as the above invariant can be obtained,
   * it is okay to relax on the ordering enforcement or delete the unit test if necessary.
   */
  @Test
  public void enforceRealTimeTopicCreationBeforeWritingToMetaSystemStore() {
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    doNothing().when(veniceHelixAdmin).ensureRealTimeTopicExistsForUserSystemStores(anyString(), anyString());
    doCallRealMethod().when(veniceHelixAdmin).setUpMetaStoreAndMayProduceSnapshot(anyString(), anyString());

    InOrder inorder = inOrder(veniceHelixAdmin);

    HelixVeniceClusterResources veniceClusterResources = mock(HelixVeniceClusterResources.class);
    ReadWriteStoreRepository repo = mock(ReadWriteStoreRepository.class);
    Store store = mock(Store.class);

    doReturn(veniceClusterResources).when(veniceHelixAdmin).getHelixVeniceClusterResources(anyString());
    doReturn(repo).when(veniceClusterResources).getStoreMetadataRepository();
    doReturn(store).when(repo).getStore(anyString());
    doReturn(Boolean.FALSE).when(store).isDaVinciPushStatusStoreEnabled();

    veniceHelixAdmin.setUpMetaStoreAndMayProduceSnapshot(anyString(), anyString());

    // Enforce that ensureRealTimeTopicExistsForUserSystemStores happens before storeMetadataUpdate. See the above
    // comments for the reasons.
    inorder.verify(veniceHelixAdmin).ensureRealTimeTopicExistsForUserSystemStores(anyString(), anyString());
    inorder.verify(veniceHelixAdmin).storeMetadataUpdate(anyString(), anyString(), any());
  }

  @Test
  public void testGetOverallPushStatus() {
    ExecutionStatus veniceStatus = ExecutionStatus.COMPLETED;
    ExecutionStatus daVinciStatus = ExecutionStatus.COMPLETED;
    ExecutionStatus overallStatus = VeniceHelixAdmin.getOverallPushStatus(veniceStatus, daVinciStatus);

    assertEquals(overallStatus, ExecutionStatus.COMPLETED);

    veniceStatus = ExecutionStatus.ERROR;
    daVinciStatus = ExecutionStatus.COMPLETED;
    overallStatus = VeniceHelixAdmin.getOverallPushStatus(veniceStatus, daVinciStatus);
    assertEquals(overallStatus, ExecutionStatus.ERROR);

    veniceStatus = ExecutionStatus.ERROR;
    daVinciStatus = ExecutionStatus.ERROR;
    overallStatus = VeniceHelixAdmin.getOverallPushStatus(veniceStatus, daVinciStatus);
    assertEquals(overallStatus, ExecutionStatus.ERROR);

    veniceStatus = ExecutionStatus.COMPLETED;
    daVinciStatus = ExecutionStatus.DVC_INGESTION_ERROR_DISK_FULL;
    overallStatus = VeniceHelixAdmin.getOverallPushStatus(veniceStatus, daVinciStatus);
    assertEquals(overallStatus, ExecutionStatus.DVC_INGESTION_ERROR_DISK_FULL);

    veniceStatus = ExecutionStatus.ERROR;
    daVinciStatus = ExecutionStatus.DVC_INGESTION_ERROR_DISK_FULL;
    overallStatus = VeniceHelixAdmin.getOverallPushStatus(veniceStatus, daVinciStatus);
    assertEquals(overallStatus, ExecutionStatus.DVC_INGESTION_ERROR_DISK_FULL);
  }

  @Test
  public void testIsRealTimeTopicRequired() {
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    Store store = mock(Store.class, RETURNS_DEEP_STUBS);
    Version version = mock(Version.class);
    doCallRealMethod().when(veniceHelixAdmin).isRealTimeTopicRequired(store, version);

    // Case 1: Store is not hybrid
    doReturn(false).when(store).isHybrid();
    assertFalse(veniceHelixAdmin.isRealTimeTopicRequired(store, version));

    // Case 2: Store is hybrid and version is not hybrid
    doReturn(true).when(store).isHybrid();
    doReturn(false).when(version).isHybrid();

    // Case 3: Both store and version are hybrid && controller is child
    doReturn(true).when(store).isHybrid();
    doReturn(true).when(version).isHybrid();
    doReturn(false).when(veniceHelixAdmin).isParent();
    assertTrue(veniceHelixAdmin.isRealTimeTopicRequired(store, version));

    // Case 4: Both store and version are hybrid && controller is parent
    doReturn(true).when(veniceHelixAdmin).isParent();
    assertFalse(veniceHelixAdmin.isRealTimeTopicRequired(store, version));
  }

  @Test
  public void testCreateOrUpdateRealTimeTopics() {
    Store store = mock(Store.class, RETURNS_DEEP_STUBS);
    when(store.getName()).thenReturn(storeName);
    Version version = mock(Version.class);
    when(version.getStoreName()).thenReturn(storeName);

    // Case 1: Only one real-time topic is required
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    doCallRealMethod().when(veniceHelixAdmin).createOrUpdateRealTimeTopics(eq(clusterName), eq(store), eq(version));
    when(veniceHelixAdmin.getPubSubTopicRepository()).thenReturn(PUB_SUB_TOPIC_REPOSITORY);
    doNothing().when(veniceHelixAdmin)
        .createOrUpdateRealTimeTopic(eq(clusterName), eq(store), eq(version), any(PubSubTopic.class));
    veniceHelixAdmin.createOrUpdateRealTimeTopics(clusterName, store, version);
    // verify and capture the arguments passed to createOrUpdateRealTimeTopic
    ArgumentCaptor<PubSubTopic> pubSubTopicArgumentCaptor = ArgumentCaptor.forClass(PubSubTopic.class);
    verify(veniceHelixAdmin, times(1))
        .createOrUpdateRealTimeTopic(eq(clusterName), eq(store), eq(version), pubSubTopicArgumentCaptor.capture());
    assertEquals(pubSubTopicArgumentCaptor.getValue().getName(), storeName + "_rt");

    // Case 2: Both regular and separate real-time topics are required
    when(version.isSeparateRealTimeTopicEnabled()).thenReturn(true);
    veniceHelixAdmin.createOrUpdateRealTimeTopics(clusterName, store, version);
    pubSubTopicArgumentCaptor = ArgumentCaptor.forClass(PubSubTopic.class);
    // verify and capture the arguments passed to createOrUpdateRealTimeTopic
    verify(veniceHelixAdmin, times(3))
        .createOrUpdateRealTimeTopic(eq(clusterName), eq(store), eq(version), pubSubTopicArgumentCaptor.capture());
    Set<PubSubTopic> pubSubTopics = new HashSet<>(pubSubTopicArgumentCaptor.getAllValues());
    PubSubTopic separateRealTimeTopic = PUB_SUB_TOPIC_REPOSITORY.getTopic(storeName + "_rt_sep");
    assertTrue(pubSubTopics.contains(separateRealTimeTopic));
  }

  @Test
  public void testCreateOrUpdateRealTimeTopic() {
    int partitionCount = 10;
    Store store = mock(Store.class, RETURNS_DEEP_STUBS);
    when(store.getName()).thenReturn(storeName);
    Version version = mock(Version.class);
    when(version.getStoreName()).thenReturn(storeName);
    when(version.getPartitionCount()).thenReturn(partitionCount);
    PubSubTopicRepository pubSubTopicRepository = new PubSubTopicRepository();
    PubSubTopic pubSubTopic = pubSubTopicRepository.getTopic(storeName + "_rt");
    TopicManager topicManager = mock(TopicManager.class);
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    when(veniceHelixAdmin.getTopicManager()).thenReturn(topicManager);

    // Case 1: Real-time topic already exists
    doCallRealMethod().when(veniceHelixAdmin)
        .createOrUpdateRealTimeTopic(eq(clusterName), eq(store), eq(version), any(PubSubTopic.class));
    when(veniceHelixAdmin.getPubSubTopicRepository()).thenReturn(pubSubTopicRepository);
    when(topicManager.containsTopic(pubSubTopic)).thenReturn(true);
    doNothing().when(veniceHelixAdmin)
        .validateAndUpdateTopic(eq(pubSubTopic), eq(store), eq(version), eq(partitionCount), eq(topicManager));
    veniceHelixAdmin.createOrUpdateRealTimeTopic(clusterName, store, version, pubSubTopic);
    verify(veniceHelixAdmin, times(1))
        .validateAndUpdateTopic(eq(pubSubTopic), eq(store), eq(version), eq(partitionCount), eq(topicManager));
    verify(topicManager, never()).createTopic(
        any(PubSubTopic.class),
        anyInt(),
        anyInt(),
        anyLong(),
        anyBoolean(),
        any(Optional.class),
        anyBoolean());

    // Case 2: Real-time topic does not exist
    VeniceControllerClusterConfig clusterConfig = mock(VeniceControllerClusterConfig.class);
    when(veniceHelixAdmin.getControllerConfig(clusterName)).thenReturn(clusterConfig);
    when(topicManager.containsTopic(pubSubTopic)).thenReturn(false);
    veniceHelixAdmin.createOrUpdateRealTimeTopic(clusterName, store, version, pubSubTopic);
    verify(topicManager, times(1)).createTopic(
        eq(pubSubTopic),
        eq(partitionCount),
        anyInt(),
        anyLong(),
        anyBoolean(),
        any(Optional.class),
        anyBoolean());
  }

  @Test
  public void testValidateAndUpdateTopic() {
    PubSubTopic realTimeTopic = PUB_SUB_TOPIC_REPOSITORY.getTopic("testStore_rt");
    Store store = mock(Store.class, RETURNS_DEEP_STUBS);
    when(store.getName()).thenReturn("testStore");
    Version version = mock(Version.class);
    int expectedNumOfPartitions = 10;
    TopicManager topicManager = mock(TopicManager.class);

    // Case 1: Actual partition count is not equal to expected partition count
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    doCallRealMethod().when(veniceHelixAdmin)
        .validateAndUpdateTopic(
            any(PubSubTopic.class),
            any(Store.class),
            any(Version.class),
            anyInt(),
            any(TopicManager.class));
    when(version.getPartitionCount()).thenReturn(expectedNumOfPartitions);
    when(topicManager.getPartitionCount(realTimeTopic)).thenReturn(expectedNumOfPartitions - 1);
    Exception exception = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin
            .validateAndUpdateTopic(realTimeTopic, store, version, expectedNumOfPartitions, topicManager));
    assertTrue(exception.getMessage().contains("has different partition count"));

    // Case 2: Actual partition count is equal to expected partition count
    when(topicManager.getPartitionCount(realTimeTopic)).thenReturn(expectedNumOfPartitions);
    when(topicManager.updateTopicRetentionWithRetries(eq(realTimeTopic), anyLong())).thenReturn(true);
    veniceHelixAdmin.validateAndUpdateTopic(realTimeTopic, store, version, expectedNumOfPartitions, topicManager);
    verify(topicManager, times(1)).updateTopicRetentionWithRetries(eq(realTimeTopic), anyLong());
  }

  @Test
  public void testEnsureRealTimeTopicExistsForUserSystemStores() {
    String systemStoreName = VeniceSystemStoreType.DAVINCI_PUSH_STATUS_STORE.getSystemStoreName(storeName);
    int partitionCount = 10;
    Store userStore = mock(Store.class, RETURNS_DEEP_STUBS);
    when(userStore.getName()).thenReturn(storeName);
    Version version = mock(Version.class);
    when(version.getStoreName()).thenReturn(storeName);
    when(version.getPartitionCount()).thenReturn(partitionCount);
    when(userStore.getPartitionCount()).thenReturn(partitionCount);
    TopicManager topicManager = mock(TopicManager.class);
    PubSubTopicRepository pubSubTopicRepository = new PubSubTopicRepository();
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    doReturn(topicManager).when(veniceHelixAdmin).getTopicManager();
    doReturn(pubSubTopicRepository).when(veniceHelixAdmin).getPubSubTopicRepository();

    // Case 1: Store does not exist
    doReturn(null).when(veniceHelixAdmin).getStore(clusterName, storeName);
    doNothing().when(veniceHelixAdmin).checkControllerLeadershipFor(clusterName);
    doCallRealMethod().when(veniceHelixAdmin).ensureRealTimeTopicExistsForUserSystemStores(anyString(), anyString());
    Exception notFoundException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin.ensureRealTimeTopicExistsForUserSystemStores(clusterName, storeName));
    assertTrue(
        notFoundException.getMessage().contains("does not exist in"),
        "Actual message: " + notFoundException.getMessage());

    // Case 2: Store exists, but it's not user system store
    doReturn(userStore).when(veniceHelixAdmin).getStore(clusterName, storeName);
    Exception notUserSystemStoreException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin.ensureRealTimeTopicExistsForUserSystemStores(clusterName, storeName));
    assertTrue(
        notUserSystemStoreException.getMessage().contains("is not a user system store"),
        "Actual message: " + notUserSystemStoreException.getMessage());

    // Case 3: Store exists, it's a user system store, but real-time topic already exists
    Store systemStore = mock(Store.class, RETURNS_DEEP_STUBS);
    doReturn(systemStoreName).when(systemStore).getName();
    doReturn(Collections.emptyList()).when(systemStore).getVersions();
    doReturn(systemStore).when(veniceHelixAdmin).getStore(clusterName, systemStoreName);
    doReturn(true).when(topicManager).containsTopic(any(PubSubTopic.class));
    veniceHelixAdmin.ensureRealTimeTopicExistsForUserSystemStores(clusterName, systemStoreName);
    verify(topicManager, times(1)).containsTopic(any(PubSubTopic.class));

    HelixVeniceClusterResources veniceClusterResources = mock(HelixVeniceClusterResources.class);
    doReturn(veniceClusterResources).when(veniceHelixAdmin).getHelixVeniceClusterResources(clusterName);
    ClusterLockManager clusterLockManager = mock(ClusterLockManager.class);
    when(veniceClusterResources.getClusterLockManager()).thenReturn(clusterLockManager);

    // Case 4: Store exists, it's a user system store, first check if real-time topic exists returns false but
    // later RT topic was created
    topicManager = mock(TopicManager.class);
    doReturn(topicManager).when(veniceHelixAdmin).getTopicManager();
    doReturn(false).doReturn(true).when(topicManager).containsTopic(any(PubSubTopic.class));
    veniceHelixAdmin.ensureRealTimeTopicExistsForUserSystemStores(clusterName, systemStoreName);
    verify(topicManager, times(2)).containsTopic(any(PubSubTopic.class));
    verify(topicManager, never()).createTopic(
        any(PubSubTopic.class),
        anyInt(),
        anyInt(),
        anyLong(),
        anyBoolean(),
        any(Optional.class),
        anyBoolean());

    // Case 5: Store exists, it's a user system store, but real-time topic does not exist and there are no versions
    // and store partition count is zero. In this case, we want the RT topic partition count to use the default (1).
    VeniceControllerClusterConfig clusterConfig = mock(VeniceControllerClusterConfig.class);
    when(veniceHelixAdmin.getControllerConfig(clusterName)).thenReturn(clusterConfig);
    doReturn(0).when(systemStore).getPartitionCount();
    doReturn(false).when(topicManager).containsTopic(any(PubSubTopic.class));
    veniceHelixAdmin.ensureRealTimeTopicExistsForUserSystemStores(clusterName, systemStoreName); // should not throw
    ArgumentCaptor<Integer> partitionCountArgumentCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(topicManager, times(1)).createTopic(
        any(PubSubTopic.class),
        partitionCountArgumentCaptor.capture(),
        anyInt(),
        anyLong(),
        anyBoolean(),
        any(Optional.class),
        anyBoolean());
    assertEquals(
        partitionCountArgumentCaptor.getValue().intValue(),
        VeniceSystemStoreUtils.DEFAULT_USER_SYSTEM_STORE_PARTITION_COUNT);

    // Case 6: Store exists, it's a user system store, but real-time topic does not exist and there are no versions
    // hence create a new real-time topic should use store's partition count
    reset(topicManager);
    doReturn(false).when(topicManager).containsTopic(any(PubSubTopic.class));
    doReturn(null).when(systemStore).getVersion(anyInt());
    doReturn(5).when(systemStore).getPartitionCount();
    veniceHelixAdmin.ensureRealTimeTopicExistsForUserSystemStores(clusterName, systemStoreName);
    verify(topicManager, times(1)).createTopic(
        any(PubSubTopic.class),
        partitionCountArgumentCaptor.capture(),
        anyInt(),
        anyLong(),
        anyBoolean(),
        any(Optional.class),
        anyBoolean());
    assertEquals(partitionCountArgumentCaptor.getValue().intValue(), 5);

    // Case 7: Store exists, it's a user system store, but real-time topic does not exist and there are versions
    version = mock(Version.class);
    topicManager = mock(TopicManager.class);
    doReturn(topicManager).when(veniceHelixAdmin).getTopicManager();
    doReturn(false).when(topicManager).containsTopic(any(PubSubTopic.class));
    doReturn(version).when(systemStore).getVersion(anyInt());
    doReturn(10).when(version).getPartitionCount();
    veniceHelixAdmin.ensureRealTimeTopicExistsForUserSystemStores(clusterName, systemStoreName);
    partitionCountArgumentCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(topicManager, times(1)).createTopic(
        any(PubSubTopic.class),
        partitionCountArgumentCaptor.capture(),
        anyInt(),
        anyLong(),
        anyBoolean(),
        any(Optional.class),
        anyBoolean());
    assertEquals(partitionCountArgumentCaptor.getValue().intValue(), 10);
  }

  @Test
  public void testValidateStoreSetupForRTWrites() {
    String pushJobId = "pushJob123";
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    Store store = mock(Store.class, RETURNS_DEEP_STUBS);
    HelixVeniceClusterResources helixVeniceClusterResources = mock(HelixVeniceClusterResources.class);
    ReadWriteStoreRepository storeMetadataRepository = mock(ReadWriteStoreRepository.class);

    // Mock the method chain
    doReturn(helixVeniceClusterResources).when(veniceHelixAdmin).getHelixVeniceClusterResources(clusterName);
    doReturn(storeMetadataRepository).when(helixVeniceClusterResources).getStoreMetadataRepository();
    doReturn(store).when(storeMetadataRepository).getStore(storeName);

    doCallRealMethod().when(veniceHelixAdmin)
        .validateStoreSetupForRTWrites(anyString(), anyString(), anyString(), any(PushType.class));

    // Case 1: Store does not exist
    doReturn(null).when(storeMetadataRepository).getStore(storeName);
    Exception storeNotFoundException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin.validateStoreSetupForRTWrites(clusterName, storeName, pushJobId, STREAM));
    assertTrue(
        storeNotFoundException.getMessage().contains("does not exist"),
        "Actual message: " + storeNotFoundException.getMessage());

    // Case 2: Store exists but is not hybrid
    doReturn(store).when(storeMetadataRepository).getStore(storeName);
    doReturn(false).when(store).isHybrid();
    Exception nonHybridStoreException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin.validateStoreSetupForRTWrites(clusterName, storeName, pushJobId, STREAM));
    assertTrue(
        nonHybridStoreException.getMessage().contains("is not a hybrid store"),
        "Actual message: " + nonHybridStoreException.getMessage());

    // Case 3: Store is hybrid but pushType is INCREMENTAL and incremental push is not enabled
    doReturn(true).when(store).isHybrid();
    doReturn(false).when(store).isIncrementalPushEnabled();
    Exception incrementalPushNotEnabledException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin.validateStoreSetupForRTWrites(clusterName, storeName, pushJobId, INCREMENTAL));
    assertTrue(
        incrementalPushNotEnabledException.getMessage().contains("is not an incremental push store"),
        "Actual message: " + incrementalPushNotEnabledException.getMessage());
    verify(store, times(1)).isIncrementalPushEnabled();

    // Case 4: Store is hybrid and pushType is INCREMENTAL with incremental push enabled
    doReturn(true).when(store).isIncrementalPushEnabled();
    veniceHelixAdmin.validateStoreSetupForRTWrites(clusterName, storeName, pushJobId, INCREMENTAL);
    verify(store, times(2)).isIncrementalPushEnabled();
  }

  @Test
  public void testValidateTopicPresenceAndState() {
    String pushJobId = "pushJob123";
    PubSubTopic topic = mock(PubSubTopic.class);
    int partitionCount = 10;
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);

    TopicManager topicManager = mock(TopicManager.class);
    HelixVeniceClusterResources helixVeniceClusterResources = mock(HelixVeniceClusterResources.class);
    VeniceAdminStats veniceAdminStats = mock(VeniceAdminStats.class);

    doReturn(topicManager).when(veniceHelixAdmin).getTopicManager();
    doReturn(helixVeniceClusterResources).when(veniceHelixAdmin).getHelixVeniceClusterResources(clusterName);
    doReturn(veniceAdminStats).when(helixVeniceClusterResources).getVeniceAdminStats();

    doCallRealMethod().when(veniceHelixAdmin)
        .validateTopicPresenceAndState(
            anyString(),
            anyString(),
            anyString(),
            any(PushType.class),
            any(PubSubTopic.class),
            anyInt());

    // Case 1: Topic exists, all partitions are online, and topic is not truncated
    when(topicManager.containsTopicAndAllPartitionsAreOnline(topic, partitionCount)).thenReturn(true);
    when(veniceHelixAdmin.isTopicTruncated(topic.getName())).thenReturn(false);
    veniceHelixAdmin
        .validateTopicPresenceAndState(clusterName, storeName, pushJobId, PushType.BATCH, topic, partitionCount);
    verify(topicManager, times(1)).containsTopicAndAllPartitionsAreOnline(topic, partitionCount);
    verify(veniceHelixAdmin, times(1)).isTopicTruncated(topic.getName());

    // Case 2: Topic does not exist or not all partitions are online
    doReturn(false).when(topicManager).containsTopicAndAllPartitionsAreOnline(topic, partitionCount);
    Exception topicAbsentException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin
            .validateTopicPresenceAndState(clusterName, storeName, pushJobId, PushType.BATCH, topic, partitionCount));
    assertTrue(
        topicAbsentException.getMessage().contains("is either absent or being truncated"),
        "Actual message: " + topicAbsentException.getMessage());
    verify(veniceAdminStats, times(1)).recordUnexpectedTopicAbsenceCount();

    // Case 3: Topic exists, all partitions are online, but topic is truncated
    when(topicManager.containsTopicAndAllPartitionsAreOnline(topic, partitionCount)).thenReturn(true);
    when(veniceHelixAdmin.isTopicTruncated(topic.getName())).thenReturn(true);
    Exception topicTruncatedException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin
            .validateTopicPresenceAndState(clusterName, storeName, pushJobId, INCREMENTAL, topic, partitionCount));
    assertTrue(
        topicTruncatedException.getMessage().contains("is either absent or being truncated"),
        "Actual message: " + topicTruncatedException.getMessage());
    verify(veniceAdminStats, times(2)).recordUnexpectedTopicAbsenceCount();

    // Case 4: Validate behavior with different PushType (e.g., INCREMENTAL)
    when(topicManager.containsTopicAndAllPartitionsAreOnline(topic, partitionCount)).thenReturn(true);
    when(veniceHelixAdmin.isTopicTruncated(topic.getName())).thenReturn(false);
    veniceHelixAdmin
        .validateTopicPresenceAndState(clusterName, storeName, pushJobId, INCREMENTAL, topic, partitionCount);
    verify(topicManager, times(4)).containsTopicAndAllPartitionsAreOnline(topic, partitionCount);
    verify(veniceHelixAdmin, times(3)).isTopicTruncated(topic.getName());
  }

  @Test
  public void testValidateTopicForIncrementalPush() {
    String pushJobId = "pushJob123";
    int partitionCount = 10;
    Store store = mock(Store.class);
    Version referenceHybridVersion = mock(Version.class, RETURNS_DEEP_STUBS);
    PubSubTopicRepository topicRepository = new PubSubTopicRepository();

    doReturn(storeName).when(store).getName();
    doReturn(storeName).when(referenceHybridVersion).getStoreName();
    doReturn(partitionCount).when(referenceHybridVersion).getPartitionCount();
    PubSubTopic rtTopic = topicRepository.getTopic(Utils.getRealTimeTopicName(referenceHybridVersion));
    PubSubTopic separateRtTopic = topicRepository.getTopic(Utils.getSeparateRealTimeTopicName(rtTopic.getName()));

    VeniceHelixAdmin veniceHelixAdmin0 = mock(VeniceHelixAdmin.class);
    doReturn(topicRepository).when(veniceHelixAdmin0).getPubSubTopicRepository();
    doCallRealMethod().when(veniceHelixAdmin0)
        .validateTopicForIncrementalPush(anyString(), any(Store.class), any(Version.class), anyString());
    doNothing().when(veniceHelixAdmin0)
        .validateTopicPresenceAndState(
            anyString(),
            anyString(),
            anyString(),
            any(PushType.class),
            any(PubSubTopic.class),
            anyInt());

    // Case 1: Separate real-time topic is enabled, and both topics are valid
    doReturn(true).when(referenceHybridVersion).isSeparateRealTimeTopicEnabled();

    veniceHelixAdmin0.validateTopicForIncrementalPush(clusterName, store, referenceHybridVersion, pushJobId);

    verify(veniceHelixAdmin0, times(1))
        .validateTopicPresenceAndState(clusterName, storeName, pushJobId, INCREMENTAL, separateRtTopic, partitionCount);
    verify(veniceHelixAdmin0, times(1))
        .validateTopicPresenceAndState(clusterName, storeName, pushJobId, INCREMENTAL, rtTopic, partitionCount);

    // Case 2: Separate real-time topic is disabled, only real-time topic is validated
    VeniceHelixAdmin veniceHelixAdmin1 = mock(VeniceHelixAdmin.class);
    doReturn(topicRepository).when(veniceHelixAdmin1).getPubSubTopicRepository();
    doCallRealMethod().when(veniceHelixAdmin1)
        .validateTopicForIncrementalPush(anyString(), any(Store.class), any(Version.class), anyString());
    doNothing().when(veniceHelixAdmin1)
        .validateTopicPresenceAndState(
            anyString(),
            anyString(),
            anyString(),
            any(PushType.class),
            any(PubSubTopic.class),
            anyInt());

    doReturn(false).when(referenceHybridVersion).isSeparateRealTimeTopicEnabled();
    veniceHelixAdmin1.validateTopicForIncrementalPush(clusterName, store, referenceHybridVersion, pushJobId);
    verify(veniceHelixAdmin1, never())
        .validateTopicPresenceAndState(clusterName, storeName, pushJobId, INCREMENTAL, separateRtTopic, partitionCount);
    verify(veniceHelixAdmin1, times(1))
        .validateTopicPresenceAndState(clusterName, storeName, pushJobId, INCREMENTAL, rtTopic, partitionCount);

    // Case 3: Exception is thrown during validation of separate real-time topic
    doReturn(true).when(referenceHybridVersion).isSeparateRealTimeTopicEnabled();
    doThrow(new VeniceException("Separate real-time topic validation failed")).when(veniceHelixAdmin1)
        .validateTopicPresenceAndState(
            anyString(),
            anyString(),
            anyString(),
            any(PushType.class),
            eq(separateRtTopic),
            anyInt());

    Exception separateRtTopicException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin1.validateTopicForIncrementalPush(clusterName, store, referenceHybridVersion, pushJobId));
    assertTrue(
        separateRtTopicException.getMessage().contains("Separate real-time topic validation failed"),
        "Actual message: " + separateRtTopicException.getMessage());

    // Case 4: Exception is thrown during validation of real-time topic
    doNothing().when(veniceHelixAdmin1)
        .validateTopicPresenceAndState(
            anyString(),
            anyString(),
            anyString(),
            any(PushType.class),
            eq(separateRtTopic),
            anyInt());
    doThrow(new VeniceException("Real-time topic validation failed")).when(veniceHelixAdmin1)
        .validateTopicPresenceAndState(
            anyString(),
            anyString(),
            anyString(),
            any(PushType.class),
            eq(rtTopic),
            anyInt());

    Exception rtTopicException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin1.validateTopicForIncrementalPush(clusterName, store, referenceHybridVersion, pushJobId));
    assertTrue(
        rtTopicException.getMessage().contains("Real-time topic validation failed"),
        "Actual message: " + rtTopicException.getMessage());
  }

  @Test
  public void testGetReferenceHybridVersionForRealTimeWrites() {
    String pushJobId = "pushJob123";
    Store store = mock(Store.class, RETURNS_DEEP_STUBS);
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);

    // Mock method calls
    doReturn(storeName).when(store).getName();
    doCallRealMethod().when(veniceHelixAdmin)
        .getReferenceHybridVersionForRealTimeWrites(anyString(), any(Store.class), anyString());

    // Case 1: Store has no versions
    doReturn(Collections.emptyList()).when(store).getVersions();
    Exception noVersionsException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin.getReferenceHybridVersionForRealTimeWrites(clusterName, store, pushJobId));
    assertTrue(
        noVersionsException.getMessage().contains("is not initialized with a version yet."),
        "Actual message: " + noVersionsException.getMessage());

    // Case 2: Store has versions, but none are valid hybrid versions
    Version version1 = mock(Version.class);
    Version version2 = mock(Version.class);
    doReturn(Arrays.asList(version1, version2)).when(store).getVersions();
    doReturn(null).when(version1).getHybridStoreConfig();
    doReturn(null).when(version2).getHybridStoreConfig();

    Exception noValidHybridException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin.getReferenceHybridVersionForRealTimeWrites(clusterName, store, pushJobId));
    assertTrue(
        noValidHybridException.getMessage().contains("No valid hybrid store version"),
        "Actual message: " + noValidHybridException.getMessage());

    // Case 3: Store has valid hybrid versions, selects the highest version number
    Version validVersion1 = mock(Version.class, RETURNS_DEEP_STUBS);
    Version validVersion2 = mock(Version.class, RETURNS_DEEP_STUBS);

    doReturn(10).when(validVersion1).getNumber();
    doReturn(20).when(validVersion2).getNumber();
    doReturn(VersionStatus.ONLINE).when(validVersion1).getStatus();
    doReturn(VersionStatus.ONLINE).when(validVersion2).getStatus();
    HybridStoreConfig hybridStoreConfig = mock(HybridStoreConfig.class);
    doReturn(hybridStoreConfig).when(validVersion1).getHybridStoreConfig();
    doReturn(hybridStoreConfig).when(validVersion2).getHybridStoreConfig();
    doReturn(Arrays.asList(validVersion1, validVersion2)).when(store).getVersions();

    Version referenceVersion =
        veniceHelixAdmin.getReferenceHybridVersionForRealTimeWrites(clusterName, store, pushJobId);
    assertEquals(
        referenceVersion,
        validVersion2,
        "Expected the version with the highest version number to be selected.");

    // Case 4: Store has valid hybrid versions, but they are ERROR or KILLED
    doReturn(VersionStatus.ERROR).when(validVersion1).getStatus();
    doReturn(VersionStatus.KILLED).when(validVersion2).getStatus();
    Exception invalidHybridVersionException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin.getReferenceHybridVersionForRealTimeWrites(clusterName, store, pushJobId));
    assertTrue(
        invalidHybridVersionException.getMessage().contains("No valid hybrid store version"),
        "Actual message: " + invalidHybridVersionException.getMessage());
  }

  @Test
  public void testGetIncrementalPushVersion() {
    String pushJobId = "pushJob123";

    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    HelixVeniceClusterResources resources = mock(HelixVeniceClusterResources.class, RETURNS_DEEP_STUBS);
    ClusterLockManager lockManager = new ClusterLockManager(clusterName);
    Store store = mock(Store.class);
    Version hybridVersion = mock(Version.class);

    doReturn(resources).when(veniceHelixAdmin).getHelixVeniceClusterResources(clusterName);
    doReturn(lockManager).when(resources).getClusterLockManager();
    doReturn(hybridVersion).when(veniceHelixAdmin)
        .getReferenceHybridVersionForRealTimeWrites(clusterName, store, pushJobId);

    when(resources.getStoreMetadataRepository().getStore(storeName)).thenReturn(store);

    doCallRealMethod().when(veniceHelixAdmin).getIncrementalPushVersion(anyString(), anyString(), anyString());

    // Case 1: All validations pass, and the real-time topic is not required
    doNothing().when(veniceHelixAdmin).checkControllerLeadershipFor(clusterName);
    doNothing().when(veniceHelixAdmin)
        .validateStoreSetupForRTWrites(eq(clusterName), eq(storeName), eq(pushJobId), eq(INCREMENTAL));
    doReturn(false).when(veniceHelixAdmin).isRealTimeTopicRequired(store, hybridVersion);
    Version result = veniceHelixAdmin.getIncrementalPushVersion(clusterName, storeName, pushJobId);
    assertEquals(result, hybridVersion, "Expected the hybrid version to be returned.");

    // Case 2: Real-time topic is required, and validation succeeds
    doReturn(true).when(veniceHelixAdmin).isRealTimeTopicRequired(store, hybridVersion);
    doNothing().when(veniceHelixAdmin).validateTopicForIncrementalPush(clusterName, store, hybridVersion, pushJobId);
    result = veniceHelixAdmin.getIncrementalPushVersion(clusterName, storeName, pushJobId);
    assertEquals(result, hybridVersion, "Expected the hybrid version to be returned after topic validation.");
    verify(veniceHelixAdmin, times(1)).validateTopicForIncrementalPush(clusterName, store, hybridVersion, pushJobId);

    // Case 3: Real-time topic validation fails
    doThrow(new VeniceException("Real-time topic validation failed")).when(veniceHelixAdmin)
        .validateTopicForIncrementalPush(clusterName, store, hybridVersion, pushJobId);
    Exception rtTopicValidationException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin.getIncrementalPushVersion(clusterName, storeName, pushJobId));
    assertTrue(
        rtTopicValidationException.getMessage().contains("Real-time topic validation failed"),
        "Actual message: " + rtTopicValidationException.getMessage());

    // Case 4: Reference hybrid version retrieval fails
    doThrow(new VeniceException("No valid hybrid version found")).when(veniceHelixAdmin)
        .getReferenceHybridVersionForRealTimeWrites(clusterName, store, pushJobId);
    Exception hybridVersionException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin.getIncrementalPushVersion(clusterName, storeName, pushJobId));
    assertTrue(
        hybridVersionException.getMessage().contains("No valid hybrid version found"),
        "Actual message: " + hybridVersionException.getMessage());

    // Case 5: Store setup validation fails
    doThrow(new VeniceException("Store setup validation failed")).when(veniceHelixAdmin)
        .validateStoreSetupForRTWrites(eq(clusterName), eq(storeName), eq(pushJobId), eq(INCREMENTAL));

    Exception storeSetupValidationException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin.getIncrementalPushVersion(clusterName, storeName, pushJobId));
    assertTrue(
        storeSetupValidationException.getMessage().contains("Store setup validation failed"),
        "Actual message: " + storeSetupValidationException.getMessage());
  }

  @Test
  public void testGetReferenceVersionForStreamingWrites() {
    String pushJobId = "pushJob123";
    int partitionCount = 10;

    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    HelixVeniceClusterResources resources = mock(HelixVeniceClusterResources.class, RETURNS_DEEP_STUBS);
    ClusterLockManager lockManager = new ClusterLockManager(clusterName);
    Store store = mock(Store.class);
    Version hybridVersion = mock(Version.class);
    PubSubTopicRepository topicRepository = new PubSubTopicRepository();
    PubSubTopic rtTopic = topicRepository.getTopic(storeName + "_rt");

    doReturn(storeName).when(store).getName();
    doReturn(storeName).when(hybridVersion).getStoreName();
    doReturn(resources).when(veniceHelixAdmin).getHelixVeniceClusterResources(clusterName);
    doReturn(lockManager).when(resources).getClusterLockManager();
    doReturn(hybridVersion).when(veniceHelixAdmin)
        .getReferenceHybridVersionForRealTimeWrites(clusterName, store, pushJobId);
    doReturn(topicRepository).when(veniceHelixAdmin).getPubSubTopicRepository();
    doReturn(partitionCount).when(hybridVersion).getPartitionCount();

    when(resources.getStoreMetadataRepository().getStore(storeName)).thenReturn(store);

    doCallRealMethod().when(veniceHelixAdmin)
        .getReferenceVersionForStreamingWrites(anyString(), anyString(), anyString());

    // Case 1: All validations pass, and the controller is parent
    doNothing().when(veniceHelixAdmin).checkControllerLeadershipFor(clusterName);
    doNothing().when(veniceHelixAdmin)
        .validateStoreSetupForRTWrites(eq(clusterName), eq(storeName), eq(pushJobId), eq(PushType.STREAM));
    doReturn(true).when(veniceHelixAdmin).isParent();

    Version result = veniceHelixAdmin.getReferenceVersionForStreamingWrites(clusterName, storeName, pushJobId);
    assertEquals(result, hybridVersion, "Expected the hybrid version to be returned.");

    // Case 2: All validations pass, and the controller is not parent
    doReturn(false).when(veniceHelixAdmin).isParent();
    doNothing().when(veniceHelixAdmin)
        .validateTopicPresenceAndState(
            eq(clusterName),
            eq(storeName),
            eq(pushJobId),
            eq(PushType.STREAM),
            eq(rtTopic),
            eq(partitionCount));
    result = veniceHelixAdmin.getReferenceVersionForStreamingWrites(clusterName, storeName, pushJobId);
    assertEquals(result, hybridVersion, "Expected the hybrid version to be returned after topic validation.");
    verify(veniceHelixAdmin, times(1)).validateTopicPresenceAndState(
        eq(clusterName),
        eq(storeName),
        eq(pushJobId),
        eq(PushType.STREAM),
        eq(rtTopic),
        eq(partitionCount));

    // Case 3: Topic validation fails when the controller is not parent
    doThrow(new VeniceException("Real-time topic validation failed")).when(veniceHelixAdmin)
        .validateTopicPresenceAndState(
            eq(clusterName),
            eq(storeName),
            eq(pushJobId),
            eq(PushType.STREAM),
            eq(rtTopic),
            eq(partitionCount));
    Exception rtTopicValidationException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin.getReferenceVersionForStreamingWrites(clusterName, storeName, pushJobId));
    assertTrue(
        rtTopicValidationException.getMessage().contains("Real-time topic validation failed"),
        "Actual message: " + rtTopicValidationException.getMessage());

    // Case 4: Reference hybrid version retrieval fails
    doThrow(new VeniceException("No valid hybrid version found")).when(veniceHelixAdmin)
        .getReferenceHybridVersionForRealTimeWrites(clusterName, store, pushJobId);
    Exception hybridVersionException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin.getReferenceVersionForStreamingWrites(clusterName, storeName, pushJobId));
    assertTrue(
        hybridVersionException.getMessage().contains("No valid hybrid version found"),
        "Actual message: " + hybridVersionException.getMessage());

    // Case 5: Store setup validation fails
    doThrow(new VeniceException("Store setup validation failed")).when(veniceHelixAdmin)
        .validateStoreSetupForRTWrites(eq(clusterName), eq(storeName), eq(pushJobId), eq(PushType.STREAM));
    Exception storeSetupValidationException = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin.getReferenceVersionForStreamingWrites(clusterName, storeName, pushJobId));
    assertTrue(
        storeSetupValidationException.getMessage().contains("Store setup validation failed"),
        "Actual message: " + storeSetupValidationException.getMessage());
  }

  @Test
  public void testCleanupWhenPushCompleteWithViewConfigs() {
    String viewName = "testMaterializedView";
    int versionNumber = 1;
    String versionTopicName = Version.composeKafkaTopic(storeName, versionNumber);
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    doCallRealMethod().when(veniceHelixAdmin).topicCleanupWhenPushComplete(anyString(), anyString(), anyInt());

    // Configure view configs
    MaterializedViewParameters.Builder viewParamsBuilder = new MaterializedViewParameters.Builder(viewName);
    viewParamsBuilder.setPartitionCount(6);
    viewParamsBuilder.setPartitioner(DefaultVenicePartitioner.class.getCanonicalName());
    Map<String, String> viewParamsMap = viewParamsBuilder.build();
    ViewConfig viewConfig = new ViewConfigImpl(MaterializedView.class.getCanonicalName(), viewParamsMap);
    String viewTopicName = ViewUtils
        .getVeniceView(viewConfig.getViewClassName(), new Properties(), storeName, viewConfig.getViewParameters())
        .getTopicNamesAndConfigsForVersion(versionNumber)
        .keySet()
        .iterator()
        .next();

    // Configure mocks
    HelixVeniceClusterResources mockClusterResource = mock(HelixVeniceClusterResources.class);
    doReturn(mockClusterResource).when(veniceHelixAdmin).getHelixVeniceClusterResources(clusterName);
    VeniceControllerClusterConfig mockClusterConfig = mock(VeniceControllerClusterConfig.class);
    doReturn(true).when(mockClusterConfig).isKafkaLogCompactionForHybridStoresEnabled();
    doReturn(mockClusterConfig).when(mockClusterResource).getConfig();
    ReadWriteStoreRepository mockStoreRepo = mock(ReadWriteStoreRepository.class);
    doReturn(mockStoreRepo).when(mockClusterResource).getStoreMetadataRepository();
    Store store = mock(Store.class);
    doReturn(true).when(store).isHybrid();
    doReturn(store).when(mockStoreRepo).getStore(storeName);
    TopicManager mockTopicManager = mock(TopicManager.class);
    doReturn(mockTopicManager).when(veniceHelixAdmin).getTopicManager();
    PubSubTopicRepository mockPubSubRepo = mock(PubSubTopicRepository.class);
    doReturn(mockPubSubRepo).when(veniceHelixAdmin).getPubSubTopicRepository();
    PubSubTopic versionTopic = mock(PubSubTopic.class);
    doReturn(versionTopicName).when(versionTopic).getName();
    doReturn(versionTopic).when(mockPubSubRepo).getTopic(versionTopicName);
    PubSubTopic viewTopic = mock(PubSubTopic.class);
    doReturn(viewTopicName).when(viewTopic).getName();
    doReturn(viewTopic).when(mockPubSubRepo).getTopic(viewTopicName);
    Version version = mock(Version.class);
    doReturn(Collections.singletonMap(viewName, viewConfig)).when(version).getViewConfigs();
    doReturn(version).when(store).getVersionOrThrow(versionNumber);

    veniceHelixAdmin.topicCleanupWhenPushComplete(clusterName, storeName, versionNumber);
    ArgumentCaptor<PubSubTopic> pubSubTopicArgumentCaptor = ArgumentCaptor.forClass(PubSubTopic.class);
    verify(mockTopicManager, times(2))
        .updateTopicCompactionPolicy(pubSubTopicArgumentCaptor.capture(), anyBoolean(), anyLong(), any());
    List<String> expectedUpdateCompactionTopics = Arrays.asList(versionTopicName, viewTopicName);
    List<PubSubTopic> pubSubTopics = pubSubTopicArgumentCaptor.getAllValues();
    assertEquals(pubSubTopics.size(), expectedUpdateCompactionTopics.size());
    for (int i = 0; i < pubSubTopics.size(); i++) {
      assertEquals(pubSubTopics.get(i).getName(), expectedUpdateCompactionTopics.get(i));
    }
  }

  @Test
  public void testGetAdminTopicMetadata() {
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    doCallRealMethod().when(veniceHelixAdmin).getAdminTopicMetadata(clusterName, Optional.of(storeName));
    doCallRealMethod().when(veniceHelixAdmin).getAdminTopicMetadata(clusterName, Optional.empty());

    // Case 1: Not store name provided
    Map<String, Long> remoteMetadata = AdminTopicMetadataAccessor
        .generateMetadataMap(Optional.of(10L), Optional.of(-1L), Optional.of(1L), Optional.of(1L));
    AdminConsumerService adminConsumerService = mock(AdminConsumerService.class);
    when(veniceHelixAdmin.getAdminConsumerService(clusterName)).thenReturn(adminConsumerService);
    when(adminConsumerService.getAdminTopicMetadata(anyString())).thenReturn(remoteMetadata);

    Map<String, Long> metadata = veniceHelixAdmin.getAdminTopicMetadata(clusterName, Optional.empty());
    assertEquals(metadata, remoteMetadata);

    // Case 2: Store name is provided
    ExecutionIdAccessor executionIdAccessor = mock(ExecutionIdAccessor.class);
    Map<String, Long> executionIdMap = new HashMap<>();
    executionIdMap.put(storeName, 10L);
    when(veniceHelixAdmin.getExecutionIdAccessor()).thenReturn(executionIdAccessor);
    when(executionIdAccessor.getLastSucceededExecutionIdMap(anyString())).thenReturn(executionIdMap);
    when(veniceHelixAdmin.getExecutionIdAccessor()).thenReturn(executionIdAccessor);
    when(adminConsumerService.getAdminTopicMetadata(anyString())).thenReturn(remoteMetadata);

    Map<String, Long> expectedMetadata = AdminTopicMetadataAccessor
        .generateMetadataMap(Optional.of(-1L), Optional.of(-1L), Optional.of(10L), Optional.of(-1L));
    Map<String, Long> metadataForStore = veniceHelixAdmin.getAdminTopicMetadata(clusterName, Optional.of(storeName));
    assertEquals(metadataForStore, expectedMetadata);
  }

  @Test
  public void testUpdateAdminTopicMetadata() {
    long executionId = 10L;
    Long offset = 10L;
    Long upstreamOffset = 1L;
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    doCallRealMethod().when(veniceHelixAdmin)
        .updateAdminTopicMetadata(clusterName, executionId, Optional.of(storeName), Optional.empty(), Optional.empty());
    doCallRealMethod().when(veniceHelixAdmin)
        .updateAdminTopicMetadata(
            clusterName,
            executionId,
            Optional.empty(),
            Optional.of(offset),
            Optional.of(upstreamOffset));

    // Case 1: Store name is provided
    ExecutionIdAccessor executionIdAccessor = mock(ExecutionIdAccessor.class);
    when(veniceHelixAdmin.getExecutionIdAccessor()).thenReturn(executionIdAccessor);

    veniceHelixAdmin
        .updateAdminTopicMetadata(clusterName, executionId, Optional.of(storeName), Optional.empty(), Optional.empty());
    verify(executionIdAccessor, times(1)).updateLastSucceededExecutionIdMap(clusterName, storeName, executionId);

    // Case 2: Store name is not provided
    AdminConsumerService adminConsumerService = mock(AdminConsumerService.class);
    when(veniceHelixAdmin.getAdminConsumerService(clusterName)).thenReturn(adminConsumerService);
    veniceHelixAdmin.updateAdminTopicMetadata(
        clusterName,
        executionId,
        Optional.empty(),
        Optional.of(offset),
        Optional.of(upstreamOffset));
    verify(executionIdAccessor, never()).updateLastSucceededExecutionId(anyString(), anyLong());
    verify(adminConsumerService, times(1)).updateAdminTopicMetadata(clusterName, executionId, offset, upstreamOffset);
  }

  @Test
  public void testGetAdminOperationVersionsFromControllers() {
    VeniceParentHelixAdmin veniceParentHelixAdmin = mock(VeniceParentHelixAdmin.class);
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    when(veniceParentHelixAdmin.getVeniceHelixAdmin()).thenReturn(veniceHelixAdmin);
    when(veniceHelixAdmin.getSslFactory()).thenReturn(Optional.empty());
    doCallRealMethod().when(veniceParentHelixAdmin).getAdminOperationVersionFromControllers(clusterName);
    doCallRealMethod().when(veniceHelixAdmin).getAdminOperationVersionFromControllers(clusterName);
    doReturn(Optional.empty()).when(veniceHelixAdmin).getSslFactory();
    doReturn("leaderHost_1234").when(veniceHelixAdmin).getControllerName();
    doNothing().when(veniceHelixAdmin).checkControllerLeadershipFor(clusterName);

    // Mock current version in leader is 2
    when(veniceHelixAdmin.getLocalAdminOperationProtocolVersion()).thenReturn(2L);

    // Mock response for non-leader controllers
    AdminOperationProtocolVersionControllerResponse response1 = new AdminOperationProtocolVersionControllerResponse();
    response1.setLocalAdminOperationProtocolVersion(1);
    response1.setLocalControllerName("controller1_1234");
    AdminOperationProtocolVersionControllerResponse response2 = new AdminOperationProtocolVersionControllerResponse();
    response2.setLocalAdminOperationProtocolVersion(2);
    response2.setLocalControllerName("controller2_1234");

    List<Instance> liveInstances = new ArrayList<>();
    Instance leaderInstance = new Instance("4", "leaderHost", 1234);
    liveInstances.add(new Instance("1", "controller1", 1234));
    liveInstances.add(new Instance("2", "controller2", 1234));
    liveInstances.add(leaderInstance);

    when(veniceHelixAdmin.getAllLiveInstanceControllers()).thenReturn(liveInstances);
    when(veniceHelixAdmin.getLeaderController(clusterName)).thenReturn(leaderInstance);

    try (MockedStatic<ControllerClient> controllerClientMockedStatic = mockStatic(ControllerClient.class)) {
      ControllerClient client = mock(ControllerClient.class);
      controllerClientMockedStatic
          .when(() -> ControllerClient.constructClusterControllerClient(eq(clusterName), any(), any()))
          .thenReturn(client);
      when(client.getLocalAdminOperationProtocolVersion("http://controller1:1234")).thenReturn(response1);
      when(client.getLocalAdminOperationProtocolVersion("http://controller2:1234")).thenReturn(response2);

      Map<String, Long> controllerNameToVersionMap =
          veniceParentHelixAdmin.getAdminOperationVersionFromControllers(clusterName);
      assertEquals(controllerNameToVersionMap.size(), 3);
      assertTrue(
          controllerNameToVersionMap.containsKey("controller1_1234")
              && controllerNameToVersionMap.get("controller1_1234") == 1L);
      assertTrue(
          controllerNameToVersionMap.containsKey("controller2_1234")
              && controllerNameToVersionMap.get("controller2_1234") == 2L);
      assertTrue(
          controllerNameToVersionMap.containsKey("leaderHost_1234")
              && controllerNameToVersionMap.get("leaderHost_1234") == 2L);
    }
  }

  @Test
  public void testFailedGetAdminOperationVersionsForStandbyControllers() {
    VeniceParentHelixAdmin veniceParentHelixAdmin = mock(VeniceParentHelixAdmin.class);
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);

    when(veniceParentHelixAdmin.getVeniceHelixAdmin()).thenReturn(veniceHelixAdmin);
    doReturn(Optional.empty()).when(veniceHelixAdmin).getSslFactory();
    doReturn("leaderHost_1234").when(veniceHelixAdmin).getControllerName();
    doCallRealMethod().when(veniceParentHelixAdmin).getAdminOperationVersionFromControllers(clusterName);
    doCallRealMethod().when(veniceHelixAdmin).getAdminOperationVersionFromControllers(clusterName);

    // Mock current version in leader is 2
    when(veniceHelixAdmin.getLocalAdminOperationProtocolVersion()).thenReturn(2L);

    // Mock response for controllers
    ArrayList<Instance> instances = new ArrayList<>();
    instances.add(new Instance("1", "controller1", 1234));
    instances.add(new Instance("2", "controller2", 1234));

    AdminOperationProtocolVersionControllerResponse response1 = new AdminOperationProtocolVersionControllerResponse();
    response1.setLocalAdminOperationProtocolVersion(1);
    response1.setLocalControllerName("controller1_1234");
    AdminOperationProtocolVersionControllerResponse failedResponse =
        new AdminOperationProtocolVersionControllerResponse();
    failedResponse.setError("Failed to get version");

    when(veniceHelixAdmin.getAllLiveInstanceControllers()).thenReturn(instances);
    when(veniceHelixAdmin.getLeaderController(clusterName)).thenReturn(new Instance("3", "leaderHost", 1234));

    try (MockedStatic<ControllerClient> controllerClientMockedStatic = mockStatic(ControllerClient.class)) {
      ControllerClient client = mock(ControllerClient.class);
      controllerClientMockedStatic
          .when(() -> ControllerClient.constructClusterControllerClient(eq(clusterName), any(), any()))
          .thenReturn(client);
      when(client.getLocalAdminOperationProtocolVersion("http://controller1:1234")).thenReturn(response1);
      when(client.getLocalAdminOperationProtocolVersion("http://controller2:1234")).thenReturn(failedResponse);

      Map<String, Long> controllerNameToVersionMap =
          veniceParentHelixAdmin.getAdminOperationVersionFromControllers(clusterName);

      assertEquals(controllerNameToVersionMap.size(), 2);
      assertTrue(
          controllerNameToVersionMap.containsKey("controller1_1234")
              && controllerNameToVersionMap.get("controller1_1234") == 1L);
      assertTrue(
          controllerNameToVersionMap.containsKey("leaderHost_1234")
              && controllerNameToVersionMap.get("leaderHost_1234") == 2L);
    }
  }

  @Test
  public void testGetLocalAdminOperationProtocolVersion() {
    VeniceParentHelixAdmin veniceParentHelixAdmin = mock(VeniceParentHelixAdmin.class);
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    when(veniceParentHelixAdmin.getVeniceHelixAdmin()).thenReturn(veniceHelixAdmin);
    doCallRealMethod().when(veniceParentHelixAdmin).getLocalAdminOperationProtocolVersion();

    doCallRealMethod().when(veniceHelixAdmin).getLocalAdminOperationProtocolVersion();
    assertEquals(
        veniceParentHelixAdmin.getLocalAdminOperationProtocolVersion(),
        AdminOperationSerializer.LATEST_SCHEMA_ID_FOR_ADMIN_OPERATION);
  }

  @Test
  public void testGetControllersWithInvalidHelixState() {
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    doCallRealMethod().when(veniceHelixAdmin).getControllersByHelixState(any(), any());

    expectThrows(VeniceException.class, () -> veniceHelixAdmin.getControllersByHelixState(clusterName, "state"));
  }

  @Test
  public void testGetAllLiveInstanceControllers() {
    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    SafeHelixManager safeHelixManager = mock(SafeHelixManager.class);
    SafeHelixDataAccessor safeHelixDataAccessor = mock(SafeHelixDataAccessor.class);
    VeniceControllerMultiClusterConfig controllerMultiClusterConfig = mock(VeniceControllerMultiClusterConfig.class);

    doCallRealMethod().when(veniceHelixAdmin).getAllLiveInstanceControllers();
    doReturn(safeHelixManager).when(veniceHelixAdmin).getHelixManager();
    doReturn(safeHelixDataAccessor).when(safeHelixManager).getHelixDataAccessor();
    doReturn(controllerMultiClusterConfig).when(veniceHelixAdmin).getMultiClusterConfigs();
    doReturn(1235).when(controllerMultiClusterConfig).getAdminSecurePort();
    doReturn(1236).when(controllerMultiClusterConfig).getAdminGrpcPort();
    doReturn(1237).when(controllerMultiClusterConfig).getAdminSecureGrpcPort();
    when(veniceHelixAdmin.getControllerClusterName()).thenReturn("controllerClusterName");

    // When there is no live instance controller, it should throw an exception
    doReturn(Collections.emptyList()).when(safeHelixDataAccessor).getChildNames(any());
    expectThrows(VeniceException.class, () -> veniceHelixAdmin.getAllLiveInstanceControllers());

    // When there are live instance controllers, it should return the list
    List<String> liveInstances = Arrays.asList("controller1_1234", "controller2_1234");
    doReturn(liveInstances).when(safeHelixDataAccessor).getChildNames(any());
    List<Instance> liveInstanceControllers = veniceHelixAdmin.getAllLiveInstanceControllers();
    assertEquals(liveInstanceControllers.size(), 2);
  }

  /** Skip if regionFilter doesn’t include this region */
  @Test
  public void testRollForwardSkipRegionFilter() {
    VeniceHelixAdmin mockVeniceHelixAdmin = mock(VeniceHelixAdmin.class);
    doCallRealMethod().when(mockVeniceHelixAdmin).rollForwardToFutureVersion(anyString(), anyString(), anyString());
    doReturn("test").when(mockVeniceHelixAdmin).getRegionName();
    // mock the static utility class
    try (MockedStatic<RegionUtils> utilities = mockStatic(RegionUtils.class)) {
      utilities.when(() -> RegionUtils.isRegionPartOfRegionsFilterList(anyString(), anyString())).thenReturn(false);

      mockVeniceHelixAdmin.rollForwardToFutureVersion(clusterName, storeName, "test");
    }

    // should bail out before even checking future versions
    verify(mockVeniceHelixAdmin, never()).getFutureVersionWithStatus(any(), any(), any());
  }

  /** No future version → just return (no exception) */
  @Test
  public void testRollForwardNoFutureVersions() {
    VeniceHelixAdmin mockVeniceHelixAdmin = mock(VeniceHelixAdmin.class);
    doCallRealMethod().when(mockVeniceHelixAdmin).rollForwardToFutureVersion(anyString(), anyString(), anyString());
    // pretend there is no future version
    doReturn(0).when(mockVeniceHelixAdmin)
        .getFutureVersionWithStatus(eq(clusterName), eq(storeName), eq(VersionStatus.ONLINE));
    doReturn(0).when(mockVeniceHelixAdmin)
        .getFutureVersionWithStatus(eq(clusterName), eq(storeName), eq(VersionStatus.PUSHED));

    doReturn("test").when(mockVeniceHelixAdmin).getRegionName();
    // mock the static utility class
    try (MockedStatic<RegionUtils> utilities = mockStatic(RegionUtils.class)) {
      utilities.when(() -> RegionUtils.isRegionPartOfRegionsFilterList(anyString(), anyString())).thenReturn(true);

      mockVeniceHelixAdmin.rollForwardToFutureVersion(clusterName, storeName, "test");
    }
    // should simply return, not throw, and never attempt a metadata update
    verify(mockVeniceHelixAdmin, never()).storeMetadataUpdate(any(), any(), any());
  }

  /**
  * isPartitionReadyToServe=>true: Future version exists and partitions are ready → success
  * isPartitionReadyToServe=>false: Future version exists but partitions aren’t ready → exception
  */
  @Test(dataProvider = "True-and-False", dataProviderClass = DataProviderUtils.class)
  public void testRollForwardPartitionNotReady(boolean isPartitionReadyToServe) {
    VeniceHelixAdmin mockVeniceHelixAdmin = mock(VeniceHelixAdmin.class);
    doReturn(2).when(mockVeniceHelixAdmin).getFutureVersionWithStatus(clusterName, storeName, VersionStatus.ONLINE);

    // build a fake Store whose version 2 has 2 partitions but only 1 ready replica
    Store mockStore = mock(Store.class);
    when(mockStore.isEnableWrites()).thenReturn(true);
    when(mockStore.getCurrentVersion()).thenReturn(1);

    Version v2 = mock(Version.class);
    when(v2.getPartitionCount()).thenReturn(2);
    when(v2.getMinActiveReplicas()).thenReturn(isPartitionReadyToServe ? 1 : 2);
    when(mockStore.getVersion(2)).thenReturn(v2);

    // stub the repository to return only 1 ready instance per partition
    HelixCustomizedViewOfflinePushRepository repo = mock(HelixCustomizedViewOfflinePushRepository.class);
    when(repo.getReadyToServeInstances(anyString(), anyInt()))
        .thenReturn(Collections.singletonList(new Instance("node1id", "node1", 1234)));

    HelixVeniceClusterResources mockClusterResources = mock(HelixVeniceClusterResources.class);
    doReturn(repo).when(mockClusterResources).getCustomizedViewRepository();
    doReturn(mockClusterResources).when(mockVeniceHelixAdmin).getHelixVeniceClusterResources(clusterName);

    RealTimeTopicSwitcher mockTopicSwitcher = mock(RealTimeTopicSwitcher.class);
    doReturn(mockTopicSwitcher).when(mockVeniceHelixAdmin).getRealTimeTopicSwitcher();
    doNothing().when(mockTopicSwitcher).transmitVersionSwapMessage(any(), anyInt(), anyInt());

    // intercept the lambda passed to storeMetadataUpdate and run it on our mockStore
    doAnswer(inv -> {
      VeniceHelixAdmin.StoreMetadataOperation updater = inv.getArgument(2);
      updater.update(mockStore);
      return null;
    }).when(mockVeniceHelixAdmin).storeMetadataUpdate(eq(clusterName), eq(storeName), any());
    doCallRealMethod().when(mockVeniceHelixAdmin).rollForwardToFutureVersion(anyString(), anyString(), anyString());

    doReturn("test").when(mockVeniceHelixAdmin).getRegionName();
    // mock the static utility class
    try (MockedStatic<RegionUtils> utilities = mockStatic(RegionUtils.class)) {
      utilities.when(() -> RegionUtils.isRegionPartOfRegionsFilterList(anyString(), anyString())).thenReturn(true);
      try {
        mockVeniceHelixAdmin.rollForwardToFutureVersion(clusterName, storeName, "test");
        if (!isPartitionReadyToServe) {
          fail("Expected VeniceException to be thrown");
        }
      } catch (VeniceException e) {
        if (isPartitionReadyToServe) {
          fail("Expected VeniceException not to be thrown");
        }
        assertTrue(
            e.getMessage().contains("do not have enough ready-to-serve instances"),
            "Actual message: " + e.getMessage());
      }
    }
  }

  @Test
  public void testAutoStoreMigration() {
    final String destCluster = "destCluster";
    final String storeNameForMigration = "testStoreForMigration";
    final Optional<Integer> currStep = Optional.empty();

    VeniceHelixAdmin veniceHelixAdmin = mock(VeniceHelixAdmin.class);
    HelixVeniceClusterResources helixVeniceClusterResources = mock(HelixVeniceClusterResources.class);
    MultiTaskSchedulerService multiTaskSchedulerService = mock(MultiTaskSchedulerService.class);
    StoreMigrationManager storeMigrationManager = mock(StoreMigrationManager.class);

    // Mock the method chain
    doReturn(helixVeniceClusterResources).when(veniceHelixAdmin).getHelixVeniceClusterResources(clusterName);
    // Optional is not empty → store migration is supported
    doReturn(Optional.of(multiTaskSchedulerService)).when(helixVeniceClusterResources).getMultiTaskSchedulerService();
    doReturn(storeMigrationManager).when(multiTaskSchedulerService).getStoreMigrationManager();

    doCallRealMethod().when(veniceHelixAdmin)
        .autoMigrateStore(
            anyString(),
            anyString(),
            anyString(),
            Mockito.<Optional<Integer>>any(),
            Mockito.<Optional<Boolean>>any());
    veniceHelixAdmin.autoMigrateStore(clusterName, destCluster, storeNameForMigration, currStep, Optional.empty());

    // Assert – scheduleMigration(...) must be invoked with the exact args
    verify(storeMigrationManager)
        .scheduleMigration(eq(storeNameForMigration), eq(clusterName), eq(destCluster), eq(0), eq(0), eq(false));

    // Optional is empty → store migration unsupported
    doReturn(Optional.empty()).when(helixVeniceClusterResources).getMultiTaskSchedulerService();

    Exception exp = expectThrows(
        VeniceException.class,
        () -> veniceHelixAdmin
            .autoMigrateStore(clusterName, destCluster, storeNameForMigration, currStep, Optional.empty()));

    assertTrue(exp.getMessage().contains("Store migration is not supported in this cluster: " + clusterName));
  }
}
