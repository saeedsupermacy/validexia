// src/features/search/GlobalSearchModal.tsx
import React, { useState, useEffect, useRef} from 'react';
import { 
 Search, X, Clock, Building2, ClipboardCheck, FileCheck, AlertTriangle, 
 Gavel, Loader2, ServerCrash, FileQuestion, ArrowRight
} from 'lucide-react';
import { fetchGlobalSearch, SearchResult} from './searchService';
import { navigateTo } from '../../shared/navigation';

interface GlobalSearchModalProps {
 isOpen: boolean;
 onClose: () => void;
}

const CATEGORIES = [
 { id: 'all', label: 'All Results' },
 { id: 'vendor', label: 'Vendors', icon: Building2 },
 { id: 'assessment', label: 'Assessments', icon: ClipboardCheck },
 { id: 'evidence', label: 'Evidence', icon: FileCheck },
 { id: 'risk_event', label: 'Risk Events', icon: AlertTriangle },
 { id: 'decision', label: 'Decisions', icon: Gavel },
];

// Reusable highlighter for precise visual feedback
function HighlightMatch({ text, query }: { text: string; query: string }) {
 if (!query) return <>{text}</>;
 const parts = text.split(new RegExp(`(${query})`, 'gi'));
 return (
 <>
 {parts.map((part, i) => 
 part.toLowerCase() === query.toLowerCase() 
 ? <span key={i} className="bg-info/20 text-info font-medium rounded-sm px-0.5">{part}</span>
 : part
 )}
 </>
 );
}

export function GlobalSearchModal({ isOpen, onClose }: GlobalSearchModalProps) {
 const [query, setQuery] = useState('');
 const [debouncedQuery, setDebouncedQuery] = useState('');
 const [activeCategory, setActiveCategory] = useState('all');
 
 const [results, setResults] = useState<SearchResult[]>([]);
 const [isLoading, setIsLoading] = useState(false);
 const [error, setError] = useState<string | null>(null);
 const [selectedIndex, setSelectedIndex] = useState(0);

 // Development fixture for recent searches
 const [recentSearches] = useState(['CloudScale', 'SOC 2', 'Critical Risk']);

 const inputRef = useRef<HTMLInputElement>(null);
 const scrollContainerRef = useRef<HTMLDivElement>(null);

 // Debounce user input
 useEffect(() => {
 const handler = setTimeout(() => setDebouncedQuery(query.trim()), 250);
 return () => clearTimeout(handler);
 }, [query]);

 // Fetch results when query or category changes
 useEffect(() => {
 if (!debouncedQuery) {
 setResults([]);
 setError(null);
 return;
 }

 let isMounted = true;
 setIsLoading(true);
 setError(null);
 setSelectedIndex(0);

 fetchGlobalSearch(debouncedQuery, activeCategory)
 .then(data => { if (isMounted) setResults(data); })
 .catch(err => { if (isMounted) setError(err.message); })
 .finally(() => { if (isMounted) setIsLoading(false); });

 return () => { isMounted = false; };
 }, [debouncedQuery, activeCategory]);

 // Handle Escape to close & body scroll locking
 useEffect(() => {
 const handleKeyDown = (e: KeyboardEvent) => { 
 if (e.key === 'Escape') onClose(); 
 };
 if (isOpen) {
 document.addEventListener('keydown', handleKeyDown);
 document.body.style.overflow = 'hidden';
 setTimeout(() => inputRef.current?.focus(), 10);
 }
 return () => {
 document.removeEventListener('keydown', handleKeyDown);
 document.body.style.overflow = 'unset';
 };
 }, [isOpen, onClose]);

 // Keyboard navigation through results
 const handleKeyDown = (e: React.KeyboardEvent) => {
 const itemsCount = debouncedQuery ? results.length : recentSearches.length;
 if (itemsCount === 0) return;

 if (e.key === 'ArrowDown') {
 e.preventDefault();
 setSelectedIndex(prev => (prev + 1) % itemsCount);
 scrollToItem((selectedIndex + 1) % itemsCount);
 } else if (e.key === 'ArrowUp') {
 e.preventDefault();
 setSelectedIndex(prev => (prev - 1 + itemsCount) % itemsCount);
 scrollToItem((selectedIndex - 1 + itemsCount) % itemsCount);
 } else if (e.key === 'Enter') {
 e.preventDefault();
 if (debouncedQuery && results[selectedIndex]) {
 const result = results[selectedIndex];
 const view = result.url.split('/')[1] as 'vendors' | 'assessments' | 'evidence' | 'risk-events' | 'monitoring' | 'remediation' | 'decisions' | 'audit-log';
 navigateTo(view || 'command');
 onClose();
 } else if (!debouncedQuery && recentSearches[selectedIndex]) {
 setQuery(recentSearches[selectedIndex]);
 }
 }
 };

 const scrollToItem = (index: number) => {
 if (!scrollContainerRef.current) return;
 const element = scrollContainerRef.current.querySelector(`[data-index="${index}"]`) as HTMLElement;
 if (element) {
 element.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
 }
 };

 const getCategoryIcon = (catId: string) => {
 const cat = CATEGORIES.find(c => c.id === catId);
 return cat?.icon || FileCheck;
 };

 if (!isOpen) return null;

 return (
 <div className="fixed inset-0 z-[100] flex flex-col sm:items-center sm:pt-[10vh] px-0 sm:px-4">
 <div className="fixed inset-0 bg-overlay backdrop-blur-sm transition-opacity" onClick={onClose} aria-hidden="true" />
 
 <div 
 className="relative w-full h-full sm:h-auto sm:max-h-[80vh] sm:max-w-3xl bg-surface sm:rounded-xl shadow-2xl border border-border flex flex-col overflow-hidden animate-in fade-in sm:zoom-in-95 duration-200"
 role="dialog"
 aria-label="Global Search"
 >
 {/* Search Header */}
 <div className="flex items-center px-4 py-3 border-b border-border bg-base/50 shrink-0">
 <Search aria-hidden="true" size={20} strokeWidth={1.5} className="text-text-tertiary shrink-0" />
 <label htmlFor="globalSearchInputModal" className="sr-only">Search across all data</label>
 <input
 id="globalSearchInputModal"
 ref={inputRef}
 type="search"
 value={query}
 onChange={(e) => setQuery(e.target.value)}
 onKeyDown={handleKeyDown}
 className="flex-1 bg-transparent px-4 py-2 text-base text-text-primary placeholder:text-text-tertiary focus:outline-none"
 placeholder="Search across all data..."
 aria-autocomplete="list"
 />
 {isLoading && <Loader2 aria-hidden="true" size={18} strokeWidth={1.5} className="text-text-tertiary animate-spin mr-3" />}
 <button 
 type="button"
 onClick={onClose}
 className="p-1.5 rounded-md text-text-tertiary hover:bg-surface-hover hover:text-text-primary transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-info"
 aria-label="Close search"
 >
 <X aria-hidden="true" size={18} strokeWidth={1.5} />
 </button>
 </div>

 {/* Category Filters */}
 <div className="flex items-center px-2 py-2 border-b border-border bg-surface overflow-x-auto shrink-0 scrollbar-hide" role="group" aria-label="Search Categories">
 {CATEGORIES.map(category => (
 <button
 key={category.id}
 type="button"
 onClick={() => setActiveCategory(category.id)}
 className={`flex items-center whitespace-nowrap px-3 py-1.5 mx-1 rounded-full text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-info ${
 activeCategory === category.id 
 ? 'bg-surface-hover text-text-primary font-bold' 
 : 'bg-transparent text-text-secondary hover:bg-surface-hover'
 }`}
 >
 {category.label}
 </button>
 ))}
 </div>

 {/* Results Area */}
 <div ref={scrollContainerRef} className="flex-1 overflow-y-auto p-2" role="listbox" aria-label="Search Results">
 
 {/* Error State */}
 {error && (
 <div className="flex flex-col items-center justify-center py-16 text-center">
 <ServerCrash aria-hidden="true" size={32} strokeWidth={1.5} className="text-risk-critical mb-4" />
 <p className="text-section-title text-risk-critical m-0">Search Unavailable</p>
 <p className="text-body-secondary mt-1 max-w-sm m-0">{error}</p>
 </div>
 )}

 {/* Empty State */}
 {!isLoading && !error && debouncedQuery && results.length === 0 && (
 <div className="flex flex-col items-center justify-center py-16 text-center">
 <FileQuestion aria-hidden="true" size={32} strokeWidth={1.5} className="text-text-tertiary mb-4" />
 <p className="text-section-title m-0">No results found</p>
 <p className="text-body-secondary mt-1 m-0">We couldn't find anything matching "{debouncedQuery}".</p>
 </div>
 )}

 {/* Recent Searches (When no query) */}
 {!debouncedQuery && !error && (
 <div className="p-2">
 <p className="px-3 py-2 text-[11px] font-semibold text-text-tertiary uppercase tracking-wider m-0">Recent Searches</p>
 {recentSearches.map((recent, idx) => (
 <button
 key={recent}
 type="button"
 data-index={idx}
 onMouseEnter={() => setSelectedIndex(idx)}
 onClick={() => setQuery(recent)}
 role="option"
 aria-selected={selectedIndex === idx}
 className={`w-full flex items-center justify-between px-3 py-2.5 rounded-md text-sm transition-colors focus:outline-none focus:ring-2 focus:ring-info ${
 selectedIndex === idx ? 'bg-surface-hover text-text-primary font-medium' : 'text-text-secondary hover:bg-surface-hover hover:text-text-primary'
 }`}
 >
 <div className="flex items-center gap-3">
 <Clock aria-hidden="true" size={16} strokeWidth={1.5} className="text-text-tertiary" />
 <span>{recent}</span>
 </div>
 </button>
 ))}
 </div>
 )}

 {/* Results List */}
 {!isLoading && !error && results.length > 0 && (
 <div className="p-1 space-y-1">
 {results.map((result, idx) => {
 const isSelected = selectedIndex === idx;
 const Icon = getCategoryIcon(result.category);
 
 return (
 <button
 key={result.id}
 type="button"
 data-index={idx}
 onMouseEnter={() => setSelectedIndex(idx)}
 onClick={() => { const parts = result.url.split('/'); const view = parts[1] as 'vendors' | 'assessments' | 'evidence' | 'risk-events' | 'monitoring' | 'remediation' | 'decisions' | 'audit-log'; const id = parts[2]; if (view === 'vendors' && id) navigateTo('vendors', { vendorId: id }); else navigateTo(view || 'command'); onClose(); }}
 className={`w-full flex items-start gap-3 p-3 rounded-md text-left transition-colors focus:outline-none focus:ring-2 focus:ring-info ${
 isSelected ? 'bg-surface-hover border-transparent ring-1 ring-border-strong' : 'bg-transparent border border-transparent hover:bg-surface-hover'
 }`}
 role="option"
 aria-selected={isSelected}
 >
 <div aria-hidden="true" className={`mt-0.5 p-1.5 rounded-md ${isSelected ? 'bg-surface border border-border shadow-xs' : 'bg-base border border-transparent'}`}>
 <Icon size={16} strokeWidth={1.5} className={isSelected ? 'text-text-primary' : 'text-text-tertiary'} />
 </div>
 <div className="flex-1 min-w-0">
 <p className="text-sm font-medium text-text-primary truncate m-0">
 <HighlightMatch text={result.title} query={debouncedQuery} />
 </p>
 <div className="flex items-center gap-2 mt-1">
 <p className="text-xs text-text-secondary truncate m-0">
 <HighlightMatch text={result.subtitle} query={debouncedQuery} />
 </p>
 {result.metadata && (
 <>
 <span aria-hidden="true" className={`w-1 h-1 rounded-full bg-border-strong shrink-0`} />
 <span className="text-xs text-text-tertiary truncate">{result.metadata}</span>
 </>
 )}
 </div>
 </div>
 {isSelected && <ArrowRight aria-hidden="true" size={16} strokeWidth={1.5} className="text-text-secondary shrink-0 mt-2" />}
 </button>
 );
 })}
 </div>
 )}
 </div>
 
 {/* Footer */}
 <div className="px-4 py-2 border-t border-border bg-base/50 flex justify-between items-center shrink-0">
 <span className="text-[10px] text-text-tertiary">Data scope: <strong className="font-medium text-text-secondary">Development Fixtures</strong></span>
 <div className="hidden sm:flex items-center gap-3">
 <span className="text-[10px] text-text-tertiary flex items-center gap-1"><kbd className="bg-surface border border-border rounded px-1">↑</kbd><kbd className="bg-surface border border-border rounded px-1">↓</kbd> to navigate</span>
 <span className="text-[10px] text-text-tertiary flex items-center gap-1"><kbd className="bg-surface border border-border rounded px-1">Enter</kbd> to select</span>
 </div>
 </div>
 </div>
 </div>
 );
}