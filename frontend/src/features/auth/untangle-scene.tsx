import { useEffect, useMemo, useRef, useState } from 'react';
import { Canvas, useFrame, useThree } from '@react-three/fiber';
import {
  AdditiveBlending,
  BufferGeometry,
  CanvasTexture,
  Color,
  CurvePath,
  EdgesGeometry,
  ExtrudeGeometry,
  Float32BufferAttribute,
  type Group,
  type InstancedMesh,
  LineCurve3,
  type Mesh,
  type MeshBasicMaterial,
  Object3D,
  Shape,
  ShapeGeometry,
  SRGBColorSpace,
  TubeGeometry,
  Vector3,
} from 'three';
import { StudioEnvironment } from '@/components/three/studio-environment';
import type { ThemeColors } from '@/components/three/theme-colors';

/*
 * The login story (ADR-026), in a loop of PERIOD seconds:
 *   1. four glowing threads, one per priority, hang in a slowly turning tangle (the complaints),
 *   2. they are pulled straight, right end first, into four lanes that feed a frosted ticket card,
 *   3. the card's status flips to RESOLVED, a check badge pops on and a ring pulses out,
 *   4. the threads loosen into a new tangle and the next ticket number comes up.
 * The whole stage follows the cursor a little. Stars and faint holographic tickets give depth.
 * This file only loads when canRunScene3d said yes (never with reduced motion or on phones).
 */

const THREADS = [
  { color: '#f43f5e', p: 2, q: 3, phase: 0 },
  { color: '#f59e0b', p: 3, q: 4, phase: 1.3 },
  { color: '#60a5fa', p: 2, q: 5, phase: 2.4 },
  { color: '#a3b1c6', p: 3, q: 5, phase: 3.7 },
] as const;
const BEADS = 230;
const PERIOD = 12;
const CARD_X = 1.95;
const CARD_W = 1.6;
const CARD_H = 1.06;
const LANE_START_X = -3.3;
const LANE_END_X = CARD_X - CARD_W / 2 - 0.02;
const TANGLE_CENTER = new Vector3(-1.05, 0.05, 0);
const TITLES = [
  'Card charged twice',
  'Refund not received',
  'Wallet top-up failed',
  'Order stuck in transit',
  'Login code never arrives',
] as const;

function clamp01(x: number) {
  return Math.min(1, Math.max(0, x));
}
function smooth(x: number) {
  const t = clamp01(x);
  return t * t * (3 - 2 * t);
}
function easeOutBack(x: number) {
  const t = clamp01(x);
  const c = 1.9;
  return 1 + (c + 1) * (t - 1) ** 3 + c * (t - 1) ** 2;
}

/** Where the story is at time t: untangle 0..1, check 0..1, and which loop (ticket) this is. */
function storyAt(t: number) {
  const c = t % PERIOD;
  const untangle =
    c < 2.4 ? 0 : c < 5.4 ? smooth((c - 2.4) / 3) : c < 10.3 ? 1 : 1 - smooth((c - 10.3) / 1.7);
  const check = c < 5.6 ? 0 : c < 6.5 ? clamp01((c - 5.6) / 0.9) : c < 10.1 ? 1 : 0;
  return { untangle, check, pulse: clamp01((c - 6.2) / 1.4), cycle: Math.floor(t / PERIOD) };
}

/** One priority thread: a string of glowing beads that morphs from tangle to lane. */
function Thread({ index }: { index: number }) {
  const def = THREADS[index] ?? THREADS[0];
  const core = useRef<InstancedMesh>(null);
  const glow = useRef<InstancedMesh>(null);
  const dummy = useMemo(() => new Object3D(), []);
  const lane = (1.5 - index) * 0.27;

  useFrame(({ clock }) => {
    const t = clock.elapsedTime;
    const { untangle } = storyAt(t);
    const coreMesh = core.current;
    const glowMesh = glow.current;
    if (!coreMesh || !glowMesh) return;
    for (let i = 0; i < BEADS; i++) {
      const s = i / (BEADS - 1);
      // The tangle: a torus knot per thread, each with its own winding, slowly turning.
      const u = s * Math.PI * 2 + def.phase + t * 0.22;
      const r = Math.cos(def.q * u) + 2.1;
      const tx = TANGLE_CENTER.x + r * Math.cos(def.p * u) * 0.36;
      const ty = TANGLE_CENTER.y + r * Math.sin(def.p * u) * 0.36;
      const tz = -Math.sin(def.q * u) * 0.42;
      // The lane: straight, with a soft travelling ripple that calms down near the card.
      const sx = LANE_START_X + s * (LANE_END_X - LANE_START_X);
      const sy = lane + Math.sin(s * 9 - t * 2.6 + index) * 0.035 * (1 - s);
      // Each bead straightens on its own schedule: the right end (at the card) first.
      const m = smooth(untangle * 1.7 - (1 - s) * 0.7);
      dummy.position.set(tx + (sx - tx) * m, ty + (sy - ty) * m, tz * (1 - m));
      dummy.updateMatrix();
      coreMesh.setMatrixAt(i, dummy.matrix);
      glowMesh.setMatrixAt(i, dummy.matrix);
    }
    coreMesh.instanceMatrix.needsUpdate = true;
    glowMesh.instanceMatrix.needsUpdate = true;
  });

  return (
    <>
      <instancedMesh ref={core} args={[undefined, undefined, BEADS]} frustumCulled={false}>
        <sphereGeometry args={[0.032, 10, 8]} />
        <meshStandardMaterial
          color={def.color}
          emissive={def.color}
          emissiveIntensity={1.8}
          roughness={0.35}
          toneMapped={false}
        />
      </instancedMesh>
      <instancedMesh ref={glow} args={[undefined, undefined, BEADS]} frustumCulled={false}>
        <sphereGeometry args={[0.085, 8, 6]} />
        <meshBasicMaterial
          color={def.color}
          transparent
          opacity={0.1}
          blending={AdditiveBlending}
          depthWrite={false}
          toneMapped={false}
        />
      </instancedMesh>
    </>
  );
}

function roundedRect(w: number, h: number, r: number): Shape {
  const shape = new Shape();
  const x = -w / 2;
  const y = -h / 2;
  shape.moveTo(x + r, y);
  shape.lineTo(x + w - r, y);
  shape.quadraticCurveTo(x + w, y, x + w, y + r);
  shape.lineTo(x + w, y + h - r);
  shape.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  shape.lineTo(x + r, y + h);
  shape.quadraticCurveTo(x, y + h, x, y + h - r);
  shape.lineTo(x, y + r);
  shape.quadraticCurveTo(x, y, x + r, y);
  return shape;
}

function pill(
  ctx: CanvasRenderingContext2D,
  text: string,
  x: number,
  y: number,
  bg: string,
  alignRight = false,
) {
  ctx.font = '700 30px "Inter Variable", system-ui, sans-serif';
  const w = ctx.measureText(text).width + 40;
  const left = alignRight ? x - w : x;
  ctx.fillStyle = bg;
  ctx.beginPath();
  ctx.roundRect(left, y, w, 50, 25);
  ctx.fill();
  ctx.fillStyle = '#ffffff';
  ctx.fillText(text, left + 20, y + 36);
}

/** The ticket's face, drawn on a 2D canvas: number, status, title, customer, priority, SLA bar. */
class CardFace {
  readonly canvas = document.createElement('canvas');
  readonly texture: CanvasTexture;

  constructor() {
    this.canvas.width = 1024;
    this.canvas.height = 680;
    this.texture = new CanvasTexture(this.canvas);
    this.texture.colorSpace = SRGBColorSpace;
    this.texture.anisotropy = 4;
  }

  draw(cycle: number, resolved: boolean) {
    const ctx = this.canvas.getContext('2d');
    if (!ctx) return;
    ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
    ctx.fillStyle = 'rgba(255,255,255,0.78)';
    ctx.font = '600 36px "JetBrains Mono Variable", ui-monospace, monospace';
    ctx.fillText(`UNT-${String(123 + cycle).padStart(6, '0')}`, 64, 104);
    pill(ctx, resolved ? 'RESOLVED' : 'OPEN', 960, 62, resolved ? '#16a34a' : '#d97706', true);
    ctx.fillStyle = '#ffffff';
    ctx.font = '700 66px "Space Grotesk Variable", system-ui, sans-serif';
    ctx.fillText(TITLES[cycle % TITLES.length] ?? TITLES[0], 64, 250);
    ctx.fillStyle = 'rgba(255,255,255,0.72)';
    ctx.font = '500 36px "Inter Variable", system-ui, sans-serif';
    ctx.fillText(resolved ? 'Resolved · refund issued' : 'Assigned to Riya · SLA 1 h', 64, 320);
    pill(ctx, 'CRITICAL', 64, 430, '#e11d48');
    // SLA bar: amber and part-used while open, full green once resolved.
    ctx.fillStyle = 'rgba(255,255,255,0.18)';
    ctx.beginPath();
    ctx.roundRect(64, 540, 896, 22, 11);
    ctx.fill();
    ctx.fillStyle = resolved ? '#22c55e' : '#f59e0b';
    ctx.beginPath();
    ctx.roundRect(64, 540, resolved ? 896 : 560, 22, 11);
    ctx.fill();
    this.texture.needsUpdate = true;
  }

  dispose() {
    this.texture.dispose();
  }
}

function useCardFace(): CardFace {
  const face = useMemo(() => new CardFace(), []);
  useEffect(
    () => () => {
      face.dispose();
    },
    [face],
  );
  return face;
}

/** A soft round glow (white centre fading to nothing), tinted by the material colour. */
function useGlowTexture() {
  const texture = useMemo(() => {
    const c = document.createElement('canvas');
    c.width = 128;
    c.height = 128;
    const ctx = c.getContext('2d');
    if (ctx) {
      const g = ctx.createRadialGradient(64, 64, 0, 64, 64, 64);
      g.addColorStop(0, 'rgba(255,255,255,1)');
      g.addColorStop(0.45, 'rgba(255,255,255,0.35)');
      g.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, 128, 128);
    }
    return new CanvasTexture(c);
  }, []);
  useEffect(
    () => () => {
      texture.dispose();
    },
    [texture],
  );
  return texture;
}

/** The frosted ticket the lanes feed into, with its resolved badge and pulse ring. */
function TicketCard({ colors }: { colors: ThemeColors }) {
  const group = useRef<Group>(null);
  const badge = useRef<Group>(null);
  const ring = useRef<Mesh>(null);
  const halo = useRef<Mesh>(null);
  const drawn = useRef('');
  const face = useCardFace();
  const glowTexture = useGlowTexture();
  const body = useMemo(() => {
    const geometry = new ExtrudeGeometry(roundedRect(CARD_W, CARD_H, 0.12), {
      depth: 0.05,
      bevelEnabled: true,
      bevelThickness: 0.02,
      bevelSize: 0.02,
      bevelSegments: 4,
    });
    geometry.center();
    return geometry;
  }, []);
  const check = useMemo(() => {
    const path = new CurvePath<Vector3>();
    path.add(new LineCurve3(new Vector3(-0.085, 0, 0), new Vector3(-0.02, -0.065, 0)));
    path.add(new LineCurve3(new Vector3(-0.02, -0.065, 0), new Vector3(0.1, 0.075, 0)));
    return new TubeGeometry(path, 24, 0.022, 8, false);
  }, []);
  useEffect(
    () => () => {
      body.dispose();
      check.dispose();
    },
    [body, check],
  );

  useFrame(({ clock }) => {
    const t = clock.elapsedTime;
    const story = storyAt(t);
    const key = `${String(story.cycle)}-${String(story.check > 0.5)}`;
    if (drawn.current !== key) {
      face.draw(story.cycle, story.check > 0.5);
      drawn.current = key;
    }
    if (group.current) {
      group.current.position.y = Math.sin(t * 0.8) * 0.05;
      group.current.rotation.y = -0.28 + Math.sin(t * 0.45) * 0.06;
      group.current.rotation.x = Math.sin(t * 0.6) * 0.03;
    }
    badge.current?.scale.setScalar(Math.max(0.0001, easeOutBack(story.check)));
    if (ring.current) {
      const p = story.check > 0 ? story.pulse : 1;
      ring.current.scale.setScalar(1 + p * 3.2);
      (ring.current.material as MeshBasicMaterial).opacity = (1 - p) * 0.55;
    }
    if (halo.current) {
      (halo.current.material as MeshBasicMaterial).opacity = 0.18 + story.check * 0.22;
    }
  });

  return (
    <group position={[CARD_X, 0, 0]}>
      <group ref={group}>
        {/* Soft halo in the theme glow behind the card. */}
        <mesh ref={halo} position={[0, 0, -0.3]} scale={[3.4, 2.6, 1]}>
          <planeGeometry args={[1, 1]} />
          <meshBasicMaterial
            map={glowTexture}
            color={colors.a3}
            transparent
            opacity={0.2}
            blending={AdditiveBlending}
            depthWrite={false}
          />
        </mesh>
        <mesh geometry={body}>
          <meshPhysicalMaterial
            color={new Color(colors.a1).lerp(new Color('#0b0820'), 0.35)}
            transmission={0.92}
            thickness={0.5}
            roughness={0.32}
            ior={1.35}
            clearcoat={1}
            clearcoatRoughness={0.15}
            iridescence={0.5}
            transparent
            opacity={0.96}
          />
        </mesh>
        <mesh position={[0, 0, 0.051]}>
          <planeGeometry args={[CARD_W * 0.97, CARD_H * 0.97]} />
          <meshBasicMaterial map={face.texture} transparent toneMapped={false} />
        </mesh>
        <group ref={badge} position={[CARD_W / 2 - 0.04, CARD_H / 2 - 0.04, 0.12]}>
          <mesh>
            <circleGeometry args={[0.17, 40]} />
            <meshStandardMaterial
              color="#16a34a"
              emissive="#22c55e"
              emissiveIntensity={0.9}
              toneMapped={false}
            />
          </mesh>
          <mesh geometry={check} position={[0, 0, 0.02]} scale={1.15}>
            <meshBasicMaterial color="#ffffff" toneMapped={false} />
          </mesh>
          <mesh ref={ring} position={[0, 0, -0.01]}>
            <ringGeometry args={[0.17, 0.2, 48]} />
            <meshBasicMaterial color="#4ade80" transparent opacity={0} depthWrite={false} />
          </mesh>
        </group>
      </group>
    </group>
  );
}

/** Distant stars in the theme colours, slowly turning. */
function Stars({ colors }: { colors: ThemeColors }) {
  const ref = useRef<Group>(null);
  const geometry = useMemo(() => {
    const count = 650;
    const positions: number[] = [];
    const tints: number[] = [];
    const palette = [new Color('#ffffff'), new Color(colors.a3), new Color(colors.a4)];
    let seed = 7;
    const rand = () => {
      seed = (seed * 16807) % 2147483647;
      return seed / 2147483647;
    };
    for (let i = 0; i < count; i++) {
      positions.push((rand() - 0.5) * 22, (rand() - 0.5) * 12, -3 - rand() * 9);
      const c = palette[i % 5 === 0 ? 1 : i % 7 === 0 ? 2 : 0] ?? palette[0];
      if (c) tints.push(c.r, c.g, c.b);
    }
    const g = new BufferGeometry();
    g.setAttribute('position', new Float32BufferAttribute(positions, 3));
    g.setAttribute('color', new Float32BufferAttribute(tints, 3));
    return g;
  }, [colors.a3, colors.a4]);
  useEffect(
    () => () => {
      geometry.dispose();
    },
    [geometry],
  );
  useFrame((_, delta) => {
    if (ref.current) ref.current.rotation.z += delta * 0.006;
  });
  return (
    <group ref={ref}>
      <points geometry={geometry}>
        <pointsMaterial
          size={0.035}
          vertexColors
          transparent
          opacity={0.85}
          depthWrite={false}
          sizeAttenuation
        />
      </points>
    </group>
  );
}

/** Faint holographic tickets drifting far behind the story: the queue that is still waiting. */
function GhostTickets({ colors }: { colors: ThemeColors }) {
  const ref = useRef<Group>(null);
  const shape = useMemo(
    () => new EdgesGeometry(new ShapeGeometry(roundedRect(0.9, 0.6, 0.08))),
    [],
  );
  useEffect(
    () => () => {
      shape.dispose();
    },
    [shape],
  );
  const spots = useMemo(
    () =>
      [
        [-3.6, 1.5, -3.2, 0.4],
        [3.9, 1.7, -3.8, -0.3],
        [-0.4, -1.9, -4.2, 0.2],
        [4.3, -1.4, -2.8, -0.5],
        [-4.2, -1.2, -3.6, 0.6],
        [0.9, 2.2, -5, -0.2],
      ] as const,
    [],
  );
  useFrame(({ clock }) => {
    const t = clock.elapsedTime;
    ref.current?.children.forEach((child, i) => {
      child.position.y += Math.sin(t * 0.5 + i) * 0.0009;
      child.rotation.z = Math.sin(t * 0.3 + i) * 0.08;
    });
  });
  return (
    <group ref={ref}>
      {spots.map(([x, y, z, ry]) => (
        <lineSegments
          key={`${String(x)}${String(y)}`}
          geometry={shape}
          position={[x, y, z]}
          rotation={[0, ry, 0]}
        >
          <lineBasicMaterial color={colors.a3} transparent opacity={0.28} depthWrite={false} />
        </lineSegments>
      ))}
    </group>
  );
}

/** Fits the story to the stage's width and tilts it toward the cursor. */
function Stage({ colors }: { colors: ThemeColors }) {
  const root = useRef<Group>(null);
  const width = useThree((s) => s.viewport.width);
  const scale = Math.min(1.35, width / 5.6);
  useFrame(({ pointer }, delta) => {
    const g = root.current;
    if (!g) return;
    const k = Math.min(1, delta * 2.5);
    g.rotation.y += (pointer.x * 0.16 - g.rotation.y) * k;
    g.rotation.x += (-pointer.y * 0.1 - g.rotation.x) * k;
  });
  return (
    <group ref={root} scale={scale}>
      {THREADS.map((thread, i) => (
        <Thread key={thread.color} index={i} />
      ))}
      <TicketCard colors={colors} />
    </group>
  );
}

/** Pauses rendering while the tab is hidden. */
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

export default function UntangleScene({ colors }: { colors: ThemeColors }) {
  const visible = usePageVisible();
  return (
    <div className="scene-fade-in absolute inset-0">
      <Canvas
        dpr={[1, 1.75]}
        frameloop={visible ? 'always' : 'never'}
        camera={{ position: [0, 0, 7], fov: 35 }}
        gl={{ alpha: true, antialias: true, powerPreference: 'high-performance' }}
      >
        <StudioEnvironment intensity={0.7} />
        <ambientLight intensity={0.35} />
        <pointLight position={[-3, 2.5, 3]} intensity={28} color={colors.a3} />
        <pointLight position={[3.5, -1.5, 2.5]} intensity={22} color={colors.a4} />
        <directionalLight position={[2, 3, 4]} intensity={1.2} />
        <Stars colors={colors} />
        <GhostTickets colors={colors} />
        <Stage colors={colors} />
      </Canvas>
    </div>
  );
}
