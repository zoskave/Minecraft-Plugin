package io.github.nsfeconomy;

import io.github.nsfeconomy.bank.BankManager;
import io.github.nsfeconomy.bounty.BountyManager;
import io.github.nsfeconomy.commands.*;
import io.github.nsfeconomy.currency.CurrencyManager;
import io.github.nsfeconomy.database.DatabaseManager;
import io.github.nsfeconomy.listeners.BookListener;
import io.github.nsfeconomy.listeners.PlayerListener;
import io.github.nsfeconomy.permit.PermitManager;
import io.github.nsfeconomy.placeholders.NSFPlaceholderExpansion;
import io.github.nsfeconomy.tax.TaxManager;
import io.github.nsfeconomy.vault.NSFEconomyProvider;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * NSF Economy Plugin - Nether Star-backed Fractional Reserve Banking System
 * 
 * A comprehensive economy plugin that implements a realistic fractional reserve
 * banking system using Nether Stars as the reserve asset and Signed Books as
 * physical currency (F-notes).
 */
public class NSFEconomy extends JavaPlugin {

    private static NSFEconomy instance;
    
    // Core managers
    private DatabaseManager databaseManager;
    private BankManager bankManager;
    private CurrencyManager currencyManager;
    private TaxManager taxManager;
    private BountyManager bountyManager;
    private PermitManager permitManager;
    
    // Commands
    private TradeCommand tradeCommand;
    
    // Vault integration
    private NSFEconomyProvider economyProvider;
    
    @Override
    public void onEnable() {
        instance = this;
        
        // Save default config
        saveDefaultConfig();
        
        // Initialize database
        getLogger().info("Initializing database...");
        databaseManager = new DatabaseManager(this);
        if (!databaseManager.initialize()) {
            getLogger().severe("Failed to initialize database! Disabling plugin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // Initialize managers
        getLogger().info("Initializing managers...");
        currencyManager = new CurrencyManager(this);
        bankManager = new BankManager(this);
        taxManager = new TaxManager(this);
        bountyManager = new BountyManager(this);
        permitManager = new PermitManager(this);
        
        // Load data
        bankManager.loadBankLocations();
        
        // Register Vault economy provider
        if (setupVault()) {
            getLogger().info("Successfully registered with Vault!");
        } else {
            getLogger().warning("Vault integration failed. Some features may not work.");
        }
        
        // Register commands
        registerCommands();
        
        // Register listeners
        registerListeners();
        
        // Setup PlaceholderAPI if available
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new NSFPlaceholderExpansion(this).register();
            getLogger().info("PlaceholderAPI found! Placeholders registered.");
        }
        
        // Start scheduled tasks
        startScheduledTasks();
        
        getLogger().info("═══════════════════════════════════════════════════");
        getLogger().info(" NSF Economy v" + getDescription().getVersion() + " enabled!");
        getLogger().info(" Nether Star-backed Fractional Reserve Banking");
        getLogger().info("═══════════════════════════════════════════════════");
    }
    
    @Override
    public void onDisable() {
        // Save all data
        if (bankManager != null) {
            bankManager.saveBankLocations();
        }
        
        // Close database connection
        if (databaseManager != null) {
            databaseManager.close();
        }
        
        // Unregister Vault provider
        if (economyProvider != null) {
            Bukkit.getServicesManager().unregister(Economy.class, economyProvider);
        }
        
        getLogger().info("NSF Economy disabled.");
    }
    
    /**
     * Setup Vault economy provider
     */
    private boolean setupVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found!");
            return false;
        }
        
        economyProvider = new NSFEconomyProvider(this);
        
        String priorityStr = getConfig().getString("integrations.vault.priority", "High");
        ServicePriority priority;
        try {
            priority = ServicePriority.valueOf(priorityStr);
        } catch (IllegalArgumentException e) {
            priority = ServicePriority.High;
        }
        
        getServer().getServicesManager().register(
            Economy.class,
            economyProvider,
            this,
            priority
        );
        
        return true;
    }
    
    /**
     * Register all commands
     */
    private void registerCommands() {
        getCommand("bank").setExecutor(new BankCommand(this));
        getCommand("tax").setExecutor(new TaxCommand(this));
        getCommand("bounty").setExecutor(new BountyCommand(this));
        tradeCommand = new TradeCommand(this);
        getCommand("trade").setExecutor(tradeCommand);
        getCommand("permit").setExecutor(new PermitCommand(this));
        getCommand("nsf").setExecutor(new NSFCommand(this));
    }
    
    /**
     * Register all event listeners
     */
    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new BookListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
    }
    
    /**
     * Start scheduled tasks for tax collection, etc.
     */
    private void startScheduledTasks() {
        // Tax reminder task - runs every hour
        Bukkit.getScheduler().runTaskTimerAsync(this, () -> {
            if (getConfig().getBoolean("tax.enabled", true)) {
                taxManager.checkTaxDueDates();
            }
        }, 20L * 60 * 60, 20L * 60 * 60); // Every hour
        
        // Process withdrawal queue - runs every minute
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            bankManager.processWithdrawalQueue();
        }, 20L * 60, 20L * 60); // Every minute
        
        // Permit expiry check - runs every 5 minutes
        Bukkit.getScheduler().runTaskTimerAsync(this, () -> {
            if (getConfig().getBoolean("permits.enabled", true)) {
                permitManager.expireOldPermits();
            }
        }, 20L * 60 * 5, 20L * 60 * 5); // Every 5 minutes
        
        // Auto-enable emergency mode check - runs every 5 minutes
        Bukkit.getScheduler().runTaskTimerAsync(this, () -> {
            double circulating = bankManager.getCirculatingSupply();
            double reserves = bankManager.getReserveBalance();
            double criticalRatio = getConfig().getDouble("bank.critical_reserve_ratio", 0.05);
            
            if (circulating > 0) {
                double currentRatio = reserves / circulating;
                if (currentRatio < criticalRatio && !bankManager.isEmergencyMode()) {
                    // Auto-activate emergency mode
                    bankManager.setEmergencyMode(true);
                    getLogger().warning("Emergency mode AUTO-ACTIVATED due to low reserves!");
                    getServer().broadcastMessage(colorize(
                        "&c[NSF Economy] &7Emergency banking measures activated due to reserve levels."
                    ));
                }
            }
        }, 20L * 60 * 5, 20L * 60 * 5); // Every 5 minutes
    }
    
    /**
     * Reload the plugin configuration
     */
    public void reload() {
        reloadConfig();
        bankManager.loadBankLocations();
        getLogger().info("Configuration reloaded!");
    }
    
    // ══════════════════════════════════════════════════════════════════════
    // Getters
    // ══════════════════════════════════════════════════════════════════════
    
    public static NSFEconomy getInstance() {
        return instance;
    }
    
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
    
    public BankManager getBankManager() {
        return bankManager;
    }
    
    public CurrencyManager getCurrencyManager() {
        return currencyManager;
    }
    
    public TaxManager getTaxManager() {
        return taxManager;
    }
    
    public BountyManager getBountyManager() {
        return bountyManager;
    }
    
    public PermitManager getPermitManager() {
        return permitManager;
    }
    
    public TradeCommand getTradeCommand() {
        return tradeCommand;
    }
    
    public NSFEconomyProvider getEconomyProvider() {
        return economyProvider;
    }
    
    /**
     * Log a debug message if debug mode is enabled
     */
    public void debug(String message) {
        if (getConfig().getBoolean("debug.enabled", false)) {
            getLogger().info("[DEBUG] " + message);
        }
    }
    
    /**
     * Get a formatted message from config
     */
    public String getMessage(String key) {
        String prefix = getConfig().getString("messages.prefix", "&6[Bank]&r ");
        String message = getConfig().getString("messages." + key, "&cMessage not found: " + key);
        return colorize(prefix + message);
    }
    
    /**
     * Get a raw message without prefix
     */
    public String getRawMessage(String key) {
        return colorize(getConfig().getString("messages." + key, "&cMessage not found: " + key));
    }
    
    /**
     * Translate color codes
     */
    public String colorize(String message) {
        return message.replace('&', '§');
    }
}
