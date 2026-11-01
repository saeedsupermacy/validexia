// src/features/assessments/AssessmentsWorkspace.tsx
import { useState, useEffect } from 'react';
import { 
 ClipboardList, Clock, AlertCircle, ShieldAlert, Search, ArrowRight } from 'lucide-react';
import { Card, CardContent, Button, EmptyState, Eyebrow, CountUp} from '../../shared/ui/core';
import { getNavParams, clearNavParams, navigateTo } from '../../shared/navigation';
import { resolveVendorId } from '../../data/vendors';
import { getCustomAssessments, addAssessment } from '../../data/assessments';
import { recordMaterialChange } from '../../data/materialChanges';
import { showToast } from '../../shared/ui/toast';
import { DeepLinkBanner } from '../../shared/ui/DeepLinkBanner';
import { Dialog } from '../../shared/ui/Dialog';
import { AssessmentDetailWorkspace } from './AssessmentDetailWorkspace';
import { NetworkErrorBanner, PermissionErrorModal, ExpiredSessionModal } from '../../shared/ui/ErrorStates';

export type AssessmentStatus = 'Awaiting Vendor' | 'Awaiting Analyst' | 'Clarification Required' | 'Completed';
export type RiskRelevance = 'Low' | 'Medium' | 'High' | 'Critical';

export interface AssessmentRecord {
 id: string;
 vendorName: string;
 assessmentType: string;
 progress: number;
 status: AssessmentStatus;
 owner: string;
 deadline: string;
 isOverdue: boolean;
 riskRelevance: RiskRelevance;
 evidenceCoverage: number;
}

const DEV_ASSESSMENTS: AssessmentRecord[] = [
 {
 id: 'assm-01',
 vendorName: 'CloudScale Infrastructure',
 assessmentType: 'Annual Cloud Security (SIG Core)',
 progress: 100,
 status: 'Awaiting Analyst',
 owner: 'Sarah Jenkins',
 deadline: 'Oct 4, 2026',
 isOverdue: true,
 riskRelevance: 'Critical',
 evidenceCoverage: 92
 },
 {
 id: 'assm-02',
 vendorName: 'Apex Gateway Payments',
 assessmentType: 'PCI-DSS Onboarding Review',
 progress: 45,
 status: 'Awaiting Vendor',
 owner: 'Marcus Chen',
 deadline: 'Oct 15, 2026',
 isOverdue: false,
 riskRelevance: 'High',
 evidenceCoverage: 30
 },
 {
 id: 'assm-03',
 vendorName: 'SecureAuth Identity',
 assessmentType: 'Custom IAM Evaluation',
 progress: 85,
 status: 'Clarification Required',
 owner: 'Alex Rivera',
 deadline: 'Oct 10, 2026',
 isOverdue: false,
 riskRelevance: 'High',
 evidenceCoverage: 78
 },
 {
 id: 'assm-04',
 vendorName: 'DataFlow Analytics',
 assessmentType: 'GDPR / DPA Attestation',
 progress: 100,
 status: 'Completed',
 owner: 'Marcus Chen',
 deadline: 'Sep 28, 2026',
 isOverdue: false,
 riskRelevance: 'Medium',
 evidenceCoverage: 100
 },
 {
 id: 'assm-05',
 vendorName: 'Synapse AI Services',
 assessmentType: 'AI Data Privacy Review',
 progress: 15,
 status: 'Awaiting Vendor',
 owner: 'Sarah Jenkins',
 deadline: 'Oct 22, 2026',
 isOverdue: false,
 riskRelevance: 'Medium',
 evidenceCoverage: 5
 }
];

export function AssessmentsWorkspace() {
 const [assessments, setAssessments] = useState<AssessmentRecord[]>(() => [...getCustomAssessments(), ...DEV_ASSESSMENTS]);
 const [launchVendor, setLaunchVendor] = useState('');
 const [launchType, setLaunchType] = useState('Security Questionnaire');

 const handleLaunchAssessment = () => {
 if (!launchVendor.trim()) return;
 const record = addAssessment({ vendorName: launchVendor.trim(), assessmentType: launchType });
 setAssessments(prev => [record, ...prev]);
 recordMaterialChange({
 objectName: record.vendorName,
 objectType: 'Assessment',
 eventType: 'assessment_change',
 changeSummary: `Assessment launched: ${record.assessmentType}`,
 severity: 'info',
 contextReason: `A ${record.assessmentType} was started for ${record.vendorName}. In production this sends the questionnaire to the vendor and starts the review workflow; this prototype tracks the assessment locally.`,
 actionLabel: 'View Assessment',
 });
 showToast(`Assessment launched for ${record.vendorName}`, 'success');
 setLaunchVendor('');
 setLaunchType('Security Questionnaire');
 setIsLaunchOpen(false);
 };
 const [searchQuery, setSearchQuery] = useState('');
  const [deepLink, setDeepLink] = useState<string | null>(null);
  const [deepLinkSource, setDeepLinkSource] = useState<string>('another tab');
  const [deepLinkVendor, setDeepLinkVendor] = useState<string | null>(null);

  // Deep link: pre-fill search from another tab (accepts search or vendorName)
  // launchAssessment=true auto-opens the launch modal pre-filled for this vendor
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
    if (params.launchAssessment === 'true' && q && typeof q === 'string') {
      setLaunchVendor(q as string);
      setIsLaunchOpen(true);
    }
    if (q || params.launchAssessment) clearNavParams();
  }, []);
 const [statusFilter, setStatusFilter] = useState<string>('All');
 const [selectedAssessment, setSelectedAssessment] = useState<AssessmentRecord | null>(null);
 const [isLaunchOpen, setIsLaunchOpen] = useState(false);

 const [hasNetworkError, setHasNetworkError] = useState(false);
 const [showPermissionModal, setShowPermissionModal] = useState(false);
 const [showSessionExpired, setShowSessionExpired] = useState(false);

 useEffect(() => {
 const handleKeyDown = (e: KeyboardEvent) => {
 if (e.key === 'Escape') {
 if (selectedAssessment) setSelectedAssessment(null);
 if (isLaunchOpen) setIsLaunchOpen(false);
 if (hasNetworkError) setHasNetworkError(false);
 if (showPermissionModal) setShowPermissionModal(false);
 if (showSessionExpired) setShowSessionExpired(false);
 }
 };
 document.addEventListener('keydown', handleKeyDown);
 return () => document.removeEventListener('keydown', handleKeyDown);
 }, [selectedAssessment, isLaunchOpen, hasNetworkError, showPermissionModal, showSessionExpired]);

 if (selectedAssessment) {
 return (
 <AssessmentDetailWorkspace 
 vendorName={selectedAssessment.vendorName}
 assessmentType={selectedAssessment.assessmentType}
 vendorId={resolveVendorId(selectedAssessment.vendorName) ?? undefined}
 onBack={() => setSelectedAssessment(null)}
 />
 );
 }

 const filteredAssessments = assessments.filter(a => {
 const matchesSearch = a.vendorName.toLowerCase().includes(searchQuery.toLowerCase()) ||
 a.assessmentType.toLowerCase().includes(searchQuery.toLowerCase());
 const matchesStatus = statusFilter === 'All' || a.status === statusFilter;
 return matchesSearch && matchesStatus;
 });

 const activeCount = assessments.filter(a => a.status !== 'Completed').length;
 const vendorCount = assessments.filter(a => a.status === 'Awaiting Vendor').length;
 const analystCount = assessments.filter(a => a.status === 'Awaiting Analyst').length;
 const clarificationCount = assessments.filter(a => a.status === 'Clarification Required').length;
 const completedCount = assessments.filter(a => a.status === 'Completed').length;
 const overdueCount = assessments.filter(a => a.isOverdue).length;
 const avgCoverage = Math.round(assessments.reduce((acc, curr) => acc + curr.evidenceCoverage, 0) / assessments.length);

 const getStatusBadge = (status: AssessmentStatus, isOverdue: boolean) => {
 if (isOverdue && status !== 'Completed') {
 return <span className="px-3 py-1 rounded-full text-[10px] font-semibold uppercase tracking-wider bg-risk-critical/10 text-risk-critical flex items-center gap-1 w-fit"><AlertCircle aria-hidden="true" size={10} /> Overdue</span>;
 }
 switch (status) {
 case 'Awaiting Vendor':
 return <span className="px-3 py-1 rounded-full text-[10px] font-semibold uppercase tracking-wider bg-black/[0.04] dark:bg-white/[0.06] text-text-secondary">Awaiting Vendor</span>;
 case 'Awaiting Analyst':
 return <span className="px-3 py-1 rounded-full text-[10px] font-semibold uppercase tracking-wider bg-status-info/10 text-status-info">Awaiting Analyst</span>;
 case 'Clarification Required':
 return <span className="px-3 py-1 rounded-full text-[10px] font-semibold uppercase tracking-wider bg-status-warning/10 text-status-warning">Clarification Req</span>;
 case 'Completed':
 return <span className="px-3 py-1 rounded-full text-[10px] font-semibold uppercase tracking-wider bg-status-success/10 text-status-success">Completed</span>;
 }
 };

 const getRiskColor = (risk: RiskRelevance) => {
 switch (risk) {
 case 'Low': return 'text-status-success';
 case 'Medium': return 'text-status-warning';
 case 'High': return 'text-risk-high';
 case 'Critical': return 'text-risk-critical';
 }
 };

 return (
 <div className="space-y-6 max-w-7xl mx-auto pb-28 animate-in fade-in duration-300">
 
 <div aria-live="polite" className="sr-only">
 {filteredAssessments.length} assessment records matched your filter criteria.
 </div>

 {hasNetworkError && <NetworkErrorBanner onRetry={() => setHasNetworkError(false)} />}
 {showPermissionModal && <PermissionErrorModal onClose={() => setShowPermissionModal(false)} />}
 {showSessionExpired && <ExpiredSessionModal onReauth={() => setShowSessionExpired(false)} />}

 <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-4 border-b border-border pb-6">
 <div>
 <Eyebrow>Program</Eyebrow>
 <h1 className="text-page-title">Assessment Portfolio</h1>
          {deepLink && (
            <div className="mt-3">
              <DeepLinkBanner
                label={`Search = "${deepLink}"`}
                source={deepLinkSource}
                count={filteredAssessments.length}
                backLabel={deepLinkVendor ? `Back to ${deepLinkVendor}` : undefined}
                onBack={deepLinkVendor ? () => {
                  const vid = resolveVendorId(deepLinkVendor);
                  navigateTo('vendors', vid ? { vendorId: vid } : { search: deepLinkVendor });
                } : undefined}
                onClear={() => { setDeepLink(null); setDeepLinkVendor(null); setSearchQuery(''); }}
              />
            </div>
          )}
 <p className="text-body-secondary mt-1 max-w-2xl">
 Monitor active security reviews, track vendor response times, and measure assessment validation against raw evidence coverage.
 </p>
 </div>
 <div className="flex flex-wrap items-center gap-2">
 <Button type="button" variant="primary" size="sm" onClick={() => setIsLaunchOpen(true)}>
 Launch Assessment
 </Button>
 </div>
 </div>

 <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-7 gap-3" role="region" aria-label="Assessment Metrics Summary">
 <Card className="shadow-sm border-border-subtle bg-surface">
 <CardContent className="!p-3 flex flex-col items-center justify-center text-center h-20">
 <span className="text-xl font-bold text-text-primary leading-tight"><CountUp value={activeCount} /></span>
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary mt-1">Active</span>
 </CardContent>
 </Card>
 <Card className="shadow-sm border-border-subtle bg-info/5">
 <CardContent className="!p-3 flex flex-col items-center justify-center text-center h-20">
 <span className="text-xl font-bold text-info leading-tight"><CountUp value={vendorCount} /></span>
 <span className="text-[10px] font-semibold uppercase tracking-wider text-info mt-1">With Vendor</span>
 </CardContent>
 </Card>
 <Card className="shadow-sm border-border-subtle bg-info/5">
 <CardContent className="!p-3 flex flex-col items-center justify-center text-center h-20">
 <span className="text-xl font-bold text-info leading-tight"><CountUp value={analystCount} /></span>
 <span className="text-[10px] font-semibold uppercase tracking-wider text-info mt-1">For Analyst</span>
 </CardContent>
 </Card>
 <Card className="shadow-sm border-border-subtle bg-status-warning/5">
 <CardContent className="!p-3 flex flex-col items-center justify-center text-center h-20">
 <span className="text-xl font-bold text-status-warning leading-tight"><CountUp value={clarificationCount} /></span>
 <span className="text-[10px] font-semibold uppercase tracking-wider text-status-warning mt-1">Clarification</span>
 </CardContent>
 </Card>
 <Card className="shadow-sm border-border-subtle bg-risk-critical/5">
 <CardContent className="!p-3 flex flex-col items-center justify-center text-center h-20">
 <span className="text-xl font-bold text-risk-critical leading-tight"><CountUp value={overdueCount} /></span>
 <span className="text-[10px] font-semibold uppercase tracking-wider text-risk-critical mt-1">Overdue</span>
 </CardContent>
 </Card>
 <Card className="shadow-sm border-border-subtle bg-status-success/5">
 <CardContent className="!p-3 flex flex-col items-center justify-center text-center h-20">
 <span className="text-xl font-bold text-status-success leading-tight"><CountUp value={completedCount} /></span>
 <span className="text-[10px] font-semibold uppercase tracking-wider text-status-success mt-1">Completed</span>
 </CardContent>
 </Card>
 <Card className="shadow-sm border-border-subtle bg-accent-muted">
 <CardContent className="!p-3 flex flex-col items-center justify-center text-center h-20">
 <span className="text-xl font-bold text-accent leading-tight"><CountUp value={avgCoverage} />%</span>
 <span className="text-[10px] font-semibold uppercase tracking-wider text-accent mt-1">Avg Coverage</span>
 </CardContent>
 </Card>
 </div>

 <Card className="shadow-sm">
 <CardContent className="p-4 flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-4">
 <div className="relative flex-1">
 <label htmlFor="assessmentSearch" className="sr-only">Search assessments and vendors</label>
 <Search aria-hidden="true" size={16} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-text-tertiary" />
 <input
 id="assessmentSearch"
 type="search"
 value={searchQuery}
 onChange={(e) => setSearchQuery(e.target.value)}
 placeholder="Search assessments, vendors..."
 className="w-full bg-black/[0.03] dark:bg-white/[0.04] border border-transparent rounded-lg pl-10 pr-4 py-2 text-sm text-text-primary placeholder:text-text-tertiary focus:outline-none focus:bg-surface focus:border-accent/40 focus:ring-4 focus:ring-accent/10 transition-all"
 />
 </div>
 <div className="flex items-center gap-2">
 <label htmlFor="statusFilterSelect" className="sr-only">Filter by status</label>
 <select
 id="statusFilterSelect"
 value={statusFilter}
 onChange={(e) => setStatusFilter(e.target.value)}
 className="bg-black/[0.03] dark:bg-white/[0.04] border border-transparent rounded-lg px-3 py-2 text-xs text-text-secondary font-medium focus:outline-none focus:bg-surface focus:border-accent/40 focus:ring-4 focus:ring-accent/10 transition-all cursor-pointer"
 >
 <option value="All">All Statuses</option>
 <option value="Awaiting Vendor">Awaiting Vendor</option>
 <option value="Awaiting Analyst">Awaiting Analyst</option>
 <option value="Clarification Required">Clarification Required</option>
 <option value="Completed">Completed</option>
 </select>
 </div>
 </CardContent>
 </Card>

 {filteredAssessments.length > 0 ? (
 <Card className="overflow-hidden shadow-sm">
 <div className="overflow-x-auto">
 <table className="w-full text-left text-sm whitespace-nowrap">
 <caption className="sr-only">Active Security Reviews and Assessment Progress</caption>
 <thead className="text-label">
 <tr>
 <th scope="col" className="py-3 px-5 font-bold uppercase tracking-wider text-[11px]">Vendor & Assessment</th>
 <th scope="col" className="py-3 px-4 font-bold uppercase tracking-wider text-[11px]">Status & Deadline</th>
 <th scope="col" className="py-3 px-4 font-bold uppercase tracking-wider text-[11px]">Completion</th>
 <th scope="col" className="py-3 px-4 font-bold uppercase tracking-wider text-[11px]">Risk Relevance</th>
 <th scope="col" className="py-3 px-4 font-bold uppercase tracking-wider text-[11px]">Evidence Coverage</th>
 <th scope="col" className="py-3 px-4 font-bold uppercase tracking-wider text-[11px]">Owner</th>
 <th scope="col" className="py-3 px-5 text-right font-bold uppercase tracking-wider text-[11px]"><span className="sr-only">Actions</span></th>
 </tr>
 </thead>
 <tbody className="bg-surface">
 {filteredAssessments.map((assessment) => (
 <tr 
 key={assessment.id} 
 onClick={() => setSelectedAssessment(assessment)}
 onKeyDown={(e) => {
 if (e.key === 'Enter' || e.key === ' ') {
 e.preventDefault();
 setSelectedAssessment(assessment);
 }
 }}
 tabIndex={0}
 role="button"
 aria-label={`Open assessment for ${assessment.vendorName}`}
 className="hover:bg-surface-hover transition-colors cursor-pointer group focus:bg-surface-hover focus:outline-none focus:ring-2 focus:ring-inset focus:ring-info"
 >
 <td className="py-4 px-5">
 <div className="font-bold text-text-primary flex items-center gap-2">
 <ClipboardList aria-hidden="true" size={16} className="text-text-tertiary shrink-0" />
 <span>{assessment.vendorName}</span>
 </div>
 <div className="text-xs text-text-secondary mt-1 ml-6">
 {assessment.assessmentType}
 </div>
 </td>

 <td className="py-4 px-4 space-y-1.5">
 {getStatusBadge(assessment.status, assessment.isOverdue)}
 <div className={`text-[11px] font-mono flex items-center gap-1 mt-1 ${assessment.isOverdue && assessment.status !== 'Completed' ? 'text-risk-critical font-bold' : 'text-text-tertiary'}`}>
 <Clock aria-hidden="true" size={12} /> <span className="sr-only">Deadline: </span>{assessment.deadline}
 </div>
 </td>

 <td className="py-4 px-4 w-40">
 <div className="flex items-center gap-3">
 <div className="w-full h-2 bg-border-subtle rounded-full overflow-hidden" role="progressbar" aria-valuenow={assessment.progress} aria-valuemin={0} aria-valuemax={100} aria-label="Completion progress">
 <div 
 className={`h-full rounded-full transition-all duration-500 ${assessment.progress === 100 ? 'bg-status-success' : 'bg-info'}`} 
 style={{ width: `${assessment.progress}%` }} 
 />
 </div>
 <span className="text-xs font-bold text-text-primary w-8 text-right">{assessment.progress}%</span>
 </div>
 </td>

 <td className="py-4 px-4">
 <div className={`flex items-center gap-1.5 font-bold text-xs ${getRiskColor(assessment.riskRelevance)}`}>
 <ShieldAlert aria-hidden="true" size={14} />
 <span className="sr-only">Risk Relevance: </span>{assessment.riskRelevance}
 </div>
 </td>

 <td className="py-4 px-4 w-40">
 <div className="flex flex-col gap-1">
 <div className="flex items-center justify-between text-[10px] font-semibold uppercase tracking-wider text-text-tertiary">
 <span>Verified Maps</span>
 <span className={assessment.evidenceCoverage > 80 ? 'text-status-success' : assessment.evidenceCoverage < 40 ? 'text-status-warning' : 'text-text-secondary'}>
 {assessment.evidenceCoverage}%
 </span>
 </div>
 <div className="w-full h-1.5 bg-border-subtle rounded-full overflow-hidden" role="progressbar" aria-valuenow={assessment.evidenceCoverage} aria-valuemin={0} aria-valuemax={100} aria-label="Evidence coverage">
 <div 
 className={`h-full rounded-full transition-all duration-500 ${assessment.evidenceCoverage > 80 ? 'bg-status-success' : assessment.evidenceCoverage < 40 ? 'bg-status-warning' : 'bg-accent'}`} 
 style={{ width: `${assessment.evidenceCoverage}%` }} 
 />
 </div>
 </div>
 </td>

 <td className="py-4 px-4">
 <div className="flex items-center gap-2 text-xs text-text-secondary">
 <div aria-hidden="true" className="w-6 h-6 rounded bg-surface-hover border border-border flex items-center justify-center text-[10px] font-bold text-text-primary">
 {assessment.owner.split(' ').map(n => n[0]).join('')}
 </div>
 <span className="font-medium truncate w-24">{assessment.owner}</span>
 </div>
 </td>

 <td className="py-4 px-5 text-right">
 <div aria-hidden="true" className="inline-flex items-center justify-center w-8 h-8 rounded-lg text-text-tertiary group-hover:bg-info group-hover:text-white transition-all shadow-sm border border-transparent group-hover:border-info/20">
 <ArrowRight size={16} />
 </div>
 </td>
 </tr>
 ))}
 </tbody>
 </table>
 </div>
 </Card>
 ) : (
 <EmptyState
 icon={ClipboardList}
 title={deepLink ? `No Results for "${deepLink}"` : "No Vendor Assessments Found"}
 description={deepLink ? "No records match this deep link. The item may have been resolved, archived, or renamed." : "No security questionnaires or risk assessments match your current filters."}
 whatItIs="The evaluation engine used to send, track, and score vendor security questionnaires."
 whyItsEmpty="Current search query or status filter eliminated all matching assessment records."
 actionLabel={deepLink ? "Clear Deep Link" : "Reset Filters"}
 onAction={() => { setDeepLink(null); setSearchQuery(''); setStatusFilter('All'); }}
 />
 )}

 <Dialog open={isLaunchOpen} onClose={() => setIsLaunchOpen(false)} title="Launch Assessment" maxWidth="max-w-md">
 <div className="p-6 space-y-5">
 <div>
 <h2 id="launch-title" className="text-base font-bold text-text-primary m-0">Launch Assessment</h2>
 <p className="text-sm text-text-secondary mt-1">Start a new vendor assessment. In production this sends the questionnaire to the vendor and starts the review workflow — this prototype records it locally so you can track it here.</p>
 </div>
 <div className="space-y-4">
 <div>
 <label htmlFor="assess-vendor" className="block text-xs font-semibold text-text-secondary mb-1.5">Vendor</label>
 <input id="assess-vendor" type="text" value={launchVendor} onChange={(e) => setLaunchVendor(e.target.value)} placeholder="e.g. Acme Cloud Services" className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-info" />
 </div>
 <div>
 <label htmlFor="assess-type" className="block text-xs font-semibold text-text-secondary mb-1.5">Assessment type</label>
 <select id="assess-type" value={launchType} onChange={(e) => setLaunchType(e.target.value)} className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:ring-2 focus:ring-info">
 <option>SOC 2 Review</option>
 <option>Security Questionnaire</option>
 <option>Penetration Test Review</option>
 <option>Compliance Audit</option>
 </select>
 </div>
 </div>
 <div className="flex justify-end gap-2">
 <Button variant="secondary" size="sm" onClick={() => setIsLaunchOpen(false)}>Cancel</Button>
 <Button variant="primary" size="sm" onClick={handleLaunchAssessment} disabled={!launchVendor.trim()}>Launch</Button>
 </div>
 </div>
 </Dialog>

 </div>
 );
}