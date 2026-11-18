import { useEffect, useMemo, useState } from 'react';
import { BackSide, Color, DataTexture, NearestFilter, RedFormat, ShaderMaterial } from 'three';

/*
 * Shared pieces for the brutalist 3D scenes (ADR-027): flat cel shading, ink outlines and a
 * pause-when-hidden switch. Lives with three.js in the lazy scene chunks, never the main bundle.
 */

export const INK = '#111111';

/** Three flat tones for the cel look. */
export function useToonRamp() {
  return useMemo(() => {
    const tex = new DataTexture(new Uint8Array([90, 175, 255]), 3, 1, RedFormat);
    tex.minFilter = NearestFilter;
    tex.magFilter = NearestFilter;
    tex.generateMipmaps = false;
    tex.needsUpdate = true;
    return tex;
  }, []);
}

/** Ink outline: the back faces pushed out along their normals, drawn flat black. */
function makeOutline(thickness: number) {
  return new ShaderMaterial({
    side: BackSide,
    uniforms: { thickness: { value: thickness }, color: { value: new Color(INK) } },
    vertexShader: /* glsl */ `
      uniform float thickness;
      void main() {
        vec3 p = position + normalize(normal) * thickness;
        gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
      }`,
    fragmentShader: /* glsl */ `
      uniform vec3 color;
      void main() { gl_FragColor = vec4(color, 1.0); }`,
  });
}

export function useOutline(thickness = 0.03) {
  const material = useMemo(() => makeOutline(thickness), [thickness]);
  useEffect(
    () => () => {
      material.dispose();
    },
    [material],
  );
  return material;
}

/** Pauses a canvas while the tab is hidden. */
export function usePageVisible() {
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
