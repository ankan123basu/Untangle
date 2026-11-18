import { useEffect, useMemo, useRef, useState } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import { AdditiveBlending, type Group, type Mesh, type MeshPhysicalMaterial } from 'three';
import { RoundedBoxGeometry } from 'three/examples/jsm/geometries/RoundedBoxGeometry.js';
import { StudioEnvironment } from '@/components/three/studio-environment';
import type { ThemeColors } from '@/components/three/theme-colors';
import type { Priority } from '@/lib/api/types';

/*
 * The live queue as a small solar system (ADR-026): a glass core, and every unresolved ticket a
 * little glowing card on its priority's orbit. Critical sits closest and moves fastest, low drifts
 * on the outside. Counts come from the dashboard summary, so the picture is the real queue.
 */

const ORBITS: { priority: Priority; color: string; radius: number; speed: number }[] = [
  { priority: 'CRITICAL', color: '#f43f5e', radius: 0.72, speed: 0.9 },
  { priority: 'HIGH', color: '#f59e0b', radius: 1.02, speed: 0.6 },
  { priority: 'MEDIUM', color: '#60a5fa', radius: 1.32, speed: 0.4 },
  { priority: 'LOW', color: '#a3b1c6', radius: 1.62, speed: 0.25 },
];
/** More than this per ring would be a blur; the number next to the stage stays exact. */
const MAX_PER_RING = 14;

function Ring({
  radius,
  color,
  speed,
  count,
  tilt,
}: {
  radius: number;
  color: string;
  speed: number;
  count: number;
  tilt: number;
}) {
  const spin = useRef<Group>(null);
  const card = useMemo(() => new RoundedBoxGeometry(0.27, 0.18, 0.04, 3, 0.04), []);
  useEffect(
    () => () => {
      card.dispose();
    },
    [card],
  );
  useFrame((_, delta) => {
    if (spin.current) spin.current.rotation.z += delta * speed * 0.6;
  });
  const n = Math.min(count, MAX_PER_RING);
  return (
    <group rotation={[Math.PI / 2 - 0.42, tilt, 0]}>
      <mesh>
        <torusGeometry args={[radius, 0.006, 8, 128]} />
        <meshBasicMaterial color={color} transparent opacity={0.45} toneMapped={false} />
      </mesh>
      <group ref={spin}>
        {Array.from({ length: n }, (_, i) => {
          const a = (i / Math.max(1, n)) * Math.PI * 2;
          return (
            <group key={i} position={[Math.cos(a) * radius, Math.sin(a) * radius, 0]}>
              <mesh geometry={card} rotation={[Math.PI / 2, 0, a]}>
                <meshStandardMaterial
                  color={color}
                  emissive={color}
                  emissiveIntensity={1.4}
                  roughness={0.25}
                  metalness={0.1}
                  toneMapped={false}
                />
              </mesh>
              <mesh>
                <sphereGeometry args={[0.13, 12, 10]} />
                <meshBasicMaterial
                  color={color}
                  transparent
                  opacity={0.12}
                  blending={AdditiveBlending}
                  depthWrite={false}
                  toneMapped={false}
                />
              </mesh>
            </group>
          );
        })}
      </group>
    </group>
  );
}

function Core({ colors, calm }: { colors: ThemeColors; calm: boolean }) {
  const ref = useRef<Mesh>(null);
  useFrame(({ clock }) => {
    const m = ref.current;
    if (!m) return;
    const t = clock.elapsedTime;
    m.rotation.y = t * 0.3;
    m.scale.setScalar(1 + Math.sin(t * 1.6) * 0.03);
    (m.material as MeshPhysicalMaterial).emissiveIntensity = calm
      ? 0.35
      : 0.5 + Math.sin(t * 3) * 0.15;
  });
  return (
    <mesh ref={ref}>
      <icosahedronGeometry args={[0.36, 6]} />
      <meshPhysicalMaterial
        color={colors.a1}
        emissive={colors.a2}
        emissiveIntensity={0.5}
        roughness={0.15}
        metalness={0.2}
        clearcoat={1}
        iridescence={0.8}
        iridescenceIOR={1.5}
      />
    </mesh>
  );
}

function usePageVisible() {
  const [visible, setVisible] = useState(() => !document.hidden);
  useEffect(() => {
    const onChange = () => {
      setVisible(!document.hidden);
    };
    document.addEventListener('visibilitychange', onChange);
    return () => {
      document.removeEventListener('visibilitychange', onChange);
    };
  }, []);
  return visible;
}

export default function QueueOrbitScene({
  colors,
  counts,
}: {
  colors: ThemeColors;
  counts: Partial<Record<Priority, number>>;
}) {
  const visible = usePageVisible();
  const total = ORBITS.reduce((sum, o) => sum + (counts[o.priority] ?? 0), 0);
  return (
    <div className="scene-fade-in absolute inset-0">
      <Canvas
        dpr={[1, 1.75]}
        frameloop={visible ? 'always' : 'never'}
        camera={{ position: [0, 0.3, 5.4], fov: 40 }}
        gl={{ alpha: true, antialias: true, powerPreference: 'high-performance' }}
      >
        <StudioEnvironment intensity={0.8} />
        <ambientLight intensity={0.4} />
        <pointLight position={[-2, 2, 3]} intensity={18} color={colors.a3} />
        <pointLight position={[2.5, -1, 2]} intensity={14} color={colors.a4} />
        <Core colors={colors} calm={total === 0} />
        {ORBITS.map((o, i) => (
          <Ring
            key={o.priority}
            radius={o.radius}
            color={o.color}
            speed={o.speed}
            count={counts[o.priority] ?? 0}
            tilt={(i - 1.5) * 0.08}
          />
        ))}
      </Canvas>
    </div>
  );
}
