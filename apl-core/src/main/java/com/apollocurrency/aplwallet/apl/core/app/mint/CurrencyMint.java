/*
 * Copyright © 2013-2016 The Nxt Core Developers.
 * Copyright © 2016-2017 Jelurida IP B.V.
 *
 * See the LICENSE.txt file at the top-level directory of this distribution
 * for licensing information.
 *
 * Unless otherwise agreed in a custom licensing agreement with Jelurida B.V.,
 * no part of the Nxt software, including this file, may be copied, modified,
 * propagated, or distributed except according to the terms contained in the
 * LICENSE.txt file.
 *
 * Removal or modification of this copyright notice is prohibited.
 *
 */

/*
 * Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.app.mint;

import com.apollocurrency.aplwallet.apl.core.entity.state.account.LedgerEvent;
import com.apollocurrency.aplwallet.apl.core.entity.state.account.Account;
import com.apollocurrency.aplwallet.apl.core.service.state.account.AccountCurrencyService;
import com.apollocurrency.aplwallet.apl.core.service.state.account.impl.AccountCurrencyServiceImpl;
import com.apollocurrency.aplwallet.apl.core.service.blockchain.Blockchain;
import com.apollocurrency.aplwallet.apl.core.service.blockchain.BlockchainImpl;
import com.apollocurrency.aplwallet.apl.core.db.DbClause;
import com.apollocurrency.aplwallet.apl.core.db.DbIterator;
import com.apollocurrency.aplwallet.apl.core.dao.state.keyfactory.DbKey;
import com.apollocurrency.aplwallet.apl.core.dao.state.keyfactory.LinkKeyFactory;
import com.apollocurrency.aplwallet.apl.core.dao.state.derived.VersionedDeletableEntityDbTable;
import com.apollocurrency.aplwallet.apl.core.monetary.Currency;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.MonetarySystemCurrencyMinting;
import com.apollocurrency.aplwallet.apl.util.annotation.DatabaseSpecificDml;
import com.apollocurrency.aplwallet.apl.util.annotation.DmlMarker;
import org.slf4j.Logger;

import javax.enterprise.inject.spi.CDI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Manages currency proof of work minting
 */
@Deprecated
public final class CurrencyMint {
    private static final Logger LOG = getLogger(CurrencyMint.class);
    private static final LinkKeyFactory<CurrencyMint> currencyMintDbKeyFactory = new LinkKeyFactory<CurrencyMint>("currency_id", "account_id") {

        @Override
        public DbKey newKey(CurrencyMint currencyMint) {
            return currencyMint.dbKey;
        }

    };
    private static final VersionedDeletableEntityDbTable<CurrencyMint> currencyMintTable = new VersionedDeletableEntityDbTable<CurrencyMint>("currency_mint", currencyMintDbKeyFactory) {

        @Override
        public CurrencyMint load(Connection con, ResultSet rs, DbKey dbKey) throws SQLException {
            return new CurrencyMint(rs, dbKey);
        }

        @Override
        public void save(Connection con, CurrencyMint currencyMint) throws SQLException {
            currencyMint.save(con);
        }

    };
//    private static final Listeners<Mint, Event> listeners = new Listeners<>();
    private static Blockchain blockchain = CDI.current().select(BlockchainImpl.class).get();
    private static AccountCurrencyService accountCurrencyService;
    private final DbKey dbKey;
    private final long currencyId;
    private final long accountId;
    private long counter;

    private CurrencyMint(long currencyId, long accountId, long counter) {
        this.currencyId = currencyId;
        this.accountId = accountId;
        this.dbKey = currencyMintDbKeyFactory.newKey(this.currencyId, this.accountId);
        this.counter = counter;
    }


    private CurrencyMint(ResultSet rs, DbKey dbKey) throws SQLException {
        this.currencyId = rs.getLong("currency_id");
        this.accountId = rs.getLong("account_id");
        this.dbKey = dbKey;
        this.counter = rs.getLong("counter");
    }

    private static AccountCurrencyService lookupAccountCurrencyService() {
        if (accountCurrencyService == null) {
            accountCurrencyService = CDI.current().select(AccountCurrencyServiceImpl.class).get();
        }
        return accountCurrencyService;
    }

/*    public static boolean addListener(Listener<Mint> listener, Event eventType) {
        return listeners.addListener(listener, eventType);
    }

    public static boolean removeListener(Listener<Mint> listener, Event eventType) {
        return listeners.removeListener(listener, eventType);
    }*/

    /**
     * @deprecated
     */
    public static void init() {
    }

    /**
     * @deprecated
     */
    public static void mintCurrency(LedgerEvent event, long eventId, final Account account,
                                    final MonetarySystemCurrencyMinting attachment) {
        CurrencyMint currencyMint = currencyMintTable.get(currencyMintDbKeyFactory.newKey(attachment.getCurrencyId(), account.getId()));
        if (currencyMint != null && attachment.getCounter() <= currencyMint.getCounter()) {
            return;
        }
        Currency currency = Currency.getCurrency(attachment.getCurrencyId());
        if (CurrencyMinting.meetsTarget(account.getId(), /*currency*/null, attachment)) {
            if (currencyMint == null) {
                currencyMint = new CurrencyMint(attachment.getCurrencyId(), account.getId(), attachment.getCounter());
            } else {
                currencyMint.counter = attachment.getCounter();
            }
            currencyMintTable.insert(currencyMint);
            long units = Math.min(attachment.getUnits(), currency.getMaxSupply() - currency.getCurrentSupply());
            lookupAccountCurrencyService().addToCurrencyAndUnconfirmedCurrencyUnits(account, event, eventId, currency.getId(), units);
            currency.increaseSupply(units);
//            listeners.notify(new Mint(account.getId(), currency.getId(), units), Event.CURRENCY_MINT);
        } else {
            LOG.debug("Currency mint hash no longer meets target %s", attachment.getJSONObject().toJSONString());
        }
    }

    /**
     * @deprecated
     */
    public static long getCounter(long currencyId, long accountId) {
        CurrencyMint currencyMint = currencyMintTable.get(currencyMintDbKeyFactory.newKey(currencyId, accountId));
        if (currencyMint != null) {
            return currencyMint.getCounter();
        } else {
            return 0;
        }
    }

    /**
     * @deprecated
     */
    public static void deleteCurrency(Currency currency) {
        List<CurrencyMint> currencyMints = new ArrayList<>();
        try (DbIterator<CurrencyMint> mints = currencyMintTable.getManyBy(new DbClause.LongClause("currency_id", currency.getId()), 0, -1)) {
            while (mints.hasNext()) {
                currencyMints.add(mints.next());
            }
        }
        currencyMints.forEach(c -> currencyMintTable.deleteAtHeight(c, blockchain.getHeight()));
    }

    private void save(Connection con) throws SQLException {
        Blockchain blockchain = CDI.current().select(BlockchainImpl.class).get();
        try (
            @DatabaseSpecificDml(DmlMarker.MERGE)
            PreparedStatement pstmt = con.prepareStatement("MERGE INTO currency_mint (currency_id, account_id, counter, height, latest, deleted) "
                + "KEY (currency_id, account_id, height) VALUES (?, ?, ?, ?, TRUE, FALSE)")
        ) {
            int i = 0;
            pstmt.setLong(++i, this.currencyId);
            pstmt.setLong(++i, this.accountId);
            pstmt.setLong(++i, this.counter);
            pstmt.setInt(++i, blockchain.getHeight());
            pstmt.executeUpdate();
        }
    }

    public long getCurrencyId() {
        return currencyId;
    }

    public long getAccountId() {
        return accountId;
    }

    public long getCounter() {
        return counter;
    }

    public enum Event {
        CURRENCY_MINT
    }

    public static class Mint {

        public final long accountId;
        public final long currencyId;
        public final long units;

        private Mint(long accountId, long currencyId, long units) {
            this.accountId = accountId;
            this.currencyId = currencyId;
            this.units = units;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Mint)) return false;
            Mint mint = (Mint) o;
            return accountId == mint.accountId &&
                currencyId == mint.currencyId &&
                units == mint.units;
        }

        @Override
        public int hashCode() {

            return Objects.hash(accountId, currencyId, units);
        }
    }

}
