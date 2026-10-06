/** Deterministic glTF refinements. Source geometry uses millimetres/Z-up.
 * Standard PBR + embedded maps only: no runtime shader patches or downloads.
 */
import { deflateSync } from "node:zlib";
import { BufferAttribute, BufferGeometry, Color, CylinderGeometry } from "three";
import { mergeVertices, toCreasedNormals } from "three/addons/utils/BufferGeometryUtils.js";

const channels = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4 };
const types = { 5126: Float32Array, 5125: Uint32Array, 5123: Uint16Array, 5121: Uint8Array };

export function readArray(gltf, binary, index) {
  const a = gltf.accessors[index], view = gltf.bufferViews[a.bufferView], Type = types[a.componentType];
  const width = channels[a.type], bytes = Type.BYTES_PER_ELEMENT;
  const result = new Type(a.count * width);
  const data = new DataView(binary.buffer, binary.byteOffset, binary.byteLength);
  const method = { 5126: "getFloat32", 5125: "getUint32", 5123: "getUint16", 5121: "getUint8" }[a.componentType];
  for (let i = 0; i < a.count; i++) for (let c = 0; c < width; c++) {
    result[i * width + c] = data[method]((view.byteOffset ?? 0) + (a.byteOffset ?? 0)
      + i * (view.byteStride ?? width * bytes) + c * bytes, true);
  }
  return result;
}

function pngChunk(type, data) {
  const bytes = Buffer.concat([Buffer.from(type), data]);
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  const head = Buffer.alloc(4), tail = Buffer.alloc(4);
  head.writeUInt32BE(data.length); tail.writeUInt32BE((crc ^ 0xffffffff) >>> 0);
  return Buffer.concat([head, bytes, tail]);
}

export function encodePng(size, rgba) {
  const header = Buffer.alloc(13); header.writeUInt32BE(size, 0); header.writeUInt32BE(size, 4);
  header[8] = 8; header[9] = 6;
  const rows = Buffer.alloc(size * (size * 4 + 1));
  for (let y = 0; y < size; y++) Buffer.from(rgba).copy(rows, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4);
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), pngChunk("IHDR", header),
    pngChunk("IDAT", deflateSync(rows, { level: 9 })), pngChunk("IEND", Buffer.alloc(0))]);
}

export function grainMaps(kind, size = 64) {
  const height = new Float32Array(size * size), normal = new Uint8Array(size * size * 4), roughness = normal.slice();
  const noise = (x, y) => {
    let n = Math.imul(x + 17, 374761393) ^ Math.imul(y + 91, 668265263);
    n = Math.imul(n ^ (n >>> 13), 1274126177);
    return ((n ^ (n >>> 16)) >>> 0) / 4294967295;
  };
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    height[y * size + x] = kind === "steel" ? noise(0, y) * 0.88 + noise(x, y) * 0.12 : noise(x, y);
  }
  const sample = (x, y) => height[((y + size) % size) * size + (x + size) % size];
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    const offset = 4 * (y * size + x), amplitude = kind === "steel" ? 0.12 : 1;
    const nx = (sample(x - 1, y) - sample(x + 1, y)) * amplitude;
    const ny = (sample(x, y - 1) - sample(x, y + 1)) * amplitude;
    const length = Math.hypot(nx, ny, 1);
    normal.set([Math.round((nx / length * 0.5 + 0.5) * 255), Math.round((ny / length * 0.5 + 0.5) * 255),
      Math.round((1 / length * 0.5 + 0.5) * 255), 255], offset);
    roughness.set([255, Math.round(190 + sample(x, y) * 65), 255, 255], offset);
  }
  return { normal, roughness, size };
}

function ring(w, h, radius) {
  const points = [];
  const centers = [[w / 2 - radius, h / 2 - radius], [-w / 2 + radius, h / 2 - radius],
    [-w / 2 + radius, -h / 2 + radius], [w / 2 - radius, -h / 2 + radius]];
  for (let q = 0; q < 4; q++) {
    const c = centers[q], next = centers[(q + 1) % 4];
    for (let k = 0; k < 8; k++) {
      const a = (q + k / 8) * Math.PI / 2;
      points.push([c[0] + radius * Math.cos(a), c[1] + radius * Math.sin(a)]);
    }
    const a = (q + 1) * Math.PI / 2, dx = radius * Math.cos(a), dy = radius * Math.sin(a);
    for (let k = 0; k < 8; k++) points.push([c[0] + dx + (next[0] - c[0]) * k / 8, c[1] + dy + (next[1] - c[1]) * k / 8]);
  }
  return points;
}

export function keycapGeometry(settings, center, angle) {
  // These dimensions and rim heights come from the pinned supplied key mesh.
  const profiles = [[17.15, 16.15, 1.4, 12], [17.5, 16.5, 1.5, 12.35],
    [17.25, 16.25, 1.6, 14.5], [16.95, 15.75, 1.7, 15.15], [16.35, 14.75, 1.8, 15.45]];
  for (const scale of [0.92, 0.8, 0.65, 0.5, 0.35, 0.2, 0.07]) {
    profiles.push([16.35 * scale, 14.75 * scale, 1.8 * scale, 15.45 - settings.dishDepth * (1 - scale * scale)]);
  }
  const positions = [], indices = [], count = 64;
  const append = (x, y, z) => positions.push(center[0] + x * Math.cos(angle) - y * Math.sin(angle),
    center[1] + x * Math.sin(angle) + y * Math.cos(angle), z);
  profiles.forEach(([w, h, r, z], row) => {
    for (let [x, y] of ring(w, h, r)) {
      const corner = Math.min(Math.abs(x / (w / 2)), Math.abs(y / (h / 2))) ** 2;
      const envelope = Math.min(1, row / 4) * Math.min(1, w / 16.35);
      const inset = settings.cornerInset * corner * envelope;
      x -= Math.sign(x) * inset; y -= Math.sign(y) * inset;
      append(x, y, z - settings.cornerDrop * corner * envelope);
    }
    if (row) for (let j = 0; j < count; j++) {
      const a = (row - 1) * count + j, b = (row - 1) * count + (j + 1) % count;
      const c = row * count + (j + 1) % count, d = row * count + j;
      indices.push(a, b, d, b, c, d);
    }
  });
  const bottom = positions.length / 3; append(0, 0, 12);
  const top = positions.length / 3; append(0, 0, 15.45 - settings.dishDepth);
  for (let j = 0; j < count; j++) {
    indices.push(bottom, (j + 1) % count, j);
    const last = (profiles.length - 1) * count;
    indices.push(last + j, last + (j + 1) % count, top);
  }
  return new BufferGeometry().setAttribute("position", new BufferAttribute(new Float32Array(positions), 3)).setIndex(indices);
}

function keyPose(positions) {
  const lo = [Infinity, Infinity], hi = [-Infinity, -Infinity], outer = [];
  for (let i = 0; i < positions.length; i += 3) {
    for (let c = 0; c < 2; c++) { lo[c] = Math.min(lo[c], positions[i + c]); hi[c] = Math.max(hi[c], positions[i + c]); }
    if (Math.abs(positions[i + 2] - 12.35) < 0.0001) outer.push([positions[i], positions[i + 1]]);
  }
  const center = lo.map((v, i) => (v + hi[i]) / 2);
  if (outer.length !== 48) throw new Error("Unrecognized source keycap perimeter.");
  // Recover orientation from adjacent rounded-corner centres. The source's
  // half-open arc samples bias PCA and the long connecting edges by ~0.26°.
  const circleCenter = (a, b, c) => {
    const bx = b[0] - a[0], by = b[1] - a[1], cx = c[0] - a[0], cy = c[1] - a[1];
    const d = 2 * (bx * cy - by * cx), b2 = bx * bx + by * by, c2 = cx * cx + cy * cy;
    return [a[0] + (b2 * cy - c2 * by) / d, a[1] + (bx * c2 - cx * b2) / d];
  };
  const a = circleCenter(outer[0], outer[4], outer[8]), b = circleCenter(outer[12], outer[16], outer[20]);
  let angle = Math.atan2(b[1] - a[1], b[0] - a[0]);
  if (angle > Math.PI / 2) angle -= Math.PI;
  if (angle < -Math.PI / 2) angle += Math.PI;
  return { center, angle };
}

export function refineSurfaces(gltf, binary, settings) {
  if (settings.version !== 1 || settings.units !== "millimetres") throw new Error("Unsupported surface refinement settings.");
  const chunks = [binary], refinements = [], shafts = [];
  let length = binary.length;
  const append = bytes => {
    const padded = Buffer.concat([bytes, Buffer.alloc((4 - bytes.length % 4) % 4)]);
    const index = gltf.bufferViews.push({ buffer: 0, byteOffset: length, byteLength: bytes.length }) - 1;
    chunks.push(padded); length += padded.length;
    return index;
  };
  const accessor = (array, size, normalized = false) => {
    const componentType = array instanceof Float32Array ? 5126 : array instanceof Uint32Array ? 5125 : 5123;
    const a = { bufferView: append(Buffer.from(array.buffer, array.byteOffset, array.byteLength)), componentType,
      count: array.length / size, type: { 1: "SCALAR", 2: "VEC2", 3: "VEC3" }[size] };
    if (normalized) a.normalized = true;
    if (size === 3) {
      a.min = [Infinity, Infinity, Infinity]; a.max = [-Infinity, -Infinity, -Infinity];
      for (let i = 0; i < array.length; i++) { const c = i % 3; a.min[c] = Math.min(a.min[c], array[i]); a.max[c] = Math.max(a.max[c], array[i]); }
    }
    return gltf.accessors.push(a) - 1;
  };
  gltf.samplers ??= [];
  const sampler = gltf.samplers.push({ magFilter: 9729, minFilter: 9987, wrapS: 10497, wrapT: 10497 }) - 1;
  gltf.images ??= []; gltf.textures ??= [];
  function texture(name, size, data) {
    const image = gltf.images.push({ name, bufferView: append(encodePng(size, data)), mimeType: "image/png" }) - 1;
    return gltf.textures.push({ name, source: image, sampler }) - 1;
  }
  const maps = {};
  for (const kind of ["case", "steel"]) {
    const pixels = grainMaps(kind);
    maps[kind] = Object.fromEntries(["normal", "roughness"].map(name => [name, texture(`${kind}_${name}`, pixels.size, pixels[name])]));
  }
  const textureInfo = (index, scale) => ({ index, extensions: { KHR_texture_transform: { scale: [scale, scale] } } });
  gltf.extensionsUsed = [...new Set([...(gltf.extensionsUsed ?? []), "KHR_texture_transform"])];
  const material = (name, color, metallicFactor, roughnessFactor, grain, strength, tile) => gltf.materials.push({ name,
    pbrMetallicRoughness: { baseColorFactor: [...new Color(color).toArray(), 1], metallicFactor, roughnessFactor,
      ...(grain ? { metallicRoughnessTexture: textureInfo(maps[grain].roughness, 400 / tile) } : {}) },
    ...(grain ? { normalTexture: { ...textureInfo(maps[grain].normal, 400 / tile), scale: strength } } : {}),
    doubleSided: false }) - 1;
  const capMat = material("PRETYPIST charcoal keycaps", settings.keycap.color, 0, settings.keycap.roughness);
  const caseMat = material("PRETYPIST textured black case", settings.case.color, 0, settings.case.roughness,
    "case", settings.case.normalStrength, settings.case.textureTileSize);
  const screwMat = material("PRETYPIST machined stainless steel", settings.steel.color, settings.steel.metalness,
    settings.steel.screwRoughness, "steel", 0.25, 2);
  const usbMat = material("PRETYPIST brushed USB shell", settings.steel.color, settings.steel.metalness,
    settings.steel.usbRoughness, "steel", 0.4, 2);
  function uv(positions) {
    const data = new Uint16Array(positions.length / 3 * 2);
    for (let i = 0; i < positions.length / 3; i++) {
      // Oblique planar mapping retains variation on the narrow side walls too.
      const [x, y, z] = positions.slice(i * 3, i * 3 + 3);
      const values = [(x + z * 0.37) / 400 + 0.5, (y + z * 0.53) / 400 + 0.5];
      if (values.some(v => v < 0 || v > 1)) throw new Error("Surface UV projection exceeds its reviewed model bounds.");
      data.set(values.map(v => Math.round(v * 65535)), i * 2);
    }
    return accessor(data, 2, true);
  }
  const sourcePositions = p => readArray(gltf, binary, p.attributes.POSITION);
  function smoothPrimitive(geometry, materialIndex) {
    const smooth = mergeVertices(toCreasedNormals(geometry, Math.PI / 3), 0.00001);
    const p = { attributes: { POSITION: accessor(smooth.attributes.position.array, 3),
      NORMAL: accessor(smooth.attributes.normal.array, 3), TEXCOORD_0: uv(smooth.attributes.position.array) },
      indices: accessor(smooth.index.array, 1), material: materialIndex };
    return p;
  }
  const originals = [...gltf.nodes];
  for (const node of originals) {
    if (node.mesh === undefined) continue;
    const name = node.extras?.sourceNode ?? node.name, mesh = gltf.meshes[node.mesh], p = mesh.primitives[0];
    if (/_SW\d+_Keycap$/.test(name)) {
      const pose = keyPose(sourcePositions(p));
      mesh.primitives = [smoothPrimitive(keycapGeometry(settings.keycap, pose.center, pose.angle), capMat)];
      refinements.push({ node: node.name, role: "keycap", center: pose.center, angle: pose.angle });
    } else if (/_Case_/.test(name)) {
      p.attributes.TEXCOORD_0 = uv(sourcePositions(p)); p.material = caseMat;
      refinements.push({ node: node.name, role: "case" });
    } else if (/_USB_C_shell$|_Cover_screw_\d+$|_Cover_standoff_\d+$/.test(name)) {
      const positions = sourcePositions(p), geometry = new BufferGeometry()
        .setAttribute("position", new BufferAttribute(positions, 3)).setIndex(new BufferAttribute(readArray(gltf, binary, p.indices), 1));
      if (/_Cover_standoff_/.test(name)) {
        const prefix = name[0], cover = originals.find(n => (n.extras?.sourceNode ?? n.name) === `${prefix}_Acrylic_cover_estimated_1.5mm`);
        const glass = gltf.accessors[gltf.meshes[cover.mesh].primitives[0].attributes.POSITION];
        const bounds = gltf.accessors[p.attributes.POSITION];
        for (let i = 2; i < positions.length; i += 3) positions[i] = bounds.min[2] + (positions[i] - bounds.min[2])
          * (glass.min[2] - bounds.min[2]) / (bounds.max[2] - bounds.min[2]);
      }
      mesh.primitives = [smoothPrimitive(geometry, name.includes("standoff") ? p.material : name.includes("USB") ? usbMat : screwMat)];
      refinements.push({ node: node.name, role: name.includes("standoff") ? "standoff" : "metal" });
    }
  }
  for (const head of originals.filter(n => /_Cover_screw_\d+$/.test(n.name))) {
    const original = head.extras.sourceNode, prefix = original[0], index = original.split("_").at(-1);
    // Read the original bounds (still in the preserved BIN/accessor list).
    const headRef = refinements.find(r => r.node === head.name);
    if (!headRef) throw new Error("Missing refined screw head.");
    const h = gltf.accessors[gltf.meshes[head.mesh].primitives[0].attributes.POSITION];
    const glassNode = originals.find(n => (n.extras?.sourceNode ?? n.name) === `${prefix}_Acrylic_cover_estimated_1.5mm`);
    const glass = gltf.accessors[gltf.meshes[glassNode.mesh].primitives[0].attributes.POSITION];
    const center = [(h.min[0] + h.max[0]) / 2, (h.min[1] + h.max[1]) / 2];
    const bottom = glass.min[2] - settings.fastener.engagement, top = h.min[2] + settings.fastener.headOverlap;
    const geometry = new CylinderGeometry(settings.fastener.shaftRadius, settings.fastener.shaftRadius, top - bottom, 32)
      .rotateX(Math.PI / 2).translate(center[0], center[1], (top + bottom) / 2);
    const mesh = gltf.meshes.push({ name: `${prefix}_Cover_screw_shaft_${index}`, primitives: [smoothPrimitive(geometry, screwMat)] }) - 1;
    const node = { name: `${prefix}_Cover_screw_shaft_${index}`, matrix: [...head.matrix], mesh,
      extras: { refinement: "shaft through existing 2.4 mm clearance hole", units: "millimetres" } };
    const headIndex = gltf.nodes.indexOf(head), parent = gltf.nodes.find(n => n.children?.includes(headIndex));
    parent.children.push(gltf.nodes.push(node) - 1);
    shafts.push({ node: node.name, center, bottom, top, radius: settings.fastener.shaftRadius,
      glassBottom: glass.min[2], glassTop: glass.max[2], headBottom: h.min[2] });
  }
  gltf.buffers[0].byteLength = length;
  return { binary: Buffer.concat(chunks), report: { version: 1, settings, refinements, shafts,
    originalBufferBytes: binary.length, addedBufferBytes: length - binary.length,
    notes: ["Original BIN retained as a prefix; selected meshes/materials explicitly replaced.",
      "Case topology, wiring, USB dimensions, glass and all physical pivots retained.",
      "Micrograin is baked, deterministic and embedded; no custom runtime shader required."] } };
}
