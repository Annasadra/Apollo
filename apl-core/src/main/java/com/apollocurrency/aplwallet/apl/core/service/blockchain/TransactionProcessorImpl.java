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

package com.apollocurrency.aplwallet.apl.core.service.blockchain;

import com.apollocurrency.aplwallet.apl.core.app.AplException;
import com.apollocurrency.aplwallet.apl.core.app.observer.events.BlockEvent;
import com.apollocurrency.aplwallet.apl.core.app.observer.events.BlockEventType;
import com.apollocurrency.aplwallet.apl.core.app.observer.events.TxEventType;
import com.apollocurrency.aplwallet.apl.core.chainid.BlockchainConfig;
import com.apollocurrency.aplwallet.apl.core.dao.TransactionalDataSource;
import com.apollocurrency.aplwallet.apl.core.dao.appdata.cdi.Transactional;
import com.apollocurrency.aplwallet.apl.core.db.TransactionHelper;
import com.apollocurrency.aplwallet.apl.core.entity.blockchain.Block;
import com.apollocurrency.aplwallet.apl.core.entity.blockchain.Transaction;
import com.apollocurrency.aplwallet.apl.core.entity.blockchain.TransactionImpl;
import com.apollocurrency.aplwallet.apl.core.entity.blockchain.UnconfirmedTransaction;
import com.apollocurrency.aplwallet.apl.core.http.JSONData;
import com.apollocurrency.aplwallet.apl.core.peer.PeersService;
import com.apollocurrency.aplwallet.apl.core.service.appdata.DatabaseManager;
import com.apollocurrency.aplwallet.apl.core.service.appdata.TimeService;
import com.apollocurrency.aplwallet.apl.core.transaction.TransactionBuilder;
import com.apollocurrency.aplwallet.apl.core.transaction.TransactionValidator;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.Appendix;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.Prunable;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.PrunableLoadingService;
import com.apollocurrency.aplwallet.apl.core.utils.CollectionUtil;
import com.apollocurrency.aplwallet.apl.core.utils.Convert2;
import lombok.extern.slf4j.Slf4j;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import javax.enterprise.event.Event;
import javax.enterprise.event.ObservesAsync;
import javax.enterprise.inject.spi.CDI;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class TransactionProcessorImpl implements TransactionProcessor {



    private final TransactionValidator transactionValidator;
    private final TransactionBuilder transactionBuilder;
    private final PrunableLoadingService prunableService;
    private final BlockchainConfig blockchainConfig;
    private final Blockchain blockchain;
    private BlockchainProcessor blockchainProcessor;
    private final TimeService timeService;
    private final GlobalSync globalSync;
    private final javax.enterprise.event.Event<List<Transaction>> txsEvent;
    private final PeersService peers;
    private final MemPool memPool;
    private final DatabaseManager databaseManager;
    private final UnconfirmedTransactionProcessingService processingService;

    @Inject
    public TransactionProcessorImpl(TransactionValidator validator,
                                    Event<List<Transaction>> txEvent,
                                    DatabaseManager databaseManager,
                                    GlobalSync globalSync,
                                    TimeService timeService,
                                    BlockchainConfig blockchainConfig,
                                    PeersService peers,
                                    Blockchain blockchain, TransactionBuilder transactionBuilder,
                                    PrunableLoadingService prunableService, UnconfirmedTransactionProcessingService processingService,
                                    MemPool memPool) {
        this.transactionValidator = validator;
        this.txsEvent = Objects.requireNonNull(txEvent);
        this.databaseManager = databaseManager;
        this.globalSync = globalSync;
        this.timeService = Objects.requireNonNull(timeService);
        this.blockchainConfig = blockchainConfig;
        this.peers = Objects.requireNonNull(peers);
        this.blockchain = Objects.requireNonNull(blockchain);
        this.transactionBuilder = transactionBuilder;
        this.prunableService = prunableService;
        this.processingService = processingService;
        this.memPool = memPool;
    }

    private BlockchainProcessor lookupBlockchainProcessor() {
        if (blockchainProcessor == null) {
            blockchainProcessor = CDI.current().select(BlockchainProcessorImpl.class).get();
        }
        return blockchainProcessor;
    }

    @Override
    public void broadcastWhenConfirmed(Transaction transaction, Transaction uncTransaction) {
        memPool.broadcastWhenConfirmed(transaction, uncTransaction);
    }

    @Override
    public void printMemPoolStat() {
        log.trace("Txs: {}, pending broadcast - {}, cache size - {}", memPool.allProcessedCount(), memPool.pendingBroadcastQueueSize(), memPool.currentCacheSize());
    }

    @Override
    public void broadcast(Transaction transaction) throws AplException.ValidationException {
        if (blockchain.hasTransaction(transaction.getId())) {
//                log.info("Transaction {} already in blockchain, will not broadcast again", transaction.getStringId());
            return;
        }
        if (memPool.hasUnconfirmedTransaction(transaction.getId())) {
            memPool.rebroadcast(transaction);
            return;
        }
        transactionValidator.validateSignatureWithTxFee(transaction);
        transactionValidator.validateLightly(transaction);
        UnconfirmedTransaction unconfirmedTransaction = new UnconfirmedTransaction(transaction, timeService.systemTimeMillis());
        boolean broadcastLater = lookupBlockchainProcessor().isProcessingBlock();
        if (broadcastLater) {
            memPool.broadcastLater(transaction);
//                log.debug("Will broadcast new transaction later {}", transaction.getStringId());
        } else {
            UnconfirmedTxValidationResult validationResult = processingService.validateBeforeProcessing(unconfirmedTransaction);
            if (!validationResult.isOk()) {
                throw new AplException.NotValidException(validationResult.getErrorDescription());
            }
            if (!processingService.addNewUnconfirmedTransaction(unconfirmedTransaction)) {
                throw new RuntimeException("Unable to broadcast tx " + unconfirmedTransaction.getId() + ", mempool is full");
            }
            //                log.debug("Accepted new transaction {}", transaction.getStringId());
            List<Transaction> acceptedTransactions = Collections.singletonList(unconfirmedTransaction);
            peers.sendToSomePeers(acceptedTransactions);
            txsEvent.select(TxEventType.literal(TxEventType.ADDED_UNCONFIRMED_TRANSACTIONS)).fire(acceptedTransactions);
            memPool.rebroadcast(transaction);
        }
    }

    @Override
    public void broadcast(Collection<Transaction> transactions) {
        List<Transaction> returned = new ArrayList<>();
        List<UnconfirmedTransaction> toBroadcast = transactions.stream()
            .filter(this::requireBroadcast)
            .map(e -> new UnconfirmedTransaction(e, timeService.systemTimeMillis()))
            .collect(Collectors.toList());
        List<UnconfirmedTransaction> processed = new ArrayList<>();
        TransactionHelper.executeInTransaction(databaseManager.getDataSource(), () -> {
            for (UnconfirmedTransaction tx : toBroadcast) {
                try {
                    if (processingService.validateBeforeProcessing(tx).isOk()) {
                        if (!processingService.addNewUnconfirmedTransaction(tx)) {
                            log.debug("Limit of mempool is reached, will return to pending queue tx {}", tx.getId());
                            returned.add(tx);
                        } else {
                            processed.add(tx);
                        }
                    }
                } catch (TransactionHelper.DbTransactionExecutionException validationException) {
                    log.trace("Not valid tx " + tx.getId(), validationException);
                }
            }
        });
        peers.sendToSomePeers(processed);
        List<Transaction> processedTxs = processed.stream().map(UnconfirmedTransaction::getTransaction).collect(Collectors.toList());
        processedTxs.forEach(memPool::rebroadcast);
        txsEvent.select(TxEventType.literal(TxEventType.ADDED_UNCONFIRMED_TRANSACTIONS)).fire(processedTxs);
        returned.forEach(e -> {
            try {
                memPool.softBroadcast(e);
            } catch (AplException.ValidationException ignored) {}
        });
    }

    private boolean requireBroadcast(Transaction tx) {
        if (blockchain.hasTransaction(tx.getId())) {
            log.info("Transaction {} already in blockchain, will not broadcast again", tx.getStringId());
            return false;
        }
        if (memPool.hasUnconfirmedTransaction(tx.getId())) {
            memPool.rebroadcast(tx);
            return false;
        }
        try {
            transactionValidator.validateSignatureWithTxFee(tx);
            transactionValidator.validateLightly(tx);
        } catch (AplException.ValidationException e) {
            log.trace("Tx " + tx.getId() + " is not valid before broadcast", e);
            return false;
        }
        return true;
    }

    @Override
    public void clearUnconfirmedTransactions() {
        TransactionalDataSource dataSource = databaseManager.getDataSource();
        List<UnconfirmedTransaction> unconfirmedTransactions = TransactionHelper.executeInTransaction(dataSource, () -> {
            List<UnconfirmedTransaction> txs = new ArrayList<>();
            memPool.getAllProcessedStream().forEach(txs::add);
            memPool.clear();
            log.trace("Unc txs cleared");
            return txs;
        });
        List<Transaction> removedTxs = unconfirmedTransactions.stream().map(UnconfirmedTransaction::getTransaction).collect(Collectors.toList());
        txsEvent.select(TxEventType.literal(TxEventType.REMOVED_UNCONFIRMED_TRANSACTIONS)).fire(removedTxs);
    }

    @Override
    public void rebroadcastAllUnconfirmedTransactions() {
        memPool.rebroadcastAllUnconfirmedTransactions();
    }

    public void removeUnconfirmedTransaction(Transaction transaction) {
        TransactionalDataSource dataSource = databaseManager.getDataSource();
        TransactionHelper.executeInTransaction(dataSource, ()-> {
            boolean removed = memPool.removeProcessedTransaction(transaction.getId());
            if (removed) {
//                log.trace("Removing unc tx {}", transaction.getId());
//                processingService.undoProcessedTransaction(transaction);
                txsEvent.select(TxEventType.literal(TxEventType.REMOVED_UNCONFIRMED_TRANSACTIONS)).fire(Collections.singletonList(transaction));
            }
        });
    }

    @Override
    public void processDelayedTxs(int number) {
        Iterator<UnconfirmedTransaction> it = memPool.processLaterQueueIterator();
        TransactionHelper.executeInTransaction(databaseManager.getDataSource(), ()-> {
            int processed = 0;
            while (it.hasNext() && processed < number) {
                UnconfirmedTransaction txToProcess = it.next();
                try {
                    Transaction tx = txToProcess.getTransaction();
                    if (requireBroadcast(tx)) {
                        if (processingService.validateBeforeProcessing(tx).isOk()) {
                            if (!processingService.addNewUnconfirmedTransaction(txToProcess)) {
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.info("Error processing unconfirmed transaction: " + txToProcess.getId(), e);
                }
                it.remove();
                processed++;
            }
        });
    }

    public void onBlockPushed(@ObservesAsync @BlockEvent(BlockEventType.BLOCK_PUSHED) Block block) {
        block.getTransactions().forEach(this::removeUnconfirmedTransaction);
    }

    @Override
    public void processLater(Collection<Transaction> transactions) {
        long currentTime = timeService.systemTimeMillis();
        for (Transaction transaction : transactions) {
//                blockchain.getTransactionCache().remove(transaction.getId());
            if (blockchain.hasTransaction(transaction.getId())) {
                continue;
            }
//                log.trace("Process later tx {}", transaction.getId());
            transaction.unsetBlock();
            memPool.processLater(new UnconfirmedTransaction(
                transaction, Math.min(currentTime, Convert2.fromEpochTime(transaction.getTimestamp())))
            );
        }
    }

//    public void processWaitingTransactions() {
//        globalSync.writeLock();
//        try {
//            if (memPool.getWaitingTransactionsQueueSize() > 0) {
//                int currentTime = timeService.getEpochTime();
//                List<Transaction> addedUnconfirmedTransactions = new ArrayList<>();
//                Iterator<UnconfirmedTransaction> iterator =
//                    memPool.getWaitingTransactionsQueueIterator();
//                while (iterator.hasNext()) {
//                    UnconfirmedTransaction unconfirmedTransaction = iterator.next();
//                    try {
////                        log.trace("Process waiting tx {}", unconfirmedTransaction.getId());
//                        transactionValidator.validate(unconfirmedTransaction);
//                        UnconfirmedTxValidationResult validationResult = processingService.validateBeforeProcessing(unconfirmedTransaction);
//                        if (!validationResult.isOk()) {
//                            processValidationResult(validationResult, unconfirmedTransaction, iterator);
//                        } else {
//                            processingService.processTransaction(unconfirmedTransaction);
//                            iterator.remove();
//                            addedUnconfirmedTransactions.add(unconfirmedTransaction.getTransaction());
//                        }
//                    } catch (AplException.ExistingTransactionException e) {
//                        iterator.remove();
////                        log.trace("Tx processing error " + unconfirmedTransaction.getId(), e);
//                    } catch (AplException.NotCurrentlyValidException e) {
//                            processNotCurrentlyValidTx(unconfirmedTransaction, currentTime, iterator);
////                        log.trace("Tx is not valid currently " + unconfirmedTransaction.getId(), e);
//                    } catch (AplException.ValidationException | RuntimeException e) {
//                        if (e instanceof TransactionHelper.DbTransactionExecutionException && e.getCause() instanceof AplException.NotCurrentlyValidException) {
//                            processNotCurrentlyValidTx(unconfirmedTransaction, currentTime, iterator);
//                        } else {
//                            iterator.remove();
//                        }
////                        log.trace("Validation tx processing error " + unconfirmedTransaction.getId(), e);
//                    }
//                }
//                if (addedUnconfirmedTransactions.size() > 0) {
//                    txsEvent.select(TxEventType.literal(TxEventType.ADDED_UNCONFIRMED_TRANSACTIONS)).fire(addedUnconfirmedTransactions);
//                }
//            }
//        } finally {
//            globalSync.writeUnlock();
//        }
//    }
//
//    private void processNotCurrentlyValidTx(UnconfirmedTransaction unconfirmedTransaction, int currentTime, Iterator<UnconfirmedTransaction> iterator) {
//        if (unconfirmedTransaction.getExpiration() < currentTime
//            || currentTime - Convert2.toEpochTime(unconfirmedTransaction.getArrivalTimestamp()) > 3600) {
//            iterator.remove();
//        }
//    }
//
//    private void processValidationResult(UnconfirmedTxValidationResult result, UnconfirmedTransaction tx, Iterator<UnconfirmedTransaction> iterator) {
//        long currentTime = timeService.getEpochTime();
//        if (result.getError() == UnconfirmedTxValidationResult.Error.ALREADY_PROCESSED || result.getError() == UnconfirmedTxValidationResult.Error.NOT_VALID) {
//            iterator.remove();
//        }
//        if (result.getError() == UnconfirmedTxValidationResult.Error.NOT_CURRENTLY_VALID) {
//            if (tx.getExpiration() < currentTime || currentTime - Convert2.toEpochTime(tx.getArrivalTimestamp()) > 3600) {
//                iterator.remove();
//            }
//        }
//    }

    @Override
    public int getUnconfirmedTxCount() {
        return memPool.allProcessedCount();
    }

    public void processPeerTransactions(List<Transaction> transactions) throws AplException.NotValidException {
        if (blockchain.getHeight() <= blockchainConfig.getLastKnownBlock()) {
            return;
        }
        if (CollectionUtil.isEmpty(transactions)) {
            return;
        }
        long arrivalTimestamp = timeService.systemTimeMillis();
        List<Transaction> receivedTransactions = new ArrayList<>();
        List<Transaction> sendToPeersTransactions = new ArrayList<>();
        List<Transaction> addedUnconfirmedTransactions = new ArrayList<>();
        List<Exception> exceptions = new ArrayList<>();
        TransactionHelper.executeInTransaction(databaseManager.getDataSource(), ()-> {
        for (Transaction transaction : transactions) {
            try {
                receivedTransactions.add(transaction);
                UnconfirmedTransaction unconfirmedTransaction = new UnconfirmedTransaction(transaction, arrivalTimestamp);
                UnconfirmedTxValidationResult validationResult = processingService.validateBeforeProcessing(unconfirmedTransaction);
                if (validationResult.isOk()) {
                    transactionValidator.validateSignatureWithTxFee(transaction);
                    transactionValidator.validateLightly(transaction);
                    if (!processingService.addNewUnconfirmedTransaction(unconfirmedTransaction)) {
                        break;
                    }
                    if (memPool.isAlreadyBroadcasted(transaction)) {
//                    log.debug("Received back transaction " + transaction.getStringId()
//                        + " that we broadcasted, will not forward again to peers");
                    } else {
                        sendToPeersTransactions.add(unconfirmedTransaction);
                    }
                    addedUnconfirmedTransactions.add(transaction);
                } else if (validationResult.getError() == UnconfirmedTxValidationResult.Error.NOT_VALID) {
                    exceptions.add(new AplException.NotValidException(validationResult.getErrorDescription()));
                }
            } catch (AplException.NotCurrentlyValidException ignore) {
            } catch (AplException.ValidationException | RuntimeException e) {
                log.warn(String.format("Invalid transaction from peer: %s", JSONData.unconfirmedTransaction(transaction)), e);
                exceptions.add(e);
            }
        }
        });
        if (!sendToPeersTransactions.isEmpty()) {
            peers.sendToSomePeers(sendToPeersTransactions);
        }
        if (!addedUnconfirmedTransactions.isEmpty()) {
            txsEvent.select(TxEventType.literal(TxEventType.ADDED_UNCONFIRMED_TRANSACTIONS)).fire(addedUnconfirmedTransactions);
        }
        memPool.removeFromBroadcasted(receivedTransactions);
        if (!exceptions.isEmpty()) {
            throw new AplException.NotValidException("Peer sends invalid transactions: " + exceptions.toString());
        }
    }


    @Override
    public boolean isFullyValidTransaction(Transaction tx) {
        UnconfirmedTxValidationResult validationResult = processingService.validateBeforeProcessing(tx);
        boolean isValid = validationResult.isOk();
        if (isValid) {
            try {
                transactionValidator.validateSignatureWithTxFee(tx);
                transactionValidator.validateFully(tx);
                isValid = true;
            } catch (AplException.ValidationException e) {
                isValid = false;
                log.trace("Tx {} is not valid", tx.getId());
            }
        }
        return isValid;
    }

    /**
     * Restore expired prunable data
     *
     * @param transactions Transactions containing prunable data
     * @return Processed transactions
     * @throws AplException.NotValidException Transaction is not valid
     */
    @Transactional(readOnly = true)
    @Override
    public List<Transaction> restorePrunableData(JSONArray transactions) throws AplException.NotValidException {
        List<Transaction> processed = new ArrayList<>();
        TransactionalDataSource dataSource = databaseManager.getDataSource();
        globalSync.readLock();
        try {
            dataSource.begin();
            try {
                //
                // Check each transaction returned by the archive peer
                //
                for (Object transactionJSON : transactions) {
                    Transaction transaction = parseTransaction((JSONObject) transactionJSON);
                    Transaction myTransaction = blockchain.findTransactionByFullHash(transaction.getFullHash());
                    if (myTransaction != null) {
                        boolean foundAllData = true;
                        //
                        // Process each prunable appendage
                        //
                        appendageLoop:
                        for (Appendix appendage : transaction.getAppendages()) {
                            if ((appendage instanceof Prunable)) {
                                //
                                // Don't load the prunable data if we already have the data
                                //
                                for (Appendix myAppendage : myTransaction.getAppendages()) {
                                    if (myAppendage.getClass() == appendage.getClass()) {
                                        prunableService.loadPrunable(myTransaction, myAppendage, true);
                                        if (((Prunable) myAppendage).hasPrunableData()) {
                                            if (log.isDebugEnabled()) {
                                                log.debug(String.format("Already have prunable data for transaction %s %s appendage",
                                                    myTransaction.getStringId(), myAppendage.getAppendixName()));
                                            }
                                            continue appendageLoop;
                                        }
                                        break;
                                    }
                                }
                                //
                                // Load the prunable data
                                //
                                if (((Prunable) appendage).hasPrunableData()) {
                                    if (log.isDebugEnabled()) {
                                        log.debug("Loading prunable data for transaction {} {} appendage",
                                            Long.toUnsignedString(transaction.getId()), appendage.getAppendixName());
                                    }
                                    prunableService.restorePrunable(transaction, appendage, myTransaction.getBlockTimestamp(), myTransaction.getHeight());
                                } else {
                                    foundAllData = false;
                                }
                            }
                        }
                        if (foundAllData) {
                            processed.add(myTransaction);
                        }
                        dataSource.commit(false);
                    }
                }
                dataSource.commit();
            } catch (Exception e) {
                dataSource.rollback();
                processed.clear();
                throw e;
            }
        } finally {
            globalSync.readUnlock();
        }
        return processed;
    }



    public TransactionImpl parseTransaction(JSONObject transactionData) throws AplException.NotValidException {
        TransactionImpl transaction = transactionBuilder.newTransactionBuilder(transactionData).build();
        if (!transactionValidator.checkSignature(transaction)) {
            throw new AplException.NotValidException("Invalid transaction signature for transaction " + transaction.getId());
        }
        return transaction;
    }
}
