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

import com.apollocurrency.aplwallet.apl.core.account.LedgerEvent;
import com.apollocurrency.aplwallet.apl.core.account.model.Account;
import com.apollocurrency.aplwallet.apl.core.account.service.AccountPublicKeyService;
import com.apollocurrency.aplwallet.apl.core.account.service.AccountPublicKeyServiceImpl;
import com.apollocurrency.aplwallet.apl.core.account.service.AccountService;
import com.apollocurrency.aplwallet.apl.core.account.service.AccountServiceImpl;
import com.apollocurrency.aplwallet.apl.core.app.observer.events.BlockEvent;
import com.apollocurrency.aplwallet.apl.core.app.observer.events.BlockEventType;
import com.apollocurrency.aplwallet.apl.core.chainid.BlockchainConfig;
import com.apollocurrency.aplwallet.apl.core.db.DatabaseManager;
import com.apollocurrency.aplwallet.apl.core.db.DbClause;
import com.apollocurrency.aplwallet.apl.core.db.DbIterator;
import com.apollocurrency.aplwallet.apl.core.db.DbKey;
import com.apollocurrency.aplwallet.apl.core.db.DbUtils;
import com.apollocurrency.aplwallet.apl.core.db.LongKeyFactory;
import com.apollocurrency.aplwallet.apl.core.db.TransactionalDataSource;
import com.apollocurrency.aplwallet.apl.core.db.dao.ShardDao;
import com.apollocurrency.aplwallet.apl.core.db.derived.VersionedDeletableEntityDbTable;
import com.apollocurrency.aplwallet.apl.core.monetary.HoldingType;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.ShufflingAttachment;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.ShufflingCancellationAttachment;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.ShufflingCreation;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.ShufflingProcessingAttachment;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.ShufflingRecipientsAttachment;
import com.apollocurrency.aplwallet.apl.crypto.AnonymouslyEncryptedData;
import com.apollocurrency.aplwallet.apl.crypto.Convert;
import com.apollocurrency.aplwallet.apl.crypto.Crypto;
import com.apollocurrency.aplwallet.apl.util.Constants;
import com.apollocurrency.aplwallet.apl.util.Listener;
import com.apollocurrency.aplwallet.apl.util.Listeners;
import com.apollocurrency.aplwallet.apl.util.ThreadUtils;
import com.apollocurrency.aplwallet.apl.util.annotation.DatabaseSpecificDml;
import com.apollocurrency.aplwallet.apl.util.annotation.DmlMarker;
import com.apollocurrency.aplwallet.apl.util.injectable.PropertiesHolder;
import org.slf4j.Logger;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.CDI;
import javax.inject.Singleton;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.slf4j.LoggerFactory.getLogger;

public final class Shuffling {
    /**
     * Cache, which contains all active shufflings required for processing on each block push
     * Purpose: getActiveShufflings db call require at least 50-100ms, with cache we can shorten time to 10-20ms
     */
//    private static Map<Long, Shuffling> activeShufflingsCache = new HashMap<>();
    private static final Logger LOG = getLogger(Shuffling.class);
    private static final Listeners<Shuffling, Event> listeners = new Listeners<>();
    private static final LongKeyFactory<Shuffling> shufflingDbKeyFactory = new LongKeyFactory<Shuffling>("id") {

        @Override
        public DbKey newKey(Shuffling shuffling) {
            return shuffling.dbKey;
        }

    };
    // TODO: YL remove static instance later
    private static PropertiesHolder propertiesLoader = CDI.current().select(PropertiesHolder.class).get();
    private static final boolean deleteFinished = propertiesLoader.getBooleanProperty("apl.deleteFinishedShufflings");
    private static BlockchainConfig blockchainConfig = CDI.current().select(BlockchainConfig.class).get();
    private static Blockchain blockchain = CDI.current().select(Blockchain.class).get();
    private static final VersionedDeletableEntityDbTable<Shuffling> shufflingTable = new VersionedDeletableEntityDbTable<Shuffling>("shuffling", shufflingDbKeyFactory) {

        @Override
        public Shuffling load(Connection con, ResultSet rs, DbKey dbKey) throws SQLException {
            return new Shuffling(rs, dbKey);
        }

        @Override
        public void save(Connection con, Shuffling shuffling) throws SQLException {
            shuffling.save(con);
            LOG.trace("Save shuffling {} - height - {} remaining - {} Trace - {}",
                shuffling.getId(), shuffling.getHeight(), shuffling.getBlocksRemaining(), ThreadUtils.last3Stacktrace());
        }
    };
    private static ShardDao shardDao = CDI.current().select(ShardDao.class).get();
    private static GlobalSync globalSync = CDI.current().select(GlobalSync.class).get();
    private static DatabaseManager databaseManager;
    private static AccountService accountService;
    private static AccountPublicKeyService accountPublicKeyService;
    private final long id;
    private final DbKey dbKey;
    private final long holdingId;
    private final HoldingType holdingType;
    private final long issuerId;
    private final long amount;
    private final byte participantCount;
    private short blocksRemaining;
    private byte registrantCount;
    private int height;
    private Stage stage;
    private long assigneeAccountId;
    private byte[][] recipientPublicKeys;

    private Shuffling(Transaction transaction, ShufflingCreation attachment) {
        this.id = transaction.getId();
        this.dbKey = shufflingDbKeyFactory.newKey(this.id);
        this.holdingId = attachment.getHoldingId();
        this.holdingType = attachment.getHoldingType();
        this.issuerId = transaction.getSenderId();
        this.amount = attachment.getAmount();
        this.participantCount = attachment.getParticipantCount();
        this.blocksRemaining = attachment.getRegistrationPeriod();
        this.stage = Stage.REGISTRATION;
        this.assigneeAccountId = issuerId;
        this.recipientPublicKeys = Convert.EMPTY_BYTES;
        this.registrantCount = 1;
        this.height = transaction.getHeight();
    }

    private Shuffling(ResultSet rs, DbKey dbKey) throws SQLException {
        this.id = rs.getLong("id");
        this.dbKey = dbKey;
        this.holdingId = rs.getLong("holding_id");
        this.holdingType = HoldingType.get(rs.getByte("holding_type"));
        this.issuerId = rs.getLong("issuer_id");
        this.amount = rs.getLong("amount");
        this.participantCount = rs.getByte("participant_count");
        this.blocksRemaining = rs.getShort("blocks_remaining");
        this.stage = Stage.get(rs.getByte("stage"));
        this.assigneeAccountId = rs.getLong("assignee_account_id");
        this.recipientPublicKeys = DbUtils.getArray(rs, "recipient_public_keys", byte[][].class, Convert.EMPTY_BYTES);
        this.registrantCount = rs.getByte("registrant_count");
        this.height = rs.getInt("height");
    }

    private static TransactionalDataSource lookupDataSource() {
        if (databaseManager == null) {
            databaseManager = CDI.current().select(DatabaseManager.class).get();
        }
        return databaseManager.getDataSource();
    }

    public static boolean addListener(Listener<Shuffling> listener, Event eventType) {
        return listeners.addListener(listener, eventType);
    }

    public static boolean removeListener(Listener<Shuffling> listener, Event eventType) {
        return listeners.removeListener(listener, eventType);
    }

    public static int getCount() {
        return shufflingTable.getCount();
    }

    public static int getActiveCount() {
        return shufflingTable.getCount(new DbClause.NotNullClause("blocks_remaining"));
    }

/*
        public void onBlockPopOff(@Observes @BlockEvent(BlockEventType.BLOCK_POPPED) Block block) {
            activeShufflingsCache = null;
        }

        public void onRescanBegin(@Observes @BlockEvent(BlockEventType.RESCAN_BEGIN) Block block) {
            activeShufflingsCache = null;
        }
*/

    public static DbIterator<Shuffling> getAll(int from, int to) {
        return shufflingTable.getAll(from, to, " ORDER BY blocks_remaining NULLS LAST, height DESC ");
    }

    public static DbIterator<Shuffling> getActiveShufflings(int from, int to) {
        return shufflingTable.getManyBy(new DbClause.NotNullClause("blocks_remaining"), from, to, " ORDER BY blocks_remaining, height DESC ");
    }

    public static List<Shuffling> getActiveShufflings() {
        Connection con = null;
        try {
            con = lookupDataSource().getConnection();
            PreparedStatement psActiveShuffling = con.prepareStatement("SELECT * FROM shuffling WHERE blocks_remaining IS NOT NULL AND latest = TRUE ORDER BY blocks_remaining, height DESC");
            return CollectionUtil.toList(shufflingTable.getManyBy(con, psActiveShuffling, true));
        } catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }

    public static DbIterator<Shuffling> getFinishedShufflings(int from, int to) {
        return shufflingTable.getManyBy(new DbClause.NullClause("blocks_remaining"), from, to, " ORDER BY height DESC ");
    }

    public static Shuffling getShuffling(long shufflingId) {
        return shufflingTable.get(shufflingDbKeyFactory.newKey(shufflingId));
    }

    public static Shuffling getShuffling(byte[] fullHash) {
        long shufflingId = Convert.fullHashToId(fullHash);
        Shuffling shuffling = shufflingTable.get(shufflingDbKeyFactory.newKey(shufflingId));
        if (shuffling != null && !Arrays.equals(shuffling.getFullHash(), fullHash)) {
            LOG.debug("Shuffling with different hash {} but same id found for hash {}",
                Convert.toHexString(shuffling.getFullHash()), Convert.toHexString(fullHash));
            return null;
        }
        return shuffling;
    }

    public static int getHoldingShufflingCount(long holdingId, boolean includeFinished) {
        DbClause clause = holdingId != 0 ? new DbClause.LongClause("holding_id", holdingId) : new DbClause.NullClause("holding_id");
        if (!includeFinished) {
            clause = clause.and(new DbClause.NotNullClause("blocks_remaining"));
        }
        return shufflingTable.getCount(clause);
    }

    public static DbIterator<Shuffling> getHoldingShufflings(long holdingId, Stage stage, boolean includeFinished, int from, int to) {
        DbClause clause = holdingId != 0 ? new DbClause.LongClause("holding_id", holdingId) : new DbClause.NullClause("holding_id");
        if (!includeFinished) {
            clause = clause.and(new DbClause.NotNullClause("blocks_remaining"));
        }
        if (stage != null) {
            clause = clause.and(new DbClause.ByteClause("stage", stage.getCode()));
        }
        return shufflingTable.getManyBy(clause, from, to, " ORDER BY blocks_remaining NULLS LAST, height DESC ");
    }

    public static DbIterator<Shuffling> getAccountShufflings(long accountId, boolean includeFinished, int from, int to) {
        Connection con = null;
        try {
            con = lookupDataSource().getConnection();
            PreparedStatement pstmt = con.prepareStatement("SELECT shuffling.* FROM shuffling, shuffling_participant WHERE "
                + "shuffling_participant.account_id = ? AND shuffling.id = shuffling_participant.shuffling_id "
                + (includeFinished ? "" : "AND shuffling.blocks_remaining IS NOT NULL ")
                + "AND shuffling.latest = TRUE AND shuffling_participant.latest = TRUE ORDER BY blocks_remaining NULLS LAST, height DESC "
                + DbUtils.limitsClause(from, to));
            int i = 0;
            pstmt.setLong(++i, accountId);
            DbUtils.setLimits(++i, pstmt, from, to);
            return shufflingTable.getManyBy(con, pstmt, false);
        } catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }

    public static DbIterator<Shuffling> getAssignedShufflings(long assigneeAccountId, int from, int to) {
        return shufflingTable.getManyBy(new DbClause.LongClause("assignee_account_id", assigneeAccountId)
                .and(new DbClause.ByteClause("stage", Stage.PROCESSING.getCode())), from, to,
            " ORDER BY blocks_remaining NULLS LAST, height DESC ");
    }

    static void addShuffling(Transaction transaction, ShufflingCreation attachment) {
        Shuffling shuffling = new Shuffling(transaction, attachment);
        insert(shuffling);
        ShufflingParticipant.addParticipant(shuffling.getId(), transaction.getSenderId(), 0);
        listeners.notify(shuffling, Event.SHUFFLING_CREATED);
    }

    public static void init() {

    }

    private static void insert(Shuffling shuffling) {
        long id = shuffling.getId();
        shuffling.height = blockchain.getHeight();
/*
        if (activeShufflingsCache != null) {
            if (shuffling.getBlocksRemaining() <= 0) {
                if (activeShufflingsCache.get(id) != null) {
                    activeShufflingsCache.remove(id);
                }
            } else { // add new or replace
                activeShufflingsCache.put(id, shuffling);
            }
        }
*/
        shufflingTable.insert(shuffling);
    }

    private static byte[] getParticipantsHash(Iterable<ShufflingParticipant> participants) {
        MessageDigest digest = Crypto.sha256();
        participants.forEach(participant -> digest.update(Convert.toBytes(participant.getAccountId())));
        return digest.digest();
    }

    private void save(Connection con) throws SQLException {
        try (
            @DatabaseSpecificDml(DmlMarker.MERGE)
            @DatabaseSpecificDml(DmlMarker.SET_ARRAY)
            PreparedStatement pstmt = con.prepareStatement("MERGE INTO shuffling (id, holding_id, holding_type, "
                + "issuer_id, amount, participant_count, blocks_remaining, stage, assignee_account_id, "
                + "recipient_public_keys, registrant_count, height, latest, deleted) "
                + "KEY (id, height) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, FALSE)")
        ) {
            int i = 0;
            pstmt.setLong(++i, this.id);
            DbUtils.setLongZeroToNull(pstmt, ++i, this.holdingId);
            pstmt.setByte(++i, this.holdingType.getCode());
            pstmt.setLong(++i, this.issuerId);
            pstmt.setLong(++i, this.amount);
            pstmt.setByte(++i, this.participantCount);
            DbUtils.setShortZeroToNull(pstmt, ++i, this.blocksRemaining);
            pstmt.setByte(++i, this.getStage().getCode());
            DbUtils.setLongZeroToNull(pstmt, ++i, this.assigneeAccountId);
            DbUtils.setArrayEmptyToNull(pstmt, ++i, this.recipientPublicKeys);
            pstmt.setByte(++i, this.registrantCount);
            pstmt.setInt(++i, blockchain.getHeight());
            pstmt.executeUpdate();
        }
    }

    public int getHeight() {
        return height;
    }

    public long getId() {
        return id;
    }

    public long getHoldingId() {
        return holdingId;
    }

    public HoldingType getHoldingType() {
        return holdingType;
    }

    public long getIssuerId() {
        return issuerId;
    }

    public long getAmount() {
        return amount;
    }

    public byte getParticipantCount() {
        return participantCount;
    }

    public byte getRegistrantCount() {
        return registrantCount;
    }

    public short getBlocksRemaining() {
        return blocksRemaining;
    }

    public Stage getStage() {
        return stage;
    }

    // caller must update database
    private void setStage(Stage stage, long assigneeAccountId, short blocksRemaining) {
        if (!this.stage.canBecome(stage)) {
            throw new IllegalStateException(String.format("Shuffling in stage %s cannot go to stage %s", this.stage, stage));
        }
        if ((stage == Stage.VERIFICATION || stage == Stage.DONE) && assigneeAccountId != 0) {
            throw new IllegalArgumentException(String.format("Invalid assigneeAccountId %s for stage %s", Long.toUnsignedString(assigneeAccountId), stage));
        }
        if ((stage == Stage.REGISTRATION || stage == Stage.PROCESSING || stage == Stage.BLAME) && assigneeAccountId == 0) {
            throw new IllegalArgumentException(String.format("In stage %s assigneeAccountId cannot be 0", stage));
        }
        if ((stage == Stage.DONE || stage == Stage.CANCELLED) && blocksRemaining != 0) {
            throw new IllegalArgumentException(String.format("For stage %s remaining blocks cannot be %s", stage, blocksRemaining));
        }
        this.stage = stage;
        this.assigneeAccountId = assigneeAccountId;
        this.blocksRemaining = blocksRemaining;
        LOG.debug("Shuffling {} entered stage {}, assignee {}, remaining blocks {}",
            Long.toUnsignedString(id), this.stage, Long.toUnsignedString(this.assigneeAccountId), this.blocksRemaining);
    }

    /*
     * Meaning of assigneeAccountId in each shuffling stage:
     *  REGISTRATION: last currently registered participant
     *  PROCESSING: next participant in turn to submit processing data
     *  VERIFICATION: 0, not assigned to anyone
     *  BLAME: the participant who initiated the blame phase
     *  CANCELLED: the participant who got blamed for the shuffling failure, if any
     *  DONE: 0, not assigned to anyone
     */
    public long getAssigneeAccountId() {
        return assigneeAccountId;
    }

    public byte[][] getRecipientPublicKeys() {
        return recipientPublicKeys;
    }

    public ShufflingParticipant getParticipant(long accountId) {
        return ShufflingParticipant.getParticipant(id, accountId);
    }

    public ShufflingParticipant getLastParticipant() {
        return ShufflingParticipant.getLastParticipant(id);
    }

    public byte[] getStateHash() {
        return stage.getHash(this);
    }

    public byte[] getFullHash() {
        return blockchain.getFullHash(id);
    }

    public ShufflingAttachment process(final long accountId, final byte[] secretBytes, final byte[] recipientPublicKey) {
        byte[][] data = Convert.EMPTY_BYTES;
        byte[] shufflingStateHash = null;
        int participantIndex = 0;
        List<ShufflingParticipant> shufflingParticipants = new ArrayList<>();
        globalSync.readLock();
        // Read the participant list for the shuffling
        try (DbIterator<ShufflingParticipant> participants = ShufflingParticipant.getParticipants(id)) {
            for (ShufflingParticipant participant : participants) {
                shufflingParticipants.add(participant);
                if (participant.getNextAccountId() == accountId) {
                    data = participant.getData();
                    shufflingStateHash = participant.getDataTransactionFullHash();
                    participantIndex = shufflingParticipants.size();
                }
            }
            if (shufflingStateHash == null) {
                shufflingStateHash = getParticipantsHash(shufflingParticipants);
            }
        } finally {
            globalSync.readUnlock();
        }
        boolean isLast = participantIndex == participantCount - 1;
        // decrypt the tokens bundled in the current data
        List<byte[]> outputDataList = new ArrayList<>();
        for (byte[] bytes : data) {
            AnonymouslyEncryptedData encryptedData = AnonymouslyEncryptedData.readEncryptedData(bytes);
            try {
                byte[] decrypted = encryptedData.decrypt(secretBytes);
                outputDataList.add(decrypted);
            } catch (Exception e) {
                LOG.info("Decryption failed", e);
                return isLast ? new ShufflingRecipientsAttachment(this.id, Convert.EMPTY_BYTES, shufflingStateHash)
                    : new ShufflingProcessingAttachment(this.id, Convert.EMPTY_BYTES, shufflingStateHash);
            }
        }
        // Calculate the token for the current sender by iteratively encrypting it using the public key of all the participants
        // which did not perform shuffle processing yet
        byte[] bytesToEncrypt = recipientPublicKey;
        byte[] nonce = Convert.toBytes(this.id);
        for (int i = shufflingParticipants.size() - 1; i > participantIndex; i--) {
            ShufflingParticipant participant = shufflingParticipants.get(i);
            byte[] participantPublicKey = lookupAccountService().getPublicKeyByteArray(participant.getAccountId());
            AnonymouslyEncryptedData encryptedData = AnonymouslyEncryptedData.encrypt(bytesToEncrypt, secretBytes, participantPublicKey, nonce);
            bytesToEncrypt = encryptedData.getBytes();
        }
        outputDataList.add(bytesToEncrypt);
        // Shuffle the tokens and save the shuffled tokens as the participant data
        Collections.sort(outputDataList, Convert.byteArrayComparator);
        if (isLast) {
            Set<Long> recipientAccounts = new HashSet<>(participantCount);
            for (byte[] publicKey : outputDataList) {
                if (!Crypto.isCanonicalPublicKey(publicKey) || !recipientAccounts.add(AccountService.getId(publicKey))) {
                    // duplicate or invalid recipient public key
                    LOG.debug("Invalid recipient public key " + Convert.toHexString(publicKey));
                    return new ShufflingRecipientsAttachment(this.id, Convert.EMPTY_BYTES, shufflingStateHash);
                }
            }
            // last participant prepares ShufflingRecipients transaction instead of ShufflingProcessing
            return new ShufflingRecipientsAttachment(this.id, outputDataList.toArray(new byte[outputDataList.size()][]),
                shufflingStateHash);
        } else {
            byte[] previous = null;
            for (byte[] decrypted : outputDataList) {
                if (previous != null && Arrays.equals(decrypted, previous)) {
                    LOG.debug("Duplicate decrypted data");
                    return new ShufflingProcessingAttachment(this.id, Convert.EMPTY_BYTES, shufflingStateHash);
                }
                if (decrypted.length != 32 + 64 * (participantCount - participantIndex - 1)) {
                    LOG.debug("Invalid encrypted data length in process " + decrypted.length);
                    return new ShufflingProcessingAttachment(this.id, Convert.EMPTY_BYTES, shufflingStateHash);
                }
                previous = decrypted;
            }
            return new ShufflingProcessingAttachment(this.id, outputDataList.toArray(new byte[outputDataList.size()][]),
                shufflingStateHash);
        }
    }

    public ShufflingCancellationAttachment revealKeySeeds(final byte[] secretBytes, long cancellingAccountId, byte[] shufflingStateHash) {
        globalSync.readLock();
        try (DbIterator<ShufflingParticipant> participants = ShufflingParticipant.getParticipants(id)) {
            if (cancellingAccountId != this.assigneeAccountId) {
                throw new RuntimeException(String.format("Current shuffling cancellingAccountId %s does not match %s",
                    Long.toUnsignedString(this.assigneeAccountId), Long.toUnsignedString(cancellingAccountId)));
            }
            if (shufflingStateHash == null || !Arrays.equals(shufflingStateHash, getStateHash())) {
                throw new RuntimeException("Current shuffling state hash does not match");
            }
            long accountId = AccountService.getId(Crypto.getPublicKey(Crypto.getKeySeed(secretBytes)));
            byte[][] data = null;
            while (participants.hasNext()) {
                ShufflingParticipant participant = participants.next();
                if (participant.getAccountId() == accountId) {
                    data = participant.getData();
                    break;
                }
            }
            if (!participants.hasNext()) {
                throw new RuntimeException("Last participant cannot have keySeeds to reveal");
            }
            if (data == null) {
                throw new RuntimeException("Account " + Long.toUnsignedString(accountId) + " has not submitted data");
            }
            final byte[] nonce = Convert.toBytes(this.id);
            final List<byte[]> keySeeds = new ArrayList<>();
            byte[] nextParticipantPublicKey = lookupAccountService().getPublicKeyByteArray(participants.next().getAccountId());
            byte[] keySeed = Crypto.getKeySeed(secretBytes, nextParticipantPublicKey, nonce);
            keySeeds.add(keySeed);
            byte[] publicKey = Crypto.getPublicKey(keySeed);
            byte[] decryptedBytes = null;
            // find the data that we encrypted
            for (byte[] bytes : data) {
                AnonymouslyEncryptedData encryptedData = AnonymouslyEncryptedData.readEncryptedData(bytes);
                if (Arrays.equals(encryptedData.getPublicKey(), publicKey)) {
                    try {
                        decryptedBytes = encryptedData.decrypt(keySeed, nextParticipantPublicKey);
                        break;
                    } catch (Exception ignore) {
                    }
                }
            }
            if (decryptedBytes == null) {
                throw new RuntimeException("None of the encrypted data could be decrypted");
            }
            // decrypt all iteratively, adding the key seeds to the result
            while (participants.hasNext()) {
                nextParticipantPublicKey = accountService.getPublicKeyByteArray(participants.next().getAccountId());
                keySeed = Crypto.getKeySeed(secretBytes, nextParticipantPublicKey, nonce);
                keySeeds.add(keySeed);
                AnonymouslyEncryptedData encryptedData = AnonymouslyEncryptedData.readEncryptedData(decryptedBytes);
                decryptedBytes = encryptedData.decrypt(keySeed, nextParticipantPublicKey);
            }
            return new ShufflingCancellationAttachment(this.id, data, keySeeds.toArray(new byte[keySeeds.size()][]),
                shufflingStateHash, cancellingAccountId);
        } finally {
            globalSync.readUnlock();
        }
    }

    void addParticipant(long participantId) {
        // Update the shuffling assignee to point to the new participant and update the next pointer of the existing participant
        // to the new participant
        ShufflingParticipant lastParticipant = ShufflingParticipant.getParticipant(this.id, this.assigneeAccountId);
        lastParticipant.setNextAccountId(participantId);
        ShufflingParticipant.addParticipant(this.id, participantId, this.registrantCount);
        this.registrantCount += 1;
        // Check if participant registration is complete and if so update the shuffling
        if (this.registrantCount == this.participantCount) {
            setStage(Stage.PROCESSING, this.issuerId, blockchainConfig.getShufflingProcessingDeadline());
        } else {
            this.assigneeAccountId = participantId;
        }
        insert(this);
        if (stage == Stage.PROCESSING) {
            listeners.notify(this, Event.SHUFFLING_PROCESSING_ASSIGNED);
        }
        LOG.trace("Shuffling addParticipant {} entered stage {}, assignee {}, remaining blocks {}",
            Long.toUnsignedString(this.id), this.stage, Long.toUnsignedString(this.assigneeAccountId), this.blocksRemaining);

    }

    void updateParticipantData(Transaction transaction, ShufflingProcessingAttachment attachment) {
        long participantId = transaction.getSenderId();
        byte[][] data = attachment.getData();
        ShufflingParticipant participant = ShufflingParticipant.getParticipant(this.id, participantId);
        participant.setData(data, transaction.getTimestamp());
        participant.setProcessed(transaction.getFullHash(), attachment.getHash());
        if (data != null && data.length == 0) {
            // couldn't decrypt all data from previous participants
            cancelBy(participant);
            return;
        }
        this.assigneeAccountId = participant.getNextAccountId();
        this.blocksRemaining = blockchainConfig.getShufflingProcessingDeadline();
        insert(this);
        listeners.notify(this, Event.SHUFFLING_PROCESSING_ASSIGNED);
        LOG.trace("Shuffling updateParticipant {} entered stage {}, assignee {}, remaining blocks {}",
            Long.toUnsignedString(this.id), this.stage, Long.toUnsignedString(this.assigneeAccountId), this.blocksRemaining);
    }

    void updateRecipients(Transaction transaction, ShufflingRecipientsAttachment attachment) {
        long participantId = transaction.getSenderId();
        this.recipientPublicKeys = attachment.getRecipientPublicKeys();
        ShufflingParticipant participant = ShufflingParticipant.getParticipant(this.id, participantId);
        participant.setProcessed(transaction.getFullHash(), null);
        if (recipientPublicKeys.length == 0) {
            // couldn't decrypt all data from previous participants
            cancelBy(participant);
            return;
        }
        participant.verify();
        // last participant announces all valid recipient public keys
        lookupAccountService();
        for (byte[] recipientPublicKey : recipientPublicKeys) {
            long recipientId = AccountService.getId(recipientPublicKey);
            if (lookupAccountPublickKeyService().setOrVerifyPublicKey(recipientId, recipientPublicKey)) {
                Account account = accountService.addOrGetAccount(recipientId);
                lookupAccountPublickKeyService().apply(account, recipientPublicKey);
            }
        }
        setStage(Stage.VERIFICATION, 0, (short) (blockchainConfig.getShufflingProcessingDeadline() + participantCount));
        insert(this);
        listeners.notify(this, Event.SHUFFLING_PROCESSING_FINISHED);
        LOG.trace("Shuffling updateRecipient {} entered stage {}, assignee {}, remaining blocks {}",
            Long.toUnsignedString(this.id), this.stage, Long.toUnsignedString(this.assigneeAccountId), this.blocksRemaining);
    }

    void verify(long accountId) {
        ShufflingParticipant.getParticipant(id, accountId).verify();
        if (ShufflingParticipant.getVerifiedCount(id) == participantCount) {
            distribute();
        }
    }

    void cancelBy(ShufflingParticipant participant, byte[][] blameData, byte[][] keySeeds) {
        participant.cancel(blameData, keySeeds);
        boolean startingBlame = this.stage != Stage.BLAME;
        if (startingBlame) {
            setStage(Stage.BLAME, participant.getAccountId(), (short) (blockchainConfig.getShufflingProcessingDeadline() + participantCount));
        }
        insert(this);
        if (startingBlame) {
            listeners.notify(this, Event.SHUFFLING_BLAME_STARTED);
        }
        LOG.trace("Shuffling cancelBy {} entered stage {}, blamingAcc {}, remaining blocks {}",
            Long.toUnsignedString(this.id), this.stage, participant.getAccountId(), this.blocksRemaining);
    }

    private void cancelBy(ShufflingParticipant participant) {
        cancelBy(participant, Convert.EMPTY_BYTES, Convert.EMPTY_BYTES);
    }

    private void distribute() {
        if (recipientPublicKeys.length != participantCount) {
            cancelBy(getLastParticipant());
            return;
        }
        lookupAccountService();
        for (byte[] recipientPublicKey : recipientPublicKeys) {
            byte[] publicKey = accountService.getPublicKeyByteArray(AccountService.getId(recipientPublicKey));
            if (publicKey != null && !Arrays.equals(publicKey, recipientPublicKey)) {
                // distribution not possible, do a cancellation on behalf of last participant instead
                cancelBy(getLastParticipant());
                return;
            }
        }
        LedgerEvent event = LedgerEvent.SHUFFLING_DISTRIBUTION;
        try (DbIterator<ShufflingParticipant> participants = ShufflingParticipant.getParticipants(id)) {
            for (ShufflingParticipant participant : participants) {
                Account participantAccount = accountService.getAccount(participant.getAccountId());
                holdingType.addToBalance(participantAccount, event, this.id, this.holdingId, -amount);
                if (holdingType != HoldingType.APL) {
                    accountService.addToBalanceATM(participantAccount, event, this.id, -blockchainConfig.getShufflingDepositAtm());
                }
            }
        }
        for (byte[] recipientPublicKey : recipientPublicKeys) {
            long recipientId = AccountService.getId(recipientPublicKey);
            Account recipientAccount = accountService.addOrGetAccount(recipientId);
            lookupAccountPublickKeyService().apply(recipientAccount, recipientPublicKey);
            holdingType.addToBalanceAndUnconfirmedBalance(recipientAccount, event, this.id, this.holdingId, amount);
            if (holdingType != HoldingType.APL) {
                accountService.addToBalanceAndUnconfirmedBalanceATM(recipientAccount, event, this.id, blockchainConfig.getShufflingDepositAtm());
            }
        }
        setStage(Stage.DONE, 0, (short) 0);
        insert(this);
        listeners.notify(this, Event.SHUFFLING_DONE);
        if (deleteFinished) {
            LOG.debug("Deleting Shuffling Done = {} , height = {}", Long.toUnsignedString(this.id), this.getHeight());
            delete();
        }
        LOG.debug("Shuffling {} was distributed", Long.toUnsignedString(id));
        LOG.trace("Shuffling distributed {} entered stage {}, assignee {}, remaining blocks {}",
            Long.toUnsignedString(this.id), this.stage, Long.toUnsignedString(this.assigneeAccountId), this.blocksRemaining);
    }

    private void cancel(Block block) {
        LedgerEvent event = LedgerEvent.SHUFFLING_CANCELLATION;
        long blamedAccountId = blame();
        lookupAccountService();
        try (DbIterator<ShufflingParticipant> participants = ShufflingParticipant.getParticipants(id)) {
            for (ShufflingParticipant participant : participants) {
                Account participantAccount = accountService.getAccount(participant.getAccountId());
                holdingType.addToUnconfirmedBalance(participantAccount, event, this.id, this.holdingId, this.amount);
                if (participantAccount.getId() != blamedAccountId) {
                    if (holdingType != HoldingType.APL) {
                        accountService.addToUnconfirmedBalanceATM(participantAccount, event, this.id, blockchainConfig.getShufflingDepositAtm());
                    }
                } else {
                    if (holdingType == HoldingType.APL) {
                        accountService.addToUnconfirmedBalanceATM(participantAccount, event, this.id, -blockchainConfig.getShufflingDepositAtm());
                    }
                    accountService.addToBalanceATM(participantAccount, event, this.id, -blockchainConfig.getShufflingDepositAtm());
                }
            }
        }
        if (blamedAccountId != 0) {
            // as a penalty the deposit goes to the generators of the finish block and previous 3 blocks
            long fee = blockchainConfig.getShufflingDepositAtm() / 4;
            int shardHeight = blockchain.getShardInitialBlock().getHeight();
            long[] generators = null;
            for (int i = 0; i < 3; i++) {
                int blockHeight = block.getHeight() - i - 1;
                if (generators == null && shardHeight > blockHeight) {
                    generators = shardDao.getLastShard().getGeneratorIds();

                }
                Account previousGeneratorAccount;
                if (shardHeight > blockHeight) {
                    int diff = shardHeight - blockHeight - 1;
                    previousGeneratorAccount = accountService.getAccount(generators[diff]);
                } else {
                    previousGeneratorAccount = accountService.getAccount(blockchain.getBlockAtHeight(blockHeight).getGeneratorId());
                }
                accountService.addToBalanceAndUnconfirmedBalanceATM(previousGeneratorAccount, LedgerEvent.BLOCK_GENERATED, block.getId(), fee);
                accountService.addToForgedBalanceATM(previousGeneratorAccount, fee);
                LOG.debug("Shuffling penalty {} {} awarded to forger at height {}", ((double) fee) / Constants.ONE_APL, blockchainConfig.getCoinSymbol(),
                    block.getHeight() - i - 1);
            }
            fee = blockchainConfig.getShufflingDepositAtm() - 3 * fee;
            Account blockGeneratorAccount = accountService.getAccount(block.getGeneratorId());
            accountService.addToBalanceAndUnconfirmedBalanceATM(blockGeneratorAccount, LedgerEvent.BLOCK_GENERATED, block.getId(), fee);
            accountService.addToForgedBalanceATM(blockGeneratorAccount, fee);
            LOG.debug("Shuffling penalty {} {} awarded to forger at height {}", ((double) fee) / Constants.ONE_APL, blockchainConfig.getCoinSymbol(),
                block.getHeight());
        }
        setStage(Stage.CANCELLED, blamedAccountId, (short) 0);
        insert(this);
        listeners.notify(this, Event.SHUFFLING_CANCELLED);
        if (deleteFinished) {
            LOG.debug("Deleting Shuffling Cancelled = {} , height = {} / {}", Long.toUnsignedString(this.id), block.getHeight(), this.getHeight());
            delete();
        }
        LOG.debug("Shuffling {} was cancelled, blaming account {}", Long.toUnsignedString(id), Long.toUnsignedString(blamedAccountId));
    }

    private long blame() {
        // if registration never completed, no one is to blame
        if (stage == Stage.REGISTRATION) {
            LOG.debug("Registration never completed for shuffling {}", Long.toUnsignedString(id));
            return 0;
        }
        // if no one submitted cancellation, blame the first one that did not submit processing data
        if (stage == Stage.PROCESSING) {
            LOG.debug("Participant {} did not submit processing", Long.toUnsignedString(assigneeAccountId));
            return assigneeAccountId;
        }
        List<ShufflingParticipant> participants = new ArrayList<>();
        try (DbIterator<ShufflingParticipant> iterator = ShufflingParticipant.getParticipants(this.id)) {
            while (iterator.hasNext()) {
                participants.add(iterator.next());
            }
        }
        if (stage == Stage.VERIFICATION) {
            // if verification started, blame the first one who did not submit verification
            for (ShufflingParticipant participant : participants) {
                if (participant.getState() != ShufflingParticipant.State.VERIFIED) {
                    LOG.debug("Participant {} did not submit verification", Long.toUnsignedString(participant.getAccountId()));
                    return participant.getAccountId();
                }
            }
            throw new RuntimeException("All participants submitted data and verifications, blame phase should not have been entered");
        }
        Set<Long> recipientAccounts = new HashSet<>(participantCount);
        lookupAccountService();
        // start from issuer and verify all data up, skipping last participant
        for (int i = 0; i < participantCount - 1; i++) {
            ShufflingParticipant participant = participants.get(i);
            byte[][] keySeeds = participant.getKeySeeds();
            // if participant couldn't submit key seeds because he also couldn't decrypt some of the previous data, this should have been caught before
            if (keySeeds.length == 0) {
                LOG.debug("Participant {} did not reveal keys", Long.toUnsignedString(participant.getAccountId()));
                return participant.getAccountId();
            }
            byte[] publicKey = Crypto.getPublicKey(keySeeds[0]);
            AnonymouslyEncryptedData encryptedData = null;
            for (byte[] bytes : participant.getBlameData()) {
                encryptedData = AnonymouslyEncryptedData.readEncryptedData(bytes);
                if (Arrays.equals(publicKey, encryptedData.getPublicKey())) {
                    // found the data that this participant encrypted
                    break;
                }
            }
            if (encryptedData == null || !Arrays.equals(publicKey, encryptedData.getPublicKey())) {
                // participant lied about key seeds or data
                LOG.debug("Participant {} did not submit blame data, or revealed invalid keys", Long.toUnsignedString(participant.getAccountId()));
                return participant.getAccountId();
            }
            for (int k = i + 1; k < participantCount; k++) {
                ShufflingParticipant nextParticipant = participants.get(k);
                byte[] nextParticipantPublicKey = accountService.getPublicKeyByteArray(nextParticipant.getAccountId());
                byte[] keySeed = keySeeds[k - i - 1];
                byte[] participantBytes;
                try {
                    participantBytes = encryptedData.decrypt(keySeed, nextParticipantPublicKey);
                } catch (Exception e) {
                    // the next participant couldn't decrypt the data either, blame this one
                    LOG.debug("Could not decrypt data from participant {}", Long.toUnsignedString(participant.getAccountId()));
                    return participant.getAccountId();
                }
                boolean isLast = k == participantCount - 1;
                if (isLast) {
                    // not encrypted data but plaintext recipient public key
                    if (!Crypto.isCanonicalPublicKey(publicKey)) {
                        // not a valid public key
                        LOG.debug("Participant {} submitted invalid recipient public key", Long.toUnsignedString(participant.getAccountId()));
                        return participant.getAccountId();
                    }
                    // check for collisions and assume they are intentional
                    byte[] currentPublicKey = accountService.getPublicKeyByteArray(AccountService.getId(participantBytes));
                    if (currentPublicKey != null && !Arrays.equals(currentPublicKey, participantBytes)) {
                        LOG.debug("Participant {} submitted colliding recipient public key", Long.toUnsignedString(participant.getAccountId()));
                        return participant.getAccountId();
                    }
                    if (!recipientAccounts.add(AccountService.getId(participantBytes))) {
                        LOG.debug("Participant {} submitted duplicate recipient public key", Long.toUnsignedString(participant.getAccountId()));
                        return participant.getAccountId();
                    }
                }
                if (nextParticipant.getState() == ShufflingParticipant.State.CANCELLED && nextParticipant.getBlameData().length == 0) {
                    break;
                }
                boolean found = false;
                for (byte[] bytes : isLast ? recipientPublicKeys : nextParticipant.getBlameData()) {
                    if (Arrays.equals(participantBytes, bytes)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    // the next participant did not include this participant's data
                    LOG.debug("Participant {} did not include previous data", Long.toUnsignedString(nextParticipant.getAccountId()));
                    return nextParticipant.getAccountId();
                }
                if (!isLast) {
                    encryptedData = AnonymouslyEncryptedData.readEncryptedData(participantBytes);
                }
            }
        }
        return assigneeAccountId;
    }

    private void delete() {
        try (DbIterator<ShufflingParticipant> participants = ShufflingParticipant.getParticipants(id)) {
            for (ShufflingParticipant participant : participants) {
                participant.delete();
            }
        }
        shufflingTable.deleteAtHeight(this, blockchain.getHeight());
        LOG.debug("DELETED Shuffling {} entered stage {}, assignee {}, remaining blocks {}",
            Long.toUnsignedString(id), this.stage, Long.toUnsignedString(this.assigneeAccountId), this.blocksRemaining);

    }

    private boolean isFull(Block block) {
        int transactionSize = Constants.MIN_TRANSACTION_SIZE; // min transaction size with no attachment
        if (stage == Stage.REGISTRATION) {
            transactionSize += 1 + 32;
        } else { // must use same for PROCESSING/VERIFICATION/BLAME
            transactionSize = 16384; // max observed was 15647 for 30 participants
        }
        return block.getPayloadLength() + transactionSize > blockchainConfig.getCurrentConfig().getMaxPayloadLength();
    }

    private AccountService lookupAccountService() {
        if (accountService == null) {
            accountService = CDI.current().select(AccountServiceImpl.class).get();
        }
        return accountService;
    }

    private AccountPublicKeyService lookupAccountPublickKeyService() {
        if (accountPublicKeyService == null) {
            accountPublicKeyService = CDI.current().select(AccountPublicKeyServiceImpl.class).get();
        }
        return accountPublicKeyService;
    }

    public enum Event {
        SHUFFLING_CREATED, SHUFFLING_PROCESSING_ASSIGNED, SHUFFLING_PROCESSING_FINISHED, SHUFFLING_BLAME_STARTED, SHUFFLING_CANCELLED, SHUFFLING_DONE
    }

    public enum Stage {
        REGISTRATION((byte) 0, new byte[]{1, 4}) {
            @Override
            byte[] getHash(Shuffling shuffling) {
                return shuffling.getFullHash();
            }
        },
        PROCESSING((byte) 1, new byte[]{2, 3, 4}) {
            @Override
            byte[] getHash(Shuffling shuffling) {
                if (shuffling.assigneeAccountId == shuffling.issuerId) {
                    try (DbIterator<ShufflingParticipant> participants = ShufflingParticipant.getParticipants(shuffling.id)) {
                        return getParticipantsHash(participants);
                    }
                } else {
                    ShufflingParticipant participant = shuffling.getParticipant(shuffling.assigneeAccountId);
                    return participant.getPreviousParticipant().getDataTransactionFullHash();
                }

            }
        },
        VERIFICATION((byte) 2, new byte[]{3, 4, 5}) {
            @Override
            byte[] getHash(Shuffling shuffling) {
                return shuffling.getLastParticipant().getDataTransactionFullHash();
            }
        },
        BLAME((byte) 3, new byte[]{4}) {
            @Override
            byte[] getHash(Shuffling shuffling) {
                return shuffling.getParticipant(shuffling.assigneeAccountId).getDataTransactionFullHash();
            }
        },
        CANCELLED((byte) 4, new byte[]{}) {
            @Override
            byte[] getHash(Shuffling shuffling) {
                byte[] hash = shuffling.getLastParticipant().getDataTransactionFullHash();
                if (hash != null && hash.length > 0) {
                    return hash;
                }
                try (DbIterator<ShufflingParticipant> participants = ShufflingParticipant.getParticipants(shuffling.id)) {
                    return getParticipantsHash(participants);
                }
            }
        },
        DONE((byte) 5, new byte[]{}) {
            @Override
            byte[] getHash(Shuffling shuffling) {
                return shuffling.getLastParticipant().getDataTransactionFullHash();
            }
        };

        private final byte code;
        private final byte[] allowedNext;

        Stage(byte code, byte[] allowedNext) {
            this.code = code;
            this.allowedNext = allowedNext;
        }

        public static Stage get(byte code) {
            for (Stage stage : Stage.values()) {
                if (stage.code == code) {
                    return stage;
                }
            }
            throw new IllegalArgumentException("No matching stage for " + code);
        }

        public byte getCode() {
            return code;
        }

        public boolean canBecome(Stage nextStage) {
            return Arrays.binarySearch(allowedNext, nextStage.code) >= 0;
        }

        abstract byte[] getHash(Shuffling shuffling);

    }

    @Singleton
    public static class ShufflingObserver {
        public void onBlockApplied(@Observes @BlockEvent(BlockEventType.AFTER_BLOCK_APPLY) Block block) {
            LOG.trace(":accept:ShufflingObserver: START onBlockApplaid AFTER_BLOCK_APPLY, block={}", block.getHeight());
            long startTime = System.currentTimeMillis();
            LOG.trace("Shuffling observer call at {}", block.getHeight());
            if (block.getOrLoadTransactions().size() == blockchainConfig.getCurrentConfig().getMaxNumberOfTransactions()
                || block.getPayloadLength() > blockchainConfig.getCurrentConfig().getMaxPayloadLength() - Constants.MIN_TRANSACTION_SIZE) {
                LOG.trace("Will not process shufflings at {}", block.getHeight());
                return;
            }
            List<Shuffling> shufflings = new ArrayList<>();
            List<Shuffling> activeShufflings = getActiveShufflings();//CollectionUtil.toList(getActiveShufflings(0, -1));
            LOG.trace("Got {} active shufflings at {} in {} ms", activeShufflings.size(), block.getHeight(), System.currentTimeMillis() - startTime);
            for (Shuffling shuffling : activeShufflings) {
                if (!shuffling.isFull(block)) {
                    shufflings.add(shuffling);
                } else {
                    LOG.trace("Skip shuffling {}, block is full", block.getId());
                }
            }
            LOG.trace("Shufflings to process - {} ", shufflings.size());
            int cancelled = 0, inserted = 0;
            for (Shuffling shuffling : shufflings) {
                if (--shuffling.blocksRemaining <= 0) {
                    cancelled++;
                    shuffling.cancel(block);
                } else {
                    LOG.trace("Insert shuffling {} - height - {} remaining - {}",
                        shuffling.getId(), shuffling.getHeight(), shuffling.getBlocksRemaining());
                    inserted++;
                    shufflingTable.insert(shuffling);
                }
            }
            LOG.trace(":accept: Shuffling observer, inserted [{}], cancelled [{}] in time: {} msec", inserted, cancelled, System.currentTimeMillis() - startTime);
        }
    }
}
