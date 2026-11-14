// src/features/ai/AIPlaygroundWorkspace.tsx
import { useState, useEffect } from 'react';
import { getNavParams, clearNavParams } from '../../shared/navigation';
import { DeepLinkBanner } from '../../shared/ui/DeepLinkBanner';
import { Sparkles } from 'lucide-react';
import { Card, Eyebrow} from '../../shared/ui/core';
import { AIContextualMenu } from '../../shared/ui/ai/AIContextualMenu';
import { AIResponsePanel, AIResponseData } from '../../shared/ui/ai/AIResponsePanel';
import { VendorBriefPanel } from './VendorBriefPanel';
import { NetworkErrorBanner, PermissionErrorModal, ExpiredSessionModal } from '../../shared/ui/ErrorStates';
import { navigateTo } from '../../shared/navigation';

export function AIPlaygroundWorkspace() {
 const [activeResponse, setActiveResponse] = useState<AIResponseData | null>(null);
 const [deepLink, setDeepLink] = useState<string | null>(null);
 const [briefVendorId, setBriefVendorId] = useState<string | undefined>(undefined);
 useEffect(() => {
 const params = getNavParams();
 if (params.vendorName && typeof params.vendorName === 'string') {
 setDeepLink(params.vendorName as string);
 clearNavParams();
 }
 if (params.vendorId && typeof params.vendorId === 'string') {
 setBriefVendorId(params.vendorId as string);
 }
 }, []);
 const [isProcessing, setIsProcessing] = useState(false);

 // Error state triggers
 const [hasNetworkError, setHasNetworkError] = useState(false);
 const [showPermissionModal, setShowPermissionModal] = useState(false);
 const [showSessionExpired, setShowSessionExpired] = useState(false);

 const handleRunAIAction = (actionId: string, actionLabel: string) => {
 setIsProcessing(true);
 setActiveResponse(null);
 
 setTimeout(() => {
 let mockResponse: AIResponseData;

 if (actionId === 'v-exposure') {
 mockResponse = {
 id: 'resp-01',
 title: actionLabel,
 conciseAnswer: "CloudScale Infrastructure processes Tier 1 PII and hosts production workloads. Their SOC 2 Type II attestation expires in 14 days, creating an unverified security posture gap.",
 confidence: 'High',
 assumptions: [
 'Production workloads remain actively hosted on CloudScale infrastructure.',
 'No unrecorded annual renewal document has been submitted.'
 ],
 unknowns: [
 'Exact architectural failover redundancy details for secondary regions.',
 'Whether subprocessor list includes unvetted offshore entities.'
 ],
 sources: [
 { id: 's1', documentName: 'Vendor Inherent Risk Profile', citation: 'Data Class: Tier 1 PII' },
 { id: 's2', documentName: 'CloudScale_SOC2_2025.pdf', citation: 'Validity Period: Expires Oct 20, 2026' }
 ],
 recommendedAction: 'Request immediate SOC 2 extension or trigger emergency vendor assessment.'
 };
 } else if (actionId === 'e-missing' || actionId === 'v-remediation') {
 mockResponse = {
 id: 'resp-02',
 title: actionLabel,
 isInsufficientEvidence: true,
 conciseAnswer: "The repository contains infrastructure penetration test results, but mobile endpoint scope is entirely missing. AI cannot verify mobile application security posture without targeted artifact ingestion.",
 confidence: 'Insufficient',
 assumptions: [],
 unknowns: [
 'Whether mobile endpoints share authentication logic with core infrastructure.',
 'Results of vulnerability scans for Android/iOS application packages.'
 ],
 sources: [],
 recommendedAction: 'Issue formal evidence request to vendor for mobile-specific penetration test.'
 };
 } else {
 mockResponse = {
 id: 'resp-03',
 title: actionLabel,
 conciseAnswer: "Analysis successfully verified against available questionnaire logs and control mappings.",
 confidence: 'Medium',
 assumptions: ['Vendor self-attestation is accurate pending annual audit.'],
 unknowns: ['Real-time telemetry log streaming continuity.'],
 sources: [
 { id: 's1', documentName: 'SIG_Lite_Response.xlsx', citation: 'Row 42: Controls' }
 ],
 recommendedAction: 'Proceed with standard review workflow.'
 };
 }

 setActiveResponse(mockResponse);
 setIsProcessing(false);
 }, 600);
 };

 return (
 <div className="space-y-6 max-w-5xl mx-auto pb-28 animate-in fade-in duration-300">
 
 {/* Live Region for Announcing AI Processing or Errors */}
 <div aria-live="polite" className="sr-only">
 {isProcessing ? "Evaluating evidence and constraints. Checking epistemic boundaries and citations." : activeResponse ? "AI Response rendered successfully." : ""}
 </div>

 {hasNetworkError && <NetworkErrorBanner onRetry={() => setHasNetworkError(false)} />}
 {showPermissionModal && <PermissionErrorModal onClose={() => setShowPermissionModal(false)} />}
 {showSessionExpired && <ExpiredSessionModal onReauth={() => setShowSessionExpired(false)} />}

 <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-4 border-b border-border pb-6">
 <div>
 <Eyebrow>Intelligence</Eyebrow>
 <h1 className="text-page-title flex items-center gap-3">Defensible AI Workbench & Response Panel
 <span className="text-[10px] font-semibold uppercase tracking-wider px-2 py-0.5 rounded bg-status-warning/10 text-status-warning border border-status-warning/20">Simulated</span>
 </h1>
 <p className="text-xs text-text-tertiary mt-1">The three panels below are pre-scripted demo examples, not live AI analysis. The Vendor Intelligence Brief at the bottom runs on the real AgentEngine.</p>
 {deepLink && (
 <div className="mt-3">
 <DeepLinkBanner
 label={`Vendor context = "${deepLink}"`}
 source="Vendors"
 onClear={() => setDeepLink(null)}
 />
 </div>
 )}
 <p className="text-body-secondary mt-1">
 Test specific AI tasks. Notice how the response panel explicitly highlights assumptions, unknowns, and features an Insufficient Evidence fallback state.
 </p>
 </div>
 <div className="flex flex-wrap items-center gap-2">
 </div>
 </div>

 <div className="grid grid-cols-1 md:grid-cols-2 gap-6 items-start">
 
 <div className="space-y-4">
 <Card className="shadow-sm relative z-30" style={{ overflow: 'visible' }}>
 <div className="p-5 flex items-center justify-between">
 <div>
 <h2 className="text-sm font-bold text-text-primary m-0">Vendor Exposure Analysis</h2>
 <p className="text-xs text-text-secondary mt-0.5">Test normal high-confidence extraction</p>
 </div>
 <AIContextualMenu context="vendor" onSelectAction={handleRunAIAction} isLoading={isProcessing} />
 </div>
 </Card>

 <Card className="shadow-sm relative z-20" style={{ overflow: 'visible' }}>
 <div className="p-5 flex items-center justify-between">
 <div>
 <h2 className="text-sm font-bold text-text-primary m-0">Evidence Gaps Analysis</h2>
 <p className="text-xs text-text-secondary mt-0.5">Test "Insufficient Evidence" fallback state</p>
 </div>
 <AIContextualMenu context="evidence" onSelectAction={handleRunAIAction} isLoading={isProcessing} />
 </div>
 </Card>

 <Card className="shadow-sm relative z-10" style={{ overflow: 'visible' }}>
 <div className="p-5 flex items-center justify-between">
 <div>
 <h2 className="text-sm font-bold text-text-primary m-0">Assessment Clarifications</h2>
 <p className="text-xs text-text-secondary mt-0.5">Test medium-confidence review workflow</p>
 </div>
 <AIContextualMenu context="assessment" onSelectAction={handleRunAIAction} isLoading={isProcessing} />
 </div>
 </Card>
 </div>

 <div className="space-y-4">
 {activeResponse ? (
 <AIResponsePanel 
 response={activeResponse}
 onApprove={() => { const v = deepLink; setActiveResponse(null); navigateTo('remediation', v ? { vendorName: v } : undefined); }}
 onReject={() => { setActiveResponse(null); }}
 onModify={() => { /* inline edit mode is handled inside AIResponsePanel */ }}
 onFeedback={() => { /* feedback recorded */ }}
 />
 ) : (
 <div role="region" aria-label="AI Workbench Output Area" className="h-full min-h-[350px] border-2 border-dashed border-border rounded-xl bg-surface flex flex-col items-center justify-center text-text-tertiary p-6 text-center">
 {isProcessing ? (
 <>
 <div aria-hidden="true" className="w-8 h-8 border-4 border-accent/25 border-t-indigo-600 rounded-full animate-spin mb-4"></div>
 <p className="text-sm font-bold text-text-primary">Evaluating evidence and constraints...</p>
 <p className="text-xs mt-1">Checking epistemic boundaries and citations.</p>
 </>
 ) : (
 <>
 <Sparkles aria-hidden="true" size={24} className="mb-3 opacity-50" />
 <p className="text-sm font-medium text-text-secondary">Awaiting AI Task Trigger</p>
 <p className="text-xs mt-1">Click an action on the left to render the AI Response Panel.</p>
 </>
 )}
 </div>
 )}
 </div>

 </div>

 <VendorBriefPanel initialVendorId={briefVendorId} />
 </div>
 );
}