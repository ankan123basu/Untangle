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
  primary: 'bg-brand text-on-brand border-line shadow-hard-sm',
  secondary: 'bg-surface text-ink border-line shadow-hard-sm',
  ghost: 'bg-transparent text-ink border-transparent hover:bg-paper-2 shadow-none',
  danger: 'bg-danger text-on-danger border-line shadow-hard-sm',
};

const sizes: Record<Size, string> = {
  sm: 'h-8 px-3 text-sm',
  md: 'h-10 px-4 text-sm',
  lg: 'h-12 px-6 text-base',
  icon: 'h-10 w-10 justify-center p-0',
};

/**
 * The brutalist button: a hard border and offset shadow that "presses" flat when clicked (the
 * shadow collapses while the button moves into its place).
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
        'inline-flex items-center gap-2 rounded-md border-2 font-semibold whitespace-nowrap select-none',
        'transition-[transform,box-shadow,background-color] duration-150 ease-brutal',
        'enabled:active:translate-x-[2px] enabled:active:translate-y-[2px] enabled:active:shadow-none',
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
