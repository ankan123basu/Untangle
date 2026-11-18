import { useId, type ReactElement, type ReactNode, cloneElement } from 'react';

/**
 * Minimal accessible tooltip: shown on hover AND keyboard focus, linked with aria-describedby so
 * screen readers announce it. Used to explain why an action is disabled (§12.5).
 */
export function Tooltip({
  content,
  children,
}: {
  content: ReactNode;
  children: ReactElement<{ 'aria-describedby'?: string }>;
}) {
  const id = useId();
  if (!content) return children;
  return (
    <span className="group relative inline-flex">
      {cloneElement(children, { 'aria-describedby': id })}
      <span
        role="tooltip"
        id={id}
        className="pointer-events-none absolute bottom-[calc(100%+8px)] left-1/2 z-50 w-max max-w-64 -translate-x-1/2 rounded-sm border-2 border-line bg-ink px-2 py-1 text-xs font-medium text-paper opacity-0 shadow-hard-sm transition-opacity duration-150 group-focus-within:opacity-100 group-hover:opacity-100"
      >
        {content}
      </span>
    </span>
  );
}
