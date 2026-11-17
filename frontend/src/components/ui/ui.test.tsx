import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Button } from './button';
import { Field, Input } from './field';

describe('design-system primitives', () => {
  it('Field links label, error and control for screen readers', () => {
    render(
      <Field label="Subject" error="Too short">
        {(props) => <Input {...props} />}
      </Field>,
    );

    const input = screen.getByLabelText('Subject');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Too short');
  });

  it('Button is disabled and announced as busy while loading', () => {
    render(<Button loading>Save</Button>);
    const button = screen.getByRole('button', { name: 'Save' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
  });

  it('Button defaults to type="button" so it never submits a form by accident', () => {
    render(<Button>Cancel</Button>);
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveAttribute('type', 'button');
  });
});
