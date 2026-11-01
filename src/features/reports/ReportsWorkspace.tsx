// src/features/reports/ReportsWorkspace.tsx
import { useState, useEffect } from 'react';
import { 
 FileText, Download, Printer, Shield, Users, 
 CheckCircle2, ArrowRight, Sparkles, Filter, Sliders, Gavel, BarChart2 
} from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent, Button, EmptyState, Eyebrow} from '../../shared/ui/core';
import { NetworkErrorBanner, PermissionErrorModal, ExpiredSessionModal } from '../../shared/ui/ErrorStates';
import { getVendors } from '../../data/vendors';
import { portfolioHealthScore } from '../command-center/PortfolioPulse';
import { DEV_ACTIVE_REMEDIATIONS } from '../command-center/RemediationHealthCard';

export type ReportCategory = 
 | 'Executive / Board' 
 | 'CISO' 
 | 'TPRM Program' 
 | 'Vendor Risk' 
 | 'Assessment' 
 | 'Remediation' 
 | 'Evidence' 
 | 'Compliance' 
 | 'Audit';

export interface ReportTemplate {
 id: ReportCategory;
 title: string;
 audience: string;
 coreQuestion: string;
 description: string;
 defaultScope: string;
 keyMetrics: string[];
}

const REPORT_TEMPLATES: ReportTemplate[] = [
 {
 id: 'Executive / Board',
 title: 'Board-Level Risk & Fiduciary Liability Brief',
 audience: 'Board of Directors, Audit Committee, Chief Executive Officer',
 coreQuestion: 'What is our aggregate financial and operational exposure from third-party vendors, and are we meeting our fiduciary duty of care?',
 description: 'High-level synthesis focusing on Tier 1 systemic risk concentration, catastrophic blast radius scenarios, unmitigated material exceptions, and capital allocation for security governance.',
 defaultScope: 'Enterprise-wide Tier 1 & Tier 2 Portfolio (All Active Vendors)',
 keyMetrics: ['Aggregate Portfolio Risk Index', 'Tier 1 Criticality Count', 'Unmitigated Material Exceptions', 'Regulatory Fines Exposure ($)']
 },
 {
 id: 'CISO',
 title: 'CISO Security Posture & Threat Intelligence Report',
 audience: 'Chief Information Security Officer, VP of Infrastructure, Security Engineering Leads',
 coreQuestion: 'Where are our security controls weakest across our supply chain, and what active threat events demand immediate mitigation?',
 description: 'Deep technical breakdown of control gaps, cipher deprecation timelines, identity/MFA fallback exposures, and active risk telemetry signals.',
 defaultScope: 'Core Vendor Infrastructure & Subprocessor Telemetry',
 keyMetrics: ['Active Risk Events (30d)', 'Open Critical Remediation Items', 'Average Time-to-Remediate (MTTR)', 'Fourth-Party Subprocessor Concentration']
 },
 {
 id: 'TPRM Program',
 title: 'Third-Party Risk Management (TPRM) Program Health',
 audience: 'Head of Risk Governance, TPRM Program Managers, Procurement Leads',
 coreQuestion: 'Is our vendor assessment throughput, intake SLA, and artifact verification pipeline operating effectively?',
 description: 'Operational metrics tracking assessment cycle times, questionnaire review backlogs, evidence freshness, and team workload distribution.',
 defaultScope: 'Global Vendor Onboarding & Annual Review Cycle',
 keyMetrics: ['Average Onboarding Duration (Days)', 'Evidence Repository Freshness (%)', 'Overdue Assessments Count', 'Intake Inbox Backlog']
 },
 {
 id: 'Vendor Risk',
 title: 'Vendor-Specific Comprehensive Risk Dossier',
 audience: 'Vendor Account Managers, Business Owners, Legal Counsel',
 coreQuestion: 'What is the complete risk, dependency, evidence, and decision history for a specific vendor entity?',
 description: 'Granular point-in-time dossier compiling all evidence artifacts, extracted facts, risk scores, blast radius dependencies, and governing decisions for a single vendor.',
 defaultScope: 'Single Selected Vendor Portfolio Entity',
 keyMetrics: ['Vendor Risk Score', 'Blast Radius Dependency Count', 'Verified Evidence Count', 'Active Decisions & Waivers']
 },
 {
 id: 'Assessment',
 title: 'Vendor Assessment & Questionnaire Findings Report',
 audience: 'Security Reviewers, Compliance Auditors, Vendor Risk Analysts',
 coreQuestion: 'What specific control gaps and questionnaire deficiencies were identified during the latest vendor assessment cycle?',
 description: 'Detailed analysis of questionnaire responses mapped against NIST/SOC 2 frameworks, highlighting unanswered controls and failing assertions.',
 defaultScope: 'Latest Completed Vendor Questionnaires',
 keyMetrics: ['Total Controls Evaluated', 'Control Compliance Rate (%)', 'Failing Assertions Count', 'Pending Clarification Items']
 },
 {
 id: 'Remediation',
 title: 'Corrective Action Plan (CAP) & Remediation Progress',
 audience: 'Remediation Owners, Engineering Leads, Risk Governance Committee',
 coreQuestion: 'Are outstanding security findings being resolved on schedule by assigned owners?',
 description: 'Tracking report focusing on open remediation tickets, overdue corrective actions, blocked workflows, and validation sign-offs.',
 defaultScope: 'Active Portfolio Remediation Ledger',
 keyMetrics: ['Open Remediation Items', 'Overdue Tickets Count', 'Completion Rate (%)', 'Validation Queue Size']
 },
 {
 id: 'Evidence',
 title: 'Evidence Repository Integrity & Epistemic Assurance',
 audience: 'Data Governance Officers, Compliance Auditors, Legal Reviewers',
 coreQuestion: 'Can we prove the authenticity, version hashes, and validity of all compliance evidence supporting our vendor posture?',
 description: 'Forensic audit of evidence artifacts, cryptographic version hashes, extraction confidence scores, and document expiration timelines.',
 defaultScope: 'Central Evidence Repository & Intake Inbox',
 keyMetrics: ['Verified Evidence Artifacts', 'Expired / Missing Documents', 'Average Extraction Confidence (%)', 'Cryptographic Hash Integrity']
 },
 {
 id: 'Compliance',
 title: 'Regulatory Framework & Standard Compliance Mapping',
 audience: 'Regulatory Compliance Officers, Legal Counsel, External Regulators',
 coreQuestion: 'How well does our vendor ecosystem align with GDPR, CCPA, PCI-DSS, and SOC 2 regulatory standards?',
 description: 'Cross-framework mapping report detailing control coverage, regulatory data residency compliance, and subprocessor DPA statuses.',
 defaultScope: 'Multi-Framework Enterprise Scope (GDPR, SOC 2, PCI-DSS)',
 keyMetrics: ['Framework Coverage (%)', 'Unverified DPA Subprocessors', 'Data Residency Violations', 'PCI-DSS Scope Integrity']
 },
 {
 id: 'Audit',
 title: 'Forensic Audit Log & Decision Defensibility Dossier',
 audience: 'Internal Auditors, External Forensic Accountants, Legal Defense Counsel',
 coreQuestion: 'Can we reconstruct every decision, state mutation, and administrative action with tamper-evident proof months later?',
 description: 'Comprehensive audit trail compiling immutable event logs, state mutation diffs (`Before`/`After`), request session IDs, and point-in-time decision replays.',
 defaultScope: 'Full System Audit Ledger & Decision History',
 keyMetrics: ['Total Immutable Events (90d)', 'Decision Replay Snapshots', 'State Mutation Count', 'Audit Trail Integrity Status']
 }
];

export function ReportsWorkspace() {
 const [selectedCategory, setSelectedCategory] = useState<ReportCategory>('Executive / Board');
 const [selectedVendorScope, setSelectedVendorScope] = useState<string>('All Active Vendors (Tier 1 & Tier 2)');
 const [selectedTimeframe, setSelectedTimeframe] = useState<string>('Trailing 90 Days');
 const [generatedPreview, setGeneratedPreview] = useState<ReportTemplate | null>(null);

 // Error simulation states
 const [hasNetworkError, setHasNetworkError] = useState(false);
 const [showPermissionModal, setShowPermissionModal] = useState(false);
 const [showSessionExpired, setShowSessionExpired] = useState(false);

 useEffect(() => {
 const handleKeyDown = (e: KeyboardEvent) => {
 if (e.key === 'Escape') {
 if (hasNetworkError) setHasNetworkError(false);
 if (showPermissionModal) setShowPermissionModal(false);
 if (showSessionExpired) setShowSessionExpired(false);
 }
 };
 document.addEventListener('keydown', handleKeyDown);
 return () => document.removeEventListener('keydown', handleKeyDown);
 }, [hasNetworkError, showPermissionModal, showSessionExpired]);

 const activeTemplate = REPORT_TEMPLATES.find(t => t.id === selectedCategory) || REPORT_TEMPLATES[0];

 // Metric values derived from live shared data — consistent with the rest of the app.
 const liveMetricValues = [
 `${portfolioHealthScore()} / 100`,
 `${getVendors().length} Vendors`,
 `${DEV_ACTIVE_REMEDIATIONS.length} Open`,
 `${getVendors().filter(v => v.evidenceStatus === 'Missing').length} Missing`,
 ];
 const generatedDate = new Date().toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });

 const handleGenerateReport = () => {
 setGeneratedPreview(activeTemplate);
 };

 const handlePrint = () => {
 window.print();
 };

 const handleExportPDF = () => {
 if (!generatedPreview) return;
 const lines = [
 `VALIDEXIA — ${generatedPreview.title}`,
 `Audience: ${generatedPreview.audience}`,
 `Generated: ${new Date().toLocaleString()}`,
 ``,
 `Scope: ${generatedPreview.defaultScope}`,
 ``,
 `Key Metrics:`,
 ...generatedPreview.keyMetrics.map(m => `  • ${m}`),
 ``,
 `Core Question: ${generatedPreview.coreQuestion}`,
 ``,
 generatedPreview.description,
 ];
 const blob = new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8;' });
 const url = URL.createObjectURL(blob);
 const a = document.createElement('a');
 a.href = url;
 a.download = `validexia-report-${generatedPreview.id.toLowerCase().replace(/[^a-z0-9]+/g, '-')}-${new Date().toISOString().slice(0, 10)}.txt`;
 document.body.appendChild(a);
 a.click();
 document.body.removeChild(a);
 URL.revokeObjectURL(url);
 };

 return (
 <div className="space-y-6 max-w-7xl mx-auto pb-28 animate-in fade-in duration-300">
 
 <div aria-live="polite" className="sr-only">
 Reports workspace loaded. Active template: {selectedCategory}.
 </div>

 {hasNetworkError && <NetworkErrorBanner onRetry={() => setHasNetworkError(false)} />}
 {showPermissionModal && <PermissionErrorModal onClose={() => setShowPermissionModal(false)} />}
 {showSessionExpired && <ExpiredSessionModal onReauth={() => setShowSessionExpired(false)} />}

 <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-4 border-b border-border pb-6">
 <div>
 <Eyebrow>Governance</Eyebrow>
 <h1 className="text-page-title">Report Templates</h1>
 <p className="text-body-secondary mt-1">
 Generate defensible, targeted governance reports tailored for board directors, CISOs, and regulators.
 </p>
 </div>
 </div>

 <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 items-start">
 <div className="space-y-6">
 <Card className="shadow-sm">
 <CardHeader className="border-b border-border px-5 py-4 bg-surface-hover/20">
 <CardTitle className="text-xs font-semibold uppercase tracking-wider flex items-center gap-2">
 <Sliders aria-hidden="true" size={15} className="text-info" /> 1. Select Report Target
 </CardTitle>
 </CardHeader>
 <CardContent className="p-4 space-y-2">
 <div className="space-y-1" role="group" aria-label="Report Templates Selection">
 {REPORT_TEMPLATES.map(template => {
 const isSelected = selectedCategory === template.id;
 return (
 <button
 key={template.id}
 type="button"
 onClick={() => { setSelectedCategory(template.id); setGeneratedPreview(null); }}
 className={`w-full text-left px-3.5 py-2.5 rounded-lg text-xs font-semibold transition-all flex items-center justify-between focus:outline-none focus:ring-2 focus:ring-info ${
 isSelected
 ? 'bg-surface text-text-primary shadow-sm border border-border-strong font-bold'
 : 'text-text-secondary hover:bg-surface-hover hover:text-text-primary'
 }`}
 >
 <span className="truncate">{template.id}</span>
 <ArrowRight aria-hidden="true" size={13} className={isSelected ? 'text-info' : 'opacity-30'} />
 </button>
 );
 })}
 </div>
 </CardContent>
 </Card>

 <Card className="shadow-sm">
 <CardHeader className="border-b border-border px-5 py-4 bg-surface-hover/20">
 <CardTitle className="text-xs font-semibold uppercase tracking-wider flex items-center gap-2">
 <Filter aria-hidden="true" size={15} className="text-info" /> 2. Scope & Parameters
 </CardTitle>
 </CardHeader>
 <CardContent className="p-5 space-y-4 text-xs">
 <div className="space-y-1.5">
 <label htmlFor="reportScopeSelect" className="font-bold uppercase tracking-wider text-[10px] text-text-tertiary block">Portfolio Scope</label>
 <select 
 id="reportScopeSelect"
 value={selectedVendorScope}
 onChange={(e) => setSelectedVendorScope(e.target.value)}
 className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-xs text-text-secondary font-medium focus:outline-none focus:ring-2 focus:ring-info"
 >
 <option value="All Active Vendors (Tier 1 & Tier 2)">All Active Vendors (Tier 1 & Tier 2)</option>
 <option value="Tier 1 Critical Vendors Only">Tier 1 Critical Vendors Only</option>
 <option value="CloudScale Infrastructure Entity">CloudScale Infrastructure Entity</option>
 <option value="Apex Gateway Payments Entity">Apex Gateway Payments Entity</option>
 <option value="SecureAuth Identity Entity">SecureAuth Identity Entity</option>
 </select>
 </div>

 <div className="space-y-1.5">
 <label htmlFor="reportTimeframeSelect" className="font-bold uppercase tracking-wider text-[10px] text-text-tertiary block">Timeframe / Audit Window</label>
 <select 
 id="reportTimeframeSelect"
 value={selectedTimeframe}
 onChange={(e) => setSelectedTimeframe(e.target.value)}
 className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-xs text-text-secondary font-medium focus:outline-none focus:ring-2 focus:ring-info"
 >
 <option value="Trailing 30 Days">Trailing 30 Days</option>
 <option value="Trailing 90 Days">Trailing 90 Days</option>
 <option value="Year-to-Date (YTD 2026)">Year-to-Date (YTD 2026)</option>
 <option value="Point-in-Time (Current State Snapshot)">Point-in-Time (Current State Snapshot)</option>
 </select>
 </div>

 <Button 
 type="button"
 variant="primary" 
 onClick={handleGenerateReport} 
 className="w-full mt-2 justify-center gap-2 font-bold py-2.5"
 >
 <>
 <Sparkles aria-hidden="true" size={15} /> Build & Preview Report
 </>
 </Button>
 </CardContent>
 </Card>
 </div>

 <div className="md:col-span-2 space-y-6">
 <Card className="shadow-sm border-accent/25 bg-accent-muted/20">
 <CardHeader className="border-b border-border px-6 py-4 bg-surface-hover/30">
 <CardTitle className="text-xs font-semibold uppercase tracking-wider flex items-center gap-2 text-accent">
 <Shield aria-hidden="true" size={16} /> Audience Blueprint & Governing Objective
 </CardTitle>
 </CardHeader>
 <CardContent className="p-6 space-y-4 text-xs">
 <div>
 <h3 className="text-base font-bold text-text-primary m-0">{activeTemplate.title}</h3>
 <p className="text-text-secondary mt-1 leading-relaxed m-0">{activeTemplate.description}</p>
 </div>

 <div className="grid grid-cols-1 md:grid-cols-2 gap-4 pt-2">
 <div className="p-4 bg-surface border border-border rounded-xl space-y-1">
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary flex items-center gap-1">
 <Users aria-hidden="true" size={13} /> Who is this report for?
 </span>
 <p className="font-semibold text-text-primary m-0">{activeTemplate.audience}</p>
 </div>

 <div className="p-4 bg-surface border border-border rounded-xl space-y-1">
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary flex items-center gap-1">
 <Gavel aria-hidden="true" size={13} /> What question does it answer?
 </span>
 <p className="font-semibold text-accent m-0">{activeTemplate.coreQuestion}</p>
 </div>
 </div>
 </CardContent>
 </Card>

 <Card className="shadow-sm overflow-hidden">
 <CardHeader className="border-b border-border px-6 py-4 bg-surface-hover/20 flex flex-row items-center justify-between">
 <CardTitle className="text-xs font-semibold uppercase tracking-wider flex items-center gap-2">
 <FileText aria-hidden="true" size={15} className="text-info" /> Preview
 </CardTitle>
 {generatedPreview && (
 <div className="flex items-center gap-2">
 <Button type="button" variant="secondary" size="sm" className="h-8 text-xs gap-1" onClick={handlePrint}>
 <Printer aria-hidden="true" size={13} /> Print
 </Button>
 <Button type="button" variant="primary" size="sm" className="h-8 text-xs gap-1" onClick={handleExportPDF}>
 <Download aria-hidden="true" size={13} /> Download Summary (.txt)
 </Button>
 </div>
 )}
 </CardHeader>

 <CardContent className="p-6 space-y-6">
 {generatedPreview ? (
 <div className="space-y-6 animate-in fade-in duration-300 bg-base p-6 rounded-xl border border-border">
 <div className="border-b border-border pb-4 flex justify-between items-start">
 <div>
 <span className="font-mono text-[10px] font-bold text-info bg-info/10 px-2 py-0.5 rounded border border-info/30">VALIDEXIA GOVERNANCE BRIEF</span>
 <h2 className="text-lg font-bold text-text-primary mt-2 m-0">{generatedPreview.title}</h2>
 <p className="text-xs text-text-tertiary mt-0.5 m-0">Target Audience: {generatedPreview.audience}</p>
 </div>
 <div className="text-right text-[11px] font-mono text-text-tertiary">
 <div>Scope: {selectedVendorScope}</div>
 <div>Window: {selectedTimeframe}</div>
 <div>Generated: {generatedDate}</div>
 </div>
 </div>

 <div className="space-y-3">
 <h4 className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary m-0">Key Performance & Risk Metrics</h4>
 <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
 {generatedPreview.keyMetrics.map((metric, idx) => (
 <div key={idx} className="p-3 bg-surface border border-border rounded-lg space-y-1">
 <span className="text-[10px] text-text-tertiary block truncate">{metric}</span>
 <span className="font-mono font-bold text-text-primary text-sm">
 {liveMetricValues[idx]}
 </span>
 </div>
 ))}
 </div>
 </div>

 <div className="space-y-2">
 <h4 className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary m-0">Executive Summary & Analysis</h4>
 <p className="text-xs text-text-secondary leading-relaxed bg-surface p-4 rounded-lg border border-border m-0">
 Based on current telemetry data and verified evidence repositories, third-party portfolio posture remains within acceptable fiduciary thresholds. However, upstream subprocessor concentration in Tier 1 cloud hosting and legacy cipher deprecation timelines require active mitigation oversight under Remediation Ticket REM-001. All governing decisions maintain demo reference tags and defensible rationale logs.
 </p>
 </div>

 <div className="border-t border-border-subtle pt-4 flex items-center justify-between text-[11px] text-text-tertiary">
 <span className="flex items-center gap-1.5 font-semibold text-text-secondary">
 <CheckCircle2 aria-hidden="true" size={14} className="text-status-success" /> Illustrative Demo Dataset
 </span>
 <span>Authorized for Board & Regulatory Review</span>
 </div>
 </div>
 ) : (
 <EmptyState
 icon={BarChart2}
 title="No Report Preview Generated"
 description="Select a target audience blueprint and parameters, then click build to compile telemetry."
 whatItIs="Automated report builder formatting portfolio telemetry into audience-specific packages."
 whyItsEmpty="You have not yet triggered generation for the selected report template."
 actionLabel="Build & Preview Report"
 onAction={handleGenerateReport}
 />
 )}
 </CardContent>
 </Card>
 </div>
 </div>
 </div>
 );
}