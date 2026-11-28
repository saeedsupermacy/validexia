import { useState, useEffect } from "react";
import { registerNavigator, parseHash, navigateTo, stashHashParams, ViewKey as NavViewKey } from "./shared/navigation";
import { AppShell } from "./components/layout/AppShell";
import { Toaster } from "./shared/ui/Toaster";
import { VendorsTable } from "./components/VendorsTable";
import { InternalSystemsWorkspace } from "./features/internal-systems/InternalSystemsWorkspace";
import { AccessGrantsWorkspace } from "./features/access-grants/AccessGrantsWorkspace";
import { CommandCenter } from "./features/command-center/CommandCenter";
import { EvidenceWorkspace } from "./features/evidence/EvidenceWorkspace";
import { EvidenceInbox } from "./features/evidence/EvidenceInbox";
import { ExtractedFactsWorkspace } from "./features/evidence/ExtractedFactsWorkspace";
import { EvidenceConfidenceView } from "./features/evidence/EvidenceConfidenceView";
import { AssessmentsWorkspace } from "./features/assessments/AssessmentsWorkspace";
import { RiskEventsWorkspace } from "./features/risk-events/RiskEventsWorkspace";
import { VendorPortalWorkspace } from "./features/vendor-portal/VendorPortalWorkspace";
import { MonitoringWorkspace } from "./features/monitoring/MonitoringWorkspace";
import { AIPlaygroundWorkspace } from "./features/ai/AIPlaygroundWorkspace";
import { RemediationWorkspace } from "./features/remediation/RemediationWorkspace";
import { DependenciesWorkspace } from "./features/dependencies/DependenciesWorkspace";
import { FourthPartiesWorkspace } from "./features/fourth-parties/FourthPartiesWorkspace";
import { DecisionsWorkspace } from "./features/decisions/DecisionsWorkspace";
import { AuditLogWorkspace } from "./features/audit/AuditLogWorkspace";
import { ReportsWorkspace } from "./features/reports/ReportsWorkspace";
import { IntegrationsWorkspace } from "./features/integrations/IntegrationsWorkspace";
import { TeamWorkspace } from "./features/team/TeamWorkspace";
import { SettingsWorkspace } from "./features/settings/SettingsWorkspace";
import { FileCheck, Inbox, Layers, ActivitySquare, ChevronRight, Home } from "lucide-react";

type ViewKey = NavViewKey;

const HIERARCHY = {
 'Risk Operations': ['command', 'risk-events', 'monitoring', 'remediation'],
 'Vendor Portfolio': ['vendors', 'internal-systems', 'access-grants', 'dependencies', 'fourth-parties', 'vendor-portal'],
 'Assurance & Audits': ['assessments', 'decisions', 'audit-log', 'reports'],
 'Evidence Engine': ['evidence', 'evidence-inbox', 'extracted-facts', 'confidence'],
 'System': ['integrations', 'team', 'settings', 'ai-playground']
};

export default function App() {
 // Read the URL hash in the initializer so the correct view renders on first
 // paint (no flash of Command Center, no effect-timing dependency).
 const [currentView, setCurrentView] = useState<ViewKey>(() => {
 try {
 const parsed = parseHash();
 if (parsed) {
 stashHashParams(parsed.params);
 return parsed.view;
 }
 } catch { /* noop — default view */ }
 return 'command';
 });

 useEffect(() => {
 registerNavigator((view: ViewKey) => {
 setCurrentView(view);
 });
 // Back/forward button support
 const onPopState = () => {
 const p = parseHash();
 if (p) {
 stashHashParams(p.params);
 setCurrentView(p.view);
 }
 };
 window.addEventListener('popstate', onPopState);
 return () => window.removeEventListener('popstate', onPopState);
 }, []);

 const getPageInfo = (view: ViewKey) => {
 const titles: Record<ViewKey, string> = {
 'command': "Command Center",
 'vendors': "Vendors",
 'internal-systems': "Internal Systems",
 'access-grants': "Access Grants",
 'assessments': "Assessments",
 'evidence': "Evidence Repository",
 'evidence-inbox': "Intake & Processing",
 'extracted-facts': "Extracted Facts",
 'confidence': "Evidence Confidence",
 'risk-events': "Risk Events",
 'monitoring': "Continuous Monitoring",
 'ai-playground': "AI Assistant",
 'remediation': "Remediation",
 'dependencies': "Dependencies",
 'fourth-parties': "Fourth Parties",
 'decisions': "Decisions",
 'audit-log': "Audit Log",
 'reports': "Reports",
 'integrations': "Integrations",
 'team': "Team & Roles",
 'settings': "Settings",
 'vendor-portal': "Vendor Portal",
 };
 const category = Object.entries(HIERARCHY).find(([_, views]) => views.includes(view))?.[0] || 'Platform';
 return { title: titles[view], category };
 };

 const { title: currentTitle } = getPageInfo(currentView);
 const isEvidenceView = HIERARCHY['Evidence Engine'].includes(currentView);

 return (
 <>
 <AppShell 
 currentPageTitle={currentTitle}
 currentView={isEvidenceView ? 'evidence' : currentView}
 onSelectView={(view) => navigateTo(view as ViewKey)}
 >
 <div className="flex items-center gap-1.5 text-xs font-medium text-text-tertiary mb-6">
 <button type="button" onClick={() => navigateTo('command')} className="hover:text-text-primary transition-colors flex items-center rounded-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-info">
 <Home aria-hidden="true" size={12} strokeWidth={1.5} className="mr-1" /> Validexia
 </button>
 <ChevronRight aria-hidden="true" size={12} strokeWidth={1.5} />
 <span className="text-text-primary" aria-current="page">{currentTitle}</span>
 </div>

 {isEvidenceView && (
 <div className="inline-flex items-center gap-1 p-1 mb-8 bg-surface-hover/50 border border-border rounded-xl overflow-x-auto w-full md:w-auto" role="tablist" aria-label="Evidence Sub-Navigation">
 <button
 type="button" role="tab" aria-selected={currentView === 'evidence'}
 onClick={() => navigateTo('evidence')}
 className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all whitespace-nowrap focus:outline-none focus:ring-2 focus:ring-info focus:ring-offset-1 ${
 currentView === 'evidence' ? 'bg-surface text-text-primary shadow-sm ring-1 ring-border' : 'text-text-secondary hover:text-text-primary hover:bg-surface-hover/50'
 }`}
 >
 <FileCheck aria-hidden="true" size={14} strokeWidth={1.5} className={currentView === 'evidence' ? 'text-info' : 'text-text-tertiary'} />
 <span>Repository</span>
 </button>
 
 <button
 type="button" role="tab" aria-selected={currentView === 'evidence-inbox'}
 onClick={() => navigateTo('evidence-inbox')}
 className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all whitespace-nowrap focus:outline-none focus:ring-2 focus:ring-info focus:ring-offset-1 ${
 currentView === 'evidence-inbox' ? 'bg-surface text-text-primary shadow-sm ring-1 ring-border' : 'text-text-secondary hover:text-text-primary hover:bg-surface-hover/50'
 }`}
 >
 <Inbox aria-hidden="true" size={14} strokeWidth={1.5} className={currentView === 'evidence-inbox' ? 'text-info' : 'text-text-tertiary'} />
 <span>Intake & Processing</span>
 </button>

 <button
 type="button" role="tab" aria-selected={currentView === 'extracted-facts'}
 onClick={() => navigateTo('extracted-facts')}
 className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all whitespace-nowrap focus:outline-none focus:ring-2 focus:ring-info focus:ring-offset-1 ${
 currentView === 'extracted-facts' ? 'bg-surface text-text-primary shadow-sm ring-1 ring-border' : 'text-text-secondary hover:text-text-primary hover:bg-surface-hover/50'
 }`}
 >
 <Layers aria-hidden="true" size={14} strokeWidth={1.5} className={currentView === 'extracted-facts' ? 'text-info' : 'text-text-tertiary'} />
 <span>Extracted Facts</span>
 </button>

 <button
 type="button" role="tab" aria-selected={currentView === 'confidence'}
 onClick={() => navigateTo('confidence')}
 className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all whitespace-nowrap focus:outline-none focus:ring-2 focus:ring-info focus:ring-offset-1 ${
 currentView === 'confidence' ? 'bg-surface text-text-primary shadow-sm ring-1 ring-border' : 'text-text-secondary hover:text-text-primary hover:bg-surface-hover/50'
 }`}
 >
 <ActivitySquare aria-hidden="true" size={14} strokeWidth={1.5} className={currentView === 'confidence' ? 'text-accent' : 'text-text-tertiary'} />
 <span>Confidence Matrix</span>
 </button>
 </div>
 )}

 {currentView === 'command' && <CommandCenter />}
 {currentView === 'vendors' && <VendorsTable />}
 {currentView === 'internal-systems' && <InternalSystemsWorkspace />}
 {currentView === 'access-grants' && <AccessGrantsWorkspace />}
 {currentView === 'assessments' && <AssessmentsWorkspace />}
 {currentView === 'evidence' && <EvidenceWorkspace />}
 {currentView === 'evidence-inbox' && <EvidenceInbox />}
 {currentView === 'extracted-facts' && <ExtractedFactsWorkspace />}
 {currentView === 'confidence' && <EvidenceConfidenceView />}
 {currentView === 'risk-events' && <RiskEventsWorkspace />}
 {currentView === 'vendor-portal' && <VendorPortalWorkspace />}
 {currentView === 'monitoring' && <MonitoringWorkspace />}
 {currentView === 'ai-playground' && <AIPlaygroundWorkspace />}
 {currentView === 'remediation' && <RemediationWorkspace />}
 {currentView === 'dependencies' && <DependenciesWorkspace />}
 {currentView === 'fourth-parties' && <FourthPartiesWorkspace />}
 {currentView === 'decisions' && <DecisionsWorkspace />}
 {currentView === 'audit-log' && <AuditLogWorkspace />}
 {currentView === 'reports' && <ReportsWorkspace />}
 {currentView === 'integrations' && <IntegrationsWorkspace />}
 {currentView === 'team' && <TeamWorkspace />}
 {currentView === 'settings' && <SettingsWorkspace />}
 </AppShell>
 <Toaster />
 </>
 );
}