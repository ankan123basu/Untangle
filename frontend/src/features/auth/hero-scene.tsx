import { useEffect, useMemo, useRef } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import { StudioEnvironment } from '@/components/three/studio-environment';
import type { ThemeColors } from '@/components/three/theme-colors';
import {
  CatmullRomCurve3,
  Color,
  EdgesGeometry,
  ExtrudeGeometry,
  type Group,
  type Mesh,
  type MeshBasicMaterial,
  Shape,
  TubeGeometry,
  Vector3,
} from 'three';

/**
 * The login hero (§12.3, ADR-024). One short doneRef, then calm:
 *   1. a tangled line (a complaint) hangs in the air for a beat,
 *   2. it is pulled straight into the Untangle checkmark, right end first,
 *   3. a single soft ring pulses out from the check,
 *   4. the floating ticket cards are resolved one by one: their priority stripe turns green and a
 *      small check pops on,
 *   5. everything keeps floating gently and follows the cursor.
 * Soft shadows fall on the hero panel for depth; colours follow the current colour theme. Reduced motion never reaches this file (the
 * static hero is used), but `reduced` still renders the finished state for safety.
 */

const SAMPLES = 160;
const RADIUS = 0.085;
const START = new Vector3(-2.3, 0.25, 0);
const END = new Vector3(2.3, 1.35, 0);
const CHECK_VERTEX = new Vector3(-0.45, -1.05, 0.15);
const INK = '#111111';
const RESOLVED = new Color('#16733a');

/** Colours for the scene, from the current colour theme (the hero panel is always deep). */
interface Palette {
  strand: string;
  glow: string;
  card: string;
  edge: string;
  text: string;
  ring: string;
  shadow: number;
  ground: string;
}
function paletteFor(colors: ThemeColors): Palette {
  return {
    strand: colors.a3,
    glow: colors.a3,
    card: '#ffffff',
    edge: '#ffffff',
    text: '#ffffff',
    ring: colors.a3,
    shadow: 0.4,
    ground: colors.a1,
  };
}

/** Shared timeline: when the checkmark finished (seconds on the R3F clock). */
type Story = { current: number | null };

function knotPoint(s: number): Vector3 {
  const base = START.clone().lerp(END, s);
  const envelope = Math.sin(Math.PI * s) ** 0.8; // the ends stay pinned to START / END
  const t = s * Math.PI * 2 * 1.5;
  const trefoil = new Vector3(
    Math.sin(t) + 2 * Math.sin(2 * t),
    Math.cos(t) - 2 * Math.cos(2 * t),
    -Math.sin(3 * t),
  ).multiplyScalar(0.42 * envelope);
  return base.add(trefoil);
}

function checkPoint(s: number): Vector3 {
  const first = START.distanceTo(CHECK_VERTEX);
  const second = CHECK_VERTEX.distanceTo(END);
  const along = s * (first + second);
  return along <= first
    ? START.clone().lerp(CHECK_VERTEX, along / first)
    : CHECK_VERTEX.clone().lerp(END, (along - first) / second);
}

const easeInOut = (x: number) => (x < 0.5 ? 4 * x * x * x : 1 - (-2 * x + 2) ** 3 / 2);
const easeOutBack = (x: number) => 1 + 2.2 * (x - 1) ** 3 + 1.2 * (x - 1) ** 2;
const clamp01 = (x: number) => Math.min(1, Math.max(0, x));

function Strand({
  doneRef,
  reduced,
  palette,
}: {
  doneRef: Story;
  reduced: boolean;
  palette: Palette;
}) {
  const mesh = useRef<Mesh>(null);
  const knot = useMemo(
    () => Array.from({ length: SAMPLES }, (_, i) => knotPoint(i / (SAMPLES - 1))),
    [],
  );
  const check = useMemo(
    () => Array.from({ length: SAMPLES }, (_, i) => checkPoint(i / (SAMPLES - 1))),
    [],
  );
  const started = useRef<number | null>(null);
  // Stable constructor args: the login form re-renders this tree, and new args would make R3F
  // rebuild the geometry, snapping a finished checkmark back into a knot.
  const initialArgs = useMemo(
    () => [new CatmullRomCurve3(knot), SAMPLES * 2, RADIUS, 16, false] as const,
    [knot],
  );

  const build = (progress: number) => {
    const points = knot.map((k, i) => {
      const s = i / (SAMPLES - 1);
      const local = easeInOut(clamp01(progress * 1.35 - (1 - s) * 0.35));
      return k.clone().lerp(check[i] ?? k, local);
    });
    const target = mesh.current;
    if (!target) return;
    target.geometry.dispose();
    target.geometry = new TubeGeometry(
      new CatmullRomCurve3(points),
      SAMPLES * 2,
      RADIUS,
      16,
      false,
    );
  };

  useEffect(() => {
    if (reduced) {
      build(1);
      doneRef.current = 0;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- one static frame
  }, [reduced]);

  useFrame((state) => {
    if (reduced || doneRef.current !== null) return;
    started.current ??= state.clock.elapsedTime;
    const progress = clamp01((state.clock.elapsedTime - started.current - 0.9) / 2.2);
    build(progress);
    if (progress >= 1) doneRef.current = state.clock.elapsedTime;
  });

  return (
    <group>
      <mesh ref={mesh} castShadow>
        <tubeGeometry args={initialArgs} />
        <meshPhysicalMaterial
          color={palette.strand}
          emissive={palette.glow}
          emissiveIntensity={0.45}
          roughness={0.15}
          metalness={0.1}
          clearcoat={1}
          iridescence={0.6}
        />
      </mesh>
      {/* Rounded ends: both stay fixed while the middle untangles. */}
      {[START, END].map((p) => (
        <mesh key={p.x} position={p} castShadow>
          <sphereGeometry args={[RADIUS, 16, 16]} />
          <meshPhysicalMaterial
            color={palette.strand}
            emissive={palette.glow}
            emissiveIntensity={0.45}
            roughness={0.15}
            metalness={0.1}
            clearcoat={1}
          />
        </mesh>
      ))}
    </group>
  );
}

/** One soft ring that pulses out from the check when it lands. */
function Pulse({ doneRef, color }: { doneRef: Story; color: string }) {
  const mesh = useRef<Mesh>(null);
  useFrame((state) => {
    const ring = mesh.current;
    if (!ring) return;
    const t = doneRef.current === null ? -1 : state.clock.elapsedTime - doneRef.current;
    const visible = t >= 0 && t < 1.1;
    ring.visible = visible;
    if (!visible) return;
    const k = t / 1.1;
    ring.scale.setScalar(0.4 + easeInOut(k) * 2.6);
    (ring.material as MeshBasicMaterial).opacity = 0.55 * (1 - k);
  });
  return (
    <mesh ref={mesh} position={[CHECK_VERTEX.x + 0.6, CHECK_VERTEX.y + 0.7, -0.2]} visible={false}>
      <ringGeometry args={[0.92, 1, 64]} />
      <meshBasicMaterial color={color} transparent opacity={0} depthWrite={false} />
    </mesh>
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

const cardGeometry = new ExtrudeGeometry(roundedRect(1.5, 0.92, 0.1), {
  depth: 0.05,
  bevelEnabled: false,
});
cardGeometry.translate(0, 0, -0.025);
const cardEdges = new EdgesGeometry(cardGeometry, 30);

// Cards sit around the strand, pushed back in depth so they never compete with it.
const CARDS = [
  { position: [-3.6, -1.3, -1.6], rotation: 0.16, stripe: '#c81e1e', speed: 0.7 },
  { position: [-3.4, 1.6, -2.8], rotation: -0.1, stripe: '#d97706', speed: 0.8 },
  { position: [3.3, 1.7, -2.6], rotation: 0.12, stripe: '#2563eb', speed: 0.6 },
  { position: [3.7, -1.5, -2.0], rotation: -0.2, stripe: '#64748b', speed: 0.9 },
] as const;

/** A frosted ticket card. After the check lands it is "resolved": green stripe + a check badge. */
function TicketCard({
  index,
  doneRef,
  reduced,
  palette,
}: {
  index: number;
  doneRef: Story;
  reduced: boolean;
  palette: Palette;
}) {
  const card = CARDS[index];
  const group = useRef<Group>(null);
  const stripe = useRef<MeshBasicMaterial>(null);
  const badge = useRef<Group>(null);
  const original = useMemo(() => new Color(card?.stripe ?? INK), [card]);

  useFrame((state) => {
    if (!card || !group.current) return;
    const t = state.clock.elapsedTime;
    if (!reduced) {
      const phase = t * card.speed + index;
      group.current.position.y = card.position[1] + Math.sin(phase) * 0.12;
      group.current.rotation.z = card.rotation + Math.sin(phase * 0.7) * 0.04;
    }
    // Resolve one card every 0.35 s after the pulse.
    const k =
      doneRef.current === null ? 0 : clamp01((t - doneRef.current - 0.5 - index * 0.35) / 0.45);
    const resolved = reduced && doneRef.current !== null ? 1 : k;
    stripe.current?.color.copy(original).lerp(RESOLVED, resolved);
    badge.current?.scale.setScalar(resolved === 0 ? 0.0001 : easeOutBack(resolved));
  });

  if (!card) return null;
  return (
    <group ref={group} position={[...card.position]} rotation={[0, 0, card.rotation]}>
      <mesh geometry={cardGeometry} castShadow>
        <meshPhysicalMaterial
          color={palette.card}
          transparent
          opacity={0.3}
          roughness={0.1}
          clearcoat={1}
        />
      </mesh>
      <lineSegments geometry={cardEdges}>
        <lineBasicMaterial color={palette.edge} transparent opacity={0.55} />
      </lineSegments>
      <mesh position={[-0.62, 0, 0.03]}>
        <planeGeometry args={[0.12, 0.68]} />
        <meshBasicMaterial ref={stripe} color={card.stripe} />
      </mesh>
      {[0.22, 0.02, -0.18].map((y, i) => (
        <mesh key={y} position={[-0.1 - i * 0.06, y, 0.03]}>
          <planeGeometry args={[0.8 - i * 0.2, 0.08]} />
          <meshBasicMaterial color={palette.text} transparent opacity={0.75 - i * 0.2} />
        </mesh>
      ))}
      {/* Resolved badge: a green disc with a white check, popping in. */}
      <group ref={badge} position={[0.52, -0.2, 0.06]} scale={0.0001}>
        <mesh>
          <circleGeometry args={[0.17, 32]} />
          <meshBasicMaterial color={RESOLVED} />
        </mesh>
        <mesh position={[-0.045, -0.02, 0.01]} rotation={[0, 0, Math.PI / 4]}>
          <planeGeometry args={[0.035, 0.11]} />
          <meshBasicMaterial color="#ffffff" />
        </mesh>
        <mesh position={[0.035, 0.02, 0.01]} rotation={[0, 0, -Math.PI / 5]}>
          <planeGeometry args={[0.035, 0.2]} />
          <meshBasicMaterial color="#ffffff" />
        </mesh>
      </group>
    </group>
  );
}

/** Everything follows the cursor a little. */
function Parallax({ children, reduced }: { children: React.ReactNode; reduced: boolean }) {
  const group = useRef<Group>(null);
  useFrame((state) => {
    if (reduced || !group.current) return;
    const g = group.current;
    g.rotation.y += (state.pointer.x * 0.28 - g.rotation.y) * 0.05;
    g.rotation.x += (-state.pointer.y * 0.18 - g.rotation.x) * 0.05;
  });
  return (
    <group ref={group} scale={0.78}>
      {children}
    </group>
  );
}

export default function HeroScene({
  reduced = false,
  colors,
}: {
  reduced?: boolean;
  colors: ThemeColors;
}) {
  const palette = useMemo(() => paletteFor(colors), [colors]);
  const doneRef = useRef<number | null>(null);
  return (
    <Canvas
      aria-hidden="true"
      dpr={[1, 1.75]}
      // Variance shadow maps honour shadow-radius: soft, blurred shadows on the panel.
      shadows="variance"
      camera={{ position: [0, 0, 7.2], fov: 42 }}
      gl={{ antialias: true, alpha: true, powerPreference: 'low-power' }}
      frameloop={reduced ? 'demand' : 'always'}
      style={{ position: 'absolute', inset: 0 }}
      resize={{ debounce: 100 }}
    >
      <StudioEnvironment intensity={0.8} />
      <hemisphereLight args={['#ffffff', palette.ground, 0.9]} />
      <directionalLight
        position={[2.5, 4, 6]}
        intensity={1.8}
        castShadow
        shadow-mapSize={[1024, 1024]}
        shadow-radius={14}
        shadow-blurSamples={16}
        shadow-camera-left={-6}
        shadow-camera-right={6}
        shadow-camera-top={4}
        shadow-camera-bottom={-4}
      />
      {/* Rim light from behind-left for a crisp edge on the strand. */}
      <directionalLight position={[-5, 2, -3]} intensity={0.8} />
      <Parallax reduced={reduced}>
        {CARDS.map((_, i) => (
          <TicketCard key={i} index={i} doneRef={doneRef} reduced={reduced} palette={palette} />
        ))}
        <Strand doneRef={doneRef} reduced={reduced} palette={palette} />
        <Pulse doneRef={doneRef} color={palette.ring} />
      </Parallax>
      {/* Invisible wall that only shows the soft shadows on the hero panel. */}
      <mesh position={[0, 0, -3.1]} receiveShadow>
        <planeGeometry args={[40, 24]} />
        <shadowMaterial opacity={palette.shadow} />
      </mesh>
    </Canvas>
  );
}
