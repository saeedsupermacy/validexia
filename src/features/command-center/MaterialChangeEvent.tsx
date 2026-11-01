// src/features/command-center/MaterialChangeEvent.tsx
import { 
 ShieldAlert, FileCheck, AlertTriangle, Activity, 
 Network, Scale, Wrench, Clock, ArrowUpRight 
} from 'lucide-react';
import { navigateTo } from '../../shared/navigation';

export type ChangeEventType = 
 | 'risk_change' 
 | 'evidence_change' 
 | 'certificate_expiry' 
 | 'assessment_change' 
 | 'monitoring_signal' 
 | 'dependency_change' 
 | 'contract_obligation' 
 | 'remediation_status';

export type ChangeSeverity = 'critical' | 'high' | 'moderate' | 'low' | 'info';

export interface MaterialChangeEventData {
 id: string;
 timestamp: string;
 occurredAt?: number;
 objectName: string;
 objectType: string;
 eventType: ChangeEventType;
 changeSummary: string;
 severity: ChangeSeverity;
 contextReason: string;
 actionLabel?: string;
 actionUrl?: string;
}

interface MaterialChangeEventProps {
 event: MaterialChangeEventData;
}

function getEventIcon(type: ChangeEventType) {
 switch (type) {
 case 'risk_change': return <Activity aria-hidden="true" size={15} strokeWidth={2} />;
 case 'evidence_change': return <FileCheck aria-hidden="true" size={15} strokeWidth={2} />;
 case 'certificate_expiry': return <AlertTriangle aria-hidden="true" size={15} strokeWidth={2} />;
 case 'assessment_change': return <FileCheck aria-hidden="true" size={15} strokeWidth={2} />;
 case 'monitoring_signal': return <ShieldAlert aria-hidden="true" size={15} strokeWidth={2} />;
 case 'dependency_change': return <Network aria-hidden="true" size={15} strokeWidth={2} />;
 case 'contract_obligation': return <Scale aria-hidden="true" size={15} strokeWidth={2} />;
 case 'remediation_status': return <Wrench aria-hidden="true" size={15} strokeWidth={2} />;
 default: return <Clock aria-hidden="true" size={15} strokeWidth={2} />;
 }
}

function getSeverityStyles(severity: ChangeSeverity) {
 switch (severity) {
 case 'critical': return 'bg-risk-critical ring-risk-critical/30';
 case 'high': return 'bg-risk-high ring-risk-high/30';
 case 'moderate': return 'bg-risk-moderate ring-risk-moderate/30';
 case 'low': return 'bg-status-info ring-status-info/30';
 case 'info': return 'bg-text-tertiary ring-border-strong';
 }
}

const EVENT_DESTINATIONS: Record<string, 'risk-events' | 'evidence' | 'assessments' | 'monitoring' | 'fourth-parties' | 'vendors' | 'remediation'> = {
 risk_change: 'risk-events',
 evidence_change: 'evidence',
 certificate_expiry: 'evidence',
 assessment_change: 'assessments',
 monitoring_signal: 'monitoring',
 dependency_change: 'fourth-parties',
 contract_obligation: 'vendors',
 remediation_status: 'remediation',
};

export function MaterialChangeEvent({ event }: MaterialChangeEventProps) {
 const dotColorClass = getSeverityStyles(event.severity);
 const handleCardClick = () => {
 const dest = EVENT_DESTINATIONS[event.eventType] || 'risk-events';
 navigateTo(dest, { vendorName: event.objectName });
 };

 return (
 <div className="relative flex items-start gap-4 pb-6 last:pb-0 group">
 <div className="relative z-10 flex items-center justify-center w-8 h-8 rounded-full bg-surface border-2 border-border-strong shadow-sm shrink-0 mt-0.5 text-text-primary group-hover:border-info transition-colors">
 {getEventIcon(event.eventType)}
 <span aria-hidden="true" className={`absolute -top-0.5 -right-0.5 w-2.5 h-2.5 rounded-full ring-2 ring-surface ${dotColorClass}`} />
 </div>

 <button
 type="button"
 onClick={handleCardClick}
 className="flex-1 text-left bg-surface border border-border rounded-xl p-4 shadow-sm hover:border-info/50 hover:shadow-sm transition-all focus:outline-none focus:ring-2 focus:ring-info cursor-pointer"
 aria-label={`${event.changeSummary} for ${event.objectName}. View details.`}
 >
 <div className="flex flex-wrap items-center justify-between gap-2 mb-1.5">
 <div className="flex items-center gap-2">
 <span className="text-xs font-bold text-text-primary tracking-wide uppercase">
 {event.objectName}
 </span>
 <span className="text-text-tertiary text-xs" aria-hidden="true">•</span>
 <span className="text-[11px] font-semibold text-text-tertiary uppercase tracking-wider">
 {event.objectType}
 </span>
 </div>
 <span className="text-xs font-mono text-text-tertiary tabular-nums bg-base px-2 py-0.5 rounded border border-border">
 {event.timestamp}
 </span>
 </div>

 <p className="text-sm font-semibold text-text-primary mb-2 leading-snug">
 {event.changeSummary}
 </p>

 <p className="text-xs text-text-secondary leading-relaxed bg-base p-3 rounded-lg border border-border-subtle mb-3">
 <strong className="text-text-primary font-semibold">Context: </strong>
 {event.contextReason}
 </p>

 {event.actionLabel && (
 <div className="flex items-center justify-end pt-2 border-t border-border-subtle">
 <span
 className="inline-flex items-center gap-1.5 text-xs font-semibold text-info"
 aria-hidden="true"
 >
 <span>{event.actionLabel}</span>
 <ArrowUpRight aria-hidden="true" size={13} strokeWidth={2.25} />
 </span>
 </div>
 )}
 </button>
 </div>
 );
}