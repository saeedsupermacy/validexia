// src/components/layout/AppShell.tsx
import React, { useState, useEffect } from "react";
import { 
 LayoutDashboard, Building2, ClipboardCheck, FileCheck, Activity, Wrench,
 Search, Bell, HelpCircle, Menu, Network, Waypoints, AlertTriangle,
 Gavel, BarChart2, History, Blocks, Users, Settings, Sun, Moon, X, PanelRightClose, PanelRightOpen, Sparkles, DoorOpen
} from "lucide-react";
import { WorkspaceSwitcher } from "../../shared/ui/WorkspaceSwitcher";
import { CommandPalette } from "../../shared/ui/CommandPalette";
import { Dialog } from "../../shared/ui/Dialog";
import { navigateTo, type ViewKey } from "../../shared/navigation";
import { buildNotifications, getReadIds, markNotificationRead, markAllNotificationsRead, type AppNotification } from "../../shared/notifications";

interface AppShellProps {
 children: React.ReactNode;
 currentPageTitle?: string;
 contextPanel?: React.ReactNode; 
 currentView?: ViewKey;
 onSelectView?: (view: ViewKey) => void;
}


interface SidebarContentProps {
  isDesktopCollapsed: boolean;
  isMobileMenuOpen: boolean;
  setIsDesktopCollapsed: (v: boolean | ((p: boolean) => boolean)) => void;
  setIsMobileMenuOpen: (v: boolean) => void;
  currentView: ViewKey;
  onSelectView?: (view: ViewKey) => void;
}

/** Top-level so React keeps it mounted across AppShell re-renders (preserves nav scroll). */
function SidebarContent({ isDesktopCollapsed, isMobileMenuOpen, setIsDesktopCollapsed, setIsMobileMenuOpen, currentView, onSelectView }: SidebarContentProps) {
 const isExpanded = !isDesktopCollapsed || isMobileMenuOpen;
 
 return (
 <>
 <div className="h-14 flex items-center px-4 border-b border-border-subtle justify-between shrink-0">
 {isExpanded && (
 <span className="font-semibold text-lg tracking-tight truncate text-text-primary">Validexia</span>
 )}
 <button 
 type="button"
 onClick={() => isMobileMenuOpen ? setIsMobileMenuOpen(false) : setIsDesktopCollapsed(!isDesktopCollapsed)} 
 className="p-1.5 hover:bg-surface-hover rounded text-text-secondary transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-info hidden md:block"
 aria-expanded={!isDesktopCollapsed}
 aria-controls="main-nav"
 aria-label={isDesktopCollapsed ? "Expand sidebar navigation" : "Collapse sidebar navigation"}
 >
 <Menu aria-hidden="true" size={20} strokeWidth={1.5} />
 </button>
 </div>
 
 <nav id="main-nav" aria-label="Main Navigation" className="flex-1 overflow-y-auto py-4 px-2 flex flex-col gap-6">
 <NavSection title="Overview" isOpen={isExpanded}>
 <NavItem 
 icon={<LayoutDashboard size={18} strokeWidth={1.5} />} 
 label="Command Center" 
 isOpen={isExpanded} 
 active={currentView === 'command'} 
 onClick={() => { onSelectView?.('command'); setIsMobileMenuOpen(false); }}
 />
 </NavSection>

 <NavSection title="Program" isOpen={isExpanded}>
 <NavItem 
 icon={<Building2 size={18} strokeWidth={1.5} />} 
 label="Vendors" 
 isOpen={isExpanded} 
 active={currentView === 'vendors'} 
 onClick={() => { onSelectView?.('vendors'); setIsMobileMenuOpen(false); }}
 />
 <NavItem 
 icon={<ClipboardCheck size={18} strokeWidth={1.5} />} 
 label="Assessments" 
 isOpen={isExpanded} 
 active={currentView === 'assessments'} 
 onClick={() => { onSelectView?.('assessments'); setIsMobileMenuOpen(false); }}
 />
 <NavItem 
 icon={<FileCheck size={18} strokeWidth={1.5} />} 
 label="Evidence" 
 isOpen={isExpanded} 
 active={currentView === 'evidence'} 
 onClick={() => { onSelectView?.('evidence'); setIsMobileMenuOpen(false); }}
 />
 <NavItem 
 icon={<Activity size={18} strokeWidth={1.5} />} 
 label="Monitoring" 
 isOpen={isExpanded} 
 active={currentView === 'monitoring'} 
 onClick={() => { onSelectView?.('monitoring'); setIsMobileMenuOpen(false); }}
 />
 <NavItem 
 icon={<Wrench size={18} strokeWidth={1.5} />} 
 label="Remediation" 
 isOpen={isExpanded} 
 active={currentView === 'remediation'} 
 onClick={() => { onSelectView?.('remediation'); setIsMobileMenuOpen(false); }}
 />
 </NavSection>

 <NavSection title="Intelligence" isOpen={isExpanded}>
 <NavItem 
 icon={<Network size={18} strokeWidth={1.5} />} 
 label="Dependencies" 
 isOpen={isExpanded} 
 active={currentView === 'dependencies'} 
 onClick={() => { onSelectView?.('dependencies'); setIsMobileMenuOpen(false); }}
 />
 <NavItem 
 icon={<Waypoints size={18} strokeWidth={1.5} />} 
 label="Fourth Parties" 
 isOpen={isExpanded} 
 active={currentView === 'fourth-parties'} 
 onClick={() => { onSelectView?.('fourth-parties'); setIsMobileMenuOpen(false); }}
 />
 <NavItem 
 icon={<DoorOpen size={18} strokeWidth={1.5} />} 
 label="Vendor Portal" 
 isOpen={isExpanded} 
 active={currentView === 'vendor-portal'} 
 onClick={() => { onSelectView?.('vendor-portal'); setIsMobileMenuOpen(false); }}
 />
 <NavItem 
 icon={<AlertTriangle size={18} strokeWidth={1.5} />} 
 label="Risk Events" 
 isOpen={isExpanded} 
 active={currentView === 'risk-events'} 
 onClick={() => { onSelectView?.('risk-events'); setIsMobileMenuOpen(false); }}
 />
 <NavItem 
 icon={<Sparkles size={18} strokeWidth={1.5} />} 
 label="AI Workbench" 
 isOpen={isExpanded} 
 active={currentView === 'ai-playground'} 
 onClick={() => { onSelectView?.('ai-playground'); setIsMobileMenuOpen(false); }}
 />
 </NavSection>
 
 <NavSection title="Governance" isOpen={isExpanded}>
 <NavItem 
 icon={<Gavel size={18} strokeWidth={1.5} />} 
 label="Decisions" 
 isOpen={isExpanded} 
 active={currentView === 'decisions'} 
 onClick={() => { onSelectView?.('decisions'); setIsMobileMenuOpen(false); }}
 />
 <NavItem 
 icon={<BarChart2 size={18} strokeWidth={1.5} />} 
 label="Reports" 
 isOpen={isExpanded} 
 active={currentView === 'reports'} 
 onClick={() => { onSelectView?.('reports'); setIsMobileMenuOpen(false); }}
 />
 <NavItem 
 icon={<History size={18} strokeWidth={1.5} />} 
 label="Audit Log" 
 isOpen={isExpanded} 
 active={currentView === 'audit-log'} 
 onClick={() => { onSelectView?.('audit-log'); setIsMobileMenuOpen(false); }}
 />
 </NavSection>

 <NavSection title="Administration" isOpen={isExpanded}>
 <NavItem 
 icon={<Blocks size={18} strokeWidth={1.5} />} 
 label="Integrations" 
 isOpen={isExpanded} 
 active={currentView === 'integrations'} 
 onClick={() => { onSelectView?.('integrations'); setIsMobileMenuOpen(false); }}
 />
 <NavItem 
 icon={<Users size={18} strokeWidth={1.5} />} 
 label="Team" 
 isOpen={isExpanded} 
 active={currentView === 'team'} 
 onClick={() => { onSelectView?.('team'); setIsMobileMenuOpen(false); }}
 />
 <NavItem 
 icon={<Settings size={18} strokeWidth={1.5} />} 
 label="Settings" 
 isOpen={isExpanded} 
 active={currentView === 'settings'} 
 onClick={() => { onSelectView?.('settings'); setIsMobileMenuOpen(false); }}
 />
 </NavSection>
 </nav>
 </>
 );
}

export function AppShell({ children, currentPageTitle, contextPanel, currentView = 'command', onSelectView }: AppShellProps) {
 const [isDesktopCollapsed, setIsDesktopCollapsed] = useState(false);
 const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);
 const [isContextPanelOpen, setIsContextPanelOpen] = useState(true);
 const [isCommandPaletteOpen, setIsCommandPaletteOpen] = useState(false);
 const [openMenu, setOpenMenu] = useState<'help' | 'notif' | 'profile' | null>(null);
 const [helpDialog, setHelpDialog] = useState<'documentation' | 'shortcuts' | 'support' | null>(null);
 const notifications = React.useMemo(() => buildNotifications(), []);
 const [readNotifIds, setReadNotifIds] = React.useState<string[]>(() => getReadIds());
 const unreadNotifs = notifications.filter(n => !readNotifIds.includes(n.id));

 const handleNotifClick = (n: AppNotification) => {
 setReadNotifIds(markNotificationRead(n.id));
 setOpenMenu(null);
 navigateTo(n.view, n.params);
 };
 const handleMarkAllRead = () => {
 setReadNotifIds(markAllNotificationsRead(notifications.map(n => n.id)));
 };
 const topbarMenuRef = React.useRef<HTMLDivElement>(null);

 // Close topbar menus on outside click / Escape
 useEffect(() => {
   if (!openMenu) return;
   const onDown = (e: MouseEvent) => {
     if (topbarMenuRef.current && !topbarMenuRef.current.contains(e.target as Node)) setOpenMenu(null);
   };
   const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpenMenu(null); };
   document.addEventListener('mousedown', onDown);
   document.addEventListener('keydown', onKey);
   return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey); };
 }, [openMenu]);
 const [isDark, setIsDark] = useState(() => {
 if (typeof window === 'undefined') return false;
 try {
 const saved = localStorage.getItem('vx-theme');
 if (saved === 'dark') return true;
 if (saved === 'light') return false;
 return window.matchMedia('(prefers-color-scheme: dark)').matches;
 } catch { return false; }
});

// Keep <html> class in sync (covers first paint + any external change)
useEffect(() => {
 document.documentElement.classList.toggle('dark', isDark);
}, [isDark]);

 useEffect(() => {
 const handleResize = () => {
 if (window.innerWidth >= 768) setIsMobileMenuOpen(false);
 };
 window.addEventListener('resize', handleResize);
 return () => window.removeEventListener('resize', handleResize);
 }, []);

 useEffect(() => {
 const handleGlobalKeyDown = (e: KeyboardEvent) => {
 if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
 e.preventDefault();
 setIsCommandPaletteOpen((prev) => !prev);
 }
 if (e.key === '/' && document.activeElement?.tagName !== 'INPUT' && document.activeElement?.tagName !== 'TEXTAREA') {
 e.preventDefault();
 setIsCommandPaletteOpen(true);
 }
 };
 document.addEventListener('keydown', handleGlobalKeyDown);
 return () => document.removeEventListener('keydown', handleGlobalKeyDown);
 }, []);

 useEffect(() => {
 if (isMobileMenuOpen) {
 const handleEscape = (e: KeyboardEvent) => {
 if (e.key === 'Escape') setIsMobileMenuOpen(false);
 };
 document.addEventListener('keydown', handleEscape);
 return () => document.removeEventListener('keydown', handleEscape);
 }
 }, [isMobileMenuOpen]);

const toggleTheme = () => {
 // 1. Force all individual elements to snap instantly so they don't stagger
 const css = document.createElement('style');
 css.appendChild(document.createTextNode('* { transition: none !important; }'));
 document.head.appendChild(css);

 const switchTheme = () => {
 const next = !isDark;
 try { localStorage.setItem('vx-theme', next ? 'dark' : 'light'); } catch {}
 document.documentElement.classList.toggle('dark', next);
 setIsDark(next);
 };

 // 2. Use the premium View Transition (screenshot fade)
 if (document.startViewTransition) {
 const transition = document.startViewTransition(switchTheme);
 // 3. Remove the instant-snap rule only after the cinematic fade is totally finished
 transition.finished.then(() => {
 document.head.removeChild(css);
 });
 } else {
 // Fallback for older browsers like Safari
 switchTheme();
 requestAnimationFrame(() => requestAnimationFrame(() => document.head.removeChild(css)));
 }
 };



 return (
<div className="flex h-screen w-full bg-base overflow-hidden text-text-primary font-sans">
 <a href="#main-content" className="sr-only focus:not-sr-only focus:absolute focus:z-[100] focus:top-4 focus:left-4 bg-surface text-info px-4 py-2 rounded-md font-bold shadow-md outline-none focus:ring-2 focus:ring-info">
 Skip to main content
 </a>

 <CommandPalette isOpen={isCommandPaletteOpen} onClose={() => setIsCommandPaletteOpen(false)} />
 <Dialog
  open={helpDialog !== null}
  onClose={() => setHelpDialog(null)}
  title={helpDialog === 'shortcuts' ? 'Keyboard shortcuts' : helpDialog === 'documentation' ? 'Documentation' : 'Contact support'}
  maxWidth="max-w-md"
 >
  <div className="p-6 space-y-4">
   <h2 className="text-base font-bold text-text-primary m-0">
    {helpDialog === 'shortcuts' ? 'Keyboard shortcuts' : helpDialog === 'documentation' ? 'Documentation' : 'Contact support'}
   </h2>
   {helpDialog === 'shortcuts' ? (
    <dl className="space-y-3 text-sm">
     <div className="flex items-center justify-between gap-4"><dt className="text-text-secondary">Open command palette</dt><dd className="font-mono text-text-primary m-0">Ctrl/⌘ + K</dd></div>
     <div className="flex items-center justify-between gap-4"><dt className="text-text-secondary">Open search</dt><dd className="font-mono text-text-primary m-0">/</dd></div>
     <div className="flex items-center justify-between gap-4"><dt className="text-text-secondary">Close the active dialog or menu</dt><dd className="font-mono text-text-primary m-0">Esc</dd></div>
    </dl>
   ) : (
    <p className="text-sm text-text-secondary m-0">
     {helpDialog === 'documentation'
      ? 'A documentation destination is not configured in this demo build. Ask your administrator for the current documentation link.'
      : 'A support contact is not configured in this demo build. Ask your administrator for the current support channel.'}
    </p>
   )}
   <div className="flex justify-end pt-2">
    <button type="button" onClick={() => setHelpDialog(null)} className="px-3 py-2 rounded-lg border border-border text-sm font-semibold text-text-primary hover:bg-surface-hover focus:outline-none focus:ring-2 focus:ring-info">Close</button>
   </div>
  </div>
 </Dialog>

 {isMobileMenuOpen && (
 <div 
 className="fixed inset-0 z-40 bg-overlay md:hidden transition-opacity" 
 onClick={() => setIsMobileMenuOpen(false)} 
 aria-hidden="true" 
 />
 )}

 <aside 
 id="mobile-sidebar"
 aria-modal={isMobileMenuOpen ? "true" : "false"}
 role={isMobileMenuOpen ? "dialog" : "complementary"}
 aria-label="Sidebar Navigation"
 className={`
 fixed inset-y-0 left-0 z-50 flex flex-col bg-sidebar border-r border-border-subtle transition-all duration-150 ease-in-out
 ${isMobileMenuOpen ? 'translate-x-0 w-64' : '-translate-x-full w-64'} 
 md:relative md:translate-x-0 ${isDesktopCollapsed ? 'md:w-16' : 'md:w-64'}
 `}
 >
 {isMobileMenuOpen && (
 <button 
 type="button"
 className="absolute top-3 -right-12 p-2 text-white bg-surface-hover/20 rounded-md md:hidden focus:outline-none focus:ring-2 focus:ring-info" 
 onClick={() => setIsMobileMenuOpen(false)}
 aria-label="Close mobile menu"
 >
 <X aria-hidden="true" size={20} strokeWidth={1.5} />
 </button>
 )}
 <SidebarContent
        isDesktopCollapsed={isDesktopCollapsed}
        isMobileMenuOpen={isMobileMenuOpen}
        setIsDesktopCollapsed={setIsDesktopCollapsed}
        setIsMobileMenuOpen={setIsMobileMenuOpen}
        currentView={currentView}
        onSelectView={onSelectView}
      />
 </aside>

 <div className="flex-1 flex flex-col min-w-0 overflow-hidden relative">
 <header role="banner" className="h-14 flex items-center justify-between px-4 md:px-6 bg-base border-b border-border-subtle z-20 shrink-0 transition-colors duration-300">
 <div className="flex items-center flex-1 gap-3">
 <button 
 type="button"
 className="md:hidden p-1.5 -ml-1.5 hover:bg-surface-hover rounded text-text-secondary focus:outline-none focus:ring-2 focus:ring-info" 
 onClick={() => setIsMobileMenuOpen(true)}
 aria-expanded={isMobileMenuOpen}
 aria-controls="mobile-sidebar"
 aria-label="Open mobile menu"
 >
 <Menu aria-hidden="true" size={20} strokeWidth={1.5} />
 </button>
 <WorkspaceSwitcher onOrganizationChange={() => { setOpenMenu(null); onSelectView?.('command'); }} />
 {currentPageTitle && (
 <div className="hidden lg:flex items-center ml-2" aria-label="Breadcrumb">
 <span className="mx-2 text-border-strong" aria-hidden="true">/</span>
 <span className="text-sm font-semibold tracking-tight text-text-primary" aria-current="page">{currentPageTitle}</span>
 </div>
 )}
 <button 
 type="button"
 onClick={() => setIsCommandPaletteOpen(true)} 
 className="hidden xl:flex items-center w-full max-w-xs ml-4 px-3 py-1.5 bg-black/[0.03] dark:bg-white/[0.04] border border-transparent rounded-md text-sm text-text-tertiary hover:bg-black/[0.05] dark:hover:bg-white/[0.06] transition-colors group text-left focus:outline-none focus:ring-2 focus:ring-info"
 aria-keyshortcuts="/"
 aria-label="Global search. Press forward slash to search portfolio."
 >
 <Search aria-hidden="true" className="mr-2 text-text-tertiary group-hover:text-text-secondary transition-colors" size={16} strokeWidth={1.5} />
 <span className="flex-1 truncate">Search portfolio...</span>
 <kbd aria-hidden="true" className="inline-flex items-center gap-0.5 font-mono text-[10px] font-medium text-text-tertiary bg-black/[0.04] dark:bg-white/[0.06] px-1.5 py-0.5 rounded shrink-0">/</kbd>
 </button>
 </div>

 <div className="hidden md:flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-status-warning/10 border border-status-warning/20 mr-2" title="Demo mode: data is synthetic, actions simulate real operations and do not affect production systems.">
 <span className="w-1.5 h-1.5 rounded-full bg-status-warning animate-pulse" aria-hidden="true" />
 <span className="text-[11px] font-bold uppercase tracking-wider text-status-warning">Demo</span>
 </div>
 <div className="flex items-center space-x-1 sm:space-x-2 text-text-secondary shrink-0 ml-4" ref={topbarMenuRef}>
 <button 
 type="button"
 onClick={toggleTheme} 
 className="p-2 rounded-md hover:bg-surface-hover text-text-secondary hover:text-text-primary transition-colors focus:outline-none focus:ring-2 focus:ring-info"
 aria-label={isDark ? "Switch to light mode" : "Switch to dark mode"}
 >
 {isDark ? <Sun aria-hidden="true" size={18} strokeWidth={1.5} /> : <Moon aria-hidden="true" size={18} strokeWidth={1.5} />}
 </button>
 <div className="relative hidden sm:block">
 <button 
 type="button"
 onClick={() => setOpenMenu(openMenu === 'help' ? null : 'help')}
 className="p-2 rounded-md hover:bg-surface-hover hover:text-text-primary transition-colors focus:outline-none focus:ring-2 focus:ring-info"
 aria-label="Help and Documentation"
 aria-expanded={openMenu === 'help'}
 >
 <HelpCircle aria-hidden="true" size={18} strokeWidth={1.5} />
 </button>
 {openMenu === 'help' && (
 <div className="absolute right-0 mt-2 w-56 rounded-lg bg-surface border border-border shadow-lg z-50 overflow-hidden animate-in fade-in-50 duration-150">
 <button type="button" onClick={() => { setOpenMenu(null); setHelpDialog('documentation'); }} className="w-full text-left px-4 py-2.5 text-sm text-text-secondary hover:bg-surface-hover hover:text-text-primary transition-colors">Documentation</button>
 <button type="button" onClick={() => { setOpenMenu(null); setHelpDialog('shortcuts'); }} className="w-full text-left px-4 py-2.5 text-sm text-text-secondary hover:bg-surface-hover hover:text-text-primary transition-colors">Keyboard shortcuts <span className="ml-1 font-mono text-[10px] text-text-tertiary">/</span></button>
 <button type="button" onClick={() => { setOpenMenu(null); setHelpDialog('support'); }} className="w-full text-left px-4 py-2.5 text-sm text-text-secondary hover:bg-surface-hover hover:text-text-primary transition-colors">Contact support</button>
 </div>
 )}
 </div>
 <div className="relative">
 <button 
 type="button"
 onClick={() => setOpenMenu(openMenu === 'notif' ? null : 'notif')}
 className="relative p-2 rounded-md hover:bg-surface-hover hover:text-text-primary transition-colors focus:outline-none focus:ring-2 focus:ring-info"
 aria-label={`Notifications${unreadNotifs.length > 0 ? `, ${unreadNotifs.length} unread` : ''}`}
 aria-expanded={openMenu === 'notif'}
 >
 <Bell aria-hidden="true" size={18} strokeWidth={1.5} />
 {unreadNotifs.length > 0 && (
 <span aria-hidden="true" className="absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] px-1 rounded-full bg-risk-critical text-white text-[10px] font-bold flex items-center justify-center tabular-nums">
 {unreadNotifs.length > 9 ? '9+' : unreadNotifs.length}
 </span>
 )}
 </button>
 {openMenu === 'notif' && (
 <div className="absolute right-0 mt-2 w-80 rounded-lg bg-surface border border-border shadow-lg z-50 overflow-hidden animate-in fade-in-50 duration-150">
 <div className="px-4 py-2.5 border-b border-border flex items-center justify-between">
 <p className="text-[11px] font-semibold uppercase tracking-wider text-text-tertiary m-0">Notifications{unreadNotifs.length > 0 && ` (${unreadNotifs.length})`}</p>
 {unreadNotifs.length > 0 && (
 <button type="button" onClick={handleMarkAllRead} className="text-[11px] font-semibold text-info hover:underline focus:outline-none focus:ring-2 focus:ring-info rounded px-1">
 Mark all as read
 </button>
 )}
 </div>
 <div className="max-h-72 overflow-y-auto">
 {unreadNotifs.length === 0 ? (
 <p className="px-4 py-6 text-sm text-text-tertiary text-center m-0">You're all caught up. No alerts right now.</p>
 ) : unreadNotifs.map((n, i) => (
 <button
 key={n.id}
 type="button"
 onClick={() => handleNotifClick(n)}
 className={`w-full text-left px-4 py-3 hover:bg-surface-hover transition-colors flex gap-3 ${i < unreadNotifs.length - 1 ? 'border-b border-border-subtle' : ''}`}
 >
 <span aria-hidden="true" className={`mt-1.5 w-2 h-2 rounded-full shrink-0 ${n.severity === 'critical' ? 'bg-risk-critical' : n.severity === 'high' ? 'bg-risk-high' : n.severity === 'medium' ? 'bg-status-warning' : 'bg-status-info'}`} />
 <span className="min-w-0">
 <p className="text-sm font-semibold text-text-primary m-0 truncate">{n.title}</p>
 <p className="text-xs text-text-tertiary mt-0.5 m-0 truncate">{n.body}</p>
 </span>
 </button>
 ))}
 </div>
 </div>
 )}
 </div>
 <div aria-hidden="true" className="w-px h-4 bg-border mx-1 hidden sm:block" />
 <div className="relative flex items-center pl-1">
 <button 
 type="button"
 onClick={() => setOpenMenu(openMenu === 'profile' ? null : 'profile')}
 className="flex items-center gap-2 p-1 rounded-md hover:bg-surface-hover transition-colors focus:outline-none focus:ring-2 focus:ring-info"
 aria-label="User Profile menu"
 aria-haspopup="true"
 aria-expanded={openMenu === 'profile'}
 >
 <div aria-hidden="true" className="w-7 h-7 rounded-full bg-black/[0.04] dark:bg-white/[0.06] flex items-center justify-center text-text-primary text-xs font-medium">SD</div>
 </button>
 {openMenu === 'profile' && (
 <div className="absolute right-0 mt-2 w-56 rounded-lg bg-surface border border-border shadow-lg z-50 overflow-hidden animate-in fade-in-50 duration-150">
 <div className="px-4 py-3 border-b border-border">
 <p className="text-sm font-semibold text-text-primary m-0">Saeed Dahri</p>
 <p className="text-xs text-text-tertiary m-0">Security Admin</p>
 </div>
 <button type="button" onClick={() => { setOpenMenu(null); onSelectView?.('settings'); }} className="w-full text-left px-4 py-2.5 text-sm text-text-secondary hover:bg-surface-hover hover:text-text-primary transition-colors">Account settings</button>
 <button type="button" onClick={() => { setOpenMenu(null); onSelectView?.('team'); }} className="w-full text-left px-4 py-2.5 text-sm text-text-secondary hover:bg-surface-hover hover:text-text-primary transition-colors">Team & roles</button>
 <button type="button" onClick={() => { setOpenMenu(null); }} className="w-full text-left px-4 py-2.5 text-sm text-risk-critical hover:bg-risk-critical/10 transition-colors">Sign out</button>
 </div>
 )}
 </div>
 </div>
 </header>

 <div className="flex-1 flex overflow-hidden bg-base relative transition-colors duration-300">
 <main 
 id="main-content"
 role="main"
 className="flex-1 overflow-y-auto overflow-x-hidden scroll-smooth focus:outline-none" 
 tabIndex={-1}
 >
 <div 
 key={currentView}
 className="w-full max-w-7xl mx-auto p-4 md:p-8 animate-page-in"
 >
 {children}
 </div>
 </main>

 {contextPanel && isContextPanelOpen && (
 <aside 
 aria-label="Context Panel"
 className="hidden xl:block w-80 lg:w-96 shrink-0 bg-surface border-l border-border overflow-y-auto shadow-sm z-10"
 >
 <div className="sticky top-0 p-4 border-b border-border bg-surface/95 backdrop-blur z-10 flex items-center justify-between">
 <h2 className="text-card-title m-0">Context Panel</h2>
 <button 
 type="button"
 onClick={() => setIsContextPanelOpen(false)} 
 className="p-1.5 hover:bg-surface-hover rounded text-text-secondary focus:outline-none focus:ring-2 focus:ring-info"
 aria-label="Close Context Panel"
 >
 <PanelRightClose aria-hidden="true" size={18} strokeWidth={1.5} />
 </button>
 </div>
 <div className="p-4">{contextPanel}</div>
 </aside>
 )}

 {contextPanel && !isContextPanelOpen && (
 <button 
 type="button"
 onClick={() => setIsContextPanelOpen(true)} 
 className="hidden xl:flex absolute right-0 top-1/2 -translate-y-1/2 p-1.5 bg-surface border border-r-0 border-border rounded-l-md shadow-sm text-text-secondary hover:text-text-primary z-20 focus:outline-none focus:ring-2 focus:ring-info"
 aria-label="Open Context Panel"
 >
 <PanelRightOpen aria-hidden="true" size={18} strokeWidth={1.5} />
 </button>
 )}
 </div>
 </div>
 </div>
 );
}

function NavSection({ title, isOpen, children }: { title: string, isOpen: boolean, children: React.ReactNode }) {
 const sectionId = React.useId();
 return (
 <div role="group" aria-labelledby={sectionId}>
 {isOpen ? (
 <h3 id={sectionId} className="px-3 text-label mb-2 transition-opacity duration-200 truncate">{title}</h3>
 ) : (
 <div className="px-4 mb-2 flex justify-center" aria-hidden="true">
 <div className="w-4 border-t border-border-strong opacity-40 transition-opacity" />
 <span id={sectionId} className="sr-only">{title}</span>
 </div>
 )}
 <ul className="space-y-0.5 m-0 p-0 list-none">
 {React.Children.map(children, child => (
 <li>{child}</li>
 ))}
 </ul>
 </div>
 );
}

function NavItem({ icon, label, isOpen, active = false, onClick }: { icon: React.ReactNode, label: string, isOpen: boolean, active?: boolean, onClick?: () => void }) {
 return (
 <button 
 type="button"
 onClick={onClick}
 className={`w-full text-left relative flex items-center px-3 py-2 rounded-md transition-colors duration-150 group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-info ${active ? 'bg-black/[0.05] dark:bg-white/[0.07] text-text-primary font-medium' : 'text-text-secondary hover:bg-black/[0.03] dark:hover:bg-white/[0.04] hover:text-text-primary'} ${!isOpen ? 'justify-center' : ''}`}
 aria-current={active ? "page" : undefined}
 title={!isOpen ? label : undefined}
 >
 <span className="shrink-0" aria-hidden="true">{icon}</span>
 {isOpen && <span className="ml-3 text-sm truncate">{label}</span>}
 
 {!isOpen && (
 <div 
 role="tooltip" 
 className="absolute left-full top-1/2 -translate-y-1/2 ml-2 px-2.5 py-1.5 bg-surface border border-border text-text-primary text-xs font-medium rounded-md shadow-md z-50 whitespace-nowrap opacity-0 pointer-events-none group-hover:opacity-100 group-focus:opacity-100 transition-opacity"
 >
 {label}
 </div>
 )}
 </button>
 );
}