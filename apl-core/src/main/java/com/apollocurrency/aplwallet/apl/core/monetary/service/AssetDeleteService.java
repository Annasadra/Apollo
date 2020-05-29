/*
 *  Copyright © 2018-2020 Apollo Foundation
 */
package com.apollocurrency.aplwallet.apl.core.monetary.service;

import com.apollocurrency.aplwallet.apl.core.app.Transaction;
import com.apollocurrency.aplwallet.apl.core.db.DbIterator;
import com.apollocurrency.aplwallet.apl.core.monetary.model.AssetDelete;

public interface AssetDeleteService {
    DbIterator<AssetDelete> getAssetDeletes(long assetId, int from, int to);

    DbIterator<AssetDelete> getAccountAssetDeletes(long accountId, int from, int to);

    DbIterator<AssetDelete> getAccountAssetDeletes(long accountId, long assetId, int from, int to);

    AssetDelete addAssetDelete(Transaction transaction, long assetId, long quantityATU);
}
