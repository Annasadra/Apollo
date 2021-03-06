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
 * Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.db;

import com.apollocurrency.aplwallet.apl.core.db.dao.factory.BigIntegerArgumentFactory;
import com.apollocurrency.aplwallet.apl.core.db.dao.factory.DexCurrenciesFactory;
import com.apollocurrency.aplwallet.apl.core.db.dao.factory.LongArrayArgumentFactory;
import com.apollocurrency.aplwallet.apl.core.db.dao.factory.OrderStatusFactory;
import com.apollocurrency.aplwallet.apl.core.db.dao.factory.OrderTypeFactory;
import com.apollocurrency.aplwallet.apl.core.db.dao.factory.ShardStateFactory;
import com.apollocurrency.aplwallet.apl.util.StringUtils;
import com.apollocurrency.aplwallet.apl.util.annotation.DatabaseSpecificDml;
import com.apollocurrency.aplwallet.apl.util.annotation.DmlMarker;
import com.apollocurrency.aplwallet.apl.util.exception.DbException;
import com.apollocurrency.aplwallet.apl.util.injectable.DbProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.h2.jdbc.JdbcSQLException;
import org.jdbi.v3.core.ConnectionException;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.h2.H2DatabasePlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Represent basic implementation of DataSource
 */
public class DataSourceWrapper implements DataSource {
    private static final Logger log = getLogger(DataSourceWrapper.class);
    private static final String DB_INITIALIZATION_ERROR_TEXT = "DatabaseManager was not initialized!";
    private static final String MV_STORE = "MV_STORE";
    private static final String MVCC = "MVCC";
    private static Pattern patternExtractShardNumber = Pattern.compile("shard-\\d+");
    //    private JdbcConnectionPool dataSource;
//    private volatile int maxActiveConnections;
    private final String dbUrl;
    private final String dbUsername;
    private final String dbPassword;
    private final int maxConnections;
    private final int loginTimeout;
    private final int defaultLockTimeout;
    private final int maxMemoryRows;
    private String shardId = "main-db";
    private HikariDataSource dataSource;
    private HikariPoolMXBean jmxBean;
    private volatile boolean initialized = false;
    private volatile boolean shutdown = false;

    public DataSourceWrapper(DbProperties dbProperties) {
        long maxCacheSize = dbProperties.getMaxCacheSize();
        if (maxCacheSize == 0) {
            maxCacheSize = Math.min(256, Math.max(16, (Runtime.getRuntime().maxMemory() / (1024 * 1024) - 128) / 2)) * 1024;
        }

        //Even though dbUrl is no longer coming from apl-blockchain.properties,
        //DbMigrationExecutor in afterMigration triggers the further creation of DataSourceWrapper
        String dbUrlTemp = dbProperties.getDbUrl();
        final String dbParams = dbProperties.getDbParams();
        validateDbParams(dbParams);

        if (StringUtils.isBlank(dbUrlTemp)) {
            String dbFileName = dbProperties.getDbFileName();
            Matcher m = patternExtractShardNumber.matcher(dbFileName); // try to match shard name
            if (m.find()) { // if found
                shardId = m.group(); // store shard id
            }
            dbUrlTemp = String.format(
                "jdbc:%s:file:%s/%s;%s",
                dbProperties.getDbType(),
                dbProperties.getDbDir(),
                dbFileName,
                dbProperties.getDbParams()
            );
        } else {
            validateDbParams(dbUrlTemp);
        }

        if (!dbUrlTemp.contains(MV_STORE + "=")) {
            dbUrlTemp += ";" + MV_STORE + "=TRUE";
        }
        if (!dbUrlTemp.contains("CACHE_SIZE=")) {
            dbUrlTemp += ";CACHE_SIZE=" + maxCacheSize;
        }
        this.dbUrl = dbUrlTemp;
        dbProperties.dbUrl(dbUrlTemp);
        this.dbUsername = dbProperties.getDbUsername();
        this.dbPassword = dbProperties.getDbPassword();
        this.maxConnections = dbProperties.getMaxConnections();
        this.loginTimeout = dbProperties.getLoginTimeout();
        this.defaultLockTimeout = dbProperties.getDefaultLockTimeout();
        this.maxMemoryRows = dbProperties.getMaxMemoryRows();
    }

    @Override
    public Connection getConnection(String username, String password) {
        throw new UnsupportedOperationException("Cannot get connection using different username and password instead of default");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        requireInitialization();
        return dataSource.unwrap(iface);
    }

    private void requireInitialization() {
        if (!initialized) {
            throw new DbException(DB_INITIALIZATION_ERROR_TEXT);
        }
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        requireInitialization();
        return dataSource.isWrapperFor(iface);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        requireInitialization();
        return this.dataSource.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        requireInitialization();
        this.dataSource.setLogWriter(out);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        requireInitialization();
        return this.dataSource.getLoginTimeout();
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        requireInitialization();
        this.dataSource.setLoginTimeout(seconds);
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        requireInitialization();
        return this.dataSource.getParentLogger();
    }

    public HikariPoolMXBean getJmxBean() {
        return jmxBean;
    }

    private void validateDbParams(String dbParams) {
        if (Objects.nonNull(dbParams)) {
            if (dbParams.contains(MVCC)) {
                final String message = String.format(
                    "%s is not supported in the dbParams or dbUrl properties.",
                    MVCC
                );
                log.error(message);
                throw new IllegalArgumentException(
                    message
                );
            }
            if (dbParams.contains(MV_STORE + "=FALSE")) {
                final String message = String.format(
                    "%s should always be TRUE.",
                    MV_STORE
                );
                log.error(message);
                throw new IllegalArgumentException(
                    message
                );
            }
        }
    }

    /**
     * Constructor creates internal DataSource.
     *
     * @param dbVersion database version related information
     */
    public Jdbi initWithJdbi(DbVersion dbVersion) {
        initDatasource(dbVersion);
        Jdbi jdbi = initJdbi();
        setInitialzed();
        return jdbi;
    }

    private void setInitialzed() {
        initialized = true;
        shutdown = false;
    }

    private void initDatasource(DbVersion dbVersion) {
        log.debug("Database jdbc url set to {} username {}", dbUrl, dbUsername);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbUrl);
        config.setUsername(dbUsername);
        config.setPassword(dbPassword);
        config.setMaximumPoolSize(maxConnections);
        config.setConnectionTimeout(TimeUnit.SECONDS.toMillis(loginTimeout));
        config.setLeakDetectionThreshold(60_000 * 5); // 5 minutes
        config.setIdleTimeout(60_000 * 20); // 20 minutes in milliseconds
        config.setPoolName(shardId);
        log.debug("Creating DataSource pool '{}', path = {}", shardId, dbUrl);
        dataSource = new HikariDataSource(config);
        jmxBean = dataSource.getHikariPoolMXBean();
/*
        dataSource = JdbcConnectionPool.create(dbUrl, dbUsername, dbPassword);
        dataSource.setMaxConnections(maxConnections);
        dataSource.setLoginTimeout(loginTimeout);
*/
        log.debug("Attempting to create DataSource by path = {}...", dbUrl);
        try (Connection con = dataSource.getConnection();
             Statement stmt = con.createStatement()) {
            stmt.executeUpdate("SET DEFAULT_LOCK_TIMEOUT " + defaultLockTimeout);
            stmt.executeUpdate("SET MAX_MEMORY_ROWS " + maxMemoryRows);
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
        log.debug("Before starting Db schema init {}...", dbVersion);
        dbVersion.init(this);
    }

    private Jdbi initJdbi() {
        log.debug("Attempting to create Jdbi instance...");
        Jdbi jdbi = Jdbi.create(dataSource);
        jdbi.installPlugin(new SqlObjectPlugin());
        jdbi.installPlugin(new H2DatabasePlugin());
        jdbi.registerArgument(new BigIntegerArgumentFactory());
        jdbi.registerArgument(new DexCurrenciesFactory());
        jdbi.registerArgument(new OrderTypeFactory());
        jdbi.registerArgument(new OrderStatusFactory());
        jdbi.registerArgument(new LongArrayArgumentFactory());
        jdbi.registerArrayType(long.class, "generatorIds");
        jdbi.registerArgument(new ShardStateFactory());

        log.debug("Attempting to open Jdbi handler to database..");
        try (Handle handle = jdbi.open()) {
            @DatabaseSpecificDml(DmlMarker.DUAL_TABLE_USE)
            Optional<Integer> result = handle.createQuery("select 1 from dual;")
                .mapTo(Integer.class).findOne();
            log.debug("check SQL result ? = {}", result);
        } catch (ConnectionException e) {
            log.error("Error on opening database connection", e);
            throw e;
        }
        return jdbi;
    }

    public void init(DbVersion dbVersion) {
        initDatasource(dbVersion);
        setInitialzed();
    }

    public void update(DbVersion dbVersion) {
        dbVersion.init(this);
    }

    public void shutdown() {
        long start = System.currentTimeMillis();
        if (!initialized) {
            return;
        }
        try {
            Connection con = dataSource.getConnection();
            Statement stmt = con.createStatement();
            stmt.execute("SHUTDOWN COMPACT");
            shutdown = true;
            initialized = false;
            dataSource.close();
//            dataSource.dispose();
            log.debug("Db shutdown completed in {} ms for '{}'", System.currentTimeMillis() - start, this.dbUrl);
        } catch (JdbcSQLException e) {
            log.info(e.toString());
        } catch (SQLException e) {
            log.info(e.toString(), e);
        }
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public void analyzeTables() {
        try (Connection con = dataSource.getConnection();
             Statement stmt = con.createStatement()) {
            log.debug("Start DB 'ANALYZE' on {}", con.getMetaData());
            stmt.execute("ANALYZE");
            log.debug("FINISHED DB 'ANALYZE' on {}", con.getMetaData());
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection con = getPooledConnection();
        con.setAutoCommit(true);
        return con;
    }

    protected Connection getPooledConnection() throws SQLException {
        Connection con = dataSource.getConnection();
        if (jmxBean != null) {
            if (log.isDebugEnabled()) {
                int totalConnections = jmxBean.getTotalConnections();
                int idleConnections = jmxBean.getIdleConnections();

                if (idleConnections <= totalConnections * 0.1) {
                    int activeConnections = jmxBean.getActiveConnections();
                    int threadAwaitingConnections = jmxBean.getThreadsAwaitingConnection();
                    log.debug("Total/Active/Idle connections in Pool '{}'/'{}'/'{}', threadsAwaitPool=[{}], {} Tread: {}",
                        totalConnections,
                        activeConnections,
                        idleConnections,
                        threadAwaitingConnections,
                        dataSource.getPoolName(), // show main or shard db
                        Thread.currentThread().getName());
                }
            }
        }
        return con;
    }

    public String getUrl() {
        return dbUrl;
    }

    @Override
    public String toString() {
        return "DataSourceWrapper{" +
            "dbUrl='" + dbUrl + '\'' +
            ", initialized=" + initialized +
            ", shutdown=" + shutdown +
            '}';
    }
}
