package io.github.nsfeconomy.commands;

import io.github.nsfeconomy.NSFEconomy;
import io.github.nsfeconomy.permit.PermitManager;
import io.github.nsfeconomy.permit.PermitManager.Permit;
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
 * Handles /permit commands for dimension access
 */
public class PermitCommand implements CommandExecutor, TabCompleter {

    private final NSFEconomy plugin;

    public PermitCommand(NSFEconomy plugin) {
        this.plugin = plugin;
        plugin.getCommand("permit").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "list" -> handleList(sender, args);
            case "buy" -> handleBuy(sender, args);
            case "extend" -> handleExtend(sender, args);
            case "check" -> handleCheck(sender, args);
            case "info" -> handleInfo(sender, args);
            case "grant" -> handleGrant(sender, args);
            case "revoke" -> handleRevoke(sender, args);
            default -> sendHelp(sender);
        }

        return true;
    }

    private void handleList(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.permit.view")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        PermitManager permitManager = plugin.getPermitManager();
        List<Permit> permits = permitManager.getPlayerPermits(player.getUniqueId());

        sender.sendMessage(plugin.colorize("&6══════ &lYour Permits &r&6══════"));
        
        if (permits.isEmpty()) {
            sender.sendMessage(plugin.colorize("&7You have no active permits."));
        } else {
            for (Permit permit : permits) {
                String status = permit.isExpired() ? "&c[EXPIRED]" : "&a[ACTIVE]";
                long days = permit.getDaysRemaining();
                sender.sendMessage(plugin.colorize(String.format(
                    "  %s &f%s &7- %d days remaining",
                    status,
                    capitalize(permit.getDimension()),
                    days
                )));
            }
        }
        
        sender.sendMessage(plugin.colorize("&6══════════════════════════════"));
        sender.sendMessage(plugin.colorize("&7Use &e/permit info <dimension> &7for pricing."));
    }

    private void handleBuy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.permit.buy")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize("&cUsage: /permit buy <dimension>"));
            showAvailableDimensions(sender);
            return;
        }

        String dimension = args[1].toLowerCase();
        PermitManager permitManager = plugin.getPermitManager();

        // Validate dimension
        if (!permitManager.getAvailableDimensions().contains(dimension)) {
            sender.sendMessage(plugin.colorize("&cUnknown dimension: " + dimension));
            showAvailableDimensions(sender);
            return;
        }

        // Check if at bank
        if (!plugin.getBankManager().isAtBank(player)) {
            sender.sendMessage(plugin.getMessage("not_at_bank"));
            return;
        }

        double price = permitManager.getPermitPrice(dimension);
        int duration = permitManager.getPermitDuration(dimension);

        // TODO: Verify player has enough F-notes

        PermitManager.PermitResult result = permitManager.purchasePermit(
            player.getUniqueId(),
            dimension
        );

        if (result.isSuccess()) {
            sender.sendMessage(plugin.colorize("&a" + capitalize(dimension) + " permit purchased!"));
            sender.sendMessage(plugin.colorize("&7Duration: &e" + duration + " days"));
            sender.sendMessage(plugin.colorize("&7Cost: &e" + 
                plugin.getCurrencyManager().formatCurrency(price)));
        } else {
            sender.sendMessage(plugin.colorize("&c" + result.getMessage()));
        }
    }

    private void handleExtend(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.permit.buy")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize("&cUsage: /permit extend <dimension>"));
            return;
        }

        String dimension = args[1].toLowerCase();
        PermitManager permitManager = plugin.getPermitManager();

        // Check if at bank
        if (!plugin.getBankManager().isAtBank(player)) {
            sender.sendMessage(plugin.getMessage("not_at_bank"));
            return;
        }

        PermitManager.PermitResult result = permitManager.extendPermit(
            player.getUniqueId(),
            dimension
        );

        if (result.isSuccess()) {
            Permit permit = permitManager.getPermit(player.getUniqueId(), dimension);
            sender.sendMessage(plugin.colorize("&aPermit extended!"));
            if (permit != null) {
                sender.sendMessage(plugin.colorize("&7New expiry: &e" + 
                    permit.getExpiresAt().toString().substring(0, 10)));
            }
        } else {
            sender.sendMessage(plugin.colorize("&c" + result.getMessage()));
        }
    }

    private void handleCheck(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.permit.view")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize("&cUsage: /permit check <dimension>"));
            return;
        }

        String dimension = args[1].toLowerCase();
        PermitManager permitManager = plugin.getPermitManager();

        if (permitManager.hasValidPermit(player.getUniqueId(), dimension)) {
            Permit permit = permitManager.getPermit(player.getUniqueId(), dimension);
            sender.sendMessage(plugin.colorize("&aYou have a valid " + capitalize(dimension) + " permit!"));
            if (permit != null) {
                sender.sendMessage(plugin.colorize("&7Days remaining: &e" + permit.getDaysRemaining()));
                sender.sendMessage(plugin.colorize("&7Expires: &e" + 
                    permit.getExpiresAt().toString().substring(0, 10)));
            }
        } else {
            sender.sendMessage(plugin.colorize("&cYou do not have a valid " + capitalize(dimension) + " permit."));
            sender.sendMessage(plugin.colorize("&7Purchase one with &e/permit buy " + dimension));
        }
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            // Show all available dimensions
            showAvailableDimensions(sender);
            return;
        }

        String dimension = args[1].toLowerCase();
        PermitManager permitManager = plugin.getPermitManager();

        if (!permitManager.getAvailableDimensions().contains(dimension)) {
            sender.sendMessage(plugin.colorize("&cUnknown dimension: " + dimension));
            showAvailableDimensions(sender);
            return;
        }

        double price = permitManager.getPermitPrice(dimension);
        int duration = permitManager.getPermitDuration(dimension);

        sender.sendMessage(plugin.colorize("&6══════ &l" + capitalize(dimension) + " Permit &r&6══════"));
        sender.sendMessage(plugin.colorize("&7Price: &e" + 
            plugin.getCurrencyManager().formatCurrency(price)));
        sender.sendMessage(plugin.colorize("&7Duration: &e" + duration + " days"));
        sender.sendMessage(plugin.colorize("&7Purchase at any bank location."));
        sender.sendMessage(plugin.colorize("&6══════════════════════════════════"));
    }

    private void handleGrant(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.permit")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.colorize("&cUsage: /permit grant <player> <dimension> [days]"));
            return;
        }

        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.colorize("&cPlayer not found: " + args[1]));
            return;
        }

        String dimension = args[2].toLowerCase();
        int days = args.length > 3 ? Integer.parseInt(args[3]) : 
                   plugin.getPermitManager().getPermitDuration(dimension);

        PermitManager.PermitResult result = plugin.getPermitManager().grantPermit(
            target.getUniqueId(),
            dimension,
            days
        );

        if (result.isSuccess()) {
            sender.sendMessage(plugin.colorize("&aGranted " + capitalize(dimension) + 
                " permit to " + target.getName() + " for " + days + " days."));
            target.sendMessage(plugin.colorize("&aYou have been granted a " + 
                capitalize(dimension) + " permit for " + days + " days!"));
        } else {
            sender.sendMessage(plugin.colorize("&c" + result.getMessage()));
        }
    }

    private void handleRevoke(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.permit")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.colorize("&cUsage: /permit revoke <player> <dimension>"));
            return;
        }

        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.colorize("&cPlayer not found: " + args[1]));
            return;
        }

        String dimension = args[2].toLowerCase();

        if (plugin.getPermitManager().revokePermit(target.getUniqueId(), dimension)) {
            sender.sendMessage(plugin.colorize("&aRevoked " + capitalize(dimension) + 
                " permit from " + target.getName()));
            target.sendMessage(plugin.colorize("&cYour " + capitalize(dimension) + 
                " permit has been revoked!"));
        } else {
            sender.sendMessage(plugin.colorize("&cPlayer does not have that permit."));
        }
    }

    private void showAvailableDimensions(CommandSender sender) {
        List<String> dimensions = plugin.getPermitManager().getAvailableDimensions();
        
        sender.sendMessage(plugin.colorize("&7Available dimensions: &e" + 
            String.join(", ", dimensions)));
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.colorize("&6══════ &lNSF Economy - Permit Commands &r&6══════"));
        sender.sendMessage(plugin.colorize("&e/permit list &7- View your permits"));
        sender.sendMessage(plugin.colorize("&e/permit buy <dimension> &7- Purchase a permit"));
        sender.sendMessage(plugin.colorize("&e/permit extend <dimension> &7- Extend permit"));
        sender.sendMessage(plugin.colorize("&e/permit check <dimension> &7- Check permit status"));
        sender.sendMessage(plugin.colorize("&e/permit info [dimension] &7- View permit pricing"));
        
        if (sender.hasPermission("nsf.admin.permit")) {
            sender.sendMessage(plugin.colorize("&c/permit grant <player> <dimension> [days] &7- Grant permit"));
            sender.sendMessage(plugin.colorize("&c/permit revoke <player> <dimension> &7- Revoke permit"));
        }
        
        sender.sendMessage(plugin.colorize("&6═══════════════════════════════════════════"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            completions.addAll(Arrays.asList("list", "buy", "extend", "check", "info"));
            if (sender.hasPermission("nsf.admin.permit")) {
                completions.addAll(Arrays.asList("grant", "revoke"));
            }
            return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        
        if (args.length == 2) {
            if (Arrays.asList("buy", "extend", "check", "info").contains(args[0].toLowerCase())) {
                completions.addAll(plugin.getPermitManager().getAvailableDimensions());
            } else if (Arrays.asList("grant", "revoke").contains(args[0].toLowerCase())) {
                return null; // Show player names
            }
        }
        
        if (args.length == 3) {
            if (Arrays.asList("grant", "revoke").contains(args[0].toLowerCase())) {
                completions.addAll(plugin.getPermitManager().getAvailableDimensions());
            }
        }
        
        return completions.stream()
            .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
            .collect(Collectors.toList());
    }
}
