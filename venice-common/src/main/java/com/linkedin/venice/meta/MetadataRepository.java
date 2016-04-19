package com.linkedin.venice.meta;

import com.linkedin.venice.VeniceResource;


/**
 * Interface of metadata repository to provide operations of stores and versions.
 */
public interface MetadataRepository extends VeniceResource {
    /**
     * Get one store by given name from repository.
     *
     * @param name name of wanted store.
     *
     * @return Store for given name.
     */
    public Store getStore(String name);

    /**
     * Update store in repository.
     *
     * @param store store need to be udpated.
     */
    public void updateStore(Store store);

    /**
     * Delete store from repository.
     *
     * @param name name of wantted store.
     */
    public void deleteStore(String name);

    /**
     * Add store into repository.
     *
     * @param store store need to be added.
     */
    public void addStore(Store store);

    /**
     * Add a listener into repository to listen the change of store list.
     *
     * @param listener Listener to get the notification.
     */
    public void subscribeStoreListChanged(StoreListChangedListener listener);

    /**
     * Add a listener into repository to listen the change the store data.
     *
     * @param storeName Name of the store which need to be listened.
     * @param listener  listener to get the notification.
     */
    public void subscribeStoreDataChanged(String storeName, StoreDataChangedListener listener);

    /**
     * Remove a listener from repository to stop listening the change of store list.
     * @param listener
     */
    public void unSubscribeStoreListChanged(StoreListChangedListener listener);

    /**
     * Remove a listener from repository to stop listening the change of store data.
     * @param storeName
     * @param listener
     */
    public void unSubscribeStoreDataChanged(String storeName, StoreDataChangedListener listener);
}
