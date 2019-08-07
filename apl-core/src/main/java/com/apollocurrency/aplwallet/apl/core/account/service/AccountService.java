/*
 *  Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.account.service;

import com.apollocurrency.aplwallet.apl.core.account.DoubleSpendingException;
import com.apollocurrency.aplwallet.apl.core.account.LedgerEvent;
import com.apollocurrency.aplwallet.apl.core.account.model.Account;
import com.apollocurrency.aplwallet.apl.core.app.Genesis;
import com.apollocurrency.aplwallet.apl.core.db.DbIterator;
import com.apollocurrency.aplwallet.apl.crypto.Convert;
import com.apollocurrency.aplwallet.apl.crypto.Crypto;

import java.sql.Connection;
import java.util.List;

/**
 * @author andrew.zinchenko@gmail.com
 */
public interface AccountService {

    static long getId(byte[] publicKey) {
        byte[] publicKeyHash = Crypto.sha256().digest(publicKey);
        return Convert.fullHashToId(publicKeyHash);
    }

    int getActiveLeaseCount();

    Account getAccount(long id);

    Account getAccount(long id, int height);

    Account getAccount(byte[] publicKey);

    Account addOrGetAccount(long id);

    Account addOrGetAccount(long id, boolean isGenesis);

    void save(Account account);

    long getEffectiveBalanceAPL(Account account, int height, boolean lock);

    long getGuaranteedBalanceATM(Account account);

    long getGuaranteedBalanceATM(Account account, int numberOfConfirmations, int currentHeight);

    long getLessorsGuaranteedBalanceATM(Account account, int height);

    DbIterator<Account> getLessorsIterator(Account account);

    DbIterator<Account> getLessorsIterator(Account account, int height);

    List<Account> getLessors(Account account);

    List<Account> getLessors(Account account, int height);

    static void checkBalance(long accountId, long confirmed, long unconfirmed) {
        if (accountId == Genesis.CREATOR_ID) {
            return;
        }
        if (confirmed < 0) {
            throw new DoubleSpendingException("Negative balance or quantity: ", accountId, confirmed, unconfirmed);
        }
        if (unconfirmed < 0) {
            throw new DoubleSpendingException("Negative unconfirmed balance or quantity: ", accountId, confirmed, unconfirmed);
        }
        if (unconfirmed > confirmed) {
            throw new DoubleSpendingException("Unconfirmed exceeds confirmed balance or quantity: ", accountId, confirmed, unconfirmed);
        }
    }

    void addToBalanceATM(Account account, LedgerEvent event, long eventId, long amountATM, long feeATM);

    void addToBalanceAndUnconfirmedBalanceATM(Account account, LedgerEvent event, long eventId, long amountATM);

    void addToUnconfirmedBalanceATM(Account account, LedgerEvent event, long eventId, long amountATM, long feeATM);

    void addToBalanceATM(Account account, LedgerEvent event, long eventId, long amountATM);

    void addToBalanceAndUnconfirmedBalanceATM(Account account, LedgerEvent event, long eventId, long amountATM, long feeATM);

    void addToUnconfirmedBalanceATM(Account account, LedgerEvent event, long eventId, long amountATM);

    long getTotalAmountOnTopAccounts(int numberOfTopAccounts);

    long getTotalAmountOnTopAccounts();

    long getTotalNumberOfAccounts();

    List<Account> getTopHolders(int numberOfTopAccounts);

    long getTotalSupply();

    int getBlockchainHeight();

    //Delegated from  AccountPublicKeyService
    boolean setOrVerify(long accountId, byte[] key);
    byte[] getPublicKey(long id);
}
