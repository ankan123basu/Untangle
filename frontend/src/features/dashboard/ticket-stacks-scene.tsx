import { useEffect, useMemo, useRef, type ReactNode, type RefObject } from 'react';
import { Canvas, useFrame, type ThreeEvent } from '@react-three/fiber';
import type { BufferGeometry, DataTexture, Group, ShaderMaterial } from 'three';
import { RoundedBoxGeometry } from 'three/examples/jsm/geometries/RoundedBoxGeometry.js';
import { Inked } from '@/components/three/inked';
import { useOutline, usePageVisible, useToonRamp } from '@/components/three/toon';
import type { Priority } from '@/lib/api/types';
import { sameTarget, type StackData, type Target } from './ticket-stacks-model';

/*
 * The unresolved queue as stacks of real tickets (ADR-027): one stack per priority, one slab per
 * ticket (most urgent first, at the bottom), drawn like the rest of the UI (flat colour, ink
 * outline, hard shadow). If a priority has more tickets than fit, a dark cap stands for the rest.
 * Slabs get thinner as a stack grows, so every ticket stays big enough to point at.
 *
 * Interactive: the slab under the pointer slides out toward you (the wrapper reads it out),
 * clicking opens it; the base and the cap open the priority's list; dragging turns the set (the
 * wrapper owns the drag and writes the angle into `yaw`). New tickets drop in with a bounce.
 */

const COLORS: Record<Priority, string> = {
  CRITICAL: '#ff5a5f',
  HIGH: '#ffb547',
  MEDIUM: '#5cc8ff',
  LOW: '#d6d3cc',
};
const ORDER: Priority[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
const GAP = 1.4;
const MAX_HEIGHT = 2.05; // world units available above the base
const MAX_SLAB_H = 0.26;
const SPACING = 0.025;

interface Shared {
  ramp: DataTexture;
  outline: ShaderMaterial;
}

/** One slab: drops in when it first appears, eases when the stack re-flows, slides out on hover. */
function Slab({
  geometry,
  color,
  y,
  index,
  hovered,
  shared,
  onOver,
  onOut,
  onClick,
}: {
  geometry: BufferGeometry;
  color: string;
  y: number;
  index: number;
  hovered: boolean;
  shared: Shared;
  onOver: () => void;
  onOut: () => void;
  onClick: () => void;
}) {
  const ref = useRef<Group>(null);
  const born = useRef<number | null>(null);
  useFrame(({ clock }, delta) => {
    const g = ref.current;
    if (!g) return;
    const t = clock.elapsedTime;
    born.current ??= t + index * 0.06;
    const local = Math.min(1, Math.max(0, (t - born.current) / 0.55));
    const bounce = 1 - Math.abs(Math.cos(local * Math.PI * 1.5)) * (1 - local);
    const k = Math.min(1, delta * 12);
    const targetY = y + (1 - bounce) * 3;
    g.position.y = local < 1 ? targetY : g.position.y + (targetY - g.position.y) * k;
    g.position.z += ((hovered ? 0.42 : 0) - g.position.z) * k;
    const sx = g.scale.x + ((hovered ? 1.12 : 1) - g.scale.x) * k;
    g.scale.set(sx, 1, sx);
    g.rotation.y = hovered ? Math.sin(t * 9) * 0.05 : index % 2 === 0 ? 0.05 : -0.05;
  });
  return (
    <group
      ref={ref}
      onPointerOver={(e: ThreeEvent<PointerEvent>) => {
        e.stopPropagation();
        onOver();
      }}
      onPointerOut={(e: ThreeEvent<PointerEvent>) => {
        e.stopPropagation();
        onOut();
      }}
      onClick={(e: ThreeEvent<MouseEvent>) => {
        e.stopPropagation();
        onClick();
      }}
    >
      <Inked geometry={geometry} color={color} ramp={shared.ramp} outline={shared.outline} />
    </group>
  );
}

function Stack({
  data,
  x,
  target,
  onHover,
  onSelect,
}: {
  data: StackData;
  x: number;
  target: Target | null;
  onHover: (t: Target | null) => void;
  onSelect: (t: Target) => void;
}) {
  const ramp = useToonRamp();
  const outline = useOutline(0.02);
  const shared = useMemo(() => ({ ramp, outline }), [ramp, outline]);
  const rest = data.total - data.slabs.length;
  const count = data.slabs.length + (rest > 0 ? 1 : 0);
  // Thinner slabs for taller stacks, so the stack never leaves the stage.
  const slabH = Math.min(MAX_SLAB_H, MAX_HEIGHT / Math.max(1, count) - SPACING);
  const slab = useMemo(
    () => new RoundedBoxGeometry(0.95, slabH, 0.7, 3, Math.min(0.05, slabH / 3)),
    [slabH],
  );
  const base = useMemo(() => new RoundedBoxGeometry(1.1, 0.08, 0.85, 2, 0.03), []);
  useEffect(
    () => () => {
      slab.dispose();
    },
    [slab],
  );
  useEffect(
    () => () => {
      base.dispose();
    },
    [base],
  );
  const color = COLORS[data.priority];
  const yAt = (i: number) => 0.08 + slabH / 2 + i * (slabH + SPACING);
  const leave = (t: Target) => () => {
    if (sameTarget(target, t)) onHover(null);
  };
  const baseTarget: Target = { kind: 'stack', priority: data.priority };
  const more: Target = { kind: 'more', priority: data.priority, rest };

  return (
    <group position={[x, 0, 0]}>
      <group
        onPointerOver={(e: ThreeEvent<PointerEvent>) => {
          e.stopPropagation();
          onHover(baseTarget);
        }}
        onPointerOut={leave(baseTarget)}
        onClick={(e: ThreeEvent<MouseEvent>) => {
          e.stopPropagation();
          onSelect(baseTarget);
        }}
      >
        <Inked
          geometry={base}
          color={target?.priority === data.priority ? color : '#ffffff'}
          ramp={ramp}
          outline={outline}
        />
      </group>
      {data.slabs.map((s, i) => {
        const t: Target = { kind: 'ticket', priority: data.priority, slab: s };
        return (
          <Slab
            key={s.id}
            geometry={slab}
            color={color}
            y={yAt(i)}
            index={i}
            hovered={sameTarget(target, t)}
            shared={shared}
            onOver={() => {
              onHover(t);
            }}
            onOut={leave(t)}
            onClick={() => {
              onSelect(t);
            }}
          />
        );
      })}
      {rest > 0 && (
        <Slab
          key="more"
          geometry={slab}
          color="#2a2a2a"
          y={yAt(data.slabs.length)}
          index={data.slabs.length}
          hovered={sameTarget(target, more)}
          shared={shared}
          onOver={() => {
            onHover(more);
          }}
          onOut={leave(more)}
          onClick={() => {
            onSelect(more);
          }}
        />
      )}
    </group>
  );
}

/** Turns the set toward the angle the wrapper's drag writes into `yaw`. */
function Turntable({ yaw, children }: { yaw: RefObject<number>; children: ReactNode }) {
  const ref = useRef<Group>(null);
  useFrame((_, delta) => {
    const g = ref.current;
    if (g) g.rotation.y += (yaw.current - g.rotation.y) * Math.min(1, delta * 8);
  });
  return <group ref={ref}>{children}</group>;
}

export default function TicketStacksScene({
  stacks,
  shadowColor,
  target,
  onHover,
  onSelect,
  yaw,
}: {
  stacks: StackData[];
  shadowColor: string;
  target: Target | null;
  onHover: (t: Target | null) => void;
  onSelect: (t: Target) => void;
  yaw: RefObject<number>;
}) {
  const visible = usePageVisible();
  return (
    <div className="scene-pop-in absolute inset-0">
      <Canvas
        shadows="basic"
        dpr={[1, 1.75]}
        frameloop={visible ? 'always' : 'never'}
        camera={{ position: [0, 1.9, 7.4], fov: 34 }}
        onCreated={({ camera }) => {
          camera.lookAt(0, 1.05, 0);
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
        <mesh rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
          <planeGeometry args={[20, 20]} />
          <shadowMaterial color={shadowColor} opacity={1} transparent />
        </mesh>
        <Turntable yaw={yaw}>
          {ORDER.map((p, i) => (
            <Stack
              key={p}
              data={stacks.find((s) => s.priority === p) ?? { priority: p, slabs: [], total: 0 }}
              x={(i - 1.5) * GAP}
              target={target}
              onHover={onHover}
              onSelect={onSelect}
            />
          ))}
        </Turntable>
      </Canvas>
    </div>
  );
}
