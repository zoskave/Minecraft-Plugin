package io.github.nsfeconomy.bounty;

import io.github.nsfeconomy.NSFEconomy;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Manages the bounty system for government work contracts.
 */
public class BountyManager {

    private final NSFEconomy plugin;

    public BountyManager(NSFEconomy plugin) {
        this.plugin = plugin;
    }

    /**
     * Create a new bounty
     */
    public int createBounty(String description, double reward, UUID createdBy, Timestamp deadline) {
        String sql = "INSERT INTO bounties (description, reward, created_by, deadline) VALUES (?, ?, ?, ?)";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, description);
            ps.setDouble(2, reward);
            ps.setString(3, createdBy.toString());
            ps.setTimestamp(4, deadline);
            
            if (ps.executeUpdate() > 0) {
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create bounty", e);
        }
        return -1;
    }

    /**
     * Get all open bounties
     */
    public List<Bounty> getOpenBounties() {
        List<Bounty> bounties = new ArrayList<>();
        String sql = "SELECT * FROM bounties WHERE status = 'open' ORDER BY created_at DESC";
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                bounties.add(bountyFromResultSet(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get open bounties", e);
        }
        return bounties;
    }

    /**
     * Get a specific bounty by ID
     */
    public Bounty getBounty(int id) {
        String sql = "SELECT * FROM bounties WHERE id = ?";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return bountyFromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get bounty", e);
        }
        return null;
    }

    /**
     * Claim a bounty
     */
    public boolean claimBounty(int bountyId, UUID playerId) {
        String sql = "UPDATE bounties SET status = 'claimed', claimed_by = ? WHERE id = ? AND status = 'open'";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setInt(2, bountyId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to claim bounty", e);
            return false;
        }
    }

    /**
     * Submit a bounty for review
     */
    public boolean submitBounty(int bountyId, UUID playerId) {
        String sql = "UPDATE bounties SET status = 'submitted' WHERE id = ? AND claimed_by = ? AND status = 'claimed'";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, bountyId);
            ps.setString(2, playerId.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to submit bounty", e);
            return false;
        }
    }

    /**
     * Approve a bounty and mark as completed
     */
    public boolean approveBounty(int bountyId) {
        String sql = "UPDATE bounties SET status = 'completed' WHERE id = ? AND status = 'submitted'";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, bountyId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to approve bounty", e);
            return false;
        }
    }

    /**
     * Cancel a bounty
     */
    public boolean cancelBounty(int bountyId) {
        String sql = "UPDATE bounties SET status = 'expired' WHERE id = ?";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, bountyId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to cancel bounty", e);
            return false;
        }
    }

    /**
     * Get bounties claimed by a player
     */
    public List<Bounty> getPlayerBounties(UUID playerId) {
        List<Bounty> bounties = new ArrayList<>();
        String sql = "SELECT * FROM bounties WHERE claimed_by = ? AND status IN ('claimed', 'submitted') ORDER BY created_at DESC";
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    bounties.add(bountyFromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get player bounties", e);
        }
        return bounties;
    }

    private Bounty bountyFromResultSet(ResultSet rs) throws SQLException {
        String claimedByStr = rs.getString("claimed_by");
        return new Bounty(
            rs.getInt("id"),
            rs.getString("description"),
            rs.getDouble("reward"),
            rs.getString("status"),
            UUID.fromString(rs.getString("created_by")),
            claimedByStr != null ? UUID.fromString(claimedByStr) : null,
            rs.getTimestamp("deadline"),
            rs.getTimestamp("created_at")
        );
    }

    /**
     * Bounty data class
     */
    public static class Bounty {
        private final int id;
        private final String description;
        private final double reward;
        private final String status;
        private final UUID createdBy;
        private final UUID claimedBy;
        private final Timestamp deadline;
        private final Timestamp createdAt;

        public Bounty(int id, String description, double reward, String status,
                     UUID createdBy, UUID claimedBy, Timestamp deadline, Timestamp createdAt) {
            this.id = id;
            this.description = description;
            this.reward = reward;
            this.status = status;
            this.createdBy = createdBy;
            this.claimedBy = claimedBy;
            this.deadline = deadline;
            this.createdAt = createdAt;
        }

        public int getId() { return id; }
        public String getDescription() { return description; }
        public double getReward() { return reward; }
        public String getStatus() { return status; }
        public UUID getCreatedBy() { return createdBy; }
        public UUID getClaimedBy() { return claimedBy; }
        public Timestamp getDeadline() { return deadline; }
        public Timestamp getCreatedAt() { return createdAt; }
    }
}
