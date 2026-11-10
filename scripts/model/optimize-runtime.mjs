/** Keep the full authoring/refinement path, derive a compact standard glTF at build time. */
import fs from "node:fs";
import { createHash } from "node:crypto";
import { BufferAttribute, BufferGeometry } from "three";
import { MeshoptSimplifier } from "three/addons/libs/meshopt_simplifier.module.js";
import { parseGlb } from "../audit-model.mjs";
import { readArray } from "./refine-surfaces.mjs";

await MeshoptSimplifier.ready;
export const optimizationSettings = Object.freeze(JSON.parse(fs.readFileSync(
  new URL("../../resources/layout-sources/modified-engram/runtime-optimization.json", import.meta.url), "utf8")));
function meshRole(node) {
  const name = node.extras?.sourceNode ?? node.name;
  if (/^case_top_/.test(node.name)) return "caseTop";
  if (/^case_bottom_/.test(node.name)) return "caseBottom";
  if (/_SW\d+_Keycap$/.test(name)) return "keycap";
  if (/_Acrylic_edge_/.test(name)) return "glassRim";
  if (/_Acrylic_cover_/.test(name)) return "glass";
  if (/wire_photo_routing/.test(name)) return "wiring";
  if (/screw|standoff|USB/.test(name)) return "hardware";
  if (/_PCB_1.6mm$/.test(name)) return "pcb";
  return "detail";
}
const widths = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4 };
const types = new Map([[Float32Array, 5126], [Uint32Array, 5125], [Uint16Array, 5123], [Uint8Array, 5121]]);
const hash = data => createHash("sha256").update(data).digest("hex");

function degenerateVertices(indices, positions) {
  const result = new Set();
  for (let i = 0; i < indices.length; i += 3) {
    const a = indices[i] * 3, b = indices[i + 1] * 3, c = indices[i + 2] * 3;
    const ab = [0, 1, 2].map(j => positions[b + j] - positions[a + j]);
    const ac = [0, 1, 2].map(j => positions[c + j] - positions[a + j]);
    const bc = [0, 1, 2].map(j => positions[c + j] - positions[b + j]);
    const area = Math.hypot(ab[1] * ac[2] - ab[2] * ac[1], ab[2] * ac[0] - ab[0] * ac[2], ab[0] * ac[1] - ab[1] * ac[0]);
    const edge = Math.max(...[ab, ac, bc].map(e => e.reduce((sum, x) => sum + x*x, 0)));
    if (area <= edge * 1e-12) for (let j = 0; j < 3; j++) result.add(indices[i + j]);
  }
  return result;
}

export function inspectTopology(indices, vertexCount) {
  const edges = new Map(), vertices = new Set(), problems = new Set();
  for (let i = 0; i < indices.length; i += 3) for (let j = 0; j < 3; j++) {
    const a = indices[i+j], b = indices[i+(j+1)%3]; vertices.add(a);
    const key = Math.min(a,b) * vertexCount + Math.max(a,b);
    edges.set(key, (edges.get(key) ?? 0) + 1);
  }
  let boundaryEdges = 0, nonManifoldEdges = 0;
  for (const [key, count] of edges) if (count !== 2) {
    if (count === 1) boundaryEdges++; else nonManifoldEdges++;
    problems.add(Math.floor(key / vertexCount)); problems.add(key % vertexCount);
  }
  return { stats: { euler: vertices.size - edges.size + indices.length / 3, boundaryEdges, nonManifoldEdges }, problems };
}

function simplifyRimTube(positions, indices, bounds, tolerance) {
  // Authored rim pieces are 80 rings of a 12-sided tube. Keep complete
  // cross-sections and cap fans; remove redundant rings along the short arc.
  const sides = 12, rings = positions.length / (3 * sides);
  if (rings !== 80) throw new Error("Unreviewed glass-rim tube structure.");
  const keep = new Set([0, rings - 1]);
  for (let c = 0; c < 3; c++) for (const bound of ["min", "max"]) {
    const vertex = Array.from({ length: positions.length / 3 }, (_, i) => i).find(i => positions[i*3+c] === bounds[bound][c]);
    keep.add(Math.floor(vertex / sides));
  }
  function errorAt(row, start, end) {
    let error = 0;
    for (let c = 0; c < sides; c++) {
      const p = (row*sides+c)*3, a = (start*sides+c)*3, b = (end*sides+c)*3;
      const delta = [0,1,2].map(j => positions[b+j] - positions[a+j]);
      const length = delta.reduce((sum,x) => sum+x*x,0);
      const t = Math.max(0,Math.min(1,delta.reduce((sum,x,j) => sum+x*(positions[p+j]-positions[a+j]),0)/length));
      error = Math.max(error, Math.hypot(...delta.map((x,j) => positions[p+j]-positions[a+j]-t*x)));
    }
    return error;
  }
  function split(start,end) {
    let worst = 0, row;
    for (let i = start+1; i < end; i++) { const error = errorAt(i,start,end); if (error > worst) {worst=error;row=i;} }
    if (worst > tolerance) {keep.add(row);split(start,row);split(row,end);}
  }
  const initial = [...keep].sort((a,b) => a-b);
  for (let i = 1; i < initial.length; i++) split(initial[i-1],initial[i]);
  const selected = [...keep].sort((a,b) => a-b), pattern = [], caps = [], out = [];
  for (let i = 0; i < indices.length; i += 3) {
    const face = Array.from(indices.subarray(i,i+3));
    if (face.every(v => Math.floor(v/sides) === 0 || Math.floor(v/sides) === rings-1)
      && face.every(v => Math.floor(v/sides) === Math.floor(face[0]/sides))) caps.push(...face);
    if (face.every(v => v === 0 || v === 1 || v === sides || v === sides+1)
      && new Set(face.map(v => Math.floor(v/sides))).size === 2) pattern.push(...face);
  }
  if (pattern.length !== 6 || caps.length !== (sides-2)*3*2) throw new Error("Unreviewed glass-rim index pattern.");
  for (let i = 1; i < selected.length; i++) for (let c = 0; c < sides; c++) {
    for (const v of pattern) out.push(selected[i-1 + Math.floor(v/sides)]*sides + (v%sides+c)%sides);
  }
  out.push(...caps);
  let error = 0;
  for (let i = 1; i < selected.length; i++) for (let row = selected[i-1]+1; row < selected[i]; row++) {
    error = Math.max(error,errorAt(row,selected[i-1],selected[i]));
  }
  return [new Uint32Array(out), error];
}

function bakeCaseNormals(positions, sourceIndices, reduced, uv, creaseDegrees) {
  const adjacency = Array.from({length: positions.length/3}, () => []), faceNormals = new Float32Array(sourceIndices.length);
  function faceNormal(a,b,c) {
    const ab = [0,1,2].map(j => positions[b*3+j]-positions[a*3+j]);
    const ac = [0,1,2].map(j => positions[c*3+j]-positions[a*3+j]);
    const n = [ab[1]*ac[2]-ab[2]*ac[1],ab[2]*ac[0]-ab[0]*ac[2],ab[0]*ac[1]-ab[1]*ac[0]], length = Math.hypot(...n);
    return n.map(v => v/length);
  }
  for (let i = 0; i < sourceIndices.length; i += 3) {
    faceNormals.set(faceNormal(...sourceIndices.subarray(i,i+3)),i);
    for (let c = 0; c < 3; c++) adjacency[sourceIndices[i+c]].push(i);
  }
  const outPositions = [], outUv = [], outNormals = [], outIndices = [], vertices = new Map(), creaseDot = Math.cos(creaseDegrees*Math.PI/180);
  for (let i = 0; i < reduced.length; i += 3) {
    const face = faceNormal(...reduced.subarray(i,i+3));
    // A large flat face must remain flat at its boundary with a bevel.
    // Otherwise averaging incident curved normals tilts the entire panel.
    const planar = Array.from(reduced.subarray(i,i+3)).every(index => adjacency[index].some(offset =>
      faceNormals[offset]*face[0]+faceNormals[offset+1]*face[1]+faceNormals[offset+2]*face[2] > 1-1e-7));
    for (let c = 0; c < 3; c++) {
      const index = reduced[i+c], sum = [0,0,0];
      for (const offset of adjacency[index]) {
        const n = faceNormals.subarray(offset,offset+3);
        if (n[0]*face[0]+n[1]*face[1]+n[2]*face[2] > creaseDot) for (let j = 0; j < 3; j++) sum[j] += n[j];
      }
      const length = Math.hypot(...sum), normal = length ? new Float32Array(sum.map(v => v/length)) : new Float32Array(face);
      const shadingNormal = planar ? new Float32Array(face) : normal;
      const key = `${index}:${Array.from(shadingNormal).join(",")}`;
      if (!vertices.has(key)) {
        vertices.set(key,vertices.size);outPositions.push(...positions.subarray(index*3,index*3+3));
        outUv.push(...uv.subarray(index*2,index*2+2));outNormals.push(...shadingNormal);
      }
      outIndices.push(vertices.get(key));
    }
  }
  return {positions: new Float32Array(outPositions), uv: new uv.constructor(outUv), normals: new Float32Array(outNormals), indices: new Uint32Array(outIndices)};
}

function encode(gltf, binary) {
  const raw = Buffer.from(JSON.stringify(gltf));
  const json = Buffer.concat([raw, Buffer.alloc((4 - raw.length % 4) % 4, 32)]);
  const out = Buffer.alloc(28 + json.length + binary.length);
  [0x46546c67, 2, out.length, json.length, 0x4e4f534a].forEach((x, i) => out.writeUInt32LE(x, i * 4));
  json.copy(out, 20); out.writeUInt32LE(binary.length, 20 + json.length);
  out.writeUInt32LE(0x004e4942, 24 + json.length); binary.copy(out, 28 + json.length);
  return out;
}

export function optimizeRuntime(input, settings = optimizationSettings) {
  const { gltf, binary } = parseGlb(input);
  if (gltf.skins?.length || gltf.animations?.length || gltf.accessors.some(a => a.sparse)
    || gltf.meshes.some(m => m.primitives.some(p => p.targets || p.extensions || (p.mode ?? 4) !== 4))) {
    throw new Error("Runtime optimizer requires the reviewed static, indexed triangle model.");
  }
  const accessors = [], views = [], chunks = [], accessorsByHash = new Map(), viewsByHash = new Map(), originals = new Map();
  const records = []; let length = 0;
  function append(data, target) {
    const bytes = Buffer.from(data.buffer, data.byteOffset, data.byteLength);
    const key = `${target ?? 0}:${hash(bytes)}`;
    if (viewsByHash.has(key)) return viewsByHash.get(key);
    const padding = Buffer.alloc((4 - length % 4) % 4); chunks.push(padding); length += padding.length;
    const index = views.push({ buffer: 0, byteOffset: length, byteLength: bytes.length, ...(target ? { target } : {}) }) - 1;
    chunks.push(bytes); length += bytes.length; viewsByHash.set(key, index); return index;
  }
  function attribute(data, template, target = 34962) {
    const a = { ...template, bufferView: append(data, target), count: data.length / widths[template.type], componentType: types.get(data.constructor) };
    delete a.byteOffset; delete a.sparse;
    if (a.min || a.max) {
      const width = widths[a.type], min = Array(width).fill(Infinity), max = Array(width).fill(-Infinity);
      for (let i = 0; i < data.length; i++) { const c = i % width; min[c] = Math.min(min[c], data[i]); max[c] = Math.max(max[c], data[i]); }
      a.min = min; a.max = max;
    }
    const key = JSON.stringify(a);
    if (accessorsByHash.has(key)) return accessorsByHash.get(key);
    const index = accessors.push(a) - 1; accessorsByHash.set(key, index); return index;
  }
  function original(index, target) {
    const key = `${index}:${target}`;
    if (originals.has(key)) return originals.get(key);
    let data = readArray(gltf, binary, index);
    if (target === 34963 && data instanceof Uint32Array && data.every(i => i < 65536)) data = new Uint16Array(data);
    const out = attribute(data, gltf.accessors[index], target); originals.set(key, out); return out;
  }
  const owners = new Map(gltf.nodes.filter(n => n.mesh !== undefined).map(n => [n.mesh, n]));
  gltf.meshes.forEach((mesh, meshIndex) => {
    const owner = owners.get(meshIndex), role = meshRole(owner);
    const policy = settings.policies[role];
    for (const p of mesh.primitives) {
      if (!policy || gltf.accessors[p.indices].count <= 96) {
        for (const [name, index] of Object.entries(p.attributes)) p.attributes[name] = original(index, 34962);
        p.indices = original(p.indices, 34963); continue;
      }
      const positions = readArray(gltf, binary, p.attributes.POSITION), indices = readArray(gltf, binary, p.indices);
      const geometry = new BufferGeometry().setAttribute("position", new BufferAttribute(positions, 3)).setIndex(new BufferAttribute(indices, 1));
      geometry.computeVertexNormals();
      const normals = p.attributes.NORMAL === undefined ? geometry.attributes.normal.array : readArray(gltf, binary, p.attributes.NORMAL);
      const uv = p.attributes.TEXCOORD_0 === undefined ? null : readArray(gltf, binary, p.attributes.TEXCOORD_0);
      const stride = uv ? 5 : 3, attributes = new Float32Array(positions.length / 3 * stride), lock = new Uint8Array(positions.length / 3);
      const bounds = gltf.accessors[p.attributes.POSITION], lockedExtrema = new Set();
      for (let i = 0; i < lock.length; i++) {
        attributes.set(normals.subarray(i * 3, i * 3 + 3), i * stride);
        if (uv) {
          const divisor = gltf.accessors[p.attributes.TEXCOORD_0].normalized ? 65535 : 1;
          attributes[i * stride + 3] = uv[i * 2] / divisor; attributes[i * stride + 4] = uv[i * 2 + 1] / divisor;
        }
        // One retained vertex for each bound keeps fitting exact without
        // locking the entire flat top/bottom of a CAD part.
        for (let c = 0; c < 3; c++) for (const bound of ["min", "max"]) {
          const key = `${c}:${bound}`;
          if (positions[i * 3 + c] === bounds[bound][c] && !lockedExtrema.has(key)) {
            lock[i] = 1; lockedExtrema.add(key);
          }
        }
      }
      if (role === "keycap") {
        const center = [0,1].map(c => (bounds.min[c]+bounds.max[c])/2);
        for (let i = 0; i < lock.length; i++) if (Math.hypot(positions[i*3]-center[0],positions[i*3+1]-center[1]) < 0.00002
          && positions[i*3+2] > bounds.min[2]+2) lock[i] = 1; // Retain the exact dish centre.
      }
      const weights = [policy.normalWeight, policy.normalWeight, policy.normalWeight, ...(uv ? [1, 1] : [])];
      const positionRemap = MeshoptSimplifier.generatePositionRemap(positions, 3);
      const surfaceTopology = index => inspectTopology(new Uint32Array(Array.from(index, v => positionRemap[v])), positions.length / 3);
      const sourceTopology = surfaceTopology(indices).stats;
      if (sourceTopology.boundaryEdges || sourceTopology.nonManifoldEdges) throw new Error(`Expected a closed, manifold source: ${owner.name}.`);
      let reduced, error, topology, repairPasses = 0;
      // Collinear CAD vertices can leave zero-area faces after edge collapse.
      // Preserve their original neighbourhood and retry. Also protect any
      // pinched edges, so the closed case and hole topology remain intact.
      for (;;) {
        [reduced, error] = policy.method === "tube-rings"
          ? simplifyRimTube(positions, indices, bounds, policy.errorMillimetres)
          : MeshoptSimplifier.simplifyWithAttributes(indices, positions, 3, attributes, stride,
            weights, lock, 0, policy.errorMillimetres, ["LockBorder", "ErrorAbsolute"]);
        const bad = degenerateVertices(reduced, positions);
        topology = surfaceTopology(reduced);
        for (let vertex = 0; vertex < positionRemap.length; vertex++) if (topology.problems.has(positionRemap[vertex])) bad.add(vertex);
        if (!bad.size) {
          if (topology.stats.euler !== sourceTopology.euler) throw new Error("Simplification changed the hole topology.");
          break;
        }
        if (++repairPasses > 8) throw new Error("Cannot simplify without changing the closed manifold surface.");
        for (let i = 0; i < indices.length; i += 3) if ([0, 1, 2].some(j => bad.has(indices[i+j]))) {
          for (let j = 0; j < 3; j++) lock[indices[i+j]] = 1;
        }
      }
      const baked = role.startsWith("case") && settings.caseNormalsCreaseDegrees
        ? bakeCaseNormals(positions, indices, reduced, uv, settings.caseNormalsCreaseDegrees) : null;
      const exportData = { POSITION: baked?.positions ?? positions };
      for (const [name, index] of Object.entries(p.attributes)) if (name !== "POSITION") exportData[name] = readArray(gltf,binary,index);
      if (baked) {exportData.TEXCOORD_0 = baked.uv; exportData.NORMAL = baked.normals; reduced = baked.indices;}
      const [remap, count] = MeshoptSimplifier.compactMesh(reduced);
      function compact(data, width) {
        const out = new data.constructor(count * width);
        for (let i = 0; i < remap.length; i++) if (remap[i] < count) out.set(data.subarray(i * width, (i + 1) * width), remap[i] * width);
        return out;
      }
      for (const [name, data] of Object.entries(exportData)) {
        const template = p.attributes[name] === undefined ? {type:"VEC3",componentType:5126} : gltf.accessors[p.attributes[name]];
        p.attributes[name] = attribute(compact(data, widths[template.type]), template);
      }
      p.indices = attribute(count < 65536 ? new Uint16Array(reduced) : reduced, { type: "SCALAR" }, 34963);
      records.push({ node: owner.name, role, errorTargetMillimetres: policy.errorMillimetres, beforeTriangles: indices.length / 3, afterTriangles: reduced.length / 3,
        beforeVertices: positions.length / 3, afterVertices: count, protectedNeighbourhoodPasses: repairPasses, topology: topology.stats,
        errorMetric: policy.method === "tube-rings" ? "sampled ring-column chord distance" : "meshoptimizer", reportedErrorMillimetres: error });
      geometry.dispose();
    }
  });
  // Remove unused material/image resources, including replaced LCD photographs.
  const materials = new Map();
  for (const mesh of gltf.meshes) for (const p of mesh.primitives) if (p.material !== undefined) {
    if (!materials.has(p.material)) materials.set(p.material, materials.size);
    p.material = materials.get(p.material);
  }
  gltf.materials = [...materials.keys()].map(i => gltf.materials[i]);
  const textures = new Map();
  function visitTextures(value) {
    for (const [key, child] of Object.entries(value)) if (child && typeof child === "object") {
      if (key.endsWith("Texture") && child.index !== undefined) {
        if (!textures.has(child.index)) textures.set(child.index, textures.size);
        child.index = textures.get(child.index);
      } else visitTextures(child);
    }
  }
  gltf.materials.forEach(visitTextures);
  gltf.textures = [...textures.keys()].map(i => gltf.textures[i]);
  const images = new Map(), samplers = new Map();
  for (const texture of gltf.textures) {
    if (!images.has(texture.source)) images.set(texture.source, images.size);
    texture.source = images.get(texture.source);
    if (texture.sampler !== undefined) {
      if (!samplers.has(texture.sampler)) samplers.set(texture.sampler, samplers.size);
      texture.sampler = samplers.get(texture.sampler);
    }
  }
  gltf.images = [...images.keys()].map(i => {
    const image = { ...gltf.images[i] }, view = gltf.bufferViews[image.bufferView];
    image.bufferView = append(binary.subarray(view.byteOffset ?? 0, (view.byteOffset ?? 0) + view.byteLength)); return image;
  });
  gltf.samplers = [...samplers.keys()].map(i => gltf.samplers[i]);
  chunks.push(Buffer.alloc((4 - length % 4) % 4)); length += (4 - length % 4) % 4;
  gltf.accessors = accessors; gltf.bufferViews = views; gltf.buffers = [{ byteLength: length }];
  gltf.asset.generator += " + runtime optimizer v2";
  gltf.asset.extras.runtimeOptimization = settings;
  gltf.asset.extras.geometryPolicy = "Pinned original retained in authored/model.glb; full refinements regenerated before selective simplification and lossless buffer compaction.";
  const output = encode(gltf, Buffer.concat(chunks));
  return { output, report: { settings, beforeBytes: input.length, afterBytes: output.length, cases: records.filter(r => r.role.startsWith("case")), meshes: records,
    effectiveTriangles: gltf.nodes.filter(n => n.mesh !== undefined).reduce((total,n) => total+gltf.meshes[n.mesh].primitives.reduce((sum,p) => sum+gltf.accessors[p.indices].count/3,0),0),
    note: "Selected surfaces simplified according to per-part error targets. Positions and UVs retain exact source values; case normals are baked from the detailed source with sharp creases and flat panels preserved. All transforms, hardware/glass aperture geometry and PBR textures are unchanged. Error is meshoptimizer's metric, not a measured Hausdorff bound." } };
}
