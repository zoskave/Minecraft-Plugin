package io.github.nsfeconomy.placeholders;

import io.github.nsfeconomy.NSFEconomy;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI expansion for NSF Economy
 * 
 * Available placeholders:
 * - %nsf_balance% - Player's F-note holdings
 * - %nsf_balance_formatted% - Formatted balance with symbol
 * - %nsf_virtual_balance% - Player's virtual (Vault) balance
 * - %nsf_tax_owed% - Total tax owed
 * - %nsf_reserve% - Current Nether Star reserves
 * - %nsf_reserve_ratio% - Current reserve ratio percentage
 * - %nsf_circulating% - Total F$ in circulation
 * - %nsf_emergency_mode% - Whether emergency mode is active
 * - %nsf_permit_nether% - Days remaining on Nether permit
 * - %nsf_permit_end% - Days remaining on End permit
 * - %nsf_at_bank% - Whether player is at a bank location
 * - %nsf_bounties_open% - Number of open bounties
 * - %nsf_bounties_claimed% - Number of bounties player has claimed
 */
public class NSFPlaceholderExpansion extends PlaceholderExpansion {

    private final NSFEconomy plugin;

    public NSFPlaceholderExpansion(NSFEconomy plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "nsf";
    }

    @Override
    public @NotNull String getAuthor() {
        return plugin.getDescription().getAuthors().toString();
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        // Global placeholders (don't need player)
        switch (params.toLowerCase()) {
            case "reserve":
                return String.format("%.0f", plugin.getBankManager().getReserveBalance());
            
            case "reserve_formatted":
                return String.format("%,.0f", plugin.getBankManager().getReserveBalance());
            
            case "reserve_ratio":
                double circulating = plugin.getBankManager().getCirculatingSupply();
                double reserves = plugin.getBankManager().getReserveBalance();
                double ratio = circulating > 0 ? (reserves / circulating) * 100 : 100;
                return String.format("%.2f%%", ratio);
            
            case "circulating":
                return plugin.getCurrencyManager().formatCurrency(
                    plugin.getBankManager().getCirculatingSupply());
            
            case "circulating_raw":
                return String.format("%.2f", plugin.getBankManager().getCirculatingSupply());
            
            case "emergency_mode":
                return plugin.getBankManager().isEmergencyMode() ? "Active" : "Normal";
            
            case "economy_status":
                return plugin.getConfig().getBoolean("economy.frozen", false) ? "Frozen" : "Active";
            
            case "bounties_open":
                return String.valueOf(plugin.getBountyManager().getOpenBounties().size());
            
            case "bank_count":
                return String.valueOf(plugin.getBankManager().getBankLocations().size());
            
            case "currency_symbol":
                return plugin.getCurrencyManager().getCurrencySymbol();
        }

        // Player-specific placeholders
        if (offlinePlayer == null) {
            return "";
        }

        switch (params.toLowerCase()) {
            case "balance":
                if (offlinePlayer.isOnline()) {
                    Player player = offlinePlayer.getPlayer();
                    if (player != null) {
                        return String.format("%.2f", 
                            plugin.getCurrencyManager().countPlayerCurrency(player));
                    }
                }
                return "0.00";
            
            case "balance_formatted":
                if (offlinePlayer.isOnline()) {
                    Player player = offlinePlayer.getPlayer();
                    if (player != null) {
                        return plugin.getCurrencyManager().formatCurrency(
                            plugin.getCurrencyManager().countPlayerCurrency(player));
                    }
                }
                return plugin.getCurrencyManager().formatCurrency(0);
            
            case "virtual_balance":
                return String.format("%.2f", 
                    plugin.getDatabaseManager().getVirtualBalance(offlinePlayer.getUniqueId()));
            
            case "virtual_balance_formatted":
                return plugin.getCurrencyManager().formatCurrency(
                    plugin.getDatabaseManager().getVirtualBalance(offlinePlayer.getUniqueId()));
            
            case "tax_owed":
                double taxOwed = plugin.getTaxManager().getTotalOwed(offlinePlayer.getUniqueId());
                return plugin.getCurrencyManager().formatCurrency(taxOwed);
            
            case "tax_owed_raw":
                return String.format("%.2f", 
                    plugin.getTaxManager().getTotalOwed(offlinePlayer.getUniqueId()));
            
            case "permit_nether":
                return getPermitDaysRemaining(offlinePlayer, "nether");
            
            case "permit_end":
                return getPermitDaysRemaining(offlinePlayer, "end");
            
            case "has_nether_permit":
                return plugin.getPermitManager().hasValidPermit(
                    offlinePlayer.getUniqueId(), "nether") ? "Yes" : "No";
            
            case "has_end_permit":
                return plugin.getPermitManager().hasValidPermit(
                    offlinePlayer.getUniqueId(), "end") ? "Yes" : "No";
            
            case "at_bank":
                if (offlinePlayer.isOnline()) {
                    Player player = offlinePlayer.getPlayer();
                    if (player != null) {
                        return plugin.getBankManager().isAtBank(player) ? "Yes" : "No";
                    }
                }
                return "No";
            
            case "bounties_claimed":
                return String.valueOf(plugin.getBountyManager()
                    .getPlayerBounties(offlinePlayer.getUniqueId()).size());
            
            case "queue_position":
                int pos = plugin.getBankManager().getQueuePosition(offlinePlayer.getUniqueId());
                return pos > 0 ? String.valueOf(pos) : "None";
        }

        // Handle dynamic permit placeholder: nsf_permit_<dimension>
        if (params.toLowerCase().startsWith("permit_")) {
            String dimension = params.substring(7);
            return getPermitDaysRemaining(offlinePlayer, dimension);
        }

        // Handle dynamic permit check: nsf_has_permit_<dimension>
        if (params.toLowerCase().startsWith("has_permit_")) {
            String dimension = params.substring(11);
            return plugin.getPermitManager().hasValidPermit(
                offlinePlayer.getUniqueId(), dimension) ? "Yes" : "No";
        }

        return null;
    }

    /**
     * Get days remaining on a permit for a player
     */
    private String getPermitDaysRemaining(OfflinePlayer player, String dimension) {
        var permit = plugin.getPermitManager().getPermit(player.getUniqueId(), dimension);
        if (permit == null || permit.isExpired()) {
            return "None";
        }
        long days = permit.getDaysRemaining();
        return days + (days == 1 ? " day" : " days");
    }
}
