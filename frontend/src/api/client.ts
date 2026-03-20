import type {
  PurchaseSnapshot,
  PurchaseAnalysisResponse,
  AnalysisResult,
  RepairRequestDto,
  DryRunResult,
  RepairResult,
  RuleInfo,
  ReplicateRequest,
  ReplicationResult,
  AuditEntry,
} from '../types/domain';

const BASE = process.env.REACT_APP_API_URL || 'http://localhost:8090';

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      'X-Operator': localStorage.getItem('operator') || 'ui-user',
      ...init?.headers,
    },
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`HTTP ${res.status}: ${body}`);
  }
  return res.json();
}

// Config
export const getConfig = () =>
  apiFetch<{ callCenter: Record<string, string> }>('/api/v1/config');

// Purchase
export const loadPurchase = (id: number, refresh = false) =>
  apiFetch<{ snapshot: PurchaseSnapshot; cachedAt: string }>(
    `/api/v1/purchases/${id}?refresh=${refresh}`
  );

export const analyzePurchase = (id: number, refresh = false) =>
  apiFetch<PurchaseAnalysisResponse>(
    `/api/v1/purchases/${id}/analyze?refresh=${refresh}`
  );

export const analyzeBatch = (ids: number[], refresh = false) =>
  apiFetch<{ results: AnalysisResult[]; summary: any }>(
    `/api/v1/purchases/batch/analyze?refresh=${refresh}`,
    { method: 'POST', body: JSON.stringify({ purchaseIds: ids }) },
  );

// Cache management
export const getCacheStats = () =>
  apiFetch<{ size: number; entries: { purchaseId: number; cachedAt: string; sizeBytes: number }[] }>(
    '/api/v1/purchases/cache/stats'
  );

export const getPurchaseAudit = (id: number) =>
  apiFetch<AuditEntry[]>(`/api/v1/purchases/${id}/audit`);

// Repair
export const dryRun = (req: RepairRequestDto) =>
  apiFetch<DryRunResult>('/api/v1/repairs/dry-run', {
    method: 'POST',
    body: JSON.stringify(req),
  });

export const executeRepair = (req: RepairRequestDto) =>
  apiFetch<RepairResult>('/api/v1/repairs/execute', {
    method: 'POST',
    body: JSON.stringify(req),
  });

// Rules
export const listRules = () =>
  apiFetch<RuleInfo[]>('/api/v1/rules');

export const enableRule = (ruleId: string) =>
  apiFetch<any>(`/api/v1/rules/${ruleId}/enable`, { method: 'PUT' });

export const disableRule = (ruleId: string) =>
  apiFetch<any>(`/api/v1/rules/${ruleId}/disable`, { method: 'PUT' });

// Replicate
export const replicatePurchase = (req: ReplicateRequest) =>
  apiFetch<ReplicationResult>('/api/v1/replicate/single', {
    method: 'POST',
    body: JSON.stringify(req),
  });

export const generateSql = (req: ReplicateRequest) =>
  apiFetch<{ insertSql: string; rollbackSql: string; tableRowCounts: Record<string, number> }>(
    '/api/v1/replicate/generate-sql',
    { method: 'POST', body: JSON.stringify(req) },
  );

// Check if replicated purchase already exists in target DB
export const checkReplicationExists = (purchaseId: number, target: string, idOffset = 0) =>
  apiFetch<{
    purchaseId: number; targetPurchaseId: number; target: string;
    reachable: boolean; exists: boolean; rowCounts: Record<string, number>;
  }>(`/api/v1/replicate/check-exists/${purchaseId}?target=${target}&idOffset=${idOffset}`);

// Rollback replication
export const rollbackReplication = (purchaseId: number, target: string) =>
  apiFetch<{ purchaseId: number; target: string; success: boolean; error?: string; executionLog?: string }>(
    '/api/v1/replicate/rollback',
    { method: 'POST', body: JSON.stringify({ purchaseId, target }) },
  );

// Replicate defaults
export const getReplicateDefaults = () =>
  apiFetch<Record<string, { customerRetailerId: number; paymentProfileId: number; idOffset: number }>>(
    '/api/v1/replicate/defaults'
  );

// Purchase lists
export interface PurchaseListData {
  id: number;
  name: string;
  createdAt: string;
  purchaseIds: number[];
}

export interface PurchaseSummaryData {
  purchaseId: number;
  cached: boolean;
  cachedAt?: string;
  replications: { target: string; replicatedAt: string; replicatedPurchaseId: number }[];
}

export const getLists = () =>
  apiFetch<PurchaseListData[]>('/api/v1/lists');

export const createList = (name: string) =>
  apiFetch<PurchaseListData>('/api/v1/lists', {
    method: 'POST',
    body: JSON.stringify({ name }),
  });

export const deleteList = (listId: number) =>
  apiFetch<any>(`/api/v1/lists/${listId}`, { method: 'DELETE' });

export const addToList = (listId: number, purchaseId: number) =>
  apiFetch<any>(`/api/v1/lists/${listId}/purchases/${purchaseId}`, { method: 'POST' });

export const removeFromList = (listId: number, purchaseId: number) =>
  apiFetch<any>(`/api/v1/lists/${listId}/purchases/${purchaseId}`, { method: 'DELETE' });

export const getPurchaseSummary = (purchaseId: number) =>
  apiFetch<PurchaseSummaryData>(`/api/v1/lists/purchase-summary/${purchaseId}`);

export const getListsForPurchase = (purchaseId: number) =>
  apiFetch<string[]>(`/api/v1/lists/for-purchase/${purchaseId}`);

// Notes
export interface PurchaseNote {
  id: number;
  purchaseId: number;
  author: string;
  content: string;
  createdAt: string;
}

export const getNotes = (purchaseId: number) =>
  apiFetch<PurchaseNote[]>(`/api/v1/lists/notes/${purchaseId}`);

export const addNote = (purchaseId: number, content: string) =>
  apiFetch<PurchaseNote>(`/api/v1/lists/notes/${purchaseId}`, {
    method: 'POST',
    body: JSON.stringify({ content }),
  });

// Review status
export interface ReviewStatus {
  purchaseId: number;
  status: string;
  updatedAt?: string;
  updatedBy?: string;
}

export const getReviewStatus = (purchaseId: number) =>
  apiFetch<ReviewStatus>(`/api/v1/lists/review-status/${purchaseId}`);

export const setReviewStatus = (purchaseId: number, status: string) =>
  apiFetch<ReviewStatus>(`/api/v1/lists/review-status/${purchaseId}`, {
    method: 'PUT',
    body: JSON.stringify({ status }),
  });

export const getReviewStatuses = (purchaseIds: number[]) =>
  apiFetch<Record<number, ReviewStatus>>('/api/v1/lists/review-statuses', {
    method: 'POST',
    body: JSON.stringify({ purchaseIds }),
  });

// Audit
export const getRecentAudit = (limit = 50) =>
  apiFetch<AuditEntry[]>(`/api/v1/audit/recent?limit=${limit}`);
