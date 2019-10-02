/*
 *  Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.db.table;

import com.apollocurrency.aplwallet.apl.core.db.DbKey;
import com.apollocurrency.aplwallet.apl.core.db.LongKey;
import com.apollocurrency.aplwallet.apl.core.db.LongKeyFactory;
import com.apollocurrency.aplwallet.apl.core.db.model.DerivedIdEntity;

public class DerivedEntityKeyFactory extends LongKeyFactory<DerivedIdEntity> {
    public DerivedEntityKeyFactory() {
        super("id");
    }

    public DerivedEntityKeyFactory(String idColumn) {
        super(idColumn);
    }

    @Override
    public DbKey newKey(DerivedIdEntity derivedIdEntity) {
        if (derivedIdEntity.getDbKey() == null) {
            derivedIdEntity.setDbKey(new LongKey(derivedIdEntity.getId()));
        }
        return derivedIdEntity.getDbKey();
    }
}