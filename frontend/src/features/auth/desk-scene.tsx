import { useEffect, useMemo, useRef } from 'react';
import { Canvas, useFrame, useThree } from '@react-three/fiber';
import {
  CanvasTexture,
  type DataTexture,
  ExtrudeGeometry,
  type Group,
  type Mesh,
  type ShaderMaterial,
  Shape,
  ShapeGeometry,
  SRGBColorSpace,
} from 'three';
import { RoundedBoxGeometry } from 'three/examples/jsm/geometries/RoundedBoxGeometry.js';
import { Inked } from '@/components/three/inked';
import { INK, useOutline, usePageVisible, useToonRamp } from '@/components/three/toon';

/*
 * The login scene (ADR-027): the support desk, drawn like the rest of the UI (flat toon colour,
 * thick ink outlines, hard offset shadows on the panel). Every object is a piece of Untangle:
 *   - a paper support ticket slides in, the rubber stamp slams RESOLVED onto it, it flies out
 *     and the next ticket number comes in (the loop),
 *   - the SLA stopwatch ticks (1 h for critical),
 *   - three escalation chevrons light up L1 → L2 → L3,
 *   - the HMAC padlock clicks shut as each partner ticket arrives (signed webhook).
 * Loaded only when canRunScene3d says yes; the static SVG desk is the fallback.
 */

const PAPER = '#fffdf5';
const PERIOD = 8;
const TICKET_W = 3.25;
const TICKET_H = 1.86;
const TITLES = [
  'Card charged twice',
  'Refund not received',
  'Wallet top-up failed',
  'Order stuck in transit',
  'Login code never arrives',
] as const;

const clamp01 = (x: number) => Math.min(1, Math.max(0, x));
const easeOutBack = (x: number) => {
  const t = clamp01(x);
  return 1 + 2.4 * (t - 1) ** 3 + 1.4 * (t - 1) ** 2;
};
const easeIn = (x: number) => clamp01(x) ** 3;

/** The loop: where the ticket is, where the stamp is, and whether this ticket is stamped yet. */
function storyAt(t: number) {
  const c = t % PERIOD;
  const cycle = Math.floor(t / PERIOD);
  const enter = easeOutBack(c / 0.9); // 0..1 slide in
  const exit = easeIn((c - 6.6) / 0.8); // 0..1 fly out
  // Stamp height: hovering 1.75, slam to 0.42 at 2.6 s, back up by 3.4 s.
  let stampY = 1.75 + Math.sin(c * 3) * 0.05;
  if (c >= 2.2 && c < 2.6) stampY = 1.75 - easeIn((c - 2.2) / 0.4) * 1.33;
  else if (c >= 2.6 && c < 3.4) stampY = 0.42 + easeOutBack((c - 2.6) / 0.8) * 1.33;
  const impact = c >= 2.6 && c < 3.0 ? 1 - (c - 2.6) / 0.4 : 0;
  return { c, cycle, enter, exit, stampY, impact, stamped: c >= 2.6 };
}

function ticketShape(w: number, h: number, notch: number): Shape {
  const s = new Shape();
  s.moveTo(-w / 2, -h / 2);
  s.lineTo(w / 2, -h / 2);
  s.lineTo(w / 2, -notch);
  s.absarc(w / 2, 0, notch, -Math.PI / 2, Math.PI / 2, true);
  s.lineTo(w / 2, h / 2);
  s.lineTo(-w / 2, h / 2);
  s.lineTo(-w / 2, notch);
  s.absarc(-w / 2, 0, notch, Math.PI / 2, -Math.PI / 2, true);
  s.lineTo(-w / 2, -h / 2);
  return s;
}

/** The ticket's printed face (2D canvas): number, title, priority, assignee, and the stamp. */
class TicketFace {
  readonly canvas = document.createElement('canvas');
  readonly texture: CanvasTexture;

  constructor() {
    this.canvas.width = 1080;
    this.canvas.height = 620;
    this.texture = new CanvasTexture(this.canvas);
    this.texture.colorSpace = SRGBColorSpace;
    this.texture.anisotropy = 4;
  }

  draw(cycle: number, stamped: boolean) {
    const ctx = this.canvas.getContext('2d');
    if (!ctx) return;
    const { width: w, height: h } = this.canvas;
    ctx.clearRect(0, 0, w, h);
    // Perforation between the stub and the body.
    ctx.strokeStyle = INK;
    ctx.lineWidth = 6;
    ctx.setLineDash([18, 16]);
    ctx.beginPath();
    ctx.moveTo(800, 30);
    ctx.lineTo(800, h - 30);
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.fillStyle = INK;
    ctx.font = '700 44px "JetBrains Mono Variable", ui-monospace, monospace';
    ctx.fillText(`UNT-${String(123 + cycle).padStart(6, '0')}`, 90, 120);
    ctx.font = '700 70px "Space Grotesk Variable", system-ui, sans-serif';
    ctx.fillText(TITLES[cycle % TITLES.length] ?? TITLES[0], 90, 250, 680);
    ctx.font = '500 38px "Inter Variable", system-ui, sans-serif';
    ctx.fillStyle = '#4d4840';
    ctx.fillText('Assigned to Riya · SLA 1 h', 90, 320);
    // Priority chip with a hard shadow.
    ctx.fillStyle = INK;
    ctx.fillRect(98, 418, 250, 72);
    ctx.fillStyle = '#c81e1e';
    ctx.fillRect(90, 410, 250, 72);
    ctx.strokeStyle = INK;
    ctx.lineWidth = 5;
    ctx.strokeRect(90, 410, 250, 72);
    ctx.fillStyle = '#ffffff';
    ctx.font = '800 40px "Inter Variable", system-ui, sans-serif';
    ctx.fillText('CRITICAL', 118, 460);
    // Stub: big admit-one style number.
    ctx.save();
    ctx.translate(940, h / 2);
    ctx.rotate(-Math.PI / 2);
    ctx.fillStyle = INK;
    ctx.font = '800 64px "Space Grotesk Variable", system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(`#${String(123 + cycle)}`, 0, 22);
    ctx.restore();
    if (stamped) {
      ctx.save();
      ctx.translate(560, 400);
      ctx.rotate(-0.16);
      ctx.strokeStyle = '#16a34a';
      ctx.fillStyle = '#16a34a';
      ctx.lineWidth = 12;
      ctx.strokeRect(-230, -70, 460, 140);
      ctx.font = '900 92px "Space Grotesk Variable", system-ui, sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('RESOLVED', 0, 33);
      ctx.restore();
    }
    this.texture.needsUpdate = true;
  }

  dispose() {
    this.texture.dispose();
  }
}

function Ticket({ ramp, outline }: { ramp: DataTexture; outline: ShaderMaterial }) {
  const group = useRef<Group>(null);
  const drawn = useRef('');
  const face = useMemo(() => new TicketFace(), []);
  const { body, front } = useMemo(() => {
    const shape = ticketShape(TICKET_W, TICKET_H, 0.17);
    const b = new ExtrudeGeometry(shape, {
      depth: 0.06,
      bevelEnabled: true,
      bevelThickness: 0.02,
      bevelSize: 0.02,
      bevelSegments: 3,
    });
    b.center();
    const f = new ShapeGeometry(shape, 24);
    // ShapeGeometry UVs are raw x/y; map them onto the 0..1 face texture.
    const uv = f.attributes.uv;
    const pos = f.attributes.position;
    if (uv && pos) {
      for (let i = 0; i < uv.count; i++) {
        uv.setXY(
          i,
          (pos.getX(i) + TICKET_W / 2) / TICKET_W,
          (pos.getY(i) + TICKET_H / 2) / TICKET_H,
        );
      }
      uv.needsUpdate = true;
    }
    return { body: b, front: f };
  }, []);
  useEffect(
    () => () => {
      face.dispose();
      body.dispose();
      front.dispose();
    },
    [face, body, front],
  );

  useFrame(({ clock }) => {
    const s = storyAt(clock.elapsedTime);
    const key = `${String(s.cycle)}-${String(s.stamped)}`;
    if (drawn.current !== key) {
      face.draw(s.cycle, s.stamped);
      drawn.current = key;
    }
    const g = group.current;
    if (!g) return;
    g.position.x = -6.5 + s.enter * 6.5 + s.exit * 7.5;
    g.position.y = -0.35 + Math.sin(s.c * 1.4) * 0.04;
    g.rotation.z = (1 - s.enter) * 0.5 - s.exit * 0.6 + Math.sin(s.c * 1.1) * 0.025;
    g.rotation.y = -0.18;
    g.scale.set(1 + s.impact * 0.04, 1 - s.impact * 0.06, 1);
  });

  return (
    <group ref={group}>
      <Inked geometry={body} color={PAPER} ramp={ramp} outline={outline} />
      <mesh geometry={front} position={[0, 0, 0.052]}>
        <meshBasicMaterial map={face.texture} transparent toneMapped={false} />
      </mesh>
    </group>
  );
}

/** The rubber stamp: red knob, wooden block, rubber pad. Slams down once per ticket. */
function Stamp({ ramp, outline }: { ramp: DataTexture; outline: ShaderMaterial }) {
  const group = useRef<Group>(null);
  const geo = useMemo(
    () => ({
      knob: new RoundedBoxGeometry(0.5, 0.42, 0.5, 4, 0.2),
      neck: new RoundedBoxGeometry(0.22, 0.42, 0.22, 3, 0.08),
      block: new RoundedBoxGeometry(1.05, 0.3, 0.62, 4, 0.08),
      pad: new RoundedBoxGeometry(0.98, 0.1, 0.56, 3, 0.03),
    }),
    [],
  );
  useEffect(
    () => () => {
      Object.values(geo).forEach((g) => {
        g.dispose();
      });
    },
    [geo],
  );
  useFrame(({ clock }) => {
    const s = storyAt(clock.elapsedTime);
    const g = group.current;
    if (!g) return;
    g.position.y = s.stampY;
    // Leans in while hovering, straight at the slam.
    g.rotation.z = s.c < 2.2 ? Math.sin(s.c * 2.2) * 0.12 : 0;
  });
  return (
    <group ref={group} position={[0.7, 1.75, 0.45]} rotation={[0.35, 0, 0]}>
      <Inked
        geometry={geo.knob}
        color="#ff5a5f"
        ramp={ramp}
        outline={outline}
        position={[0, 0.78, 0]}
      />
      <Inked
        geometry={geo.neck}
        color="#d9a066"
        ramp={ramp}
        outline={outline}
        position={[0, 0.42, 0]}
      />
      <Inked
        geometry={geo.block}
        color="#d9a066"
        ramp={ramp}
        outline={outline}
        position={[0, 0.12, 0]}
      />
      <Inked
        geometry={geo.pad}
        color="#16a34a"
        ramp={ramp}
        outline={outline}
        position={[0, -0.08, 0]}
      />
    </group>
  );
}

/** Speed lines that burst from the stamp at impact. */
function Impact() {
  const group = useRef<Group>(null);
  const lines = useMemo(() => Array.from({ length: 8 }, (_, i) => (i / 8) * Math.PI * 2 + 0.2), []);
  useFrame(({ clock }) => {
    const s = storyAt(clock.elapsedTime);
    const g = group.current;
    if (!g) return;
    g.visible = s.impact > 0;
    const spread = 1 - s.impact;
    g.children.forEach((child, i) => {
      const a = lines[i] ?? 0;
      const r = 0.9 + spread * 0.7;
      child.position.set(Math.cos(a) * r * 1.2, Math.sin(a) * r * 0.55, 0);
      child.rotation.z = a;
      child.scale.x = 0.4 + s.impact * 0.8;
    });
  });
  return (
    <group ref={group} position={[0.7, 0.2, 0.4]}>
      {lines.map((a) => (
        <mesh key={a}>
          <boxGeometry args={[0.32, 0.05, 0.02]} />
          <meshBasicMaterial color={INK} />
        </mesh>
      ))}
    </group>
  );
}

/** The SLA stopwatch: ticking hand, crown, a green "1h" face that turns red near breach. */
function Stopwatch({ ramp, outline }: { ramp: DataTexture; outline: ShaderMaterial }) {
  const hand = useRef<Mesh>(null);
  const group = useRef<Group>(null);
  const geo = useMemo(
    () => ({
      body: new RoundedBoxGeometry(1.25, 1.25, 0.32, 6, 0.6),
      crown: new RoundedBoxGeometry(0.26, 0.24, 0.2, 3, 0.06),
      face: new RoundedBoxGeometry(0.98, 0.98, 0.04, 6, 0.48),
    }),
    [],
  );
  useEffect(
    () => () => {
      Object.values(geo).forEach((g) => {
        g.dispose();
      });
    },
    [geo],
  );
  useFrame(({ clock }) => {
    const t = clock.elapsedTime;
    // Ticks: one hard step per second, like a real stopwatch.
    if (hand.current) hand.current.rotation.z = -Math.floor(t * 2) * (Math.PI / 15);
    if (group.current) {
      group.current.position.y = 1.05 + Math.sin(t * 1.3) * 0.08;
      group.current.rotation.y = 0.35 + Math.sin(t * 0.7) * 0.08;
    }
  });
  return (
    <group ref={group} position={[-3.05, 1.05, -0.1]} rotation={[0, 0.35, 0.12]}>
      <Inked geometry={geo.body} color="#5cc8ff" ramp={ramp} outline={outline} />
      <Inked
        geometry={geo.crown}
        color="#5cc8ff"
        ramp={ramp}
        outline={outline}
        position={[0, 0.74, 0]}
      />
      <mesh geometry={geo.face} position={[0, 0, 0.17]}>
        <meshToonMaterial color={PAPER} gradientMap={ramp} />
      </mesh>
      {/* Twelve tick marks. */}
      {Array.from({ length: 12 }, (_, i) => {
        const a = (i / 12) * Math.PI * 2;
        return (
          <mesh
            key={a}
            position={[Math.sin(a) * 0.38, Math.cos(a) * 0.38, 0.2]}
            rotation={[0, 0, -a]}
          >
            <boxGeometry args={[0.035, i % 3 === 0 ? 0.12 : 0.07, 0.01]} />
            <meshBasicMaterial color={INK} />
          </mesh>
        );
      })}
      <mesh ref={hand} position={[0, 0, 0.21]}>
        <boxGeometry args={[0.05, 0.62, 0.02]} />
        <meshBasicMaterial color="#c81e1e" />
      </mesh>
      <mesh position={[0, 0, 0.22]}>
        <circleGeometry args={[0.06, 20]} />
        <meshBasicMaterial color={INK} />
      </mesh>
    </group>
  );
}

function chevronShape(): Shape {
  const s = new Shape();
  s.moveTo(-0.5, -0.12);
  s.lineTo(0, 0.28);
  s.lineTo(0.5, -0.12);
  s.lineTo(0.5, -0.36);
  s.lineTo(0, 0.04);
  s.lineTo(-0.5, -0.36);
  s.lineTo(-0.5, -0.12);
  return s;
}

/** Escalation levels L1 → L2 → L3 as stacked chevrons that light up in turn. */
function Escalation({ ramp, outline }: { ramp: DataTexture; outline: ShaderMaterial }) {
  const geometry = useMemo(() => {
    const g = new ExtrudeGeometry(chevronShape(), {
      depth: 0.16,
      bevelEnabled: true,
      bevelThickness: 0.02,
      bevelSize: 0.02,
      bevelSegments: 2,
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
  const group = useRef<Group>(null);
  const colors = ['#16a34a', '#f59e0b', '#c81e1e'] as const;
  useFrame(({ clock }) => {
    const t = clock.elapsedTime;
    const lit = Math.floor((t % 3) / 1); // 0, 1, 2
    group.current?.children.forEach((child, i) => {
      const on = i <= lit;
      child.position.y = i * 0.42 + (on ? Math.sin(t * 4 + i) * 0.03 : 0);
      child.scale.setScalar(on ? 1 : 0.86);
    });
    if (group.current) group.current.rotation.y = -0.4 + Math.sin(t * 0.6) * 0.1;
  });
  return (
    <group ref={group} position={[3.15, 0.55, -0.1]}>
      {colors.map((color) => (
        <Inked key={color} geometry={geometry} color={color} ramp={ramp} outline={outline} />
      ))}
    </group>
  );
}

/** The signed-webhook padlock: shackle drops shut as each ticket arrives. */
function Padlock({ ramp, outline }: { ramp: DataTexture; outline: ShaderMaterial }) {
  const shackle = useRef<Group>(null);
  const group = useRef<Group>(null);
  const label = useMemo(() => {
    const c = document.createElement('canvas');
    c.width = 256;
    c.height = 128;
    const ctx = c.getContext('2d');
    if (ctx) {
      ctx.fillStyle = '#ffd23f';
      ctx.font = '900 64px "Space Grotesk Variable", system-ui, sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('HMAC', 128, 86);
    }
    const tex = new CanvasTexture(c);
    tex.colorSpace = SRGBColorSpace;
    return tex;
  }, []);
  const geo = useMemo(() => ({ body: new RoundedBoxGeometry(0.95, 0.75, 0.38, 4, 0.12) }), []);
  useEffect(
    () => () => {
      label.dispose();
      geo.body.dispose();
    },
    [label, geo],
  );
  useFrame(({ clock }) => {
    const s = storyAt(clock.elapsedTime);
    // Open while the ticket is away, snaps shut as it lands.
    if (shackle.current) shackle.current.position.y = 0.38 + (1 - s.enter) * 0.22;
    if (group.current) {
      group.current.position.y = -1.25 + Math.sin(clock.elapsedTime * 1.2 + 1) * 0.06;
      group.current.rotation.y = -0.35 + Math.sin(clock.elapsedTime * 0.8) * 0.08;
    }
  });
  return (
    <group ref={group} position={[3.1, -1.25, 0]}>
      <group ref={shackle} position={[0, 0.38, 0]}>
        <mesh castShadow>
          <torusGeometry args={[0.3, 0.075, 12, 32, Math.PI]} />
          <meshToonMaterial color="#d6d3cc" gradientMap={ramp} />
        </mesh>
        <mesh>
          <torusGeometry args={[0.3, 0.075, 12, 32, Math.PI]} />
          <primitive object={outline} attach="material" />
        </mesh>
      </group>
      <Inked geometry={geo.body} color="#2a2a2a" ramp={ramp} outline={outline} />
      <mesh position={[0, -0.02, 0.2]}>
        <planeGeometry args={[0.85, 0.42]} />
        <meshBasicMaterial map={label} transparent toneMapped={false} />
      </mesh>
    </group>
  );
}

/** A little shake on impact, and a gentle tilt toward the cursor. */
function Rig({ children }: { children: React.ReactNode }) {
  const root = useRef<Group>(null);
  const width = useThree((s) => s.viewport.width);
  const scale = Math.min(1.05, width / 8.6);
  useFrame(({ pointer, clock }, delta) => {
    const g = root.current;
    if (!g) return;
    const k = Math.min(1, delta * 3);
    g.rotation.y += (pointer.x * 0.12 - g.rotation.y) * k;
    g.rotation.x += (-pointer.y * 0.08 - g.rotation.x) * k;
    const { impact } = storyAt(clock.elapsedTime);
    g.position.x = impact > 0 ? (Math.random() - 0.5) * 0.06 * impact : 0;
    g.position.y = impact > 0 ? (Math.random() - 0.5) * 0.06 * impact : 0;
  });
  return (
    <group ref={root} scale={scale}>
      {children}
    </group>
  );
}

function Desk({ shadowColor }: { shadowColor: string }) {
  const ramp = useToonRamp();
  const outline = useOutline(0.028);
  return (
    <>
      <ambientLight intensity={1.1} />
      <directionalLight
        position={[-4, 5, 8]}
        intensity={2.2}
        castShadow
        shadow-mapSize-width={2048}
        shadow-mapSize-height={2048}
        shadow-camera-left={-7}
        shadow-camera-right={7}
        shadow-camera-top={5}
        shadow-camera-bottom={-5}
        shadow-radius={1}
        shadow-bias={-0.0005}
      />
      {/* The panel itself catches the hard offset shadows. */}
      <mesh position={[0, 0, -0.62]} receiveShadow>
        <planeGeometry args={[30, 20]} />
        <shadowMaterial color={shadowColor} opacity={1} transparent />
      </mesh>
      <Rig>
        <Stopwatch ramp={ramp} outline={outline} />
        <Escalation ramp={ramp} outline={outline} />
        <Padlock ramp={ramp} outline={outline} />
        <Ticket ramp={ramp} outline={outline} />
        <Stamp ramp={ramp} outline={outline} />
        <Impact />
      </Rig>
    </>
  );
}

export default function DeskScene({ shadowColor }: { shadowColor: string }) {
  const visible = usePageVisible();
  return (
    <div className="scene-pop-in absolute inset-0">
      <Canvas
        shadows="basic"
        dpr={[1, 1.75]}
        frameloop={visible ? 'always' : 'never'}
        camera={{ position: [0, 0.15, 8.6], fov: 34 }}
        gl={{ alpha: true, antialias: true }}
      >
        <Desk shadowColor={shadowColor} />
      </Canvas>
    </div>
  );
}
