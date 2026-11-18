import { motion, useReducedMotion } from 'motion/react';
import { Check } from 'lucide-react';
import type { TicketStatus } from '@/lib/api/types';
import { cn, humanize } from '@/lib/utils';
import { STATUS_FLOW } from '../ticket-meta';

/** OPEN → IN_PROGRESS → RESOLVED → CLOSED, with an animated progress line (§12.3). */
export function StatusStepper({ status }: { status: TicketStatus }) {
  const reduce = useReducedMotion();
  const current = STATUS_FLOW.indexOf(status);
  const progress = current / (STATUS_FLOW.length - 1);
  return (
    <ol aria-label="Ticket progress" className="relative grid grid-cols-4">
      <div
        aria-hidden="true"
        className="absolute top-4 right-[12.5%] left-[12.5%] h-1 rounded-full bg-line-soft"
      >
        <motion.div
          className="h-full origin-left rounded-full bg-ink"
          initial={false}
          animate={{ scaleX: progress }}
          transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 160, damping: 22 }}
        />
      </div>
      {STATUS_FLOW.map((step, index) => {
        // The last step (Closed) is complete once reached.
        const done = index < current || (index === current && index === STATUS_FLOW.length - 1);
        const active = index === current;
        return (
          <li
            key={step}
            aria-current={active ? 'step' : undefined}
            className="relative flex flex-col items-center gap-1.5 text-center"
          >
            <motion.span
              initial={false}
              animate={{ scale: active && !reduce ? 1.12 : 1 }}
              className={cn(
                'flex size-9 items-center justify-center rounded-full border border-line text-sm font-bold',
                done && 'bg-ink text-paper',
                active && 'bg-brand-gradient text-on-brand shadow-soft-sm',
                !done && !active && 'bg-surface text-muted',
              )}
            >
              {done ? <Check aria-hidden="true" className="size-4" /> : index + 1}
            </motion.span>
            <span className={cn('text-xs font-semibold', active ? 'text-ink' : 'text-muted')}>
              {humanize(step)}
              {done && <span className="sr-only"> (done)</span>}
            </span>
          </li>
        );
      })}
    </ol>
  );
}
