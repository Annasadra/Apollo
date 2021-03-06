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
 * Copyright © 2018-2020 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.alias.dao;

import com.apollocurrency.aplwallet.apl.core.alias.entity.Alias;
import com.apollocurrency.aplwallet.apl.core.alias.entity.AliasOffer;
import com.apollocurrency.aplwallet.apl.core.db.DbClause;
import com.apollocurrency.aplwallet.apl.core.db.DbKey;
import com.apollocurrency.aplwallet.apl.core.db.DbUtils;
import com.apollocurrency.aplwallet.apl.core.db.LongKeyFactory;
import com.apollocurrency.aplwallet.apl.core.db.derived.VersionedDeletableEntityDbTable;
import com.apollocurrency.aplwallet.apl.util.annotation.DatabaseSpecificDml;
import com.apollocurrency.aplwallet.apl.util.annotation.DmlMarker;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * @author silaev-firstbridge on 3/17/2020
 */
@Singleton
public class AliasOfferTable extends VersionedDeletableEntityDbTable<AliasOffer> {

    private static final LongKeyFactory<AliasOffer> offerDbKeyFactory = new LongKeyFactory<>("id") {

        @Override
        public DbKey newKey(AliasOffer offer) {
            if (offer.getDbKey() == null) {
                offer.setDbKey(super.newKey(offer.getAliasId()));
            }
            return offer.getDbKey();
        }
    };

    @Inject
    public AliasOfferTable() {
        super("alias_offer", offerDbKeyFactory, false);
    }

    @Override
    public AliasOffer load(Connection con, ResultSet rs, DbKey dbKey) throws SQLException {
        return new AliasOffer(rs, dbKey);
    }

    @Override
    public void save(Connection con, AliasOffer offer) throws SQLException {
        try (
            @DatabaseSpecificDml(DmlMarker.MERGE)
            PreparedStatement pstmt = con.prepareStatement("MERGE INTO alias_offer (id, price, buyer_id, "
                + "height) KEY (id, height) VALUES (?, ?, ?, ?)")
        ) {
            int i = 0;
            pstmt.setLong(++i, offer.getAliasId());
            pstmt.setLong(++i, offer.getPriceATM());
            DbUtils.setLongZeroToNull(pstmt, ++i, offer.getBuyerId());
            pstmt.setInt(++i, offer.getHeight());
            pstmt.executeUpdate();
        }
    }

    public AliasOffer getOffer(Alias alias) {
        return getBy(new DbClause.LongClause("id", alias.getId()).and(new DbClause.LongClause("price", DbClause.Op.NE, Long.MAX_VALUE)));
    }
}
