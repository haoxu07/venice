package com.linkedin.davinci.store;

import com.linkedin.davinci.config.VeniceStoreConfig;
import com.linkedin.venice.exceptions.StorageInitializationException;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.meta.PersistenceType;
import java.util.Set;
import org.apache.log4j.Logger;


/**
 * An abstraction that represents the shared resources of a persistence engine.
 * This could include file handles, db connection pools, caches, etc.
 *
 * For example for BDB it holds the various environments, for jdbc it holds a
 * connection pool reference
 */
public abstract class StorageEngineFactory {
  private static final Logger logger = Logger.getLogger(StorageEngineFactory.class.getName());
  /**
   * Get an initialized storage implementation
   *
   * @param storeDef  store definition
   * @return The storage engine
   */
  public abstract AbstractStorageEngine getStorageEngine(VeniceStoreConfig storeDef)
      throws StorageInitializationException;

  /**
   * Timestamp metadata is only supported in RocksDB storage engine. For other type of the storage engine, we will
   * throw VeniceException here.
   */
  public AbstractStorageEngine getStorageEngine(VeniceStoreConfig storeDef, boolean timestampMetadataEnabled) {
    if (timestampMetadataEnabled) {
      throw new VeniceException("Timestamp metadata is only supported in RocksDB storage engine!");
    }
    return getStorageEngine(storeDef);
  }

  /**
   * Retrieve all the stores persisted previously
   *
   * @return All the store names
   */
  public abstract Set<String> getPersistedStoreNames();

  /**
   * Close the storage configuration
   */
  public abstract void close();

  /**
   * Remove the storage engine from the underlying storage configuration
   *
   * @param engine Specifies the storage engine to be removed
   */
  public abstract void removeStorageEngine(AbstractStorageEngine engine);

  /**
   * Close the storage engine from the underlying storage configuration
   *
   * @param engine Specifies the storage engine to be removed
   */
  public abstract void closeStorageEngine(AbstractStorageEngine engine);


  /**
   * Return the persistence type current factory supports.
   * @return
   */
  public abstract PersistenceType getPersistenceType();

  public void verifyPersistenceType(VeniceStoreConfig storeConfig) {
    if (!storeConfig.getStorePersistenceType().equals(getPersistenceType())) {
      throw new VeniceException("Required store persistence type: " + storeConfig.getStorePersistenceType() + " of store: "
          + storeConfig.getStoreName() + " isn't supported in current factory: " + getClass().getName() +
          " with type: " + getPersistenceType());
    }
  }

  public void verifyPersistenceType(AbstractStorageEngine engine) {
    if (!engine.getType().equals(getPersistenceType())) {
      throw new VeniceException("Required store persistence type: " + engine.getType() + " of store: "
          + engine.getStoreName() + " isn't supported in current factory: " + getClass().getName() +
          " with type: " + getPersistenceType());
    }
  }
}

