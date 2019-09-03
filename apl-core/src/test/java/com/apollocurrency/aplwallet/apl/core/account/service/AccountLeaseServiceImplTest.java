/*
 * Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.account.service;

import com.apollocurrency.aplwallet.apl.core.account.AccountEventType;
import com.apollocurrency.aplwallet.apl.core.account.dao.AccountLeaseTable;
import com.apollocurrency.aplwallet.apl.core.account.model.Account;
import com.apollocurrency.aplwallet.apl.core.account.model.AccountLease;
import com.apollocurrency.aplwallet.apl.core.app.Blockchain;
import com.apollocurrency.aplwallet.apl.core.app.BlockchainImpl;
import com.apollocurrency.aplwallet.apl.core.chainid.BlockchainConfig;
import com.apollocurrency.aplwallet.apl.data.AccountTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.enterprise.event.Event;

import static com.apollocurrency.aplwallet.apl.core.account.observer.events.AccountEventBinding.literal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class AccountLeaseServiceImplTest {

    private Blockchain blockchain = mock(BlockchainImpl.class);
    private BlockchainConfig blockchainConfig = mock(BlockchainConfig.class);
    private AccountLeaseTable accountLeaseTable = mock(AccountLeaseTable.class);
    private Event leaseEvent = mock(Event.class);

    private AccountLeaseService accountLeaseService;
    private AccountTestData testData;

    private int leasingDelay = 100;

    @BeforeEach
    void setUp() {
        testData = new AccountTestData();
        accountLeaseService = spy(new AccountLeaseServiceImpl(
                accountLeaseTable,blockchain, blockchainConfig, leaseEvent));
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void leaseEffectiveBalance1() {
        check_leaseEffectiveBalance(
                testData.ACC_1,
                testData.ACC_LEAS_0,
                testData.ACC_LEAS_0.getCurrentLeasingHeightTo()-leasingDelay-1,
                1500
                );
    }

    @Test
    void leaseEffectiveBalance2() {
        check_leaseEffectiveBalance(
                testData.ACC_1,
                testData.ACC_LEAS_0,
                testData.ACC_LEAS_0.getCurrentLeasingHeightTo() + 1,
                1500
                );
    }

    @Test
    void leaseEffectiveBalance_newLease() {
        check_leaseEffectiveBalance(
                testData.ACC_1,
                null,
                testData.ACC_LEAS_0.getCurrentLeasingHeightTo() + 1,
                1500
        );
    }

    private void check_leaseEffectiveBalance(Account account, AccountLease accountLeaseFromTable, int height, int period) {
        long lesseeId = -1;
        long expectedFrom = height + leasingDelay;
        long expectedTo = expectedFrom + period;
        AccountLease accountLease = null;

        if(accountLeaseFromTable == null){
            lesseeId = account.getId();
            accountLease = new AccountLease(account.getId(),
                    height + leasingDelay,
                    height + leasingDelay + period,
                    lesseeId, height);
        }else {
            accountLease = accountLeaseFromTable;
            lesseeId = accountLease.getCurrentLesseeId();
            if (expectedFrom < accountLease.getCurrentLeasingHeightTo()) {
                expectedFrom = accountLease.getCurrentLeasingHeightTo();
                expectedTo = expectedFrom + period;
            }
        }

        Event firedEvent = mock(Event.class);

        doReturn(height).when(blockchain).getHeight();
        doReturn(leasingDelay).when(blockchainConfig).getLeasingDelay();
        doReturn(firedEvent).when(leaseEvent).select(literal(AccountEventType.LEASE_SCHEDULED));
        doReturn(accountLeaseFromTable).when(accountLeaseTable).get(any());

        accountLeaseService.leaseEffectiveBalance(account, lesseeId, period);

        if (accountLeaseFromTable == null){
            assertEquals(expectedFrom, accountLease.getCurrentLeasingHeightFrom());
            assertEquals(expectedTo, accountLease.getCurrentLeasingHeightTo());
            assertEquals(lesseeId, accountLease.getCurrentLesseeId());
        }else {
            assertEquals(expectedFrom, accountLease.getNextLeasingHeightFrom());
            assertEquals(expectedTo, accountLease.getNextLeasingHeightTo());
            assertEquals(lesseeId, accountLease.getNextLesseeId());
        }
        verify(accountLeaseTable).insert(accountLease);
        verify(firedEvent).fire(accountLease);
    }


}