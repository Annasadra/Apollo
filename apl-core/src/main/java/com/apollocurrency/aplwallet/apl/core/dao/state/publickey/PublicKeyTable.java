/*
 * Copyright © 2018-2019 Apollo Foundation
 */
package com.apollocurrency.aplwallet.apl.core.dao.state.publickey;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.apollocurrency.aplwallet.apl.core.dao.state.derived.EntityDbTable;
import com.apollocurrency.aplwallet.apl.core.dao.state.derived.EntityDbTableInterface;
import com.apollocurrency.aplwallet.apl.core.dao.state.keyfactory.DbKey;
import com.apollocurrency.aplwallet.apl.core.dao.state.keyfactory.LongKey;
import com.apollocurrency.aplwallet.apl.core.dao.state.keyfactory.LongKeyFactory;
import com.apollocurrency.aplwallet.apl.core.db.DbUtils;
import com.apollocurrency.aplwallet.apl.core.entity.state.account.PublicKey;
import com.apollocurrency.aplwallet.apl.core.service.appdata.DatabaseManager;
import com.apollocurrency.aplwallet.apl.core.service.blockchain.Blockchain;
import com.apollocurrency.aplwallet.apl.core.service.state.DerivedTablesRegistry;
import com.apollocurrency.aplwallet.apl.util.annotation.DatabaseSpecificDml;
import com.apollocurrency.aplwallet.apl.util.annotation.DmlMarker;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * @author al
 */
@Singleton
public class PublicKeyTable extends EntityDbTable<PublicKey> implements EntityDbTableInterface<PublicKey> {

    private static final LongKeyFactory<PublicKey> KEY_FACTORY = new LongKeyFactory<>("account_id") {
        @Override
        public DbKey newKey(PublicKey publicKey) {
            if (publicKey.getDbKey() == null) {
                publicKey.setDbKey(new LongKey(publicKey.getAccountId()));
            }
            return publicKey.getDbKey();
        }
    };
    private final Blockchain blockchain;

    @Inject
    public PublicKeyTable(Blockchain blockchain,
                          DerivedTablesRegistry derivedDbTablesRegistry,
                          DatabaseManager databaseManager) {
        super("public_key", KEY_FACTORY, true, null, derivedDbTablesRegistry, databaseManager, null);
        this.blockchain = Objects.requireNonNull(blockchain, "Blockchain cannot be null");
    }

    public DbKey newKey(long id) {
        return KEY_FACTORY.newKey(id);
    }

    @Override
    public PublicKey load(Connection con, ResultSet rs, DbKey dbKey) throws SQLException {
        return new PublicKey(rs, dbKey);
    }

    @Override
    public void save(Connection con, PublicKey publicKey) throws SQLException {
        publicKey.setHeight(blockchain.getHeight());
        try (
            @DatabaseSpecificDml(DmlMarker.MERGE)
            final PreparedStatement pstmt = con.prepareStatement("INSERT INTO " + table
                + " (account_id, public_key, height, latest) "
                + "VALUES (?, ?, ?, TRUE) "
                + "ON DUPLICATE KEY UPDATE "
                + "account_id = VALUES(account_id), public_key = VALUES(public_key), height = VALUES(height), latest = TRUE")
        ) {
            int i = 0;
            pstmt.setLong(++i, publicKey.getAccountId());
            DbUtils.setBytes(pstmt, ++i, publicKey.getPublicKey());
            pstmt.setInt(++i, publicKey.getHeight());
            pstmt.executeUpdate();
        }
    }

}
