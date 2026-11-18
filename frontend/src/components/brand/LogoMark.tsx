interface LogoMarkProps {
  /** Rendered width and height in pixels. */
  size?: number;
  /** Accessible name. Pass an empty string when the logo sits next to visible brand text. */
  title?: string;
}

/**
 * The Untangle mark: a knotted line that straightens out into a checkmark.
 * Phase 9 animates this same path for the login hero.
 */
export function LogoMark({ size = 40, title = 'Untangle' }: LogoMarkProps) {
  const decorative = title === '';
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 40 40"
      fill="none"
      role={decorative ? 'presentation' : 'img'}
      aria-hidden={decorative ? true : undefined}
      aria-label={decorative ? undefined : title}
    >
      <rect
        x="1.5"
        y="1.5"
        width="37"
        height="37"
        rx="9"
        className="fill-brand stroke-ink"
        strokeWidth="3"
      />
      <path
        d="M7 22c3-9 11-10 10-3s-8 9-3 10 6-3 8-5l4 4 8-13"
        className="stroke-ink"
        strokeWidth="3.2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
