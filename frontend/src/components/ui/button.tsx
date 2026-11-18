import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { cn } from '@/lib/utils';
import { Spinner } from './spinner';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';
type Size = 'sm' | 'md' | 'lg' | 'icon';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
  icon?: ReactNode;
}

const variants: Record<Variant, string> = {
  primary:
    'btn-glow bg-brand-gradient text-on-brand border-white/25 enabled:hover:-translate-y-px enabled:hover:brightness-110',
  secondary:
    'bg-surface text-ink border-glass-edge shadow-soft-sm backdrop-blur-md enabled:hover:bg-glass-strong',
  ghost: 'bg-transparent text-ink border-transparent hover:bg-paper-2 shadow-none',
  danger: 'bg-danger text-on-danger border-transparent shadow-soft-sm enabled:hover:brightness-110',
};

const sizes: Record<Size, string> = {
  sm: 'h-8 px-3 text-sm',
  md: 'h-10 px-4 text-sm',
  lg: 'h-12 px-6 text-base',
  icon: 'h-10 w-10 justify-center p-0',
};

/**
 * The glass pill button. Primary actions use the accent gradient; the rest are frosted. It lifts
 * slightly on hover and sinks a little when pressed (transform only, so it stays cheap).
 */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'secondary',
    size = 'md',
    loading = false,
    icon,
    className,
    children,
    disabled,
    type,
    ...rest
  },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type ?? 'button'}
      disabled={disabled ?? loading}
      aria-busy={loading || undefined}
      className={cn(
        'inline-flex items-center gap-2 rounded-full border font-semibold whitespace-nowrap select-none',
        'transition-[transform,box-shadow,background-color,filter] duration-150 ease-smooth',
        'enabled:hover:-translate-y-px enabled:active:translate-y-0 enabled:active:scale-[0.98]',
        'disabled:cursor-not-allowed disabled:opacity-50',
        variants[variant],
        sizes[size],
        className,
      )}
      {...rest}
    >
      {loading ? <Spinner className="size-4" /> : icon}
      {children}
    </button>
  );
});
