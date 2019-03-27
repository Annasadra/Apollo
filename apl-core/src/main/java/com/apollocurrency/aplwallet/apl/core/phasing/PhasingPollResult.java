/*
 *  Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.phasing;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class PhasingPollResult {

    private final long id;
    private final long result;
    private final boolean approved;
    private final int height;

    public PhasingPollResult(PhasingPoll poll, long result, int height) {
        this.id = poll.getId();
        this.result = result;
        this.approved = result >= poll.getQuorum();
        this.height = height;
    }

    public PhasingPollResult(ResultSet rs) throws SQLException {
        this.id = rs.getLong("id");
        this.result = rs.getLong("result");
        this.approved = rs.getBoolean("approved");
        this.height = rs.getInt("height");
    }

    public PhasingPollResult(long id, long result, boolean approved, int height) {
        this.id = id;
        this.result = result;
        this.approved = approved;
        this.height = height;
    }

    public void save(Connection con) throws SQLException {
        try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO phasing_poll_result (id, "
                + "result, approved, height) VALUES (?, ?, ?, ?)")) {
            int i = 0;
            pstmt.setLong(++i, id);
            pstmt.setLong(++i, result);
            pstmt.setBoolean(++i, approved);
            pstmt.setInt(++i, height);
            pstmt.executeUpdate();
        }
    }

    public long getId() {
        return id;
    }

    public long getResult() {
        return result;
    }

    public boolean isApproved() {
        return approved;
    }

    public int getHeight() {
        return height;
    }
}
