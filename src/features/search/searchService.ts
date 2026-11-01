// --- TYPES ---
export type SearchCategory = 'vendor' | 'assessment' | 'evidence' | 'risk_event' | 'decision';

export interface SearchResult {
 id: string;
 category: SearchCategory;
 title: string;
 subtitle: string;
 metadata?: string;
 url: string;
}

import { getVendors } from '../../data/vendors';
import { getDecisions } from '../../data/decisions';

// --- EXPLICIT DEVELOPMENT FIXTURES (non-vendor entities) ---
const DEV_FIXTURES: SearchResult[] = [
 { id: 'a-1', category: 'assessment', title: 'Annual Security Review - CloudScale', subtitle: 'Template: SIG Core 2026', metadata: 'Status: In Progress', url: '/assessments/a-1' },
 { id: 'e-1', category: 'evidence', title: 'SOC 2 Type II Report (2025)', subtitle: 'Vendor: SecureAuth Identity', metadata: 'Valid until: Dec 2026', url: '/evidence/e-1' },
 { id: 'e-2', category: 'evidence', title: 'Penetration Test Summary - Q3', subtitle: 'Vendor: CloudScale Infrastructure', metadata: 'Uploaded: Oct 2', url: '/evidence/e-2' },
 { id: 'r-1', category: 'risk_event', title: 'Unpatched Zero-Day Vulnerability', subtitle: 'Source: Threat Intel', metadata: 'Severity: High', url: '/risk-events/r-1' },
];

/** Vendor results are built from the canonical registry — all vendors, real IDs, real risk data. */
function buildVendorResults(): SearchResult[] {
 return getVendors().map(v => ({
 id: v.id,
 category: 'vendor' as SearchCategory,
 title: v.name,
 subtitle: `Code: ${v.code} · ${v.criticality}`,
 metadata: `Risk: ${v.riskLevel}`,
 url: `/vendors/${v.id}`,
 }));
}

/** Decision search results are projections of the canonical decision ledger. */
function buildDecisionResults(): SearchResult[] {
 return getDecisions().map(decision => ({
  id: decision.id,
  category: 'decision',
  title: decision.decisionTitle,
  subtitle: `Vendor: ${decision.vendorName} · Type: ${decision.decisionType}`,
  metadata: `Decided by: ${decision.decisionMaker}`,
  url: `/decisions/${decision.id}`,
 }));
}

// --- MOCK SERVICE INTERFACE ---
export async function fetchGlobalSearch(query: string, categoryFilter: string = 'all'): Promise<SearchResult[]> {
 return new Promise((resolve, reject) => {
 // Simulate network latency
 setTimeout(() => {
 // Simulate error state for testing (type 'error' to trigger)
 if (query.toLowerCase() === 'error') {
 return reject(new Error('Failed to connect to search indexing service.'));
 }

 const lowerQuery = query.toLowerCase();
 const allItems = [...buildVendorResults(), ...DEV_FIXTURES, ...buildDecisionResults()];
 const results = allItems.filter(item => {
 const matchesQuery = item.title.toLowerCase().includes(lowerQuery) || item.subtitle.toLowerCase().includes(lowerQuery);
 const matchesCategory = categoryFilter === 'all' || item.category === categoryFilter;
 return matchesQuery && matchesCategory;
 });

 resolve(results);
 }, 400); // 400ms fake delay
 });
}