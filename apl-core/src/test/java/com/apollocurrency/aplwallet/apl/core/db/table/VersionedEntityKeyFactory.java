/*
 *  Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.db.table;

import com.apollocurrency.aplwallet.apl.core.db.DbKey;
import com.apollocurrency.aplwallet.apl.core.db.LongKey;
import com.apollocurrency.aplwallet.apl.core.db.LongKeyFactory;
import com.apollocurrency.aplwallet.apl.core.db.model.VersionedDerivedIdEntity;

public class VersionedEntityKeyFactory extends LongKeyFactory<VersionedDerivedIdEntity> {
    public VersionedEntityKeyFactory(String idColumn) {
        super(idColumn);
    }

    public VersionedEntityKeyFactory() {
        super("id");
    }

    @Override
    public DbKey newKey(VersionedDerivedIdEntity versionedDerivedIdEntity) {
        if (versionedDerivedIdEntity.getDbKey() == null) {
            versionedDerivedIdEntity.setDbKey(new LongKey(versionedDerivedIdEntity.getId()));
        }
        return versionedDerivedIdEntity.getDbKey();
    }
}