// src/features/evidence/EvidenceConfidenceView.tsx
import { useState, useEffect } from 'react';
import { 
 ShieldAlert, ShieldCheck, Clock, FileCheck2, Fingerprint, 
 Bot, UserCheck, Info, BarChart3, Search, Activity
} from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent, Eyebrow} from '../../shared/ui/core';
import { getNavParams, clearNavParams } from '../../shared/navigation';

// Backend-provided types. No frontend calculation of formulas.
export type RiskLevel = 'Low' | 'Medium' | 'High' | 'Critical';
export type ConfidenceLevel = 'Low' | 'Medium' | 'High';

export interface ConfidenceFactor {
 category: 'Freshness' | 'Completeness' | 'Source Reliability' | 'Extraction Confidence' | 'Reviewer Verification';
 scoreLabel: string; // e.g., "98%", "Grade A", "Verified" - provided by backend
 description: string;
 status: 'Optimal' | 'Acceptable' | 'Degraded' | 'Critical';
}

export interface VendorEpistemicProfile {
 id: string;
 vendorName: string;
 assessedRisk: RiskLevel;
 overallConfidence: ConfidenceLevel;
 confidenceScore: string; // Backend provided aggregate
 factors: ConfidenceFactor[];
}

// Mock backend payload demonstrating decoupling of risk and confidence
const DEV_EPISTEMIC_PROFILES: VendorEpistemicProfile[] = [
 {
 id: 'ep-01',
 vendorName: 'CloudScale Infrastructure',
 assessedRisk: 'High',
 overallConfidence: 'High',
 confidenceScore: '92/100',
 factors: [
 { category: 'Freshness', scoreLabel: '30 Days Old', description: 'SOC 2 Type II report is recent and covers the current operating period.', status: 'Optimal' },
 { category: 'Completeness', scoreLabel: '100% Coverage', description: 'All requested control domains are fully addressed in the artifact.', status: 'Optimal' },
 { category: 'Source Reliability', scoreLabel: 'Tier 1 Auditor', description: 'Attestation provided directly by a Big 4 auditing firm.', status: 'Optimal' },
 { category: 'Extraction Confidence', scoreLabel: '96.4%', description: 'AI parsing yielded high certainty with no conflicting text blocks.', status: 'Optimal' },
 { category: 'Reviewer Verification', scoreLabel: 'Verified', description: 'Human analyst has countersigned the automated extractions.', status: 'Optimal' }
 ]
 },
 {
 id: 'ep-02',
 vendorName: 'Apex Gateway Payments',
 assessedRisk: 'Low',
 overallConfidence: 'Low',
 confidenceScore: '34/100',
 factors: [
 { category: 'Freshness', scoreLabel: '14 Months Old', description: 'Evidence exceeds standard 12-month validity threshold.', status: 'Critical' },
 { category: 'Completeness', scoreLabel: 'Partial (60%)', description: 'Missing network boundary and endpoint security sections.', status: 'Degraded' },
 { category: 'Source Reliability', scoreLabel: 'Self-Attested', description: 'Unverified custom questionnaire filled by vendor sales team.', status: 'Degraded' },
 { category: 'Extraction Confidence', scoreLabel: '62.1%', description: 'Parsing struggled with unstructured, handwritten PDF scans.', status: 'Critical' },
 { category: 'Reviewer Verification', scoreLabel: 'Pending', description: 'Awaiting human review to resolve AI extraction ambiguities.', status: 'Critical' }
 ]
 },
 {
 id: 'ep-03',
 vendorName: 'SecureAuth Identity',
 assessedRisk: 'Critical',
 overallConfidence: 'Medium',
 confidenceScore: '72/100',
 factors: [
 { category: 'Freshness', scoreLabel: '6 Months Old', description: 'Penetration test is within acceptable boundaries.', status: 'Acceptable' },
 { category: 'Completeness', scoreLabel: '90% Coverage', description: 'Minor exclusions noted in the methodology section.', status: 'Acceptable' },
 { category: 'Source Reliability', scoreLabel: 'Boutique Firm', description: 'Valid 3rd party penetration testing firm.', status: 'Acceptable' },
 { category: 'Extraction Confidence', scoreLabel: '88.5%', description: 'Standardized tables parsed successfully.', status: 'Optimal' },
 { category: 'Reviewer Verification', scoreLabel: 'Pending', description: 'Findings require mapping to internal risk register.', status: 'Critical' }
 ]
 }
];

export function EvidenceConfidenceView() {
 const [profiles] = useState<VendorEpistemicProfile[]>(DEV_EPISTEMIC_PROFILES);
 const [selectedProfile, setSelectedProfile] = useState<VendorEpistemicProfile>(profiles[0]);
 const [searchQuery, setSearchQuery] = useState('');

 // Deep link: pre-fill search when arriving from another tab
 useEffect(() => {
 const params = getNavParams();
 const q = params.search || params.vendorName;
 if (q && typeof q === 'string') {
 setSearchQuery(q);
 }
 if (q) clearNavParams();
 }, []);

 useEffect(() => {
 const handleKeyDown = (e: KeyboardEvent) => {
 if (e.key === 'Escape') {
 if (searchQuery) setSearchQuery('');
 }
 };
 document.addEventListener('keydown', handleKeyDown);
 return () => document.removeEventListener('keydown', handleKeyDown);
 }, [searchQuery]);

 const filteredProfiles = profiles.filter(p => p.vendorName.toLowerCase().includes(searchQuery.toLowerCase()));

 // Risk gets traditional Red/Yellow/Green. Confidence gets Blue/Indigo to separate semantics.
 const getRiskBadge = (risk: RiskLevel) => {
 const styles = {
 'Low': 'bg-status-success/10 text-status-success ',
 'Medium': 'bg-status-warning/10 text-status-warning ',
 'High': 'bg-risk-high/10 text-risk-high ',
 'Critical': 'bg-risk-critical/10 text-risk-critical '
 };
 return <span className={`px-2 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wider ${styles[risk]}`}>{risk} Risk</span>;
 };

 const getConfidenceBadge = (confidence: ConfidenceLevel) => {
 const styles = {
 'Low': 'bg-surface-hover text-text-secondary border-border dark:bg-surface-hover dark:text-text-secondary dark:border-border',
 'Medium': 'bg-accent-muted text-accent',
 'High': 'bg-accent text-white'
 };
 return <span className={`px-2 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wider ${styles[confidence]}`}>{confidence} Confidence</span>;
 };

 const getFactorIcon = (category: string) => {
 switch(category) {
 case 'Freshness': return <Clock aria-hidden="true" size={16} />;
 case 'Completeness': return <FileCheck2 aria-hidden="true" size={16} />;
 case 'Source Reliability': return <Fingerprint aria-hidden="true" size={16} />;
 case 'Extraction Confidence': return <Bot aria-hidden="true" size={16} />;
 case 'Reviewer Verification': return <UserCheck aria-hidden="true" size={16} />;
 default: return <Info aria-hidden="true" size={16} />;
 }
 };

 const getFactorStatusColor = (status: string) => {
 switch(status) {
 case 'Optimal': return 'text-status-success';
 case 'Acceptable': return 'text-text-secondary';
 case 'Degraded': return 'text-status-warning';
 case 'Critical': return 'text-risk-critical';
 default: return 'text-text-tertiary';
 }
 };

 return (
 <div className="space-y-6 max-w-7xl mx-auto pb-28 animate-in fade-in duration-300">
 
 <div aria-live="polite" className="sr-only">
 Evidence Confidence view loaded. {filteredProfiles.length} epistemic profiles matched your search criteria.
 </div>

 {/* Header & Paradigm Explainer */}
 <div className="border-b border-border pb-6">
 <Eyebrow>Program</Eyebrow>
 <h1 className="text-page-title">Evidence Confidence & Epistemic Assurance</h1>
 <p className="text-body-secondary mt-1 max-w-3xl">
 Assess the reliability of our compliance data. A low-risk assessment built on low-confidence evidence represents a blind spot, whereas a high-risk assessment on high-confidence evidence represents a known, actionable threat.
 </p>
 </div>

 <div className="bg-accent-muted border border-accent/25 p-4 rounded-xl flex items-start gap-3">
 <Activity aria-hidden="true" size={18} className="text-accent shrink-0 mt-0.5" />
 <div className="text-sm text-accent">
 <strong>Crucial Distinction:</strong> Do not confuse Risk with Confidence. The backend models calculate epistemic certainty strictly based on freshness, completeness, source provenance, AI parser certainty, and human sign-off.
 </div>
 </div>

 <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
 
 {/* LEFT: Portfolio Matrix & Vendor Selection (5 cols) */}
 <div className="lg:col-span-5 space-y-4">
 <Card className="shadow-sm flex flex-col h-[700px]">
 <CardHeader className="border-b border-border px-5 py-4 shrink-0">
 <CardTitle className="text-sm flex items-center gap-2">
 <BarChart3 aria-hidden="true" size={16} className="text-text-tertiary" />
 Portfolio Evidence Confidence
 </CardTitle>
 <div className="relative mt-3">
 <label htmlFor="epistemicSearchInput" className="sr-only">Search vendors</label>
 <Search aria-hidden="true" size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-text-tertiary" />
 <input
 id="epistemicSearchInput"
 type="search"
 value={searchQuery}
 onChange={(e) => setSearchQuery(e.target.value)}
 placeholder="Search vendors..."
 className="w-full bg-surface border border-border rounded-md pl-9 pr-3 py-1.5 text-xs text-text-primary focus:outline-none focus:ring-2 focus:ring-info"
 />
 </div>
 </CardHeader>

 <div className="flex-1 overflow-y-auto p-2 space-y-1 bg-surface-hover/30" role="region" aria-label="Vendor Epistemic Profiles List">
 {filteredProfiles.map(profile => {
 const isSelected = selectedProfile.id === profile.id;
 return (
 <button
 key={profile.id}
 type="button"
 onClick={() => setSelectedProfile(profile)}
 onKeyDown={(e) => {
 if (e.key === 'Enter' || e.key === ' ') {
 e.preventDefault();
 setSelectedProfile(profile);
 }
 }}
 className={`w-full text-left p-3 rounded-lg border transition-all focus:outline-none focus:ring-2 focus:ring-info ${
 isSelected 
 ? 'bg-surface border-border-strong shadow-sm ring-1 ring-border-strong' 
 : 'bg-surface border-transparent hover:border-border'
 }`}
 >
 <div className="font-bold text-text-primary text-sm mb-2">{profile.vendorName}</div>
 <div className="flex items-center gap-2">
 {getRiskBadge(profile.assessedRisk)}
 <span className="text-border-strong" aria-hidden="true">+</span>
 {getConfidenceBadge(profile.overallConfidence)}
 </div>
 </button>
 );
 })}
 </div>
 </Card>
 </div>

 {/* RIGHT: Selected Vendor Factor Breakdown (7 cols) */}
 <div className="lg:col-span-7 space-y-4">
 
 <Card className="shadow-sm border-accent/25">
 <CardContent className="p-6">
 <div className="flex items-start justify-between border-b border-border-subtle pb-5 mb-5">
 <div>
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary block">Epistemic Profile</span>
 <h2 className="text-xl font-bold text-text-primary mt-1 m-0">{selectedProfile.vendorName}</h2>
 
 {/* The Critical distinction visually presented */}
 <div className="flex items-center gap-3 mt-3">
 <div className="flex items-center gap-1.5">
 <ShieldAlert aria-hidden="true" size={14} className="text-text-secondary" />
 <span className="text-xs font-semibold text-text-secondary">Assessed Risk:</span>
 {getRiskBadge(selectedProfile.assessedRisk)}
 </div>
 <div aria-hidden="true" className="w-px h-4 bg-border"></div>
 <div className="flex items-center gap-1.5">
 <ShieldCheck aria-hidden="true" size={14} className="text-text-secondary" />
 <span className="text-xs font-semibold text-text-secondary">Data Confidence:</span>
 {getConfidenceBadge(selectedProfile.overallConfidence)}
 </div>
 </div>
 </div>

 <div className="text-right flex flex-col items-end">
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary block mb-1">Backend Confidence Score</span>
 <div className="text-2xl font-mono font-bold text-accent bg-accent-muted px-3 py-1 rounded-lg border border-accent/25">
 {selectedProfile.confidenceScore}
 </div>
 </div>
 </div>

 <h3 className="text-xs font-semibold uppercase tracking-wider text-text-tertiary mb-4 m-0">Underlying Confidence Factors</h3>
 
 <div className="space-y-3">
 {selectedProfile.factors.map((factor, idx) => (
 <div key={idx} className="flex items-start gap-4 p-4 rounded-xl border border-border bg-base hover:bg-surface-hover transition-colors">
 <div aria-hidden="true" className={`p-2 rounded-lg bg-surface border border-border-strong shrink-0 ${getFactorStatusColor(factor.status)}`}>
 {getFactorIcon(factor.category)}
 </div>
 
 <div className="flex-1 min-w-0">
 <div className="flex items-center justify-between mb-1">
 <span className="text-sm font-bold text-text-primary">{factor.category}</span>
 <span className="text-xs font-mono font-bold text-text-secondary bg-surface px-2 py-0.5 rounded border border-border">
 {factor.scoreLabel}
 </span>
 </div>
 <p className="text-xs text-text-secondary leading-relaxed m-0">
 {factor.description}
 </p>
 </div>
 </div>
 ))}
 </div>

 </CardContent>
 </Card>

 </div>
 </div>
 </div>
 );
}