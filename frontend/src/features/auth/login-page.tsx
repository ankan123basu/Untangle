import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { Lock, LogIn } from 'lucide-react';
import { LogoMark } from '@/components/brand/LogoMark';
import ShinyText from '@/components/reactbits/ShinyText';
import { Button } from '@/components/ui/button';
import { Field, Input } from '@/components/ui/field';
import { type ApiError, messageFor, toApiError } from '@/lib/api/problem';
import { formatDateTime } from '@/lib/utils';
import { login } from './auth-api';
import { useSession } from './session-store';
import { LoginHero } from './login-hero';

const schema = z.object({
  email: z
    .string()
    .trim()
    .min(1, 'Enter your work email')
    .pipe(z.email('That doesn’t look like an email')),
  password: z.string().min(1, 'Enter your password'),
});
type LoginForm = z.infer<typeof schema>;

/**
 * Screen 1 (§12.5): secure entry. Distinct, friendly states for wrong credentials, a locked account
 * (with the unlock time) and a deactivated account.
 */
export default function LoginPage() {
  const status = useSession((s) => s.status);
  const navigate = useNavigate();
  const location = useLocation();
  const [failure, setFailure] = useState<ApiError | null>(null);
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginForm>({
    resolver: zodResolver(schema),
    defaultValues: { email: '', password: '' },
  });

  const from = (location.state as { from?: string } | null)?.from ?? '/';
  if (status === 'authenticated') return <Navigate to={from} replace />;

  const onSubmit = handleSubmit(async ({ email, password }) => {
    setFailure(null);
    try {
      await login(email, password);
      void navigate(from, { replace: true });
    } catch (error) {
      setFailure(toApiError(error));
    }
  });

  const lockedUntil =
    failure?.code === 'AUTH_ACCOUNT_LOCKED' ? failure.extra.lockedUntil : undefined;

  return (
    <main className="relative grid min-h-dvh overflow-hidden bg-paper lg:grid-cols-[1.15fr_1fr]">
      <LoginHero />
      <section className="relative z-10 flex items-center justify-center p-6 sm:p-10">
        <div className="glass-panel w-full max-w-md p-8">
          <div className="mb-8 flex items-center gap-3">
            <LogoMark size={44} title="" />
            <div>
              <h1 className="font-display text-3xl font-bold tracking-tight">Untangle</h1>
              <p className="text-sm text-muted">
                <ShinyText
                  text="Every complaint, untangled."
                  speed={3}
                  color="var(--ut-muted)"
                  shineColor="var(--ut-ink)"
                />
              </p>
            </div>
          </div>

          <form
            noValidate
            onSubmit={(e) => void onSubmit(e)}
            className="flex flex-col gap-4"
            aria-describedby={failure ? 'login-error' : undefined}
          >
            {failure && (
              <div
                id="login-error"
                role="alert"
                className="flex items-start gap-2 rounded-md border-2 border-danger bg-surface p-3 text-sm font-medium text-danger"
              >
                <Lock aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
                <span>
                  {messageFor(failure)}
                  {typeof lockedUntil === 'string' && (
                    <> Try again after {formatDateTime(lockedUntil)}.</>
                  )}
                </span>
              </div>
            )}

            <Field label="Work email" error={errors.email?.message} required>
              {(props) => (
                <Input
                  type="email"
                  autoComplete="username"
                  autoFocus
                  placeholder="you@company.com"
                  {...props}
                  {...register('email')}
                />
              )}
            </Field>
            <Field label="Password" error={errors.password?.message} required>
              {(props) => (
                <Input
                  type="password"
                  autoComplete="current-password"
                  {...props}
                  {...register('password')}
                />
              )}
            </Field>
            <Button
              type="submit"
              variant="primary"
              size="lg"
              loading={isSubmitting}
              icon={<LogIn className="size-4" />}
            >
              Sign in
            </Button>
          </form>
          <p className="mt-6 text-xs text-muted">
            No sign-up: an admin creates your account. Demo:{' '}
            <span className="font-mono">admin@untangle.local</span> /{' '}
            <span className="font-mono">Untangle@2026</span>
          </p>
        </div>
      </section>
    </main>
  );
}
