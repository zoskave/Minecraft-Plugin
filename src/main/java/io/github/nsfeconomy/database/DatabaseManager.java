package io.github.nsfeconomy.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.nsfeconomy.NSFEconomy;

import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Manages database connections and operations for the NSF Economy plugin.
 * Supports both SQLite and MySQL databases.
 */
public class DatabaseManager {

    private final NSFEconomy plugin;
    private HikariDataSource dataSource;
    private boolean isMySQL;

    public DatabaseManager(NSFEconomy plugin) {
        this.plugin = plugin;
    }

    /**
     * Initialize the database connection and create tables
     */
    public boolean initialize() {
        String dbType = plugin.getConfig().getString("database.type", "sqlite").toLowerCase();
        isMySQL = dbType.equals("mysql");

        try {
            if (isMySQL) {
                initializeMySQL();
            } else {
                initializeSQLite();
            }
            
            createTables();
            plugin.getLogger().info("Database initialized successfully (" + dbType.toUpperCase() + ")");
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize database", e);
            return false;
        }
    }

    /**
     * Initialize SQLite database
     */
    private void initializeSQLite() throws SQLException {
        String fileName = plugin.getConfig().getString("database.sqlite.file", "data.db");
        File dbFile = new File(plugin.getDataFolder(), fileName);
        
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        config.setMaximumPoolSize(1); // SQLite only supports single connection
        config.setConnectionTestQuery("SELECT 1");
        config.setPoolName("NSFEconomy-SQLite");

        dataSource = new HikariDataSource(config);
    }

    /**
     * Initialize MySQL database
     */
    private void initializeMySQL() {
        String host = plugin.getConfig().getString("database.mysql.host", "localhost");
        int port = plugin.getConfig().getInt("database.mysql.port", 3306);
        String database = plugin.getConfig().getString("database.mysql.database", "nsf_economy");
        String username = plugin.getConfig().getString("database.mysql.username", "minecraft");
        String password = plugin.getConfig().getString("database.mysql.password", "changeme");

        HikariConfig config = new HikariConfig();
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + 
                         "?useSSL=false&allowPublicKeyRetrieval=true&autoReconnect=true");
        config.setUsername(username);
        config.setPassword(password);
        
        // Pool settings
        config.setMaximumPoolSize(plugin.getConfig().getInt("database.mysql.pool.max_size", 10));
        config.setMinimumIdle(plugin.getConfig().getInt("database.mysql.pool.min_idle", 2));
        config.setConnectionTimeout(plugin.getConfig().getLong("database.mysql.pool.connection_timeout", 30000));
        config.setIdleTimeout(plugin.getConfig().getLong("database.mysql.pool.idle_timeout", 600000));
        config.setMaxLifetime(plugin.getConfig().getLong("database.mysql.pool.max_lifetime", 1800000));
        config.setPoolName("NSFEconomy-MySQL");

        dataSource = new HikariDataSource(config);
    }

    /**
     * Create all required database tables
     */
    private void createTables() throws SQLException {
        try (Connection conn = getConnection()) {
            // Bank locations table
            execute(conn, """
                CREATE TABLE IF NOT EXISTS bank_locations (
                    id INTEGER PRIMARY KEY %s,
                    name VARCHAR(64) NOT NULL UNIQUE,
                    type VARCHAR(16) NOT NULL,
                    world VARCHAR(64) NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    radius INTEGER NOT NULL DEFAULT 10,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """.formatted(isMySQL ? "AUTO_INCREMENT" : "AUTOINCREMENT"));

            // Currency ledger table
            execute(conn, """
                CREATE TABLE IF NOT EXISTS currency_ledger (
                    serial VARCHAR(36) PRIMARY KEY,
                    denomination INTEGER NOT NULL,
                    issued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    issued_to VARCHAR(36),
                    status VARCHAR(20) NOT NULL DEFAULT 'circulating',
                    status_changed_at TIMESTAMP,
                    status_changed_by VARCHAR(36)
                )
                """);

            // Reserve table (singleton)
            execute(conn, """
                CREATE TABLE IF NOT EXISTS reserve (
                    id INTEGER PRIMARY KEY,
                    nether_stars BIGINT NOT NULL DEFAULT 0,
                    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
            
            // Initialize reserve if empty
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO reserve (id, nether_stars) VALUES (1, 0)")) {
                ps.executeUpdate();
            } catch (SQLException e) {
                // MySQL syntax
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT IGNORE INTO reserve (id, nether_stars) VALUES (1, 0)")) {
                    ps.executeUpdate();
                }
            }

            // Transactions log table
            execute(conn, """
                CREATE TABLE IF NOT EXISTS transactions (
                    id INTEGER PRIMARY KEY %s,
                    type VARCHAR(32) NOT NULL,
                    player VARCHAR(36) NOT NULL,
                    amount_f DECIMAL(15,2),
                    amount_stars BIGINT,
                    details TEXT,
                    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """.formatted(isMySQL ? "AUTO_INCREMENT" : "AUTOINCREMENT"));

            // Tax obligations table
            execute(conn, """
                CREATE TABLE IF NOT EXISTS tax_obligations (
                    id INTEGER PRIMARY KEY %s,
                    player VARCHAR(36) NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    amount DECIMAL(15,2) NOT NULL,
                    due_date TIMESTAMP NOT NULL,
                    paid INTEGER NOT NULL DEFAULT 0,
                    paid_at TIMESTAMP
                )
                """.formatted(isMySQL ? "AUTO_INCREMENT" : "AUTOINCREMENT"));

            // Bounties table
            execute(conn, """
                CREATE TABLE IF NOT EXISTS bounties (
                    id INTEGER PRIMARY KEY %s,
                    description TEXT NOT NULL,
                    reward DECIMAL(15,2) NOT NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'open',
                    created_by VARCHAR(36) NOT NULL,
                    claimed_by VARCHAR(36),
                    deadline TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """.formatted(isMySQL ? "AUTO_INCREMENT" : "AUTOINCREMENT"));

            // Permits table
            execute(conn, """
                CREATE TABLE IF NOT EXISTS permits (
                    id INTEGER PRIMARY KEY %s,
                    player VARCHAR(36) NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    expires_at TIMESTAMP NOT NULL,
                    purchased_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """.formatted(isMySQL ? "AUTO_INCREMENT" : "AUTOINCREMENT"));

            // Withdrawal queue table
            execute(conn, """
                CREATE TABLE IF NOT EXISTS withdrawal_queue (
                    id INTEGER PRIMARY KEY %s,
                    player VARCHAR(36) NOT NULL,
                    amount DECIMAL(15,2) NOT NULL,
                    requested_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    processed INTEGER NOT NULL DEFAULT 0
                )
                """.formatted(isMySQL ? "AUTO_INCREMENT" : "AUTOINCREMENT"));

            // Player data table (for virtual balance tracking via Vault)
            execute(conn, """
                CREATE TABLE IF NOT EXISTS player_data (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(16),
                    virtual_balance DECIMAL(15,2) NOT NULL DEFAULT 0,
                    last_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // Create indexes
            execute(conn, "CREATE INDEX IF NOT EXISTS idx_ledger_status ON currency_ledger(status)");
            execute(conn, "CREATE INDEX IF NOT EXISTS idx_transactions_player ON transactions(player)");
            execute(conn, "CREATE INDEX IF NOT EXISTS idx_tax_player ON tax_obligations(player)");
            execute(conn, "CREATE INDEX IF NOT EXISTS idx_permits_player ON permits(player)");
        }
    }

    /**
     * Execute a SQL statement
     */
    private void execute(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * Get a database connection from the pool
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Close the database connection pool
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    /**
     * Check if using MySQL
     */
    public boolean isMySQL() {
        return isMySQL;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Currency Ledger Operations
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Record a new note in the ledger
     */
    public boolean recordNote(UUID serial, int denomination, UUID issuedTo) {
        String sql = "INSERT INTO currency_ledger (serial, denomination, issued_to, status) VALUES (?, ?, ?, 'circulating')";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serial.toString());
            ps.setInt(2, denomination);
            ps.setString(3, issuedTo != null ? issuedTo.toString() : null);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to record note", e);
            return false;
        }
    }

    /**
     * Check if a serial number is valid and circulating
     */
    public boolean isNoteValid(UUID serial) {
        String sql = "SELECT status FROM currency_ledger WHERE serial = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serial.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return "circulating".equals(rs.getString("status"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to validate note", e);
        }
        return false;
    }

    /**
     * Get the denomination of a note
     */
    public int getNoteDenomination(UUID serial) {
        String sql = "SELECT denomination FROM currency_ledger WHERE serial = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serial.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("denomination");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get note denomination", e);
        }
        return -1;
    }

    /**
     * Mark a note as redeemed
     */
    public boolean redeemNote(UUID serial, UUID redeemedBy) {
        String sql = "UPDATE currency_ledger SET status = 'redeemed', status_changed_at = CURRENT_TIMESTAMP, status_changed_by = ? WHERE serial = ? AND status = 'circulating'";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, redeemedBy.toString());
            ps.setString(2, serial.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to redeem note", e);
            return false;
        }
    }

    /**
     * Get total circulating currency by denomination
     */
    public long getCirculatingCount(int denomination) {
        String sql = "SELECT COUNT(*) FROM currency_ledger WHERE denomination = ? AND status = 'circulating'";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, denomination);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get circulating count", e);
        }
        return 0;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Reserve Operations
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Get current reserve amount
     */
    public long getReserve() {
        String sql = "SELECT nether_stars FROM reserve WHERE id = 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong("nether_stars");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get reserve", e);
        }
        return 0;
    }

    /**
     * Add to reserve
     */
    public boolean addToReserve(long amount) {
        String sql = "UPDATE reserve SET nether_stars = nether_stars + ?, last_updated = CURRENT_TIMESTAMP WHERE id = 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to add to reserve", e);
            return false;
        }
    }

    /**
     * Remove from reserve
     */
    public boolean removeFromReserve(long amount) {
        String sql = "UPDATE reserve SET nether_stars = nether_stars - ?, last_updated = CURRENT_TIMESTAMP WHERE id = 1 AND nether_stars >= ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setLong(2, amount);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to remove from reserve", e);
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Transaction Logging
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Log a transaction
     */
    public void logTransaction(String type, UUID player, double amountF, long amountStars, String details) {
        String sql = "INSERT INTO transactions (type, player, amount_f, amount_stars, details) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setString(2, player.toString());
            ps.setDouble(3, amountF);
            ps.setLong(4, amountStars);
            ps.setString(5, details);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to log transaction", e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Player Data Operations (for Vault integration)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Get player's virtual balance
     */
    public double getVirtualBalance(UUID uuid) {
        String sql = "SELECT virtual_balance FROM player_data WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("virtual_balance");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get virtual balance", e);
        }
        return 0;
    }

    /**
     * Set player's virtual balance
     */
    public boolean setVirtualBalance(UUID uuid, String username, double balance) {
        String sql = isMySQL
            ? "INSERT INTO player_data (uuid, username, virtual_balance) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE virtual_balance = ?, username = ?, last_seen = CURRENT_TIMESTAMP"
            : "INSERT OR REPLACE INTO player_data (uuid, username, virtual_balance, last_seen) VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.setDouble(3, balance);
            if (isMySQL) {
                ps.setDouble(4, balance);
                ps.setString(5, username);
            }
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to set virtual balance", e);
            return false;
        }
    }

    /**
     * Update player's virtual balance by adding/subtracting amount
     */
    public boolean updateVirtualBalance(UUID uuid, double amount) {
        // First ensure account exists
        createPlayerAccount(uuid, uuid.toString().substring(0, 8));
        
        String sql = isMySQL
            ? "UPDATE player_data SET virtual_balance = virtual_balance + ?, last_seen = CURRENT_TIMESTAMP WHERE uuid = ?"
            : "UPDATE player_data SET virtual_balance = virtual_balance + ?, last_seen = CURRENT_TIMESTAMP WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, amount);
            ps.setString(2, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to update virtual balance", e);
            return false;
        }
    }

    /**
     * Create player account if not exists
     */
    public boolean createPlayerAccount(UUID uuid, String username) {
        String sql = isMySQL
            ? "INSERT IGNORE INTO player_data (uuid, username, virtual_balance) VALUES (?, ?, 0)"
            : "INSERT OR IGNORE INTO player_data (uuid, username, virtual_balance) VALUES (?, ?, 0)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create player account", e);
            return false;
        }
    }

    /**
     * Check if player has account
     */
    public boolean hasPlayerAccount(UUID uuid) {
        String sql = "SELECT 1 FROM player_data WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to check player account", e);
        }
        return false;
    }
}
