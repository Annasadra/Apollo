/*
 *  Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.app;

import static com.apollocurrency.aplwallet.apl.data.TransactionTestData.TRANSACTION_0;
import static com.apollocurrency.aplwallet.apl.data.TransactionTestData.TRANSACTION_11;
import static com.apollocurrency.aplwallet.apl.data.TransactionTestData.TRANSACTION_5;
import static com.apollocurrency.aplwallet.apl.data.TransactionTestData.TRANSACTION_8;
import static com.apollocurrency.aplwallet.apl.data.TransactionTestData.TRANSACTION_9;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.apollocurrency.aplwallet.apl.core.chainid.BlockchainConfig;
import com.apollocurrency.aplwallet.apl.core.chainid.HeightConfig;
import com.apollocurrency.aplwallet.apl.core.config.DaoConfig;
import com.apollocurrency.aplwallet.apl.core.db.DbExtension;
import com.apollocurrency.aplwallet.apl.core.db.DerivedDbTablesRegistry;
import com.apollocurrency.aplwallet.apl.core.db.cdi.transaction.JdbiHandleFactory;
import com.apollocurrency.aplwallet.apl.core.db.dao.BlockIndexDao;
import com.apollocurrency.aplwallet.apl.core.db.dao.ReferencedTransactionDao;
import com.apollocurrency.aplwallet.apl.util.NtpTime;
import com.apollocurrency.aplwallet.apl.util.injectable.PropertiesHolder;
import org.jboss.weld.junit.MockBean;
import org.jboss.weld.junit5.EnableWeld;
import org.jboss.weld.junit5.WeldInitiator;
import org.jboss.weld.junit5.WeldSetup;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import javax.inject.Inject;
@EnableWeld
public class ReferencedTransactionServiceTest {
    @Inject
    private JdbiHandleFactory jdbiHandleFactory;

    @RegisterExtension
    static DbExtension extension = new DbExtension();
    BlockchainConfig blockchainConfig = Mockito.mock(BlockchainConfig.class);
    HeightConfig config = Mockito.mock(HeightConfig.class);
    @WeldSetup
    public WeldInitiator weld = WeldInitiator.from(
            PropertiesHolder.class, TransactionImpl.class, BlockchainImpl.class, DaoConfig.class,
            JdbiHandleFactory.class, BlockIndexDao.class, ReferencedTransactionDao.class,
            GlobalSync.class,
            GlobalSyncImpl.class,
            DerivedDbTablesRegistry.class,
            EpochTime.class, BlockDaoImpl.class, TransactionDaoImpl.class, ReferencedTransactionService.class)
            .addBeans(MockBean.of(extension.getDatabaseManger(), DatabaseManager.class))
            .addBeans(MockBean.of(extension.getDatabaseManger().getJdbi(), Jdbi.class))
            .addBeans(MockBean.of(blockchainConfig, BlockchainConfig.class))
            .addBeans(MockBean.of(mock(TransactionProcessor.class), TransactionProcessor.class))
            .addBeans(MockBean.of(mock(NtpTime.class), NtpTime.class))
            .build();

    @Inject
    ReferencedTransactionService service;


    @AfterEach
    void cleanup() {
        jdbiHandleFactory.close();
    }

    @BeforeEach
    void setUp() {
        doReturn(config).when(blockchainConfig).getCurrentConfig();
    }
    @Test
    void testHasAllReferencedTransaction() {
        doReturn(1000).when(config).getReferencedTransactionHeightSpan();
        boolean hasAll = service.hasAllReferencedTransactions(TRANSACTION_0, 1000);

        assertTrue(hasAll);
    }
    @Test
    void testHasNotAllReferencedTransactionsWhenHeightIsNotEnough() {
        doReturn(1000).when(config).getReferencedTransactionHeightSpan();
        boolean hasAll = service.hasAllReferencedTransactions(TRANSACTION_5, TRANSACTION_5.getHeight());

        assertFalse(hasAll);
    }
    @Test
    void testHasNotAllReferencedTransactionsWhenTransactionHeightIsLessThanHeightOfReferencedTransactions() {
        doReturn(20_000).when(config).getReferencedTransactionHeightSpan();
        boolean hasAll = service.hasAllReferencedTransactions(TRANSACTION_11, TRANSACTION_11.getHeight());

        assertFalse(hasAll);
    }

    @Test
    void testHasNotAllReferencedTransactionWhenMaximumNumberOfReferencedTransactionsReached() {
        doReturn(20_000).when(config).getReferencedTransactionHeightSpan();

        boolean hasAll = service.hasAllReferencedTransactions(TRANSACTION_9, TRANSACTION_9.getHeight());

        assertFalse(hasAll);

        hasAll = service.hasAllReferencedTransactions(TRANSACTION_8, TRANSACTION_8.getHeight());

        assertFalse(hasAll);
    }


}
