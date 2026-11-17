import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { App } from './App';

describe('App (Phase 0 placeholder)', () => {
  it('renders the brand name as the page heading', () => {
    render(<App />);
    expect(screen.getByRole('heading', { level: 1, name: 'Untangle' })).toBeInTheDocument();
  });

  it('shows the tagline', () => {
    render(<App />);
    expect(screen.getByText('Every complaint, untangled.')).toBeInTheDocument();
  });

  it('labels the brand section by its heading for screen readers', () => {
    render(<App />);
    expect(screen.getByRole('region', { name: 'Untangle' })).toBeInTheDocument();
  });
});
