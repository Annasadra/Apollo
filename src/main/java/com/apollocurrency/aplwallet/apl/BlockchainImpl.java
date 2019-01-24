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
 * Copyright © 2018 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.apollocurrency.aplwallet.apl.db.DbIterator;
import com.apollocurrency.aplwallet.apl.db.DbUtils;
import com.apollocurrency.aplwallet.apl.util.Convert;
import com.apollocurrency.aplwallet.apl.util.Filter;
import com.apollocurrency.aplwallet.apl.util.ReadWriteUpdateLock;

final class BlockchainImpl implements Blockchain {

    private static final BlockchainImpl instance = new BlockchainImpl();

    static BlockchainImpl getInstance() {
        return instance;
    }

    private BlockchainImpl() {}

    private final ReadWriteUpdateLock lock = new ReadWriteUpdateLock();
    private final AtomicReference<BlockImpl> lastBlock = new AtomicReference<>();

    @Override
    public void readLock() {
        lock.readLock().lock();
    }

    @Override
    public void readUnlock() {
        lock.readLock().unlock();
    }

    @Override
    public void updateLock() {
        lock.updateLock().lock();
    }

    @Override
    public void updateUnlock() {
        lock.updateLock().unlock();
    }

    void writeLock() {
        lock.writeLock().lock();
    }

    void writeUnlock() {
        lock.writeLock().unlock();
    }

    @Override
    public BlockImpl getLastBlock() {
        return lastBlock.get();
    }

    void setLastBlock(BlockImpl block) {
        lastBlock.set(block);
    }

    @Override
    public int getHeight() {
        BlockImpl last = lastBlock.get();
        return last == null ? 0 : last.getHeight();
    }

    @Override
    public int getLastBlockTimestamp() {
        BlockImpl last = lastBlock.get();
        return last == null ? 0 : last.getTimestamp();
    }

    @Override
    public BlockImpl getLastBlock(int timestamp) {
        BlockImpl block = lastBlock.get();
        if (timestamp >= block.getTimestamp()) {
            return block;
        }
        return AplGlobalObjects.getBlockDb().findLastBlock(timestamp);
    }
    //load transactions
    @Override
    public BlockImpl getBlock(long blockId) {
        BlockImpl block = lastBlock.get();
        if (block.getId() == blockId) {
            return block;
        }
        return AplGlobalObjects.getBlockDb().findBlock(blockId);
    }

    @Override
    public boolean hasBlock(long blockId) {
        return lastBlock.get().getId() == blockId || AplGlobalObjects.getBlockDb().hasBlock(blockId);
    }
    //load transactions
    @Override
    public DbIterator<BlockImpl> getAllBlocks() {
        Connection con = null;
        try {
            con = Db.getDb().getConnection();
            PreparedStatement pstmt = con.prepareStatement("SELECT * FROM block ORDER BY db_id ASC");
            return getBlocks(con, pstmt);
        } catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }

    @Override
    public DbIterator<BlockImpl> getBlocks(int from, int to) {
        Connection con = null;
        try {
            con = Db.getDb().getConnection();
            PreparedStatement pstmt = con.prepareStatement("SELECT * FROM block WHERE height <= ? AND height >= ? ORDER BY height DESC");
            int blockchainHeight = getHeight();
            pstmt.setInt(1, blockchainHeight - from);
            pstmt.setInt(2, blockchainHeight - to);
            return getBlocks(con, pstmt);
        } catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }

    @Override
    public DbIterator<BlockImpl> getBlocks(long accountId, int timestamp) {
        return getBlocks(accountId, timestamp, 0, -1);
    }

    @Override
    public DbIterator<BlockImpl> getBlocks(long accountId, int timestamp, int from, int to) {
        Connection con = null;
        try {
            con = Db.getDb().getConnection();
            PreparedStatement pstmt = con.prepareStatement("SELECT * FROM block WHERE generator_id = ? "
                    + (timestamp > 0 ? " AND timestamp >= ? " : " ") + "ORDER BY height DESC"
                    + DbUtils.limitsClause(from, to));
            int i = 0;
            pstmt.setLong(++i, accountId);
            if (timestamp > 0) {
                pstmt.setInt(++i, timestamp);
            }
            DbUtils.setLimits(++i, pstmt, from, to);
            return getBlocks(con, pstmt);
        } catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }

    @Override
    public int getBlockCount(long accountId) {
        try (Connection con = Db.getDb().getConnection();
            PreparedStatement pstmt = con.prepareStatement("SELECT COUNT(*) FROM block WHERE generator_id = ?")) {
            pstmt.setLong(1, accountId);
            try (ResultSet rs = pstmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    @Override
    public DbIterator<BlockImpl> getBlocks(Connection con, PreparedStatement pstmt) {
        return new DbIterator<>(con, pstmt, BlockDb::loadBlock);
    }

    @Override
    public List<Long> getBlockIdsAfter(long blockId, int limit) {
        // Check the block cache
        List<Long> result = new ArrayList<>(AplGlobalObjects.getBlockDb().getBlockCacheSize());
        synchronized(AplGlobalObjects.getBlockDb().getBlockCache()) {
            BlockImpl block = AplGlobalObjects.getBlockDb().getBlockCache().get(blockId);
            if (block != null) {
                Collection<BlockImpl> cacheMap = AplGlobalObjects.getBlockDb().getHeightMap().tailMap(block.getHeight() + 1).values();
                for (BlockImpl cacheBlock : cacheMap) {
                    if (result.size() >= limit) {
                        break;
                    }
                    result.add(cacheBlock.getId());
                }
                return result;
            }
        }
        // Search the database
        try (Connection con = Db.getDb().getConnection();
                PreparedStatement pstmt = con.prepareStatement("SELECT id FROM block "
                            + "WHERE db_id > IFNULL ((SELECT db_id FROM block WHERE id = ?), " + Long.MAX_VALUE + ") "
                            + "ORDER BY db_id ASC LIMIT ?")) {
            pstmt.setLong(1, blockId);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong("id"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
        return result;
    }

    @Override
    public List<BlockImpl> getBlocksAfter(long blockId, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        // Check the block cache
        List<BlockImpl> result = new ArrayList<>(AplGlobalObjects.getBlockDb().getBlockCacheSize());
        synchronized(AplGlobalObjects.getBlockDb().getBlockCache()) {
            BlockImpl block = AplGlobalObjects.getBlockDb().getBlockCache().get(blockId);
            if (block != null) {
                Collection<BlockImpl> cacheMap = AplGlobalObjects.getBlockDb().getHeightMap().tailMap(block.getHeight() + 1).values();
                for (BlockImpl cacheBlock : cacheMap) {
                    if (result.size() >= limit) {
                        break;
                    }
                    result.add(cacheBlock);
                }
                return result;
            }
        }
        // Search the database
        try (Connection con = Db.getDb().getConnection();
                PreparedStatement pstmt = con.prepareStatement("SELECT * FROM block "
                        + "WHERE db_id > IFNULL ((SELECT db_id FROM block WHERE id = ?), " + Long.MAX_VALUE + ") "
                        + "ORDER BY db_id ASC LIMIT ?")) {
            pstmt.setLong(1, blockId);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(AplGlobalObjects.getBlockDb().loadBlock(con, rs, true));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
        return result;
    }

    @Override
    public List<BlockImpl> getBlocksAfter(long blockId, List<Long> blockList) {
        if (blockList.isEmpty()) {
            return Collections.emptyList();
        }
        // Check the block cache
        List<BlockImpl> result = new ArrayList<>(AplGlobalObjects.getBlockDb().getBlockCacheSize());
        synchronized(AplGlobalObjects.getBlockDb().getBlockCache()) {
            BlockImpl block = AplGlobalObjects.getBlockDb().getBlockCache().get(blockId);
            if (block != null) {
                Collection<BlockImpl> cacheMap = AplGlobalObjects.getBlockDb().getHeightMap().tailMap(block.getHeight() + 1).values();
                int index = 0;
                for (BlockImpl cacheBlock : cacheMap) {
                    if (result.size() >= blockList.size() || cacheBlock.getId() != blockList.get(index++)) {
                        break;
                    }
                    result.add(cacheBlock);
                }
                return result;
            }
        }
        // Search the database
        try (Connection con = Db.getDb().getConnection();
                PreparedStatement pstmt = con.prepareStatement("SELECT * FROM block "
                        + "WHERE db_id > IFNULL ((SELECT db_id FROM block WHERE id = ?), " + Long.MAX_VALUE + ") "
                        + "ORDER BY db_id ASC LIMIT ?")) {
            pstmt.setLong(1, blockId);
            pstmt.setInt(2, blockList.size());
            try (ResultSet rs = pstmt.executeQuery()) {
                int index = 0;
                while (rs.next()) {
                    BlockImpl block = AplGlobalObjects.getBlockDb().loadBlock(con, rs, true);
                    if (block.getId() != blockList.get(index++)) {
                        break;
                    }
                    result.add(block);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
        return result;
    }

    @Override
    public long getBlockIdAtHeight(int height) {
        Block block = lastBlock.get();
        if (height > block.getHeight()) {
            throw new IllegalArgumentException("Invalid height " + height + ", current blockchain is at " + block.getHeight());
        }
        if (height == block.getHeight()) {
            return block.getId();
        }
        return AplGlobalObjects.getBlockDb().findBlockIdAtHeight(height);
    }

    @Override
    public BlockImpl getBlockAtHeight(int height) {
        BlockImpl block = lastBlock.get();
        if (height > block.getHeight()) {
            throw new IllegalArgumentException("Invalid height " + height + ", current blockchain is at " + block.getHeight());
        }
        if (height == block.getHeight()) {
            return block;
        }
        return AplGlobalObjects.getBlockDb().findBlockAtHeight(height);
    }

    @Override
    public BlockImpl getECBlock(int timestamp) {
        Block block = getLastBlock(timestamp);
        if (block == null) {
            return getBlockAtHeight(0);
        }
        return AplGlobalObjects.getBlockDb().findBlockAtHeight(Math.max(block.getHeight() - 720, 0));
    }

    @Override
    public TransactionImpl getTransaction(long transactionId) {
        return TransactionDb.findTransaction(transactionId);
    }

    @Override
    public TransactionImpl getTransactionByFullHash(String fullHash) {
        return TransactionDb.findTransactionByFullHash(Convert.parseHexString(fullHash));
    }

    @Override
    public boolean hasTransaction(long transactionId) {
        return TransactionDb.hasTransaction(transactionId);
    }

    @Override
    public boolean hasTransactionByFullHash(String fullHash) {
        return TransactionDb.hasTransactionByFullHash(Convert.parseHexString(fullHash));
    }

    @Override
    public int getTransactionCount() {
        try (Connection con = Db.getDb().getConnection(); PreparedStatement pstmt = con.prepareStatement("SELECT COUNT(*) FROM transaction");
             ResultSet rs = pstmt.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    @Override
    public DbIterator<TransactionImpl> getAllTransactions() {
        Connection con = null;
        try {
            con = Db.getDb().getConnection();
            PreparedStatement pstmt = con.prepareStatement("SELECT * FROM transaction ORDER BY db_id ASC");
            return getTransactions(con, pstmt);
        } catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }

    @Override
    public DbIterator<TransactionImpl> getTransactions(long accountId, byte type, byte subtype, int blockTimestamp,
                                                       boolean includeExpiredPrunable) {
        return getTransactions(accountId, 0, type, subtype, blockTimestamp, false, false, false, 0, -1, includeExpiredPrunable, false, true);
    }

    @Override
    public DbIterator<TransactionImpl> getTransactions(long accountId, int numberOfConfirmations, byte type, byte subtype,
                                                       int blockTimestamp, boolean withMessage, boolean phasedOnly, boolean nonPhasedOnly,
                                                       int from, int to, boolean includeExpiredPrunable, boolean executedOnly, boolean includePrivate) {
        if (phasedOnly && nonPhasedOnly) {
            throw new IllegalArgumentException("At least one of phasedOnly or nonPhasedOnly must be false");
        }
        int height = numberOfConfirmations > 0 ? getHeight() - numberOfConfirmations : Integer.MAX_VALUE;
        if (height < 0) {
            throw new IllegalArgumentException("Number of confirmations required " + numberOfConfirmations
                    + " exceeds current blockchain height " + getHeight());
        }
        Connection con = null;
        try {
            StringBuilder buf = new StringBuilder();
            buf.append("SELECT transaction.* FROM transaction ");
            if (executedOnly && !nonPhasedOnly) {
                buf.append(" LEFT JOIN phasing_poll_result ON transaction.id = phasing_poll_result.id ");
            }
            buf.append("WHERE recipient_id = ? AND sender_id <> ? ");
            if (blockTimestamp > 0) {
                buf.append("AND block_timestamp >= ? ");
            }
            if (!includePrivate && TransactionType.findTransactionType(type, subtype) == TransactionType.Payment.PRIVATE) {
                    throw new RuntimeException("None of private transactions should be retrieved!");
            }
            if (type >= 0) {
                buf.append("AND type = ? ");
                if (subtype >= 0) {
                    buf.append("AND subtype = ? ");
                }
            }
            if (!includePrivate) {
                    buf.append("AND (type <> ? ");
                    buf.append("OR subtype <> ? ) ");
            }
            if (height < Integer.MAX_VALUE) {
                buf.append("AND transaction.height <= ? ");
            }
            if (withMessage) {
                buf.append("AND (has_message = TRUE OR has_encrypted_message = TRUE ");
                buf.append("OR ((has_prunable_message = TRUE OR has_prunable_encrypted_message = TRUE) AND timestamp > ?)) ");
            }
            if (phasedOnly) {
                buf.append("AND phased = TRUE ");
            } else if (nonPhasedOnly) {
                buf.append("AND phased = FALSE ");
            }
            if (executedOnly && !nonPhasedOnly) {
                buf.append("AND (phased = FALSE OR approved = TRUE) ");
            }
            buf.append("UNION ALL SELECT transaction.* FROM transaction ");
            if (executedOnly && !nonPhasedOnly) {
                buf.append(" LEFT JOIN phasing_poll_result ON transaction.id = phasing_poll_result.id ");
            }
            buf.append("WHERE sender_id = ? ");
            if (blockTimestamp > 0) {
                buf.append("AND block_timestamp >= ? ");
            }
            if (type >= 0) {
                buf.append("AND type = ? ");
                if (subtype >= 0) {
                    buf.append("AND subtype = ? ");
                }
            }
            if (!includePrivate) {
                buf.append("AND (type <> ? ");
                buf.append("OR subtype <> ? ) ");
            }
            if (height < Integer.MAX_VALUE) {
                buf.append("AND transaction.height <= ? ");
            }
            if (withMessage) {
                buf.append("AND (has_message = TRUE OR has_encrypted_message = TRUE OR has_encrypttoself_message = TRUE ");
                buf.append("OR ((has_prunable_message = TRUE OR has_prunable_encrypted_message = TRUE) AND timestamp > ?)) ");
            }
            if (phasedOnly) {
                buf.append("AND phased = TRUE ");
            } else if (nonPhasedOnly) {
                buf.append("AND phased = FALSE ");
            }
            if (executedOnly && !nonPhasedOnly) {
                buf.append("AND (phased = FALSE OR approved = TRUE) ");
            }

            buf.append("ORDER BY block_timestamp DESC, transaction_index DESC");
            buf.append(DbUtils.limitsClause(from, to));
            con = Db.getDb().getConnection();
            PreparedStatement pstmt;
            int i = 0;
            pstmt = con.prepareStatement(buf.toString());
            pstmt.setLong(++i, accountId);
            pstmt.setLong(++i, accountId);
            if (blockTimestamp > 0) {
                pstmt.setInt(++i, blockTimestamp);
            }
            if (type >= 0) {
                pstmt.setByte(++i, type);
                if (subtype >= 0) {
                    pstmt.setByte(++i, subtype);
                }
            }
            if (!includePrivate) {
                pstmt.setByte(++i, TransactionType.Payment.PRIVATE.getType());
                pstmt.setByte(++i, TransactionType.Payment.PRIVATE.getSubtype());
            }
            if (height < Integer.MAX_VALUE) {
                pstmt.setInt(++i, height);
            }
            int prunableExpiration = Math.max(0, Constants.INCLUDE_EXPIRED_PRUNABLE && includeExpiredPrunable ?
                                        Apl.getEpochTime() - AplGlobalObjects.getChainConfig().getMaxPrunableLifetime() :
                                        Apl.getEpochTime() - AplGlobalObjects.getChainConfig().getMinPrunableLifetime());
            if (withMessage) {
                pstmt.setInt(++i, prunableExpiration);
            }
            pstmt.setLong(++i, accountId);
            if (blockTimestamp > 0) {
                pstmt.setInt(++i, blockTimestamp);
            }
            if (type >= 0) {
                pstmt.setByte(++i, type);
                if (subtype >= 0) {
                    pstmt.setByte(++i, subtype);
                }
            }
            if (!includePrivate) {
                pstmt.setByte(++i, TransactionType.Payment.PRIVATE.getType());
                pstmt.setByte(++i, TransactionType.Payment.PRIVATE.getSubtype());
            }
            if (height < Integer.MAX_VALUE) {
                pstmt.setInt(++i, height);
            }
            if (withMessage) {
                pstmt.setInt(++i, prunableExpiration);
            }
            DbUtils.setLimits(++i, pstmt, from, to);
            return getTransactions(con, pstmt);
        } catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }
    @Override
    public DbIterator<TransactionImpl> getReferencingTransactions(long transactionId, int from, int to) {
        Connection con = null;
        try {
            con = Db.getDb().getConnection();
            PreparedStatement pstmt = con.prepareStatement("SELECT transaction.* FROM transaction, referenced_transaction "
                    + "WHERE referenced_transaction.referenced_transaction_id = ? "
                    + "AND referenced_transaction.transaction_id = transaction.id "
                    + "ORDER BY transaction.block_timestamp DESC, transaction.transaction_index DESC "
                    + DbUtils.limitsClause(from, to));
            int i = 0;
            pstmt.setLong(++i, transactionId);
            DbUtils.setLimits(++i, pstmt, from, to);
            return getTransactions(con, pstmt);
        } catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }
    @Override
    public DbIterator<TransactionImpl> getTransactions(byte type, byte subtype, int from, int to) {
        StringBuilder sqlQuery = new StringBuilder("SELECT * FROM transaction WHERE (type <> ? OR subtype <> ?) ");
        if (type >= 0) {
            sqlQuery.append("AND type = ? ");
            if (subtype >= 0) {
                sqlQuery.append("AND subtype = ? ");
            }
        }
        sqlQuery.append("ORDER BY block_timestamp DESC, transaction_index DESC ");
        sqlQuery.append(DbUtils.limitsClause(from, to));
        Connection con = null;
        try {
            con = Db.getDb().getConnection();
            PreparedStatement statement = con.prepareStatement(sqlQuery.toString());
            int i = 0;
            statement.setByte(++i, TransactionType.Payment.PRIVATE.getType());
            statement.setByte(++i, TransactionType.Payment.PRIVATE.getSubtype());
            if (type >= 0) {
                statement.setByte(++i, type);
                if (subtype >= 0) {
                    statement.setByte(++i, subtype);
                }
            }
            DbUtils.setLimits(++i, statement, from, to);
            return getTransactions(con, statement);
        }
        catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }
    @Override
    public int getTransactionCount(long accountId, byte type, byte subtype) {
        StringBuilder sqlQuery = new StringBuilder("SELECT COUNT(*) FROM transaction WHERE (type <> ? OR subtype <> ?) AND (sender_id = ? OR recipient_id = ?) ");
        if (type >= 0) {
            sqlQuery.append("AND type = ? ");
            if (subtype >= 0) {
                sqlQuery.append("AND subtype = ? ");
            }
        }
        try (
            Connection con = Db.getDb().getConnection();
            PreparedStatement statement = con.prepareStatement(sqlQuery.toString())) {
            int i = 0;
            statement.setByte(++i, TransactionType.Payment.PRIVATE.getType());
            statement.setByte(++i, TransactionType.Payment.PRIVATE.getSubtype());
            statement.setLong(++i, accountId);
            statement.setLong(++i, accountId);
            if (type >= 0) {
                statement.setByte(++i, type);
                if (subtype >= 0) {
                    statement.setByte(++i, subtype);
                }
            }
            try(ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }

        }
        catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    @Override
    public DbIterator<TransactionImpl> getTransactions(Connection con, PreparedStatement pstmt) {
        return new DbIterator<>(con, pstmt, TransactionDb::loadTransaction);
    }
    //phased transactions
    @Override
    public List<TransactionImpl> getExpectedTransactions(Filter<Transaction> filter) {
        Map<TransactionType, Map<String, Integer>> duplicates = new HashMap<>();
        BlockchainProcessorImpl blockchainProcessor = BlockchainProcessorImpl.getInstance();
        List<TransactionImpl> result = new ArrayList<>();
        readLock();
        try {
            try (DbIterator<TransactionImpl> phasedTransactions = PhasingPoll.getFinishingTransactions(getHeight() + 1)) {
                for (TransactionImpl phasedTransaction : phasedTransactions) {
                    try {
                        phasedTransaction.validate();
                        if (!phasedTransaction.attachmentIsDuplicate(duplicates, false) && filter.test(phasedTransaction)) {
                            result.add(phasedTransaction);
                        }
                    } catch (AplException.ValidationException ignore) {
                    }
                }
            }
            blockchainProcessor.selectUnconfirmedTransactions(duplicates, getLastBlock(), -1).forEach(
                    unconfirmedTransaction -> {
                        TransactionImpl transaction = unconfirmedTransaction.getTransaction();
                        if (transaction.getPhasing() == null && filter.test(transaction)) {
                            result.add(transaction);
                        }
                    }
            );
        } finally {
            readUnlock();
        }
        return result;
    }
}