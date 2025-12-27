# NSF Economy

A Nether Star-backed Fractional Reserve Banking system for Minecraft servers running Paper/Spigot 1.21+.

## Overview

NSF Economy implements a realistic economic system where:
- **Nether Stars** serve as the reserve asset (like gold in a real economy)
- **F-notes** (Signed Books) are the circulating currency backed by those reserves
- **Fractional Reserve Banking** allows the bank to issue more F-notes than reserves (configurable)
- Built-in **Tax System**, **Bounty Board**, **Dimension Permits**, and **Trade System**

### Key Features

- 🏦 **Physical Currency**: F-notes are actual signed books in players' inventories
- ⚖️ **Fractional Reserve System**: Configurable reserve ratios with emergency modes
- 💰 **Multiple Denominations**: F$1, F$10, F$100 notes
- 📊 **Tax System**: Sales tax on trades, land tax integration ready
- 🎯 **Bounty Board**: Post and claim player-created bounties
- 🌍 **Dimension Permits**: Gate access to Nether/End with purchasable permits
- 🔄 **Vault Integration**: Works with economy plugins expecting Vault API
- 📈 **PlaceholderAPI Support**: Display economy stats in scoreboards, etc.

## Installation

1. **Requirements**:
   - Paper/Spigot 1.21 or higher
   - Java 17+
   - Vault plugin
   - (Optional) PlaceholderAPI for placeholders

2. **Build**:
   ```bash
   mvn clean package
   ```
   The compiled JAR will be in `target/NSFEconomy-1.0.0.jar`

3. **Install**:
   - Copy the JAR to your server's `plugins` folder
   - Restart the server
   - Configure `plugins/NSFEconomy/config.yml`

## Commands

### Bank Commands (`/bank`)
| Command | Description | Permission |
|---------|-------------|------------|
| `/bank deposit <amount>` | Deposit Nether Stars for F-notes | `nsf.bank.deposit` |
| `/bank withdraw <amount>` | Redeem F-notes for Nether Stars | `nsf.bank.withdraw` |
| `/bank balance` | Check F-note holdings | `nsf.bank.balance` |
| `/bank exchange <from> <to> <amount>` | Exchange denominations | `nsf.bank.exchange` |
| `/bank create <name> <type>` | Create bank location | `nsf.admin.bank.create` |
| `/bank reserve` | View reserve statistics | `nsf.admin.bank.reserve` |

### Tax Commands (`/tax`)
| Command | Description | Permission |
|---------|-------------|------------|
| `/tax owed` | View outstanding taxes | `nsf.tax.view` |
| `/tax pay <amount>` | Pay taxes at bank | `nsf.tax.pay` |
| `/tax set <type> <rate>` | Set tax rates | `nsf.admin.tax.set` |
| `/tax forgive <player> <amount>` | Forgive player's taxes | `nsf.admin.tax.forgive` |

### Bounty Commands (`/bounty`)
| Command | Description | Permission |
|---------|-------------|------------|
| `/bounty list [filter]` | List bounties | `nsf.bounty.view` |
| `/bounty post <reward> <desc>` | Post a new bounty | `nsf.bounty.post` |
| `/bounty claim <id>` | Claim a bounty | `nsf.bounty.claim` |
| `/bounty submit <id>` | Submit for approval | `nsf.bounty.submit` |
| `/bounty approve <id>` | Approve submission | `nsf.bounty.approve` |

### Trade Commands (`/trade`)
| Command | Description | Permission |
|---------|-------------|------------|
| `/trade request <player>` | Request trade | `nsf.trade` |
| `/trade accept` | Accept trade request | `nsf.trade` |
| `/trade offer <amount\|hand>` | Offer currency/item | `nsf.trade` |
| `/trade confirm` | Confirm trade | `nsf.trade` |
| `/trade cancel` | Cancel trade | `nsf.trade` |

### Permit Commands (`/permit`)
| Command | Description | Permission |
|---------|-------------|------------|
| `/permit list` | View your permits | `nsf.permit.view` |
| `/permit buy <dimension>` | Purchase permit | `nsf.permit.buy` |
| `/permit extend <dimension>` | Extend permit | `nsf.permit.buy` |
| `/permit check <dimension>` | Check permit status | `nsf.permit.view` |
| `/permit grant <player> <dim>` | Grant permit | `nsf.admin.permit` |

### Admin Commands (`/nsf`)
| Command | Description | Permission |
|---------|-------------|------------|
| `/nsf stats` | View economy statistics | `nsf.admin.stats` |
| `/nsf reload` | Reload configuration | `nsf.admin.reload` |
| `/nsf economy <status\|freeze\|unfreeze>` | Control economy | `nsf.admin.economy` |
| `/nsf emergency <activate\|deactivate>` | Emergency mode | `nsf.admin.emergency` |
| `/nsf audit <player\|full>` | Audit economy | `nsf.admin.audit` |

## Configuration

See `config.yml` for all configuration options including:

- Currency denominations and exchange rates
- Reserve ratios and emergency thresholds
- Tax rates and grace periods
- Permit prices and durations
- Database settings (SQLite or MySQL)
- All message strings (customizable)

## Economy Mechanics

### Exchange Rate
```
1 F$ = 1728 Nether Stars (64 × 27, or one shulker box of stacks)
```

### Reserve Ratio
- **Target Reserve**: 10% (configurable)
- **Critical Threshold**: 5% (triggers emergency mode)
- **Emergency Mode**: Limits withdrawals and applies fees

### F-Note Validation
F-notes are written books with:
- Generation: COPY_OF_ORIGINAL (cannot be copied)
- Unique serial number (UUID) in the ledger
- Author: "Central Bank"

Any modified or counterfeit notes are automatically detected and confiscated.

## PlaceholderAPI

Available placeholders:
- `%nsf_balance%` - Player's F-note holdings
- `%nsf_balance_formatted%` - Formatted balance
- `%nsf_tax_owed%` - Tax owed
- `%nsf_reserve%` - Current reserves
- `%nsf_reserve_ratio%` - Reserve ratio percentage
- `%nsf_circulating%` - Total currency in circulation
- `%nsf_emergency_mode%` - Emergency mode status
- `%nsf_permit_nether%` - Nether permit days remaining
- `%nsf_permit_end%` - End permit days remaining
- `%nsf_at_bank%` - Whether at a bank location

## Database

Supports both SQLite (default) and MySQL:

```yaml
database:
  type: sqlite  # or mysql
  sqlite:
    file: data.db
  mysql:
    host: localhost
    port: 3306
    database: nsf_economy
    username: minecraft
    password: changeme
```

## Project Structure

```
src/main/java/io/github/nsfeconomy/
├── NSFEconomy.java          # Main plugin class
├── bank/
│   ├── BankManager.java     # Banking operations
│   └── BankLocation.java    # Bank location data
├── bounty/
│   └── BountyManager.java   # Bounty board system
├── commands/
│   ├── BankCommand.java
│   ├── BountyCommand.java
│   ├── NSFCommand.java
│   ├── PermitCommand.java
│   ├── TaxCommand.java
│   └── TradeCommand.java
├── currency/
│   └── CurrencyManager.java # F-note creation/validation
├── database/
│   └── DatabaseManager.java # Database operations
├── listeners/
│   ├── BookListener.java    # F-note security
│   └── PlayerListener.java  # Player events
├── permit/
│   └── PermitManager.java   # Dimension permits
├── placeholders/
│   └── NSFPlaceholderExpansion.java
├── tax/
│   └── TaxManager.java      # Tax system
└── vault/
    └── NSFEconomyProvider.java # Vault integration
```

## Dependencies

- Paper API 1.21+
- Vault API
- HikariCP (for database pooling)
- PlaceholderAPI (optional)

## License

MIT License - See LICENSE file

## Credits

Designed based on the NSF Economy Plugin Design Document.
