import type { HTMLAttributes } from 'react';
import { cn } from '@/lib/utils';

export function Badge({ className, ...rest }: HTMLAttributes<HTMLSpanElement>) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-sm border-2 border-line px-1.5 py-0.5 text-xs font-bold tracking-wide whitespace-nowrap uppercase',
        className,
      )}
      {...rest}
    />
  );
}
