/*
 *  Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.dgs.dao;

import com.apollocurrency.aplwallet.apl.core.app.Blockchain;
import com.apollocurrency.aplwallet.apl.core.app.BlockchainImpl;
import com.apollocurrency.aplwallet.apl.core.db.DbKey;
import com.apollocurrency.aplwallet.apl.core.db.LongKeyFactory;
import com.apollocurrency.aplwallet.apl.core.db.derived.VersionedValuesDbTable;
import com.apollocurrency.aplwallet.apl.core.dgs.model.DGSPurchase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.enterprise.inject.spi.CDI;
import javax.inject.Singleton;

@Singleton
//public class DGSPublicFeedbackTable extends VersionedValuesDbTable<DGSPurchase, String> {
public class DGSPublicFeedbackTable extends VersionedValuesDbTable<DGSPurchase/*, String*/> {
    private static final LongKeyFactory<DGSPurchase> KEY_FACTORY = new LongKeyFactory<>("id") {
        @Override
        public DbKey newKey(DGSPurchase purchase) {
            if (purchase.getDbKey() == null) {
                DbKey dbKey = newKey(purchase.getId());
                purchase.setDbKey(dbKey);
            }
            return purchase.getDbKey();
        }

    };
    private static final String TABLE_NAME = "purchase_public_feedback";

    protected DGSPublicFeedbackTable() {
        super(TABLE_NAME, KEY_FACTORY);
    }

    @Override
//    protected String load(Connection connection, ResultSet rs) throws SQLException {
    public DGSPurchase load(Connection connection, ResultSet rs, DbKey dbKey) throws SQLException {
//        return rs.getString("public_feedback");
        return new DGSPurchase(rs, dbKey);
    }

    @Override
    public void save(Connection con, DGSPurchase purchase/*, String publicFeedback*/) throws SQLException {
        try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO purchase_public_feedback (id, public_feedback, "
                + "height, latest) VALUES (?, ?, ?, TRUE)")) {
            int i = 0;
            pstmt.setLong(++i, purchase.getId());
//            pstmt.setString(++i, publicFeedback);
            pstmt.setString(++i, purchase.getPublicFeedbacks().get(0)); // TODO: YL review
            Blockchain blockchain = CDI.current().select(BlockchainImpl.class).get();
            pstmt.setInt(++i, blockchain.getHeight()); // TODO: YL review
            pstmt.executeUpdate();
        }
    }

}
