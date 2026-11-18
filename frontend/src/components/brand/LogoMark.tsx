import { useId } from 'react';

interface LogoMarkProps {
  /** Rendered width and height in pixels. */
  size?: number;
  /** Accessible name. Pass an empty string when the logo sits next to visible brand text. */
  title?: string;
}

/**
 * The Untangle mark: a knotted line that straightens out into a checkmark, in white on a glossy
 * tile of the current colour theme's gradient. The login hero animates this same idea in 3D.
 */
export function LogoMark({ size = 40, title = 'Untangle' }: LogoMarkProps) {
  const decorative = title === '';
  // Unique per instance: several logos can be on screen, and gradient ids are document-global.
  const id = useId();
  const fill = `${id}-fill`;
  const shine = `${id}-shine`;
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
      <defs>
        <linearGradient id={fill} x1="0" y1="0" x2="40" y2="40" gradientUnits="userSpaceOnUse">
          <stop style={{ stopColor: 'var(--ut-a2)' }} />
          <stop offset="1" style={{ stopColor: 'var(--ut-a1)' }} />
        </linearGradient>
        <linearGradient id={shine} x1="0" y1="0" x2="0" y2="22" gradientUnits="userSpaceOnUse">
          <stop stopColor="#ffffff" stopOpacity="0.45" />
          <stop offset="1" stopColor="#ffffff" stopOpacity="0" />
        </linearGradient>
      </defs>
      <rect x="1" y="1" width="38" height="38" rx="11" fill={`url(#${fill})`} />
      <rect x="1" y="1" width="38" height="20" rx="11" fill={`url(#${shine})`} />
      <rect
        x="1.5"
        y="1.5"
        width="37"
        height="37"
        rx="10.5"
        stroke="#ffffff"
        strokeOpacity="0.35"
      />
      <path
        d="M7 22c3-9 11-10 10-3s-8 9-3 10 6-3 8-5l4 4 8-13"
        stroke="#ffffff"
        strokeWidth="3.2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
