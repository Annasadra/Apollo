/*
 * Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.account.dao;

import com.apollocurrency.aplwallet.apl.core.account.model.AccountAsset;
import com.apollocurrency.aplwallet.apl.core.app.Blockchain;
import com.apollocurrency.aplwallet.apl.core.app.BlockchainImpl;
import com.apollocurrency.aplwallet.apl.core.app.BlockchainProcessor;
import com.apollocurrency.aplwallet.apl.core.app.BlockchainProcessorImpl;
import com.apollocurrency.aplwallet.apl.core.chainid.BlockchainConfig;
import com.apollocurrency.aplwallet.apl.core.db.DatabaseManager;
import com.apollocurrency.aplwallet.apl.core.db.DerivedDbTablesRegistryImpl;
import com.apollocurrency.aplwallet.apl.core.db.DerivedTablesRegistry;
import com.apollocurrency.aplwallet.apl.core.db.fulltext.FullTextConfig;
import com.apollocurrency.aplwallet.apl.core.db.fulltext.FullTextConfigImpl;
import com.apollocurrency.aplwallet.apl.data.AccountTestData;
import com.apollocurrency.aplwallet.apl.data.DbTestData;
import com.apollocurrency.aplwallet.apl.extension.DbExtension;
import com.apollocurrency.aplwallet.apl.testutil.DbUtils;
import com.apollocurrency.aplwallet.apl.util.Constants;
import com.apollocurrency.aplwallet.apl.util.injectable.PropertiesHolder;
import org.jboss.weld.junit.MockBean;
import org.jboss.weld.junit5.EnableWeld;
import org.jboss.weld.junit5.WeldInitiator;
import org.jboss.weld.junit5.WeldSetup;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static com.apollocurrency.aplwallet.apl.testutil.DbUtils.toList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@EnableWeld
class AccountAssetDaoTest {
    @RegisterExtension
    static DbExtension dbExtension = new DbExtension(DbTestData.getInMemDbProps(), "db/acc-data.sql", "db/schema.sql");

    private Blockchain blockchain = mock(BlockchainImpl.class);
    private BlockchainConfig blockchainConfig = mock(BlockchainConfig.class);
    private BlockchainProcessor blockchainProcessor = mock(BlockchainProcessor.class);

    @WeldSetup
    public WeldInitiator weld = WeldInitiator.from(
            PropertiesHolder.class, AccountAssetTable.class
    )
            .addBeans(MockBean.of(dbExtension.getDatabaseManager(), DatabaseManager.class))
            .addBeans(MockBean.of(dbExtension.getDatabaseManager().getJdbi(), Jdbi.class))
            .addBeans(MockBean.of(blockchainConfig, BlockchainConfig.class))
            .addBeans(MockBean.of(blockchain, Blockchain.class, BlockchainImpl.class))
            .addBeans(MockBean.of(blockchainProcessor, BlockchainProcessor.class, BlockchainProcessorImpl.class))
            .addBeans(MockBean.of(mock(FullTextConfig.class), FullTextConfig.class, FullTextConfigImpl.class))
            .addBeans(MockBean.of(mock(DerivedTablesRegistry.class), DerivedTablesRegistry.class, DerivedDbTablesRegistryImpl.class))
            .build();

    @Inject
    AccountAssetTable table;

    AccountTestData testData;

    Comparator<AccountAsset> assetComparator = Comparator
            .comparing(AccountAsset::getQuantityATU, Comparator.reverseOrder())
            .thenComparing(AccountAsset::getAccountId)
            .thenComparing(AccountAsset::getAssetId);

    @BeforeEach
    void setUp() {
        testData = new AccountTestData();
    }

    @Test
    void testLoad() {
        AccountAsset accountAsset = table.get(table.getDbKeyFactory().newKey(testData.ACC_ASS_0));
        assertNotNull(accountAsset);
        assertEquals(testData.ACC_ASS_0, accountAsset);
    }

    @Test
    void testLoad_returnNull_ifNotExist() {
        AccountAsset accountAsset = table.get(table.getDbKeyFactory().newKey(testData.newAsset));
        assertNull(accountAsset);
    }

    @Test
    void testSave_insert_new_entity() {//SQL MERGE -> INSERT
        AccountAsset previous = table.get(table.getDbKeyFactory().newKey(testData.newAsset));
        assertNull(previous);

        DbUtils.inTransaction(dbExtension, (con) -> table.insert(testData.newAsset));
        AccountAsset actual = table.get(table.getDbKeyFactory().newKey(testData.newAsset));

        assertNotNull(actual);
        assertTrue(actual.getDbId() != 0);
        assertEquals(testData.newAsset.getAccountId(), actual.getAccountId());
        assertEquals(testData.newAsset.getAssetId(), actual.getAssetId());
    }

    @Test
    void testSave_update_existing_entity() {//SQL MERGE -> UPDATE
        AccountAsset previous = table.get(table.getDbKeyFactory().newKey(testData.ACC_ASS_0));
        assertNotNull(previous);
        previous.setUnconfirmedQuantityATU(previous.getUnconfirmedQuantityATU()+50000);

        DbUtils.inTransaction(dbExtension, (con) -> table.insert(previous));
        AccountAsset actual = table.get(table.getDbKeyFactory().newKey(previous));

        assertNotNull(actual);
        assertTrue(actual.getUnconfirmedQuantityATU()-testData.ACC_ASS_0.getUnconfirmedQuantityATU() == 50000);
        assertEquals(previous.getQuantityATU(), actual.getQuantityATU());
        assertEquals(previous.getAssetId(), actual.getAssetId());
    }

    @Test
    void testCheckAvailable_on_correct_height() {
        doReturn(720).when(blockchainProcessor).getMinRollbackHeight();
        doReturn(testData.ASS_BLOCKCHAIN_HEIGHT).when(blockchain).getHeight();
        assertDoesNotThrow(() -> table.checkAvailable(testData.ASS_BLOCKCHAIN_HEIGHT));
    }

    @Test
    void testCheckAvailable_on_wrong_height_LT_rollback() {
        doReturn(testData.ASS_BLOCKCHAIN_WRONG_HEIGHT + Constants.MAX_DIVIDEND_PAYMENT_ROLLBACK + 720)
                .when(blockchainProcessor).getMinRollbackHeight();
        doReturn(testData.ASS_BLOCKCHAIN_HEIGHT).when(blockchain).getHeight();
        assertThrows(IllegalArgumentException.class, () -> table.checkAvailable(testData.ASS_BLOCKCHAIN_WRONG_HEIGHT));
    }

    @Test
    void testCheckAvailable_on_wrong_height() {
        doReturn(720).when(blockchainProcessor).getMinRollbackHeight();
        doReturn(testData.ASS_BLOCKCHAIN_HEIGHT).when(blockchain).getHeight();
        assertThrows(IllegalArgumentException.class, () -> table.checkAvailable(testData.ASS_BLOCKCHAIN_WRONG_HEIGHT));
    }

    @Test
    void testDefaultSort() {
        assertNotNull(table.defaultSort());
        List<AccountAsset> expectedAll = testData.ALL_ASSETS.stream().sorted(assetComparator).collect(Collectors.toList());
        List<AccountAsset> actualAll = toList(table.getAll(0, Integer.MAX_VALUE));
        assertEquals(expectedAll, actualAll);
    }

    @Test
    void testGetAssetCount() {
        long count = table.getAssetCount(testData.ACC_ASS_6.getAssetId());
        assertEquals(4, count);
    }

    @Test
    void testGetAssetCount_on_Height() {
        doReturn(testData.ASS_BLOCKCHAIN_HEIGHT).when(blockchain).getHeight();
        long count = table.getAssetCount(testData.ACC_ASS_6.getAssetId(), testData.ACC_ASS_6.getHeight());
        assertEquals(3, count);
    }

    @Test
    void testGetAccountAssetCount() {
        long count = table.getAccountAssetCount(testData.ACC_ASS_12.getAccountId());
        assertEquals(2, count);
    }

    @Test
    void testGetAccountAssetCount_on_Height() {
        doReturn(testData.ASS_BLOCKCHAIN_HEIGHT).when(blockchain).getHeight();
        long count = table.getAccountAssetCount(testData.ACC_ASS_12.getAccountId(), testData.ACC_ASS_12.getHeight());
        assertEquals(1, count);
    }

    @Test
    void testGetAccountAssets() {
        List<AccountAsset> actual = toList(table.getAccountAssets(testData.ACC_ASS_12.getAccountId(), 0, Integer.MAX_VALUE));
        assertEquals(2, actual.size());
        assertEquals(testData.ACC_ASS_12.getAssetId(), actual.get(0).getAssetId());
        assertEquals(testData.ACC_ASS_13.getAssetId(), actual.get(1).getAssetId());
    }

    @Test
    void testGetAccountAssets_on_Height() {
        doReturn(testData.ASS_BLOCKCHAIN_HEIGHT).when(blockchain).getHeight();
        List<AccountAsset> actual = toList(table.getAccountAssets(testData.ACC_ASS_12.getAccountId(), testData.ACC_ASS_12.getHeight(), 0, Integer.MAX_VALUE));
        assertEquals(1, actual.size());
        assertEquals(testData.ACC_ASS_12.getAssetId(), actual.get(0).getAssetId());
    }

    @Test
    void testGetAssetAccounts() {
        List<AccountAsset> actual = toList(table.getAssetAccounts(testData.ACC_ASS_6.getAssetId(), 0, Integer.MAX_VALUE));
        assertEquals(4, actual.size());
        List<AccountAsset> expected = testData.ALL_ASSETS.stream()
                .filter(ass -> ass.getAssetId()==testData.ACC_ASS_6.getAssetId())
                .sorted(assetComparator).collect(Collectors.toList());
        assertEquals(expected, actual);
    }

    @Test
    void testGetAssetAccounts_on_Height() {
        doReturn(testData.ASS_BLOCKCHAIN_HEIGHT).when(blockchain).getHeight();
        List<AccountAsset> actual = toList(table.getAssetAccounts(testData.ACC_ASS_6.getAssetId(), testData.ACC_ASS_6.getHeight(), 0, Integer.MAX_VALUE));
        List<AccountAsset> expected = testData.ALL_ASSETS.stream()
                .filter(ass -> ass.getAssetId()==testData.ACC_ASS_6.getAssetId())
                .sorted(assetComparator).collect(Collectors.toList());
        assertEquals(3, actual.size());
        assertEquals(testData.ACC_ASS_6.getAccountId(), actual.get(0).getAccountId());
    }
}