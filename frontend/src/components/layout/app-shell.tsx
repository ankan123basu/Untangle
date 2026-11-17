import { useState, type ReactNode } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router';
import {
  Building2,
  LayoutDashboard,
  ListChecks,
  LogOut,
  Monitor,
  Moon,
  PlusCircle,
  Search,
  Sun,
  Users,
  Webhook,
} from 'lucide-react';
import { LogoMark } from '@/components/brand/LogoMark';
import { Button } from '@/components/ui/button';
import { Kbd } from '@/components/ui/feedback';
import { logout } from '@/features/auth/auth-api';
import { useCan, useSession } from '@/features/auth/session-store';
import { useTheme, type ThemeChoice } from '@/hooks/use-theme';
import { cn, humanize } from '@/lib/utils';
import { NotificationBell } from '@/features/notifications/notification-bell';
import { CommandPalette } from './command-palette';
import { OfflineBanner } from './offline-banner';

function NavItem({
  to,
  icon,
  children,
  end,
}: {
  to: string;
  icon: ReactNode;
  children: ReactNode;
  end?: boolean;
}) {
  return (
    <NavLink
      to={to}
      end={end ?? false}
      className={({ isActive }) =>
        cn(
          'flex items-center gap-3 rounded-md border-2 px-3 py-2 text-sm font-semibold transition-colors',
          isActive
            ? 'border-line bg-brand text-on-brand shadow-hard-sm'
            : 'border-transparent hover:bg-paper-2',
        )
      }
    >
      {icon}
      <span>{children}</span>
    </NavLink>
  );
}

const THEMES: { value: ThemeChoice; icon: ReactNode; label: string }[] = [
  { value: 'light', icon: <Sun className="size-4" />, label: 'Light theme' },
  { value: 'dark', icon: <Moon className="size-4" />, label: 'Dark theme' },
  { value: 'system', icon: <Monitor className="size-4" />, label: 'Follow system theme' },
];

function ThemeToggle() {
  const [theme, setTheme] = useTheme();
  const index = THEMES.findIndex((t) => t.value === theme);
  const current = THEMES[index] ?? THEMES[2];
  const next = THEMES[(index + 1) % THEMES.length] ?? THEMES[0];
  if (!current || !next) return null;
  return (
    <Button
      variant="ghost"
      size="icon"
      aria-label={`${current.label}. Switch to ${next.label.toLowerCase()}`}
      title={current.label}
      onClick={() => {
        setTheme(next.value);
      }}
    >
      {current.icon}
    </Button>
  );
}

/**
 * The frame around every signed-in screen: navigation, search (command palette), notifications,
 * theme and account. Admin links appear only for people who can use them.
 */
export function AppShell() {
  const agent = useSession((s) => s.agent);
  const canManageAgents = useCan('AGENT_MANAGE');
  const canManagePartners = useCan('PARTNER_MANAGE');
  const navigate = useNavigate();
  const [paletteOpen, setPaletteOpen] = useState(false);

  return (
    <div className="min-h-dvh bg-paper">
      <a
        href="#main"
        className="sr-only-focusable absolute top-2 left-2 z-50 rounded-md bg-brand px-3 py-2 font-semibold"
      >
        Skip to content
      </a>
      <OfflineBanner />
      <div className="mx-auto grid max-w-[1500px] gap-6 p-4 lg:grid-cols-[230px_1fr] lg:p-6">
        <aside className="lg:sticky lg:top-6 lg:h-[calc(100dvh-3rem)]">
          <nav aria-label="Main" className="solid-panel flex h-full flex-col gap-1 p-3">
            <NavLink to="/" className="mb-4 flex items-center gap-2 px-2 pt-1">
              <LogoMark size={32} title="" />
              <span className="font-display text-xl font-bold">Untangle</span>
            </NavLink>
            <NavItem to="/" end icon={<LayoutDashboard className="size-4" />}>
              Dashboard
            </NavItem>
            <NavItem to="/tickets" end icon={<ListChecks className="size-4" />}>
              Tickets
            </NavItem>
            <NavItem to="/tickets/new" icon={<PlusCircle className="size-4" />}>
              New ticket
            </NavItem>
            {(canManageAgents || canManagePartners) && (
              <p className="mt-4 px-3 text-xs font-bold tracking-wider text-muted uppercase">
                Admin
              </p>
            )}
            {canManageAgents && (
              <NavItem to="/admin/agents" icon={<Users className="size-4" />}>
                Agents
              </NavItem>
            )}
            {canManagePartners && (
              <>
                <NavItem to="/admin/partners" icon={<Building2 className="size-4" />}>
                  Partners
                </NavItem>
                <NavItem to="/admin/webhooks" icon={<Webhook className="size-4" />}>
                  Webhook logs
                </NavItem>
              </>
            )}
            <div className="mt-auto border-t-2 border-line-soft pt-3">
              <p className="truncate px-3 text-sm font-semibold">{agent?.fullName}</p>
              <p className="px-3 text-xs text-muted">{agent ? humanize(agent.role) : ''}</p>
              <Button
                variant="ghost"
                className="mt-2 w-full justify-start"
                icon={<LogOut className="size-4" />}
                onClick={() => {
                  void logout().then(() => navigate('/login'));
                }}
              >
                Sign out
              </Button>
            </div>
          </nav>
        </aside>

        <div className="flex min-w-0 flex-col gap-6">
          <header className="solid-panel flex items-center gap-3 px-4 py-2">
            <button
              type="button"
              onClick={() => {
                setPaletteOpen(true);
              }}
              className="flex h-10 flex-1 items-center gap-2 rounded-md border-2 border-line-soft bg-paper px-3 text-left text-sm text-muted hover:border-line"
            >
              <Search aria-hidden="true" className="size-4" />
              <span className="flex-1">Search tickets, jump to UNT-…, create…</span>
              <Kbd>⌘K</Kbd>
            </button>
            <NotificationBell />
            <ThemeToggle />
          </header>
          <main id="main" tabIndex={-1} className="min-w-0 outline-none">
            <Outlet />
          </main>
        </div>
      </div>
      <CommandPalette open={paletteOpen} onOpenChange={setPaletteOpen} />
    </div>
  );
}
