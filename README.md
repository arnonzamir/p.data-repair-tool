# Purchase Repair Tool

Investigates, diagnoses, and repairs broken loans in Sunbit's legacy LMS payment system.

## Install (Docker, recommended)

```bash
# One-liner (requires gh CLI, which most devs have):
curl -sH "Authorization: token $(gh auth token)" https://raw.githubusercontent.com/sunbit-dev/account-management-service/purchase-repair-sync/repair-tool/install.sh | bash

# Or clone and run:
git clone -b purchase-repair-sync git@github.com:sunbit-dev/account-management-service.git purchase-repair
cd purchase-repair/repair-tool
bash install.sh

# Or manually (no clone needed):
docker pull sunbit/arnon-temp:purchase-repair-tool
mkdir -p ~/.sunbit
docker run -d --name repair-tool --restart unless-stopped \
  -p 8090:8090 \
  -e SNOWFLAKE_USER=your-name@sunbit.com \
  -v ~/.ssh:/root/.ssh-host:ro \
  -v ~/.sunbit:/root/.sunbit \
  -v ~/.sunbit/snowflake_cache:/root/.cache/snowflake \
  sunbit/arnon-temp:purchase-repair-tool
```

Open http://localhost:8090. Enter your name as operator (top right).

### What you need

- **Docker Desktop** running
- **Snowflake access** via Okta SSO (first query opens browser for login)
- **SSH key** registered with GitHub (for team sync). Check with `ssh -T git@github.com`

### Persistence

Your data is stored in `~/.sunbit/` on your machine (mounted into the container):
- Cached purchase snapshots (so you don't re-query Snowflake)
- Purchase lists, notes, review statuses
- Audit log of all your actions

This data survives container restarts, upgrades, and recreations.

### Team sync

Lists, notes, review statuses, and audit logs sync across team members via git.
The container clones the sync repo on startup and polls every 30 seconds.
When you make a change (add a note, update a status), it commits and pushes automatically.

### Update

```bash
docker pull sunbit/arnon-temp:purchase-repair-tool
docker stop repair-tool && docker rm repair-tool
# Re-run the docker run command above (your data in ~/.sunbit/ is preserved)
```

### Commands

| Action | Command |
|--------|---------|
| Stop | `docker stop repair-tool` |
| Start | `docker start repair-tool` |
| Logs | `docker logs -f repair-tool` |
| Restart | `docker restart repair-tool` |
| Remove (keeps data) | `docker stop repair-tool && docker rm repair-tool` |

## What It Does

- **Loads** purchase data from Snowflake (BRONZE + GOLD schemas) with real-time progress
- **Caches** snapshots in SQLite for instant reloads
- **Analyzes** with 23 rules that detect ghost payments, money gaps, duplicate charges, schedule inconsistencies, and more
- **Fixes** with 8 loan manipulators that can run against LOCAL, STAGING, or PROD
- **Merges** charge data from purchase-service and charge-service into a unified view
- **Replicates** purchases to local/staging MySQL with PII anonymization
- **Syncs** investigation state across team members via git

## Tabs

| Tab | What it shows |
|-----|--------------|
| Payments | All payments with source, status, issues inline. Click to expand detail. |
| Charges | Unified view merging charge_transactions + loan_transactions + payment_attempts across both schemas. |
| Disbursals | Planned vs actual disbursal schedule. Only for multi-disbursal loans. |
| Timeline | Chronological stream of all events from customer's perspective. |
| Purchase Actions | Loan management operations (pay-now, amount change, workout, etc.). |
| Notifications | Emails sent/missing/erroneous with call center deep links. |
| Tickets | Support tickets with call center deep links. |
| Issues | Analysis findings with severity, explanations, and suggested repairs. |
| Fix | Applicable loan manipulators with preview, execute, and verify. |

## Analysis Rules (23)

| Rule | Severity | Detects |
|------|----------|---------|
| ghost-payment | CRITICAL | Active payment whose parent is also active |
| suspicious-overlap | CRITICAL | Similar-amount payments on nearby dates (missed deactivation) |
| zero-amount-active | CRITICAL | Active payment with amount <= 0 |
| duplicate-active-same-date | CRITICAL/MEDIUM | Two+ active payments on same date |
| money-gap | LOW-HIGH | Schedule or money gap (severity by size and direction) |
| cross-schema-desync | CRITICAL/LOW | Row-level mismatch between purchase and charge schemas |
| missing-rebalance | HIGH | Paid unscheduled payment with no rebalanced children |
| duplicate-charge | HIGH/LOW | Multiple paid scheduled payments on same date (context-aware) |
| payment-tree-integrity | CRITICAL/HIGH | Payment trees with zero or multiple active members |
| orphaned-payment | MEDIUM | Mutation-created payment missing expected parent |
| inconsistent-schedule | MEDIUM | Gap > 45 days between consecutive scheduled payments |
| rapid-duplicate-charges | MEDIUM | Similar amounts charged within 14 days |
| stale-manual-until | MEDIUM | Expired manual_until flag |
| chargeback-without-resolution | MEDIUM | Unresolved chargeback |
| stuck-chargeback | MEDIUM | Re-charge attempted but payment still unpaid |
| cpp-status-inconsistency | MEDIUM | CPP status doesn't match actual payment states |
| refund-without-unpay | MEDIUM | CI=32 payment with both refundDate and paidOffDate |
| incomplete-rebalance | MEDIUM | Mixed paymentActionIds among unpaid schedule |
| zombie-unscheduled | HIGH | Active unpaid unscheduled payment (charge risk) |
| loan-transaction-mismatch | MEDIUM/HIGH | Loan transaction differs from payment |
| residual-principal | MEDIUM | Last payment principal balance > 50% of amount |
| apr-mismatch | LOW | Nominal vs effective APR divergence > 1 point |
| missing-notification | LOW | Charge without corresponding email |

## Loan Manipulators (8)

| Manipulator | Category | What it does |
|-------------|----------|-------------|
| fix-ghost-payment | STRUCTURAL | Unpays ghost payments (active children whose parent is active) |
| fix-duplicate-charge | STRUCTURAL | Unpays duplicate paid payments, keeps the earliest |
| fix-orphaned-payment | STRUCTURAL | Deactivates orphaned CI=8 scheduled payments |
| fix-suspicious-overlap | STRUCTURAL | Removes overlapping payment and rebalances |
| rebalance-schedule | FINANCIAL | Forces schedule recalculation |
| recalculate-cpp-status | FINANCIAL | Triggers CPP status recalculation |
| feb28-group2-waive-zeroed | REMEDIATION | Restores amount from audit trail and waives |
| deactivate-zeroed-payment | STRUCTURAL | Deactivates zero-amount payments (with impact warning) |

Each manipulator supports: canApply check, preview, execute (LOCAL/STAGING/PROD), and verify.

## Development

```bash
# Backend (port 8090)
gradle bootRun

# Frontend (port 3000, proxies to backend)
cd frontend && npm start
```

### Requirements for development

- Java 21
- Node.js 18+
- Snowflake access (Okta SSO)
- Local MySQL at sunbit-mysql:30306 (for replication)
