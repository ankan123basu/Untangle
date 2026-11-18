import type { HTMLAttributes } from 'react';
import { cn } from '@/lib/utils';

export function Badge({ className, ...rest }: HTMLAttributes<HTMLSpanElement>) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full border border-line px-2 py-0.5 text-xs font-bold tracking-wide whitespace-nowrap uppercase',
        className,
      )}
      {...rest}
    />
  );
}
