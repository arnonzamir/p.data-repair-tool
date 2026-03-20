# Purchase Repair Tool

Investigates, diagnoses, and repairs broken loans in Sunbit's legacy LMS payment system.

## Quick Start

```bash
# Backend (port 8090) -- opens browser for Okta SSO on first Snowflake query
gradle bootRun

# Frontend (port 3000)
cd frontend && npm start
```

Open http://localhost:3000. Enter your name, then a purchase ID.

## What It Does

- **Loads** purchase data from Snowflake (BRONZE + GOLD schemas) with real-time progress
- **Caches** snapshots in SQLite (`~/.sunbit/purchase-repair-cache.db`) for instant reloads
- **Analyzes** with 17 rules that detect ghost payments, money gaps, duplicate charges, schedule inconsistencies, etc.
- **Merges** charge data from purchase-service and charge-service into a unified view
- **Replicates** purchases to local/staging MySQL (both purchase + charge schemas) with PII anonymization
- **Tracks** investigation state: notes, review status, purchase lists

## Architecture

```
Frontend (React/TS :3000)  -->  Backend (Kotlin/Spring :8090)  -->  Snowflake (Okta SSO)
                                       |                               |
                                  SQLite cache                    BRONZE.PURCHASE.*
                                  (~/.sunbit/)                    BRONZE.CHARGE.*
                                       |                          GOLD.COMMUNICATION_HUB.*
                                  Local MySQL
                                  (purchase + charge schemas)
```

## Tabs

| Tab | What it shows |
|-----|--------------|
| Payments | All payments with source, status, paid-off date, parent chain. Click to expand detail. |
| Charges | Unified view merging charge_transactions + loan_transactions + payment_attempts across both schemas. |
| Disbursals | Multi-disbursal schedule (release dates, amounts, adjustments). Only for multi-disbursal loans. |
| Timeline | Chronological stream of all events from customer's perspective. |
| Purchase Actions | Loan management operations (pay-now, amount change, workout, etc.). |
| Notifications | Emails sent/missing/erroneous with call center deep links. |
| Tickets | Support tickets with call center deep links. |
| Issues | Analysis findings grouped by severity with suggested repairs. |

## Analysis Rules (17)

| Rule | Severity | Detects |
|------|----------|---------|
| ghost-payment | CRITICAL | Active payment whose parent is also active |
| duplicate-active-same-date | CRITICAL/MEDIUM | Two+ active payments on same date (CRITICAL if both automated) |
| money-gap | LOW-HIGH | Schedule or money gap (severity by size and direction) |
| cross-schema-desync | CRITICAL/LOW | Row-level mismatch between purchase and charge schemas |
| missing-rebalance | HIGH | Paid unscheduled payment with no rebalanced children |
| duplicate-charge | HIGH | Multiple paid active scheduled payments on same date |
| payment-tree-integrity | HIGH | Payment trees with zero or multiple active members |
| zero-amount-active | HIGH | Active payment with amount <= 0 |
| orphaned-payment | MEDIUM | Mutation-created payment missing expected parent |
| inconsistent-schedule | MEDIUM | Gap > 45 days between consecutive scheduled payments |
| rapid-duplicate-charges | MEDIUM | Similar amounts charged within 14 days |
| stale-manual-until | MEDIUM | Expired manual_until flag |
| chargeback-without-resolution | MEDIUM | Unresolved chargeback |
| loan-transaction-mismatch | MEDIUM/HIGH | Loan transaction differs from payment (post-charge mutation) |
| residual-principal | MEDIUM | Last payment principal balance > 50% of amount |
| apr-mismatch | LOW | Nominal vs effective APR divergence > 1 point |
| missing-notification | LOW | Charge without corresponding email |

## Replication

Replicates a purchase from Snowflake to local/staging MySQL with:
- Full purchase schema: customer, payments, plans, charge transactions, etc.
- Full charge schema: payment profiles, debit methods, payment attempts, statuses
- PII anonymization (deterministic fake identities)
- `INSERT IGNORE` for shared entities (customers, profiles)
- Rollback scripts stored in SQLite
- Customer-retailer replicated with local retailer ID override

## Configuration

`src/main/resources/application.yaml`:
- Snowflake credentials (defaults to Okta SSO)
- Call center URLs (prod/staging/local)
- Replication defaults (retailer IDs per target)

## Requirements

- Java 21
- Node.js 18+
- Snowflake access (Okta SSO)
- Local MySQL at sunbit-mysql:30306 (for replication)
