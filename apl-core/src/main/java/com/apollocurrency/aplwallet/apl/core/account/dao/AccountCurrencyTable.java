/*
 * Copyright © 2018-2019 Apollo Foundation.
 */

package com.apollocurrency.aplwallet.apl.core.account.dao;

import com.apollocurrency.aplwallet.apl.core.account.model.AccountCurrency;
import com.apollocurrency.aplwallet.apl.core.db.DbClause;
import com.apollocurrency.aplwallet.apl.core.db.DbIterator;
import com.apollocurrency.aplwallet.apl.core.db.DbKey;
import com.apollocurrency.aplwallet.apl.core.db.LinkKeyFactory;
import com.apollocurrency.aplwallet.apl.core.db.derived.VersionedDeletableEntityDbTable;

import javax.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 *
 * @author al
 */
@Singleton
public class AccountCurrencyTable extends VersionedDeletableEntityDbTable<AccountCurrency> {

    private static final LinkKeyFactory<AccountCurrency> accountCurrencyDbKeyFactory = new LinkKeyFactory<AccountCurrency>("account_id", "currency_id") {
    
        @Override
        public DbKey newKey(AccountCurrency accountCurrency) {
            return accountCurrency.getDbKey() == null ? newKey(accountCurrency.getAccountId(), accountCurrency.getCurrencyId()) : accountCurrency.getDbKey();
        }

    };  

    public static DbKey newKey(long idA, long idB){
        return accountCurrencyDbKeyFactory.newKey(idA,idB);
    } 

    public AccountCurrencyTable() {
        super("account_currency", accountCurrencyDbKeyFactory);
    }

    @Override
    public AccountCurrency load(Connection con, ResultSet rs, DbKey dbKey) throws SQLException {
        return new AccountCurrency(rs, dbKey);
    }

    @Override
    public void save(Connection con, AccountCurrency accountCurrency) throws SQLException {
        try (final PreparedStatement pstmt = con.prepareStatement("MERGE INTO account_currency " + "(account_id, currency_id, units, unconfirmed_units, height, latest) " + "KEY (account_id, currency_id, height) VALUES (?, ?, ?, ?, ?, TRUE)")) {
            int i = 0;
            pstmt.setLong(++i, accountCurrency.getAccountId());
            pstmt.setLong(++i, accountCurrency.getCurrencyId());
            pstmt.setLong(++i, accountCurrency.getUnits());
            pstmt.setLong(++i, accountCurrency.getUnconfirmedUnits());
            pstmt.setInt(++i, accountCurrency.getHeight());
            pstmt.executeUpdate();
        }
    }

    @Override
    protected String defaultSort() {
        return " ORDER BY units DESC, account_id, currency_id ";
    }

    public DbIterator<AccountCurrency> getAccountCurrencies(long accountId, int from, int to) {
        return getManyBy(new DbClause.LongClause("account_id", accountId), from, to);
    }

    public DbIterator<AccountCurrency> getAccountCurrencies(long accountId, int height, int from, int to) {
        return getManyBy(new DbClause.LongClause("account_id", accountId), height, from, to);
    }

    public DbIterator<AccountCurrency> getCurrencyAccounts(long currencyId, int from, int to) {
        return getManyBy(new DbClause.LongClause("currency_id", currencyId), from, to);
    }

    public DbIterator<AccountCurrency> getCurrencyAccounts(long currencyId, int height, int from, int to) {
        return getManyBy(new DbClause.LongClause("currency_id", currencyId), height, from, to);
    }

    public int getCurrencyAccountCount(long currencyId) {
        return getCount(new DbClause.LongClause("currency_id", currencyId));
    }

    public int getCurrencyAccountCount(long currencyId, int height) {
        return getCount(new DbClause.LongClause("currency_id", currencyId), height);
    }

    public int getAccountCurrencyCount(long accountId) {
        return getCount(new DbClause.LongClause("account_id", accountId));
    }

    public int getAccountCurrencyCount(long accountId, int height) {
        return getCount(new DbClause.LongClause("account_id", accountId), height);
    }
}
