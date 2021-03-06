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

import com.apollocurrency.aplwallet.apl.core.app.observer.events.BlockEvent;
import com.apollocurrency.aplwallet.apl.core.app.observer.events.BlockEventType;
import com.apollocurrency.aplwallet.apl.core.db.DatabaseManager;
import com.apollocurrency.aplwallet.apl.core.db.DbClause;
import com.apollocurrency.aplwallet.apl.core.db.DbIterator;
import com.apollocurrency.aplwallet.apl.core.db.DbKey;
import com.apollocurrency.aplwallet.apl.core.db.DbUtils;
import com.apollocurrency.aplwallet.apl.core.db.LongKey;
import com.apollocurrency.aplwallet.apl.core.db.LongKeyFactory;
import com.apollocurrency.aplwallet.apl.core.db.TransactionalDataSource;
import com.apollocurrency.aplwallet.apl.core.db.derived.EntityDbTable;
import com.apollocurrency.aplwallet.apl.core.db.derived.ValuesDbTable;
import com.apollocurrency.aplwallet.apl.core.transaction.Messaging;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.MessagingPollCreation;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.MessagingVoteCasting;
import com.apollocurrency.aplwallet.apl.util.AplException;
import com.apollocurrency.aplwallet.apl.util.Constants;
import com.apollocurrency.aplwallet.apl.util.annotation.DatabaseSpecificDml;
import com.apollocurrency.aplwallet.apl.util.annotation.DmlMarker;
import org.slf4j.Logger;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.Vetoed;
import javax.enterprise.inject.spi.CDI;
import javax.inject.Singleton;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;

@Vetoed
public final class Poll extends AbstractPoll {
    private static final Logger LOG = getLogger(Poll.class);

    // TODO: YL remove static instance later
    private static final LongKeyFactory<Poll> pollDbKeyFactory = new LongKeyFactory<Poll>("id") {
        @Override
        public DbKey newKey(Poll poll) {
            return poll.getDbKey() == null ? newKey(poll.id) : poll.getDbKey();
        }
    };
    private static final LongKeyFactory<PollOptionResult> pollResultsDbKeyFactory = new LongKeyFactory<>("poll_id") {
        @Override
        public DbKey newKey(PollOptionResult pollOptionResult) {
            if (pollOptionResult.getDbKey() == null) {
                pollOptionResult.setDbKey(new LongKey(pollOptionResult.getPollId()));
            }
            return pollOptionResult.getDbKey();
        }
    };
    private static BlockchainProcessor blockchainProcessor;
    private static Blockchain blockchain;
    private final static EntityDbTable<Poll> pollTable = new EntityDbTable<>("poll", pollDbKeyFactory, "name,description") {

        @Override
        public Poll load(Connection con, ResultSet rs, DbKey dbKey) throws SQLException {
            return new Poll(rs, dbKey);
        }

        @Override
        public void save(Connection con, Poll poll) throws SQLException {
            poll.save(con);
        }
    };
    private static final ValuesDbTable<PollOptionResult> pollResultsTable = new ValuesDbTable<PollOptionResult>("poll_result", pollResultsDbKeyFactory) {

        @Override
        public PollOptionResult load(Connection con, ResultSet rs, DbKey dbKey) throws SQLException {
            long id = rs.getLong("poll_id");
            long result = rs.getLong("result");
            long weight = rs.getLong("weight");
            return weight == 0 ? new PollOptionResult(id) : new PollOptionResult(id, result, weight);
        }

        @Override
        protected void save(Connection con, PollOptionResult optionResult) throws SQLException {
            try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO poll_result (poll_id, "
                + "result, weight, height) VALUES (?, ?, ?, ?)")) {
                int i = 0;
                pstmt.setLong(++i, optionResult.getPollId());
                if (!optionResult.isUndefined()) {
                    pstmt.setLong(++i, optionResult.getResult());
                    pstmt.setLong(++i, optionResult.getWeight());
                } else {
                    pstmt.setNull(++i, Types.BIGINT);
                    pstmt.setLong(++i, 0);
                }
                int height = lookupBlockchain().getHeight();
                pstmt.setInt(++i, height);
                LOG.trace("PollResult save = {} at height = {}", optionResult, height);
                pstmt.executeUpdate();
            }
        }
    };
    private static DatabaseManager databaseManager;
    private final String name;
    private final String description;
    private final String[] options;
    private final byte minNumberOfOptions;
    private final byte maxNumberOfOptions;
    private final byte minRangeValue;
    private final byte maxRangeValue;
    private final int timestamp;

    public Poll(Transaction transaction, MessagingPollCreation attachment) {
        super(null, transaction.getHeight(), transaction.getId(), attachment.getVoteWeighting(), transaction.getSenderId(), attachment.getFinishHeight());
        setDbKey(pollDbKeyFactory.newKey(this.id));
        this.name = attachment.getPollName();
        this.description = attachment.getPollDescription();
        this.options = attachment.getPollOptions();
        this.minNumberOfOptions = attachment.getMinNumberOfOptions();
        this.maxNumberOfOptions = attachment.getMaxNumberOfOptions();
        this.minRangeValue = attachment.getMinRangeValue();
        this.maxRangeValue = attachment.getMaxRangeValue();
        this.timestamp = lookupBlockchain().getLastBlockTimestamp();
    }

    public Poll(ResultSet rs, DbKey dbKey) throws SQLException {
        super(rs);
        setDbKey(dbKey);
        this.name = rs.getString("name");
        this.description = rs.getString("description");
        this.options = DbUtils.getArray(rs, "options", String[].class);
        this.minNumberOfOptions = rs.getByte("min_num_options");
        this.maxNumberOfOptions = rs.getByte("max_num_options");
        this.minRangeValue = rs.getByte("min_range_value");
        this.maxRangeValue = rs.getByte("max_range_value");
        this.timestamp = rs.getInt("timestamp");
    }

    public static Poll getPoll(long id) {
        return pollTable.get(pollDbKeyFactory.newKey(id));
    }

    public static DbIterator<Poll> getPollsFinishingAtOrBefore(int height, int from, int to) {
        return pollTable.getManyBy(new DbClause.IntClause("finish_height", DbClause.Op.LTE, height), from, to);
    }

    public static DbIterator<Poll> getPollsFinishingBelowHeight(int height, int from, int to) {
        // select all Polls where 'finish_height' is LESS (DbClause.Op.LT) then specified height value
        return pollTable.getManyBy(new DbClause.IntClause("finish_height", DbClause.Op.LT, height), from, to);
    }

    public static DbIterator<Poll> getPollsFinishingAtHeight(int height) {
        // EXACT matching to Poll finish height
        return pollTable.getManyBy(new DbClause.IntClause("finish_height", height), 0, Integer.MAX_VALUE);
    }

    public static DbIterator<Poll> getAllPolls(int from, int to) {
        return pollTable.getAll(from, to);
    }

    public static DbIterator<Poll> getActivePolls(int from, int to) {
        return pollTable.getManyBy(new DbClause.IntClause("finish_height", DbClause.Op.GT, lookupBlockchain().getHeight()), from, to);
    }

    public static DbIterator<Poll> getPollsByAccount(long accountId, boolean includeFinished, boolean finishedOnly, int from, int to) {
        DbClause dbClause = new DbClause.LongClause("account_id", accountId);
        if (finishedOnly) {
            dbClause = dbClause.and(new DbClause.IntClause("finish_height", DbClause.Op.LTE, lookupBlockchain().getHeight()));
        } else if (!includeFinished) {
            dbClause = dbClause.and(new DbClause.IntClause("finish_height", DbClause.Op.GT, lookupBlockchain().getHeight()));
        }
        return pollTable.getManyBy(dbClause, from, to);
    }

    private static TransactionalDataSource lookupDataSource() {
        if (databaseManager == null) {
            databaseManager = CDI.current().select(DatabaseManager.class).get();
        }
        return databaseManager.getDataSource();
    }

    private static Blockchain lookupBlockchain() {
        if (blockchain == null) {
            blockchain = CDI.current().select(Blockchain.class).get();
        }
        return blockchain;
    }

    private static BlockchainProcessor lookupProcessor() {
        if (blockchainProcessor == null) {
            blockchainProcessor = CDI.current().select(BlockchainProcessor.class).get();
        }
        return blockchainProcessor;
    }

    public static DbIterator<Poll> getVotedPollsByAccount(long accountId, int from, int to) throws AplException.NotValidException {
        Connection connection = null;
        try {
            connection = lookupDataSource().getConnection();

//            extract voted poll ids from attachment
            try (PreparedStatement pstmt = connection.prepareStatement(
                "(SELECT attachment_bytes FROM transaction " +
                    "WHERE sender_id = ? AND type = ? AND subtype = ? " +
                    "ORDER BY block_timestamp DESC, transaction_index DESC"
                    + DbUtils.limitsClause(from, to))) {
                int i = 0;
                pstmt.setLong(++i, accountId);
                pstmt.setByte(++i, Messaging.VOTE_CASTING.getType());
                pstmt.setByte(++i, Messaging.VOTE_CASTING.getSubtype());
                DbUtils.setLimits(++i, pstmt, from, to);
                List<Long> ids = new ArrayList<>();
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        byte[] bytes = rs.getBytes("attachment_bytes");
                        ByteBuffer buffer = ByteBuffer.allocate(bytes.length);
                        buffer.order(ByteOrder.LITTLE_ENDIAN);
                        buffer.put(bytes);
                        long pollId = new MessagingVoteCasting(buffer).getPollId();
                        ids.add(pollId);
                    }
                }
                PreparedStatement pollStatement = connection.prepareStatement(
                    "SELECT * FROM poll WHERE id IN (SELECT * FROM table(x bigint = ? ))");
                pollStatement.setObject(1, ids.toArray());
                return pollTable.getManyBy(connection, pollStatement, false);
            }

        } catch (SQLException e) {
            DbUtils.close(connection);
            throw new RuntimeException(e.toString(), e);
        }
    }

    public static DbIterator<Poll> searchPolls(String query, boolean includeFinished, int from, int to) {
        DbClause dbClause = includeFinished ? DbClause.EMPTY_CLAUSE : new DbClause.IntClause("finish_height",
            DbClause.Op.GT, lookupBlockchain().getHeight());
        return pollTable.search(query, dbClause, from, to, " ORDER BY ft.score DESC, poll.height DESC, poll.db_id DESC ");
    }

    public static int getCount() {
        return pollTable.getCount();
    }

    public static void addPoll(Transaction transaction, MessagingPollCreation attachment) {
        Poll poll = new Poll(transaction, attachment);
        LOG.trace("addPoll = {}, height = {}, blockId={}", poll, transaction.getHeight(), transaction.getBlockId());
        pollTable.insert(poll);
    }

    public static void init() {

    }

    private static void checkPolls(int currentHeight) {
        // select all Polls where 'finish_height' is EQUAL (DbClause.Op.EQ) then specified height value
        try (DbIterator<Poll> polls = getPollsFinishingAtHeight(currentHeight)) {
            int index = 0;
            for (Poll poll : polls) {
                try {
                    List<PollOptionResult> results = poll.countResults(poll.getVoteWeighting(), currentHeight);
                    LOG.trace("checkPolls: height = {}, [{}] PollId = {} has = {}", currentHeight, index, poll.getId(), results.size());
                    pollResultsTable.insert(results);
                    LOG.trace("checkPolls: height = {}, [{}] PollId = {} checked : {}", currentHeight, index, poll.getId(), results);
                    index++;
                } catch (RuntimeException e) {
                    LOG.error("Couldn't count RollResult for poll {} at height = {}", poll.getId(), currentHeight, e);
                }
            }
        }
    }

    private void save(Connection con) throws SQLException {
        try (
            @DatabaseSpecificDml(DmlMarker.SET_ARRAY)
            PreparedStatement pstmt = con.prepareStatement("INSERT INTO poll (id, account_id, "
                + "name, description, options, finish_height, voting_model, min_balance, min_balance_model, "
                + "holding_id, min_num_options, max_num_options, min_range_value, max_range_value, timestamp, height) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        ) {
            int i = 0;
            pstmt.setLong(++i, id);
            pstmt.setLong(++i, accountId);
            pstmt.setString(++i, name);
            pstmt.setString(++i, description);
            DbUtils.setArray(pstmt, ++i, options);
            pstmt.setInt(++i, finishHeight);
            pstmt.setByte(++i, voteWeighting.getVotingModel().getCode());
            DbUtils.setLongZeroToNull(pstmt, ++i, voteWeighting.getMinBalance());
            pstmt.setByte(++i, voteWeighting.getMinBalanceModel().getCode());
            DbUtils.setLongZeroToNull(pstmt, ++i, voteWeighting.getHoldingId());
            pstmt.setByte(++i, minNumberOfOptions);
            pstmt.setByte(++i, maxNumberOfOptions);
            pstmt.setByte(++i, minRangeValue);
            pstmt.setByte(++i, maxRangeValue);
            pstmt.setInt(++i, timestamp);
            pstmt.setInt(++i, lookupBlockchain().getHeight());
            pstmt.executeUpdate();
        }
    }

    public List<PollOptionResult> getResults(VoteWeighting voteWeighting) {
        if (this.voteWeighting.equals(voteWeighting)) {
            return getResults();
        } else {
            return countResults(voteWeighting);
        }
    }

    public List<PollOptionResult> getResults() {
        if (isFinished()) {
            return pollResultsTable.get(pollDbKeyFactory.newKey(this)).stream().filter(r -> !r.isUndefined()).collect(Collectors.toList());
        } else {
            return countResults(voteWeighting);
        }
    }

    public DbIterator<Vote> getVotes() {
        return Vote.getVotes(this.getId(), 0, -1);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String[] getOptions() {
        return options;
    }

    public byte getMinNumberOfOptions() {
        return minNumberOfOptions;
    }

    public byte getMaxNumberOfOptions() {
        return maxNumberOfOptions;
    }

    public byte getMinRangeValue() {
        return minRangeValue;
    }

    public byte getMaxRangeValue() {
        return maxRangeValue;
    }

    public int getTimestamp() {
        return timestamp;
    }

    public boolean isFinished() {
        return finishHeight <= lookupBlockchain().getHeight();
    }

    private List<PollOptionResult> countResults(VoteWeighting voteWeighting) {
        int countHeight = Math.min(finishHeight, lookupBlockchain().getHeight());
        if (countHeight < lookupProcessor().getMinRollbackHeight()) {
            return null;
        }
        return countResults(voteWeighting, countHeight);
    }

    private List<PollOptionResult> countResults(VoteWeighting voteWeighting, int height) {
        final PollOptionResult[] result = new PollOptionResult[options.length];
        LOG.trace("count RollResult: START h={}, pollId={}, accountId = {}, {}, voteList = [{}]",
            height, id, accountId, voteWeighting, options.length);
        for (int i = 0; i < result.length; i++) {
            result[i] = new PollOptionResult(this.getId());
        }
        VoteWeighting.VotingModel votingModel = voteWeighting.getVotingModel();
        try (DbIterator<Vote> votes = Vote.getVotes(this.getId(), 0, -1)) {
            List<Vote> voteList = CollectionUtil.toList(votes);
            if (voteList.size() <= 0) {
                // stop further processing because there are no votes found
                LOG.trace("count RollResult: END 1. pollId={}, accountId={} PollOptionResult = {}", id, accountId, result);
                return Arrays.asList(result);
            }
            LOG.trace("count RollResult: h={}, pollId={}, votingModel={}, voteList = [{}]",
                height, id, votingModel, voteList.size());
//            LOG.trace("count RollResult: pollId={}, voteList = \n{}", id, voteList);
            for (Vote vote : voteList) {
                long weight = votingModel.calcWeight(voteWeighting, vote.getVoterId(), height);
                if (weight <= 0) {
                    continue;
                }
                long[] partialResult = countVote(vote, weight);
                for (int i = 0; i < partialResult.length; i++) {
                    if (partialResult[i] != Long.MIN_VALUE) {
                        if (result[i].isUndefined()) {
                            result[i] = new PollOptionResult(this.getId(), partialResult[i], weight);
                        } else {
                            result[i].add(partialResult[i], weight);
                        }
                    }
                }
            }
        }
        LOG.trace("count RollResult: END 2. pollId={}, accountId={} PollOptionResult = {}", id, accountId, result);
        return Arrays.asList(result);
    }

    private long[] countVote(Vote vote, long weight) {
        final long[] partialResult = new long[options.length];
        final byte[] optionValues = vote.getVoteBytes();
        for (int i = 0; i < optionValues.length; i++) {
            if (optionValues[i] != Constants.NO_VOTE_VALUE) {
                partialResult[i] = (long) optionValues[i] * weight;
            } else {
                partialResult[i] = Long.MIN_VALUE;
            }
        }
        return partialResult;
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("Poll{");
        sb.append("id=").append(id);
        sb.append(", name='").append(name).append('\'');
        sb.append(", accountId=").append(accountId);
        sb.append(", ").append(voteWeighting);
        sb.append(", finishHeight=").append(finishHeight);
        sb.append(", options=").append(options == null ? "null" : Arrays.asList(options).toString());
        sb.append(", description='").append(description).append('\'');
        sb.append(", minNumberOfOptions=").append(minNumberOfOptions);
        sb.append(", maxNumberOfOptions=").append(maxNumberOfOptions);
        sb.append(", minRangeValue=").append(minRangeValue);
        sb.append(", maxRangeValue=").append(maxRangeValue);
        sb.append(", timestamp=").append(timestamp);
        sb.append('}');
        return sb.toString();
    }

    @Singleton
    public static class PollObserver {
        public void onBlockApplied(@Observes @BlockEvent(BlockEventType.AFTER_BLOCK_APPLY) Block block) {
            int height = block.getHeight();
            LOG.trace(":accept:PollObserver: START onBlockApplied AFTER_BLOCK_APPLY. height={}", height);
            Poll.checkPolls(height);
            LOG.trace(":accept:PollObserver: END onBlockApplied AFTER_BLOCK_APPLY. height={}", height);
        }
    }
}
