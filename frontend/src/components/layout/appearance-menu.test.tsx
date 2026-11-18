import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { AppearanceMenu } from './appearance-menu';

const html = document.documentElement;

describe('appearance menu (ADR-027)', () => {
  afterEach(() => {
    localStorage.clear();
    html.removeAttribute('data-theme');
    html.removeAttribute('data-accent');
  });

  it('offers twelve colour themes and applies and remembers the picked one', async () => {
    const user = userEvent.setup();
    render(<AppearanceMenu />);

    await user.click(screen.getByRole('button', { name: 'Appearance' }));
    const panel = screen.getByRole('dialog', { name: 'Appearance' });
    expect(within(panel).getAllByRole('radio')).toHaveLength(12);
    expect(within(panel).getByRole('radio', { name: 'Banana' })).toBeChecked();

    await user.click(within(panel).getByRole('radio', { name: 'Mint' }));

    expect(html).toHaveAttribute('data-accent', 'mint');
    expect(localStorage.getItem('untangle.accent')).toBe('mint');
    expect(within(panel).getByRole('radio', { name: 'Mint' })).toBeChecked();
  });

  it('switches light and dark mode', async () => {
    const user = userEvent.setup();
    render(<AppearanceMenu />);
    await user.click(screen.getByRole('button', { name: 'Appearance' }));

    await user.click(screen.getByRole('button', { name: 'Dark' }));
    expect(html).toHaveAttribute('data-theme', 'dark');
    expect(screen.getByRole('button', { name: 'Dark' })).toHaveAttribute('aria-pressed', 'true');

    await user.click(screen.getByRole('button', { name: 'System' }));
    expect(html).not.toHaveAttribute('data-theme');
    expect(localStorage.getItem('untangle.theme')).toBeNull();
  });

  it('lets people switch the 3D scenes off, and closes on Escape', async () => {
    const user = userEvent.setup();
    render(<AppearanceMenu />);
    await user.click(screen.getByRole('button', { name: 'Appearance' }));

    const toggle = screen.getByRole('switch', { name: '3D scenes' });
    expect(toggle).toHaveAttribute('aria-checked', 'true');
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    expect(localStorage.getItem('untangle.scene3d')).toBe('off');

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Appearance' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Appearance' })).toHaveFocus();
  });
});
