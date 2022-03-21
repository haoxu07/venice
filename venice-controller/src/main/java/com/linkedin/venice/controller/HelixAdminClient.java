package com.linkedin.venice.controller;

import java.util.List;
import java.util.Map;


/**
 * Interface and wrapper for Helix related admin operations needed by Venice when running Helix as a service.
 */
public interface HelixAdminClient {

  /**
   * Check if the Venice controller cluster is created and configured.
   * @return true or false.
   */
  boolean isVeniceControllerClusterCreated();

  /**
   * Check if the given Venice storage cluster is created and configured.
   * @param clusterName of the Venice cluster.
   * @return true or false.
   */
  boolean isVeniceStorageClusterCreated(String clusterName);

  /**
   * Create and configure the Venice controller cluster.
   * @param isControllerInAzureFabric whether the controller is in Azure fabric.
   */
  void createVeniceControllerCluster(boolean isControllerInAzureFabric);

  /**
   * Create and configure the Venice storage cluster.
   * @param clusterName of the Venice storage cluster.
   * @param helixClusterProperties to be applied to the new cluster.
   * @param isControllerInAzureFabric whether the controller is in Azure fabric.
   */
  void createVeniceStorageCluster(String clusterName, Map<String, String> helixClusterProperties,
      boolean isControllerInAzureFabric);

  /**
   * Check if the given Venice storage cluster's cluster resource is in the Venice controller cluster.
   * @param clusterName of the Venice storage cluster.
   * @return ture of false.
   */
  boolean isVeniceStorageClusterInControllerCluster(String clusterName);

  /**
   * Add the given Venice storage cluster's cluster resource to the controller cluster.
   * @param clusterName of the Venice storage cluster.
   */
  void addVeniceStorageClusterToControllerCluster(String clusterName);

  /**
   * Check if the grand cluster managed by HaaS controllers is aware of the given cluster.
   * @param clusterName of the cluster.
   * @return true or false.
   */
  boolean isClusterInGrandCluster(String clusterName);

  /**
   * Add the specified cluster as a resource to the grand cluster to be managed by HaaS controllers.
   * @param clusterName of the cluster to be added as a resource to the grand cluster.
   */
  void addClusterToGrandCluster(String clusterName);

  /**
   * Update some Helix cluster properties for the given cluster.
   * @param clusterName of the cluster to be updated.
   * @param helixClusterProperties to be applied to the given cluster.
   */
  void updateClusterConfigs(String clusterName, Map<String, String> helixClusterProperties);

  /**
   * Disable or enable a list of partitions on an instance
   * @param enabled
   * @param clusterName
   * @param instanceName
   * @param resourceName
   * @param partitionNames
   */
  void enablePartition(boolean enabled, String clusterName, String instanceName,
      String resourceName, List<String> partitionNames);

  /**
   * Get a list of instances under a cluster
   * @param clusterName
   * @return a list of instance names
   */
  List<String> getInstancesInCluster(String clusterName);

  /**
   * Create resources for a given storage node cluster.
   * @param clusterName
   * @param kafkaTopic
   * @param numberOfPartition
   * @param replicationFactor
   * @param isLeaderFollowerStateModel
   */
  void createVeniceStorageClusterResources(String clusterName, String kafkaTopic , int numberOfPartition ,
      int replicationFactor, boolean isLeaderFollowerStateModel);

  /**
   * Drop a resource from a cluster
   * @param clusterName
   * @param resourceName
   */
  void dropResource(String clusterName, String resourceName);

  /**
   * Drop a storage node instance from the given cluster
   * @param clusterName
   * @param instanceName
   */
  void dropStorageInstance(String clusterName, String instanceName);

  /**
   * Reset a list of partitions in error state for an instance
   * The partitions are assume to be in error state and reset will bring them from error
   * to initial state. An error to initial state transition is required for reset.
   * @param clusterName
   * @param instanceName
   * @param resourceName
   * @param partitionNames
   */
  void resetPartition(String clusterName, String instanceName, String resourceName, List<String> partitionNames);

  /**
   * Release resources.
   */
  void close();
}
