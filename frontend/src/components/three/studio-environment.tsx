import { useEffect } from 'react';
import { useThree } from '@react-three/fiber';
import { PMREMGenerator } from 'three';
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js';

/**
 * Soft studio reflections for glossy materials, generated on the GPU from three's built-in room
 * (no HDR file to download). Disposed with the scene.
 */
export function StudioEnvironment({ intensity = 1 }: { intensity?: number }) {
  // Read the renderer and scene inside the effect (R3F's store), not as hook values to mutate.
  const get = useThree((s) => s.get);
  useEffect(() => {
    const { gl, scene } = get();
    const pmrem = new PMREMGenerator(gl);
    const room = new RoomEnvironment();
    const target = pmrem.fromScene(room, 0.04);
    scene.environment = target.texture;
    scene.environmentIntensity = intensity;
    return () => {
      scene.environment = null;
      target.dispose();
      room.dispose();
      pmrem.dispose();
    };
  }, [get, intensity]);
  return null;
}
