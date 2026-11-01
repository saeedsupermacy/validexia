// src/features/integrations/IntegrationsWorkspace.tsx
import { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import {
 CheckCircle2, AlertTriangle, XCircle, Settings, Shield, Lock, Cpu, Database, FileText, MessageSquare, Ticket, ShoppingBag, X, ArrowRight
} from 'lucide-react';
import { Card, CardContent, Button, Eyebrow} from '../../shared/ui/core';
import { NetworkErrorBanner, PermissionErrorModal, ExpiredSessionModal } from '../../shared/ui/ErrorStates';

export type IntegrationCategory = 
 | 'Identity' 
 | 'Procurement' 
 | 'Ticketing' 
 | 'Communication' 
 | 'Security' 
 | 'GRC' 
 | 'Contracts' 
 | 'Data sources';

export type ConnectionStatus = 'Connected' | 'Not connected' | 'Needs attention' | 'Configuration';

export interface IntegrationCard {
 id: string;
 name: string;
 category: IntegrationCategory;
 description: string;
 status: ConnectionStatus;
 lastSync?: string;
 authType: string;
 backendSupported: boolean;
}

const DEV_INTEGRATIONS: IntegrationCard[] = [
 {
 id: 'int-okta',
 name: 'Okta Enterprise SSO & IAM',
 category: 'Identity',
 description: 'Sync administrative accounts, enforce MFA standards, and audit Single Sign-On session tokens.',
 status: 'Connected',
 lastSync: '10 mins ago',
 authType: 'OAuth 2.0 / SCIM',
 backendSupported: true
 },
 {
 id: 'int-azure',
 name: 'Azure Active Directory (Entra ID)',
 category: 'Identity',
 description: 'Import user groups and verify employee identity token lifecycles.',
 status: 'Needs attention',
 lastSync: '3 hours ago (Token Expiring)',
 authType: 'Microsoft Graph API',
 backendSupported: true
 },
 {
 id: 'int-lever',
 name: 'Lever & Workday Procurement',
 category: 'Procurement',
 description: 'Automatically onboard vendor contracts and flag unauthorized software purchases.',
 status: 'Not connected',
 authType: 'REST API Key',
 backendSupported: false
 },
 {
 id: 'int-jira',
 name: 'Jira Cloud & Service Management',
 category: 'Ticketing',
 description: 'Automatically create and track Remediation Tickets (CAPs) linked to security findings.',
 status: 'Connected',
 lastSync: '2 mins ago',
 authType: 'Atlassian Webhooks',
 backendSupported: true
 },
 {
 id: 'int-slack',
 name: 'Slack Enterprise Grid',
 category: 'Communication',
 description: 'Broadcast risk events, telemetry threshold breaches, and urgent remediation pings to channel leads.',
 status: 'Connected',
 lastSync: 'Just now',
 authType: 'Bot Token / Webhooks',
 backendSupported: true
 },
 {
 id: 'int-crowdstrike',
 name: 'CrowdStrike Falcon Endpoint Security',
 category: 'Security',
 description: 'Ingest endpoint threat telemetry and vendor host anomaly feeds.',
 status: 'Not connected',
 authType: 'OAuth 2.0 API Client',
 backendSupported: false
 },
 {
 id: 'int-archer',
 name: 'RSA Archer GRC',
 category: 'GRC',
 description: 'Sync enterprise control frameworks, audit checklists, and risk registers.',
 status: 'Needs attention',
 lastSync: '2 days ago (Sync Failure)',
 authType: 'SOAP / REST XML',
 backendSupported: false
 },
 {
 id: 'int-ironclad',
 name: 'Ironclad CLM Contracts',
 category: 'Contracts',
 description: 'Extract data privacy clauses, liability caps, and termination conditions from vendor MSAs.',
 status: 'Not connected',
 authType: 'Bearer Token',
 backendSupported: false
 },
 {
 id: 'int-snowflake',
 name: 'Snowflake Enterprise Data Warehouse',
 category: 'Data sources',
 description: 'Stream raw audit logs, transaction summaries, and compliance datasets into the intelligence engine.',
 status: 'Connected',
 lastSync: '1 hour ago',
 authType: 'Key-Pair JWT Authentication',
 backendSupported: true
 }
];

export function IntegrationsWorkspace() {
 const [selectedCategory, setSelectedCategory] = useState<string>('All');
 const [selectedIntegration, setSelectedIntegration] = useState<IntegrationCard | null>(null);

 // Error simulation states
 const [hasNetworkError, setHasNetworkError] = useState(false);
 const [showPermissionModal, setShowPermissionModal] = useState(false);
 const [showSessionExpired, setShowSessionExpired] = useState(false);

 useEffect(() => {
 const handleKeyDown = (e: KeyboardEvent) => {
 if (e.key === 'Escape') {
 if (selectedIntegration) setSelectedIntegration(null);
 if (hasNetworkError) setHasNetworkError(false);
 if (showPermissionModal) setShowPermissionModal(false);
 if (showSessionExpired) setShowSessionExpired(false);
 }
 };
 document.addEventListener('keydown', handleKeyDown);
 return () => document.removeEventListener('keydown', handleKeyDown);
 }, [selectedIntegration, hasNetworkError, showPermissionModal, showSessionExpired]);

 const filteredIntegrations = DEV_INTEGRATIONS.filter(item => {
 if (selectedCategory === 'All') return true;
 return item.category === selectedCategory;
 });

 const getStatusBadge = (status: ConnectionStatus) => {
 switch (status) {
 case 'Connected':
 return <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wider bg-status-success/10 text-status-success "><CheckCircle2 aria-hidden="true" size={12} /> Connected</span>;
 case 'Needs attention':
 return <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wider bg-status-warning/10 text-status-warning "><AlertTriangle aria-hidden="true" size={12} /> Needs attention</span>;
 case 'Not connected':
 return <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wider bg-surface-hover text-text-tertiary border border-border-subtle"><XCircle aria-hidden="true" size={12} /> Not connected</span>;
 case 'Configuration':
 return <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wider bg-info/10 text-info"><Settings aria-hidden="true" size={12} /> Configuration</span>;
 }
 };

 const getCategoryIcon = (category: IntegrationCategory) => {
 switch (category) {
 case 'Identity': return <Lock aria-hidden="true" size={16} className="text-info" />;
 case 'Procurement': return <ShoppingBag aria-hidden="true" size={16} className="text-accent" />;
 case 'Ticketing': return <Ticket aria-hidden="true" size={16} className="text-status-success" />;
 case 'Communication': return <MessageSquare aria-hidden="true" size={16} className="text-risk-high" />;
 case 'Security': return <Shield aria-hidden="true" size={16} className="text-risk-critical" />;
 case 'GRC': return <Cpu aria-hidden="true" size={16} className="text-accent" />;
 case 'Contracts': return <FileText aria-hidden="true" size={16} className="text-risk-moderate" />;
 case 'Data sources': return <Database aria-hidden="true" size={16} className="text-status-info" />;
 }
 };

 return (
 <div className="space-y-6 max-w-7xl mx-auto pb-28 animate-in fade-in duration-300">
 
 <div aria-live="polite" className="sr-only">
 Enterprise integrations workspace loaded. {filteredIntegrations.length} connectors match category filter {selectedCategory}.
 </div>

 {hasNetworkError && <NetworkErrorBanner onRetry={() => setHasNetworkError(false)} />}
 {showPermissionModal && <PermissionErrorModal onClose={() => setShowPermissionModal(false)} />}
 {showSessionExpired && <ExpiredSessionModal onReauth={() => setShowSessionExpired(false)} />}

 {/* Header */}
 <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-4 border-b border-border pb-6">
 <div>
 <Eyebrow>Administration</Eyebrow>
 <h1 className="text-page-title">Enterprise Integrations Hub</h1>
 <p className="text-body-secondary mt-1">
 Manage connectors across identity providers, ticketing systems, GRC repositories, and data sources.
 </p>
 </div>
 </div>

 {/* Category Filter Pills */}
 <div className="flex items-center gap-2 overflow-x-auto py-1 px-0.5 -mx-0.5 pb-2" role="group" aria-label="Integration Category Filters">
 {['All', 'Identity', 'Procurement', 'Ticketing', 'Communication', 'Security', 'GRC', 'Contracts', 'Data sources'].map(cat => (
 <button
 key={cat}
 type="button"
 onClick={() => setSelectedCategory(cat)}
 className={`px-3.5 py-1.5 rounded-lg text-xs font-semibold whitespace-nowrap transition-colors focus:outline-none focus:ring-2 focus:ring-info ${
 selectedCategory === cat 
 ? 'bg-surface-hover text-text-primary font-bold shadow-sm' 
 : 'bg-surface border border-border text-text-secondary hover:bg-surface-hover'
 }`}
 >
 {cat}
 </button>
 ))}
 </div>

 {/* Integration Cards Grid */}
 <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
 {filteredIntegrations.map(integration => (
 <Card 
 key={integration.id}
 onClick={() => setSelectedIntegration(integration)}
 onKeyDown={(e) => {
 if (e.key === 'Enter' || e.key === ' ') {
 e.preventDefault();
 setSelectedIntegration(integration);
 }
 }}
 tabIndex={0}
 role="button"
 aria-label={`Configure integration ${integration.name}`}
 className="cursor-pointer transition-all hover:border-border-strong hover:shadow-md shadow-sm flex flex-col justify-between focus:outline-none focus:ring-2 focus:ring-info"
 >
 <CardContent className="p-6 space-y-4">
 <div className="flex items-start justify-between">
 <div className="flex items-center gap-2.5">
 <div aria-hidden="true" className="p-2.5 rounded-xl bg-base border border-border">
 {getCategoryIcon(integration.category)}
 </div>
 <div>
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary block">
 {integration.category}
 </span>
 <h3 className="text-sm font-bold text-text-primary mt-0.5 m-0">{integration.name}</h3>
 </div>
 </div>
 </div>

 <p className="text-xs text-text-secondary leading-relaxed line-clamp-2 m-0">
 {integration.description}
 </p>

 <div className="space-y-2 pt-2 border-t border-border-subtle text-[11px]">
 <div className="flex items-center justify-between">
 <span className="text-text-tertiary font-mono">Status:</span>
 {getStatusBadge(integration.status)}
 </div>

 <div className="flex items-center justify-between font-mono">
 <span className="text-text-tertiary">Auth Type:</span>
 <span className="text-text-primary font-medium">{integration.authType}</span>
 </div>

 {integration.lastSync && (
 <div className="flex items-center justify-between font-mono">
 <span className="text-text-tertiary">Last Sync:</span>
 <span className="text-text-secondary">{integration.lastSync}</span>
 </div>
 )}
 </div>
 </CardContent>

 <div className="px-6 py-3 bg-surface-hover/30 border-t border-border flex items-center justify-between text-xs">
 <span className="text-[10px] font-mono font-bold text-text-tertiary">
 {integration.backendSupported ? '🟢 Live API Ready' : '🟡 Dev Placeholder'}
 </span>
 <span className="font-semibold text-info flex items-center gap-1 group-hover:underline">
 Configure <ArrowRight aria-hidden="true" size={12} />
 </span>
 </div>
 </Card>
 ))}
 </div>

 {/* Configuration Modal */}
 {selectedIntegration && createPortal(
 <div 
 role="dialog"
 aria-modal="true"
 aria-labelledby="integration-modal-title"
 className="fixed inset-0 z-[100] flex items-center justify-center bg-black/40 p-4 animate-in fade-in duration-200"
 >
 <div className="bg-surface rounded-2xl max-w-lg w-full max-h-[90vh] overflow-y-auto shadow-[0_8px_30px_rgb(0,0,0,0.12)] dark:shadow-[0_8px_30px_rgb(0,0,0,0.5)] p-6 space-y-6">
 
 <div className="flex items-start justify-between border-b border-border pb-4">
 <div className="flex items-center gap-3">
 <div aria-hidden="true" className="p-3 rounded-xl bg-base border border-border">
 {getCategoryIcon(selectedIntegration.category)}
 </div>
 <div>
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary">{selectedIntegration.category} Connector</span>
 <h2 id="integration-modal-title" className="text-base font-bold text-text-primary mt-0.5 m-0">{selectedIntegration.name}</h2>
 </div>
 </div>
 <button 
 type="button"
 onClick={() => setSelectedIntegration(null)} 
 className="p-1.5 rounded-lg hover:bg-surface-hover text-text-tertiary hover:text-text-primary transition-colors focus:outline-none focus:ring-2 focus:ring-info"
 aria-label="Close configuration modal"
 >
 <X aria-hidden="true" size={18} />
 </button>
 </div>

 <div className="space-y-4 text-xs">
 <p className="text-text-secondary leading-relaxed m-0">{selectedIntegration.description}</p>

 <div className="p-4 bg-base border border-border rounded-xl space-y-3 font-mono">
 <div className="flex justify-between items-center text-[11px]">
 <span className="text-text-tertiary">Connection State:</span>
 {getStatusBadge(selectedIntegration.status)}
 </div>
 <div className="flex justify-between items-center text-[11px]">
 <span className="text-text-tertiary">Sign-in method:</span>
 <span className="text-text-primary font-bold">{selectedIntegration.authType}</span>
 </div>
 <div className="flex justify-between items-center text-[11px]">
 <span className="text-text-tertiary">Status:</span>
 <span className={selectedIntegration.backendSupported ? 'text-status-success font-bold' : 'text-status-warning font-bold'}>
 {selectedIntegration.backendSupported ? 'Simulated' : 'Development Placeholder'}
 </span>
 </div>
 </div>

 {!selectedIntegration.backendSupported && (
 <div className="p-3 bg-risk-moderate/10 border border-risk-moderate/25 rounded-lg text-risk-moderate text-[11px]">
 <strong>Note:</strong> This connector is currently a development placeholder. Live ingestion endpoints for this service are not yet active in the current backend release.
 </div>
 )}

 <div className="space-y-1.5 pt-2">
 <label htmlFor="integrationWebhookInput" className="font-bold uppercase tracking-wider text-[10px] text-text-tertiary block">API Endpoint / Webhook URL</label>
 <input 
 id="integrationWebhookInput"
 type="text" 
 readOnly 
 value={`https://api.validexia.io/v1/integrations/${selectedIntegration.id}/webhook`}
 className="w-full bg-base border border-border rounded-lg px-3 py-2 text-xs font-mono text-text-secondary focus:outline-none focus:ring-2 focus:ring-info"
 />
 </div>
 </div>

 <div className="flex items-center justify-between pt-4 border-t border-border">
 <Button type="button" variant="secondary" size="sm" onClick={() => setSelectedIntegration(null)}>
 Cancel
 </Button>
 <Button type="button" variant="primary" size="sm" onClick={() => { setSelectedIntegration(null); }}>
 Save Configuration
 </Button>
 </div>

 </div>
 </div>
 , document.body)}

 </div>
 );
}