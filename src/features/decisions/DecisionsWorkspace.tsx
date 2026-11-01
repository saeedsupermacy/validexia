// src/features/decisions/DecisionsWorkspace.tsx
import { useState, useMemo, useEffect, useRef } from 'react';
import { getNavParams, clearNavParams, navigateTo } from '../../shared/navigation';
import { resolveVendorId } from '../../data/vendors';
import { getDecisions, type DecisionRecord, type DecisionType } from '../../data/decisions';
import { DeepLinkBanner } from '../../shared/ui/DeepLinkBanner';
import { createPortal } from 'react-dom';
import { 
 Gavel, Search, Building2, User, Clock, ArrowRight, X, History,
 ShieldAlert, FileText, CheckCircle2, AlertTriangle, FileCheck, Sparkles, ArrowLeft, Layers
} from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent, Button, EmptyState, Eyebrow} from '../../shared/ui/core';
import { NetworkErrorBanner, PermissionErrorModal, ExpiredSessionModal } from '../../shared/ui/ErrorStates';

export interface DecisionReplayState {
 decisionId: string;
 decisionTitle: string;
 vendorName: string;
 decisionMaker: string;
 timestamp: string;
 decisionType: string;
 whatWeKnew: string[];
 evidenceExisted: {
 id: string;
 documentName: string;
 versionHash: string;
 validityStatus: string;
 }[];
 whatChanged: string[];
 riskCalculated: string;
 uncertaintyExisted: string[];
 recommendationMade: string;
 decisionRationale: string;
 actionsFollowed: string[];
}



export function DecisionsWorkspace() {
 const [searchQuery, setSearchQuery] = useState('');
 const [deepLink, setDeepLink] = useState<string | null>(null);
 const [deepLinkSource, setDeepLinkSource] = useState<string>('another tab');
 const [deepLinkVendor, setDeepLinkVendor] = useState<string | null>(null);

 // Deep link: pre-fill search from another tab (accepts search or vendorName)
 useEffect(() => {
 const params = getNavParams();
 const q = params.search || params.vendorName;
 if (q && typeof q === 'string') {
 setSearchQuery(q as string);
 setDeepLink(q as string);
 }
 if (params.vendorName && typeof params.vendorName === 'string') {
 setDeepLinkVendor(params.vendorName as string);
 }
 if (params.from && typeof params.from === 'string') {
 setDeepLinkSource(params.from as string);
 }
 if (q) clearNavParams();
 }, []);
 const highlightRowRef = useRef<HTMLTableRowElement | null>(null);
 useEffect(() => {
 if (deepLink && highlightRowRef.current) {
 highlightRowRef.current.scrollIntoView({ behavior: 'smooth', block: 'center' });
 }
 }, [deepLink]);
 const [typeFilter, setTypeFilter] = useState<string>('All');
 const [selectedDecision, setSelectedDecision] = useState<DecisionRecord | null>(null);
 const [replayingDecision, setReplayingDecision] = useState<DecisionRecord | null>(null);
 const isReplaying = replayingDecision !== null;

 // Point-in-time reconstruction is derived from the selected ledger record.
 function buildReplayState(dec: DecisionRecord): DecisionReplayState {
 return {
 decisionId: dec.id,
 decisionTitle: dec.decisionTitle,
 vendorName: dec.vendorName,
 decisionMaker: dec.decisionMaker,
 timestamp: dec.timestamp,
 decisionType: dec.decisionType,
 whatWeKnew: [dec.context],
 evidenceExisted: dec.evidenceRef
 ? [{ id: 'ev-01', documentName: dec.evidenceRef, versionHash: 'demo-ref:unversioned', validityStatus: 'Demo reference — no archived snapshot' }]
 : [],
 whatChanged: dec.knownGaps,
 riskCalculated: dec.riskDescription,
 uncertaintyExisted: dec.knownGaps,
 recommendationMade: dec.recommendation,
 decisionRationale: dec.decisionRationale,
 actionsFollowed: dec.relatedRemediation ? [dec.relatedRemediation] : [],
 };
 }

 // Error simulation states
 const [hasNetworkError, setHasNetworkError] = useState(false);
 const [showPermissionModal, setShowPermissionModal] = useState(false);
 const [showSessionExpired, setShowSessionExpired] = useState(false);

 useEffect(() => {
 const handleKeyDown = (e: KeyboardEvent) => {
 if (e.key === 'Escape') {
 if (selectedDecision) setSelectedDecision(null);
 if (replayingDecision) setReplayingDecision(null);
 if (hasNetworkError) setHasNetworkError(false);
 if (showPermissionModal) setShowPermissionModal(false);
 if (showSessionExpired) setShowSessionExpired(false);
 }
 };
 document.addEventListener('keydown', handleKeyDown);
 return () => document.removeEventListener('keydown', handleKeyDown);
 }, [selectedDecision, replayingDecision, hasNetworkError, showPermissionModal, showSessionExpired]);

 const allDecisions = getDecisions();

 const filteredDecisions = useMemo(() => {
 return allDecisions.filter(dec => {
 const matchesSearch = 
 dec.decisionTitle.toLowerCase().includes(searchQuery.toLowerCase()) ||
 dec.vendorName.toLowerCase().includes(searchQuery.toLowerCase()) ||
 dec.decisionMaker.toLowerCase().includes(searchQuery.toLowerCase()) ||
 dec.id.toLowerCase().includes(searchQuery.toLowerCase());

 const matchesType = typeFilter === 'All' || dec.decisionType === typeFilter;
 return matchesSearch && matchesType;
 });
 }, [allDecisions, searchQuery, typeFilter]);

 const getDecisionBadge = (type: DecisionType) => {
 const styles: Record<DecisionType, string> = {
 'Approve': 'bg-status-success/10 text-status-success ',
 'Reject': 'bg-risk-critical/10 text-risk-critical ',
 'Approve with conditions': 'bg-info/10 text-info border-info/30',
 'Accept risk': 'bg-risk-high/10 text-risk-high ',
 'Request remediation': 'bg-accent-muted text-accent ',
 'Reassess': 'bg-surface-hover text-text-secondary border-border'
 };
 return <span className={`px-2.5 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wider ${styles[type]}`}>{type}</span>;
 };

 if (isReplaying && replayingDecision) {
 const REPLAY = buildReplayState(replayingDecision);
 return (
 <div className="space-y-6 max-w-5xl mx-auto pb-28 animate-in fade-in duration-300">
 
 <div aria-live="polite" className="sr-only">
 Replaying historical state for decision {REPLAY.decisionId}: {REPLAY.decisionTitle}
 </div>

 {hasNetworkError && <NetworkErrorBanner onRetry={() => setHasNetworkError(false)} />}
 {showPermissionModal && <PermissionErrorModal onClose={() => setShowPermissionModal(false)} />}
 {showSessionExpired && <ExpiredSessionModal onReauth={() => setShowSessionExpired(false)} />}

 <div className="flex items-center justify-between border-b border-border pb-6">
 <div>
 <div className="flex items-center gap-2 mb-1">
 <span className="font-mono text-xs font-bold text-info bg-info/10 px-2 py-0.5 rounded border border-info/30">{REPLAY.decisionId}</span>
 <span className="text-xs font-bold text-status-warning uppercase tracking-wider">Demo replay · record-derived</span>
 </div>
 <Eyebrow>Governance</Eyebrow>
 <h1 className="text-page-title">{REPLAY.decisionTitle}</h1>
 </div>
 <Button type="button" variant="secondary" size="sm" onClick={() => setReplayingDecision(null)} className="h-9">
 <ArrowLeft aria-hidden="true" size={14} className="mr-1.5" /> Back to Decisions
 </Button>
 </div>

 <div role="note" className="rounded-lg border border-status-warning/30 bg-status-warning/10 px-4 py-3 text-xs text-text-secondary">
  Demo reconstruction from the selected decision record only; this is not an archived historical snapshot. Evidence and follow-up details are illustrative.
 </div>

 <Card className="shadow-sm bg-surface-hover/30">
 <CardContent className="p-4 grid grid-cols-1 sm:grid-cols-3 gap-4 text-xs">
 <div>
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary block">Vendor Entity</span>
 <span className="font-bold text-text-primary mt-0.5 block">{REPLAY.vendorName}</span>
 </div>
 <div>
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary block">Approved By</span>
 <span className="font-bold text-text-primary mt-0.5 block flex items-center gap-1">
 <User aria-hidden="true" size={13} className="text-text-tertiary" /> {REPLAY.decisionMaker}
 </span>
 </div>
 <div>
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary block">Reconstructed Timestamp</span>
 <span className="font-mono text-text-secondary mt-0.5 block flex items-center gap-1">
 <Clock aria-hidden="true" size={13} /> {REPLAY.timestamp}
 </span>
 </div>
 </CardContent>
 </Card>

 <div className="space-y-6">
 <Card className="shadow-sm">
 <CardHeader className="border-b border-border px-6 py-4 bg-surface-hover/20">
 <CardTitle className="text-xs font-semibold uppercase tracking-wider flex items-center gap-2">
 <History aria-hidden="true" size={15} className="text-info" /> 1. Context Recorded on the Decision
 </CardTitle>
 </CardHeader>
 <CardContent className="p-6 space-y-2">
 <ul className="list-disc pl-5 space-y-1.5 text-xs text-text-primary font-medium">
 {REPLAY.whatWeKnew.map((item, idx) => (
 <li key={idx}>{item}</li>
 ))}
 </ul>
 </CardContent>
 </Card>

 <Card className="shadow-sm">
 <CardHeader className="border-b border-border px-6 py-4 bg-surface-hover/20">
 <CardTitle className="text-xs font-semibold uppercase tracking-wider flex items-center gap-2">
 <FileCheck aria-hidden="true" size={15} className="text-status-success" /> 2. Evidence References on the Decision Record
 </CardTitle>
 </CardHeader>
 <CardContent className="p-6">
 <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
 {REPLAY.evidenceExisted.map(ev => (
 <div key={ev.id} className="p-4 bg-base border border-border rounded-xl space-y-2">
 <div className="flex items-center justify-between">
 <span className="font-bold text-text-primary text-xs flex items-center gap-1.5">
 <FileText aria-hidden="true" size={14} className="text-text-tertiary" /> {ev.documentName}
 </span>
 <span className="text-[10px] font-semibold text-status-success">{ev.validityStatus}</span>
 </div>
 <div className="font-mono text-[10px] text-text-tertiary bg-surface px-2 py-1 rounded border border-border-subtle">
 {ev.versionHash}
 </div>
 </div>
 ))}
 </div>
 </CardContent>
 </Card>

 <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
 <Card className="shadow-sm">
 <CardHeader className="border-b border-border px-5 py-4 bg-surface-hover/20">
 <CardTitle className="text-xs font-semibold uppercase tracking-wider flex items-center gap-2">
 <Layers aria-hidden="true" size={15} className="text-risk-high" /> 3. Gaps Recorded on the Decision
 </CardTitle>
 </CardHeader>
 <CardContent className="p-5 space-y-2">
 <ul className="list-disc pl-4 space-y-1 text-xs text-text-secondary font-medium">
 {REPLAY.whatChanged.map((change, idx) => (
 <li key={idx}>{change}</li>
 ))}
 </ul>
 </CardContent>
 </Card>

 <Card className="shadow-sm">
 <CardHeader className="border-b border-border px-5 py-4 bg-surface-hover/20">
 <CardTitle className="text-xs font-semibold uppercase tracking-wider flex items-center gap-2">
 <ShieldAlert aria-hidden="true" size={15} className="text-risk-critical" /> Risk Description on Record
 </CardTitle>
 </CardHeader>
 <CardContent className="p-5">
 <p className="text-xs font-bold text-risk-critical leading-relaxed m-0">
 {REPLAY.riskCalculated}
 </p>
 </CardContent>
 </Card>
 </div>

 <Card className="shadow-sm ">
 <CardHeader className="border-b border-border px-6 py-4 bg-risk-moderate/50 dark:bg-risk-moderate/10">
 <CardTitle className="text-xs font-semibold uppercase tracking-wider flex items-center gap-2 text-risk-moderate">
 <AlertTriangle aria-hidden="true" size={15} /> 4. Known Gaps on the Decision Record
 </CardTitle>
 </CardHeader>
 <CardContent className="p-6 space-y-2">
 <ul className="list-disc pl-5 space-y-1 text-xs text-risk-moderate font-medium">
 {REPLAY.uncertaintyExisted.map((unknown, idx) => (
 <li key={idx}>{unknown}</li>
 ))}
 </ul>
 </CardContent>
 </Card>

 <Card className="shadow-sm border-accent/25">
 <CardHeader className="border-b border-border px-6 py-4 bg-surface-hover/30">
 <CardTitle className="text-xs font-semibold uppercase tracking-wider flex items-center gap-2 text-accent">
 <Sparkles aria-hidden="true" size={15} /> 5. Recommendation & Defensible Rationale
 </CardTitle>
 </CardHeader>
 <CardContent className="p-6 space-y-4 text-xs">
 <div className="space-y-1">
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary">Initial Recommendation</span>
 <p className="text-text-primary font-medium m-0">{REPLAY.recommendationMade}</p>
 </div>
 <div className="p-4 bg-accent-muted/50 border border-accent/25 rounded-xl space-y-1">
 <span className="text-[10px] font-semibold uppercase tracking-wider text-accent">Why They Approved It (Defensible Rationale)</span>
 <p className="text-text-primary font-medium leading-relaxed m-0">{REPLAY.decisionRationale}</p>
 </div>
 </CardContent>
 </Card>

 <Card className="shadow-sm">
 <CardHeader className="border-b border-border px-6 py-4 bg-surface-hover/20">
 <CardTitle className="text-xs font-semibold uppercase tracking-wider flex items-center gap-2">
 <CheckCircle2 aria-hidden="true" size={15} className="text-status-success" /> 6. Related Remediation on the Decision Record
 </CardTitle>
 </CardHeader>
 <CardContent className="p-6 space-y-2">
 <ul className="list-disc pl-5 space-y-1 text-xs text-text-secondary font-medium">
 {REPLAY.actionsFollowed.map((action, idx) => (
 <li key={idx}>{action}</li>
 ))}
 </ul>
 </CardContent>
 </Card>
 </div>
 </div>
 );
 }

 return (
 <div className="space-y-6 max-w-7xl mx-auto pb-28 animate-in fade-in duration-300">
 
 <div aria-live="polite" className="sr-only">
 Decisions loaded. {filteredDecisions.length} decision records match your search criteria.
 </div>

 {hasNetworkError && <NetworkErrorBanner onRetry={() => setHasNetworkError(false)} />}
 {showPermissionModal && <PermissionErrorModal onClose={() => setShowPermissionModal(false)} />}
 {showSessionExpired && <ExpiredSessionModal onReauth={() => setShowSessionExpired(false)} />}

 <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-4 border-b border-border pb-6">
 <div>
 <Eyebrow>Governance</Eyebrow>
 <h1 className="text-page-title">Decisions</h1> {deepLink && (
 <div className="mt-3">
 <DeepLinkBanner
 label={`Search = "${deepLink}"`}
 source={deepLinkSource}
 count={filteredDecisions.length}
 backLabel={deepLinkVendor ? `Back to ${deepLinkVendor}` : undefined}
 onBack={deepLinkVendor ? () => {
 const vid = resolveVendorId(deepLinkVendor);
 navigateTo('vendors', vid ? { vendorId: vid } : { search: deepLinkVendor });
 } : undefined}
 onClear={() => { setDeepLink(null); setDeepLinkVendor(null); setSearchQuery(''); }}
 />
 </div>
 )}
 <p className="text-body-secondary mt-1">
 Maintain a decision audit trail of vendor approvals, risk acceptances, and defensible rationale. Demo data.
 </p>
 </div>
 <div className="flex flex-wrap items-center gap-2">
 </div>
 </div>

 <Card className="shadow-sm">
 <CardContent className="p-4 flex flex-col md:flex-row items-stretch md:items-center justify-between gap-4">
 <div className="relative flex-1 max-w-md">
 <label htmlFor="decisionSearchInput" className="sr-only">Search decisions, vendors, decision makers, IDs</label>
 <Search aria-hidden="true" size={16} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-text-tertiary" />
 <input
 id="decisionSearchInput"
 type="search"
 value={searchQuery}
 onChange={(e) => setSearchQuery(e.target.value)}
 placeholder="Search decisions, vendors, decision makers, IDs..."
 className="w-full bg-surface border border-border rounded-lg pl-10 pr-4 py-2 text-sm text-text-primary placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-info shadow-sm"
 />
 </div>

 <div className="flex flex-wrap items-center gap-3">
 <div>
 <label htmlFor="decisionTypeFilter" className="sr-only">Filter by decision type</label>
 <select
 id="decisionTypeFilter"
 value={typeFilter}
 onChange={(e) => setTypeFilter(e.target.value)}
 className="bg-surface border border-border rounded-lg px-3 py-2 text-xs text-text-secondary font-medium focus:outline-none focus:ring-2 focus:ring-info"
 >
 <option value="All">All Decision Types</option>
 <option value="Approve">Approve</option>
 <option value="Reject">Reject</option>
 <option value="Approve with conditions">Approve with conditions</option>
 <option value="Accept risk">Accept risk</option>
 <option value="Request remediation">Request remediation</option>
 <option value="Reassess">Reassess</option>
 </select>
 </div>
 </div>
 </CardContent>
 </Card>

 {filteredDecisions.length > 0 ? (
 <Card className="overflow-hidden shadow-sm">
 <div className="overflow-x-auto">
 <table className="w-full text-left text-sm whitespace-nowrap">
 <caption className="sr-only">Decision Records</caption>
 <thead className="bg-surface-hover border-b border-border text-label">
 <tr>
 <th scope="col" className="py-3 px-5 font-bold uppercase tracking-wider text-[11px]">ID & Decision Title</th>
 <th scope="col" className="py-3 px-4 font-bold uppercase tracking-wider text-[11px]">Vendor</th>
 <th scope="col" className="py-3 px-4 font-bold uppercase tracking-wider text-[11px]">Decision Type</th>
 <th scope="col" className="py-3 px-4 font-bold uppercase tracking-wider text-[11px]">Decision Maker & Timestamp</th>
 <th scope="col" className="py-3 px-4 font-bold uppercase tracking-wider text-[11px]">Related Remediation</th>
 <th scope="col" className="py-3 px-5 text-right font-bold uppercase tracking-wider text-[11px]"><span className="sr-only">Actions</span></th>
 </tr>
 </thead>
 <tbody className="divide-y divide-border-subtle bg-surface">
 {filteredDecisions.map((dec) => {
 const isHighlighted = !!deepLink && (dec.vendorName.toLowerCase().includes(deepLink.toLowerCase()) || dec.decisionTitle.toLowerCase().includes(deepLink.toLowerCase()));
 return (
 <tr
 key={dec.id}
 ref={isHighlighted ? highlightRowRef : undefined}
 onClick={() => setSelectedDecision(dec)}
 onKeyDown={(e) => {
 if (e.key === 'Enter' || e.key === ' ') {
 e.preventDefault();
 setSelectedDecision(dec);
 }
 }}
 tabIndex={0}
 role="button"
 aria-label={`Inspect decision ${dec.id}: ${dec.decisionTitle}`}
 className={`hover:bg-surface-hover transition-colors cursor-pointer group focus:bg-surface-hover focus:outline-none focus:ring-2 focus:ring-inset focus:ring-info ${isHighlighted ? 'bg-info/10 shadow-[inset_3px_0_0_0_var(--color-info)]' : ''}`}
 >
 <td className="py-4 px-5 max-w-xs">
 <div className="flex items-center gap-2 mb-1">
 <span className="font-mono text-xs font-bold text-info">{dec.id}</span>
 </div>
 <p className="text-xs font-bold text-text-primary truncate max-w-xs group-hover:text-info transition-colors m-0">
 {dec.decisionTitle}
 </p>
 </td>

 <td className="py-4 px-4">
 <div className="font-semibold text-text-primary text-xs flex items-center gap-1.5">
 <Building2 aria-hidden="true" size={13} className="text-text-tertiary" /> {dec.vendorName}
 </div>
 </td>

 <td className="py-4 px-4">
 {getDecisionBadge(dec.decisionType)}
 </td>

 <td className="py-4 px-4">
 <div className="text-xs font-medium text-text-primary flex items-center gap-1.5">
 <User aria-hidden="true" size={13} className="text-text-tertiary" /> {dec.decisionMaker}
 </div>
 <div className="text-[11px] font-mono text-text-tertiary mt-0.5 flex items-center gap-1">
 <Clock aria-hidden="true" size={12} /> {dec.timestamp}
 </div>
 </td>

 <td className="py-4 px-4 text-xs font-mono text-text-secondary">
 {dec.relatedRemediation}
 </td>

 <td className="py-4 px-5 text-right flex items-center justify-end gap-2">
 <button 
 type="button"
 onClick={(e) => { e.stopPropagation(); setReplayingDecision(dec); }}
 onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') e.stopPropagation(); }}
 className="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-semibold text-accent bg-accent-muted hover:bg-accent-muted border border-accent/25 rounded-md transition-colors focus:outline-none focus:ring-2 focus:ring-info"
 title="Reconstruct historical state"
 >
 <History aria-hidden="true" size={12} /> Replay
 </button>
 <button 
 type="button"
 onClick={(e) => { e.stopPropagation(); setSelectedDecision(dec); }}
 onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') e.stopPropagation(); }}
 className="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-semibold text-text-secondary bg-base hover:bg-surface-hover border border-border rounded-md transition-colors focus:outline-none focus:ring-2 focus:ring-info"
 >
 Inspect <ArrowRight aria-hidden="true" size={12} />
 </button>
 </td>
 </tr>
 );
 })}
 </tbody>
 </table>
 </div>
 </Card>
 ) : (
 <EmptyState
 icon={Gavel}
 title={deepLink ? `No Results for "${deepLink}"` : "No Governance Decisions Found"}
 description={deepLink ? "No records match this deep link. The item may have been resolved, archived, or renamed." : "No governance decisions or waivers match your current search and filter criteria."}
 whatItIs="Auditable ledger capturing executive sign-offs, risk waivers, and policy exceptions."
 whyItsEmpty="Current search parameters or type filters cleared all matching decision records."
 actionLabel={deepLink ? "Clear Deep Link" : "Clear Filters"}
 onAction={() => { setDeepLink(null); setSearchQuery(''); setTypeFilter('All'); }}
 />
 )}

 {selectedDecision && createPortal(
 <div 
 role="dialog"
 aria-modal="true"
 aria-labelledby="decision-modal-title"
 className="fixed inset-0 z-[100] flex items-center justify-center bg-black/40 p-4 animate-in fade-in duration-200"
 onClick={() => setSelectedDecision(null)}
 >
 <div className="bg-surface rounded-2xl max-w-3xl w-full max-h-[90vh] overflow-y-auto shadow-[0_8px_30px_rgb(0,0,0,0.12)] dark:shadow-[0_8px_30px_rgb(0,0,0,0.5)] p-6 space-y-6" onClick={(e) => e.stopPropagation()}>
 <div className="flex items-start justify-between border-b border-border pb-4">
 <div>
 <div className="flex items-center gap-2 mb-1">
 <span className="font-mono text-xs font-bold text-info bg-info/10 px-2 py-0.5 rounded border border-info/30">{selectedDecision.id}</span>
 {getDecisionBadge(selectedDecision.decisionType)}
 </div>
 <h2 id="decision-modal-title" className="text-base font-bold text-text-primary mt-1 m-0">{selectedDecision.decisionTitle}</h2>
 </div>
 <button 
 type="button"
 onClick={() => setSelectedDecision(null)} 
 className="p-1.5 rounded-lg hover:bg-surface-hover text-text-tertiary hover:text-text-primary transition-colors focus:outline-none focus:ring-2 focus:ring-info"
 aria-label="Close decision inspector"
 >
 <X aria-hidden="true" size={18} />
 </button>
 </div>

 <div className="space-y-4 text-xs">
 <div className="bg-accent-muted/50 border border-accent/25 p-4 rounded-xl space-y-1.5">
 <span className="text-[10px] font-semibold uppercase tracking-wider text-accent flex items-center gap-1.5">
 <Gavel aria-hidden="true" size={14} /> Defensible Decision Rationale
 </span>
 <p className="text-text-primary font-medium leading-relaxed m-0">{selectedDecision.decisionRationale}</p>
 </div>

 <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
 <div className="bg-base border border-border p-4 rounded-xl space-y-1">
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary">Context & Situation</span>
 <p className="text-text-primary leading-relaxed font-medium m-0">{selectedDecision.context}</p>
 </div>
 <div className="bg-base border border-border p-4 rounded-xl space-y-1">
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary">Business Impact</span>
 <p className="text-text-primary leading-relaxed font-medium m-0">{selectedDecision.businessImpact}</p>
 </div>
 </div>

 <div className="bg-base border border-border p-4 rounded-xl space-y-1">
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary">Risk Evaluated</span>
 <p className="text-risk-critical font-medium leading-relaxed m-0">{selectedDecision.riskDescription}</p>
 </div>

 <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
 <div className="bg-base border border-border p-4 rounded-xl space-y-2">
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary">Options Considered</span>
 <ul className="list-disc pl-4 space-y-1 text-text-secondary font-medium">
 {selectedDecision.optionsConsidered.map((opt, idx) => (
 <li key={idx}>{opt}</li>
 ))}
 </ul>
 </div>
 <div className="bg-base border border-border p-4 rounded-xl space-y-2">
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary">Known Gaps (What AI / Reviewer Did Not Know)</span>
 <ul className="list-disc pl-4 space-y-1 text-risk-critical font-medium">
 {selectedDecision.knownGaps.map((gap, idx) => (
 <li key={idx}>{gap}</li>
 ))}
 </ul>
 </div>
 </div>
 </div>

 <div className="flex items-center justify-between pt-4 border-t border-border text-xs">
 <Button type="button" variant="secondary" size="sm" onClick={() => { setReplayingDecision(selectedDecision); setSelectedDecision(null); }} className="gap-1.5 text-accent">
 <History aria-hidden="true" size={14} /> Launch Decision Replay
 </Button>
 <Button type="button" variant="secondary" size="sm" onClick={() => setSelectedDecision(null)}>
 Close Ledger
 </Button>
 </div>
 </div>
 </div>
 , document.body)}
 </div>
 );
}