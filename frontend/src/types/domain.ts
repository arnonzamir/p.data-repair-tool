// Domain types matching the Kotlin backend model

export interface PurchaseSnapshot {
  purchaseId: number;
  loadedAt: string;
  snowflakeDataTimestamp?: string;
  purchaseStatus: number;
  cppStatus: string;
  specialStatus?: string;
  plan: PlanInfo;
  payments: Payment[];
  chargeTransactions: ChargeTransaction[];
  moneyMovement: MoneyMovement;
  balanceCheck: BalanceCheck;
  notifications: NotificationSummary;
  supportTickets: SupportTicket[];
  auditTrail: PaymentAuditRecord[];
  paymentActions: PaymentAction[];
  paymentAttempts: PaymentAttempt[];
  crossSchemaReconciliation: CrossSchemaReconciliation;
  chargeServiceAttempts?: ChargeServiceAttempt[];
  chargeServiceStatuses?: ChargeServiceAttemptStatus[];
  loanTransactions?: LoanTransaction[];
  unifiedChargeEvents?: UnifiedChargeEvent[];
}

export interface PlanInfo {
  planId: number;
  totalAmount: number;
  amountFinanced: number;
  totalOfPayments: number;
  financialCharge: number;
  nominalApr: number;
  effectiveApr?: number;
  dailyApr?: number;
  monthlyApr?: number;
  numInstallments: number;
  paymentProfileId?: number;
  paymentsInterval?: number;
}

export interface Payment {
  id: number;
  paymentPlanId: number;
  amount: number;
  interestAmount?: number;
  interestCharge?: number;
  principalBalance?: number;
  dueDate: string;
  effectiveDate?: string;
  paidOffDate?: string;
  refundDate?: string;
  type: number;
  typeName?: string;
  changeIndicator: number;
  changeIndicatorName?: string;
  isActive: boolean;
  paymentActionId?: number;
  chargeBack?: string;
  chargeBackEnhancement?: string;
  dispute?: string;
  splitFrom?: number;
  originalPaymentId?: number;
  directParentId?: number;
  paymentProfileId?: number;
  manualUntil?: string;
  creationDate?: string;
  amountPaid?: number;
  initialAmount?: number;
  computedStatus: string;
}

export interface ChargeTransaction {
  id: number;
  purchaseId: number;
  type: number;
  typeName?: string;
  amount: number;
  chargeTime?: string;
  chargeback?: boolean;
  chargebackEnhancement?: boolean;
  parentId?: number;
  paymentProfileId?: number;
  manualAdjustment?: boolean;
}

export interface MoneyMovement {
  byType: MoneyMovementEntry[];
  totalCollected: number;
  totalReturned: number;
  net: number;
}

export interface MoneyMovementEntry {
  type: number;
  typeName: string;
  count: number;
  totalAmount: number;
  earliest?: string;
  latest?: string;
}

export interface BalanceCheck {
  planTotal: number;
  moneyCollected: number;
  moneyReturned: number;
  netCollected: number;
  downPayment: number;
  paidInstallments: number;
  unpaidInstallments: number;
  scheduleTotal: number;
  scheduleGap: number;
  moneyGap: number;
  checkAVerdict: string;
  checkBVerdict: string;
}

export interface NotificationSummary {
  sent: NotificationRecord[];
  missing: MissingNotification[];
  erroneous: ErroneousNotification[];
}

export interface NotificationRecord {
  id: number;
  purchaseId: number;
  type: number;
  typeName?: string;
  status?: number;
  changeStatusTime?: string;
  purchaseStatusOnSend?: number;
  notificationId?: string;
  recipientAddress?: string;
  templateName?: string;
  subject?: string;
}

export interface MissingNotification {
  paymentId: number;
  paidOffDate: string;
  expectedEmailType: string;
  description: string;
}

export interface ErroneousNotification {
  notificationId: number;
  type: number;
  typeName: string;
  reason: string;
}

export interface SupportTicket {
  id: string;
  purchaseId: number;
  subject?: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
  assignee?: string;
  category?: string;
  priority?: string;
  description?: string;
  channel?: string;
}

export interface PaymentAuditRecord {
  paymentId: number;
  rev: number;
  amount?: number;
  isActive?: boolean;
  paidOffDate?: string;
  changeIndicator?: number;
  rowTime?: string;
}

export interface PaymentAction {
  id: number;
  type: number;
  typeName?: string;
  purchaseId: number;
  timeOfAction?: string;
}

export interface PaymentAttempt {
  id: string;
  paymentId: number;
  triggeringPaymentId?: number;
  source?: number;
  sourceName?: string;
  dateTime?: string;
  type?: number;
  status?: number;
  failMessage?: string;
  processorTxId?: string;
  holdTransactionId?: string;
  chargeTransactionId?: string;
}

export interface CrossSchemaReconciliation {
  processorSuccessCount: number;
  processorSuccessAmount: number;
  purchaseChargeCount: number;
  purchaseChargeAmount: number;
  processorRefundCount: number;
  processorRefundAmount: number;
  purchaseRefundCount: number;
  purchaseRefundAmount: number;
  processorFailCount: number;
  verdict: string;
}

// Charge-service data (from charge schema)

export interface ChargeServiceAttempt {
  id: number;
  dateTime?: string;
  lastUpdateTime?: string;
  paymentProfileId?: number;
  paymentProcessor?: string;
  processorTxId?: string;
  failMessage?: string;
  externalId: string;
  amount: number;
  status: number;
  type: number;
  origin?: string;
}

export interface ChargeServiceAttemptStatus {
  id: number;
  paymentAttemptId: number;
  creationTime?: string;
  lastUpdateTime?: string;
  chargeStatus?: string;
  chargeStatusReason?: string;
  summary?: string;
}

export interface LoanTransaction {
  id: number;
  paymentId: number;
  chargeTransactionId: number;
  amount: number;
  effectiveDate?: string;
  interestAmount?: number;
  interestCharge?: number;
  principalBalance?: number;
}

export type MatchQuality = 'EXACT' | 'AMOUNT_MATCH' | 'UNMATCHED';

export interface UnifiedChargeEvent {
  chargeTransaction?: ChargeTransaction;
  loanTransaction?: LoanTransaction;
  purchaseAttempt?: PaymentAttempt;
  chargeServiceAttempt?: ChargeServiceAttempt;
  chargeServiceStatuses: ChargeServiceAttemptStatus[];
  paymentId?: number;
  amount: number;
  timestamp?: string;
  matchQuality: MatchQuality;
}

// Analysis types

export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';

export interface Finding {
  ruleId: string;
  ruleName: string;
  severity: Severity;
  affectedPaymentIds: number[];
  description: string;
  evidence: Record<string, any>;
  suggestedRepairs: SuggestedRepair[];
}

export interface SuggestedRepair {
  action: string;
  description: string;
  parameters: Record<string, any>;
  supportsDryRun: boolean;
}

export interface AnalysisResult {
  purchaseId: number;
  analyzedAt: string;
  findings: Finding[];
  ruleResults: RuleExecutionResult[];
  criticalCount: number;
  highCount: number;
  mediumCount: number;
  lowCount: number;
  overallSeverity?: Severity;
}

export interface RuleExecutionResult {
  ruleId: string;
  ruleName: string;
  enabled: boolean;
  executionTimeMs: number;
  findingCount: number;
  error?: string;
}

export interface ReplicationRecord {
  target: string;
  replicatedAt: string;
  replicatedPurchaseId: number;
  idOffset: number;
  hasRollback: boolean;
}

export interface PurchaseAnalysisResponse {
  snapshot: PurchaseSnapshot;
  analysis: AnalysisResult;
  cachedAt: string;
  replications: ReplicationRecord[];
}

// Repair types

export interface RepairRequestDto {
  purchaseId: number;
  actionType: string;
  reason: string;
  paymentId?: number;
  newAmount?: number;
  newApr?: number;
  workoutParams?: Record<string, any>;
  settlementAmount?: number;
  cancellationReason?: string;
}

export interface DryRunResult {
  purchaseId: number;
  request: any;
  supported: boolean;
  preview?: Record<string, any>;
  description: string;
}

export interface RepairResult {
  purchaseId: number;
  request: any;
  executedAt: string;
  success: boolean;
  httpStatus?: number;
  responseBody?: string;
  error?: string;
  verificationResult?: AnalysisResult;
}

// Rules

export interface RuleInfo {
  ruleId: string;
  ruleName: string;
  description: string;
  enabled: boolean;
}

// Replication

export interface ReplicateRequest {
  purchaseId: number;
  target: 'LOCAL' | 'STAGING';
  execute: boolean;
  idOffset?: number;
  customerRetailerId?: number;
  paymentProfileId?: number;
  namePrefix?: string;
}

export interface ReplicationResult {
  purchaseId: number;
  target: string;
  success: boolean;
  insertSql: string;
  rollbackSql: string;
  tableRowCounts: Record<string, number>;
  piiRedactions: number;
  executed: boolean;
  executionError?: string;
  runDirectory?: string;
}

// Audit

export interface AuditEntry {
  id: string;
  timestamp: string;
  operator: string;
  purchaseId: number;
  action: string;
  input?: Record<string, any>;
  output?: Record<string, any>;
  durationMs?: number;
}
