/*
 * Copyright © 2018-2020 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.db.service;

import com.apollocurrency.aplwallet.apl.core.app.Block;
import com.apollocurrency.aplwallet.apl.core.db.DbIterator;

/**
 * @author silaev-firstbridge on 1/31/2020
 */
public interface BlockChainInfoService {
    boolean doesNotExceed(int height);

    void checkAvailable(int height, boolean isMultiVersion);

    void checkAvailable(int height);

    int getMinRollbackHeight();

    int getHeight();

    Block getLastBlock();

    DbIterator<Block> getBlocks(long accountId, int timestamp, int from, int to);
}
