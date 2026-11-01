// src/components/VendorsTable.tsx
import { useState, useMemo, useEffect, useRef } from 'react';
import { 
 Search, ArrowUpDown, Download, Plus, 
 X, SlidersHorizontal, RotateCcw, ShieldAlert 
} from 'lucide-react';
import { Card, CardContent, Button, Eyebrow, ScoreRing} from '../shared/ui/core';
import { VendorDetailWorkspace } from '../features/vendors/VendorDetailWorkspace';
import { getNavParams, clearNavParams, navigateTo, parseHash } from '../shared/navigation';
import { DeepLinkBanner } from '../shared/ui/DeepLinkBanner';
import { Dialog } from '../shared/ui/Dialog';

// ... (Types and DEV_VENDORS remain unchanged) ...
import type { RiskLevel, BusinessCriticality, EvidenceStatus, AssessmentStatus, VendorRecord } from '../data/vendors';
export type { RiskLevel, BusinessCriticality, EvidenceStatus, AssessmentStatus, VendorRecord };
import { getVendorsWithStatus, addVendor } from '../data/vendors';
import { recordMaterialChange } from '../data/materialChanges';
import { showToast } from '../shared/ui/toast';
import { auditStore } from '../features/audit/auditStore';

export interface FilterState {
 search: string;
 riskLevels: RiskLevel[];
 criticalities: BusinessCriticality[];
 evidenceStatuses: EvidenceStatus[];
 assessmentStatuses: AssessmentStatus[];
 owners: string[];
 minOpenIssues: number;
 statuses: ('Active' | 'Terminated')[];
 /** "Needs attention" = high/critical risk OR missing evidence (OR semantics). */
 needsAttention: boolean;
}

const DEFAULT_FILTERS: FilterState = {
 search: '',
 riskLevels: [],
 criticalities: [],
 evidenceStatuses: [],
 assessmentStatuses: [],
 owners: [],
 minOpenIssues: 0,
 statuses: [],
 needsAttention: false,
};


export function VendorsTable() {
 const [activeVendorId, setActiveVendorId] = useState<string | null>(null);
 const [isAddVendorOpen, setIsAddVendorOpen] = useState(false);
 const [vendors, setVendors] = useState(() => getVendorsWithStatus());
 // Row id of a vendor added moments ago — scroll to + highlight it.
 const [justAddedId, setJustAddedId] = useState<string | null>(null);
 // Form state for the Add Vendor modal
 const [newVendorName, setNewVendorName] = useState('');
 const [newVendorTier, setNewVendorTier] = useState<'Tier 1' | 'Tier 2' | 'Tier 3'>('Tier 2');
 const [newVendorOwner, setNewVendorOwner] = useState('');
 const [newVendorWebsite, setNewVendorWebsite] = useState('');
 const [newVendorDesc, setNewVendorDesc] = useState('');
 const [tierDataAccess, setTierDataAccess] = useState('');
 const [tierCriticality, setTierCriticality] = useState('');
 const suggestedTier = (() => {
 const scores: Record<string, number> = { 'Tier 1': 3, 'Tier 2': 2, 'Tier 3': 1 };
 const fromData: Record<string, string> = { 'pii': 'Tier 1', 'internal': 'Tier 2', 'public': 'Tier 3' };
 const fromCrit: Record<string, string> = { 'stops': 'Tier 1', 'major': 'Tier 2', 'minor': 'Tier 3' };
 const candidates = [fromData[tierDataAccess], fromCrit[tierCriticality]].filter(Boolean) as string[];
 if (!candidates.length) return null;
 return candidates.sort((a, b) => scores[b] - scores[a])[0] as 'Tier 1' | 'Tier 2' | 'Tier 3';
 })();
 const handleExportAudit = () => {
 const headers = ['ID', 'Code', 'Vendor Name', 'Status', 'Criticality', 'Risk Level', 'Risk Score', 'Inherent Risk', 'Residual Risk', 'Evidence Status', 'Assessment Status', 'Open Issues', 'Owner', 'Website', 'Description', 'Contract Start', 'Contract End', 'Monitoring Status', 'Last Material Change'];
 const q = (v: unknown) => `"${String(v ?? '').replace(/"/g, '""')}"`;
 const rows = filteredVendors.map(v => [
 v.id, v.code, q(v.name), v.status, v.criticality, v.riskLevel, v.riskScore, v.inherentRisk ?? '', v.residualRisk ?? '',
 v.evidenceStatus, v.assessmentStatus, v.openIssues, q(v.owner), v.website ?? '', q(v.description ?? ''),
 v.contractStart ?? '', v.contractEnd ?? '', v.monitoringStatus, q(v.lastMaterialChange)
 ]);
 const csv = [headers.join(','), ...rows.map(r => r.join(','))].join('\n');
 const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
 const url = URL.createObjectURL(blob);
 const a = document.createElement('a');
 a.href = url;
 a.download = `validexia-vendor-audit-${new Date().toISOString().slice(0, 10)}.csv`;
 document.body.appendChild(a);
 a.click();
 document.body.removeChild(a);
 URL.revokeObjectURL(url);
 };

 const handleAddVendor = () => {
 if (!newVendorName.trim()) return;
 const record = addVendor({ name: newVendorName, criticality: newVendorTier, owner: newVendorOwner, website: newVendorWebsite, description: newVendorDesc });
 auditStore.appendRecord('Current User', 'VENDOR_ADDED', newVendorName.trim(), `Added ${newVendorTier} vendor via Vendors tab`);
 recordMaterialChange({
 objectName: record.name,
 objectType: 'Vendor',
 eventType: 'risk_change',
 changeSummary: `New vendor "${record.name}" added to the portfolio (${record.criticality})`,
 severity: 'info',
 contextReason: 'Vendor registered via the Vendors tab. Complete their assessment and evidence to establish a risk baseline.',
 actionLabel: 'Open Vendor Profile',
 });
 // Reset filters so the new vendor is visible, then scroll to + highlight its row.
 setFilters(DEFAULT_FILTERS);
 setDeepLink(null);
 setVendors(getVendorsWithStatus());
 setJustAddedId(record.id);
 showToast(`"${record.name}" added to your portfolio`, 'success');
 setNewVendorName('');
 setNewVendorTier('Tier 2');
 setNewVendorOwner('');
 setNewVendorWebsite('');
 setNewVendorDesc('');
 setTierDataAccess('');
 setTierCriticality('');
 setIsAddVendorOpen(false);
 };

 // Scroll to + briefly highlight a newly added vendor's row.
 useEffect(() => {
 if (!justAddedId) return;
 const t = setTimeout(() => {
 highlightRowRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
 }, 60);
 const clear = setTimeout(() => setJustAddedId(null), 8000);
 return () => { clearTimeout(t); clearTimeout(clear); };
 }, [justAddedId]);

 // Deep link: filter by params from another tab + blue highlight on arrival.
 // Also supports ?vendorId=xxx to open a vendor's detail page directly (shareable URL).
 const [deepLink, setDeepLink] = useState<{ label: string; highlight: string } | null>(null);
 const [deepLinkSource, setDeepLinkSource] = useState<string>('another tab');
 const highlightRowRef = useRef<HTMLTableRowElement | null>(null);
 useEffect(() => {
 const params = getNavParams();
 // Vendor detail deep link takes precedence — open the detail page by ID (exact match, not name substring)
 if (params.vendorId && typeof params.vendorId === 'string') {
 const match = getVendorsWithStatus().find(v => v.id === params.vendorId);
 if (match) {
 setActiveVendorId(match.id);
 clearNavParams();
 return;
 }
 }
 let changed = false;
 const next: Partial<FilterState> = {};
 const labels: string[] = [];
 let highlight = '';
 if (params.vendorName && typeof params.vendorName === 'string') {
 next.search = params.vendorName as string;
 labels.push(`Vendor = ${params.vendorName}`);
 highlight = params.vendorName as string;
 changed = true;
 }
 if (params.evidenceStatus && typeof params.evidenceStatus === 'string') {
 const valid = ['Verified', 'Expiring', 'Missing', 'Stale'];
 if (valid.includes(params.evidenceStatus as string)) {
 next.evidenceStatuses = [params.evidenceStatus as EvidenceStatus];
 labels.push(`Evidence status = ${params.evidenceStatus}`);
 changed = true;
 }
 }
 if (params.riskLevels && typeof params.riskLevels === 'string') {
 const valid = ['Critical', 'High', 'Moderate', 'Low'];
 const levels = (params.riskLevels as string).split(',').filter(l => valid.includes(l)) as RiskLevel[];
 if (levels.length > 0) {
 next.riskLevels = levels;
 labels.push(`Risk level = ${levels.join(', ')}`);
 changed = true;
 }
 }
 if (params.criticalities && typeof params.criticalities === 'string') {
 const valid = ['Tier 1', 'Tier 2', 'Tier 3'];
 const tiers = (params.criticalities as string).split(',').filter(t => valid.includes(t)) as BusinessCriticality[];
 if (tiers.length > 0) {
 next.criticalities = tiers;
 labels.push(`Criticality = ${tiers.join(', ')}`);
 changed = true;
 }
 }
 if (params.assessmentStatus && typeof params.assessmentStatus === 'string') {
 const valid = ['Completed', 'Pending Review', 'Not Started'];
 if (valid.includes(params.assessmentStatus as string)) {
 next.assessmentStatuses = [params.assessmentStatus as AssessmentStatus];
 labels.push(`Assessment = ${params.assessmentStatus}`);
 changed = true;
 }
 }
 if (params.needsAttention === 'true') {
 next.needsAttention = true;
 labels.push('Needs attention (high/critical risk or missing evidence)');
 changed = true;
 }
 if (params.from && typeof params.from === 'string') {
 setDeepLinkSource(params.from as string);
 }
 if (changed) {
 setFilters(prev => ({ ...prev, ...next }));
 setDeepLink({ label: labels.join(' · '), highlight });
 clearNavParams();
 }
 }, []);

 // Scroll the first highlighted row into view + pulse it
 useEffect(() => {
 if (deepLink && highlightRowRef.current) {
 highlightRowRef.current.scrollIntoView({ behavior: 'smooth', block: 'center' });
 }
 }, [deepLink]);

 // Back/forward button: sync the open detail page with the URL hash.
 // pushState doesn't fire hashchange, so this only runs on browser back/forward.
 useEffect(() => {
 const syncDetailFromHash = () => {
 const parsed = parseHash();
 if (parsed && parsed.view === 'vendors') {
 const vid = parsed.params.vendorId;
 if (typeof vid === 'string' && vid && getVendorsWithStatus().some(v => v.id === vid)) {
 setActiveVendorId(vid);
 } else {
 setActiveVendorId(null);
 }
 }
 };
 window.addEventListener('hashchange', syncDetailFromHash);
 return () => window.removeEventListener('hashchange', syncDetailFromHash);
 }, []);

 /** Open a vendor's detail page and reflect it in the URL (shareable/bookmarkable). */
 const openVendorDetail = (id: string) => {
 setActiveVendorId(id);
 navigateTo('vendors', { vendorId: id });
 };
 /** Close the detail page and clean the vendorId from the URL. */
 const closeVendorDetail = () => {
 setActiveVendorId(null);
 navigateTo('vendors');
 };

 const clearDeepLink = () => {
 setDeepLink(null);
 setDeepLinkSource('another tab');
 setFilters(DEFAULT_FILTERS);
 };
 const [filters, setFilters] = useState<FilterState>(DEFAULT_FILTERS);
 const [isFilterOpen, setIsFilterOpen] = useState(false);
 const [sortField, setSortField] = useState<keyof VendorRecord>('riskScore');
 const [sortDirection, setSortDirection] = useState<'asc' | 'desc'>('desc');

 const filterRef = useRef<HTMLDivElement>(null);
 const filterButtonRef = useRef<HTMLButtonElement>(null);

 useEffect(() => {
 const handleKeyDown = (e: KeyboardEvent) => {
 if (e.key === 'Escape' && isFilterOpen) {
 setIsFilterOpen(false);
 filterButtonRef.current?.focus();
 }
 };
 const handleClickOutside = (e: MouseEvent) => {
 if (filterRef.current && !filterRef.current.contains(e.target as Node)) {
 setIsFilterOpen(false);
 }
 };
 
 if (isFilterOpen) {
 document.addEventListener('keydown', handleKeyDown);
 document.addEventListener('mousedown', handleClickOutside);
 }
 return () => {
 document.removeEventListener('keydown', handleKeyDown);
 document.removeEventListener('mousedown', handleClickOutside);
 };
 }, [isFilterOpen]);

 const applyPresetView = (preset: 'critical_stale' | 'tier1_unreviewed' | 'breached_remediation' | 'needs_attention') => {
 if (preset === 'critical_stale') {
 setFilters({ ...DEFAULT_FILTERS, riskLevels: ['Critical', 'High'], evidenceStatuses: ['Stale', 'Missing', 'Expiring'], minOpenIssues: 1 });
 } else if (preset === 'tier1_unreviewed') {
 setFilters({ ...DEFAULT_FILTERS, criticalities: ['Tier 1'], assessmentStatuses: ['Pending Review', 'Not Started'] });
 } else if (preset === 'breached_remediation') {
 setFilters({ ...DEFAULT_FILTERS, minOpenIssues: 4 });
 } else if (preset === 'needs_attention') {
 setFilters({ ...DEFAULT_FILTERS, needsAttention: true });
 }
 };

 const filteredVendors = useMemo(() => {
 return vendors.filter(vendor => {
 const matchesSearch = filters.search === '' ||
 vendor.name.toLowerCase().includes(filters.search.toLowerCase()) || 
 vendor.code.toLowerCase().includes(filters.search.toLowerCase()) ||
 vendor.owner.toLowerCase().includes(filters.search.toLowerCase());
 const matchesRisk = filters.riskLevels.length === 0 || filters.riskLevels.includes(vendor.riskLevel);
 const matchesCriticality = filters.criticalities.length === 0 || filters.criticalities.includes(vendor.criticality);
 const matchesEvidence = filters.evidenceStatuses.length === 0 || filters.evidenceStatuses.includes(vendor.evidenceStatus);
 const matchesAssessment = filters.assessmentStatuses.length === 0 || filters.assessmentStatuses.includes(vendor.assessmentStatus);
 const matchesOwner = filters.owners.length === 0 || filters.owners.includes(vendor.owner);
 const matchesIssues = vendor.openIssues >= filters.minOpenIssues;
 const matchesStatus = filters.statuses.length === 0 || filters.statuses.includes(vendor.status);
 // "Needs attention" uses OR semantics: high/critical risk OR missing evidence.
 const matchesAttention = !filters.needsAttention ||
 (vendor.riskLevel === 'High' || vendor.riskLevel === 'Critical' || vendor.evidenceStatus === 'Missing');

 return matchesSearch && matchesRisk && matchesCriticality && matchesEvidence && matchesAssessment && matchesOwner && matchesIssues && matchesStatus && matchesAttention;
 }).sort((a, b) => {
 let aVal = a[sortField];
 let bVal = b[sortField];
 if (typeof aVal === 'string' && typeof bVal === 'string') return sortDirection === 'asc' ? aVal.localeCompare(bVal) : bVal.localeCompare(aVal);
 if (typeof aVal === 'number' && typeof bVal === 'number') return sortDirection === 'asc' ? aVal - bVal : bVal - aVal;
 return 0;
 });
 }, [vendors, filters, sortField, sortDirection]);

 const activeFilterCount = useMemo(() => {
 return filters.riskLevels.length + filters.criticalities.length + filters.evidenceStatuses.length + 
 filters.assessmentStatuses.length + filters.owners.length + (filters.minOpenIssues > 0 ? 1 : 0) + filters.statuses.length +
 (filters.needsAttention ? 1 : 0);
 }, [filters]);

 const handleSort = (field: keyof VendorRecord) => {
 if (sortField === field) {
 setSortDirection(prev => (prev === 'asc' ? 'desc' : 'asc'));
 } else {
 setSortField(field);
 setSortDirection('desc');
 }
 };

 const toggleArrayFilter = <K extends keyof FilterState>(key: K, value: any) => {
 setFilters(prev => {
 const current = prev[key] as Array<any>;
 return { ...prev, [key]: current.includes(value) ? current.filter(item => item !== value) : [...current, value] };
 });
 };

 const getRiskBadge = (risk: RiskLevel) => {
 switch (risk) {
 case 'Critical': return 'bg-risk-critical/10 text-risk-critical ';
 case 'High': return 'bg-risk-high/10 text-risk-high ';
 case 'Moderate': return 'bg-risk-moderate/10 text-risk-moderate ';
 case 'Low': return 'bg-status-success/10 text-status-success ';
 }
 };

 const getEvidenceBadge = (status: EvidenceStatus) => {
 switch (status) {
 case 'Verified': return 'text-status-success bg-status-success/10';
 case 'Expiring': return 'text-status-warning bg-status-warning/10';
 case 'Stale': return 'text-risk-moderate bg-risk-moderate/10';
 case 'Missing': return 'text-risk-critical bg-risk-critical/10';
 }
 };

 const getAriaSort = (field: keyof VendorRecord) => {
 if (sortField !== field) return 'none';
 return sortDirection === 'asc' ? 'ascending' : 'descending';
 };

 if (activeVendorId) {
 return <VendorDetailWorkspace vendorId={activeVendorId} onBack={closeVendorDetail} />;
 }

 return (
 <div className="space-y-6 max-w-7xl mx-auto pb-28 animate-in fade-in duration-300">
 
 {/* Live Region for Screen Readers */}
 <div aria-live="polite" className="sr-only">
 {filteredVendors.length} vendors matched active filters.
 </div>

 {deepLink && (
 <DeepLinkBanner
 label={deepLink.label}
 source={deepLinkSource}
 count={filteredVendors.length}
 onClear={clearDeepLink}
 />
 )}

 <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-4 border-b border-border pb-6">
 <div>
 <Eyebrow>Program</Eyebrow>
 <h1 className="text-page-title">Vendor Portfolio</h1>
 <p className="text-body-secondary mt-1">
 Enterprise risk profiles, compliance evaluations, and continuous telemetry monitoring.
 </p>
 </div>
 <div className="flex items-center gap-3">
 <Button variant="secondary" size="sm" onClick={handleExportAudit} aria-label={`Export ${filteredVendors.length} vendors to CSV`}>
 <Download aria-hidden="true" size={14} className="mr-1.5" />
 Export CSV
 </Button>
 <Button variant="primary" size="sm" onClick={() => setIsAddVendorOpen(true)}>
 <Plus aria-hidden="true" size={14} className="mr-1.5" />
 Add Vendor
 </Button>
 </div>
 </div>

 <Card className="shadow-sm relative z-30" style={{ overflow: 'visible' }}>
 <CardContent className="p-4 space-y-4">
 <div className="flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-4">
 
 <div className="flex items-center gap-2 flex-1">
 <div className="relative w-full sm:w-80">
 <label htmlFor="vendorSearch" className="sr-only">Search vendors, codes, or owners</label>
 <Search aria-hidden="true" size={16} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-text-tertiary" />
 <input
 id="vendorSearch"
 type="search"
 value={filters.search}
 onChange={(e) => setFilters(prev => ({ ...prev, search: e.target.value }))}
 placeholder="Search vendors, codes, owners..."
 className="w-full bg-surface border border-border rounded-lg pl-10 pr-4 py-2 text-sm text-text-primary placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-info shadow-sm"
 />
 </div>

 <div className="relative" ref={filterRef}>
 <Button
 ref={filterButtonRef}
 variant={activeFilterCount > 0 ? 'primary' : 'secondary'}
 size="sm"
 onClick={() => setIsFilterOpen(prev => !prev)}
 className="h-9 px-3.5 gap-2 shrink-0 font-semibold"
 aria-expanded={isFilterOpen}
 aria-controls="filterDropdown"
 aria-label={`Filters, ${activeFilterCount} active`}
 >
 <SlidersHorizontal aria-hidden="true" size={14} />
 <span>Filter</span>
 {activeFilterCount > 0 && (
 <span className="w-5 h-5 rounded-full bg-surface text-text-primary text-[10px] font-bold flex items-center justify-center ml-0.5">
 {activeFilterCount}
 </span>
 )}
 </Button>

 {isFilterOpen && (
 <div id="filterDropdown" role="dialog" aria-label="Filter Options" className="absolute left-0 sm:left-auto sm:right-0 mt-2 w-80 sm:w-96 bg-surface border border-border-strong rounded-xl shadow-2xl z-50 p-4 space-y-4 animate-in fade-in duration-150">
 <div className="flex items-center justify-between border-b border-border pb-3">
 <h2 className="text-xs font-semibold uppercase tracking-wider text-text-primary">Advanced Filters</h2>
 <button 
 onClick={() => setFilters(DEFAULT_FILTERS)}
 className="text-xs text-info hover:underline font-semibold flex items-center gap-1"
 aria-label="Reset all filters"
 >
 <RotateCcw aria-hidden="true" size={12} /> Reset all
 </button>
 </div>

 <fieldset className="space-y-1.5 border-none p-0 m-0">
 <legend className="text-[11px] font-bold uppercase tracking-wider text-text-tertiary mb-1">Risk Level</legend>
 <div className="flex flex-wrap gap-1.5">
 {(['Critical', 'High', 'Moderate', 'Low'] as RiskLevel[]).map(risk => {
 const active = filters.riskLevels.includes(risk);
 return (
 <button
 key={risk}
 type="button"
 aria-pressed={active}
 onClick={() => toggleArrayFilter('riskLevels', risk)}
 className={`px-2.5 py-1 rounded-md text-xs font-semibold border transition-colors ${
 active ? 'bg-surface-hover text-text-primary border-border-strong font-bold' : 'bg-surface text-text-secondary border-border hover:bg-surface-hover'
 }`}
 >
 {risk}
 </button>
 );
 })}
 </div>
 </fieldset>

 <fieldset className="space-y-1.5 border-none p-0 m-0">
 <legend className="text-[11px] font-bold uppercase tracking-wider text-text-tertiary mb-1">Business Criticality</legend>
 <div className="flex flex-wrap gap-1.5">
 {(['Tier 1', 'Tier 2', 'Tier 3'] as BusinessCriticality[]).map(tier => {
 const active = filters.criticalities.includes(tier);
 return (
 <button
 key={tier}
 type="button"
 aria-pressed={active}
 onClick={() => toggleArrayFilter('criticalities', tier)}
 className={`px-2.5 py-1 rounded-md text-xs font-semibold border transition-colors ${
 active ? 'bg-surface-hover text-text-primary border-border-strong font-bold' : 'bg-surface text-text-secondary border-border hover:bg-surface-hover'
 }`}
 >
 {tier}
 </button>
 );
 })}
 </div>
 </fieldset>

 <fieldset className="space-y-1.5 border-none p-0 m-0">
 <legend className="text-[11px] font-bold uppercase tracking-wider text-text-tertiary mb-1">Evidence Status</legend>
 <div className="flex flex-wrap gap-1.5">
 {(['Verified', 'Expiring', 'Stale', 'Missing'] as EvidenceStatus[]).map(status => {
 const active = filters.evidenceStatuses.includes(status);
 return (
 <button
 key={status}
 type="button"
 aria-pressed={active}
 onClick={() => toggleArrayFilter('evidenceStatuses', status)}
 className={`px-2.5 py-1 rounded-md text-xs font-semibold border transition-colors ${
 active ? 'bg-surface-hover text-text-primary border-border-strong font-bold' : 'bg-surface text-text-secondary border-border hover:bg-surface-hover'
 }`}
 >
 {status}
 </button>
 );
 })}
 </div>
 </fieldset>

 <fieldset className="space-y-1.5 border-none p-0 m-0">
 <legend className="text-[11px] font-bold uppercase tracking-wider text-text-tertiary mb-1">Owner</legend>
 <div className="flex flex-wrap gap-1.5">
 {['Sarah Jenkins', 'Marcus Chen', 'Alex Rivera'].map(owner => {
 const active = filters.owners.includes(owner);
 return (
 <button
 key={owner}
 type="button"
 aria-pressed={active}
 onClick={() => toggleArrayFilter('owners', owner)}
 className={`px-2.5 py-1 rounded-md text-xs font-semibold border transition-colors ${
 active ? 'bg-surface-hover text-text-primary border-border-strong font-bold' : 'bg-surface text-text-secondary border-border hover:bg-surface-hover'
 }`}
 >
 {owner}
 </button>
 );
 })}
 </div>
 </fieldset>

 <fieldset className="space-y-1.5 border-none p-0 m-0">
 <legend className="text-[11px] font-bold uppercase tracking-wider text-text-tertiary mb-1">Vendor Status</legend>
 <div className="flex flex-wrap gap-1.5">
 {(['Active', 'Terminated'] as const).map(status => {
 const active = filters.statuses.includes(status);
 return (
 <button
 key={status}
 type="button"
 aria-pressed={active}
 onClick={() => toggleArrayFilter('statuses', status)}
 className={`px-2.5 py-1 rounded-md text-xs font-semibold border transition-colors ${
 active ? 'bg-surface-hover text-text-primary border-border-strong font-bold' : 'bg-surface text-text-secondary border-border hover:bg-surface-hover'
 }`}
 >
 {status}
 </button>
 );
 })}
 </div>
 </fieldset>

 <div className="pt-2 border-t border-border flex justify-end">
 <Button size="sm" onClick={() => setIsFilterOpen(false)} className="w-full">
 Apply Filters ({filteredVendors.length} matches)
 </Button>
 </div>
 </div>
 )}
 </div>
 </div>

 <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Preset Views">
 <span className="text-[11px] text-text-tertiary font-bold uppercase mr-1" aria-hidden="true">Views:</span>
 <button
 type="button"
 onClick={() => applyPresetView('critical_stale')}
 className="px-2.5 py-1 rounded-md text-xs font-medium bg-base border border-border hover:border-border-strong text-text-secondary transition-colors focus:ring-2 focus:ring-info outline-none"
 >
 Critical + Stale Evidence
 </button>
 <button
 type="button"
 onClick={() => applyPresetView('tier1_unreviewed')}
 className="px-2.5 py-1 rounded-md text-xs font-medium bg-base border border-border hover:border-border-strong text-text-secondary transition-colors focus:ring-2 focus:ring-info outline-none"
 >
 Tier 1 Unreviewed
 </button>
 <button
 type="button"
 onClick={() => applyPresetView('needs_attention')}
 className="px-2.5 py-1 rounded-md text-xs font-medium bg-base border border-border hover:border-border-strong text-text-secondary transition-colors focus:ring-2 focus:ring-info outline-none"
 title="High/critical risk or missing evidence"
 >
 Needs Attention
 </button>
 </div>
 </div>

 {activeFilterCount > 0 && (
 <div className="flex flex-wrap items-center gap-2 pt-3 border-t border-border-subtle" aria-label="Active Filters">
 <span className="text-xs font-semibold uppercase tracking-wider text-text-tertiary mr-1">Active:</span>
 
 {filters.riskLevels.map(risk => (
 <span key={risk} className="inline-flex items-center gap-1 bg-surface border border-border-strong px-2 py-0.5 rounded-md text-xs font-semibold text-text-primary">
 Risk: {risk}
 <button type="button" aria-label={`Remove Risk filter: ${risk}`} onClick={() => toggleArrayFilter('riskLevels', risk)} className="text-text-tertiary hover:text-text-primary focus:ring-2 outline-none rounded"><X size={12} /></button>
 </span>
 ))}

 {filters.criticalities.map(tier => (
 <span key={tier} className="inline-flex items-center gap-1 bg-surface border border-border-strong px-2 py-0.5 rounded-md text-xs font-semibold text-text-primary">
 Criticality: {tier}
 <button type="button" aria-label={`Remove Criticality filter: ${tier}`} onClick={() => toggleArrayFilter('criticalities', tier)} className="text-text-tertiary hover:text-text-primary focus:ring-2 outline-none rounded"><X size={12} /></button>
 </span>
 ))}

 {filters.evidenceStatuses.map(status => (
 <span key={status} className="inline-flex items-center gap-1 bg-surface border border-border-strong px-2 py-0.5 rounded-md text-xs font-semibold text-text-primary">
 Evidence: {status}
 <button type="button" aria-label={`Remove Evidence filter: ${status}`} onClick={() => toggleArrayFilter('evidenceStatuses', status)} className="text-text-tertiary hover:text-text-primary focus:ring-2 outline-none rounded"><X size={12} /></button>
 </span>
 ))}

 {filters.owners.map(owner => (
 <span key={owner} className="inline-flex items-center gap-1 bg-surface border border-border-strong px-2 py-0.5 rounded-md text-xs font-semibold text-text-primary">
 Owner: {owner}
 <button type="button" aria-label={`Remove Owner filter: ${owner}`} onClick={() => toggleArrayFilter('owners', owner)} className="text-text-tertiary hover:text-text-primary focus:ring-2 outline-none rounded"><X size={12} /></button>
 </span>
 ))}

 {filters.needsAttention && (
 <span className="inline-flex items-center gap-1 bg-surface border border-border-strong px-2 py-0.5 rounded-md text-xs font-semibold text-text-primary">
 Needs attention
 <button type="button" aria-label="Remove Needs attention filter" onClick={() => setFilters(prev => ({ ...prev, needsAttention: false }))} className="text-text-tertiary hover:text-text-primary focus:ring-2 outline-none rounded"><X size={12} /></button>
 </span>
 )}

 <button
 type="button"
 onClick={() => setFilters(DEFAULT_FILTERS)}
 className="text-xs font-semibold text-info hover:underline ml-2 focus:ring-2 outline-none rounded px-1"
 >
 Clear all
 </button>
 </div>
 )}

 </CardContent>
 </Card>

 <Card className="overflow-hidden shadow-sm">
 <div className="overflow-x-auto">
 <table className="w-full text-left text-sm whitespace-nowrap">
 <caption className="sr-only">Vendor Details and Risk Profiles</caption>
 <thead className="text-label">
 <tr>
 <th scope="col" aria-sort={getAriaSort('name')} className="py-0 px-0 font-bold uppercase tracking-wider text-[11px]">
 <button type="button" className="w-full h-full flex items-center gap-1.5 py-3 px-5 focus:bg-surface focus:outline-none focus:ring-2 focus:ring-inset focus:ring-info" onClick={() => handleSort('name')}>
 <span>Vendor</span>
 <ArrowUpDown aria-hidden="true" size={12} className="text-text-tertiary" />
 </button>
 </th>
 <th scope="col" aria-sort={getAriaSort('criticality')} className="py-0 px-0 font-bold uppercase tracking-wider text-[11px]">
 <button type="button" className="w-full h-full flex items-center gap-1.5 py-3 px-4 focus:bg-surface focus:outline-none focus:ring-2 focus:ring-inset focus:ring-info" onClick={() => handleSort('criticality')}>
 <span>Criticality</span>
 <ArrowUpDown aria-hidden="true" size={12} className="text-text-tertiary" />
 </button>
 </th>
 <th scope="col" aria-sort={getAriaSort('riskScore')} className="py-0 px-0 font-bold uppercase tracking-wider text-[11px]">
 <button type="button" title="Computed automatically from the risk score (0–100, higher = riskier): 80+ Critical, 60–79 High, 30–59 Moderate, below 30 Low. You can't set it by hand — lower the underlying risk to move it." className="w-full h-full flex items-center gap-1.5 py-3 px-4 focus:bg-surface focus:outline-none focus:ring-2 focus:ring-inset focus:ring-info" onClick={() => handleSort('riskScore')}>
 <span>Risk / Exposure</span>
 <ArrowUpDown aria-hidden="true" size={12} className="text-text-tertiary" />
 </button>
 </th>
 <th scope="col" className="py-3 px-4 font-bold uppercase tracking-wider text-[11px]">
 Evidence Status
 </th>
 <th scope="col" className="py-3 px-4 font-bold uppercase tracking-wider text-[11px]">
 Assessment
 </th>
 <th scope="col" aria-sort={getAriaSort('openIssues')} className="py-0 px-0 font-bold uppercase tracking-wider text-[11px]">
 <button type="button" className="w-full h-full flex items-center gap-1.5 py-3 px-4 focus:bg-surface focus:outline-none focus:ring-2 focus:ring-inset focus:ring-info" onClick={() => handleSort('openIssues')}>
 <span>Open Issues</span>
 <ArrowUpDown aria-hidden="true" size={12} className="text-text-tertiary" />
 </button>
 </th>
 <th scope="col" className="py-3 px-4 font-bold uppercase tracking-wider text-[11px]">
 Owner
 </th>
 <th scope="col" className="py-3 px-5 font-bold uppercase tracking-wider text-[11px]">
 Last Material Change
 </th>
 </tr>
 </thead>
 <tbody className="bg-surface">
 {filteredVendors.length > 0 ? (
 filteredVendors.map((vendor) => (
 <tr
 key={vendor.id}
 ref={(deepLink && deepLink.highlight && vendor.name.toLowerCase().includes(deepLink.highlight.toLowerCase())) || vendor.id === justAddedId ? highlightRowRef : undefined}
 onClick={() => openVendorDetail(vendor.id)}
 onKeyDown={(e) => {
 if (e.key === 'Enter' || e.key === ' ') {
 e.preventDefault();
 openVendorDetail(vendor.id);
 }
 }}
 tabIndex={0}
 role="button"
 aria-label={`View workspace for ${vendor.name}`}
 className={`transition-colors cursor-pointer group hover:bg-surface-hover focus:bg-surface-hover focus:outline-none focus:ring-2 focus:ring-inset focus:ring-info ${((deepLink && deepLink.highlight && vendor.name.toLowerCase().includes(deepLink.highlight.toLowerCase())) || vendor.id === justAddedId) ? 'bg-info/10 shadow-[inset_3px_0_0_0_var(--color-info)]' : ''}`}
 >
 <td className="py-3.5 px-5">
 <div className="font-bold text-text-primary group-hover:text-info transition-colors flex items-center gap-2">
 {vendor.name}
 {vendor.status === 'Terminated' && (
 <span className="text-[10px] font-semibold uppercase tracking-wider px-1.5 py-0.5 rounded bg-surface text-text-tertiary border border-border">Terminated</span>
 )}
 </div>
 <div className="text-[11px] font-mono text-text-tertiary mt-0.5">
 {vendor.code} • <span className="text-status-success font-semibold">{vendor.monitoringStatus}</span>
 </div>
 </td>

 <td className="py-3.5 px-4">
 <span className="text-xs font-bold text-text-secondary bg-base px-2 py-0.5 rounded border border-border">
 {vendor.criticality}
 </span>
 </td>

 <td className="py-3.5 px-4">
 <div className="flex items-center gap-2.5">
 {vendor.assessmentStatus === 'Not Started' ? (
 <span className="inline-flex justify-center w-[74px] text-[10px] font-semibold uppercase tracking-wider px-2 py-0.5 rounded bg-surface text-text-tertiary border border-border">
 <span className="sr-only">Risk Level: </span>Not assessed
 </span>
 ) : (
 <>
 <span className={`inline-flex justify-center w-[74px] text-[10px] font-semibold uppercase tracking-wider px-2 py-0.5 rounded ${getRiskBadge(vendor.riskLevel)}`}>
 <span className="sr-only">Risk Level: </span>{vendor.riskLevel}
 </span>
 <ScoreRing score={vendor.riskScore} size={38} />
 </>
 )}
 </div>
 </td>

 <td className="py-3.5 px-4">
 <span className={`inline-flex items-center gap-1 text-[11px] font-semibold px-2 py-0.5 rounded ${getEvidenceBadge(vendor.evidenceStatus)}`}>
 <span className="sr-only">Status: </span>{vendor.evidenceStatus}
 </span>
 </td>

 <td className="py-3.5 px-4">
 <span className="text-xs text-text-secondary font-medium">
 {vendor.assessmentStatus}
 </span>
 </td>

 <td className="py-3.5 px-4">
 <span className={`text-xs font-bold tabular-nums px-2 py-0.5 rounded ${
 vendor.openIssues > 4 ? 'bg-risk-critical/10 text-risk-critical' : vendor.openIssues > 0 ? 'bg-status-warning/10 text-status-warning' : 'text-text-tertiary'
 }`}>
 {vendor.openIssues} <span className="sr-only">active issues</span>{vendor.openIssues > 0 ? ' active' : ''}
 </span>
 </td>

 <td className="py-3.5 px-4">
 <div className="flex items-center gap-2">
 <div aria-hidden="true" className="w-5 h-5 rounded-full bg-base border border-border-strong flex items-center justify-center text-[9px] font-bold text-text-secondary shrink-0">
 {vendor.owner.charAt(0)}
 </div>
 <span className="text-xs text-text-secondary font-medium">{vendor.owner}</span>
 </div>
 </td>

 <td className="py-3.5 px-5 text-xs text-text-tertiary font-mono tabular-nums">
 <div className="flex items-center justify-between">
 <span>{vendor.lastMaterialChange}</span>
 <button 
 type="button"
 className="inline-flex items-center gap-1.5 px-2.5 py-1 text-[11px] font-semibold text-accent bg-accent-muted hover:bg-accent-muted rounded-md transition-colors opacity-0 group-hover:opacity-100 group-focus:opacity-100 focus:opacity-100 focus:ring-2 focus:ring-accent outline-none "
 onClick={(e) => {
 e.stopPropagation();
 navigateTo('ai-playground', { vendorName: vendor.name });
 }}
 onKeyDown={(e) => {
 // Prevent row click when pressing enter on this specific button
 if (e.key === 'Enter' || e.key === ' ') {
 e.stopPropagation();
 }
 }}
 >
 <ShieldAlert aria-hidden="true" size={12} className="opacity-70" /> Explain Exposure
 </button>
 </div>
 </td>
 </tr>
 ))
 ) : (
 <tr>
 <td colSpan={8} className="py-12 text-center text-sm text-text-tertiary">
 No vendors match your active filter criteria.
 </td>
 </tr>
 )}
 </tbody>
 </table>
 </div>
 </Card>

 <Dialog open={isAddVendorOpen} onClose={() => setIsAddVendorOpen(false)} title="Add Vendor" maxWidth="max-w-md">
 <div className="p-6 space-y-5">
 <div className="flex items-start justify-between">
 <div>
 <h2 id="add-vendor-title" className="text-base font-bold text-text-primary m-0">Add Vendor</h2>
 <p className="text-sm text-text-secondary mt-1">Register a new vendor in the portfolio.</p>
 </div>
 <button type="button" onClick={() => setIsAddVendorOpen(false)} className="p-1.5 rounded-lg hover:bg-surface-hover text-text-tertiary hover:text-text-primary focus:outline-none focus:ring-2 focus:ring-info" aria-label="Close">
 <X size={18} />
 </button>
 </div>
 <div className="space-y-4">
 <div>
 <label htmlFor="vendor-name" className="block text-xs font-semibold text-text-secondary mb-1.5">Vendor name</label>
 <input id="vendor-name" type="text" value={newVendorName} onChange={(e) => setNewVendorName(e.target.value)} placeholder="e.g. Acme Cloud Services" className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-info" />
 </div>
 <div className="p-3.5 rounded-xl border border-border bg-base space-y-3">
 <p className="text-xs font-semibold text-text-primary m-0">Quick tiering <span className="font-normal text-text-tertiary">— answer 2 questions, we'll suggest a tier</span></p>
 <div>
 <label htmlFor="tier-data" className="block text-xs font-medium text-text-secondary mb-1.5">What data will they access?</label>
 <select id="tier-data" value={tierDataAccess} onChange={(e) => { setTierDataAccess(e.target.value); const t = e.target.value === 'pii' ? 'Tier 1' : e.target.value === 'internal' ? 'Tier 2' : e.target.value === 'public' ? 'Tier 3' : null; if (t) setNewVendorTier(t as 'Tier 1' | 'Tier 2' | 'Tier 3'); }} className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:ring-2 focus:ring-info">
 <option value="">Select...</option>
 <option value="pii">Customer PII, financial, or health data</option>
 <option value="internal">Internal business data only</option>
 <option value="public">Public data or no data access</option>
 </select>
 </div>
 <div>
 <label htmlFor="tier-crit" className="block text-xs font-medium text-text-secondary mb-1.5">If they went down for 24 hours?</label>
 <select id="tier-crit" value={tierCriticality} onChange={(e) => { setTierCriticality(e.target.value); const t = e.target.value === 'stops' ? 'Tier 1' : e.target.value === 'major' ? 'Tier 2' : e.target.value === 'minor' ? 'Tier 3' : null; if (t) { const cur = newVendorTier; const scores: Record<string, number> = { 'Tier 1': 3, 'Tier 2': 2, 'Tier 3': 1 }; if (scores[t] > scores[cur]) setNewVendorTier(t as 'Tier 1' | 'Tier 2' | 'Tier 3'); } }} className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:ring-2 focus:ring-info">
 <option value="">Select...</option>
 <option value="stops">Business stops completely</option>
 <option value="major">Major disruption</option>
 <option value="minor">Minor inconvenience</option>
 </select>
 </div>
 {suggestedTier && (
 <p className="text-xs text-info font-medium m-0">Suggested: <strong>{suggestedTier}</strong> based on your answers. You can override below.</p>
 )}
 </div>
 <div>
 <label htmlFor="vendor-tier" className="block text-xs font-semibold text-text-secondary mb-1.5">Criticality tier</label>
 <select id="vendor-tier" value={newVendorTier} onChange={(e) => setNewVendorTier(e.target.value as 'Tier 1' | 'Tier 2' | 'Tier 3')} className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:ring-2 focus:ring-info">
 <option value="Tier 1">Tier 1 — Critical</option>
 <option value="Tier 2">Tier 2 — Important</option>
 <option value="Tier 3">Tier 3 — Standard</option>
 </select>
 </div>
 <div>
 <label htmlFor="vendor-owner" className="block text-xs font-semibold text-text-secondary mb-1.5">Owner</label>
 <input id="vendor-owner" type="text" value={newVendorOwner} onChange={(e) => setNewVendorOwner(e.target.value)} placeholder="e.g. Sarah Jenkins" className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-info" />
 </div>
 <div>
 <label htmlFor="vendor-website" className="block text-xs font-semibold text-text-secondary mb-1.5">Website</label>
 <input id="vendor-website" type="text" value={newVendorWebsite} onChange={(e) => setNewVendorWebsite(e.target.value)} placeholder="e.g. acmecloud.com" className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-info" />
 </div>
 <div>
 <label htmlFor="vendor-desc" className="block text-xs font-semibold text-text-secondary mb-1.5">What do they do? <span className="font-normal text-text-tertiary">(optional)</span></label>
 <input id="vendor-desc" type="text" value={newVendorDesc} onChange={(e) => setNewVendorDesc(e.target.value)} placeholder="e.g. Cloud hosting and CDN services" className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-info" />
 </div>
 </div>
 <div className="flex justify-end gap-2 pt-1">
 <Button variant="secondary" size="sm" onClick={() => setIsAddVendorOpen(false)}>Cancel</Button>
 <Button variant="primary" size="sm" onClick={handleAddVendor} disabled={!newVendorName.trim()}>Add Vendor</Button>
 </div>
 </div>
 </Dialog>

 </div>
 );
}