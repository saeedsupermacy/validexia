// src/features/command-center/CommandCenter.tsx
import { 
 AlertCircle, ShieldAlert, Clock, Activity, 
 FileX, Filter, FileText, Network, Scale
} from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent, Button, Eyebrow, CountUp} from '../../shared/ui/core';
import { PortfolioRiskCard } from './PortfolioRiskCard';
import { getVendors } from '../../data/vendors';
import { getDecisions } from '../../data/decisions';
import { PortfolioPulse } from './PortfolioPulse';
import { materialChanges24h } from '../../data/materialChanges';
import { RiskEventsSummaryCard } from './RiskEventsSummaryCard';
import { DEV_ACTIVE_REMEDIATIONS } from './RemediationHealthCard';
import { MaterialChangesTimeline } from './MaterialChangesTimeline';
import { EvidenceHealthCard } from './EvidenceHealthCard';
import { RemediationHealthCard } from './RemediationHealthCard';
import { navigateTo, ViewKey } from '../../shared/navigation';


type TriageStatus = 'overdue' | 'warning' | 'pending';

export interface TriageItem {
 id: string;
 category: 'evidence' | 'risk' | 'remediation' | 'assessment' | 'contract' | 'fourth_party';
 vendor: string;
 what: string;
 why: string;
 who: string;
 whenLabel: string;
 status: TriageStatus;
 nextAction: string;
}

export const DEV_TRIAGE_ITEMS: TriageItem[] = [
 { 
 id: 't1', 
 category: 'remediation',
 vendor: 'SecureAuth Identity', 
 what: 'Critical vulnerability patching SLA breached', 
 why: 'Unpatched Zero-Day (CVE-2026-1045) exposed on perimeter scan.',
 who: 'Marcus Chen',
 whenLabel: '94 days overdue',
 status: 'overdue',
 nextAction: 'Escalate'
 },
 { 
 id: 't2', 
 category: 'evidence',
 vendor: 'CloudScale Infrastructure', 
 what: 'SOC 2 Type II report expired', 
 why: 'Creates a critical compliance gap for a Tier 1 infrastructure provider.',
 who: 'Sarah Jenkins',
 whenLabel: 'Overdue by 2 days',
 status: 'overdue',
 nextAction: 'Request Update'
 },
 { 
 id: 't3', 
 category: 'risk',
 vendor: 'Apex Gateway', 
 what: 'Material risk score increase (68 → 76)', 
 why: 'Multiple dark web credential exposures detected in the last 48 hours.',
 who: 'Security Ops',
 whenLabel: 'Today',
 status: 'warning',
 nextAction: 'Investigate'
 },
 { 
 id: 't4', 
 category: 'assessment',
 vendor: 'DataFlow Analytics', 
 what: 'Annual SIG Core assessment submitted', 
 why: 'Vendor has completed the questionnaire and uploaded supporting artifacts.',
 who: 'Alex Rivera',
 whenLabel: 'Due in 2 days',
 status: 'pending',
 nextAction: 'Review'
 },
 { 
 id: 't5', 
 category: 'fourth_party',
 vendor: 'GlobalHR Systems', 
 what: 'New 4th-party sub-processor detected', 
 why: 'Added AWS eu-central-1, altering our approved geographic data residency boundaries.',
 who: 'Sarah Jenkins',
 whenLabel: 'Due in 7 days',
 status: 'pending',
 nextAction: 'Audit Sub-processor'
 },
];

/** Build triage items for expired vendor contracts.
 *  Surfaces vendors whose contractEnd has passed without renewal. */
function buildContractTriageItems(): TriageItem[] {
 const today = new Date();
 today.setHours(0, 0, 0, 0);
 return getVendors()
 .filter(v => v.status === 'Active' && v.contractEnd)
 .filter(v => {
 const end = new Date(v.contractEnd!);
 end.setHours(0, 0, 0, 0);
 return end < today;
 })
 .map(v => {
 const end = new Date(v.contractEnd!);
 const daysOverdue = Math.floor((today.getTime() - end.getTime()) / 86400000);
 const endLabel = end.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
 return {
 id: `contract-${v.id}`,
 category: 'contract' as const,
 vendor: v.name,
 what: 'Vendor contract expired',
 why: `Contract ended ${endLabel} (${daysOverdue} days ago). Renewal or offboarding decision required.`,
 who: v.owner,
 whenLabel: `Expired ${daysOverdue}d ago`,
 status: 'overdue' as const,
 nextAction: 'Review Contract',
 };
 });
}

/** Full triage list: static items + dynamically detected contract expirations. */
export function getTriageItems(): TriageItem[] {
 return [...DEV_TRIAGE_ITEMS, ...buildContractTriageItems()];
}

const getCategoryIcon = (category: TriageItem['category']) => {
 switch (category) {
 case 'remediation': return <ShieldAlert aria-hidden="true" size={16} className="text-risk-critical" strokeWidth={2} />;
 case 'evidence': return <FileX aria-hidden="true" size={16} className="text-risk-high" strokeWidth={2} />;
 case 'risk': return <Activity aria-hidden="true" size={16} className="text-status-warning" strokeWidth={2} />;
 case 'assessment': return <FileText aria-hidden="true" size={16} className="text-status-info" strokeWidth={2} />;
 case 'contract': return <Scale aria-hidden="true" size={16} className="text-text-secondary" strokeWidth={2} />;
 case 'fourth_party': return <Network aria-hidden="true" size={16} className="text-text-secondary" strokeWidth={2} />;
 default: return <AlertCircle aria-hidden="true" size={16} className="text-text-secondary" strokeWidth={2} />;
 }
};

const getStatusBadgeStyles = (status: TriageStatus) => {
 switch (status) {
 case 'overdue': return 'bg-risk-critical/10 text-risk-critical font-semibold';
 case 'warning': return 'bg-status-warning/10 text-status-warning font-semibold';
 case 'pending': return 'bg-black/[0.04] dark:bg-white/[0.06] text-text-secondary';
 }
};

export function CommandCenter() {
 const triageItems = getTriageItems();
 const recentDecisions = getDecisions().slice(0, 3);
 const handleTriageNavigate = (item: TriageItem) => {
 const viewMap: Record<string, ViewKey> = {
 remediation: 'remediation',
 evidence: 'evidence',
 assessment: 'assessments',
 monitoring: 'monitoring',
 vendor: 'vendors',
 fourth_party: 'fourth-parties',
 risk: 'risk-events',
 };
 const target = viewMap[item.category] || 'vendors';
 navigateTo(target, { vendorName: item.vendor });
 };
 return (
 <div className="space-y-8 max-w-7xl mx-auto pb-28 animate-in fade-in duration-300">
 
 <div aria-live="polite" className="sr-only">
 Command Center loaded. {triageItems.length} critical items require operational triage today.
 </div>

 {/* 1. Page Header */}
 <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-4 border-b border-border-subtle pb-6">
 <div>
 <Eyebrow>Overview</Eyebrow>
 <h1 className="text-page-title">Command Center</h1>
 <p className="text-body-secondary mt-1">
 <strong className="text-text-primary font-semibold"><CountUp value={triageItems.length} /> open items</strong> require operational triage today.
 </p>
 </div>
 <div className="flex flex-wrap items-center gap-3">
 <div className="flex items-center text-xs text-text-tertiary font-mono" aria-label="Last updated time">
 <Clock aria-hidden="true" size={14} className="mr-1.5" strokeWidth={1.75} />
 Last updated: {new Date().toLocaleDateString('en-US', { month: 'short', day: 'numeric' })} at {new Date().toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })}
 </div>
 </div>
 </div>

 {/* 1b. Portfolio Pulse — the hook: health, stakes, and what's new, all live */}
 <PortfolioPulse
          changed24h={materialChanges24h()}
          breachedCount={DEV_ACTIVE_REMEDIATIONS.filter(i => { const s: Record<string, number> = { critical: 14, high: 30, moderate: 60, low: 90 }; return i.agingDays > (s[i.severity] ?? 90); }).length}
        />

 {/* 2. Needs Attention — structured operational queue, not a boxed card */}
 <section aria-labelledby="triage-heading">
 <div className="flex items-center justify-between mb-3 px-1">
 <div className="flex items-baseline gap-2.5">
 <h2 id="triage-heading" className="text-sm font-semibold text-text-primary m-0">Needs attention</h2>
 <span className="text-xs text-text-tertiary">{triageItems.length} items · triage queue</span>
 </div>

 </div>

 <div className="rounded-[10px] border border-border-subtle bg-surface overflow-hidden shadow-[var(--shadow-card)]">
 <div className="flex-1 flex flex-col divide-y divide-border-subtle">
 {triageItems.map((item) => (
 <div 
 key={item.id} 
 className="group relative flex flex-col lg:flex-row lg:items-center justify-between gap-4 p-5 hover:bg-surface-hover transition-colors cursor-pointer focus:bg-surface-hover focus:outline-none focus:ring-2 focus:ring-inset focus:ring-info"
 onClick={() => handleTriageNavigate(item)}
 onKeyDown={(e) => {
 if (e.key === 'Enter' || e.key === ' ') {
 e.preventDefault();
 handleTriageNavigate(item);
 }
 }}
 role="button"
 tabIndex={0}
 aria-label={`Review ${item.what} for ${item.vendor}. Status: ${item.status}. Assigned to ${item.who}.`}
 >
 <div className="flex items-start gap-3.5 min-w-0 flex-1">
 <div className="p-2 rounded-lg bg-black/[0.03] dark:bg-white/[0.04] shrink-0 mt-0.5">
 {getCategoryIcon(item.category)}
 </div>

 <div className="min-w-0 flex-1">
 <div className="flex flex-wrap items-center gap-2 mb-1">
 <span className="text-xs font-bold text-text-primary tracking-wide uppercase">
 {item.vendor}
 </span>
 <span className="text-text-tertiary text-xs" aria-hidden="true">•</span>
 <span className="text-[11px] font-semibold text-text-tertiary uppercase tracking-wider">
 {item.category.replace('_', ' ')}
 </span>
 </div>
 <h3 className="text-sm font-bold text-text-primary mb-1 group-hover:text-info transition-colors m-0">
 {item.what}
 </h3>
 <p className="text-xs text-text-secondary leading-relaxed line-clamp-1 m-0">
 {item.why}
 </p>
 </div>
 </div>

 <div className="flex items-center justify-between lg:justify-end gap-4 shrink-0 pl-11 lg:pl-0">
 <div className="flex items-center gap-2">
 <div aria-hidden="true" className="w-6 h-6 rounded-full bg-black/[0.04] dark:bg-white/[0.06] flex items-center justify-center text-[10px] font-bold text-text-secondary shrink-0">
 {item.who.charAt(0)}
 </div>
 <span className="text-xs font-medium text-text-secondary truncate max-w-[120px]">{item.who}</span>
 </div>
 
 <span className="relative inline-flex items-center shrink-0">
 <span className={`text-[10px] uppercase tracking-wider px-2.5 py-1 rounded-md whitespace-nowrap transition-opacity group-hover:opacity-0 ${getStatusBadgeStyles(item.status)}`}>
 {item.whenLabel}
 </span>
 <span className="absolute inset-0 flex items-center justify-center opacity-0 group-hover:opacity-100 group-focus:opacity-100 transition-opacity">
 <Button type="button" variant="secondary" size="sm" className="text-xs h-7 px-3 font-semibold whitespace-nowrap" onClick={(e) => { e.stopPropagation(); handleTriageNavigate(item); }}>
 {item.nextAction}
 </Button>
 </span>
 </span>
 </div>
 </div>
 ))}
 </div>
 </div>
 </section>

 {/* 3. Dashboard cards: two independent columns that grow with their own content.
 No rigid rows — any height difference only appears at the page bottom, never as a mid-page void. */}
 <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 items-start">
 <div className="flex flex-col gap-6 min-w-0">
 <PortfolioRiskCard />

  <Card className="shadow-sm">
 <CardHeader className="border-b border-border px-6 py-4">
 <CardTitle>Program Coverage</CardTitle>
 </CardHeader>
 <CardContent className="p-6">
 {(() => {
 const known = getVendors().length;
 const tiered = getVendors().filter(v => v.tieringComplete !== false).length;
 const assessed = getVendors().filter(v => v.assessmentStatus === 'Completed').length;
 const monitored = getVendors().filter(v => v.monitoringStatus === 'Active').length;
 const untiered = known - tiered;
 return (<>
 <div className="flex items-center w-full max-w-xl mx-auto" role="region" aria-label="Program Coverage Funnel Diagram">
 <div className="flex flex-col items-center gap-2">
 <button type="button" onClick={() => navigateTo('vendors')} className="flex flex-col items-center gap-2 rounded-lg focus:outline-none focus:ring-2 focus:ring-info px-1" aria-label={`${known} known vendors. View vendors.`}>
 <div className="w-12 h-12 rounded-full bg-black/[0.04] dark:bg-white/[0.06] border-2 border-border-strong flex items-center justify-center text-sm font-bold tabular-nums text-text-primary z-10 shadow-sm hover:border-info transition-colors">{known}</div>
 <span className="text-xs text-text-secondary font-semibold">Known</span>
 </button>
 </div>
 
 <div aria-hidden="true" className="flex-1 h-0.5 bg-border-strong -mt-6 rounded-full"></div>
 
 <div className="flex flex-col items-center gap-2">
 <button type="button" onClick={() => navigateTo('vendors')} className="flex flex-col items-center gap-2 rounded-lg focus:outline-none focus:ring-2 focus:ring-info px-1" aria-label={`${tiered} tiered vendors. View vendors.`}>
 <div className="w-12 h-12 rounded-full bg-status-info/10 border-2 border-status-info/50 flex items-center justify-center text-sm font-bold tabular-nums text-status-info z-10 shadow-sm hover:border-info transition-colors">{tiered}</div>
 <span className="text-xs text-status-info font-semibold">Tiered</span>
 </button>
 </div>
 
 <div aria-hidden="true" className="flex-1 h-0.5 bg-status-info/40 -mt-6 rounded-full"></div>
 
 <div className="flex flex-col items-center gap-2">
 <button type="button" onClick={() => navigateTo('vendors', { assessmentStatus: 'Completed' })} className="flex flex-col items-center gap-2 rounded-lg focus:outline-none focus:ring-2 focus:ring-info px-1" aria-label={`${assessed} assessed vendors. View vendors with completed assessments.`}>
 <div className="w-12 h-12 rounded-full bg-status-info/10 border-2 border-status-info flex items-center justify-center text-sm font-bold tabular-nums text-status-info z-10 shadow-sm hover:border-info transition-colors">{assessed}</div>
 <span className="text-xs text-status-info font-semibold">Assessed</span>
 </button>
 </div>

 <div aria-hidden="true" className="flex-1 h-0.5 bg-status-success/40 -mt-6 rounded-full"></div>

 <div className="flex flex-col items-center gap-2">
 <button type="button" onClick={() => navigateTo('vendors')} className="flex flex-col items-center gap-2 rounded-lg focus:outline-none focus:ring-2 focus:ring-info px-1" aria-label={`${monitored} monitored vendors. View vendors.`}>
 <div className="w-12 h-12 rounded-full bg-status-success/10 border-2 border-status-success flex items-center justify-center text-sm font-bold tabular-nums text-status-success z-10 shadow-sm hover:border-info transition-colors">{monitored}</div>
 <span className="text-xs text-status-success font-semibold">Monitored</span>
 </button>
 </div>
 </div>
 
 {untiered > 0 && (
 <div className="mt-8 flex items-start gap-3 text-sm text-text-secondary bg-surface-hover p-4 rounded-lg border border-border max-w-2xl mx-auto shadow-sm">
 <Filter aria-hidden="true" size={16} className="text-text-tertiary shrink-0 mt-0.5" strokeWidth={1.75} />
 <p className="m-0"><strong className="text-text-primary font-semibold">{untiered} vendors</strong> have no tier. Assign one so they get the right review schedule.</p>
 </div>
 )}
 </>);
 })()}
 </CardContent>
 </Card>
 <EvidenceHealthCard />
 </div>
 <div className="flex flex-col gap-6 min-w-0">
 <MaterialChangesTimeline />
 <RiskEventsSummaryCard />
 <RemediationHealthCard />
 </div>
 </div>



 {/* 6. Decisions */}
 <div>
 <h2 className="text-section-title mb-1 px-1">Decisions</h2>
 <p className="text-xs text-text-secondary mb-4 px-1">The official record of governance calls — who decided what, for which vendor, and when. Click a row to open it in the Decisions tab.</p>
 <Card className="overflow-hidden shadow-sm">
 <div className="overflow-x-auto">
 <table className="w-full text-left text-sm whitespace-nowrap">
 <caption className="sr-only">Governance Decisions Ledger</caption>
 <thead className="bg-surface-hover border-b border-border text-label">
 <tr>
 <th scope="col" className="py-3 px-6 font-bold uppercase tracking-wider text-[11px]">Timestamp</th>
 <th scope="col" className="py-3 px-6 font-bold uppercase tracking-wider text-[11px]">Action</th>
 <th scope="col" className="py-3 px-6 font-bold uppercase tracking-wider text-[11px]">Entity</th>
 <th scope="col" className="py-3 px-6 font-bold uppercase tracking-wider text-[11px]">Actor</th>
 </tr>
 </thead>
 <tbody className="divide-y divide-border-subtle bg-surface">
 {recentDecisions.map((decision) => (
 <tr
 key={decision.id}
 onClick={() => navigateTo('decisions', { search: decision.id })}
 onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') navigateTo('decisions', { search: decision.id }); }}
 tabIndex={0}
 role="button"
 aria-label={`Decision: ${decision.decisionTitle} for ${decision.vendorName}. View decision ${decision.id}.` }
 className="hover:bg-surface-hover transition-colors cursor-pointer focus:outline-none focus:ring-2 focus:ring-info focus:ring-inset"
 >
 <td className="py-3.5 px-6 text-text-tertiary tabular-nums font-mono text-xs">{decision.timestamp}</td>
 <td className="py-3.5 px-6 text-text-primary font-semibold">{decision.decisionTitle}</td>
 <td className="py-3.5 px-6 text-text-secondary font-medium">{decision.vendorName}</td>
 <td className="py-3.5 px-6 text-text-secondary">{decision.decisionMaker}</td>
 </tr>
 ))}
 </tbody>
 </table>
 </div>
 </Card>
 </div>

 </div>
 );
}