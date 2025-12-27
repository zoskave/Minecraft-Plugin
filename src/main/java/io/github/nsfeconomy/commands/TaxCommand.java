package io.github.nsfeconomy.commands;

import io.github.nsfeconomy.NSFEconomy;
import io.github.nsfeconomy.tax.TaxManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles /tax commands
 */
public class TaxCommand implements CommandExecutor, TabCompleter {

    private final NSFEconomy plugin;

    public TaxCommand(NSFEconomy plugin) {
        this.plugin = plugin;
        plugin.getCommand("tax").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "owed" -> handleOwed(sender, args);
            case "pay" -> handlePay(sender, args);
            case "history" -> handleHistory(sender, args);
            case "set" -> handleSet(sender, args);
            case "forgive" -> handleForgive(sender, args);
            default -> sendHelp(sender);
        }

        return true;
    }

    private void handleOwed(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.tax.view")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        TaxManager taxManager = plugin.getTaxManager();
        List<TaxManager.TaxObligation> obligations = taxManager.getUnpaidTaxes(player.getUniqueId());
        double total = taxManager.getTotalOwed(player.getUniqueId());

        if (obligations.isEmpty()) {
            sender.sendMessage(plugin.colorize("&aYou have no outstanding taxes. Good citizen!"));
            return;
        }

        sender.sendMessage(plugin.colorize("&6══════ &lYour Tax Obligations &r&6══════"));
        for (TaxManager.TaxObligation obligation : obligations) {
            sender.sendMessage(plugin.colorize(String.format(
                "  &7[%s] &f%s &7- Due: &e%s",
                obligation.getType(),
                plugin.getCurrencyManager().formatCurrency(obligation.getAmount()),
                obligation.getDueDate().toString().substring(0, 10)
            )));
        }
        sender.sendMessage(plugin.colorize("&6Total Owed: &c" + plugin.getCurrencyManager().formatCurrency(total)));
        sender.sendMessage(plugin.colorize("&6════════════════════════════════════"));
    }

    private void handlePay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.tax.pay")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize("&cUsage: /tax pay <amount>"));
            return;
        }

        double amount;
        try {
            String amountStr = args[1].replace(plugin.getCurrencyManager().getCurrencySymbol(), "");
            amount = Double.parseDouble(amountStr);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessage("error_invalid_amount"));
            return;
        }

        // Check if at bank
        if (!plugin.getBankManager().isAtBank(player)) {
            sender.sendMessage(plugin.getMessage("not_at_bank"));
            return;
        }

        // TODO: Verify player has enough F-notes and process payment
        TaxManager.PaymentResult result = plugin.getTaxManager().payTaxes(player.getUniqueId(), amount);

        if (result.isSuccess()) {
            String msg = plugin.getRawMessage("tax_paid")
                .replace("{amount}", plugin.getCurrencyManager().formatCurrency(result.getAmountPaid()));
            sender.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.prefix", "") + msg));
        } else {
            sender.sendMessage(plugin.colorize("&cFailed to process payment: " + result.getMessage()));
        }
    }

    private void handleHistory(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.tax.view")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        // TODO: Implement payment history
        sender.sendMessage(plugin.colorize("&7Tax payment history coming soon."));
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.tax.set")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.colorize("&cUsage: /tax set <type> <rate>"));
            sender.sendMessage(plugin.colorize("&7Types: sales_tax, land_tax"));
            return;
        }

        String type = args[1].toLowerCase();
        double rate;
        
        try {
            rate = Double.parseDouble(args[2]);
            if (rate < 0 || rate > 1) {
                sender.sendMessage(plugin.colorize("&cRate must be between 0 and 1 (e.g., 0.05 for 5%)"));
                return;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessage("error_invalid_amount"));
            return;
        }

        switch (type) {
            case "sales_tax" -> plugin.getConfig().set("tax.sales_tax.rate", rate);
            case "land_tax" -> plugin.getConfig().set("tax.land_tax.rate_per_block", rate);
            default -> {
                sender.sendMessage(plugin.colorize("&cUnknown tax type. Use: sales_tax, land_tax"));
                return;
            }
        }

        plugin.saveConfig();
        sender.sendMessage(plugin.colorize("&a" + type + " rate set to " + String.format("%.2f%%", rate * 100)));
    }

    private void handleForgive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.tax.forgive")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.colorize("&cUsage: /tax forgive <player> <amount>"));
            return;
        }

        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.colorize("&cPlayer not found: " + args[1]));
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessage("error_invalid_amount"));
            return;
        }

        if (plugin.getTaxManager().forgiveTaxes(target.getUniqueId(), amount)) {
            sender.sendMessage(plugin.colorize("&aForgave " + plugin.getCurrencyManager().formatCurrency(amount) + 
                " of " + target.getName() + "'s taxes."));
            target.sendMessage(plugin.colorize("&aAn admin has forgiven " + 
                plugin.getCurrencyManager().formatCurrency(amount) + " of your taxes!"));
        } else {
            sender.sendMessage(plugin.colorize("&cFailed to forgive taxes."));
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.colorize("&6══════ &lNSF Economy - Tax Commands &r&6══════"));
        sender.sendMessage(plugin.colorize("&e/tax owed &7- View your tax obligations"));
        sender.sendMessage(plugin.colorize("&e/tax pay <amount> &7- Pay your taxes"));
        sender.sendMessage(plugin.colorize("&e/tax history &7- View payment history"));
        
        if (sender.hasPermission("nsf.admin.tax.set")) {
            sender.sendMessage(plugin.colorize("&c/tax set <type> <rate> &7- Set tax rates"));
            sender.sendMessage(plugin.colorize("&c/tax forgive <player> <amount> &7- Forgive taxes"));
        }
        
        sender.sendMessage(plugin.colorize("&6════════════════════════════════════════"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            List<String> subCommands = new ArrayList<>(Arrays.asList("owed", "pay", "history"));
            if (sender.hasPermission("nsf.admin.tax.set")) {
                subCommands.addAll(Arrays.asList("set", "forgive"));
            }
            return subCommands.stream()
                .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("set")) {
                completions.addAll(Arrays.asList("sales_tax", "land_tax"));
            } else if (args[0].equalsIgnoreCase("forgive")) {
                return null; // Show player names
            }
        }
        
        return completions.stream()
            .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
            .collect(Collectors.toList());
    }
}
