import { useEffect, useRef, type ReactNode } from 'react';
import { X } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from './button';

interface DialogProps {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: string;
  children: ReactNode;
  footer?: ReactNode;
  className?: string;
}

/**
 * Accessible modal built on the native <dialog> element: the browser provides the focus trap,
 * Escape-to-close, inert background and top-layer stacking, so there's no custom focus code to get
 * wrong. Focus returns to the trigger on close.
 */
export function Dialog({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  className,
}: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      aria-labelledby="dialog-title"
      aria-describedby={description ? 'dialog-desc' : undefined}
      onClose={onClose}
      onClick={(event) => {
        if (event.target === ref.current) onClose(); // backdrop click
      }}
      className={cn(
        'glass-panel m-auto w-[min(92vw,32rem)] p-0 text-ink backdrop:bg-black/40 backdrop:backdrop-blur-sm',
        className,
      )}
    >
      <div className="flex items-start justify-between gap-4 border-b-2 border-line px-5 py-4">
        <div>
          <h2 id="dialog-title" className="font-display text-xl font-bold">
            {title}
          </h2>
          {description && (
            <p id="dialog-desc" className="mt-1 text-sm text-muted">
              {description}
            </p>
          )}
        </div>
        <Button variant="ghost" size="icon" aria-label="Close dialog" onClick={onClose}>
          <X className="size-5" />
        </Button>
      </div>
      <div className="px-5 py-4">{children}</div>
      {footer && (
        <div className="flex justify-end gap-2 border-t-2 border-line px-5 py-3">{footer}</div>
      )}
    </dialog>
  );
}
