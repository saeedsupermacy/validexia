// src/features/vendors/VendorDetailWorkspace.tsx
import { useState, useEffect, useMemo } from 'react';
import { 
 Building2, ShieldAlert, FileCheck, 
 Server, Lock, Gavel, ChevronDown, Upload, Wrench, ExternalLink, UserX 
} from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent, Button } from '../../shared/ui/core';
import { NetworkErrorBanner, PermissionErrorModal, ExpiredSessionModal } from '../../shared/ui/ErrorStates';
import { navigateTo } from '../../shared/navigation';
import { WhyThisExposure } from './WhyThisExposure';
import { BusinessImpactSection } from './BusinessImpactSection';
import { RiskDimensionsSection } from './RiskDimensionsSection';
import { hasAuthoredDetail, buildExposureModel, buildBusinessImpactModel, buildRiskDimensionsModel } from './vendorDetailModel';
import { offboardVendor, getVendorsWithStatus } from '../../data/vendors';
import type { VendorRecord } from '../../data/vendors';
import { getEvidenceForVendor } from '../../data/evidence';
import { getRemediationsForVendor, addRemediation, type RemediationSeverity } from '../../data/remediation';
import { getDecisionsForVendor, addDecision, type DecisionType } from '../../data/decisions';
import { recordMaterialChange } from '../../data/materialChanges';
import { showToast } from '../../shared/ui/toast';
import { auditStore } from '../audit/auditStore';
import { Dialog } from '../../shared/ui/Dialog';

/** Detail view-model: canonical vendor fields plus related shared evidence/remediation/decision records. */
export interface VendorDetailViewModel extends VendorRecord {
 category: string;
 domain: string;
 dependentSystems: Array<{ name: string; dataHandled: string; residency: string }>;
 evidence: Array<{ name: string; status: string; date: string; confidence?: string }>;
 remediation: Array<{ id?: string; title: string; owner: string; aging: string; severity?: string }>;
 decisions: Array<{ date: string; action: string; actor: string; rationale: string }>;
}

interface VendorDetailProps {
 vendorId?: string;
 onBack?: () => void;
}

export function VendorDetailWorkspace({ vendorId = 'vend-001', onBack }: VendorDetailProps) {
 const [isActionsOpen, setIsActionsOpen] = useState(false);
 const [isOffboardOpen, setIsOffboardOpen] = useState(false);
 const [offboardChecklist, setOffboardChecklist] = useState({ accessRevoked: false, dataDestroyed: false, contractsClosed: false });
 const [isRemediationOpen, setIsRemediationOpen] = useState(false);
 const [isDecisionOpen, setIsDecisionOpen] = useState(false);
 const [remediationForm, setRemediationForm] = useState({ title: '', issue: '', severity: 'High' as RemediationSeverity, owner: '' });
 const [decisionForm, setDecisionForm] = useState({ title: '', type: 'Approve' as DecisionType, rationale: '' });
 // Bumped after creating a remediation/decision so the store-derived sections re-read.
 const [refreshTick, setRefreshTick] = useState(0);
 const [hasNetworkError, setHasNetworkError] = useState(false);
 const [showPermissionModal, setShowPermissionModal] = useState(false);
 const [showSessionExpired, setShowSessionExpired] = useState(false);

 useEffect(() => {
 const handleKeyDown = (e: KeyboardEvent) => {
 if (e.key === 'Escape') {
 setIsActionsOpen(false);
 setIsOffboardOpen(false);
 setIsRemediationOpen(false);
 setIsDecisionOpen(false);
 setHasNetworkError(false);
 setShowPermissionModal(false);
 setShowSessionExpired(false);
 }
 };
 document.addEventListener('keydown', handleKeyDown);
 return () => document.removeEventListener('keydown', handleKeyDown);
 }, []);

 // Source of truth: shared vendor registry (matches the Vendors table).
 const sharedVendor = getVendorsWithStatus().find(v => v.id === vendorId) || getVendorsWithStatus()[0];
 const resolvedId = sharedVendor?.id ?? vendorId;
 const resolvedName = sharedVendor?.name ?? 'Unknown Vendor';

 // Evidence / remediation / decisions come from the live stores (demo seeds +
 // anything the user created), so this page can never disagree with the tabs.
 // Re-read when refreshTick bumps so newly created records appear immediately.
 const storeEvidence = useMemo(
 () => getEvidenceForVendor(resolvedId, resolvedName),
 [resolvedId, resolvedName, refreshTick]
 );
 const storeRemediations = useMemo(
 () => getRemediationsForVendor(resolvedId),
 [resolvedId, refreshTick]
 );
 const storeDecisions = useMemo(
 () => getDecisionsForVendor(resolvedId),
 [resolvedId, refreshTick]
 );

 // Vendors that ship with authored demo narratives keep them; everyone else
 // (user-added vendors) gets an honest model built from their OWN record —
 // never another vendor's story.
 const useHonestModels = !hasAuthoredDetail(resolvedId);
 const honestExposure = useMemo(
 () => (useHonestModels && sharedVendor ? buildExposureModel(sharedVendor) : undefined),
 [useHonestModels, sharedVendor]
 );
 const honestBusinessImpact = useMemo(
 () => (useHonestModels && sharedVendor ? buildBusinessImpactModel(sharedVendor) : undefined),
 [useHonestModels, sharedVendor]
 );
 const honestRiskDimensions = useMemo(
 () => (useHonestModels && sharedVendor ? buildRiskDimensionsModel(sharedVendor) : undefined),
 [useHonestModels, sharedVendor]
 );

 const vendor: VendorDetailViewModel = {
  ...(sharedVendor as VendorRecord),
  name: resolvedName,
  code: sharedVendor?.code ?? '—',
  criticality: sharedVendor?.criticality ?? 'Tier 2',
  monitoringStatus: sharedVendor?.monitoringStatus ?? '—',
  lastMaterialChange: sharedVendor?.lastMaterialChange ?? '—',
  category: sharedVendor?.category ?? '—',
  domain: sharedVendor?.domain ?? sharedVendor?.website ?? '',
  dependentSystems: sharedVendor?.dependentSystems ?? [],
  evidence: storeEvidence.map(ev => ({ name: ev.artifactName, status: ev.status, date: ev.expirationDate, confidence: ev.confidence })),
  remediation: storeRemediations.map(r => ({ id: r.id, title: r.title, owner: r.owner, aging: `${r.agingDays}d`, severity: r.severity })),
  decisions: storeDecisions.map(d => ({ date: d.timestamp, action: d.decisionTitle, actor: d.decisionMaker, rationale: d.decisionRationale })),
 };

 const isTerminated = vendor.status === 'Terminated';

 const handleCreateRemediation = () => {
 if (!remediationForm.title.trim()) return;
 const rec = addRemediation({
 title: remediationForm.title.trim(),
 issue: remediationForm.issue.trim() || remediationForm.title.trim(),
 vendorId: resolvedId,
 vendorName: resolvedName,
 owner: remediationForm.owner.trim() || 'Unassigned',
 severity: remediationForm.severity,
 });
 auditStore.appendRecord('Current User', 'REMEDIATION_CREATED', rec.id, `Created remediation "${rec.title}" for ${resolvedName}`);
 recordMaterialChange({
 objectName: resolvedName,
 objectType: 'Remediation',
 eventType: 'remediation_status',
 changeSummary: `New remediation opened: ${rec.title}`,
 severity: rec.severity === 'Critical' ? 'critical' : rec.severity === 'High' ? 'high' : rec.severity === 'Moderate' ? 'moderate' : 'low',
 contextReason: `Created from the vendor profile. Severity: ${rec.severity}. Owner: ${rec.owner}.`,
 actionLabel: 'View Remediation',
 });
 showToast(`Remediation created for ${resolvedName}`, 'success');
 setRemediationForm({ title: '', issue: '', severity: 'High', owner: '' });
 setIsRemediationOpen(false);
 setRefreshTick(t => t + 1);
 };

 const handleCreateDecision = () => {
 if (!decisionForm.title.trim()) return;
 const rec = addDecision({
 vendorId: resolvedId,
 vendorName: resolvedName,
 decisionTitle: decisionForm.title.trim(),
 decisionType: decisionForm.type,
 decisionRationale: decisionForm.rationale.trim() || 'Recorded from the vendor profile.',
 });
 auditStore.appendRecord('Current User', 'DECISION_RECORDED', rec.id, `Recorded decision "${rec.decisionTitle}" for ${resolvedName}`);
 recordMaterialChange({
 objectName: resolvedName,
 objectType: 'Decision',
 eventType: 'risk_change',
 changeSummary: `Governance decision recorded: ${rec.decisionTitle}`,
 severity: 'info',
 contextReason: `Decision type: ${rec.decisionType}. ${rec.decisionRationale}`,
 actionLabel: 'View Decisions',
 });
 showToast(`Decision recorded for ${resolvedName}`, 'success');
 setDecisionForm({ title: '', type: 'Approve', rationale: '' });
 setIsDecisionOpen(false);
 setRefreshTick(t => t + 1);
 };

 return (
 <div className="space-y-8 max-w-7xl mx-auto pb-28 animate-in fade-in duration-200">
 
 <div aria-live="polite" className="sr-only">
 Vendor detail workspace loaded for {vendor.name} ({vendor.code}).
 </div>

 {hasNetworkError && <NetworkErrorBanner onRetry={() => setHasNetworkError(false)} />}
 {showPermissionModal && <PermissionErrorModal onClose={() => setShowPermissionModal(false)} />}
 {showSessionExpired && <ExpiredSessionModal onReauth={() => setShowSessionExpired(false)} />}

 <div className="flex items-center justify-between border-b border-border pb-4">
 <button 
 type="button"
 onClick={onBack}
 className="text-xs font-semibold text-text-tertiary hover:text-text-primary transition-colors flex items-center gap-1.5 focus:outline-none focus:ring-2 focus:ring-info rounded"
 >
 <span>← Back to Vendor Portfolio</span>
 </button>
 <div className="flex items-center gap-4 text-xs font-mono text-text-tertiary">
 Last Material Change: <strong className="text-text-primary ml-1">{vendor.lastMaterialChange}</strong>
 </div>
 </div>

 <Card className="shadow-sm overflow-visible">
 <div className="bg-surface p-6 sm:p-7 flex flex-col lg:flex-row lg:items-center justify-between gap-6 border-b border-border rounded-xl">
 <div className="flex items-start gap-4 min-w-0">
 <div aria-hidden="true" className="w-12 h-12 rounded-lg bg-base border border-border-strong flex items-center justify-center text-text-primary shadow-sm shrink-0 mt-0.5">
 <Building2 size={24} strokeWidth={1.5} />
 </div>
 <div className="min-w-0">
 <div className="flex flex-wrap items-center gap-2 mb-1">
 <h1 className="text-lg sm:text-xl font-bold text-text-primary tracking-tight truncate m-0">{vendor.name}</h1>
 <span className="text-xs font-mono text-text-tertiary bg-base px-2 py-0.5 rounded border border-border">{vendor.code}</span>
 <span className="text-[11px] font-bold uppercase tracking-wider px-2 py-0.5 rounded bg-risk-critical/10 text-risk-critical ">{vendor.criticality}</span>
 {isTerminated && (
 <span className="text-[11px] font-bold uppercase tracking-wider px-2 py-0.5 rounded bg-base text-text-tertiary border border-border-strong">Terminated</span>
 )}
 </div>
 <p className="text-xs text-text-secondary flex flex-wrap items-center gap-2 m-0">
 <span className="font-medium">{vendor.category}</span>
 <span aria-hidden="true" className="text-text-tertiary">•</span>
 <span className="font-mono">{vendor.domain}</span>
 <span aria-hidden="true" className="text-text-tertiary">•</span>
 <span className="text-status-success font-semibold">{vendor.monitoringStatus}</span>
 </p>
 {vendor.inherentRisk !== undefined && (
 <div className="mt-3 flex items-center gap-4 text-xs" aria-label={`Inherent risk ${vendor.inherentRisk}, residual risk ${vendor.residualRisk}`}>
 <div className="flex items-center gap-1.5">
 <span className="text-text-tertiary font-medium">Inherent:</span>
 <span className="font-bold text-text-primary tabular-nums">{vendor.inherentRisk}</span>
 <span className="text-text-tertiary">→</span>
 <span className="text-text-tertiary font-medium">Residual:</span>
 <span className="font-bold text-info tabular-nums">{vendor.residualRisk}</span>
 </div>
 <div className="flex-1 max-w-[200px] h-1.5 rounded-full bg-base border border-border overflow-hidden" aria-hidden="true">
 <div className="h-full rounded-full bg-gradient-to-r from-risk-critical to-status-warning" style={{ width: `${vendor.inherentRisk}%` }} />
 </div>
 <span className="text-text-tertiary" title="Inherent risk is the exposure before controls; residual is what remains after your mitigations.">ⓘ</span>
 </div>
 )}
 {vendor.contractEnd && (
 <div className="mt-2 flex items-center gap-2 text-xs">
 <span className="text-text-tertiary font-medium">Contract:</span>
 <span className="font-mono text-text-secondary">{vendor.contractStart} → {vendor.contractEnd}</span>
 {(() => {
 const contractEnd = vendor.contractEnd;
 if (!contractEnd) return null;
 const daysLeft = Math.ceil((new Date(contractEnd).getTime() - Date.now()) / (1000 * 60 * 60 * 24));
 if (daysLeft < 0) return <span className="font-bold text-risk-critical uppercase text-[10px] tracking-wider">Expired</span>;
 if (daysLeft < 90) return <span className="font-bold text-status-warning uppercase text-[10px] tracking-wider">Renews in {daysLeft}d</span>;
 return null;
 })()}
 </div>
 )}
 </div>
 </div>

 <div className="relative flex items-center gap-2 shrink-0">
 <Button variant="primary" size="sm" disabled={isTerminated} title={isTerminated ? 'Vendor is terminated — assessments are disabled' : undefined} onClick={() => navigateTo('assessments', { vendorName: vendor.name, launchAssessment: 'true', from: 'Vendor Profile' })} aria-label={`Start assessment for ${vendor.name}`}>
 Start Assessment
 </Button>
 <div className="relative">
 <Button variant="secondary" size="sm" onClick={() => setIsActionsOpen(!isActionsOpen)} className="gap-1.5">
 <span>Actions</span>
 <ChevronDown size={14} strokeWidth={1.5} />
 </Button>
 {isActionsOpen && (
 <div role="menu" className="absolute right-0 mt-2 w-52 bg-surface border border-border-strong rounded-xl shadow-xl z-50 p-1.5 space-y-0.5 animate-in fade-in duration-150">
 <button role="menuitem" disabled={isTerminated} onClick={() => { setIsActionsOpen(false); navigateTo('evidence', { vendorName: vendor.name, openUpload: 'true', from: 'Vendor Profile' }); }} className="w-full text-left px-3 py-2 text-xs font-medium text-text-secondary hover:text-text-primary hover:bg-surface-hover rounded-lg flex items-center gap-2 disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-text-secondary">
 <Upload size={14} strokeWidth={1.5} /> Upload Evidence
 </button>
 <button role="menuitem" disabled={isTerminated} onClick={() => { setIsActionsOpen(false); setIsRemediationOpen(true); }} className="w-full text-left px-3 py-2 text-xs font-medium text-text-secondary hover:text-text-primary hover:bg-surface-hover rounded-lg flex items-center gap-2 disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-text-secondary">
 <Wrench size={14} strokeWidth={1.5} /> Create Remediation
 </button>
 <button role="menuitem" disabled={isTerminated} onClick={() => { setIsActionsOpen(false); setIsDecisionOpen(true); }} className="w-full text-left px-3 py-2 text-xs font-medium text-text-secondary hover:text-text-primary hover:bg-surface-hover rounded-lg flex items-center gap-2 disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-text-secondary">
 <Gavel size={14} strokeWidth={1.5} /> Create Decision
 </button>
 <div className="h-px bg-border-subtle my-1" />
 <button role="menuitem" onClick={() => { setIsActionsOpen(false); if (vendor.domain) window.open(`https://${vendor.domain}`, '_blank', 'noopener'); }} className="w-full text-left px-3 py-2 text-xs font-medium text-text-secondary hover:text-text-primary hover:bg-surface-hover rounded-lg flex items-center gap-2">
 <ExternalLink size={14} strokeWidth={1.5} /> Open Vendor Portal
 </button>
 {!isTerminated && (
 <>
 <div className="h-px bg-border-subtle my-1" />
 <button role="menuitem" onClick={() => { setIsActionsOpen(false); setIsOffboardOpen(true); setOffboardChecklist({ accessRevoked: false, dataDestroyed: false, contractsClosed: false }); }} className="w-full text-left px-3 py-2 text-xs font-medium text-risk-critical hover:bg-risk-critical/10 rounded-lg flex items-center gap-2">
 <UserX size={14} strokeWidth={1.5} /> Offboard Vendor
 </button>
 </>
 )}
 </div>
 )}
 </div>
 </div>
 </div>

 {/* Narrative sections: authored vendors keep their demo story; everyone else
 gets an honest model built from their OWN record — never borrowed fiction. */}
 <div className="p-6 bg-surface-hover/30 border-b border-border">
 <WhyThisExposure model={honestExposure} vendorName={vendor.name} />
 </div>
 </Card>

 <BusinessImpactSection model={honestBusinessImpact} />
 <RiskDimensionsSection model={honestRiskDimensions} />

 {/* Grid: Dependent Systems & Evidence */}
 <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 items-start">
 <Card className="flex flex-col h-full shadow-sm">
 <CardHeader className="border-b border-border px-6 py-4">
 <CardTitle className="flex items-center gap-2 text-sm font-bold uppercase tracking-wider">
 <Server size={18} strokeWidth={1.5} className="text-text-secondary" />
 Dependent Systems & Data Scope
 </CardTitle>
 </CardHeader>
 <CardContent className="p-6 space-y-4">
 {vendor.dependentSystems.map((sys, idx) => (
 <button type="button" key={idx} onClick={() => navigateTo('fourth-parties', { vendorName: vendor.name })} className="w-full text-left p-3.5 rounded-lg border border-border bg-surface space-y-1 hover:border-info hover:shadow-sm transition-all focus:outline-none focus:ring-2 focus:ring-info cursor-pointer">
 <div className="flex items-center justify-between">
 <span className="text-sm font-bold text-text-primary">{sys.name}</span>
 <span className="text-[11px] font-mono font-semibold text-info bg-info/5 px-2 py-0.5 rounded border border-info/20">{sys.residency}</span>
 </div>
 <p className="text-xs text-text-secondary flex items-center gap-1.5 pt-0.5 m-0">
 <Lock size={12} strokeWidth={1.5} className="text-text-tertiary" />
 <span>Data Classification: <strong className="text-text-primary font-medium">{sys.dataHandled}</strong></span>
 </p>
 </button>
 ))}
 {vendor.dependentSystems.length === 0 && (
 <p className="text-xs text-text-tertiary text-center py-4 m-0">No dependent systems recorded for this vendor yet.</p>
 )}
 </CardContent>
 </Card>

 <Card className="flex flex-col h-full shadow-sm">
 <CardHeader className="border-b border-border px-6 py-4">
 <CardTitle className="flex items-center gap-2 text-sm font-bold uppercase tracking-wider">
 <FileCheck size={18} strokeWidth={1.5} className="text-text-secondary" />
 Evidence & Artifact Verification
 </CardTitle>
 </CardHeader>
 <CardContent className="p-6 space-y-3">
 {vendor.evidence.map((ev, idx) => (
 <button type="button" key={idx} onClick={() => navigateTo('evidence', { vendorName: vendor.name })} className="w-full text-left flex items-center justify-between p-3.5 rounded-lg border border-border bg-surface hover:border-info hover:shadow-sm transition-all focus:outline-none focus:ring-2 focus:ring-info cursor-pointer">
 <div>
 <h4 className="text-sm font-bold text-text-primary m-0">{ev.name}</h4>
 <p className="text-xs text-text-tertiary mt-0.5 m-0">{ev.date}</p>
 </div>
 <span className={`text-[10px] font-semibold uppercase px-2.5 py-1 rounded border ${ev.status === 'Expired' ? 'bg-risk-critical/10 text-risk-critical ' : 'bg-status-success/10 text-status-success '}`}>
 {ev.status}
 </span>
 </button>
 ))}
 {vendor.evidence.length === 0 && (
 <p className="text-xs text-text-tertiary text-center py-4 m-0">No evidence artifacts recorded for this vendor yet.</p>
 )}
 </CardContent>
 </Card>
 </div>

 {/* Grid: Remediation & Decisions */}
 <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 items-start">
 <Card className="flex flex-col h-full shadow-sm">
 <CardHeader className="border-b border-border px-6 py-4">
 <CardTitle className="flex items-center gap-2 text-sm font-bold uppercase tracking-wider">
 <ShieldAlert size={18} strokeWidth={1.5} className="text-text-secondary" />
 Active Remediation Queue
 </CardTitle>
 </CardHeader>
 <CardContent className="p-6 space-y-3">
 {vendor.remediation.map((rem, idx) => (
 <button type="button" key={idx} onClick={() => navigateTo('remediation', { vendorName: vendor.name })} className="w-full text-left p-3.5 rounded-lg border border-border bg-surface space-y-1.5 hover:border-info hover:shadow-sm transition-all focus:outline-none focus:ring-2 focus:ring-info cursor-pointer">
 <div className="flex items-start justify-between gap-2">
 <span className="text-sm font-bold text-text-primary">{rem.title}</span>
 <span className="text-[10px] font-semibold uppercase px-2 py-0.5 rounded border bg-risk-critical/10 text-risk-critical shrink-0">
 {rem.aging}
 </span>
 </div>
 <p className="text-xs text-text-secondary m-0">Owner: <strong className="text-text-primary font-medium">{rem.owner}</strong></p>
 </button>
 ))}
 {vendor.remediation.length === 0 && (
 <p className="text-xs text-text-tertiary text-center py-4 m-0">No open remediation items for this vendor.</p>
 )}
 </CardContent>
 </Card>

 <Card className="flex flex-col h-full shadow-sm">
 <CardHeader className="border-b border-border px-6 py-4">
 <CardTitle className="flex items-center gap-2 text-sm font-bold uppercase tracking-wider">
 <Gavel size={18} strokeWidth={1.5} className="text-text-secondary" />
 Governance Decision History
 </CardTitle>
 </CardHeader>
 <CardContent className="p-6 space-y-3">
 {vendor.decisions.map((dec, idx) => (
 <button type="button" key={idx} onClick={() => navigateTo('decisions', { vendorName: vendor.name })} className="w-full text-left p-3.5 rounded-lg border border-border bg-surface space-y-1.5 hover:border-info hover:shadow-sm transition-all focus:outline-none focus:ring-2 focus:ring-info cursor-pointer">
 <div className="flex items-center justify-between">
 <span className="text-xs font-bold text-text-primary">{dec.action}</span>
 <span className="text-[11px] font-mono text-text-tertiary">{dec.date}</span>
 </div>
 <p className="text-xs text-text-secondary m-0">Actor: <strong className="text-text-primary font-medium">{dec.actor}</strong></p>
 <p className="text-xs text-text-secondary italic m-0">"{dec.rationale}"</p>
 </button>
 ))}
 {vendor.decisions.length === 0 && (
 <p className="text-xs text-text-tertiary text-center py-4 m-0">No governance decisions recorded for this vendor yet.</p>
 )}
 </CardContent>
 </Card>
 </div>
  <Dialog open={isOffboardOpen} onClose={() => setIsOffboardOpen(false)} title={`Offboard ${vendor.name}`} maxWidth="max-w-md">
 <div className="p-6 space-y-4">
 <div className="flex items-center gap-3">
 <div className="w-10 h-10 rounded-xl bg-risk-critical/10 border border-risk-critical/20 flex items-center justify-center text-risk-critical">
 <UserX size={20} />
 </div>
 <div>
 <h2 className="text-base font-bold text-text-primary m-0">Offboard {vendor.name}?</h2>
 <p className="text-xs text-text-secondary mt-0.5 m-0">This terminates the vendor relationship. Complete all steps.</p>
 </div>
 </div>
 <div className="space-y-2.5">
 {[
 { key: 'accessRevoked', label: 'Revoke all system access', desc: 'Disable API keys, SSO, and integrations' },
 { key: 'dataDestroyed', label: 'Confirm data destruction', desc: 'Vendor confirms deletion of your data per DPA' },
 { key: 'contractsClosed', label: 'Close contracts & billing', desc: 'Terminate agreements and settle final invoices' },
 ].map((item) => (
 <label key={item.key} className="flex items-start gap-3 p-3 rounded-xl border border-border bg-base cursor-pointer hover:border-border-strong transition-colors">
 <input
 type="checkbox"
 checked={offboardChecklist[item.key as keyof typeof offboardChecklist]}
 onChange={(e) => setOffboardChecklist({ ...offboardChecklist, [item.key]: e.target.checked })}
 className="mt-0.5 w-4 h-4 rounded accent-risk-critical"
 />
 <span>
 <span className="block text-sm font-semibold text-text-primary">{item.label}</span>
 <span className="block text-xs text-text-tertiary mt-0.5">{item.desc}</span>
 </span>
 </label>
 ))}
 </div>
 <div className="flex items-center justify-end gap-2 pt-1">
 <Button variant="secondary" size="sm" onClick={() => setIsOffboardOpen(false)}>Cancel</Button>
 <Button
 variant="primary"
 size="sm"
 disabled={!offboardChecklist.accessRevoked || !offboardChecklist.dataDestroyed || !offboardChecklist.contractsClosed}
 onClick={() => {
 if (offboardVendor(vendor.id, offboardChecklist)) {
 auditStore.appendRecord('Current User', 'VENDOR_OFFBOARDED', vendor.name, 'Offboarding checklist completed: access revoked, data destroyed, contracts closed');
 recordMaterialChange({
 objectName: vendor.name,
 objectType: 'Vendor',
 eventType: 'risk_change',
 changeSummary: `${vendor.name} was offboarded and marked Terminated`,
 severity: 'high',
 contextReason: 'Offboarding checklist completed: access revoked, data destroyed, contracts closed.',
 actionLabel: 'View Vendors',
 });
 showToast(`${vendor.name} offboarded`, 'success');
 setIsOffboardOpen(false);
 navigateTo('vendors');
 }
 }}
 className="bg-risk-critical hover:bg-risk-critical/90 border-risk-critical"
 >
 Confirm Offboarding
 </Button>
 </div>
 </div>
  </Dialog>

  <Dialog open={isRemediationOpen} onClose={() => setIsRemediationOpen(false)} title={`Create Remediation — ${vendor.name}`} maxWidth="max-w-md">
 <div className="p-6 space-y-4">
 <div>
 <h2 className="text-base font-bold text-text-primary m-0">Create Remediation</h2>
 <p className="text-xs text-text-secondary mt-1 m-0">Open a corrective action item for {vendor.name}. It appears here and in the Remediation tab immediately.</p>
 </div>
 <div>
 <label htmlFor="rem-title" className="block text-xs font-semibold text-text-secondary mb-1.5">Title</label>
 <input
 id="rem-title"
 type="text"
 value={remediationForm.title}
 onChange={(e) => setRemediationForm({ ...remediationForm, title: e.target.value })}
 placeholder="e.g. Renew expired SOC 2 attestation"
 className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-info"
 />
 </div>
 <div>
 <label htmlFor="rem-issue" className="block text-xs font-semibold text-text-secondary mb-1.5">Description</label>
 <textarea
 id="rem-issue"
 value={remediationForm.issue}
 onChange={(e) => setRemediationForm({ ...remediationForm, issue: e.target.value })}
 placeholder="What needs to be fixed?"
 rows={3}
 className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-info resize-y"
 />
 </div>
 <div className="grid grid-cols-2 gap-3">
 <div>
 <label htmlFor="rem-severity" className="block text-xs font-semibold text-text-secondary mb-1.5">Severity</label>
 <select
 id="rem-severity"
 value={remediationForm.severity}
 onChange={(e) => setRemediationForm({ ...remediationForm, severity: e.target.value as RemediationSeverity })}
 className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:ring-2 focus:ring-info"
 >
 <option value="Critical">Critical</option>
 <option value="High">High</option>
 <option value="Moderate">Moderate</option>
 <option value="Low">Low</option>
 </select>
 </div>
 <div>
 <label htmlFor="rem-owner" className="block text-xs font-semibold text-text-secondary mb-1.5">Owner</label>
 <input
 id="rem-owner"
 type="text"
 value={remediationForm.owner}
 onChange={(e) => setRemediationForm({ ...remediationForm, owner: e.target.value })}
 placeholder="Unassigned"
 className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-info"
 />
 </div>
 </div>
 <div className="flex items-center justify-end gap-2 pt-1">
 <Button variant="secondary" size="sm" onClick={() => setIsRemediationOpen(false)}>Cancel</Button>
 <Button variant="primary" size="sm" disabled={!remediationForm.title.trim()} onClick={handleCreateRemediation}>Create Remediation</Button>
 </div>
 </div>
  </Dialog>

  <Dialog open={isDecisionOpen} onClose={() => setIsDecisionOpen(false)} title={`Create Decision — ${vendor.name}`} maxWidth="max-w-md">
 <div className="p-6 space-y-4">
 <div>
 <h2 className="text-base font-bold text-text-primary m-0">Create Decision</h2>
 <p className="text-xs text-text-secondary mt-1 m-0">Record a governance decision for {vendor.name}. It appears here and in the Decisions ledger immediately.</p>
 </div>
 <div>
 <label htmlFor="dec-title" className="block text-xs font-semibold text-text-secondary mb-1.5">Decision title</label>
 <input
 id="dec-title"
 type="text"
 value={decisionForm.title}
 onChange={(e) => setDecisionForm({ ...decisionForm, title: e.target.value })}
 placeholder="e.g. Approve conditional use pending evidence renewal"
 className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-info"
 />
 </div>
 <div>
 <label htmlFor="dec-type" className="block text-xs font-semibold text-text-secondary mb-1.5">Decision type</label>
 <select
 id="dec-type"
 value={decisionForm.type}
 onChange={(e) => setDecisionForm({ ...decisionForm, type: e.target.value as DecisionType })}
 className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:ring-2 focus:ring-info"
 >
 <option value="Approve">Approve</option>
 <option value="Approve with conditions">Approve with conditions</option>
 <option value="Reject">Reject</option>
 <option value="Accept risk">Accept risk</option>
 <option value="Request remediation">Request remediation</option>
 <option value="Reassess">Reassess</option>
 </select>
 </div>
 <div>
 <label htmlFor="dec-rationale" className="block text-xs font-semibold text-text-secondary mb-1.5">Rationale</label>
 <textarea
 id="dec-rationale"
 value={decisionForm.rationale}
 onChange={(e) => setDecisionForm({ ...decisionForm, rationale: e.target.value })}
 placeholder="Why is this the right call? This becomes the audit trail."
 rows={3}
 className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-info resize-y"
 />
 </div>
 <div className="flex items-center justify-end gap-2 pt-1">
 <Button variant="secondary" size="sm" onClick={() => setIsDecisionOpen(false)}>Cancel</Button>
 <Button variant="primary" size="sm" disabled={!decisionForm.title.trim()} onClick={handleCreateDecision}>Record Decision</Button>
 </div>
 </div>
  </Dialog>
</div>
 



);
}