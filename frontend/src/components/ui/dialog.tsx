import { useEffect, useId, useRef, type ReactNode } from 'react';
import { X } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from './button';

interface DialogProps {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: string | undefined;
  children: ReactNode;
  footer?: ReactNode | undefined;
  className?: string | undefined;
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
  // Unique per dialog: pages render several dialogs, and duplicate ids would give them all the
  // first dialog's accessible name.
  const titleId = useId();
  const descId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      dialog.showModal();
      // React applies autoFocus at mount (while the dialog is still closed), and showModal() then
      // focuses the close button. Move focus to the first field so the user can type at once.
      dialog
        .querySelector<HTMLElement>(
          '[data-autofocus], input:not([type="hidden"]):not([disabled]), textarea:not([disabled]), select:not([disabled])',
        )
        ?.focus();
    }
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      aria-describedby={description ? descId : undefined}
      onClose={onClose}
      onClick={(event) => {
        if (event.target === ref.current) onClose(); // backdrop click
      }}
      className={cn(
        'solid-panel m-auto w-[min(92vw,32rem)] p-0 text-ink backdrop:bg-black/35 backdrop:backdrop-blur-md',
        className,
      )}
    >
      <div className="flex items-start justify-between gap-4 border-b border-line px-5 py-4">
        <div>
          <h2 id={titleId} className="font-display text-xl font-bold">
            {title}
          </h2>
          {description && (
            <p id={descId} className="mt-1 text-sm text-muted">
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
        <div className="flex justify-end gap-2 border-t border-line px-5 py-3">{footer}</div>
      )}
    </dialog>
  );
}
