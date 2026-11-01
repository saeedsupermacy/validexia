// src/shared/ui/core.tsx
import React, { useState, useRef, useEffect } from 'react';
import { Loader2, Check, Copy, LucideIcon } from 'lucide-react';

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
 variant?: 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger' | 'inverted';
 size?: 'sm' | 'md' | 'lg';
 isLoading?: boolean;
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
 ({ className = '', variant = 'primary', size = 'md', isLoading, children, disabled, type = 'button', ...props }, ref) => {
 const baseStyles = 'inline-flex items-center justify-center font-medium tracking-tight transition-all duration-150 disabled:opacity-50 disabled:pointer-events-none motion-interactive cursor-pointer';
 
 const variants = {
 primary: 'rounded-lg bg-accent text-white hover:bg-accent-hover border border-transparent',
 secondary: 'rounded-lg bg-interactive text-text-primary hover:bg-selected border border-transparent',
 outline: 'rounded-lg border border-border bg-transparent text-text-primary hover:bg-surface-hover/50',
 ghost: 'rounded-lg bg-transparent text-text-secondary hover:text-text-primary hover:bg-surface-hover',
 danger: 'rounded-lg bg-risk-critical text-white hover:opacity-90',
 inverted: 'rounded-full bg-inverse text-white hover:opacity-90 dark:text-black border border-transparent shadow-sm',
 };

 const sizes = {
 sm: 'h-7 px-2.5 text-xs font-medium',
 md: 'h-9 px-3.5 text-xs font-semibold',
 lg: 'h-10 px-5 text-sm font-semibold',
 };

 return (
 <button ref={ref} type={type} disabled={disabled || isLoading} className={`${baseStyles} ${variants[variant]} ${sizes[size]} ${className}`} {...props}>
 {isLoading && <Loader2 aria-hidden="true" size={14} strokeWidth={1.5} className="mr-2 animate-spin motion-reduce:animate-none" />}
 {children}
 </button>
 );
 }
);
Button.displayName = 'Button';

export function CopyButton({ textToCopy, label }: { textToCopy: string; label?: string }) {
 const [copied, setCopied] = useState(false);

 const handleCopy = (e: React.MouseEvent) => {
 e.stopPropagation();
 navigator.clipboard.writeText(textToCopy);
 setCopied(true);
 setTimeout(() => setCopied(false), 2000);
 };

 return (
 <button type="button" onClick={handleCopy} className="inline-flex items-center gap-1.5 px-2 py-1 rounded-md bg-surface border border-border hover:bg-surface-hover text-xs font-mono text-text-secondary hover:text-text-primary transition-all duration-150 active:scale-95" title="Copy to clipboard">
 {copied ? (
 <>
 <Check aria-hidden="true" size={12} strokeWidth={1.5} className="text-status-success animate-in fade-in duration-150" />
 <span className="text-status-success font-medium">Copied</span>
 </>
 ) : (
 <>
 <Copy aria-hidden="true" size={12} strokeWidth={1.5} className="text-text-tertiary" />
 <span>{label || textToCopy}</span>
 </>
 )}
 </button>
 );
}

export function Card({ className = '', ...props }: React.HTMLAttributes<HTMLDivElement>) {
 return <div className={`bg-surface border border-border-subtle rounded-[10px] overflow-hidden shadow-[var(--shadow-card)] ${className}`} {...props} />;
}

export function CardHeader({ className = '', ...props }: React.HTMLAttributes<HTMLDivElement>) {
 return <div className={`flex flex-col space-y-1 p-5 md:p-6 ${className}`} {...props} />;
}

export function CardTitle({ className = '', ...props }: React.HTMLAttributes<HTMLHeadingElement>) {
 return <h3 className={`text-sm font-semibold text-text-primary tracking-tight m-0 ${className}`} {...props} />;
}

export function CardContent({ className = '', ...props }: React.HTMLAttributes<HTMLDivElement>) {
 return <div className={`p-5 md:p-6 pt-0 ${className}`} {...props} />;
}

export function Badge({ className = '', children, ...props }: React.HTMLAttributes<HTMLSpanElement>) {
 return (
 <span className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium text-text-secondary bg-black/[0.04] dark:bg-white/[0.06] border border-transparent transition-colors duration-150 ${className}`} {...props}>
 {children}
 </span>
 );
}

export function StatusBadge({ status, label }: { status: 'critical' | 'high' | 'moderate' | 'low' | 'info' | 'success' | 'warning', label: string }) {
 const dotColors = {
 critical: 'bg-risk-critical animate-pulse',
 high: 'bg-risk-high',
 moderate: 'bg-risk-moderate',
 low: 'bg-risk-low',
 info: 'bg-status-info',
 success: 'bg-status-success',
 warning: 'bg-status-warning',
 };

 return (
 <div className="inline-flex items-center gap-1.5 rounded-md px-2 py-0.5 bg-black/[0.04] dark:bg-white/[0.06] border border-transparent transition-colors duration-150">
 <div aria-hidden="true" className={`w-1.5 h-1.5 rounded-full ${dotColors[status]}`} />
 <span className="text-xs font-medium text-text-primary tracking-tight">{label}</span>
 </div>
 );
}

export function Divider({ className = '' }: { className?: string }) {
 return <hr className={`border-t border-border-subtle w-full my-4 ${className}`} />;
}

export function Skeleton({ className = '', ...props }: React.HTMLAttributes<HTMLDivElement>) {
 return <div className={`animate-pulse rounded-md bg-surface-hover ${className}`} {...props} />;
}

interface EmptyStateProps {
 icon: LucideIcon;
 title: string;
 description: string;
 whatItIs: string;
 whyItsEmpty: string;
 actionLabel?: string;
 onAction?: () => void;
}

export function EmptyState({ icon: Icon, title, description, whatItIs, whyItsEmpty, actionLabel, onAction }: EmptyStateProps) {
 return (
 <div className="flex flex-col items-center justify-center py-16 px-6 text-center border border-border-subtle bg-surface-hover/30 rounded-2xl animate-in fade-in duration-200 max-w-2xl mx-auto my-8">
 <div aria-hidden="true" className="p-3 bg-surface border border-border rounded-xl shadow-sm mb-4 text-text-secondary">
 <Icon size={20} strokeWidth={1.5} />
 </div>
 
 <h3 className="text-base font-semibold text-text-primary m-0">{title}</h3>
 <p className="text-sm text-text-secondary mt-1 max-w-md m-0">{description}</p>

 <div className="w-full grid grid-cols-1 md:grid-cols-2 gap-3 mt-8 text-left text-xs bg-surface border border-border rounded-xl p-4">
 <div>
 <span className="font-medium text-text-tertiary block mb-1">What this area is</span>
 <p className="text-text-primary leading-relaxed m-0">{whatItIs}</p>
 </div>
 <div>
 <span className="font-medium text-text-tertiary block mb-1">Why it is empty</span>
 <p className="text-text-secondary leading-relaxed m-0">{whyItsEmpty}</p>
 </div>
 </div>

 {actionLabel && onAction && (
 <div className="mt-8">
 <Button type="button" variant="primary" size="md" onClick={onAction}>
 {actionLabel}
 </Button>
 </div>
 )}
 </div>
 );
}
/* Tiny uppercase eyebrow label shown above page titles (Stripe-style section eyebrows). */
export function Eyebrow({ children, className = '' }: { children: React.ReactNode; className?: string }) {
 return (
 <p className={`text-[11px] font-semibold uppercase tracking-[0.14em] text-text-tertiary mb-2 ${className}`}>
 {children}
 </p>
 );
}

/* Animated count-up number: eases from 0 to `value` when scrolled into view. */
export function CountUp({ value, duration = 900, className = '' }: { value: number; duration?: number; className?: string }) {
 const [display, setDisplay] = useState(0);
 const ref = useRef<HTMLSpanElement>(null);
 const started = useRef(false);

 useEffect(() => {
 const el = ref.current;
 if (!el) return;
 if (typeof IntersectionObserver === 'undefined') {
 setDisplay(value);
 return;
 }
 const observer = new IntersectionObserver(
 ([entry]) => {
 if (entry.isIntersecting && !started.current) {
 started.current = true;
 const start = performance.now();
 const tick = (now: number) => {
 const p = Math.min((now - start) / duration, 1);
 const eased = 1 - Math.pow(1 - p, 3);
 setDisplay(Math.round(value * eased));
 if (p < 1) requestAnimationFrame(tick);
 };
 requestAnimationFrame(tick);
 observer.disconnect();
 }
 },
 { threshold: 0.4 }
 );
 observer.observe(el);
 return () => observer.disconnect();
 }, [value, duration]);

 return (
 <span ref={ref} className={className}>
 {display}
 </span>
 );
}

/* Circular risk-score ring (UpGuard-style): 0-100 score with semantic color. */
export function ScoreRing({ score, size = 40 }: { score: number; size?: number }) {
 const r = (size - 7) / 2;
 const c = 2 * Math.PI * r;
 const clamped = Math.max(0, Math.min(100, score));
 const color =
 clamped >= 75 ? 'var(--risk-critical)'
 : clamped >= 55 ? 'var(--risk-high)'
 : clamped >= 35 ? 'var(--risk-moderate)'
 : 'var(--status-success)';
 const mid = size / 2;

 return (
 <svg
 width={size}
 height={size}
 viewBox={`0 0 ${size} ${size}`}
 role="img"
 aria-label={`Risk score ${clamped} out of 100, higher means riskier`}
 className="shrink-0"
 >
 <circle cx={mid} cy={mid} r={r} fill="none" stroke="var(--border-subtle)" strokeWidth={3.5} />
 <circle
 cx={mid}
 cy={mid}
 r={r}
 fill="none"
 stroke={color}
 strokeWidth={3.5}
 strokeLinecap="round"
 strokeDasharray={c}
 strokeDashoffset={c * (1 - clamped / 100)}
 transform={`rotate(-90 ${mid} ${mid})`}
 />
 <text
 x="50%"
 y="50%"
 dy="0.36em"
 textAnchor="middle"
 fill="var(--text-primary)"
 fontSize={Math.round(size * 0.3)}
 fontWeight={700}
 fontFamily="inherit"
 >
 {clamped}
 </text>
 </svg>
 );
}
