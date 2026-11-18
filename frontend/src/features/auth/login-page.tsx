import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { Eye, EyeOff, Lock, LogIn } from 'lucide-react';
import { LogoMark } from '@/components/brand/LogoMark';
import { Button } from '@/components/ui/button';
import { Field, Input } from '@/components/ui/field';
import { type ApiError, messageFor, toApiError } from '@/lib/api/problem';
import { formatDateTime } from '@/lib/utils';
import { login } from './auth-api';
import { useSession } from './session-store';
import { AppearanceMenu } from '@/components/layout/appearance-menu';
import { AppBackdrop } from '@/components/layout/app-backdrop';
import { LoginHero } from './login-hero';

/** Demo seed accounts (db/seed-demo). Hide the panel with VITE_SHOW_DEMO_ACCOUNTS=false. */
const SHOW_DEMO = import.meta.env.VITE_SHOW_DEMO_ACCOUNTS !== 'false';
const DEMO_PASSWORD = 'Untangle@2026';
const DEMO_ACCOUNTS = [
  { role: 'Admin', email: 'admin@untangle.local', hint: 'Full access', tone: 'bg-stk-pink' },
  {
    role: 'Lead',
    email: 'leela.thomas@untangle.local',
    hint: 'Team & workload',
    tone: 'bg-stk-mint',
  },
  { role: 'Agent', email: 'riya.sen@untangle.local', hint: 'Own tickets', tone: 'bg-stk-sky' },
] as const;

/** Everyone in the demo team, for trying notifications and permissions between two people. */
const DEMO_PEOPLE = [
  { name: 'Asha Verma', role: 'Admin', email: 'admin@untangle.local' },
  { name: 'Leela Thomas', role: 'Lead', email: 'leela.thomas@untangle.local' },
  { name: 'Nikhil Bose', role: 'Lead', email: 'nikhil.bose@untangle.local' },
  { name: 'Riya Sen', role: 'Agent', email: 'riya.sen@untangle.local' },
  { name: 'Dev Malhotra', role: 'Agent', email: 'dev.malhotra@untangle.local' },
  { name: 'Omar Siddiqui', role: 'Agent', email: 'omar.siddiqui@untangle.local' },
  { name: 'Tanvi Shah', role: 'Agent', email: 'tanvi.shah@untangle.local' },
  { name: 'Zoya Ali', role: 'Agent', email: 'zoya.ali@untangle.local' },
  { name: 'Harish Nair', role: 'Agent', email: 'harish.nair@untangle.local' },
] as const;

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
  const [showPassword, setShowPassword] = useState(false);
  const {
    register,
    handleSubmit,
    setValue,
    setFocus,
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

  const pick = (email: string) => {
    setValue('email', email, { shouldValidate: true });
    setValue('password', DEMO_PASSWORD, { shouldValidate: true });
    setFailure(null);
    setFocus('password');
  };

  const lockedUntil =
    failure?.code === 'AUTH_ACCOUNT_LOCKED' ? failure.extra.lockedUntil : undefined;

  return (
    <main className="relative isolate grid min-h-dvh overflow-hidden lg:grid-cols-[1.15fr_1fr]">
      <AppBackdrop />
      <LoginHero />
      <section className="relative z-10 flex items-center justify-center px-4 pt-20 pb-10 sm:px-10">
        <AppearanceMenu className="absolute top-4 right-4 z-20" />
        <div className="w-full max-w-md">
          <div className="solid-panel p-8 shadow-hard-lg">
            {/* On small screens the hero is hidden, so the brand lives here instead. */}
            <div className="mb-6 flex items-center gap-3 lg:hidden">
              <LogoMark size={40} title="" />
              <div>
                <p className="font-display text-2xl font-bold tracking-tight">Untangle</p>
                <p className="text-sm font-semibold text-muted">Every complaint, untangled.</p>
              </div>
            </div>
            <h1 className="font-display text-4xl font-bold tracking-tight">
              Welcome <span className="highlight">back</span>
            </h1>
            <p className="mt-1 mb-6 text-muted">Sign in with your work account.</p>

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
                  <div className="relative">
                    <Input
                      type={showPassword ? 'text' : 'password'}
                      autoComplete="current-password"
                      className="pr-11"
                      {...props}
                      {...register('password')}
                    />
                    <button
                      type="button"
                      aria-label={showPassword ? 'Hide the password' : 'Show the password'}
                      aria-pressed={showPassword}
                      onClick={() => {
                        setShowPassword((v) => !v);
                      }}
                      className="absolute top-1/2 right-2 flex size-8 -translate-y-1/2 items-center justify-center rounded-sm text-muted hover:bg-paper-2 hover:text-ink"
                    >
                      {showPassword ? (
                        <EyeOff aria-hidden="true" className="size-4" />
                      ) : (
                        <Eye aria-hidden="true" className="size-4" />
                      )}
                    </button>
                  </div>
                )}
              </Field>
              <Button
                type="submit"
                variant="primary"
                size="lg"
                className="mt-2 justify-center"
                loading={isSubmitting}
                icon={<LogIn className="size-4" />}
              >
                Sign in
              </Button>
            </form>

            {SHOW_DEMO && (
              <section
                aria-labelledby="demo-title"
                className="mt-6 rounded-md border-2 border-dashed border-line bg-paper-2 p-4"
              >
                <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
                  <h2 id="demo-title" className="text-sm font-bold">
                    Try the demo
                  </h2>
                  <p className="text-xs text-muted">
                    Password <span className="font-mono text-ink">{DEMO_PASSWORD}</span>
                  </p>
                </div>
                <div className="mt-3 grid grid-cols-3 gap-2">
                  {DEMO_ACCOUNTS.map((account) => (
                    <button
                      key={account.role}
                      type="button"
                      title={`${account.email}: ${account.hint}`}
                      onClick={() => {
                        pick(account.email);
                      }}
                      className={`sticker press flex flex-col items-start rounded-md border-2 border-[#111] px-3 py-2 text-left shadow-[3px_3px_0_0_#111] ${account.tone}`}
                    >
                      <span className="text-sm font-bold">{account.role}</span>
                      <span className="text-[11px] leading-tight text-muted">{account.hint}</span>
                    </button>
                  ))}
                </div>
                <details className="mt-3 text-sm">
                  <summary className="cursor-pointer font-semibold">
                    All demo people ({DEMO_PEOPLE.length})
                  </summary>
                  <ul className="mt-2 grid gap-1 sm:grid-cols-2">
                    {DEMO_PEOPLE.map((person) => (
                      <li key={person.email}>
                        <button
                          type="button"
                          title={person.email}
                          onClick={() => {
                            pick(person.email);
                          }}
                          className="flex w-full items-center justify-between gap-2 rounded-sm px-2 py-1 text-left hover:bg-paper-2"
                        >
                          <span className="truncate">{person.name}</span>
                          <span className="text-xs text-muted">{person.role}</span>
                        </button>
                      </li>
                    ))}
                  </ul>
                </details>
                <p className="mt-2 text-xs text-muted">Picks fill the form; press Sign in.</p>
              </section>
            )}
          </div>
          <p className="mt-5 text-center text-sm text-muted">
            No public sign-up. Your admin creates your account.
          </p>
        </div>
      </section>
    </main>
  );
}
