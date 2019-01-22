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

package com.apollocurrency.aplwallet.apl.core.app;

import static java.util.Comparator.comparingInt;
import static java.util.Comparator.comparingLong;
import static org.slf4j.LoggerFactory.getLogger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.spi.CDI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.apollocurrency.aplwallet.apl.core.app.transaction.messages.AbstractAppendix;
import com.apollocurrency.aplwallet.apl.core.app.transaction.messages.Appendix;
import com.apollocurrency.aplwallet.apl.core.app.transaction.messages.Prunable;
import com.apollocurrency.aplwallet.apl.core.chainid.BlockchainConfig;
import com.apollocurrency.aplwallet.apl.core.db.DbClause;
import com.apollocurrency.aplwallet.apl.core.db.DbIterator;
import com.apollocurrency.aplwallet.apl.core.db.DbKey;
import com.apollocurrency.aplwallet.apl.core.db.EntityDbTable;
import com.apollocurrency.aplwallet.apl.core.peer.Peer;
import com.apollocurrency.aplwallet.apl.core.peer.Peers;
import com.apollocurrency.aplwallet.apl.util.AplException;
import com.apollocurrency.aplwallet.apl.util.JSON;
import com.apollocurrency.aplwallet.apl.util.Listener;
import com.apollocurrency.aplwallet.apl.util.Listeners;
import com.apollocurrency.aplwallet.apl.util.NtpTime;
import com.apollocurrency.aplwallet.apl.util.ThreadPool;
import com.apollocurrency.aplwallet.apl.util.injectable.PropertiesHolder;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;

@ApplicationScoped
public class TransactionProcessorImpl implements TransactionProcessor {
    private static final Logger LOG = getLogger(TransactionProcessorImpl.class);

    // TODO: YL remove static instance later
    private static PropertiesHolder propertiesLoader = CDI.current().select(PropertiesHolder.class).get();    
    private static TransactionDb transactionDb = CDI.current().select(TransactionDb.class).get();
    private BlockchainConfig blockchainConfig = CDI.current().select(BlockchainConfig.class).get();
    private NtpTime ntpTime = CDI.current().select(NtpTime.class).get();
    private static Blockchain blockchain;
    private static BlockchainProcessor blockchainProcessor;

    private static final boolean enableTransactionRebroadcasting = propertiesLoader.getBooleanProperty("apl.enableTransactionRebroadcasting");
    private static final boolean testUnconfirmedTransactions = propertiesLoader.getBooleanProperty("apl.testUnconfirmedTransactions");
    private static int maxUnconfirmedTransactions;
/*
    static {
        int n = propertiesLoader.getIntProperty("apl.maxUnconfirmedTransactions");
        maxUnconfirmedTransactions = n <= 0 ? Integer.MAX_VALUE : n;
    }

    private static TransactionProcessorImpl instance = new TransactionProcessorImpl();

    public static TransactionProcessorImpl getInstance() {
        if (instance == null) instance = CDI.current().select(TransactionProcessorImpl.class).get();
        return instance;
    }
*/
    private BlockchainProcessor lookupBlockchainProcessor() {
        if (blockchainProcessor == null) {
            blockchainProcessor = CDI.current().select(BlockchainProcessorImpl.class).get();
        }
        return blockchainProcessor;
    }

    private final Map<DbKey, UnconfirmedTransaction> transactionCache = new HashMap<>();
    private volatile boolean cacheInitialized = false;

    private DbKey.LongKeyFactory<UnconfirmedTransaction> unconfirmedTransactionDbKeyFactory = new DbKey.LongKeyFactory<UnconfirmedTransaction>("id") {

        @Override
        public DbKey newKey(UnconfirmedTransaction unconfirmedTransaction) {
            return unconfirmedTransaction.getTransaction().getDbKey();
        }

    };

    public DbKey.LongKeyFactory<UnconfirmedTransaction> getUnconfirmedTransactionDbKeyFactory() {
        return unconfirmedTransactionDbKeyFactory;
    }

    private final EntityDbTable<UnconfirmedTransaction> unconfirmedTransactionTable =
            new EntityDbTable<UnconfirmedTransaction>("unconfirmed_transaction", unconfirmedTransactionDbKeyFactory) {

        @Override
        protected UnconfirmedTransaction load(Connection con, ResultSet rs, DbKey dbKey) throws SQLException {
            return new UnconfirmedTransaction(rs);
        }

        @Override
        protected void save(Connection con, UnconfirmedTransaction unconfirmedTransaction) throws SQLException {
            unconfirmedTransaction.save(con);
            if (transactionCache.size() < maxUnconfirmedTransactions) {
                transactionCache.put(unconfirmedTransaction.getDbKey(), unconfirmedTransaction);
            }
        }

        @Override
        public void rollback(int height) {
            try (Connection con = Db.getDb().getConnection();
                 PreparedStatement pstmt = con.prepareStatement("SELECT * FROM unconfirmed_transaction WHERE height > ?")) {
                pstmt.setInt(1, height);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        UnconfirmedTransaction unconfirmedTransaction = load(con, rs, null);
                        waitingTransactions.add(unconfirmedTransaction);
                        transactionCache.remove(unconfirmedTransaction.getDbKey());
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e.toString(), e);
            }
            super.rollback(height);
            unconfirmedDuplicates.clear();
        }

        @Override
        public void truncate() {
            super.truncate();
            clearCache();
        }

        @Override
        protected String defaultSort() {
            return " ORDER BY transaction_height ASC, fee_per_byte DESC, arrival_timestamp ASC, id ASC ";
        }

    };

    private final Set<TransactionImpl> broadcastedTransactions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Listeners<List<? extends Transaction>,Event> transactionListeners = new Listeners<>();

    private final PriorityQueue<UnconfirmedTransaction> waitingTransactions = new PriorityQueue<UnconfirmedTransaction>(
            (UnconfirmedTransaction o1, UnconfirmedTransaction o2) -> {
                int result;
                if ((result = Integer.compare(o2.getHeight(), o1.getHeight())) != 0) {
                    return result;
                }
                if ((result = Boolean.compare(o2.getTransaction().referencedTransactionFullHash() != null,
                        o1.getTransaction().referencedTransactionFullHash() != null)) != 0) {
                    return result;
                }
                if ((result = Long.compare(o1.getFeePerByte(), o2.getFeePerByte())) != 0) {
                    return result;
                }
                if ((result = Long.compare(o2.getArrivalTimestamp(), o1.getArrivalTimestamp())) != 0) {
                    return result;
                }
                return Long.compare(o2.getId(), o1.getId());
            })
    {

        @Override
        public boolean add(UnconfirmedTransaction unconfirmedTransaction) {
            if (!super.add(unconfirmedTransaction)) {
                return false;
            }
            if (size() > maxUnconfirmedTransactions) {
                UnconfirmedTransaction removed = remove();
                //LOG.debug("Dropped unconfirmed transaction " + removed.getJSONObject().toJSONString());
            }
            return true;
        }

    };

    private final Map<TransactionType, Map<String, Integer>> unconfirmedDuplicates = new HashMap<>();


    private final Runnable removeUnconfirmedTransactionsThread = () -> {

        try {
            try {
                if (lookupBlockchainProcessor().isDownloading() && ! testUnconfirmedTransactions) {
                    return;
                }
                List<UnconfirmedTransaction> expiredTransactions = new ArrayList<>();
                try (DbIterator<UnconfirmedTransaction> iterator = unconfirmedTransactionTable.getManyBy(
                        new DbClause.IntClause("expiration", DbClause.Op.LT, AplCore.getEpochTime()), 0, -1, "")) {
                    while (iterator.hasNext()) {
                        expiredTransactions.add(iterator.next());
                    }
                }
                if (expiredTransactions.size() > 0) {
                    blockchain.writeLock();
                    try {
                        try {
                            Db.getDb().beginTransaction();
                            for (UnconfirmedTransaction unconfirmedTransaction : expiredTransactions) {
                                removeUnconfirmedTransaction(unconfirmedTransaction.getTransaction());
                            }
                            Db.getDb().commitTransaction();
                        } catch (Exception e) {
                            LOG.error(e.toString(), e);
                            Db.getDb().rollbackTransaction();
                            throw e;
                        } finally {
                            Db.getDb().endTransaction();
                        }
                    } finally {
                        blockchain.writeUnlock();
                    }
                }
            } catch (Exception e) {
                LOG.info("Error removing unconfirmed transactions", e);
            }
        } catch (Throwable t) {
            LOG.error("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
            t.printStackTrace();
            System.exit(1);
        }

    };

    private final Runnable rebroadcastTransactionsThread = () -> {

        try {
            try {
                if (lookupBlockchainProcessor().isDownloading() && ! testUnconfirmedTransactions) {
                    return;
                }
                List<Transaction> transactionList = new ArrayList<>();
                int curTime = AplCore.getEpochTime();
                for (TransactionImpl transaction : broadcastedTransactions) {
                    if (transaction.getExpiration() < curTime || transactionDb.hasTransaction(transaction.getId())) {
                        broadcastedTransactions.remove(transaction);
                    } else if (transaction.getTimestamp() < curTime - 30) {
                        transactionList.add(transaction);
                    }
                }

                if (transactionList.size() > 0) {
                    Peers.sendToSomePeers(transactionList);
                }

            } catch (Exception e) {
                LOG.info("Error in transaction re-broadcasting thread", e);
            }
        } catch (Throwable t) {
            LOG.error("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
            t.printStackTrace();
            System.exit(1);
        }

    };

    private final Runnable processTransactionsThread = () -> {

        try {
            try {
                if (lookupBlockchainProcessor().isDownloading() && ! testUnconfirmedTransactions) {
                    return;
                }
                Peer peer = Peers.getAnyPeer(Peer.State.CONNECTED, true);
                if (peer == null) {
                    return;
                }
                JSONObject request = new JSONObject();
                request.put("requestType", "getUnconfirmedTransactions");
                JSONArray exclude = new JSONArray();
                getAllUnconfirmedTransactionIds().forEach(transactionId -> exclude.add(Long.toUnsignedString(transactionId)));
                Collections.sort(exclude);
                request.put("exclude", exclude);
                request.put("chainId", blockchainConfig.getChain().getChainId());
                JSONObject response = peer.send(JSON.prepareRequest(request), blockchainConfig.getChain().getChainId(),
                        10 * 1024 * 1024, false);
                if (response == null) {
                    return;
                }
                JSONArray transactionsData = (JSONArray)response.get("unconfirmedTransactions");
                if (transactionsData == null || transactionsData.size() == 0) {
                    return;
                }
                try {
                    processPeerTransactions(transactionsData);
                } catch (AplException.ValidationException|RuntimeException e) {
                    peer.blacklist(e);
                }
            } catch (Exception e) {
                LOG.info("Error processing unconfirmed transactions", e);
            }
        } catch (Throwable t) {
            LOG.error("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
            t.printStackTrace();
            System.exit(1);
        }

    };

    private final Runnable processWaitingTransactionsThread = () -> {

        try {
            try {
                if (lookupBlockchainProcessor().isDownloading() && ! testUnconfirmedTransactions) {
                    return;
                }
                processWaitingTransactions();
            } catch (Exception e) {
                LOG.info("Error processing waiting transactions", e);
            }
        } catch (Throwable t) {
            LOG.error("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
            t.printStackTrace();
            System.exit(1);
        }

    };


//    private TransactionProcessorImpl() {
    public void init() {
        if (!Constants.isLightClient) {
            if (!Constants.isOffline) {
                ThreadPool.scheduleThread("ProcessTransactions", processTransactionsThread, 5);
                ThreadPool.runAfterStart("Rebroadcast all unconfirmed txs",this::rebroadcastAllUnconfirmedTransactions);
                ThreadPool.scheduleThread("RebroadcastTransactions", rebroadcastTransactionsThread, 23);
            }
            ThreadPool.scheduleThread("RemoveUnconfirmedTransactions", removeUnconfirmedTransactionsThread, 20);
            ThreadPool.scheduleThread("ProcessWaitingTransactions", processWaitingTransactionsThread, 1);
        }
        int n = propertiesLoader.getIntProperty("apl.maxUnconfirmedTransactions");
        maxUnconfirmedTransactions = n <= 0 ? Integer.MAX_VALUE : n;
        blockchain = CDI.current().select(BlockchainImpl.class).get();
    }

    @Override
    public boolean addListener(Listener<List<? extends Transaction>> listener, Event eventType) {
        return transactionListeners.addListener(listener, eventType);
    }

    @Override
    public boolean removeListener(Listener<List<? extends Transaction>> listener, Event eventType) {
        return transactionListeners.removeListener(listener, eventType);
    }

    public void notifyListeners(List<? extends Transaction> transactions, Event eventType) {
        transactionListeners.notify(transactions, eventType);
    }

    @Override
    public DbIterator<UnconfirmedTransaction> getAllUnconfirmedTransactions() {
        return unconfirmedTransactionTable.getAll(0, -1);
    }

    @Override
    public DbIterator<UnconfirmedTransaction> getAllUnconfirmedTransactions(int from, int to) {
        return unconfirmedTransactionTable.getAll(from, to);
    }

    @Override
    public DbIterator<UnconfirmedTransaction> getAllUnconfirmedTransactions(String sort) {
        return unconfirmedTransactionTable.getAll(0, -1, sort);
    }

    @Override
    public DbIterator<UnconfirmedTransaction> getAllUnconfirmedTransactions(int from, int to, String sort) {
        return unconfirmedTransactionTable.getAll(from, to, sort);
    }

    @Override
    public Transaction getUnconfirmedTransaction(long transactionId) {
        DbKey dbKey = unconfirmedTransactionDbKeyFactory.newKey(transactionId);
        return getUnconfirmedTransaction(dbKey);
    }

    Transaction getUnconfirmedTransaction(DbKey dbKey) {
        blockchain.readLock();
        try {
            Transaction transaction = transactionCache.get(dbKey);
            if (transaction != null) {
                return transaction;
            }
        } finally {
            blockchain.readUnlock();
        }
        return unconfirmedTransactionTable.get(dbKey);
    }

    private List<Long> getAllUnconfirmedTransactionIds() {
        List<Long> result = new ArrayList<>();
        try (Connection con = Db.getDb().getConnection();
             PreparedStatement pstmt = con.prepareStatement("SELECT id FROM unconfirmed_transaction");
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                result.add(rs.getLong("id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
        return result;
    }

    @Override
    public UnconfirmedTransaction[] getAllWaitingTransactions() {
        UnconfirmedTransaction[] transactions;
        blockchain.readLock();
        try {
            transactions = waitingTransactions.toArray(new UnconfirmedTransaction[waitingTransactions.size()]);
        } finally {
            blockchain.readUnlock();
        }
        Arrays.sort(transactions, waitingTransactions.comparator());
        return transactions;
    }

    public Collection<UnconfirmedTransaction> getWaitingTransactions() {
        return Collections.unmodifiableCollection(waitingTransactions);
    }

    @Override
    public TransactionImpl[] getAllBroadcastedTransactions() {
        blockchain.readLock();
        try {
            return broadcastedTransactions.toArray(new TransactionImpl[broadcastedTransactions.size()]);
        } finally {
            blockchain.readUnlock();
        }
    }

    @Override
    public void broadcast(Transaction transaction) throws AplException.ValidationException {
        blockchain.writeLock();
        try {
            if (transactionDb.hasTransaction(transaction.getId())) {
                LOG.info("Transaction " + transaction.getStringId() + " already in blockchain, will not broadcast again");
                return;
            }
            if (getUnconfirmedTransaction(((TransactionImpl)transaction).getDbKey()) != null) {
                if (enableTransactionRebroadcasting) {
                    broadcastedTransactions.add((TransactionImpl) transaction);
                    LOG.info("Transaction " + transaction.getStringId() + " already in unconfirmed pool, will re-broadcast");
                } else {
                    LOG.info("Transaction " + transaction.getStringId() + " already in unconfirmed pool, will not broadcast again");
                }
                return;
            }
            transaction.validate();
            UnconfirmedTransaction unconfirmedTransaction = new UnconfirmedTransaction((TransactionImpl) transaction, ntpTime.getTime());
            boolean broadcastLater = lookupBlockchainProcessor().isProcessingBlock();
            if (broadcastLater) {
                waitingTransactions.add(unconfirmedTransaction);
                broadcastedTransactions.add((TransactionImpl) transaction);
                LOG.debug("Will broadcast new transaction later " + transaction.getStringId());
            } else {
                processTransaction(unconfirmedTransaction);
                LOG.debug("Accepted new transaction " + transaction.getStringId());
                List<Transaction> acceptedTransactions = Collections.singletonList(transaction);
                Peers.sendToSomePeers(acceptedTransactions);
                transactionListeners.notify(acceptedTransactions, Event.ADDED_UNCONFIRMED_TRANSACTIONS);
                if (enableTransactionRebroadcasting) {
                    broadcastedTransactions.add((TransactionImpl) transaction);
                }
            }
        } finally {
            blockchain.writeUnlock();
        }
    }

    @Override
    public void processPeerTransactions(JSONObject request) throws AplException.ValidationException {
        JSONArray transactionsData = (JSONArray)request.get("transactions");
        processPeerTransactions(transactionsData);
    }

    @Override
    public void clearUnconfirmedTransactions() {
        blockchain.writeLock();
        try {
            List<Transaction> removed = new ArrayList<>();
            try {
                Db.getDb().beginTransaction();
                try (DbIterator<UnconfirmedTransaction> unconfirmedTransactions = getAllUnconfirmedTransactions()) {
                    for (UnconfirmedTransaction unconfirmedTransaction : unconfirmedTransactions) {
                        unconfirmedTransaction.getTransaction().undoUnconfirmed();
                        removed.add(unconfirmedTransaction.getTransaction());
                    }
                }
                unconfirmedTransactionTable.truncate();
                Db.getDb().commitTransaction();
            } catch (Exception e) {
                LOG.error(e.toString(), e);
                Db.getDb().rollbackTransaction();
                throw e;
            } finally {
                Db.getDb().endTransaction();
            }
            unconfirmedDuplicates.clear();
            waitingTransactions.clear();
            broadcastedTransactions.clear();
            transactionCache.clear();
            transactionListeners.notify(removed, Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
        } finally {
            blockchain.writeUnlock();
        }
    }

    @Override
    public void requeueAllUnconfirmedTransactions() {
        blockchain.writeLock();
        try {
            if (!Db.getDb().isInTransaction()) {
                try {
                    Db.getDb().beginTransaction();
                    requeueAllUnconfirmedTransactions();
                    Db.getDb().commitTransaction();
                } catch (Exception e) {
                    LOG.error(e.toString(), e);
                    Db.getDb().rollbackTransaction();
                    throw e;
                } finally {
                    Db.getDb().endTransaction();
                }
                return;
            }
            List<Transaction> removed = new ArrayList<>();
            try (DbIterator<UnconfirmedTransaction> unconfirmedTransactions = getAllUnconfirmedTransactions()) {
                for (UnconfirmedTransaction unconfirmedTransaction : unconfirmedTransactions) {
                    unconfirmedTransaction.getTransaction().undoUnconfirmed();
                    if (removed.size() < maxUnconfirmedTransactions) {
                        removed.add(unconfirmedTransaction.getTransaction());
                    }
                    waitingTransactions.add(unconfirmedTransaction);
                }
            }
            unconfirmedTransactionTable.truncate();
            unconfirmedDuplicates.clear();
            transactionCache.clear();
            transactionListeners.notify(removed, Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
        } finally {
            blockchain.writeUnlock();
        }
    }

    @Override
    public void rebroadcastAllUnconfirmedTransactions() {
        blockchain.writeLock();
        try {
            try (DbIterator<UnconfirmedTransaction> oldNonBroadcastedTransactions = getAllUnconfirmedTransactions()) {
                for (UnconfirmedTransaction unconfirmedTransaction : oldNonBroadcastedTransactions) {
                    if (unconfirmedTransaction.getTransaction().isUnconfirmedDuplicate(unconfirmedDuplicates)) {
                        LOG.debug("Skipping duplicate unconfirmed transaction " + unconfirmedTransaction.getTransaction().getJSONObject().toString());
                    } else if (enableTransactionRebroadcasting) {
                        broadcastedTransactions.add(unconfirmedTransaction.getTransaction());
                    }
                }
            }
        } finally {
            blockchain.writeUnlock();
        }
    }

    public void removeUnconfirmedTransaction(Transaction transaction) {
        if (!Db.getDb().isInTransaction()) {
            try {
                Db.getDb().beginTransaction();
                removeUnconfirmedTransaction(transaction);
                Db.getDb().commitTransaction();
            } catch (Exception e) {
                LOG.error(e.toString(), e);
                Db.getDb().rollbackTransaction();
                throw e;
            } finally {
                Db.getDb().endTransaction();
            }
            return;
        }
        try (Connection con = Db.getDb().getConnection();
             PreparedStatement pstmt = con.prepareStatement("DELETE FROM unconfirmed_transaction WHERE id = ?")) {
            pstmt.setLong(1, transaction.getId());
            int deleted = pstmt.executeUpdate();
            if (deleted > 0) {
                ((TransactionImpl)transaction).undoUnconfirmed();
                transactionCache.remove(((TransactionImpl)transaction).getDbKey());
                transactionListeners.notify(Collections.singletonList(transaction), Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
            }
        } catch (SQLException e) {
            LOG.error(e.toString(), e);
            throw new RuntimeException(e.toString(), e);
        }
    }

    @Override
    public void processLater(Collection<Transaction> transactions) {
        long currentTime = ntpTime.getTime();
        blockchain.writeLock();
        try {
            for (Transaction transaction : transactions) {
                CDI.current().select(BlockDaoImpl.class).get().getTransactionCache().remove(transaction.getId());
                if (transactionDb.hasTransaction(transaction.getId())) {
                    continue;
                }
                ((TransactionImpl)transaction).unsetBlock();
                waitingTransactions.add(new UnconfirmedTransaction((TransactionImpl)transaction, Math.min(currentTime, Convert2.fromEpochTime(transaction.getTimestamp()))));
            }
        } finally {
            blockchain.writeUnlock();
        }
    }

    public void processWaitingTransactions() {
        blockchain.writeLock();
        try {
            if (waitingTransactions.size() > 0) {
                int currentTime = AplCore.getEpochTime();
                List<Transaction> addedUnconfirmedTransactions = new ArrayList<>();
                Iterator<UnconfirmedTransaction> iterator = waitingTransactions.iterator();
                while (iterator.hasNext()) {
                    UnconfirmedTransaction unconfirmedTransaction = iterator.next();
                    try {
                        unconfirmedTransaction.validate();
                        processTransaction(unconfirmedTransaction);
                        iterator.remove();
                        addedUnconfirmedTransactions.add(unconfirmedTransaction.getTransaction());
                    } catch (AplException.ExistingTransactionException e) {
                        iterator.remove();
                    } catch (AplException.NotCurrentlyValidException e) {
                        if (unconfirmedTransaction.getExpiration() < currentTime
                                || currentTime - Convert2.toEpochTime(unconfirmedTransaction.getArrivalTimestamp()) > 3600) {
                            iterator.remove();
                        }
                    } catch (AplException.ValidationException|RuntimeException e) {
                        iterator.remove();
                    }
                }
                if (addedUnconfirmedTransactions.size() > 0) {
                    transactionListeners.notify(addedUnconfirmedTransactions, Event.ADDED_UNCONFIRMED_TRANSACTIONS);
                }
            }
        } finally {
            blockchain.writeUnlock();
        }
    }

    private void processPeerTransactions(JSONArray transactionsData) throws AplException.NotValidException {
        if (blockchain.getHeight() <= blockchainConfig.getLastKnownBlock() && !testUnconfirmedTransactions) {
            return;
        }
        if (transactionsData == null || transactionsData.isEmpty()) {
            return;
        }
        long arrivalTimestamp = ntpTime.getTime();
        List<TransactionImpl> receivedTransactions = new ArrayList<>();
        List<TransactionImpl> sendToPeersTransactions = new ArrayList<>();
        List<TransactionImpl> addedUnconfirmedTransactions = new ArrayList<>();
        List<Exception> exceptions = new ArrayList<>();
        for (Object transactionData : transactionsData) {
            try {
                TransactionImpl transaction = TransactionImpl.parseTransaction((JSONObject) transactionData);
                receivedTransactions.add(transaction);
                if (getUnconfirmedTransaction(transaction.getDbKey()) != null || transactionDb.hasTransaction(transaction.getId())) {
                    continue;
                }
                transaction.validate();
                UnconfirmedTransaction unconfirmedTransaction = new UnconfirmedTransaction(transaction, arrivalTimestamp);
                processTransaction(unconfirmedTransaction);
                if (broadcastedTransactions.contains(transaction)) {
                    LOG.debug("Received back transaction " + transaction.getStringId()
                            + " that we broadcasted, will not forward again to peers");
                } else {
                    sendToPeersTransactions.add(transaction);
                }
                addedUnconfirmedTransactions.add(transaction);

            } catch (AplException.NotCurrentlyValidException ignore) {
            } catch (AplException.ValidationException|RuntimeException e) {
                LOG.debug(String.format("Invalid transaction from peer: %s", ((JSONObject) transactionData).toJSONString()), e);
                exceptions.add(e);
            }
        }
        if (sendToPeersTransactions.size() > 0) {
            Peers.sendToSomePeers(sendToPeersTransactions);
        }
        if (addedUnconfirmedTransactions.size() > 0) {
            transactionListeners.notify(addedUnconfirmedTransactions, Event.ADDED_UNCONFIRMED_TRANSACTIONS);
        }
        broadcastedTransactions.removeAll(receivedTransactions);
        if (!exceptions.isEmpty()) {
            throw new AplException.NotValidException("Peer sends invalid transactions: " + exceptions.toString());
        }
    }

    private void processTransaction(UnconfirmedTransaction unconfirmedTransaction) throws AplException.ValidationException {
        TransactionImpl transaction = unconfirmedTransaction.getTransaction();
        int curTime = AplCore.getEpochTime();
        if (transaction.getTimestamp() > curTime + Constants.MAX_TIMEDRIFT || transaction.getExpiration() < curTime) {
            throw new AplException.NotCurrentlyValidException("Invalid transaction timestamp");
        }
        if (transaction.getVersion() < 1) {
            throw new AplException.NotValidException("Invalid transaction version");
        }
        if (transaction.getId() == 0L) {
            throw new AplException.NotValidException("Invalid transaction id 0");
        }

        blockchain.writeLock();
        try {
            try {
                Db.getDb().beginTransaction();
                if (blockchain.getHeight() < blockchainConfig.getLastKnownBlock() && !testUnconfirmedTransactions) {
                    throw new AplException.NotCurrentlyValidException("Blockchain not ready to accept transactions");
                }

                if (getUnconfirmedTransaction(transaction.getDbKey()) != null || transactionDb.hasTransaction(transaction.getId())) {
                    throw new AplException.ExistingTransactionException("Transaction already processed");
                }

                if (! transaction.verifySignature()) {
                    if (Account.getAccount(transaction.getSenderId()) != null) {
                        throw new AplException.NotValidException("Transaction signature verification failed");
                    } else {
                        throw new AplException.NotCurrentlyValidException("Unknown transaction sender");
                    }
                }

                if (! transaction.applyUnconfirmed()) {
                    throw new AplException.InsufficientBalanceException("Insufficient balance");
                }

                if (transaction.isUnconfirmedDuplicate(unconfirmedDuplicates)) {
                    throw new AplException.NotCurrentlyValidException("Duplicate unconfirmed transaction");
                }

                unconfirmedTransactionTable.insert(unconfirmedTransaction);

                Db.getDb().commitTransaction();
            } catch (Exception e) {
                Db.getDb().rollbackTransaction();
                throw e;
            } finally {
                Db.getDb().endTransaction();
            }
        } finally {
            blockchain.writeUnlock();
        }
    }

    private static final Comparator<UnconfirmedTransaction> cachedUnconfirmedTransactionComparator =
                    comparingInt(UnconfirmedTransaction::getHeight) // Sort by transaction_height ASC
                    .thenComparing(comparingLong(UnconfirmedTransaction::getFeePerByte).reversed()) // Sort by fee_per_byte DESC
                    .thenComparingLong(UnconfirmedTransaction::getArrivalTimestamp) // Sort by arrival_timestamp ASC
                    .thenComparingLong(UnconfirmedTransaction::getId); // Sort by transaction ID ASC

    /**
     * Get the cached unconfirmed transactions
     *
     * @param   exclude                 List of transaction identifiers to exclude
     */
    @Override
    public SortedSet<? extends Transaction> getCachedUnconfirmedTransactions(List<String> exclude) {
        SortedSet<UnconfirmedTransaction> transactionSet = new TreeSet<>(cachedUnconfirmedTransactionComparator);
        blockchain.readLock();
        try {
            //
            // Initialize the unconfirmed transaction cache if it hasn't been done yet
            //
            synchronized(transactionCache) {
                if (!cacheInitialized) {
                    DbIterator<UnconfirmedTransaction> it = getAllUnconfirmedTransactions();
                    while (it.hasNext()) {
                        UnconfirmedTransaction unconfirmedTransaction = it.next();
                        transactionCache.put(unconfirmedTransaction.getDbKey(), unconfirmedTransaction);
                    }
                    cacheInitialized = true;
                }
            }
            //
            // Build the result set
            //
            transactionCache.values().forEach(transaction -> {
                if (Collections.binarySearch(exclude, transaction.getStringId()) < 0) {
                    transactionSet.add(transaction);
                }
            });
        } finally {
            blockchain.readUnlock();
        }
        return transactionSet;
    }

    /**
     * Restore expired prunable data
     *
     * @param   transactions                        Transactions containing prunable data
     * @return                                      Processed transactions
     * @throws  AplException.NotValidException    Transaction is not valid
     */
    @Override
    public List<Transaction> restorePrunableData(JSONArray transactions) throws AplException.NotValidException {
        List<Transaction> processed = new ArrayList<>();
        blockchain.readLock();
        try {
            Db.getDb().beginTransaction();
            try {
                //
                // Check each transaction returned by the archive peer
                //
                for (Object transactionJSON : transactions) {
                    Transaction transaction = TransactionImpl.parseTransaction((JSONObject)transactionJSON);
                    Transaction myTransaction = transactionDb.findTransactionByFullHash(transaction.getFullHash());
                    if (myTransaction != null) {
                        boolean foundAllData = true;
                        //
                        // Process each prunable appendage
                        //
                        appendageLoop: for (Appendix appendage : transaction.getAppendages()) {
                            if ((appendage instanceof Prunable)) {
                                //
                                // Don't load the prunable data if we already have the data
                                //
                                for (Appendix myAppendage : myTransaction.getAppendages()) {
                                    if (myAppendage.getClass() == appendage.getClass()) {
                                        ((AbstractAppendix)myAppendage).loadPrunable(myTransaction, true);
                                        if (((Prunable)myAppendage).hasPrunableData()) {
                                            LOG.debug(String.format("Already have prunable data for transaction %s %s appendage",
                                                    myTransaction.getStringId(), ((AbstractAppendix)myAppendage).getAppendixName()));
                                            continue appendageLoop;
                                        }
                                        break;
                                    }
                                }
                                //
                                // Load the prunable data
                                //
                                if (((Prunable)appendage).hasPrunableData()) {
                                    LOG.debug(String.format("Loading prunable data for transaction %s %s appendage",
                                            Long.toUnsignedString(transaction.getId()), ((AbstractAppendix)appendage).getAppendixName()));
                                    ((Prunable)appendage).restorePrunableData(transaction, myTransaction.getBlockTimestamp(), myTransaction.getHeight());
                                } else {
                                    foundAllData = false;
                                }
                            }
                        }
                        if (foundAllData) {
                            processed.add(myTransaction);
                        }
                        Db.getDb().clearCache();
                        Db.getDb().commitTransaction();
                    }
                }
                Db.getDb().commitTransaction();
            } catch (Exception e) {
                Db.getDb().rollbackTransaction();
                processed.clear();
                throw e;
            } finally {
                Db.getDb().endTransaction();
            }
        } finally {
            blockchain.readUnlock();
        }
        return processed;
    }
}
