import type { BufferGeometry, DataTexture, ShaderMaterial } from 'three';

/** A toon mesh with an ink outline and a hard shadow. */
export function Inked({
  geometry,
  color,
  ramp,
  outline,
  position = [0, 0, 0],
  rotation = [0, 0, 0],
}: {
  geometry: BufferGeometry;
  color: string;
  ramp: DataTexture;
  outline: ShaderMaterial;
  position?: [number, number, number];
  rotation?: [number, number, number];
}) {
  return (
    <group position={position} rotation={rotation}>
      <mesh geometry={geometry} castShadow>
        <meshToonMaterial color={color} gradientMap={ramp} />
      </mesh>
      <mesh geometry={geometry} material={outline} />
    </group>
  );
}
