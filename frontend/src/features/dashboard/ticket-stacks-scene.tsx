import { useEffect, useMemo, useRef } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import type { Group } from 'three';
import { RoundedBoxGeometry } from 'three/examples/jsm/geometries/RoundedBoxGeometry.js';
import { Inked } from '@/components/three/inked';
import { useOutline, usePageVisible, useToonRamp } from '@/components/three/toon';
import type { Priority } from '@/lib/api/types';

/*
 * The unresolved queue as physical stacks of tickets (ADR-027): one column per priority, one slab
 * per open ticket, drawn like the rest of the UI (flat colour, ink outline, hard shadow). New
 * tickets drop in with a bounce; the top of a non-empty critical stack keeps wobbling for
 * attention. Counts are the dashboard summary's, capped so a big queue stays readable (the exact
 * numbers are printed beside the stage).
 */

const STACKS: { priority: Priority; color: string }[] = [
  { priority: 'CRITICAL', color: '#ff5a5f' },
  { priority: 'HIGH', color: '#ffb547' },
  { priority: 'MEDIUM', color: '#5cc8ff' },
  { priority: 'LOW', color: '#d6d3cc' },
];
const MAX_SLABS = 7;
const SLAB_H = 0.26;
const GAP = 1.4;

function Stack({
  x,
  color,
  count,
  wobble,
}: {
  x: number;
  color: string;
  count: number;
  wobble: boolean;
}) {
  const ramp = useToonRamp();
  const outline = useOutline(0.022);
  const slab = useMemo(() => new RoundedBoxGeometry(0.95, SLAB_H, 0.7, 3, 0.05), []);
  const base = useMemo(() => new RoundedBoxGeometry(1.1, 0.08, 0.85, 2, 0.03), []);
  useEffect(
    () => () => {
      slab.dispose();
      base.dispose();
    },
    [slab, base],
  );
  const group = useRef<Group>(null);
  const born = useRef<number | null>(null);
  const n = Math.min(count, MAX_SLABS);

  useFrame(({ clock }) => {
    const t = clock.elapsedTime;
    born.current ??= t;
    const since = t - born.current;
    group.current?.children.forEach((child, i) => {
      // Each slab drops from above, staggered bottom-up, and lands with a bounce.
      const local = Math.min(1, Math.max(0, (since - i * 0.09) / 0.55));
      const bounce = 1 - Math.abs(Math.cos(local * Math.PI * 1.5)) * (1 - local);
      const restY = 0.08 + SLAB_H / 2 + i * (SLAB_H + 0.02);
      child.position.y = restY + (1 - bounce) * 3;
      child.rotation.y =
        (i % 2 === 0 ? 0.05 : -0.05) + (wobble && i === n - 1 ? Math.sin(t * 5) * 0.08 : 0);
    });
  });

  return (
    <group position={[x, 0, 0]}>
      <Inked geometry={base} color="#ffffff" ramp={ramp} outline={outline} />
      <group ref={group}>
        {Array.from({ length: n }, (_, i) => (
          <Inked key={i} geometry={slab} color={color} ramp={ramp} outline={outline} />
        ))}
      </group>
    </group>
  );
}

export default function TicketStacksScene({
  counts,
  shadowColor,
}: {
  counts: Partial<Record<Priority, number>>;
  shadowColor: string;
}) {
  const visible = usePageVisible();
  return (
    <div className="scene-pop-in absolute inset-0">
      <Canvas
        shadows="basic"
        dpr={[1, 1.75]}
        frameloop={visible ? 'always' : 'never'}
        camera={{ position: [0, 2.1, 5.3], fov: 34 }}
        onCreated={({ camera }) => {
          camera.lookAt(0, 0.55, 0);
        }}
        gl={{ alpha: true, antialias: true }}
      >
        <ambientLight intensity={1.15} />
        <directionalLight
          position={[-5, 4.5, 3]}
          intensity={2}
          castShadow
          shadow-mapSize-width={1024}
          shadow-mapSize-height={1024}
          shadow-camera-left={-4}
          shadow-camera-right={4}
          shadow-camera-top={4}
          shadow-camera-bottom={-4}
        />
        <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, 0, 0]} receiveShadow>
          <planeGeometry args={[20, 20]} />
          <shadowMaterial color={shadowColor} opacity={1} transparent />
        </mesh>
        {STACKS.map((s, i) => (
          <Stack
            key={s.priority}
            x={(i - 1.5) * GAP}
            color={s.color}
            count={counts[s.priority] ?? 0}
            wobble={s.priority === 'CRITICAL' && (counts.CRITICAL ?? 0) > 0}
          />
        ))}
      </Canvas>
    </div>
  );
}
