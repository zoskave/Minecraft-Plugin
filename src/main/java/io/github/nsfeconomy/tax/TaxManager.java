package io.github.nsfeconomy.tax;

import io.github.nsfeconomy.NSFEconomy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Level;

/**
 * Manages the taxation system for the NSF Economy.
 */
public class TaxManager {

    private final NSFEconomy plugin;

    public TaxManager(NSFEconomy plugin) {
        this.plugin = plugin;
    }

    /**
     * Check for upcoming and overdue taxes
     */
    public void checkTaxDueDates() {
        if (!plugin.getConfig().getBoolean("tax.enabled", true)) {
            return;
        }

        String sql = "SELECT * FROM tax_obligations WHERE paid = 0";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                UUID playerUUID = UUID.fromString(rs.getString("player"));
                double amount = rs.getDouble("amount");
                Timestamp dueDate = rs.getTimestamp("due_date");
                
                Player player = Bukkit.getPlayer(playerUUID);
                if (player != null && player.isOnline()) {
                    Instant now = Instant.now();
                    Instant due = dueDate.toInstant();
                    
                    if (now.isAfter(due)) {
                        // Overdue
                        player.sendMessage(plugin.getMessage("tax_overdue"));
                    } else if (ChronoUnit.DAYS.between(now, due) <= 3) {
                        // Due soon
                        String msg = plugin.getRawMessage("tax_reminder")
                            .replace("{amount}", plugin.getCurrencyManager().formatCurrency(amount))
                            .replace("{date}", dueDate.toString());
                        player.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.prefix", "") + msg));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to check tax due dates", e);
        }
    }

    /**
     * Create a tax obligation for a player
     */
    public boolean createTaxObligation(UUID player, String type, double amount, Timestamp dueDate) {
        String sql = "INSERT INTO tax_obligations (player, type, amount, due_date) VALUES (?, ?, ?, ?)";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, type);
            ps.setDouble(3, amount);
            ps.setTimestamp(4, dueDate);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create tax obligation", e);
            return false;
        }
    }

    /**
     * Get a player's unpaid tax obligations
     */
    public List<TaxObligation> getUnpaidTaxes(UUID player) {
        List<TaxObligation> obligations = new ArrayList<>();
        String sql = "SELECT * FROM tax_obligations WHERE player = ? AND paid = 0 ORDER BY due_date";
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    obligations.add(new TaxObligation(
                        rs.getInt("id"),
                        UUID.fromString(rs.getString("player")),
                        rs.getString("type"),
                        rs.getDouble("amount"),
                        rs.getTimestamp("due_date"),
                        rs.getBoolean("paid")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get unpaid taxes", e);
        }
        return obligations;
    }

    /**
     * Get total amount owed by a player
     */
    public double getTotalOwed(UUID player) {
        String sql = "SELECT SUM(amount) as total FROM tax_obligations WHERE player = ? AND paid = 0";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("total");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get total owed", e);
        }
        return 0;
    }

    /**
     * Pay off tax obligations
     */
    public PaymentResult payTaxes(UUID player, double amount) {
        List<TaxObligation> obligations = getUnpaidTaxes(player);
        
        if (obligations.isEmpty()) {
            return new PaymentResult(false, 0, "No taxes owed");
        }
        
        double paid = 0;
        
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            for (TaxObligation obligation : obligations) {
                if (paid >= amount) break;
                
                double toPay = Math.min(obligation.getAmount(), amount - paid);
                
                if (toPay >= obligation.getAmount()) {
                    // Pay in full
                    String sql = "UPDATE tax_obligations SET paid = 1, paid_at = CURRENT_TIMESTAMP WHERE id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setInt(1, obligation.getId());
                        ps.executeUpdate();
                    }
                } else {
                    // Partial payment
                    String sql = "UPDATE tax_obligations SET amount = amount - ? WHERE id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setDouble(1, toPay);
                        ps.setInt(2, obligation.getId());
                        ps.executeUpdate();
                    }
                }
                
                paid += toPay;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to process tax payment", e);
            return new PaymentResult(false, paid, "Database error");
        }
        
        // Log transaction
        plugin.getDatabaseManager().logTransaction("tax", player, paid, 0, "Tax payment");
        
        return new PaymentResult(true, paid, "Success");
    }

    /**
     * Forgive a player's taxes (admin only)
     */
    public boolean forgiveTaxes(UUID player, double amount) {
        String sql = "UPDATE tax_obligations SET paid = 1, paid_at = CURRENT_TIMESTAMP " +
                    "WHERE player = ? AND paid = 0 LIMIT 1";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to forgive taxes", e);
            return false;
        }
    }

    /**
     * Get sales tax rate
     */
    public double getSalesTaxRate() {
        if (!plugin.getConfig().getBoolean("tax.sales_tax.enabled", true)) {
            return 0;
        }
        return plugin.getConfig().getDouble("tax.sales_tax.rate", 0.05);
    }

    /**
     * Calculate sales tax for a transaction
     */
    public double calculateSalesTax(double salePrice) {
        return salePrice * getSalesTaxRate();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Inner Classes
    // ══════════════════════════════════════════════════════════════════════

    public static class TaxObligation {
        private final int id;
        private final UUID player;
        private final String type;
        private final double amount;
        private final Timestamp dueDate;
        private final boolean paid;

        public TaxObligation(int id, UUID player, String type, double amount, Timestamp dueDate, boolean paid) {
            this.id = id;
            this.player = player;
            this.type = type;
            this.amount = amount;
            this.dueDate = dueDate;
            this.paid = paid;
        }

        public int getId() { return id; }
        public UUID getPlayer() { return player; }
        public String getType() { return type; }
        public double getAmount() { return amount; }
        public Timestamp getDueDate() { return dueDate; }
        public boolean isPaid() { return paid; }
    }

    public static class PaymentResult {
        private final boolean success;
        private final double amountPaid;
        private final String message;

        public PaymentResult(boolean success, double amountPaid, String message) {
            this.success = success;
            this.amountPaid = amountPaid;
            this.message = message;
        }

        public boolean isSuccess() { return success; }
        public double getAmountPaid() { return amountPaid; }
        public String getMessage() { return message; }
    }
}
