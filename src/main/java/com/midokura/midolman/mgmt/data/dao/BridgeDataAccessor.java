/*
 * @(#)BridgeDataAccessor        1.6 11/09/11
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.state.BridgeZkManagerProxy;
import com.midokura.midolman.mgmt.data.state.BridgeZkManagerProxy.BridgeMgmtConfig;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Data access class for bridge.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
public class BridgeDataAccessor extends DataAccessor {

    /**
     * Constructor
     * 
     * @param zkConn
     *            Zookeeper connection string
     */
    public BridgeDataAccessor(String zkConn, int timeout, String rootPath,
            String mgmtRootPath) {
        super(zkConn, timeout, rootPath, mgmtRootPath);
    }

    private BridgeZkManagerProxy getBridgeZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn, zkTimeout);
        return new BridgeZkManagerProxy(conn.getZooKeeper(), zkRoot, zkMgmtRoot);
    }

    /**
     * Add a JAXB object the ZK directories.
     * 
     * @param bridge
     *            Bridge object to add.
     * @throws Exception
     *             Error connecting to Zookeeper.
     */
    public UUID create(Bridge bridge) throws Exception {
        return getBridgeZkManager().create(bridge.toConfig());
    }

    /**
     * Fetch a JAXB object from the ZooKeeper.
     * 
     * @param id
     *            Bridge UUID to fetch..
     * @throws Exception
     *             Error connecting to Zookeeper.
     */
    public Bridge get(UUID id) throws Exception {
        // TODO: Throw NotFound exception here.
        return Bridge.createBridge(id, getBridgeZkManager().get(id).value);
    }

    public Bridge[] list(UUID tenantId) throws Exception {
        BridgeZkManagerProxy manager = getBridgeZkManager();
        List<Bridge> bridges = new ArrayList<Bridge>();
        List<ZkNodeEntry<UUID, BridgeMgmtConfig>> entries = manager
                .list(tenantId);
        for (ZkNodeEntry<UUID, BridgeMgmtConfig> entry : entries) {
            bridges.add(Bridge.createBridge(entry.key, entry.value));
        }
        return bridges.toArray(new Bridge[bridges.size()]);
    }

    public void update(UUID id, Bridge bridge) throws Exception {
        BridgeZkManagerProxy manager = getBridgeZkManager();
        // Only allow an update of 'name'
        ZkNodeEntry<UUID, BridgeMgmtConfig> entry = manager.get(id);
        // Just allow copy of the name.
        entry.value.name = bridge.getName();
        manager.update(entry);
    }

    public void delete(UUID id) throws Exception {
        // TODO: catch NoNodeException if does not exist.
        getBridgeZkManager().delete(id);
    }
}
