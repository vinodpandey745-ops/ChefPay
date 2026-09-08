package com.chefpay.server.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev/small-restaurant SQLite profile only. Fixes a real, reproducible startup crash: Hibernate's
 * {@code ddl-auto: update} schema-diffing introspects the ENTIRE existing schema on every startup,
 * and the specific way it does so triggers a genuine, long-standing bug in the xerial sqlite-jdbc
 * driver - see xerial/sqlite-jdbc issue #487 ("JDBC3DatabaseMetaData > getColumns may cause
 * [SQLITE_ERROR] (too many terms in compound SELECT)").
 *
 * <p><b>Root cause, confirmed precisely (this round) rather than assumed:</b> Hibernate 6's {@code
 * AbstractInformationExtractorImpl#populateTablesWithColumns} calls the JDBC {@code
 * DatabaseMetaData#getColumns(catalog, schema, tableNamePattern, columnNamePattern)} method with
 * {@code tableNamePattern = null} - i.e. "give me every column of every table in one call" - every
 * single startup. SQLite has no real {@code information_schema}, so xerial's driver synthesizes
 * this by running {@code PRAGMA table_xinfo(...)} per table and unioning the results into ONE
 * compound {@code SELECT ... UNION ALL SELECT ...} query with exactly one term per column, summed
 * across EVERY table the pattern matches - here, every table in the schema at once. SQLite's own
 * native library caps a compound SELECT at 500 terms ({@code SQLITE_LIMIT_COMPOUND_SELECT}), and
 * this project's ~46 entities collectively have well over 500 columns (the {@code Restaurant}
 * entity alone has ~65), so this one JDBC call was guaranteed to exceed that ceiling regardless of
 * how large the schema ever gets from here - this was never a "the schema grew too large" problem
 * with a numeric threshold to tune, it was baked in from the day total-columns-across-the-schema
 * crossed 500.
 *
 * <p><b>Why the previous fix in this class did not work (found and corrected this round):</b> an
 * earlier version of this class tried to raise {@code SQLITE_LIMIT_COMPOUND_SELECT} on each new
 * connection via {@code SQLiteConnection#setLimit}. That compiles and runs without error, which is
 * exactly what made it look like a fix - but {@code sqlite3_limit()} (the underlying native call)
 * can only ever request a value UP TO a hard, compile-time ceiling baked into the SQLite library
 * itself; it can never raise the limit past that ceiling. For the standard SQLite build bundled in
 * the xerial driver, that hard ceiling for compound-SELECT terms already equals the default (500) -
 * confirmed empirically (Python's {@code sqlite3} module, linked against a standard SQLite build,
 * silently clamps any requested value back down to 500 no matter how high you ask). So the previous
 * fix's {@code setLimit(..., 100_000)} call was a complete no-op every single time - the limit was
 * 500 before the call and 500 after it. It never had any chance of working, on this or any
 * standard SQLite build, regardless of what value was requested.
 *
 * <p><b>The actual fix:</b> since the limit itself cannot move, the only real fix is to never issue
 * a {@code getColumns()} call wide enough to hit it. This class now wraps every physical JDBC
 * {@link Connection} so that {@link Connection#getMetaData()} returns a {@link DatabaseMetaData}
 * proxy which intercepts exactly one method: when {@code getColumns(...)} is called with a null (or
 * schema-wide wildcard) table pattern - which is precisely the case that trips this driver bug -
 * it transparently fans that single call out into one real {@code getColumns()} call PER TABLE
 * (each one safely bounded by that single table's own column count, nowhere near 500 for anything
 * in this schema) and stitches the individual result sets back together into one combined {@link
 * ResultSet}, indistinguishable to Hibernate from the single wide call it asked for. Every other
 * {@code DatabaseMetaData}/{@code Connection} method passes straight through unchanged. This is the
 * same workaround xerial/sqlite-jdbc issue #487's own reporter described using (an ugly-but-correct
 * "split it yourself and combine the results" approach) - there is no clean driver-level or PRAGMA
 * fix available; the issue itself remains unresolved upstream as of this project's dependency
 * version ({@code 3.46.1.3}).
 *
 * <p>This is a schema-SHAPE problem (how many tables/columns exist), not a data-VOLUME problem -
 * deleting old rows (see {@code com.chefpay.server.retention.DataRetentionService}) does not fix
 * this, since the number of tables/columns doesn't shrink just because a table has fewer rows in it.
 *
 * <p>This preserves the existing {@code ddl-auto: update} behavior (and every byte of locally-stored
 * data, across restarts) rather than switching to a schema-wiping {@code create}/{@code create-drop}
 * strategy, which would be unacceptable for a database this project also uses as small-restaurants'
 * real production store (requirement Section 5), not just a throwaway dev sandbox.
 *
 * <p>Providing this {@code @Bean DataSource} here replaces Spring Boot's own autoconfigured
 * DataSource for the 'dev' profile only - Spring Boot's {@code DataSourceAutoConfiguration} backs
 * off its own {@code DataSource} bean the moment any other one is present, so the 'postgres'/'mysql'
 * profiles are completely untouched and keep using Spring Boot's normal Hikari + driver-class-name
 * autoconfiguration - this SQLite-specific bug does not exist on those databases at all (Postgres/
 * MySQL have no analogous "compound SELECT term" ceiling here, and their JDBC drivers implement
 * {@code getColumns()} against a real {@code information_schema}, not a synthesized UNION query).
 */
@Configuration
@Profile("dev")
@Slf4j
public class SqliteDataSourceConfig {

    @Bean
    public DataSource dataSource(DataSourceProperties properties) {
        String url = properties.getUrl();
        log.info("ChefPay: configuring the SQLite datasource with per-table column-metadata "
                + "splitting, working around xerial/sqlite-jdbc issue #487 (a schema-wide "
                + "getColumns() call exceeds SQLite's fixed 500-term compound-SELECT ceiling once "
                + "this project's total column count across all tables passes 500).");

        // The full JDBC URL - including this project's existing "?busy_timeout=30000" query
        // parameter - is preserved exactly as-is; SQLiteDataSource parses the same URL-level PRAGMA
        // options this app already relies on (see application.yml's own comment on why busy_timeout
        // is set, and why WAL mode is deliberately NOT used).
        SQLiteDataSource realDataSource = new SQLiteDataSource(new SQLiteConfig());
        realDataSource.setUrl(url);

        ColumnSplittingDataSource wrapped = new ColumnSplittingDataSource(realDataSource);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDataSource(wrapped);
        // Pool size: left at Hikari's own default (10), not restricted to 1. Round 27 (pre-
        // deployment audit) correction: this comment used to justify that by pointing at
        // NumberGeneratorService's REQUIRES_NEW pattern needing 2 connections at once - that
        // pattern no longer exists (NumberGeneratorService and AuditService were both changed to
        // REQUIRED specifically to remove the cross-connection SQLite deadlock it caused; see
        // NumberGeneratorService's own javadoc). The real, current reason to keep the pool at its
        // normal size: this server handles genuinely concurrent HTTP requests from multiple POS
        // terminals at once, each on its own thread needing its own connection - that's an ordinary
        // web-app concurrency need, unrelated to any single request needing more than one
        // connection. SQLite's own single-writer serialization (via busy_timeout, not the pool
        // size) is still what actually orders concurrent writes safely.
        return new HikariDataSource(hikariConfig);
    }

    /** Thin delegating wrapper around the real {@link SQLiteDataSource} that wraps every physical
     * {@link Connection} it hands out in {@link #wrapConnection}, so every connection's {@code
     * getMetaData()} returns the column-splitting proxy described in this class's javadoc. */
    private static final class ColumnSplittingDataSource implements DataSource {

        private final SQLiteDataSource delegate;

        ColumnSplittingDataSource(SQLiteDataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrapConnection(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrapConnection(delegate.getConnection(username, password));
        }

        private Connection wrapConnection(Connection real) {
            if (real == null) {
                return null;
            }
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    new ConnectionHandler(real));
        }

        @Override
        public java.io.PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }

    /** Passes every {@link Connection} method straight through to the real connection unchanged,
     * except {@code getMetaData()}, which gets wrapped in the same style via {@link
     * DatabaseMetaDataHandler}. */
    private record ConnectionHandler(Connection real) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getMetaData".equals(method.getName()) && (args == null || args.length == 0)) {
                DatabaseMetaData realMetaData = real.getMetaData();
                return Proxy.newProxyInstance(
                        DatabaseMetaData.class.getClassLoader(),
                        new Class<?>[] {DatabaseMetaData.class},
                        new DatabaseMetaDataHandler(real, realMetaData));
            }
            return invokeReal(real, method, args);
        }
    }

    /** Passes every {@link DatabaseMetaData} method straight through unchanged, except {@code
     * getColumns(catalog, schemaPattern, tableNamePattern, columnNamePattern)} when {@code
     * tableNamePattern} is null or a schema-wide wildcard ({@code null} or {@code "%"}) - exactly
     * the call shape that trips xerial/sqlite-jdbc issue #487. In that one case, this fans the call
     * out into one real {@code getColumns()} call per table (each individually far below SQLite's
     * 500-term compound-SELECT ceiling, since no single table in this schema has anywhere near 500
     * columns) and merges the results via {@link ConcatenatedResultSetHandler}. A call that already
     * names a specific table is left completely untouched - it was never the problem. */
    private record DatabaseMetaDataHandler(Connection connection, DatabaseMetaData real) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getColumns".equals(method.getName()) && args != null && args.length == 4) {
                String tableNamePattern = (String) args[2];
                if (tableNamePattern == null || "%".equals(tableNamePattern)) {
                    return splitByTable((String) args[0], (String) args[1], (String) args[3]);
                }
            }
            return invokeReal(real, method, args);
        }

        private ResultSet splitByTable(String catalog, String schemaPattern, String columnNamePattern)
                throws SQLException {
            List<String> tableNames = listTableNames(catalog, schemaPattern);

            /* Round 26 fix: the very first production run of this class's Round 25 fix crashed with
             * a NullPointerException from inside java.lang.reflect.Method.invoke - traced to
             * ConcatenatedResultSetHandler#currentOrFirst() returning null, which only happens when
             * this method's own "one table name per row" enumeration above came back completely
             * empty. Hibernate derives the catalog/schemaPattern arguments it passes into getColumns()
             * from Connection#getCatalog()/#getSchema() (see AbstractInformationExtractorImpl -
             * confirmed by fetching Hibernate 6.5's actual source this round), NOT from anything this
             * project configures - and the xerial sqlite-jdbc driver's own getTables() has a
             * long-documented history of being inconsistent about whether it honors a non-null
             * catalog/schema filter at all for a database engine that has no real catalog/schema
             * concept to begin with. Rather than gamble on exactly reproducing whatever mismatch
             * caused zero rows in production (unreproducible here - this sandbox cannot run Maven or
             * a real JVM against this project at all, so nothing below can be compiled/run locally;
             * every line was re-read in full after editing instead), this retries with no
             * catalog/schema filter at all - which is always safe for SQLite, a single-schema engine
             * where "every table, unfiltered" is never wrong - the moment the caller-supplied filter
             * yields nothing. */
            if (tableNames.isEmpty() && (catalog != null || schemaPattern != null)) {
                log.warn("ChefPay: getTables() returned zero tables for catalog={}, schema={} while "
                        + "splitting a schema-wide getColumns() call - retrying with no catalog/schema "
                        + "filter, since SQLite has no real catalog/schema concept for that filter to "
                        + "meaningfully narrow.", catalog, schemaPattern);
                tableNames = listTableNames(null, null);
            }

            // Round 27 (pre-deployment audit) fix: if getColumns() throws partway through this
            // ~46-table loop (e.g. a transient SQLITE_BUSY on one table), every ResultSet already
            // opened for the tables processed so far was previously left unclosed - the exception
            // propagated straight out with those native statement handles leaked, with no
            // finalizer guarantee to ever reclaim them. Close whatever was already opened before
            // rethrowing.
            List<ResultSet> perTable = new ArrayList<>(Math.max(tableNames.size(), 1));
            try {
                for (String tableName : tableNames) {
                    perTable.add(real.getColumns(catalog, schemaPattern, tableName, columnNamePattern));
                }
            } catch (SQLException ex) {
                for (ResultSet opened : perTable) {
                    try {
                        opened.close();
                    } catch (SQLException closeFailure) {
                        ex.addSuppressed(closeFailure);
                    }
                }
                throw ex;
            }

            /* Belt-and-suspenders regardless of the above: ConcatenatedResultSetHandler must NEVER
             * be handed an empty delegate list - Hikari's own ResultSet-proxying wrapper calls
             * getStatement() on whatever ResultSet getColumns() returns before any next()/row is
             * ever touched, and there is no such thing as "no delegate at all" to safely answer that
             * (or any other) call with. A getColumns() lookup for a table name that cannot exist
             * always returns a real, valid, driver-backed empty ResultSet per the JDBC contract
             * (zero rows, but every method - getStatement(), getMetaData(), close(), ... - answers
             * correctly), which is exactly what's needed here as a last-resort single delegate. */
            if (perTable.isEmpty()) {
                perTable.add(real.getColumns(catalog, schemaPattern,
                        "chefpay_no_such_table__round26_safety_net", columnNamePattern));
            }

            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[] {ResultSet.class},
                    new ConcatenatedResultSetHandler(perTable));
        }

        private List<String> listTableNames(String catalog, String schemaPattern) throws SQLException {
            List<String> tableNames = new ArrayList<>();
            try (ResultSet tables = real.getTables(catalog, schemaPattern, null, new String[] {"TABLE"})) {
                while (tables.next()) {
                    tableNames.add(tables.getString("TABLE_NAME"));
                }
            }
            return tableNames;
        }
    }

    /** Makes a list of same-shaped {@link ResultSet}s (one per table, from {@link
     * DatabaseMetaDataHandler#splitByTable}) look like a single {@link ResultSet} to the caller -
     * {@code next()} advances within the current delegate and transparently moves on to the next
     * one once the current is exhausted; every other method delegates to whichever {@link
     * ResultSet} is current at the time of the call, which is always correct for the standard
     * JDBC usage pattern of "call {@code next()}, then read columns off the row it just moved to". */
    private static final class ConcatenatedResultSetHandler implements InvocationHandler {

        private final List<ResultSet> delegates;
        private int index = -1;

        ConcatenatedResultSetHandler(List<ResultSet> delegates) {
            this.delegates = delegates;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "next":
                    return advance();
                case "close":
                    SQLException first = null;
                    for (ResultSet rs : delegates) {
                        try {
                            rs.close();
                        } catch (SQLException ex) {
                            if (first == null) {
                                first = ex;
                            }
                        }
                    }
                    if (first != null) {
                        throw first;
                    }
                    return null;
                default:
                    ResultSet current = currentOrFirst();
                    if (current == null) {
                        // Should be unreachable after the Round 26 fix in splitByTable(), which
                        // guarantees at least one delegate - but a raw NPE from inside
                        // Method.invoke() (as happened before that fix) is nearly undiagnosable from
                        // the stack trace alone. Fail loudly and specifically instead, if this is
                        // ever somehow reached again.
                        throw new SQLException("ChefPay: ConcatenatedResultSetHandler has no "
                                + "underlying ResultSet to delegate " + method.getName() + "() to - "
                                + "this indicates splitByTable() built an empty delegate list, which "
                                + "should no longer be possible after the Round 26 fix.");
                    }
                    return invokeReal(current, method, args);
            }
        }

        private boolean advance() throws SQLException {
            if (index < 0) {
                index = 0;
            }
            while (index < delegates.size()) {
                if (delegates.get(index).next()) {
                    return true;
                }
                index++;
            }
            return false;
        }

        /** {@code getMetaData()}/similar calls can legally happen before the first {@code next()} -
         * every per-table result set here has an identical column shape (they're all "one row of
         * column-metadata" results from the same driver method), so any of them answers those calls
         * identically; falling back to the first one when nothing has been iterated yet is safe. */
        private ResultSet currentOrFirst() {
            if (index >= 0 && index < delegates.size()) {
                return delegates.get(index);
            }
            return delegates.isEmpty() ? null : delegates.get(0);
        }
    }

    private static Object invokeReal(Object real, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause() != null ? ex.getCause() : ex;
        }
    }
}
