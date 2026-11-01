// src/features/assessments/AssessmentDetailWorkspace.tsx
import React, { useState, useEffect } from 'react';
import { 
 CheckCircle2, HelpCircle, UserCheck, FileQuestion, 
 ShieldAlert, ArrowLeft, Info, FileText 
} from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent, Button } from '../../shared/ui/core';
import { AnalystReviewWorkspace } from './AnalystReviewWorkspace';
import { getVendorsWithStatus, resolveVendorId } from '../../data/vendors';
import { getEvidenceForVendor } from '../../data/evidence';
import { getRemediationsForVendor } from '../../data/remediation';

export type AssessmentBucket = 'verified' | 'vendor' | 'analyst' | 'evidence_gaps' | 'control_gaps';

export interface DueDiligenceItem {
 id: string;
 bucket: AssessmentBucket;
 controlId: string;
 questionTitle: string;
 whyPrompted: string;
 sourceArtifact?: string;
 suggestedAction: string;
}

const DEV_DUE_DILIGENCE_ITEMS: DueDiligenceItem[] = [
 {
 id: 'dd-01',
 bucket: 'verified',
 controlId: 'CC6.1',
 questionTitle: 'Is encryption at rest enforced using AES-256 keys?',
 whyPrompted: 'Satisfied by verified SOC 2 Type II Report (Page 14, Section 4.1). No further vendor input required.',
 sourceArtifact: 'CloudScale_SOC2_TypeII_2026.pdf',
 suggestedAction: 'Auto-mapped and locked by repository.'
 },
 {
 id: 'dd-02',
 bucket: 'vendor',
 controlId: 'CC7.2',
 questionTitle: 'Provide confirmation of secondary geographic failover redundancy targets.',
 whyPrompted: 'Existing SIG questionnaire left disaster recovery response blank for multi-region clustering.',
 suggestedAction: 'Request vendor confirmation on RTO/RPO SLAs.'
 },
 {
 id: 'dd-03',
 bucket: 'analyst',
 controlId: 'A.9.1',
 questionTitle: 'Review privilege escalation approval workflow exception logs.',
 whyPrompted: 'Vendor submitted custom attestation, but raw logs contain an unverified administrative override.',
 suggestedAction: 'Perform human audit of exception logs before sign-off.'
 },
 {
 id: 'dd-04',
 bucket: 'evidence_gaps',
 controlId: 'CC9.1',
 questionTitle: 'Supply current third-party penetration testing summary for mobile applications.',
 whyPrompted: 'Repository contains infrastructure pen test, but mobile endpoint scope is entirely missing.',
 suggestedAction: 'Upload targeted mobile pen test artifact.'
 },
 {
 id: 'dd-05',
 bucket: 'control_gaps',
 controlId: 'ISO A.12',
 questionTitle: 'Remediate missing vulnerability patching SLA documentation within 14 days.',
 whyPrompted: 'Current vendor policy allows 60-day patching windows, violating enterprise baseline maximum of 14 days.',
 suggestedAction: 'Issue formal control exception or demand policy update.'
 }
];

export interface AssessmentDetailWorkspaceProps {
 vendorName: string;
 assessmentType: string;
 vendorId?: string;
 onBack: () => void;
}

/** Vendors that ship with authored demo due-diligence content. */
const AUTHORED_ASSESSMENT_IDS = new Set(['vend-001']);

/**
 * Build an honest due-diligence item list from the vendor's OWN record —
 * evidence actually on file, real open remediations, tier and risk score.
 * Vendors without authored content get explicit "not yet known" states
 * instead of another vendor's demo story.
 */
function buildDueDiligenceItems(vendorId: string, vendorName: string): DueDiligenceItem[] {
 const vendor = getVendorsWithStatus().find(v => v.id === vendorId);
 const evidence = getEvidenceForVendor(vendorId, vendorName);
 const openRems = getRemediationsForVendor(vendorId).filter(
 r => r.status !== 'Resolved' && r.status !== 'Accepted risk'
 );
 const items: DueDiligenceItem[] = [];
 const tier = vendor?.criticality ?? 'Tier 3';
 const riskLevel = vendor?.riskLevel ?? 'Unknown';
 const riskScore = vendor?.riskScore;

 // Already verified — satisfied by artifacts actually on file.
 evidence
 .filter(e => e.status === 'Verified')
 .forEach((ev, i) => {
 items.push({
 id: `dd-verified-${i}`,
 bucket: 'verified',
 controlId: ev.controlMapping.split(' ')[0] || 'GEN',
 questionTitle: `${ev.evidenceType} attestation on file`,
 whyPrompted: `Satisfied by verified artifact "${ev.artifactName}" (${ev.citationRef}). No further vendor input required.`,
 sourceArtifact: ev.artifactName,
 suggestedAction: 'Auto-mapped and locked by repository.',
 });
 });

 // Evidence gaps — expired/expiring artifacts, or nothing on file at all.
 evidence
 .filter(e => e.status === 'Expired' || e.status === 'Expiring')
 .forEach((ev, i) => {
 items.push({
 id: `dd-evgap-${i}`,
 bucket: 'evidence_gaps',
 controlId: ev.controlMapping.split(' ')[0] || 'GEN',
 questionTitle: `Renew ${ev.evidenceType} — currently ${ev.status.toLowerCase()}`,
 whyPrompted: `"${ev.artifactName}" is ${ev.status.toLowerCase()} (expires ${ev.expirationDate}). Decisions cannot rely on stale attestations.`,
 suggestedAction: 'Request renewed artifact from vendor.',
 });
 });
 if (evidence.length === 0) {
 items.push({
 id: 'dd-evgap-baseline',
 bucket: 'evidence_gaps',
 controlId: 'GEN-01',
 questionTitle: 'Supply baseline security attestation (SOC 2 Type II or ISO 27001)',
 whyPrompted: `No evidence artifacts are on file for ${vendorName} yet.`,
 suggestedAction: 'Request baseline attestation from vendor.',
 });
 }

 // Vendor input — tier-aware questions, never vendor-specific fiction.
 items.push({
 id: 'dd-vendor-dr',
 bucket: 'vendor',
 controlId: 'BCP-01',
 questionTitle: tier === 'Tier 1'
 ? 'Provide confirmation of secondary geographic failover redundancy targets and RTO/RPO SLAs'
 : 'Confirm documented disaster recovery objectives (RTO/RPO)',
 whyPrompted: tier === 'Tier 1'
 ? `${vendorName} is classified Tier 1 — Critical. Failover posture must be evidenced, not assumed.`
 : `Disaster recovery posture for ${vendorName} has not been evidenced yet.`,
 suggestedAction: 'Request vendor confirmation on RTO/RPO SLAs.',
 });
 items.push({
 id: 'dd-vendor-access',
 bucket: 'vendor',
 controlId: 'CC6.1',
 questionTitle: 'Describe logical access controls for personnel with access to our data',
 whyPrompted: `Access-control posture for ${vendorName} is unknown until the vendor responds.`,
 suggestedAction: 'Send targeted access-control questionnaire.',
 });

 // Analyst review — only when the recorded risk warrants human eyes.
 if (riskScore !== undefined && riskScore >= 60) {
 items.push({
 id: 'dd-analyst-risk',
 bucket: 'analyst',
 controlId: 'RSK-01',
 questionTitle: `Review elevated risk score of ${riskScore} (${riskLevel}) before sign-off`,
 whyPrompted: `${vendorName} carries a ${riskLevel} risk rating. An analyst should confirm the rating reflects current posture.`,
 suggestedAction: 'Perform human review of risk drivers before sign-off.',
 });
 } else if (!vendor || vendor.assessmentStatus === 'Not Started') {
 items.push({
 id: 'dd-analyst-baseline',
 bucket: 'analyst',
 controlId: 'RSK-01',
 questionTitle: 'Establish baseline risk rating for this vendor',
 whyPrompted: `${vendorName} has not been assessed yet, so no risk rating exists to review.`,
 suggestedAction: 'Complete the vendor questionnaire first.',
 });
 }

 // Control gaps — real open remediations for this vendor.
 openRems.slice(0, 5).forEach((r, i) => {
 items.push({
 id: `dd-ctrlgap-${i}`,
 bucket: 'control_gaps',
 controlId: 'CTL-01',
 questionTitle: r.title,
 whyPrompted: `Open remediation ${r.id} (${r.severity}, aging ${r.agingDays}d): ${r.issue}`,
 suggestedAction: r.recommendedAction && r.recommendedAction !== '—' ? r.recommendedAction : 'Triage and assign an owner.',
 });
 });

 return items;
}

export function AssessmentDetailWorkspace({ vendorName, assessmentType, vendorId, onBack }: AssessmentDetailWorkspaceProps) {
 const resolvedId = vendorId ?? resolveVendorId(vendorName) ?? '';
 const [activeBucket, setActiveBucket] = useState<AssessmentBucket>('vendor');
 // CloudScale ships with authored demo content; every other vendor gets an
 // honest item list built from its own record — never copied demo data.
 const [items] = useState<DueDiligenceItem[]>(() =>
 AUTHORED_ASSESSMENT_IDS.has(resolvedId)
 ? DEV_DUE_DILIGENCE_ITEMS
 : buildDueDiligenceItems(resolvedId, vendorName)
 );
 const [isReviewing, setIsReviewing] = useState(false);
 const [requestedIds, setRequestedIds] = useState<Set<string>>(new Set());

 useEffect(() => {
 const handleKeyDown = (e: KeyboardEvent) => {
 if (e.key === 'Escape' && !isReviewing) {
 onBack();
 }
 };
 document.addEventListener('keydown', handleKeyDown);
 return () => document.removeEventListener('keydown', handleKeyDown);
 }, [isReviewing, onBack]);

 if (isReviewing) {
 return (
 <AnalystReviewWorkspace 
 vendorName={vendorName}
 onBack={() => setIsReviewing(false)}
 />
 );
 }

 const bucketCounts = {
 verified: items.filter(i => i.bucket === 'verified').length,
 vendor: items.filter(i => i.bucket === 'vendor').length,
 analyst: items.filter(i => i.bucket === 'analyst').length,
 evidence_gaps: items.filter(i => i.bucket === 'evidence_gaps').length,
 control_gaps: items.filter(i => i.bucket === 'control_gaps').length
 };

 const currentItems = items.filter(i => i.bucket === activeBucket);

 const bucketConfig: Record<AssessmentBucket, { label: string; icon: React.ReactNode; desc: string; color: string }> = {
 verified: {
 label: 'Already Verified',
 icon: <CheckCircle2 aria-hidden="true" size={16} className="text-status-success" />,
 desc: 'Requirements already satisfied by existing repository artifacts. Zero redundant questioning.',
 color: ' bg-status-success/5 text-status-success'
 },
 vendor: {
 label: 'Vendor Input Required',
 icon: <HelpCircle aria-hidden="true" size={16} className="text-info" />,
 desc: 'Targeted questions that genuinely require vendor response due to unknown operational criteria.',
 color: 'border-info/30 bg-info/5 text-info'
 },
 analyst: {
 label: 'Analyst Review',
 icon: <UserCheck aria-hidden="true" size={16} className="text-status-warning" />,
 desc: 'Vendor responses or AI extractions requiring direct human judgement and verification.',
 color: ' bg-status-warning/5 text-status-warning'
 },
 evidence_gaps: {
 label: 'Evidence Gaps',
 icon: <FileQuestion aria-hidden="true" size={16} className="text-risk-high" />,
 desc: 'Identified missing documentation required to validate compliance claims.',
 color: ' bg-risk-high/5 text-risk-high'
 },
 control_gaps: {
 label: 'Control Gaps',
 icon: <ShieldAlert aria-hidden="true" size={16} className="text-risk-critical" />,
 desc: 'Active compliance requirements not satisfied by current vendor posture.',
 color: ' bg-risk-critical/5 text-risk-critical'
 }
 };

 return (
 <div className="space-y-6 max-w-7xl mx-auto pb-28 animate-in fade-in duration-300">
 
 <div aria-live="polite" className="sr-only">
 Viewing assessment detail for {vendorName}. Active bucket: {bucketConfig[activeBucket].label}, containing {currentItems.length} items.
 </div>

 <div className="flex items-center justify-between border-b border-border pb-4">
 <div className="flex items-center gap-3">
 <Button type="button" variant="secondary" size="sm" onClick={onBack} className="h-8 px-3 text-xs gap-1.5">
 <ArrowLeft aria-hidden="true" size={14} />
 <span>Back to Portfolio</span>
 </Button>
 <div aria-hidden="true" className="h-4 w-px bg-border"></div>
 <div>
 <h1 className="text-lg font-bold text-text-primary">{vendorName}</h1>
 <p className="text-xs text-text-secondary">{assessmentType} • Adaptive Due Diligence Stream</p>
 </div>
 </div>
 </div>

 <div className="bg-surface border border-border p-4 rounded-xl flex items-start gap-3">
 <Info aria-hidden="true" size={18} className="text-info shrink-0 mt-0.5" />
 <div className="text-xs text-text-secondary leading-relaxed">
 <strong className="text-text-primary">Adaptive Workflow Active:</strong> Instead of answering 150 static questions, the assessment engine has filtered out satisfied controls and isolated exact delta items into focused operational buckets.
 </div>
 </div>

 <div className="grid grid-cols-2 md:grid-cols-5 gap-3" role="tablist" aria-label="Assessment Buckets">
 {(Object.keys(bucketConfig) as AssessmentBucket[]).map((key) => {
 const cfg = bucketConfig[key];
 const isActive = activeBucket === key;
 const count = bucketCounts[key];

 return (
 <button
 key={key}
 type="button"
 role="tab"
 aria-selected={isActive}
 aria-controls="bucket-item-list"
 onClick={() => setActiveBucket(key)}
 className={`p-4 rounded-xl border text-left transition-all flex flex-col justify-between focus:outline-none focus:ring-2 focus:ring-info ${
 isActive 
 ? 'bg-surface border-border-strong shadow-sm ring-2 ring-info/20' 
 : 'bg-base border-border hover:bg-surface-hover'
 }`}
 >
 <div className="flex items-center justify-between mb-2">
 <div className="p-1.5 rounded-lg bg-surface border border-border-strong">
 {cfg.icon}
 </div>
 <span className={`text-sm font-bold font-mono px-2 py-0.5 rounded border ${cfg.color}`}>
 {count}
 </span>
 </div>
 <div>
 <h3 className="text-xs font-bold text-text-primary mb-1 m-0">{cfg.label}</h3>
 <p className="text-[11px] text-text-tertiary line-clamp-2 m-0">{cfg.desc}</p>
 </div>
 </button>
 );
 })}
 </div>

 <Card id="bucket-item-list" role="tabpanel" className="shadow-sm overflow-hidden">
 <CardHeader className="border-b border-border px-6 py-4 bg-surface-hover/30 flex flex-row items-center justify-between">
 <div className="flex items-center gap-2">
 {bucketConfig[activeBucket].icon}
 <CardTitle className="text-sm font-bold">{bucketConfig[activeBucket].label} Items</CardTitle>
 </div>
 <span className="text-xs font-mono text-text-tertiary">{currentItems.length} items requiring attention</span>
 </CardHeader>

 <CardContent className="p-0 divide-y divide-border-subtle bg-surface">
 {currentItems.length > 0 ? (
 currentItems.map((item) => (
 <div key={item.id} className="p-6 hover:bg-surface-hover transition-colors flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
 <div className="space-y-2 max-w-3xl">
 <div className="flex items-center gap-2">
 <span className="text-xs font-mono font-bold text-text-primary bg-base px-2 py-0.5 rounded border border-border">
 {item.controlId}
 </span>
 <h4 className="text-sm font-bold text-text-primary m-0">
 {item.questionTitle}
 </h4>
 </div>

 <div className="bg-base border border-border p-3 rounded-lg text-xs text-text-secondary flex items-start gap-2">
 <Info aria-hidden="true" size={14} className="text-info shrink-0 mt-0.5" />
 <div className="m-0">
 <strong className="text-text-primary">Why this is asked:</strong> {item.whyPrompted}
 </div>
 </div>

 {item.sourceArtifact && (
 <div className="flex items-center gap-1.5 text-xs text-text-tertiary font-mono">
 <FileText aria-hidden="true" size={13} />
 <span>Source: {item.sourceArtifact}</span>
 </div>
 )}
 </div>

 <div className="shrink-0 flex flex-col items-end gap-2 w-full md:w-auto">
 <span className="text-[11px] font-semibold text-text-secondary bg-base px-3 py-1.5 rounded-lg border border-border text-center w-full md:w-auto">
 {item.suggestedAction}
 </span>
 {activeBucket === 'analyst' && (
 <>
 <Button 
 type="button"
 variant="primary" 
 size="sm" 
 onClick={() => setIsReviewing(true)}
 className="text-xs h-8 w-full md:w-auto"
 >
 Review & Sign Off
 </Button>
 <button type="button" onClick={() => setIsReviewing(true)} className="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 text-[11px] font-semibold text-accent bg-accent-muted hover:bg-accent-muted rounded-md transition-colors w-full mt-2 focus:ring-2 focus:ring-info outline-none cursor-pointer">
 <HelpCircle aria-hidden="true" size={12} className="opacity-70" /> Suggest Clarifications
 </button>
 </>
 )}
 {activeBucket === 'vendor' && (
 <Button
 type="button"
 variant="secondary"
 size="sm"
 className="text-xs h-8 w-full md:w-auto"
 disabled={requestedIds.has(item.id)}
 onClick={() => setRequestedIds(prev => new Set(prev).add(item.id))}
 >
 {requestedIds.has(item.id) ? '✓ Request Sent' : 'Request from Vendor'}
 </Button>
 )}
 </div>
 </div>
 ))
 ) : (
 <div className="py-16 text-center text-sm text-text-tertiary">
 No items in this category. All requirements are fully resolved or correctly routed.
 </div>
 )}
 </CardContent>
 </Card>

 </div>
 );
}