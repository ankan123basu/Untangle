import { useEffect, useMemo, useRef, type RefObject } from 'react';
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
 *
 * Interactive: hovering a stack lifts it and jiggles its top ticket (the wrapper shows its label),
 * clicking opens that priority's unresolved tickets, and dragging turns the whole set (the wrapper
 * owns the drag and writes the angle into `yaw`; the scene eases toward it).
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
  hovered,
  onHover,
  onSelect,
}: {
  x: number;
  color: string;
  count: number;
  wobble: boolean;
  hovered: boolean;
  onHover: (on: boolean) => void;
  onSelect: () => void;
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
  const column = useRef<Group>(null);
  const born = useRef<number | null>(null);
  const n = Math.min(count, MAX_SLABS);
  const height = 0.08 + Math.max(1, n) * (SLAB_H + 0.02) + 0.2;

  useFrame(({ clock }, delta) => {
    const t = clock.elapsedTime;
    born.current ??= t;
    const since = t - born.current;
    // Hover: the whole column rises and grows a little, easing in and out.
    const c = column.current;
    if (c) {
      const k = Math.min(1, delta * 10);
      c.position.y += ((hovered ? 0.2 : 0) - c.position.y) * k;
      const s = c.scale.x + ((hovered ? 1.07 : 1) - c.scale.x) * k;
      c.scale.setScalar(s);
    }
    group.current?.children.forEach((child, i) => {
      // Each slab drops from above, staggered bottom-up, and lands with a bounce.
      const local = Math.min(1, Math.max(0, (since - i * 0.09) / 0.55));
      const bounce = 1 - Math.abs(Math.cos(local * Math.PI * 1.5)) * (1 - local);
      const restY = 0.08 + SLAB_H / 2 + i * (SLAB_H + 0.02);
      child.position.y = restY + (1 - bounce) * 3;
      child.rotation.y =
        (i % 2 === 0 ? 0.05 : -0.05) +
        ((wobble || hovered) && i === n - 1 ? Math.sin(t * (hovered ? 9 : 5)) * 0.1 : 0);
    });
  });

  return (
    <group position={[x, 0, 0]}>
      <group ref={column}>
        <Inked geometry={base} color={hovered ? color : '#ffffff'} ramp={ramp} outline={outline} />
        <group ref={group}>
          {Array.from({ length: n }, (_, i) => (
            <Inked key={i} geometry={slab} color={color} ramp={ramp} outline={outline} />
          ))}
        </group>
      </group>
      {/* Invisible hit box over the whole column, so even an empty stack is easy to point at. */}
      <mesh
        position={[0, height / 2, 0]}
        onPointerOver={(e) => {
          e.stopPropagation();
          onHover(true);
        }}
        onPointerOut={() => {
          onHover(false);
        }}
        onClick={(e) => {
          e.stopPropagation();
          onSelect();
        }}
      >
        <boxGeometry args={[1.25, height, 1]} />
        <meshBasicMaterial transparent opacity={0} depthWrite={false} />
      </mesh>
    </group>
  );
}

/** Turns the set toward the angle the wrapper's drag writes into `yaw`. */
function Turntable({ yaw, children }: { yaw: RefObject<number>; children: React.ReactNode }) {
  const ref = useRef<Group>(null);
  useFrame((_, delta) => {
    const g = ref.current;
    if (g) g.rotation.y += (yaw.current - g.rotation.y) * Math.min(1, delta * 8);
  });
  return <group ref={ref}>{children}</group>;
}

export default function TicketStacksScene({
  counts,
  shadowColor,
  hovered,
  onHover,
  onSelect,
  yaw,
}: {
  counts: Partial<Record<Priority, number>>;
  shadowColor: string;
  hovered: Priority | null;
  onHover: (priority: Priority | null) => void;
  onSelect: (priority: Priority) => void;
  yaw: RefObject<number>;
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
        <Turntable yaw={yaw}>
          {STACKS.map((s, i) => (
            <Stack
              key={s.priority}
              x={(i - 1.5) * GAP}
              color={s.color}
              count={counts[s.priority] ?? 0}
              wobble={s.priority === 'CRITICAL' && (counts.CRITICAL ?? 0) > 0}
              hovered={hovered === s.priority}
              onHover={(on) => {
                if (on) onHover(s.priority);
                else if (hovered === s.priority) onHover(null);
              }}
              onSelect={() => {
                onSelect(s.priority);
              }}
            />
          ))}
        </Turntable>
      </Canvas>
    </div>
  );
}
