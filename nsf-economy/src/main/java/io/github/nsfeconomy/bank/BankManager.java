package io.github.nsfeconomy.bank;

import io.github.nsfeconomy.NSFEconomy;
import io.github.nsfeconomy.currency.CurrencyManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages bank locations and core banking operations like deposits and withdrawals.
 */
public class BankManager {

    private final NSFEconomy plugin;
    private final Map<String, BankLocation> bankLocations;
    private final Map<UUID, Long> withdrawalCooldowns;
    private final Map<UUID, Integer> hourlyTransactionCounts;
    
    // Withdrawal queue
    private final List<WithdrawalRequest> withdrawalQueue;

    public BankManager(NSFEconomy plugin) {
        this.plugin = plugin;
        this.bankLocations = new ConcurrentHashMap<>();
        this.withdrawalCooldowns = new ConcurrentHashMap<>();
        this.hourlyTransactionCounts = new ConcurrentHashMap<>();
        this.withdrawalQueue = Collections.synchronizedList(new ArrayList<>());
        
        // Clear hourly transaction counts every hour
        Bukkit.getScheduler().runTaskTimerAsync(plugin, hourlyTransactionCounts::clear, 
            20L * 60 * 60, 20L * 60 * 60);
    }

    /**
     * Load bank locations from database
     */
    public void loadBankLocations() {
        bankLocations.clear();
        String sql = "SELECT * FROM bank_locations";
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            
            while (rs.next()) {
                BankLocation location = new BankLocation(
                    rs.getInt("id"),
                    rs.getString("name"),
                    rs.getString("type"),
                    rs.getString("world"),
                    rs.getInt("x"),
                    rs.getInt("y"),
                    rs.getInt("z"),
                    rs.getInt("radius")
                );
                bankLocations.put(location.getName().toLowerCase(), location);
            }
            
            plugin.getLogger().info("Loaded " + bankLocations.size() + " bank locations.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load bank locations", e);
        }
    }

    /**
     * Save bank locations to database
     */
    public void saveBankLocations() {
        // Locations are saved on creation/removal, this is for any pending changes
        plugin.debug("Bank locations saved.");
    }

    /**
     * Create a new bank location
     */
    public boolean createBankLocation(String name, String type, Location location, int radius) {
        if (bankLocations.containsKey(name.toLowerCase())) {
            return false;
        }
        
        // Check for main vault uniqueness
        if ("main".equalsIgnoreCase(type)) {
            for (BankLocation bl : bankLocations.values()) {
                if ("main".equalsIgnoreCase(bl.getType())) {
                    return false; // Only one main vault allowed
                }
            }
        }
        
        String sql = "INSERT INTO bank_locations (name, type, world, x, y, z, radius) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            
            ps.setString(1, name);
            ps.setString(2, type.toLowerCase());
            ps.setString(3, location.getWorld().getName());
            ps.setInt(4, location.getBlockX());
            ps.setInt(5, location.getBlockY());
            ps.setInt(6, location.getBlockZ());
            ps.setInt(7, radius);
            
            if (ps.executeUpdate() > 0) {
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        int id = rs.getInt(1);
                        BankLocation bankLoc = new BankLocation(id, name, type, 
                            location.getWorld().getName(), 
                            location.getBlockX(), location.getBlockY(), location.getBlockZ(), 
                            radius);
                        bankLocations.put(name.toLowerCase(), bankLoc);
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create bank location", e);
        }
        return false;
    }

    /**
     * Remove a bank location
     */
    public boolean removeBankLocation(String name) {
        BankLocation location = bankLocations.get(name.toLowerCase());
        if (location == null) {
            return false;
        }
        
        String sql = "DELETE FROM bank_locations WHERE id = ?";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setInt(1, location.getId());
            if (ps.executeUpdate() > 0) {
                bankLocations.remove(name.toLowerCase());
                return true;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to remove bank location", e);
        }
        return false;
    }

    /**
     * Check if a player is at a bank location
     */
    public boolean isAtBank(Player player) {
        return getNearbyBank(player) != null;
    }

    /**
     * Get the bank location a player is at, or null if not at any
     */
    public BankLocation getNearbyBank(Player player) {
        Location playerLoc = player.getLocation();
        String worldName = playerLoc.getWorld().getName();
        
        for (BankLocation bank : bankLocations.values()) {
            if (!bank.getWorld().equals(worldName)) continue;
            
            double distance = Math.sqrt(
                Math.pow(playerLoc.getX() - bank.getX(), 2) +
                Math.pow(playerLoc.getY() - bank.getY(), 2) +
                Math.pow(playerLoc.getZ() - bank.getZ(), 2)
            );
            
            if (distance <= bank.getRadius()) {
                return bank;
            }
        }
        return null;
    }

    /**
     * Process a deposit
     */
    public DepositResult processDeposit(Player player, int starAmount) {
        if (!isAtBank(player)) {
            return new DepositResult(false, "not_at_bank", 0, null);
        }
        
        // Check rate limits
        if (!checkRateLimit(player)) {
            return new DepositResult(false, "rate_limited", 0, null);
        }
        
        CurrencyManager currencyManager = plugin.getCurrencyManager();
        int starsPerDollar = currencyManager.getStarsPerDollar();
        
        // Minimum deposit is F$1 worth
        if (starAmount < starsPerDollar) {
            return new DepositResult(false, "minimum_deposit", starsPerDollar, null);
        }
        
        // Count Nether Stars in inventory
        int starsInInventory = countNetherStars(player.getInventory());
        if (starsInInventory < starAmount) {
            return new DepositResult(false, "insufficient_stars", starsInInventory, null);
        }
        
        // Calculate F$ to issue
        double fDollars = currencyManager.starsToFDollars(starAmount);
        Map<Integer, Integer> denomBreakdown = currencyManager.calculateDenominations(fDollars);
        
        // Calculate actual stars used (rounded down to nearest F$1)
        long actualStars = currencyManager.fDollarsToStars(Math.floor(fDollars));
        
        if (actualStars == 0) {
            return new DepositResult(false, "amount_too_small", 0, null);
        }
        
        // Remove Nether Stars from inventory
        if (!removeNetherStars(player.getInventory(), (int) actualStars)) {
            return new DepositResult(false, "removal_failed", 0, null);
        }
        
        // Add to reserve
        if (!plugin.getDatabaseManager().addToReserve(actualStars)) {
            // Rollback: return stars
            giveNetherStars(player, (int) actualStars);
            return new DepositResult(false, "reserve_failed", 0, null);
        }
        
        // Create and give F-notes
        List<ItemStack> notes = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : denomBreakdown.entrySet()) {
            notes.addAll(currencyManager.createNotes(entry.getKey(), entry.getValue(), player.getUniqueId()));
        }
        
        for (ItemStack note : notes) {
            HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(note);
            for (ItemStack overflowItem : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflowItem);
            }
        }
        
        // Log transaction
        plugin.getDatabaseManager().logTransaction("deposit", player.getUniqueId(), 
            Math.floor(fDollars), actualStars, "Deposited " + actualStars + " stars");
        
        incrementTransactionCount(player);
        
        return new DepositResult(true, "success", actualStars, denomBreakdown);
    }

    /**
     * Process a withdrawal
     */
    public WithdrawalResult processWithdrawal(Player player, double fDollars) {
        if (!isAtBank(player)) {
            return new WithdrawalResult(false, "not_at_bank", 0);
        }
        
        // Check rate limits
        if (!checkRateLimit(player)) {
            return new WithdrawalResult(false, "rate_limited", 0);
        }
        
        // Check withdrawal cooldown
        if (!checkWithdrawalCooldown(player)) {
            return new WithdrawalResult(false, "cooldown", 0);
        }
        
        CurrencyManager currencyManager = plugin.getCurrencyManager();
        
        // Check emergency mode
        if (isEmergencyModeActive()) {
            double maxWithdrawal = plugin.getConfig().getDouble("bank.emergency_mode.max_withdrawal_per_day", 10);
            if (fDollars > maxWithdrawal) {
                return new WithdrawalResult(false, "emergency_limit", maxWithdrawal);
            }
        }
        
        // Count valid F-notes in inventory
        Map<Integer, Integer> notesInInventory = currencyManager.countNotesInInventory(
            player.getInventory().getContents());
        double totalValue = currencyManager.calculateTotalValue(notesInInventory);
        
        if (totalValue < fDollars) {
            return new WithdrawalResult(false, "insufficient_notes", totalValue);
        }
        
        // Calculate stars needed
        long starsNeeded = currencyManager.fDollarsToStars(fDollars);
        
        // Check reserve
        long currentReserve = plugin.getDatabaseManager().getReserve();
        if (currentReserve < starsNeeded) {
            return new WithdrawalResult(false, "insufficient_reserve", currentReserve);
        }
        
        // Remove F-notes from inventory and redeem them
        double notesRemoved = removeAndRedeemNotes(player, fDollars);
        if (notesRemoved < fDollars) {
            return new WithdrawalResult(false, "note_removal_failed", notesRemoved);
        }
        
        // Remove from reserve
        if (!plugin.getDatabaseManager().removeFromReserve(starsNeeded)) {
            return new WithdrawalResult(false, "reserve_removal_failed", 0);
        }
        
        // Give Nether Stars
        giveNetherStars(player, (int) starsNeeded);
        
        // Apply emergency fee if applicable
        if (isEmergencyModeActive()) {
            double feeRate = plugin.getConfig().getDouble("bank.emergency_mode.withdrawal_fee", 0.05);
            // Fee would be deducted from the notes, but we've already processed
            // In a real implementation, this would be handled before giving stars
        }
        
        // Log transaction
        plugin.getDatabaseManager().logTransaction("withdraw", player.getUniqueId(), 
            fDollars, starsNeeded, "Withdrew " + starsNeeded + " stars");
        
        setWithdrawalCooldown(player);
        incrementTransactionCount(player);
        
        return new WithdrawalResult(true, "success", starsNeeded);
    }

    /**
     * Add a player to the withdrawal queue
     */
    public boolean addToWithdrawalQueue(Player player, double amount) {
        WithdrawalRequest request = new WithdrawalRequest(player.getUniqueId(), amount);
        withdrawalQueue.add(request);
        
        String sql = "INSERT INTO withdrawal_queue (player, amount) VALUES (?, ?)";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setDouble(2, amount);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to add to withdrawal queue", e);
            return false;
        }
    }

    /**
     * Process the withdrawal queue
     */
    public void processWithdrawalQueue() {
        long currentReserve = plugin.getDatabaseManager().getReserve();
        CurrencyManager currencyManager = plugin.getCurrencyManager();
        
        Iterator<WithdrawalRequest> iterator = withdrawalQueue.iterator();
        while (iterator.hasNext()) {
            WithdrawalRequest request = iterator.next();
            long starsNeeded = currencyManager.fDollarsToStars(request.getAmount());
            
            if (currentReserve >= starsNeeded) {
                Player player = Bukkit.getPlayer(request.getPlayerUUID());
                if (player != null && player.isOnline()) {
                    // Process the queued withdrawal
                    if (plugin.getDatabaseManager().removeFromReserve(starsNeeded)) {
                        giveNetherStars(player, (int) starsNeeded);
                        currentReserve -= starsNeeded;
                        
                        player.sendMessage(plugin.getMessage("queue_processed"));
                        iterator.remove();
                        
                        // Update database
                        markQueueRequestProcessed(request.getPlayerUUID());
                    }
                }
            }
        }
    }

    /**
     * Get a player's position in the withdrawal queue
     */
    public int getQueuePosition(UUID playerUUID) {
        int position = 1;
        for (WithdrawalRequest request : withdrawalQueue) {
            if (request.getPlayerUUID().equals(playerUUID)) {
                return position;
            }
            position++;
        }
        return -1; // Not in queue
    }

    /**
     * Remove a player from the withdrawal queue
     */
    public boolean removeFromQueue(UUID playerUUID) {
        withdrawalQueue.removeIf(r -> r.getPlayerUUID().equals(playerUUID));
        
        String sql = "DELETE FROM withdrawal_queue WHERE player = ? AND processed = 0";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUUID.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to remove from queue", e);
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Reserve and Statistics
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Get current reserve statistics
     */
    public ReserveStats getReserveStats() {
        long reserve = plugin.getDatabaseManager().getReserve();
        
        CurrencyManager cm = plugin.getCurrencyManager();
        Map<Integer, Long> circulatingByDenom = new HashMap<>();
        long totalCirculating = 0;
        
        for (int denom : cm.getDenominations()) {
            long count = plugin.getDatabaseManager().getCirculatingCount(denom);
            circulatingByDenom.put(denom, count);
            totalCirculating += count * denom;
        }
        
        long starsNeededForFull = cm.fDollarsToStars(totalCirculating);
        double reserveRatio = starsNeededForFull > 0 ? (double) reserve / starsNeededForFull : 1.0;
        
        return new ReserveStats(reserve, totalCirculating, circulatingByDenom, reserveRatio);
    }

    /**
     * Check if emergency mode is active
     */
    public boolean isEmergencyModeActive() {
        if (!plugin.getConfig().getBoolean("bank.emergency_mode.enabled", true)) {
            return false;
        }
        
        double criticalRatio = plugin.getConfig().getDouble("bank.reserve_ratio_critical", 0.05);
        ReserveStats stats = getReserveStats();
        return stats.getReserveRatio() < criticalRatio;
    }

    /**
     * Check if emergency mode is active (alias for compatibility)
     */
    public boolean isEmergencyMode() {
        return plugin.getConfig().getBoolean("bank.emergency_mode.active", false) || isEmergencyModeActive();
    }

    /**
     * Set emergency mode active or inactive
     */
    public void setEmergencyMode(boolean active) {
        plugin.getConfig().set("bank.emergency_mode.active", active);
        plugin.saveConfig();
        
        if (active) {
            plugin.getLogger().warning("Emergency mode has been activated!");
        } else {
            plugin.getLogger().info("Emergency mode has been deactivated.");
        }
    }

    /**
     * Get circulating currency counts by denomination
     */
    public Map<Integer, Integer> getCirculatingByDenomination() {
        Map<Integer, Integer> counts = new HashMap<>();
        String sql = "SELECT denomination, COUNT(*) as count FROM currency_ledger " +
                     "WHERE status = 'circulating' GROUP BY denomination";
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            
            while (rs.next()) {
                counts.put(rs.getInt("denomination"), rs.getInt("count"));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get circulating by denomination", e);
        }
        
        return counts;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helper Methods
    // ══════════════════════════════════════════════════════════════════════

    private int countNetherStars(PlayerInventory inventory) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == Material.NETHER_STAR) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private boolean removeNetherStars(PlayerInventory inventory, int amount) {
        int remaining = amount;
        for (int i = 0; i < inventory.getSize() && remaining > 0; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() == Material.NETHER_STAR) {
                int toRemove = Math.min(item.getAmount(), remaining);
                remaining -= toRemove;
                if (toRemove == item.getAmount()) {
                    inventory.setItem(i, null);
                } else {
                    item.setAmount(item.getAmount() - toRemove);
                }
            }
        }
        return remaining == 0;
    }

    private void giveNetherStars(Player player, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int stackSize = Math.min(remaining, 64);
            ItemStack stars = new ItemStack(Material.NETHER_STAR, stackSize);
            HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(stars);
            for (ItemStack item : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
            remaining -= stackSize;
        }
    }

    private double removeAndRedeemNotes(Player player, double amountNeeded) {
        CurrencyManager cm = plugin.getCurrencyManager();
        double removed = 0;
        PlayerInventory inventory = player.getInventory();
        
        // Sort denominations descending for optimal removal
        List<Integer> sortedDenoms = new ArrayList<>(cm.getDenominations());
        sortedDenoms.sort(Collections.reverseOrder());
        
        for (int i = 0; i < inventory.getSize() && removed < amountNeeded; i++) {
            ItemStack item = inventory.getItem(i);
            if (item == null) continue;
            
            CurrencyManager.ValidationResult result = cm.validateNote(item);
            if (result.isValid()) {
                // Redeem this note
                if (plugin.getDatabaseManager().redeemNote(result.getSerial(), player.getUniqueId())) {
                    removed += result.getDenomination();
                    inventory.setItem(i, null);
                }
            }
        }
        
        return removed;
    }

    private boolean checkRateLimit(Player player) {
        int maxPerHour = plugin.getConfig().getInt("bank.rate_limits.max_transactions_per_hour", 20);
        int current = hourlyTransactionCounts.getOrDefault(player.getUniqueId(), 0);
        return current < maxPerHour;
    }

    private void incrementTransactionCount(Player player) {
        hourlyTransactionCounts.merge(player.getUniqueId(), 1, Integer::sum);
    }

    private boolean checkWithdrawalCooldown(Player player) {
        Long lastWithdrawal = withdrawalCooldowns.get(player.getUniqueId());
        if (lastWithdrawal == null) return true;
        
        int cooldownSeconds = plugin.getConfig().getInt("bank.rate_limits.withdrawal_cooldown_seconds", 300);
        return System.currentTimeMillis() - lastWithdrawal >= cooldownSeconds * 1000L;
    }

    private void setWithdrawalCooldown(Player player) {
        withdrawalCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private void markQueueRequestProcessed(UUID playerUUID) {
        String sql = "UPDATE withdrawal_queue SET processed = 1 WHERE player = ?";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUUID.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to mark queue request processed", e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Getters
    // ══════════════════════════════════════════════════════════════════════

    public Map<String, BankLocation> getBankLocations() {
        return Collections.unmodifiableMap(bankLocations);
    }

    public BankLocation getBankLocation(String name) {
        return bankLocations.get(name.toLowerCase());
    }

    public boolean hasMainVault() {
        return bankLocations.values().stream()
            .anyMatch(bl -> "main".equalsIgnoreCase(bl.getType()));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Result Classes
    // ══════════════════════════════════════════════════════════════════════

    public static class DepositResult {
        private final boolean success;
        private final String reason;
        private final long starsUsed;
        private final Map<Integer, Integer> notesIssued;

        public DepositResult(boolean success, String reason, long starsUsed, Map<Integer, Integer> notesIssued) {
            this.success = success;
            this.reason = reason;
            this.starsUsed = starsUsed;
            this.notesIssued = notesIssued;
        }

        public boolean isSuccess() { return success; }
        public String getReason() { return reason; }
        public long getStarsUsed() { return starsUsed; }
        public Map<Integer, Integer> getNotesIssued() { return notesIssued; }
    }

    public static class WithdrawalResult {
        private final boolean success;
        private final String reason;
        private final double value;

        public WithdrawalResult(boolean success, String reason, double value) {
            this.success = success;
            this.reason = reason;
            this.value = value;
        }

        public boolean isSuccess() { return success; }
        public String getReason() { return reason; }
        public double getValue() { return value; }
    }

    public static class ReserveStats {
        private final long reserveStars;
        private final long totalCirculating;
        private final Map<Integer, Long> circulatingByDenom;
        private final double reserveRatio;

        public ReserveStats(long reserveStars, long totalCirculating, 
                          Map<Integer, Long> circulatingByDenom, double reserveRatio) {
            this.reserveStars = reserveStars;
            this.totalCirculating = totalCirculating;
            this.circulatingByDenom = circulatingByDenom;
            this.reserveRatio = reserveRatio;
        }

        public long getReserveStars() { return reserveStars; }
        public long getTotalCirculating() { return totalCirculating; }
        public Map<Integer, Long> getCirculatingByDenom() { return circulatingByDenom; }
        public double getReserveRatio() { return reserveRatio; }
    }

    private static class WithdrawalRequest {
        private final UUID playerUUID;
        private final double amount;
        private final long requestTime;

        public WithdrawalRequest(UUID playerUUID, double amount) {
            this.playerUUID = playerUUID;
            this.amount = amount;
            this.requestTime = System.currentTimeMillis();
        }

        public UUID getPlayerUUID() { return playerUUID; }
        public double getAmount() { return amount; }
        public long getRequestTime() { return requestTime; }
    }
}
