import { lazy, Suspense, useCallback, useEffect, useState, type ReactNode } from 'react';
import { NavLink, Outlet, useLocation, useNavigate, useNavigation } from 'react-router';
import {
  Building2,
  Columns3,
  Menu,
  X,
  LayoutDashboard,
  ListChecks,
  LogOut,
  PlusCircle,
  Search,
  Users,
  Webhook,
} from 'lucide-react';
import { LogoMark } from '@/components/brand/LogoMark';
import { Button } from '@/components/ui/button';
import { Kbd } from '@/components/ui/feedback';
import { logout } from '@/features/auth/auth-api';
import { useCan, useSession } from '@/features/auth/session-store';
import { useGlobalShortcuts } from '@/hooks/use-global-shortcuts';
import { cn, humanize } from '@/lib/utils';
import { NotificationBell } from '@/features/notifications/notification-bell';
import { AppBackdrop } from './app-backdrop';
import { AppearanceMenu } from './appearance-menu';

const CommandPalette = lazy(() => import('./command-palette'));
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

/**
 * The frame around every signed-in screen: navigation, search (command palette), notifications,
 * theme and account. Admin links appear only for people who can use them.
 */
export function AppShell() {
  const agent = useSession((s) => s.agent);
  const canManageAgents = useCan('AGENT_MANAGE');
  const canManagePartners = useCan('PARTNER_MANAGE');
  const navigate = useNavigate();
  const [paletteOpen, setPaletteOpenState] = useState(false);
  // The palette's code (cmdk) is fetched the first time it opens, then stays mounted.
  const [paletteLoaded, setPaletteLoaded] = useState(false);
  const setPaletteOpen = useCallback((open: boolean) => {
    if (open) setPaletteLoaded(true);
    setPaletteOpenState(open);
  }, []);
  useGlobalShortcuts(paletteOpen, setPaletteOpen);
  const [navOpen, setNavOpen] = useState(false);
  const location = useLocation();
  const navigation = useNavigation();

  // On small screens the nav is a drawer; close it whenever the page changes or Esc is pressed.
  const [lastPath, setLastPath] = useState(location.pathname);
  if (lastPath !== location.pathname) {
    setLastPath(location.pathname);
    setNavOpen(false);
  }
  useEffect(() => {
    if (!navOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setNavOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
    };
  }, [navOpen]);

  return (
    // `isolate` makes this a stacking context so the backdrop (z -1) paints above our background.
    <div className="relative isolate min-h-dvh bg-paper">
      <AppBackdrop />
      <a
        href="#main"
        className="sr-only-focusable absolute top-2 left-2 z-50 rounded-md bg-brand px-3 py-2 font-semibold"
      >
        Skip to content
      </a>
      {navigation.state === 'loading' && (
        // Lazy route chunks can take a moment on slow networks; show that something is happening.
        <div
          role="progressbar"
          aria-label="Loading page"
          className="fixed inset-x-0 top-0 z-[70] h-1 origin-left animate-pulse bg-brand"
        />
      )}
      <OfflineBanner />
      <div className="mx-auto grid max-w-[1500px] gap-6 p-4 lg:grid-cols-[230px_1fr] lg:p-6">
        {navOpen && (
          <div
            aria-hidden="true"
            className="fixed inset-0 z-40 bg-black/40 lg:hidden"
            onClick={() => {
              setNavOpen(false);
            }}
          />
        )}
        <aside
          id="main-nav"
          className={cn(
            'fixed inset-y-0 left-0 z-50 w-[260px] p-3 transition-transform duration-200 ease-brutal lg:sticky lg:top-6 lg:z-auto lg:h-[calc(100dvh-3rem)] lg:w-auto lg:translate-x-0 lg:p-0',
            navOpen ? 'translate-x-0' : '-translate-x-[110%]',
          )}
        >
          <nav
            aria-label="Main"
            className="glass-panel flex h-full flex-col gap-1 overflow-y-auto p-3"
          >
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
            <NavItem to="/board" icon={<Columns3 className="size-4" />}>
              Board
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
          <header className="glass-panel relative z-30 flex items-center gap-2 px-2 py-2 sm:gap-3 sm:px-4">
            <Button
              variant="ghost"
              size="icon"
              className="lg:hidden"
              aria-label={navOpen ? 'Close menu' : 'Open menu'}
              aria-expanded={navOpen}
              aria-controls="main-nav"
              onClick={() => {
                setNavOpen((o) => !o);
              }}
            >
              {navOpen ? <X className="size-5" /> : <Menu className="size-5" />}
            </Button>
            <button
              type="button"
              onClick={() => {
                setPaletteOpen(true);
              }}
              className="flex h-10 flex-1 items-center gap-2 rounded-md border-2 border-line-soft bg-paper px-3 text-left text-sm text-muted hover:border-line"
            >
              <Search aria-hidden="true" className="size-4" />
              <span className="flex-1 truncate">
                <span className="sm:hidden">Search…</span>
                <span className="hidden sm:inline">Search tickets, jump to UNT-…, create…</span>
              </span>
              <span className="hidden sm:inline">
                <Kbd>⌘K</Kbd>
              </span>
            </button>
            <NotificationBell />
            <AppearanceMenu />
          </header>
          <main id="main" tabIndex={-1} className="min-w-0 outline-none">
            {/* Each page enters with a short fade-and-rise (§12.3); keyed so it replays per route.
                Plain CSS, so the animation library stays out of the main bundle. */}
            <div key={location.pathname} className="animate-rise">
              <Outlet />
            </div>
          </main>
        </div>
      </div>
      {paletteLoaded && (
        <Suspense fallback={null}>
          <CommandPalette open={paletteOpen} onOpenChange={setPaletteOpen} />
        </Suspense>
      )}
    </div>
  );
}
