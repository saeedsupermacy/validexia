// src/features/command-center/MaterialChangesTimeline.tsx
import { useState } from 'react';
import { Activity, Filter } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '../../shared/ui/core';
import { MaterialChangeEvent } from './MaterialChangeEvent';
import { getMaterialChanges } from '../../data/materialChanges';

export function MaterialChangesTimeline() {
 const [selectedFilter, setSelectedFilter] = useState<string>('all');

 const events = getMaterialChanges();
 const filteredEvents = events.filter(event => {
 if (selectedFilter === 'all') return true;
 if (selectedFilter === 'critical') return event.severity === 'critical' || event.severity === 'high';
 return event.eventType === selectedFilter;
 });

 return (
 <Card className="flex flex-col h-full shadow-sm" id="material-changes-feed">
 <CardHeader className="border-b border-border px-6 py-4 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
 <div>
 <CardTitle className="flex items-center gap-2">
 <Activity aria-hidden="true" size={18} className="text-text-secondary" strokeWidth={1.75} />
 Material Changes & Decision Feed
 </CardTitle>
 <p className="text-xs text-text-secondary mt-1">
 What changed across your portfolio — risk scores, evidence, remediations, contracts — newest first. Things you do in the app appear here too.
 </p>
 </div>

 <div className="flex flex-nowrap items-center gap-1.5 overflow-x-auto" role="group" aria-label="Timeline Filters">
 <div className="flex items-center gap-1 text-xs text-text-tertiary mr-1 font-semibold">
 <Filter aria-hidden="true" size={12} />
 <span>Filter:</span>
 </div>
 <button
 type="button"
 onClick={() => setSelectedFilter('all')}
 className={`px-2.5 py-1 rounded-md text-xs font-semibold transition-colors focus:outline-none border ${
 selectedFilter === 'all' 
 ? 'bg-surface-hover text-text-primary border-accent shadow-sm ring-1 ring-accent/30' 
 : 'bg-base text-text-secondary border-transparent hover:text-text-primary hover:bg-surface-hover'
 }`}
 >
 All
 </button>
 <button
 type="button"
 onClick={() => setSelectedFilter('critical')}
 className={`px-2.5 py-1 rounded-md text-xs font-semibold transition-colors focus:outline-none border ${
 selectedFilter === 'critical' 
 ? 'bg-surface-hover text-text-primary border-accent shadow-sm ring-1 ring-accent/30' 
 : 'bg-base text-text-secondary border-transparent hover:text-text-primary hover:bg-surface-hover'
 }`}
 >
 Critical+High
 </button>
 <button
 type="button"
 onClick={() => setSelectedFilter('risk_change')}
 className={`px-2.5 py-1 rounded-md text-xs font-semibold transition-colors focus:outline-none border ${
 selectedFilter === 'risk_change' 
 ? 'bg-surface-hover text-text-primary border-accent shadow-sm ring-1 ring-accent/30' 
 : 'bg-base text-text-secondary border-transparent hover:text-text-primary hover:bg-surface-hover'
 }`}
 >
 Risk Shifts
 </button>
 </div>
 </CardHeader>

 <CardContent className="p-6 flex-1">
 <div className="relative pl-5 space-y-2">
 <div className="absolute left-8.5 top-4 bottom-4 w-0.5 bg-border-strong -z-0" aria-hidden="true" />

 {filteredEvents.length > 0 ? (
 filteredEvents.map(event => (
 <MaterialChangeEvent key={event.id} event={event} />
 ))
 ) : (
 <div className="py-12 text-center text-sm text-text-tertiary">
 No material changes match the selected filter criteria.
 </div>
 )}
 </div>
 </CardContent>
 </Card>
 );
}