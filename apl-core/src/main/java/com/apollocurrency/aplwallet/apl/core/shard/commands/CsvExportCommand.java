/*
 * Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.shard.commands;

import static org.slf4j.LoggerFactory.getLogger;

import com.apollocurrency.aplwallet.apl.core.shard.ExcludeInfo;
import com.apollocurrency.aplwallet.apl.core.shard.MigrateState;
import com.apollocurrency.aplwallet.apl.core.shard.ShardConstants;
import com.apollocurrency.aplwallet.apl.core.shard.ShardEngine;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Export specified tables + 'derived tables' into CSV.
 */
public class CsvExportCommand implements DataMigrateOperation {
    private static final Logger log = getLogger(CsvExportCommand.class);

    private ShardEngine shardEngine;
    private List<String> tableNameList;
    private int commitBatchSize;
    private int snapshotBlockHeight;
    private ExcludeInfo excludeInfo;
    private int blockchainHeight;

    public CsvExportCommand(ShardEngine shardEngine,
                            int commitBatchSize, int snapshotBlockHeight, List<String> tableNameList, ExcludeInfo excludeInfo, int blockchainHeight) {
        this.shardEngine = Objects.requireNonNull(shardEngine, "shardEngine is NULL");
        this.snapshotBlockHeight = snapshotBlockHeight;
        this.commitBatchSize = commitBatchSize <= 0 ? ShardConstants.DEFAULT_COMMIT_BATCH_SIZE : commitBatchSize;
        this.excludeInfo = excludeInfo;
        this.tableNameList = tableNameList == null ? new ArrayList<>() : tableNameList;
        this.blockchainHeight = blockchainHeight;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public MigrateState execute() {
        log.debug("CSV Export Command execute...");
        CommandParamInfo paramInfo = CommandParamInfo.builder()
                .tableNameList(this.tableNameList)
                .commitBatchSize(this.commitBatchSize)
                .snapshotBlockHeight(this.snapshotBlockHeight)
                .excludeInfo(this.excludeInfo)
                .blockchainHeight(blockchainHeight)
                .build();
        return shardEngine.exportCsv(paramInfo);
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("CsvExportCommand{");
        sb.append("tableNameList=").append(tableNameList);
        sb.append(", commitBatchSize=").append(commitBatchSize);
        sb.append(", snapshotBlockHeight=").append(snapshotBlockHeight);
        sb.append('}');
        return sb.toString();
    }
}
