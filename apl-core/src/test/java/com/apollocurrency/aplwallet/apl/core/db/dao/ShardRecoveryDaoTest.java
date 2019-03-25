/*
 *  Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.db.dao;

import static com.apollocurrency.aplwallet.apl.crypto.Convert.parseHexString;
import static com.apollocurrency.aplwallet.apl.data.ShardTestData.NOT_SAVED_SHARD;
import static com.apollocurrency.aplwallet.apl.data.ShardTestData.SHARDS;
import static com.apollocurrency.aplwallet.apl.data.ShardTestData.SHARD_0;
import static com.apollocurrency.aplwallet.apl.data.ShardTestData.SHARD_1;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.apollocurrency.aplwallet.apl.core.app.BlockchainImpl;
import com.apollocurrency.aplwallet.apl.core.app.EpochTime;
import com.apollocurrency.aplwallet.apl.core.app.GlobalSync;
import com.apollocurrency.aplwallet.apl.core.app.GlobalSyncImpl;
import com.apollocurrency.aplwallet.apl.core.app.TransactionDaoImpl;
import com.apollocurrency.aplwallet.apl.core.chainid.BlockchainConfig;
import com.apollocurrency.aplwallet.apl.core.config.DaoConfig;
import com.apollocurrency.aplwallet.apl.core.db.BlockDaoImpl;
import com.apollocurrency.aplwallet.apl.core.db.DatabaseManager;
import com.apollocurrency.aplwallet.apl.core.db.DbExtension;
import com.apollocurrency.aplwallet.apl.core.db.DerivedDbTablesRegistry;
import com.apollocurrency.aplwallet.apl.core.db.cdi.transaction.JdbiHandleFactory;
import com.apollocurrency.aplwallet.apl.core.db.dao.model.Shard;
import com.apollocurrency.aplwallet.apl.core.db.dao.model.ShardRecovery;
import com.apollocurrency.aplwallet.apl.core.shard.MigrateState;
import com.apollocurrency.aplwallet.apl.util.NtpTime;
import com.apollocurrency.aplwallet.apl.util.injectable.PropertiesHolder;
import org.jboss.weld.junit.MockBean;
import org.jboss.weld.junit5.EnableWeld;
import org.jboss.weld.junit5.WeldInitiator;
import org.jboss.weld.junit5.WeldSetup;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@EnableWeld
class ShardRecoveryDaoTest {

    @Inject
    private JdbiHandleFactory jdbiHandleFactory;
    @RegisterExtension
    static DbExtension extension = new DbExtension();
    @WeldSetup
    public WeldInitiator weld = WeldInitiator.from(NtpTime.class,
            PropertiesHolder.class, BlockchainConfig.class, BlockchainImpl.class, DaoConfig.class,
            JdbiHandleFactory.class, ShardRecoveryDao.class,
            GlobalSync.class,
            GlobalSyncImpl.class,
            DerivedDbTablesRegistry.class,
            EpochTime.class, BlockDaoImpl.class, TransactionDaoImpl.class)
            .addBeans(MockBean.of(extension.getDatabaseManger(), DatabaseManager.class))
            .addBeans(MockBean.of(extension.getDatabaseManger().getJdbi(), Jdbi.class))
            .build();

    @Inject
    private ShardRecoveryDao dao;

    @AfterEach
    void cleanup() {
        jdbiHandleFactory.close();
    }

    @Test
    void testGetAllForEmpty() {
        List<ShardRecovery> allShardRecoveries = dao.getAllShardRecovery();
        assertEquals(0, allShardRecoveries.size());
    }

    @Test
    void testUnknownShardById() {
        ShardRecovery recovery = dao.getShardRecoveryById(-1L);
        assertNull(recovery);
    }

    @Test
    void testInsert() {
        ShardRecovery recovery = new ShardRecovery(
                MigrateState.INIT, "BLOCK", "DB_ID", 1L,
                "DB_ID", 100000);

        long insertedId = dao.saveShardRecovery(recovery);
        assertTrue(insertedId >= 1L);

        long count = dao.countShardRecovery();
        assertEquals(1, count);

        List<ShardRecovery> actual = dao.getAllShardRecovery();
        assertEquals(1, actual.size());
        assertEquals(recovery.getState(), actual.get(0).getState());

        int deleted = dao.hardDeleteShardRecovery(actual.get(0).getShardRecoveryId());
        assertEquals(1, deleted);

    }

    @Test
    void testUpdate() {
        ShardRecovery recovery = new ShardRecovery(
                MigrateState.INIT, "BLOCK", "DB_ID", 1L,
                "DB_ID", 100000);
        long insertedId = dao.saveShardRecovery(recovery);
        assertTrue(insertedId > 1L);

        recovery.setShardRecoveryId(insertedId);
        recovery.setState(MigrateState.SHARD_SCHEMA_FULL);
        int updateCount = dao.updateShardRecovery(recovery);
        assertEquals(1, updateCount);

        List<ShardRecovery> actual = dao.getAllShardRecovery();
        assertEquals(1, actual.size());
        assertEquals(recovery.getState(), actual.get(0).getState());

        ShardRecovery found = dao.getShardRecoveryById(actual.get(0).getShardRecoveryId());
        assertEquals(MigrateState.SHARD_SCHEMA_FULL, found.getState());

        int deleted = dao.hardDeleteShardRecovery(found.getShardRecoveryId());
        assertEquals(1, deleted);

        dao.hardDeleteAllShardRecovery();
    }

    @Test
    void testDeleteMissing() {
        int deleteCount = dao.hardDeleteShardRecovery(-1L);
        assertEquals(0, deleteCount);
    }

    @Test
    void testCount() {
        long count = dao.countShardRecovery();
        assertEquals(0, count);
    }

}