/**
 * The canonical decision ledger shared by the Command Center, Decisions page,
 * search results, and vendor detail. Seed records are clearly demo data; user
 * entries are persisted separately and merged into the same read API.
 */
import { getVendors } from './vendors';

export type DecisionType = 'Approve' | 'Reject' | 'Approve with conditions' | 'Accept risk' | 'Request remediation' | 'Reassess';

export interface DecisionRecord {
  id: string;
  vendorId: string;
  vendorName: string;
  decisionTitle: string;
  decisionType: DecisionType;
  context: string;
  riskDescription: string;
  businessImpact: string;
  evidenceRef: string;
  knownGaps: string[];
  optionsConsidered: string[];
  recommendation: string;
  decisionMaker: string;
  timestamp: string;
  occurredAt: number;
  decisionRationale: string;
  relatedRemediation: string;
  riskAcceptanceDetails?: { acceptedBy: string; expiresAt: string; reviewCycle: string };
}

export interface CustomDecision {
  id: string;
  vendorId: string;
  vendorName: string;
  decisionTitle: string;
  decisionType: DecisionType;
  decisionRationale: string;
  decisionMaker: string;
  timestamp: string;
  occurredAt: number;
}

export interface NewDecisionInput {
  vendorId: string;
  vendorName: string;
  decisionTitle: string;
  decisionType: DecisionType;
  decisionRationale: string;
}

const CUSTOM_DECISIONS_KEY = 'vx-custom-decisions';

type SeedDecision = Omit<DecisionRecord, 'vendorName'>;

const DEMO_DECISIONS: SeedDecision[] = [
  { id: 'DEC-2026-104', vendorId: 'vend-003', decisionTitle: 'Conditional Approval for Public Payment Gateway TLS Policy Extension', decisionType: 'Approve with conditions', context: 'Vendor requested a 30-day extension to phase out legacy TLS 1.0/1.1 endpoints following a scheduled checkout engine refactor.', riskDescription: 'Exposure to man-in-the-middle decryption attacks and PCI-DSS compliance failure during the extension window.', businessImpact: 'Immediate contract termination would halt inbound revenue settlement pipelines ($2.4M ARR).', evidenceRef: 'PEN-2026-APEX-01 (Finding #3) & SOC 2 Attestation', knownGaps: ['Exact cipher retirement telemetry verification pending.', 'Upstream load balancer log streaming continuity unverified.'], optionsConsidered: ['Option A: Immediate vendor suspension (High business disruption).', 'Option B: Conditional 30-day waiver with strict compensating WAF rules (Selected).'], recommendation: 'Grant temporary 30-day conditional approval bound to mandatory WAF IP restriction rules.', decisionMaker: 'Sarah Jenkins (Chief Information Security Officer)', timestamp: 'Oct 04, 2026 - 14:22 UTC', occurredAt: Date.parse('2026-10-04T14:22:00Z'), decisionRationale: 'Accepted temporary exposure under the condition that Cloudflare WAF block rules intercept legacy handshake attempts while remediation REM-001 is completed.', relatedRemediation: 'REM-001 (Deprecated TLS 1.0/1.1 remediation)' },
  { id: 'DEC-2026-092', vendorId: 'vend-004', decisionTitle: 'Risk Acceptance for Batch CSV Log Export Limitation', decisionType: 'Accept risk', context: 'Vendor architecture lacks real-time SIEM streaming, relying instead on scheduled daily batch CSV log exports.', riskDescription: 'Delayed threat detection capabilities for security operations center (up to 24-hour latency on audit trails).', businessImpact: 'DataFlow provides essential data warehouse analytics required for Q4 product intelligence.', evidenceRef: 'SIG-2026-DF-12 (Logging Section)', knownGaps: ['Real-time webhook alert continuity unavailable.'], optionsConsidered: ['Option A: Require custom API build (Vendor refused due to cost).', 'Option B: Formally accept risk under annual re-evaluation (Selected).'], recommendation: 'Accept time-bound architectural limitation with quarterly security posture reviews.', decisionMaker: 'Marcus Chen (Head of Risk Governance)', timestamp: 'Sep 28, 2026 - 09:15 UTC', occurredAt: Date.parse('2026-09-28T09:15:00Z'), decisionRationale: 'Compensating internal log anomaly monitors provide secondary coverage, mitigating the delayed batch export window.', relatedRemediation: 'REM-006 (Accepted risk exception tracking)', riskAcceptanceDetails: { acceptedBy: 'Marcus Chen (Head of Risk Governance)', expiresAt: 'Sep 28, 2027', reviewCycle: 'Annual Recertification' } },
  { id: 'DEC-2026-088', vendorId: 'vend-001', decisionTitle: 'Rejection of Unvetted Offshore Subprocessor Addition', decisionType: 'Reject', context: 'Vendor attempted to route secondary telemetry backups through an unvetted offshore subprocessor entity without prior DPA review.', riskDescription: 'Potential GDPR cross-border data transfer non-compliance and unverified subprocessor access controls.', businessImpact: 'Minor operational delay for secondary logging redundancy; core production remains unaffected.', evidenceRef: 'OCR-RSS-2026-88 (Subprocessor Monitor)', knownGaps: ['Subprocessor SOC 2 report entirely missing.', 'Data residency boundaries undefined.'], optionsConsidered: ['Option A: Grant provisional approval (Rejected due to GDPR exposure).', 'Option B: Issue formal rejection and halt subprocessor integration (Selected).'], recommendation: 'Reject subprocessor integration until Standard Contractual Clauses (SCCs) and security audits are provided.', decisionMaker: 'Sarah Jenkins (Chief Information Security Officer)', timestamp: 'Sep 19, 2026 - 16:45 UTC', occurredAt: Date.parse('2026-09-19T16:45:00Z'), decisionRationale: 'Zero-tolerance policy for unvetted Tier 1 data handlers processing personal identifiable information across international borders.', relatedRemediation: 'REM-004 (Subprocessor DPA execution)' },
  { id: 'DEC-2026-096', vendorId: 'vend-002', decisionTitle: 'Evidence Update Requested — SOC 2 Type II Renewal', decisionType: 'Request remediation', context: 'Automated evidence monitor flagged the vendor SOC 2 Type II report as expired with no renewal uploaded.', riskDescription: 'Critical compliance gap: Tier 1 identity provider operating without current audit attestation.', businessImpact: 'Conditional vendor approval remains in effect; procurement hold on contract renewal until evidence is refreshed.', evidenceRef: 'EV-SEC-2026-096 (Evidence Monitor)', knownGaps: ['SOC 2 Type II report expired — renewal not yet provided.', 'Pen-test attestation (PEN-2026-SA-99) past due date.'], optionsConsidered: ['Option A: Suspend vendor access immediately (Rejected — disproportionate to risk).', 'Option B: Issue formal evidence update request with 14-day deadline (Selected).'], recommendation: 'Request updated SOC 2 Type II and pen-test attestation within 14 days; escalate to vendor security leadership on day 7 if unacknowledged.', decisionMaker: 'System (Automated Evidence Monitor)', timestamp: 'Oct 03, 2026 - 08:00 UTC', occurredAt: Date.parse('2026-10-03T08:00:00Z'), decisionRationale: 'Maintain continuous assurance posture — expired attestations cannot support active risk decisions.', relatedRemediation: 'REM-003 (MFA fallback control gap)' },
];

function loadCustomDecisions(): CustomDecision[] {
  try {
    const raw = localStorage.getItem(CUSTOM_DECISIONS_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

export function getCustomDecisions(): CustomDecision[] {
  return loadCustomDecisions().sort((a, b) => b.occurredAt - a.occurredAt);
}

/** All seed and user decisions, with vendor names resolved from the canonical vendor registry. */
export function getDecisions(): DecisionRecord[] {
  const vendors = new Map(getVendors().map(vendor => [vendor.id, vendor.name]));
  const seeded: DecisionRecord[] = DEMO_DECISIONS.map(decision => ({
    ...decision,
    vendorName: vendors.get(decision.vendorId) ?? 'Unknown Vendor',
  }));
  const custom: DecisionRecord[] = getCustomDecisions().map(decision => ({
    ...decision,
    vendorName: vendors.get(decision.vendorId) ?? decision.vendorName,
    context: decision.decisionRationale,
    riskDescription: 'Recorded directly by the user — see rationale.',
    businessImpact: '—',
    evidenceRef: '',
    knownGaps: [],
    optionsConsidered: [],
    recommendation: decision.decisionTitle,
    relatedRemediation: '',
  }));
  return [...custom, ...seeded].sort((a, b) => b.occurredAt - a.occurredAt);
}

export function getDecisionsForVendor(vendorId: string): DecisionRecord[] {
  return getDecisions().filter(decision => decision.vendorId === vendorId);
}

/** Compatibility accessor for callers that need only user-created records. */
export function getCustomDecisionsForVendor(vendorId: string): CustomDecision[] {
  return getCustomDecisions().filter(decision => decision.vendorId === vendorId);
}

export function addDecision(input: NewDecisionInput): CustomDecision {
  const at = Date.now();
  const record: CustomDecision = {
    id: `dec-custom-${at}`,
    decisionMaker: 'Current User',
    timestamp: new Date(at).toLocaleString([], { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }),
    occurredAt: at,
    ...input,
  };
  try {
    localStorage.setItem(CUSTOM_DECISIONS_KEY, JSON.stringify([record, ...loadCustomDecisions()].slice(0, 200)));
  } catch {
    // storage unavailable — still returned for this session
  }
  return record;
}
