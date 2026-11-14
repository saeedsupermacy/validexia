/**
 * Single source of truth for vendor data.
 * Every component that displays vendor counts, lists, or stats reads from here.
 * When the backend arrives, replace DEV_VENDORS with an API call —
 * everything downstream stays consistent automatically.
 */

export type BusinessCriticality = 'Tier 1' | 'Tier 2' | 'Tier 3';
export type RiskLevel = 'Critical' | 'High' | 'Moderate' | 'Low';
export type EvidenceStatus = 'Verified' | 'Expiring' | 'Missing' | 'Stale';
export type AssessmentStatus = 'Completed' | 'Pending Review' | 'Not Started';

export interface VendorRecord {
  id: string;
  name: string;
  code: string;
  /** Where this row came from. 'live' rows are real backend records (their
   *  score/evidence fields are placeholders that the UI must not present as
   *  real — the API has no scores yet); absent means demo/fixture data. */
  source?: 'live' | 'demo';
  /** Real-API identity fields (live rows only; null when not recorded). */
  legalName?: string | null;
  lei?: string | null;
  jurisdiction?: string | null;
  /** Owner as the API knows it: a user UUID, never a display name. */
  ownerUserId?: string | null;
  criticality: BusinessCriticality;
  riskLevel: RiskLevel;
  riskScore: number;
  evidenceStatus: EvidenceStatus;
  assessmentStatus: AssessmentStatus;
  openIssues: number;
  owner: string;
  monitoringStatus: string;
  lastMaterialChange: string;
  website?: string;
  description?: string;
  category?: string;
  domain?: string;
  dependentSystems?: Array<{ name: string; dataHandled: string; residency: string }>;
  status: 'Active' | 'Terminated';
  inherentRisk: number;
  residualRisk: number;
  contractStart?: string;
  contractEnd?: string;
  /** False if the vendor was added without completing the tiering questionnaire. */
  tieringComplete?: boolean;
}

export const DEV_VENDORS: VendorRecord[] = [
  {
    id: 'vend-001', name: 'CloudScale Infrastructure', code: 'VND-001', category: 'Cloud & Hosting Services', domain: 'cloudscale-infra.io',
    criticality: 'Tier 1', riskLevel: 'Critical', riskScore: 88, evidenceStatus: 'Stale', assessmentStatus: 'Pending Review', openIssues: 5,
    owner: 'Sarah Jenkins', monitoringStatus: 'Active', lastMaterialChange: 'Today, 09:14 AM', status: 'Active', inherentRisk: 95, residualRisk: 88,
    contractStart: '2024-01-15', contractEnd: '2027-01-15',
    dependentSystems: [
      { name: 'Core Billing Engine (US-East)', dataHandled: 'Financial Ledger & PCI', residency: 'AWS us-east-1' },
      { name: 'Identity Federation Gateway', dataHandled: 'Customer PII & OAuth Tokens', residency: 'AWS us-west-2' },
      { name: 'Telemetry Analytics Pipeline', dataHandled: 'Encrypted Log Data', residency: 'AWS eu-central-1' },
    ],
  },
  { id: 'vend-002', name: 'SecureAuth Identity', code: 'VND-002', criticality: 'Tier 1', riskLevel: 'Critical', riskScore: 92, evidenceStatus: 'Verified', assessmentStatus: 'Completed', openIssues: 8, owner: 'Marcus Chen', monitoringStatus: 'Active', lastMaterialChange: 'Today, 08:30 AM' , status: 'Active', inherentRisk: 98, residualRisk: 92, contractStart: '2023-06-01', contractEnd: '2026-06-01' },
  {
    id: 'vend-003', name: 'Apex Gateway Payments', code: 'VND-003', category: 'Financial Infrastructure', domain: 'apexpayments.com',
    criticality: 'Tier 1', riskLevel: 'High', riskScore: 76, evidenceStatus: 'Expiring', assessmentStatus: 'Pending Review', openIssues: 3,
    owner: 'Sarah Jenkins', monitoringStatus: 'Active', lastMaterialChange: 'Yesterday, 11:14 AM', status: 'Active', inherentRisk: 88, residualRisk: 76,
    contractStart: '2024-03-10', contractEnd: '2027-03-10',
    dependentSystems: [{ name: 'Global Payment Gateway API', dataHandled: 'PCI-DSS Financial Data', residency: 'AWS us-east-1' }],
  },
  { id: 'vend-004', name: 'DataFlow Analytics', code: 'VND-004', criticality: 'Tier 2', riskLevel: 'Moderate', riskScore: 52, evidenceStatus: 'Verified', assessmentStatus: 'Completed', openIssues: 1, owner: 'Alex Rivera', monitoringStatus: 'Active', lastMaterialChange: 'Oct 3, 16:45' , status: 'Active', inherentRisk: 65, residualRisk: 52, contractStart: '2024-09-01', contractEnd: '2026-09-01' },
  { id: 'vend-005', name: 'GlobalHR Systems', code: 'VND-005', criticality: 'Tier 2', riskLevel: 'Moderate', riskScore: 48, evidenceStatus: 'Missing', assessmentStatus: 'Completed', openIssues: 2, owner: 'Sarah Jenkins', monitoringStatus: 'Active', lastMaterialChange: 'Oct 2, 09:10 AM' , status: 'Active', inherentRisk: 60, residualRisk: 48, contractStart: '2023-11-20', contractEnd: '2026-11-20' },
  { id: 'vend-006', name: 'LogiChain Logistics', code: 'VND-006', criticality: 'Tier 3', riskLevel: 'Low', riskScore: 22, evidenceStatus: 'Missing', assessmentStatus: 'Completed', openIssues: 0, owner: 'Alex Rivera', monitoringStatus: 'Paused', lastMaterialChange: 'Sep 28, 14:00' , status: 'Active', inherentRisk: 35, residualRisk: 22, contractStart: '2024-05-15', contractEnd: '2027-05-15' },
  { id: 'vend-007', name: 'Synapse AI Services', code: 'VND-007', criticality: 'Tier 1', riskLevel: 'High', riskScore: 74, evidenceStatus: 'Expiring', assessmentStatus: 'Pending Review', openIssues: 4, owner: 'Marcus Chen', monitoringStatus: 'Active', lastMaterialChange: 'Sep 25, 10:20 AM' , status: 'Active', inherentRisk: 85, residualRisk: 74, contractStart: '2024-07-01', contractEnd: '2027-07-01' },
  { id: 'vend-008', name: 'OmniCloud Storage', code: 'VND-008', criticality: 'Tier 3', riskLevel: 'Low', riskScore: 18, evidenceStatus: 'Missing', assessmentStatus: 'Completed', openIssues: 0, owner: 'Sarah Jenkins', monitoringStatus: 'Active', lastMaterialChange: 'Sep 20, 15:30', status: 'Active', inherentRisk: 28, residualRisk: 18, contractStart: '2024-02-01', contractEnd: '2027-02-01' }
];

/** Portfolio matrix cell derived from the vendor list — never hardcoded. */
export interface PortfolioCell {
  tier: BusinessCriticality;
  risk: RiskLevel;
  count: number;
  unverified: number;
}

const TIERS: BusinessCriticality[] = ['Tier 1', 'Tier 2', 'Tier 3'];
const RISKS: RiskLevel[] = ['Low', 'Moderate', 'High', 'Critical'];

/** Build the Tier × Risk matrix from the vendor list. */
export function buildPortfolioMatrix(vendors: VendorRecord[] = getVendors()): PortfolioCell[] {
  const cells: PortfolioCell[] = [];
  for (const tier of TIERS) {
    for (const risk of RISKS) {
      const matches = vendors.filter(v => v.criticality === tier && v.riskLevel === risk);
      cells.push({
        tier,
        risk,
        count: matches.length,
        unverified: matches.filter(v => v.evidenceStatus !== 'Verified').length,
      });
    }
  }
  return cells;
}

/** Headline stats derived from the vendor list. */
export function portfolioHeadline(vendors: VendorRecord[] = getVendors()): { tier1HighCritical: number; unverified: number } {
  const tier1HighCritical = vendors.filter(
    v => v.criticality === 'Tier 1' && (v.riskLevel === 'High' || v.riskLevel === 'Critical')
  ).length;
  const unverified = vendors.filter(v => v.evidenceStatus !== 'Verified').length;
  return { tier1HighCritical, unverified };
}

/** Registry for user-added vendors — persists to localStorage, survives refresh. */
const ADDED_VENDORS_KEY = 'vx-added-vendors';

function loadAddedVendors(): VendorRecord[] {
  try {
    const raw = localStorage.getItem(ADDED_VENDORS_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

/** Derive the risk band from a 0–100 score per SCORE_CONTRACT.md:
 *  Critical 80–100, High 60–79, Moderate 30–59, Low 0–29 (higher = riskier). */
export function riskLevelForScore(score: number): RiskLevel {
  if (score >= 80) return 'Critical';
  if (score >= 60) return 'High';
  if (score >= 30) return 'Moderate';
  return 'Low';
}

/** Full vendor list: built-in demo data + anything the user added.
 *  Applies status overrides (offboarded vendors) and enforces
 *  score→band consistency so riskLevel can never contradict riskScore. */
export function getVendors(): VendorRecord[] {
  const overrides = readStatusOverrides();
  return [...DEV_VENDORS, ...loadAddedVendors()].map(v => {
    const withStatus = overrides[v.id]
      ? { ...v, status: 'Terminated' as const, monitoringStatus: 'Paused' }
      : v;
    // Derive the band from the score — stored riskLevel is a hint, score wins.
    return { ...withStatus, riskLevel: riskLevelForScore(withStatus.riskScore) };
  });
}

export interface NewVendorInput {
  name: string;
  criticality: BusinessCriticality;
  owner: string;
  website?: string;
  description?: string;
}

/** Add a vendor. Sensible defaults for a fresh record; persists to localStorage. */
export function addVendor(input: NewVendorInput): VendorRecord {
  const added = loadAddedVendors();
  const record: VendorRecord = {
    id: `vend-custom-${Date.now()}`,
    name: input.name.trim(),
    code: `VND-${String(DEV_VENDORS.length + added.length + 1).padStart(3, '0')}`,
    criticality: input.criticality,
    riskLevel: riskLevelForScore(50),
    riskScore: 50,
    evidenceStatus: 'Missing',
    assessmentStatus: 'Not Started',
    openIssues: 0,
    owner: input.owner.trim() || 'Unassigned',
    monitoringStatus: 'Active',
    website: input.website?.trim() || undefined,
    description: input.description?.trim() || undefined,
    status: 'Active',
    inherentRisk: 60,
    residualRisk: 50,
    lastMaterialChange: 'Just now',
  };
  try {
    localStorage.setItem(ADDED_VENDORS_KEY, JSON.stringify([...added, record]));
  } catch {
    // storage unavailable — record still works for this session
  }
  return record;
}

/** Offboard a vendor: marks as Terminated with checklist. Persists to localStorage. */
export function offboardVendor(id: string, checklist: { accessRevoked: boolean; dataDestroyed: boolean; contractsClosed: boolean }): boolean {
  if (!checklist.accessRevoked || !checklist.dataDestroyed || !checklist.contractsClosed) return false;
  try {
    const added: VendorRecord[] = loadAddedVendors();
    const found = added.find((v: VendorRecord) => v.id === id);
    if (found) {
      found.status = 'Terminated';
      found.monitoringStatus = 'Paused';
      try { localStorage.setItem(ADDED_VENDORS_KEY, JSON.stringify(added)); } catch { /* noop */ }
      return true;
    }
    // Built-in vendor: store override
    const overrides = readStatusOverrides();
    overrides[id] = 'Terminated';
    writeStatusOverrides(overrides);
    return true;
  } catch { return false; }
}

const STATUS_KEY = 'vx-vendor-status';
function readStatusOverrides(): Record<string, 'Terminated'> {
  try {
    const raw = localStorage.getItem(STATUS_KEY);
    return raw ? JSON.parse(raw) : {};
  } catch { return {}; }
}
function writeStatusOverrides(o: Record<string, 'Terminated'>): void {
  try { localStorage.setItem(STATUS_KEY, JSON.stringify(o)); } catch { /* noop */ }
}

/** Get vendors with status overrides applied.
 *  (getVendors() already applies overrides; kept for backwards compatibility.) */
export function getVendorsWithStatus(): VendorRecord[] {
  return getVendors();
}

/**
 * Resolve a vendor name (or partial name) to its canonical ID.
 * Exact ID match first, then exact name match (case-insensitive),
 * then substring fallback. Returns null when nothing matches.
 * Prefer passing IDs through navigation; use this only when the
 * source record carries just a name.
 */
export function resolveVendorId(nameOrId: string): string | null {
  if (!nameOrId) return null;
  const vendors = getVendors();
  const q = nameOrId.trim().toLowerCase();
  const byId = vendors.find(v => v.id.toLowerCase() === q);
  if (byId) return byId.id;
  const byName = vendors.find(v => v.name.toLowerCase() === q);
  if (byName) return byName.id;
  const bySubstring = vendors.find(v => v.name.toLowerCase().includes(q));
  return bySubstring ? bySubstring.id : null;
}
