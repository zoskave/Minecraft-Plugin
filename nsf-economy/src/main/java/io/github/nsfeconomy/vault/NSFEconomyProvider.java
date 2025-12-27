package io.github.nsfeconomy.vault;

import io.github.nsfeconomy.NSFEconomy;
import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Vault Economy Provider implementation for NSF Economy
 * 
 * This bridges NSF's physical F-note economy with Vault's virtual balance API.
 * Virtual balances are tracked separately in the database for plugins that require
 * Vault integration (shop plugins, etc.)
 * 
 * Note: This is a hybrid approach - physical F-notes remain the primary currency,
 * but virtual balances can be used for plugin compatibility.
 */
public class NSFEconomyProvider extends AbstractEconomy {

    private final NSFEconomy plugin;

    public NSFEconomyProvider(NSFEconomy plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

    @Override
    public String getName() {
        return "NSFEconomy";
    }

    @Override
    public boolean hasBankSupport() {
        return true;
    }

    @Override
    public int fractionalDigits() {
        return 2;
    }

    @Override
    public String format(double amount) {
        return plugin.getCurrencyManager().formatCurrency(amount);
    }

    @Override
    public String currencyNamePlural() {
        return plugin.getConfig().getString("currency.name_plural", "F-Dollars");
    }

    @Override
    public String currencyNameSingular() {
        return plugin.getConfig().getString("currency.name", "F-Dollar");
    }

    // ==================== Account Methods ====================

    @Override
    public boolean hasAccount(String playerName) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        return hasAccount(player);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return player.hasPlayedBefore() || player.isOnline();
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    // ==================== Balance Methods ====================

    @Override
    public double getBalance(String playerName) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        return getBalance(player);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        // Return virtual balance from database
        return plugin.getDatabaseManager().getVirtualBalance(player.getUniqueId());
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    // ==================== Withdrawal Methods ====================

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) {
            return new EconomyResponse(0, getBalance(player), 
                EconomyResponse.ResponseType.FAILURE, "Cannot withdraw negative amounts");
        }

        // Check if economy is frozen
        if (plugin.getConfig().getBoolean("economy.frozen", false)) {
            return new EconomyResponse(0, getBalance(player),
                EconomyResponse.ResponseType.FAILURE, "Economy is currently frozen");
        }

        double balance = getBalance(player);
        if (balance < amount) {
            return new EconomyResponse(0, balance, 
                EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
        }

        // Perform withdrawal from virtual balance
        boolean success = plugin.getDatabaseManager().updateVirtualBalance(
            player.getUniqueId(), -amount
        );

        if (success) {
            double newBalance = getBalance(player);
            
            // Log transaction
            plugin.getDatabaseManager().logTransaction(
                player.getUniqueId(), "vault_withdraw",
                "Virtual balance withdrawal via Vault API",
                -amount, null
            );

            return new EconomyResponse(amount, newBalance, 
                EconomyResponse.ResponseType.SUCCESS, null);
        }

        return new EconomyResponse(0, balance, 
            EconomyResponse.ResponseType.FAILURE, "Transaction failed");
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    // ==================== Deposit Methods ====================

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        return depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) {
            return new EconomyResponse(0, getBalance(player), 
                EconomyResponse.ResponseType.FAILURE, "Cannot deposit negative amounts");
        }

        // Check if economy is frozen
        if (plugin.getConfig().getBoolean("economy.frozen", false)) {
            return new EconomyResponse(0, getBalance(player),
                EconomyResponse.ResponseType.FAILURE, "Economy is currently frozen");
        }

        // Perform deposit to virtual balance
        boolean success = plugin.getDatabaseManager().updateVirtualBalance(
            player.getUniqueId(), amount
        );

        if (success) {
            double newBalance = getBalance(player);
            
            // Log transaction
            plugin.getDatabaseManager().logTransaction(
                player.getUniqueId(), "vault_deposit",
                "Virtual balance deposit via Vault API",
                amount, null
            );

            return new EconomyResponse(amount, newBalance, 
                EconomyResponse.ResponseType.SUCCESS, null);
        }

        return new EconomyResponse(0, getBalance(player), 
            EconomyResponse.ResponseType.FAILURE, "Transaction failed");
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    // ==================== Account Creation ====================

    @Override
    public boolean createPlayerAccount(String playerName) {
        // Accounts are created implicitly when needed
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // ==================== Bank Methods ====================
    // Note: These refer to the central NSF bank, not player-owned banks

    @Override
    public EconomyResponse createBank(String name, String player) {
        return new EconomyResponse(0, 0, 
            EconomyResponse.ResponseType.NOT_IMPLEMENTED, 
            "NSF Economy uses a central bank system");
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return createBank(name, player.getName());
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return new EconomyResponse(0, 0, 
            EconomyResponse.ResponseType.NOT_IMPLEMENTED, 
            "NSF Economy uses a central bank system");
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        // Return the central reserve balance
        double reserves = plugin.getBankManager().getReserveBalance();
        return new EconomyResponse(0, reserves, 
            EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        double reserves = plugin.getBankManager().getReserveBalance();
        if (reserves >= amount) {
            return new EconomyResponse(0, reserves, 
                EconomyResponse.ResponseType.SUCCESS, null);
        }
        return new EconomyResponse(0, reserves, 
            EconomyResponse.ResponseType.FAILURE, "Insufficient reserves");
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return new EconomyResponse(0, 0, 
            EconomyResponse.ResponseType.NOT_IMPLEMENTED, 
            "Use in-game bank commands for withdrawals");
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return new EconomyResponse(0, 0, 
            EconomyResponse.ResponseType.NOT_IMPLEMENTED, 
            "Use in-game bank commands for deposits");
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return new EconomyResponse(0, 0, 
            EconomyResponse.ResponseType.NOT_IMPLEMENTED, 
            "NSF Economy uses a central bank system");
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return isBankOwner(name, player.getName());
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        // All players are members of the central bank
        return new EconomyResponse(0, 0, 
            EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return isBankMember(name, player.getName());
    }

    @Override
    public List<String> getBanks() {
        List<String> banks = new ArrayList<>();
        banks.add("NSF Central Bank");
        // Add branch names
        plugin.getBankManager().getBankLocations().forEach(loc -> 
            banks.add(loc.getName()));
        return banks;
    }
}
