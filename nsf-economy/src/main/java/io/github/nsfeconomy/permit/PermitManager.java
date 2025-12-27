package io.github.nsfeconomy.permit;

import io.github.nsfeconomy.NSFEconomy;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages dimension access permits
 */
public class PermitManager {

    private final NSFEconomy plugin;

    public PermitManager(NSFEconomy plugin) {
        this.plugin = plugin;
    }

    /**
     * Check if a player has a valid permit for a dimension
     */
    public boolean hasValidPermit(UUID playerId, String dimension) {
        String sql = "SELECT id FROM permits WHERE player_id = ? AND dimension = ? AND " +
                     "expires_at > ? AND status = 'active'";
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, playerId.toString());
            stmt.setString(2, dimension.toLowerCase());
            stmt.setString(3, LocalDateTime.now().toString());
            
            ResultSet rs = stmt.executeQuery();
            return rs.next();
            
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to check permit: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get a player's permit for a specific dimension
     */
    public Permit getPermit(UUID playerId, String dimension) {
        String sql = "SELECT * FROM permits WHERE player_id = ? AND dimension = ? AND status = 'active' " +
                     "ORDER BY expires_at DESC LIMIT 1";
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, playerId.toString());
            stmt.setString(2, dimension.toLowerCase());
            
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return new Permit(
                    rs.getInt("id"),
                    UUID.fromString(rs.getString("player_id")),
                    rs.getString("dimension"),
                    LocalDateTime.parse(rs.getString("purchased_at")),
                    LocalDateTime.parse(rs.getString("expires_at")),
                    rs.getDouble("price_paid"),
                    rs.getString("status")
                );
            }
            
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get permit: " + e.getMessage());
        }
        
        return null;
    }

    /**
     * Get all active permits for a player
     */
    public List<Permit> getPlayerPermits(UUID playerId) {
        List<Permit> permits = new ArrayList<>();
        String sql = "SELECT * FROM permits WHERE player_id = ? AND status = 'active' AND expires_at > ?";
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, playerId.toString());
            stmt.setString(2, LocalDateTime.now().toString());
            
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                permits.add(new Permit(
                    rs.getInt("id"),
                    UUID.fromString(rs.getString("player_id")),
                    rs.getString("dimension"),
                    LocalDateTime.parse(rs.getString("purchased_at")),
                    LocalDateTime.parse(rs.getString("expires_at")),
                    rs.getDouble("price_paid"),
                    rs.getString("status")
                ));
            }
            
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get player permits: " + e.getMessage());
        }
        
        return permits;
    }

    /**
     * Purchase a permit for a dimension
     */
    public PermitResult purchasePermit(UUID playerId, String dimension) {
        // Check if permit already exists and is valid
        if (hasValidPermit(playerId, dimension)) {
            return new PermitResult(false, "You already have an active permit for " + dimension, -1);
        }

        // Get permit configuration
        String configPath = "permits." + dimension.toLowerCase();
        if (!plugin.getConfig().contains(configPath)) {
            return new PermitResult(false, "Unknown dimension: " + dimension, -1);
        }

        double price = plugin.getConfig().getDouble(configPath + ".price", 10.0);
        int durationDays = plugin.getConfig().getInt(configPath + ".duration_days", 30);

        // TODO: Verify player has enough F-notes at bank and deduct them

        // Create permit
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expires = now.plusDays(durationDays);

        String sql = "INSERT INTO permits (player_id, dimension, purchased_at, expires_at, price_paid, status) " +
                     "VALUES (?, ?, ?, ?, ?, 'active')";
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            
            stmt.setString(1, playerId.toString());
            stmt.setString(2, dimension.toLowerCase());
            stmt.setString(3, now.toString());
            stmt.setString(4, expires.toString());
            stmt.setDouble(5, price);
            
            stmt.executeUpdate();
            
            ResultSet rs = stmt.getGeneratedKeys();
            int permitId = rs.next() ? rs.getInt(1) : -1;
            
            // Log transaction
            plugin.getDatabaseManager().logTransaction(
                playerId, "permit_purchase",
                "Purchased " + dimension + " permit for " + durationDays + " days",
                -price, null
            );
            
            return new PermitResult(true, "Permit purchased successfully!", permitId);
            
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to purchase permit: " + e.getMessage());
            return new PermitResult(false, "Database error", -1);
        }
    }

    /**
     * Extend an existing permit
     */
    public PermitResult extendPermit(UUID playerId, String dimension) {
        Permit existing = getPermit(playerId, dimension);
        
        String configPath = "permits." + dimension.toLowerCase();
        double price = plugin.getConfig().getDouble(configPath + ".price", 10.0);
        int durationDays = plugin.getConfig().getInt(configPath + ".duration_days", 30);

        LocalDateTime newExpiry;
        if (existing != null && existing.getExpiresAt().isAfter(LocalDateTime.now())) {
            // Extend from current expiry
            newExpiry = existing.getExpiresAt().plusDays(durationDays);
        } else {
            // Start fresh
            newExpiry = LocalDateTime.now().plusDays(durationDays);
        }

        if (existing != null) {
            // Update existing permit
            String sql = "UPDATE permits SET expires_at = ?, price_paid = price_paid + ? WHERE id = ?";
            
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                stmt.setString(1, newExpiry.toString());
                stmt.setDouble(2, price);
                stmt.setInt(3, existing.getId());
                
                stmt.executeUpdate();
                
                plugin.getDatabaseManager().logTransaction(
                    playerId, "permit_extension",
                    "Extended " + dimension + " permit by " + durationDays + " days",
                    -price, null
                );
                
                return new PermitResult(true, "Permit extended!", existing.getId());
                
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to extend permit: " + e.getMessage());
                return new PermitResult(false, "Database error", -1);
            }
        } else {
            return purchasePermit(playerId, dimension);
        }
    }

    /**
     * Grant a permit (admin function)
     */
    public PermitResult grantPermit(UUID playerId, String dimension, int durationDays) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expires = now.plusDays(durationDays);

        // Check for existing permit
        Permit existing = getPermit(playerId, dimension);
        if (existing != null) {
            // Extend it
            expires = existing.getExpiresAt().plusDays(durationDays);
            
            String sql = "UPDATE permits SET expires_at = ? WHERE id = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                stmt.setString(1, expires.toString());
                stmt.setInt(2, existing.getId());
                stmt.executeUpdate();
                
                return new PermitResult(true, "Permit extended!", existing.getId());
                
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to grant permit: " + e.getMessage());
                return new PermitResult(false, "Database error", -1);
            }
        }

        String sql = "INSERT INTO permits (player_id, dimension, purchased_at, expires_at, price_paid, status) " +
                     "VALUES (?, ?, ?, ?, 0, 'active')";
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            
            stmt.setString(1, playerId.toString());
            stmt.setString(2, dimension.toLowerCase());
            stmt.setString(3, now.toString());
            stmt.setString(4, expires.toString());
            
            stmt.executeUpdate();
            
            ResultSet rs = stmt.getGeneratedKeys();
            int permitId = rs.next() ? rs.getInt(1) : -1;
            
            return new PermitResult(true, "Permit granted!", permitId);
            
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to grant permit: " + e.getMessage());
            return new PermitResult(false, "Database error", -1);
        }
    }

    /**
     * Revoke a permit (admin function)
     */
    public boolean revokePermit(UUID playerId, String dimension) {
        String sql = "UPDATE permits SET status = 'revoked' WHERE player_id = ? AND dimension = ? AND status = 'active'";
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, playerId.toString());
            stmt.setString(2, dimension.toLowerCase());
            
            return stmt.executeUpdate() > 0;
            
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to revoke permit: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the price for a dimension permit
     */
    public double getPermitPrice(String dimension) {
        return plugin.getConfig().getDouble("permits." + dimension.toLowerCase() + ".price", 10.0);
    }

    /**
     * Get the duration for a dimension permit
     */
    public int getPermitDuration(String dimension) {
        return plugin.getConfig().getInt("permits." + dimension.toLowerCase() + ".duration_days", 30);
    }

    /**
     * Get all available permit types from config
     */
    public List<String> getAvailableDimensions() {
        if (plugin.getConfig().getConfigurationSection("permits") != null) {
            return new ArrayList<>(plugin.getConfig().getConfigurationSection("permits").getKeys(false));
        }
        return new ArrayList<>();
    }

    /**
     * Check and expire old permits
     */
    public void expireOldPermits() {
        String sql = "UPDATE permits SET status = 'expired' WHERE expires_at < ? AND status = 'active'";
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, LocalDateTime.now().toString());
            int expired = stmt.executeUpdate();
            
            if (expired > 0) {
                plugin.getLogger().info("Expired " + expired + " permits.");
            }
            
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to expire permits: " + e.getMessage());
        }
    }

    // Data classes
    
    public static class Permit {
        private final int id;
        private final UUID playerId;
        private final String dimension;
        private final LocalDateTime purchasedAt;
        private final LocalDateTime expiresAt;
        private final double pricePaid;
        private final String status;

        public Permit(int id, UUID playerId, String dimension, LocalDateTime purchasedAt,
                      LocalDateTime expiresAt, double pricePaid, String status) {
            this.id = id;
            this.playerId = playerId;
            this.dimension = dimension;
            this.purchasedAt = purchasedAt;
            this.expiresAt = expiresAt;
            this.pricePaid = pricePaid;
            this.status = status;
        }

        public int getId() { return id; }
        public UUID getPlayerId() { return playerId; }
        public String getDimension() { return dimension; }
        public LocalDateTime getPurchasedAt() { return purchasedAt; }
        public LocalDateTime getExpiresAt() { return expiresAt; }
        public double getPricePaid() { return pricePaid; }
        public String getStatus() { return status; }
        
        public boolean isExpired() {
            return LocalDateTime.now().isAfter(expiresAt);
        }
        
        public long getDaysRemaining() {
            if (isExpired()) return 0;
            return java.time.temporal.ChronoUnit.DAYS.between(LocalDateTime.now(), expiresAt);
        }
    }

    public static class PermitResult {
        private final boolean success;
        private final String message;
        private final int permitId;

        public PermitResult(boolean success, String message, int permitId) {
            this.success = success;
            this.message = message;
            this.permitId = permitId;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public int getPermitId() { return permitId; }
    }
}
