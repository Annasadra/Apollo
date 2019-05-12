/*
 * Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.db.derived;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Common derived interface functions. It supports rollback, truncate, trim.
 *
 * @author yuriy.larin
 */
public interface DerivedTableInterface<T> {

    void rollback(int height);

    void truncate();

    void trim(int height);

    void createSearchIndex(Connection con) throws SQLException;

    void insert(T t);

    DerivedTableData<T> getAllByDbId(long from, int limit, long dbIdLimit) throws SQLException;

    boolean delete(T t);

    default DerivedTableData<T> getAllByDbId(MinMaxDbId minMaxDbId, int limit) throws SQLException {
        throw new UnsupportedOperationException("GetAll is not supported");
    }

    default ResultSet getRangeByDbId(Connection con, PreparedStatement pstmt,
                                     MinMaxDbId minMaxDbId, int limit) throws SQLException {
        throw new UnsupportedOperationException("GetRange is not supported");
    }

    default MinMaxDbId getMinMaxDbId(int height) throws SQLException {
        return new MinMaxDbId();
    }

}
