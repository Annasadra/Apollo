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

package com.apollocurrency.aplwallet.apl.core.monetary;

import com.apollocurrency.aplwallet.apl.core.app.Blockchain;
import com.apollocurrency.aplwallet.apl.core.app.BlockchainImpl;
import com.apollocurrency.aplwallet.apl.core.app.Transaction;
import com.apollocurrency.aplwallet.apl.core.db.DatabaseManager;
import com.apollocurrency.aplwallet.apl.core.db.DbClause;
import com.apollocurrency.aplwallet.apl.core.db.DbIterator;
import com.apollocurrency.aplwallet.apl.core.db.DbKey;
import com.apollocurrency.aplwallet.apl.core.db.DbUtils;
import com.apollocurrency.aplwallet.apl.core.db.LongKeyFactory;
import com.apollocurrency.aplwallet.apl.core.db.TransactionalDataSource;
import com.apollocurrency.aplwallet.apl.core.db.derived.EntityDbTable;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.MonetarySystemCurrencyTransfer;
import com.apollocurrency.aplwallet.apl.util.Listener;
import com.apollocurrency.aplwallet.apl.util.Listeners;

import javax.enterprise.inject.spi.CDI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class CurrencyTransfer {

    private static final Listeners<CurrencyTransfer, Event> listeners = new Listeners<>();
    private static final LongKeyFactory<CurrencyTransfer> currencyTransferDbKeyFactory = new LongKeyFactory<CurrencyTransfer>("id") {

        @Override
        public DbKey newKey(CurrencyTransfer transfer) {
            return transfer.dbKey;
        }

    };
    private static final EntityDbTable<CurrencyTransfer> currencyTransferTable = new EntityDbTable<CurrencyTransfer>("currency_transfer", currencyTransferDbKeyFactory) {

        @Override
        public CurrencyTransfer load(Connection con, ResultSet rs, DbKey dbKey) throws SQLException {
            return new CurrencyTransfer(rs, dbKey);
        }

        @Override
        public void save(Connection con, CurrencyTransfer transfer) throws SQLException {
            transfer.save(con);
        }

    };
    private static DatabaseManager databaseManager = CDI.current().select(DatabaseManager.class).get();
    private final long id;
    private final DbKey dbKey;
    private final long currencyId;
    private final int height;
    private final long senderId;
    private final long recipientId;
    private final long units;
    private final int timestamp;


    private CurrencyTransfer(Transaction transaction, MonetarySystemCurrencyTransfer attachment) {
        this.id = transaction.getId();
        this.dbKey = currencyTransferDbKeyFactory.newKey(this.id);
        Blockchain blockchain = CDI.current().select(BlockchainImpl.class).get();
        this.height = blockchain.getHeight();
        this.currencyId = attachment.getCurrencyId();
        this.senderId = transaction.getSenderId();
        this.recipientId = transaction.getRecipientId();
        this.units = attachment.getUnits();
        this.timestamp = blockchain.getLastBlockTimestamp();
    }

    private CurrencyTransfer(ResultSet rs, DbKey dbKey) throws SQLException {
        this.id = rs.getLong("id");
        this.dbKey = dbKey;
        this.currencyId = rs.getLong("currency_id");
        this.senderId = rs.getLong("sender_id");
        this.recipientId = rs.getLong("recipient_id");
        this.units = rs.getLong("units");
        this.timestamp = rs.getInt("timestamp");
        this.height = rs.getInt("height");
    }

    private static TransactionalDataSource lookupDataSource() {
        if (databaseManager == null) {
            databaseManager = CDI.current().select(DatabaseManager.class).get();
        }
        return databaseManager.getDataSource();
    }

    public static DbIterator<CurrencyTransfer> getAllTransfers(int from, int to) {
        return currencyTransferTable.getAll(from, to);
    }

    public static int getCount() {
        return currencyTransferTable.getCount();
    }

    public static boolean addListener(Listener<CurrencyTransfer> listener, Event eventType) {
        return listeners.addListener(listener, eventType);
    }

    public static boolean removeListener(Listener<CurrencyTransfer> listener, Event eventType) {
        return listeners.removeListener(listener, eventType);
    }

    public static boolean addListener(Listener<CurrencyTransfer> listener) {
        return addListener(listener, Event.TRANSFER);
    }

    public static boolean removeListener(Listener<CurrencyTransfer> listener) {
        return removeListener(listener, Event.TRANSFER);
    }

    public static DbIterator<CurrencyTransfer> getCurrencyTransfers(long currencyId, int from, int to) {
        return currencyTransferTable.getManyBy(new DbClause.LongClause("currency_id", currencyId), from, to);
    }

    public static DbIterator<CurrencyTransfer> getAccountCurrencyTransfers(long accountId, int from, int to) {
        Connection con = null;
        try {
            con = lookupDataSource().getConnection();
            PreparedStatement pstmt = con.prepareStatement("SELECT * FROM currency_transfer WHERE sender_id = ?"
                + " UNION ALL SELECT * FROM currency_transfer WHERE recipient_id = ? AND sender_id <> ? ORDER BY height DESC, db_id DESC"
                + DbUtils.limitsClause(from, to));
            int i = 0;
            pstmt.setLong(++i, accountId);
            pstmt.setLong(++i, accountId);
            pstmt.setLong(++i, accountId);
            DbUtils.setLimits(++i, pstmt, from, to);
            return currencyTransferTable.getManyBy(con, pstmt, false);
        } catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }

    public static DbIterator<CurrencyTransfer> getAccountCurrencyTransfers(long accountId, long currencyId, int from, int to) {
        Connection con = null;
        try {
            con = lookupDataSource().getConnection();
            PreparedStatement pstmt = con.prepareStatement("SELECT * FROM currency_transfer WHERE sender_id = ? AND currency_id = ?"
                + " UNION ALL SELECT * FROM currency_transfer WHERE recipient_id = ? AND sender_id <> ? AND currency_id = ? ORDER BY height DESC, db_id DESC"
                + DbUtils.limitsClause(from, to));
            int i = 0;
            pstmt.setLong(++i, accountId);
            pstmt.setLong(++i, currencyId);
            pstmt.setLong(++i, accountId);
            pstmt.setLong(++i, accountId);
            pstmt.setLong(++i, currencyId);
            DbUtils.setLimits(++i, pstmt, from, to);
            return currencyTransferTable.getManyBy(con, pstmt, false);
        } catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }

    public static int getTransferCount(long currencyId) {
        return currencyTransferTable.getCount(new DbClause.LongClause("currency_id", currencyId));
    }

    static CurrencyTransfer addTransfer(Transaction transaction, MonetarySystemCurrencyTransfer attachment) {
        CurrencyTransfer transfer = new CurrencyTransfer(transaction, attachment);
        currencyTransferTable.insert(transfer);
        listeners.notify(transfer, Event.TRANSFER);
        return transfer;
    }

    public static void init() {
    }

    private void save(Connection con) throws SQLException {
        try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO currency_transfer (id, currency_id, "
            + "sender_id, recipient_id, units, timestamp, height) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            int i = 0;
            pstmt.setLong(++i, this.id);
            pstmt.setLong(++i, this.currencyId);
            pstmt.setLong(++i, this.senderId);
            pstmt.setLong(++i, this.recipientId);
            pstmt.setLong(++i, this.units);
            pstmt.setInt(++i, this.timestamp);
            pstmt.setInt(++i, this.height);
            pstmt.executeUpdate();
        }
    }

    public long getId() {
        return id;
    }

    public long getCurrencyId() {
        return currencyId;
    }

    public long getSenderId() {
        return senderId;
    }

    public long getRecipientId() {
        return recipientId;
    }

    public long getUnits() {
        return units;
    }

    public int getTimestamp() {
        return timestamp;
    }

    public int getHeight() {
        return height;
    }

    public enum Event {
        TRANSFER
    }

}
