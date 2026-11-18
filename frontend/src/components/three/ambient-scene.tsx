import { useEffect, useMemo, useRef, type ReactNode } from 'react';
import { Canvas, useFrame, useThree } from '@react-three/fiber';
import {
  CatmullRomCurve3,
  Color,
  ExtrudeGeometry,
  type Group,
  Shape,
  TubeGeometry,
  Vector3,
} from 'three';
import { StudioEnvironment } from './studio-environment';
import type { ThemeColors } from './theme-colors';

/*
 * The immersive background behind every signed-in screen (ADR-025). Glossy, slowly floating
 * objects in the theme colours sit behind the frosted panels, so the glass picks up real depth and
 * colour as it blurs them:
 *   - a large iridescent torus knot: the tangle,
 *   - a glowing checkmark: the tangle, solved,
 *   - frosted ticket cards, glossy orbs and a ring drifting around them.
 * Everything follows the cursor and the page scroll a little.
 *
 * Cost control: renders on demand at about 30 fps (not 60), pauses while the tab is hidden, caps
 * the pixel ratio at 1.5, and is never loaded with reduced motion, without WebGL, on small or
 * low-power devices, or when the person switched 3D off (see AmbientLayer).
 */

const FPS = 30;

/** Drives on-demand rendering at a fixed, modest frame rate; stops while the tab is hidden. */
function FrameDriver() {
  const invalidate = useThree((s) => s.invalidate);
  useEffect(() => {
    let timer: number | undefined;
    const start = () => {
      if (timer === undefined)
        timer = window.setInterval(() => {
          invalidate();
        }, 1000 / FPS);
    };
    const stop = () => {
      window.clearInterval(timer);
      timer = undefined;
    };
    const onVisibility = () => {
      if (document.hidden) stop();
      else start();
    };
    if (!document.hidden) start();
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      stop();
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [invalidate]);
  return null;
}

/** The pointer and scroll, tracked on window because the canvas ignores pointer events. */
function usePointerAndScroll() {
  const state = useRef({ x: 0, y: 0, scroll: 0 });
  useEffect(() => {
    const onMove = (e: PointerEvent) => {
      state.current.x = (e.clientX / window.innerWidth) * 2 - 1;
      state.current.y = -((e.clientY / window.innerHeight) * 2 - 1);
    };
    const onScroll = () => {
      state.current.scroll = window.scrollY;
    };
    window.addEventListener('pointermove', onMove, { passive: true });
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('scroll', onScroll);
    };
  }, []);
  return state;
}

function Rig({ children }: { children: ReactNode }) {
  const group = useRef<Group>(null);
  const input = usePointerAndScroll();
  useFrame(() => {
    const g = group.current;
    if (!g) return;
    const { x, y, scroll } = input.current;
    g.rotation.y += (x * 0.22 - g.rotation.y) * 0.04;
    g.rotation.x += (-y * 0.14 - g.rotation.x) * 0.04;
    // Scrolling drifts the scene upward slowly: parallax depth behind the panels.
    const targetY = Math.min(scroll, 2400) * 0.0012;
    g.position.y += (targetY - g.position.y) * 0.06;
  });
  return <group ref={group}>{children}</group>;
}

/** Gentle bobbing and spinning for one object. */
function Float({
  children,
  position,
  speed = 1,
  amplitude = 0.18,
  spin = 0.15,
}: {
  children: ReactNode;
  position: [number, number, number];
  speed?: number;
  amplitude?: number;
  spin?: number;
}) {
  const group = useRef<Group>(null);
  const phase = useMemo(() => position[0] * 1.7 + position[1], [position]);
  useFrame((state) => {
    const g = group.current;
    if (!g) return;
    const t = state.clock.elapsedTime * speed + phase;
    g.position.y = position[1] + Math.sin(t) * amplitude;
    g.rotation.x = Math.sin(t * 0.6) * spin;
    g.rotation.z = Math.cos(t * 0.5) * spin;
  });
  return (
    <group ref={group} position={position}>
      {children}
    </group>
  );
}

function Tangle({ colors }: { colors: ThemeColors }) {
  const mesh = useRef<Group>(null);
  useFrame((_, delta) => {
    if (mesh.current) {
      mesh.current.rotation.y += delta * 0.12;
      mesh.current.rotation.x += delta * 0.05;
    }
  });
  return (
    <group ref={mesh}>
      <mesh>
        <torusKnotGeometry args={[1.15, 0.36, 220, 36, 2, 3]} />
        <meshPhysicalMaterial
          color={colors.a1}
          metalness={0.25}
          roughness={0.18}
          clearcoat={1}
          clearcoatRoughness={0.08}
          iridescence={0.9}
          iridescenceIOR={1.45}
          sheen={0.6}
          sheenColor={colors.a3}
        />
      </mesh>
    </group>
  );
}

const CHECK_POINTS = [
  new Vector3(-0.9, 0.05, 0),
  new Vector3(-0.25, -0.6, 0),
  new Vector3(1, 0.85, 0),
];

function Check({ colors }: { colors: ThemeColors }) {
  const geometry = useMemo(
    () =>
      new TubeGeometry(new CatmullRomCurve3(CHECK_POINTS, false, 'catmullrom', 0.05), 64, 0.13, 20),
    [],
  );
  useEffect(
    () => () => {
      geometry.dispose();
    },
    [geometry],
  );
  return (
    <group>
      <mesh geometry={geometry}>
        <meshPhysicalMaterial
          color={colors.a3}
          emissive={colors.a3}
          emissiveIntensity={colors.dark ? 0.55 : 0.25}
          roughness={0.2}
          clearcoat={1}
        />
      </mesh>
      {[CHECK_POINTS[0], CHECK_POINTS[2]].map((p) =>
        p ? (
          <mesh key={p.x} position={p}>
            <sphereGeometry args={[0.13, 20, 20]} />
            <meshPhysicalMaterial
              color={colors.a3}
              emissive={colors.a3}
              emissiveIntensity={colors.dark ? 0.55 : 0.25}
              roughness={0.2}
              clearcoat={1}
            />
          </mesh>
        ) : null,
      )}
    </group>
  );
}

function roundedRect(w: number, h: number, r: number): Shape {
  const s = new Shape();
  const x = -w / 2;
  const y = -h / 2;
  s.moveTo(x + r, y);
  s.lineTo(x + w - r, y);
  s.quadraticCurveTo(x + w, y, x + w, y + r);
  s.lineTo(x + w, y + h - r);
  s.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  s.lineTo(x + r, y + h);
  s.quadraticCurveTo(x, y + h, x, y + h - r);
  s.lineTo(x, y + r);
  s.quadraticCurveTo(x, y, x + r, y);
  return s;
}

/** A frosted ticket card: rounded slab, a priority stripe and three "text" lines. */
function TicketCard({ colors, stripe }: { colors: ThemeColors; stripe: string }) {
  const geometry = useMemo(() => {
    const g = new ExtrudeGeometry(roundedRect(1.6, 1, 0.16), {
      depth: 0.06,
      bevelEnabled: true,
      bevelThickness: 0.03,
      bevelSize: 0.03,
      bevelSegments: 4,
    });
    g.center();
    return g;
  }, []);
  useEffect(
    () => () => {
      geometry.dispose();
    },
    [geometry],
  );
  const line = colors.dark ? '#ffffff' : colors.a1;
  return (
    <group>
      <mesh geometry={geometry}>
        <meshPhysicalMaterial
          color={colors.dark ? '#2a2540' : '#ffffff'}
          transparent
          opacity={colors.dark ? 0.7 : 0.82}
          roughness={0.12}
          clearcoat={1}
          metalness={0}
        />
      </mesh>
      <mesh position={[-0.66, 0, 0.07]}>
        <planeGeometry args={[0.1, 0.7]} />
        <meshBasicMaterial color={stripe} />
      </mesh>
      {[0.22, 0.02, -0.18].map((y, i) => (
        <mesh key={y} position={[-0.1 - i * 0.07, y, 0.07]}>
          <planeGeometry args={[0.9 - i * 0.22, 0.08]} />
          <meshBasicMaterial color={line} transparent opacity={0.55 - i * 0.12} />
        </mesh>
      ))}
    </group>
  );
}

function Orb({ color, radius }: { color: string; radius: number }) {
  return (
    <mesh>
      <sphereGeometry args={[radius, 48, 48]} />
      <meshPhysicalMaterial
        color={color}
        roughness={0.1}
        metalness={0.1}
        clearcoat={1}
        iridescence={0.5}
      />
    </mesh>
  );
}

/** Spread objects across the screen width, whatever its aspect ratio. */
function useSpread(): number {
  const { width, height } = useThree((s) => s.size);
  return Math.max(1, Math.min(1.8, width / height / 1.3));
}

function Objects({ colors }: { colors: ThemeColors }) {
  const k = useSpread();
  return (
    <Rig>
      <Float position={[4.3 * k, 1.2, -3]} speed={0.35} amplitude={0.25} spin={0.05}>
        <Tangle colors={colors} />
      </Float>
      <Float position={[-4.6 * k, -1.9, -2.2]} speed={0.5} amplitude={0.22}>
        <group rotation={[0, 0, -0.15]} scale={1.15}>
          <Check colors={colors} />
        </group>
      </Float>
      <Float position={[-3.6 * k, 2.4, -3.4]} speed={0.6} spin={0.25}>
        <group rotation={[0.2, 0.5, 0.15]}>
          <TicketCard colors={colors} stripe="#c81e1e" />
        </group>
      </Float>
      <Float position={[2.1 * k, -2.6, -2.4]} speed={0.7} spin={0.22}>
        <group rotation={[-0.2, -0.45, -0.12]}>
          <TicketCard colors={colors} stripe="#16a34a" />
        </group>
      </Float>
      <Float position={[0.2 * k, 2.9, -5]} speed={0.45} spin={0.3}>
        <group rotation={[0.3, 0.2, 0.25]} scale={0.8}>
          <TicketCard colors={colors} stripe="#d97706" />
        </group>
      </Float>
      <Float position={[-1.4 * k, -0.4, -6]} speed={0.4}>
        <Orb color={colors.a2} radius={0.75} />
      </Float>
      <Float position={[5.4 * k, -2.3, -4]} speed={0.55}>
        <Orb color={colors.a3} radius={0.5} />
      </Float>
      <Float position={[-5.6 * k, 0.9, -5]} speed={0.65}>
        <Orb color={colors.a1} radius={0.38} />
      </Float>
      <Float position={[1.8 * k, 0.6, -7]} speed={0.3} spin={0.4}>
        <mesh rotation={[1.1, 0.3, 0]}>
          <torusGeometry args={[1.1, 0.07, 24, 120]} />
          <meshPhysicalMaterial color={colors.a3} roughness={0.15} metalness={0.4} clearcoat={1} />
        </mesh>
      </Float>
    </Rig>
  );
}

export default function AmbientScene({ colors }: { colors: ThemeColors }) {
  const keyLight = useMemo(() => new Color(colors.a3), [colors.a3]);
  return (
    <Canvas
      aria-hidden="true"
      dpr={[1, 1.5]}
      frameloop="demand"
      camera={{ position: [0, 0, 8], fov: 45 }}
      gl={{ antialias: true, alpha: true, powerPreference: 'low-power' }}
      className="ambient-canvas"
      style={{ position: 'fixed', inset: 0, zIndex: -1, pointerEvents: 'none' }}
      resize={{ debounce: 150 }}
    >
      <FrameDriver />
      <StudioEnvironment intensity={colors.dark ? 0.55 : 1} />
      <ambientLight intensity={colors.dark ? 0.25 : 0.5} />
      <directionalLight position={[4, 6, 6]} intensity={colors.dark ? 1.2 : 1.6} />
      <pointLight position={[-6, -3, 2]} intensity={30} color={keyLight} />
      <Objects colors={colors} />
    </Canvas>
  );
}
