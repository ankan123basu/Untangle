import { Bot } from 'lucide-react';
import { cn } from '@/lib/utils';

/** Marks the AI teammate wherever a person's name appears (ADR-028). */
export function BotBadge({ className }: { className?: string }) {
  return (
    <span
      title="AI teammate"
      className={cn(
        'inline-flex items-center gap-0.5 rounded-sm border-2 border-[#111] bg-[#b79bff] px-1 py-px align-middle text-[10px] leading-none font-extrabold tracking-wide text-[#111] uppercase',
        className,
      )}
    >
      <Bot aria-hidden="true" className="size-3" strokeWidth={2.5} />
      AI
    </span>
  );
}
