// src/features/ai/agentsApi.ts
import { api } from '../../shared/api/client';
import { ensureTenantContext } from '../../shared/api/session';

/**
 * AgentEngine REST contract — mirrors AgentController (backend,
 * /api/v1/agents). A run's `result` and each task's `output` are JSON
 * strings (or null), not parsed objects; callers parse defensively.
 */

export type RunMode = 'ASSIST' | 'APPROVAL' | 'AUTONOMOUS';
export type RunState = 'PENDING' | 'RUNNING' | 'WAITING_APPROVAL' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
export type TaskState = 'PENDING' | 'RUNNING' | 'WAITING_APPROVAL' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED' | 'CANCELLED';

export interface AgentTask {
 id: string;
 seq: number;
 toolKey: string;
 riskClass: string;
 state: TaskState;
 output: string | null;
 error: string | null;
 attempts: number;
}

export interface AgentRun {
 id: string;
 agentKey: string;
 objective: string;
 mode: RunMode;
 state: RunState;
 result: string | null;
 error: string | null;
 tasks: AgentTask[];
}

export interface CreateRunPayload {
 agentKey: string;
 objective: string;
 mode: RunMode;
 idempotencyKey?: string;
 /** JSON string carried into the run; the vendor.intelligence agent reads vendorId from it. */
 context?: string;
}

export const agentsApi = {
 createRun: async (payload: CreateRunPayload): Promise<AgentRun> => {
 await ensureTenantContext();
 return api.post<AgentRun>('/agents/runs', payload);
 },

 getRun: async (runId: string, signal?: AbortSignal): Promise<AgentRun> => {
 await ensureTenantContext(signal);
 return api.get<AgentRun>(`/agents/runs/${runId}`, { signal });
 },
};

/** The agent definition key for the Vendor Intelligence Brief specialist. */
export const VENDOR_INTELLIGENCE_AGENT_KEY = 'vendor.intelligence';
