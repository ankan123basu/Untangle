import {
  forwardRef,
  useId,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';
import { cn } from '@/lib/utils';

const control =
  'w-full rounded-md border border-field-line bg-surface px-3 text-ink placeholder:text-muted ' +
  'shadow-[inset_0_1px_2px_rgb(0_0_0/0.06)] backdrop-blur-md transition-[box-shadow,border-color] ' +
  'duration-150 hover:border-accent focus-visible:border-accent focus-visible:outline-none ' +
  'focus-visible:ring-4 focus-visible:ring-accent/25 aria-[invalid=true]:border-danger ' +
  'disabled:cursor-not-allowed disabled:opacity-60';

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className, ...rest }, ref) {
    return <input ref={ref} className={cn(control, 'h-10', className)} {...rest} />;
  },
);

export const Textarea = forwardRef<
  HTMLTextAreaElement,
  TextareaHTMLAttributes<HTMLTextAreaElement>
>(function Textarea({ className, ...rest }, ref) {
  return (
    <textarea
      ref={ref}
      className={cn(control, 'min-h-24 py-2 leading-relaxed', className)}
      {...rest}
    />
  );
});

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(
  function Select({ className, children, ...rest }, ref) {
    return (
      <select ref={ref} className={cn(control, 'h-10 cursor-pointer pr-8', className)} {...rest}>
        {children}
      </select>
    );
  },
);

interface FieldProps {
  label: string;
  error?: string | undefined;
  hint?: string | undefined;
  required?: boolean | undefined;
  /** Receives the generated id and aria props to spread on the control. */
  children: (props: {
    id: string;
    'aria-invalid': boolean;
    'aria-describedby'?: string;
  }) => ReactNode;
  className?: string | undefined;
}

/** Label + control + hint/error, wired up for screen readers (aria-invalid, aria-describedby). */
export function Field({ label, error, hint, required, children, className }: FieldProps) {
  const id = useId();
  const messageId = `${id}-msg`;
  const describedBy = error || hint ? messageId : undefined;
  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      <label htmlFor={id} className="text-sm font-semibold">
        {label}
        {required && (
          <span aria-hidden="true" className="ml-0.5 text-danger">
            *
          </span>
        )}
      </label>
      {children({
        id,
        'aria-invalid': Boolean(error),
        ...(describedBy ? { 'aria-describedby': describedBy } : {}),
      })}
      {error ? (
        <p id={messageId} role="alert" className="text-sm font-medium text-danger">
          {error}
        </p>
      ) : hint ? (
        <p id={messageId} className="text-sm text-muted">
          {hint}
        </p>
      ) : null}
    </div>
  );
}
