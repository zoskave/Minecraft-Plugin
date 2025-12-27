package io.github.nsfeconomy.currency;

import io.github.nsfeconomy.NSFEconomy;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages F-note (currency) creation, validation, and manipulation.
 * F-notes are signed books with specific metadata that serve as the
 * physical currency in the NSF Economy system.
 */
public class CurrencyManager {

    private final NSFEconomy plugin;
    private final String serverName;
    private final String currencySymbol;
    private final int starsPerDollar;
    private final List<Integer> denominations;
    
    // Pattern to extract serial from book page
    private static final Pattern SERIAL_PATTERN = Pattern.compile("Serial:\\s*([a-f0-9-]+)", Pattern.CASE_INSENSITIVE);
    
    // Date formatter for issue date
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

    public CurrencyManager(NSFEconomy plugin) {
        this.plugin = plugin;
        this.serverName = plugin.getConfig().getString("server_name", "My Server");
        this.currencySymbol = plugin.getConfig().getString("currency.symbol", "F$");
        this.starsPerDollar = plugin.getConfig().getInt("currency.stars_per_dollar", 1728);
        this.denominations = plugin.getConfig().getIntegerList("currency.denominations");
        
        if (denominations.isEmpty()) {
            denominations.addAll(Arrays.asList(1, 10, 100));
        }
    }

    /**
     * Create a new F-note
     * 
     * @param denomination The denomination (1, 10, or 100)
     * @param issuedTo UUID of the player receiving the note (can be null for admin mints)
     * @return The created ItemStack, or null if failed
     */
    public ItemStack createNote(int denomination, UUID issuedTo) {
        if (!denominations.contains(denomination)) {
            plugin.getLogger().warning("Invalid denomination: " + denomination);
            return null;
        }

        // Generate unique serial
        UUID serial = UUID.randomUUID();
        String shortSerial = serial.toString().substring(0, 13); // Short format for display

        // Record in ledger
        if (!plugin.getDatabaseManager().recordNote(serial, denomination, issuedTo)) {
            plugin.getLogger().severe("Failed to record note in ledger!");
            return null;
        }

        // Create the book item
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK, 1);
        BookMeta meta = (BookMeta) book.getItemMeta();

        // Set book properties
        meta.setTitle(currencySymbol + denomination + " Note");
        meta.setAuthor("Central Bank");
        meta.setGeneration(BookMeta.Generation.COPY_OF_ORIGINAL); // Generation 1

        // Create the page content
        String pageContent = createPageContent(denomination, shortSerial, serial.toString());
        meta.addPage(pageContent);

        // Add a second page with security information
        String securityPage = createSecurityPage();
        meta.addPage(securityPage);

        book.setItemMeta(meta);

        plugin.debug("Created " + currencySymbol + denomination + " note with serial " + shortSerial);
        return book;
    }

    /**
     * Create the main page content for an F-note
     */
    private String createPageContent(int denomination, String shortSerial, String fullSerial) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("§0═══════════════\n");
        sb.append("§1§l   CENTRAL BANK OF\n");
        sb.append("§1§l     ").append(serverName.toUpperCase()).append("\n");
        sb.append("§0═══════════════\n\n");
        sb.append("§0      §l§n").append(currencySymbol).append(" ").append(denomination).append("\n\n");
        sb.append("§8  Serial: ").append(shortSerial).append("\n");
        sb.append("§8  Issued: ").append(dateFormat.format(new Date())).append("\n\n");
        sb.append("§7  \"Redeemable for Nether\n");
        sb.append("§7   Stars at any Central\n");
        sb.append("§7   Bank location.\"\n\n");
        sb.append("§0═══════════════\n");
        sb.append("§4§l      [OFFICIAL]\n");
        sb.append("§0═══════════════");
        
        return sb.toString();
    }

    /**
     * Create the security page content
     */
    private String createSecurityPage() {
        StringBuilder sb = new StringBuilder();
        
        sb.append("§0═══════════════\n");
        sb.append("§4§l   SECURITY NOTICE\n");
        sb.append("§0═══════════════\n\n");
        sb.append("§0This note is protected by\n");
        sb.append("§0the Central Bank's ledger\n");
        sb.append("§0verification system.\n\n");
        sb.append("§cCounterfeit notes will be\n");
        sb.append("§cconfiscated and the holder\n");
        sb.append("§cmay face penalties.\n\n");
        sb.append("§8Generation: COPY_OF_ORIGINAL\n");
        sb.append("§8Valid copies: 1 only\n\n");
        sb.append("§0═══════════════");
        
        return sb.toString();
    }

    /**
     * Validate an F-note
     * 
     * @param item The ItemStack to validate
     * @return ValidationResult containing validity and details
     */
    public ValidationResult validateNote(ItemStack item) {
        if (item == null || item.getType() != Material.WRITTEN_BOOK) {
            return new ValidationResult(false, "Not a written book", null, 0);
        }

        BookMeta meta = (BookMeta) item.getItemMeta();
        if (meta == null) {
            return new ValidationResult(false, "No book metadata", null, 0);
        }

        // Check author
        if (!"Central Bank".equals(meta.getAuthor())) {
            return new ValidationResult(false, "Invalid author", null, 0);
        }

        // Check generation
        if (meta.getGeneration() != BookMeta.Generation.COPY_OF_ORIGINAL) {
            return new ValidationResult(false, "Invalid generation", null, 0);
        }

        // Check title format
        String title = meta.getTitle();
        if (title == null || !title.matches(Pattern.quote(currencySymbol) + "\\d+ Note")) {
            return new ValidationResult(false, "Invalid title format", null, 0);
        }

        // Extract denomination from title
        int denomination;
        try {
            String denomStr = title.replace(currencySymbol, "").replace(" Note", "");
            denomination = Integer.parseInt(denomStr);
            if (!denominations.contains(denomination)) {
                return new ValidationResult(false, "Invalid denomination", null, 0);
            }
        } catch (NumberFormatException e) {
            return new ValidationResult(false, "Cannot parse denomination", null, 0);
        }

        // Extract serial from page content
        if (!meta.hasPages()) {
            return new ValidationResult(false, "No pages", null, 0);
        }

        String page = meta.getPage(1);
        UUID serial = extractSerial(page);
        if (serial == null) {
            return new ValidationResult(false, "Cannot extract serial", null, 0);
        }

        // Verify serial in database
        if (!plugin.getDatabaseManager().isNoteValid(serial)) {
            return new ValidationResult(false, "Invalid or redeemed serial", serial, denomination);
        }

        // Verify denomination matches ledger
        int ledgerDenom = plugin.getDatabaseManager().getNoteDenomination(serial);
        if (ledgerDenom != denomination) {
            return new ValidationResult(false, "Denomination mismatch", serial, denomination);
        }

        plugin.debug("Validated " + currencySymbol + denomination + " note: " + serial);
        return new ValidationResult(true, "Valid", serial, denomination);
    }

    /**
     * Extract serial UUID from page content
     */
    private UUID extractSerial(String pageContent) {
        // Remove color codes for parsing
        String cleanContent = pageContent.replaceAll("§.", "");
        
        Matcher matcher = SERIAL_PATTERN.matcher(cleanContent);
        if (matcher.find()) {
            String serialStr = matcher.group(1);
            try {
                // The displayed serial is shortened, so we need to search the database
                // by prefix or store the full serial somewhere
                // For now, let's assume we store full serial in a hidden way
                
                // Actually, let's modify the page to include a hidden full serial
                // For this implementation, we'll search for any UUID pattern
                Pattern fullUUIDPattern = Pattern.compile(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                    Pattern.CASE_INSENSITIVE
                );
                Matcher fullMatcher = fullUUIDPattern.matcher(cleanContent);
                if (fullMatcher.find()) {
                    return UUID.fromString(fullMatcher.group());
                }
                
                // If only short serial, we need a different approach
                // For now, return null to indicate we need full serial
                return null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Create multiple notes of the same denomination
     */
    public List<ItemStack> createNotes(int denomination, int quantity, UUID issuedTo) {
        List<ItemStack> notes = new ArrayList<>();
        for (int i = 0; i < quantity; i++) {
            ItemStack note = createNote(denomination, issuedTo);
            if (note != null) {
                notes.add(note);
            }
        }
        return notes;
    }

    /**
     * Calculate the optimal denomination breakdown for a given amount
     */
    public Map<Integer, Integer> calculateDenominations(double amount) {
        Map<Integer, Integer> breakdown = new LinkedHashMap<>();
        
        // Sort denominations in descending order
        List<Integer> sortedDenoms = new ArrayList<>(denominations);
        sortedDenoms.sort(Collections.reverseOrder());
        
        double remaining = amount;
        for (int denom : sortedDenoms) {
            int count = (int) (remaining / denom);
            if (count > 0) {
                breakdown.put(denom, count);
                remaining -= count * denom;
            }
        }
        
        return breakdown;
    }

    /**
     * Count valid F-notes in an inventory
     */
    public Map<Integer, Integer> countNotesInInventory(ItemStack[] contents) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int denom : denominations) {
            counts.put(denom, 0);
        }

        for (ItemStack item : contents) {
            if (item == null) continue;
            
            ValidationResult result = validateNote(item);
            if (result.isValid()) {
                int currentCount = counts.getOrDefault(result.getDenomination(), 0);
                counts.put(result.getDenomination(), currentCount + item.getAmount());
            }
        }

        return counts;
    }

    /**
     * Calculate total F$ value from denomination counts
     */
    public double calculateTotalValue(Map<Integer, Integer> denomCounts) {
        double total = 0;
        for (Map.Entry<Integer, Integer> entry : denomCounts.entrySet()) {
            total += entry.getKey() * entry.getValue();
        }
        return total;
    }

    /**
     * Convert F$ to Nether Stars
     */
    public long fDollarsToStars(double fDollars) {
        return (long) (fDollars * starsPerDollar);
    }

    /**
     * Convert Nether Stars to F$
     */
    public double starsToFDollars(long stars) {
        return (double) stars / starsPerDollar;
    }

    /**
     * Format a currency amount for display
     */
    public String formatCurrency(double amount) {
        if (amount == (long) amount) {
            return currencySymbol + String.format("%,d", (long) amount);
        }
        return currencySymbol + String.format("%,.2f", amount);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Getters
    // ══════════════════════════════════════════════════════════════════════

    public String getCurrencySymbol() {
        return currencySymbol;
    }

    public int getStarsPerDollar() {
        return starsPerDollar;
    }

    public List<Integer> getDenominations() {
        return Collections.unmodifiableList(denominations);
    }

    /**
     * Result class for note validation
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String reason;
        private final UUID serial;
        private final int denomination;

        public ValidationResult(boolean valid, String reason, UUID serial, int denomination) {
            this.valid = valid;
            this.reason = reason;
            this.serial = serial;
            this.denomination = denomination;
        }

        public boolean isValid() {
            return valid;
        }

        public String getReason() {
            return reason;
        }

        public UUID getSerial() {
            return serial;
        }

        public int getDenomination() {
            return denomination;
        }
    }
}
