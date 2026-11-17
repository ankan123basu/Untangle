import { useEffect, useMemo, useRef } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import {
  BoxGeometry,
  CatmullRomCurve3,
  EdgesGeometry,
  type Group,
  type Mesh,
  TubeGeometry,
  Vector3,
} from 'three';

/**
 * The login hero (§12.3): the Untangle knot straightening into a checkmark, with floating glass
 * "ticket" cards and a gentle cursor parallax. Loaded as its own chunk (three.js never reaches the
 * main bundle); login-hero.tsx decides whether to show it at all.
 */

const SAMPLES = 160;
const START = new Vector3(-2.3, 0.25, 0);
const END = new Vector3(2.3, 1.35, 0);
const CHECK_VERTEX = new Vector3(-0.45, -1.05, 0.15);

/** An open, knotted curve: a straight line from START to END with a trefoil wound around it. */
function knotPoint(s: number): Vector3 {
  const base = START.clone().lerp(END, s);
  const envelope = Math.sin(Math.PI * s) ** 0.8; // ends stay pinned to START / END
  const t = s * Math.PI * 2 * 1.5;
  const trefoil = new Vector3(
    Math.sin(t) + 2 * Math.sin(2 * t),
    Math.cos(t) - 2 * Math.cos(2 * t),
    -Math.sin(3 * t),
  ).multiplyScalar(0.42 * envelope);
  return base.add(trefoil);
}

/** The checkmark: START → vertex → END, sampled evenly by length. */
function checkPoint(s: number): Vector3 {
  const first = START.distanceTo(CHECK_VERTEX);
  const second = CHECK_VERTEX.distanceTo(END);
  const along = s * (first + second);
  return along <= first
    ? START.clone().lerp(CHECK_VERTEX, along / first)
    : CHECK_VERTEX.clone().lerp(END, (along - first) / second);
}

const easeInOut = (x: number) => (x < 0.5 ? 4 * x * x * x : 1 - (-2 * x + 2) ** 3 / 2);
const clamp01 = (x: number) => Math.min(1, Math.max(0, x));

function Strand({ reduced }: { reduced: boolean }) {
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
  const finished = useRef(false);

  const build = (progress: number) => {
    // The right end straightens first, as if someone pulled the string.
    const points = knot.map((k, i) => {
      const s = i / (SAMPLES - 1);
      const local = easeInOut(clamp01((progress * 1.35 - (1 - s) * 0.35) / 1));
      return k.clone().lerp(check[i] ?? k, local);
    });
    const geometry = new TubeGeometry(new CatmullRomCurve3(points), SAMPLES * 2, 0.085, 14, false);
    const target = mesh.current;
    if (target) {
      target.geometry.dispose();
      target.geometry = geometry;
    }
  };

  useEffect(() => {
    if (reduced) build(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- build once for the static frame
  }, [reduced]);

  useFrame((state) => {
    if (reduced || finished.current) return;
    started.current ??= state.clock.elapsedTime;
    const elapsed = state.clock.elapsedTime - started.current;
    const progress = clamp01((elapsed - 0.9) / 2.2); // hold the knot briefly, then untangle
    build(progress);
    if (progress >= 1) finished.current = true;
  });

  return (
    <mesh ref={mesh} castShadow>
      <tubeGeometry args={[new CatmullRomCurve3(knot), SAMPLES * 2, 0.085, 14, false]} />
      <meshStandardMaterial color="#111111" roughness={0.35} metalness={0.1} />
    </mesh>
  );
}

// Cards sit around the strand, pushed back in depth so they never compete with it.
const CARDS = [
  { position: [-3.6, -1.3, -1.6], rotation: 0.16, stripe: '#c81e1e', speed: 0.7 },
  { position: [3.7, -1.5, -2.0], rotation: -0.2, stripe: '#d97706', speed: 0.9 },
  { position: [3.3, 1.7, -2.6], rotation: 0.12, stripe: '#2563eb', speed: 0.6 },
  { position: [-3.4, 1.6, -2.8], rotation: -0.1, stripe: '#16733a', speed: 0.8 },
] as const;

const cardGeometry = new BoxGeometry(1.5, 0.92, 0.05);
const cardEdges = new EdgesGeometry(cardGeometry);

/** A frosted "ticket" with a hard black outline and a priority stripe. */
function TicketCard({ index, reduced }: { index: number; reduced: boolean }) {
  const card = CARDS[index];
  const group = useRef<Group>(null);
  useFrame((state) => {
    if (reduced || !group.current || !card) return;
    const t = state.clock.elapsedTime * card.speed + index;
    group.current.position.y = card.position[1] + Math.sin(t) * 0.12;
    group.current.rotation.z = card.rotation + Math.sin(t * 0.7) * 0.04;
  });
  if (!card) return null;
  return (
    <group ref={group} position={[...card.position]} rotation={[0, 0, card.rotation]}>
      <mesh geometry={cardGeometry}>
        <meshStandardMaterial color="#ffffff" transparent opacity={0.72} roughness={0.15} />
      </mesh>
      <lineSegments geometry={cardEdges}>
        <lineBasicMaterial color="#111111" />
      </lineSegments>
      <mesh position={[-0.62, 0, 0.03]}>
        <planeGeometry args={[0.12, 0.7]} />
        <meshBasicMaterial color={card.stripe} />
      </mesh>
      {[0.22, 0.02, -0.18].map((y, i) => (
        <mesh key={y} position={[0.05 - i * 0.08, y, 0.03]}>
          <planeGeometry args={[0.95 - i * 0.25, 0.08]} />
          <meshBasicMaterial color="#111111" transparent opacity={0.75 - i * 0.2} />
        </mesh>
      ))}
    </group>
  );
}

/** Follows the cursor a little; everything lives inside this group. */
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

export default function HeroScene({ reduced = false }: { reduced?: boolean }) {
  return (
    <Canvas
      aria-hidden="true"
      dpr={[1, 1.75]}
      camera={{ position: [0, 0, 7.2], fov: 42 }}
      gl={{ antialias: true, alpha: true, powerPreference: 'low-power' }}
      frameloop={reduced ? 'demand' : 'always'}
      style={{ position: 'absolute', inset: 0 }}
      resize={{ debounce: 100 }}
    >
      <ambientLight intensity={1.4} />
      <directionalLight position={[3, 4, 6]} intensity={1.6} />
      <Parallax reduced={reduced}>
        {CARDS.map((_, i) => (
          <TicketCard key={i} index={i} reduced={reduced} />
        ))}
        <Strand reduced={reduced} />
      </Parallax>
    </Canvas>
  );
}
