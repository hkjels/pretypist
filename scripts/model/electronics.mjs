/**
 * Original, procedural electronics geometry for PRETYPIST's Chocofi model.
 *
 * Coordinate contract:
 * - metres
 * - Y-up
 * - X is across a component and Z is along the authored left keyboard half
 * - primitive origins are centred in X/Z with their bottom face at Y=0,
 *   unless a function explicitly documents a pin/pad origin
 *
 * Provenance (dimensions only; no third-party CAD data is embedded here):
 * - The nice!nano board envelope follows the Pro Micro-compatible 0.7 x 1.3
 *   inch outline already used by PRETYPIST. Nice Keyboards documents the v2's
 *   mid-mount USB-C and 3.2 mm total board/connector thickness:
 *   https://nicekeyboards.com/docs/nice-nano/
 * - nice!view proportions, headers, and visible component placement are a
 *   visual model based on the user-supplied keyboard photograph and the
 *   existing PRETYPIST 14 x 36 mm display proxy. It is not manufacturing CAD.
 * - Chocofi component anchors come from the pinned upstream PCB source at
 *   resources/layout-sources/modified-engram/upstream/chocofi-case-v1/.
 * - JST-XH-style dimensions follow JST's published XH catalogue drawings for
 *   S2B-XH-A and XHP-2. The geometry below is independently constructed and
 *   deliberately simplified; no JST CAD file is copied or redistributed:
 *   https://www.jst-mfg.com/product/pdf/eng/eXH.pdf
 * - The 2.54 mm Chocofi J3 footprint is generic, while genuine XH is 2.50 mm.
 *   The XH identification is therefore a well-supported visual inference, not
 *   a claim that the photographed part is a particular genuine JST SKU.
 */

export const MM = 0.001;

function metres(valueInMillimetres) {
  return valueInMillimetres * MM;
}

function deepFreeze(value) {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const child of Object.values(value)) deepFreeze(child);
  }
  return value;
}

/**
 * Source-PCB anchors remain in millimetres so the consuming generator can run
 * them through the same STL/PCB centring transform as key switches.
 */
export const CHOCOFI_V1_ELECTRONICS_ANCHORS_MM = deepFreeze({
  niceNano: [178.51, 72.0784],
  niceViewHeader: [173.93, 90.6084],
  batteryJ3Pad1: [170.053, 92.583],
  batteryJ3Pad2: [170.053, 90.043],
  powerSwitch: [178.181, 55.88],
  resetSwitch: [182.753, 93.853],
  innerBoardEdgeAtJ3: 188.214,
});

export const NICE_NANO_V2_DIMENSIONS = deepFreeze({
  board: {
    width: metres(17.78),
    depth: metres(33.02),
    thickness: metres(0.8),
    cornerRadius: metres(0.65),
  },
  usbC: {
    width: metres(8.9),
    depth: metres(6.2),
    height: metres(3.2),
    wall: metres(0.35),
    tongueWidth: metres(5.4),
    tongueDepth: metres(3.9),
    tongueThickness: metres(0.42),
  },
  radioModule: {
    width: metres(10.2),
    depth: metres(15.8),
    height: metres(1.05),
  },
  sidePadPitch: metres(2.54),
  sidePadCount: 12,
});

export const NICE_VIEW_DIMENSIONS = deepFreeze({
  board: {
    width: metres(14),
    depth: metres(36),
    thickness: metres(0.65),
    cornerRadius: metres(0.65),
  },
  activeSurface: {
    width: metres(11.3),
    depth: metres(26.5),
    thickness: metres(0.32),
    cornerRadius: metres(0.75),
  },
  resolution: [160, 68],
  headerPitch: metres(2.54),
  headerCount: 5,
});

/**
 * Offsets use J3 pad 1 (GND) as [0, 0, 0]. The pad row runs toward -Z and
 * the side-entry connector points toward +X, the central cutout/board edge.
 */
export const JST_XH_STYLE_PLACEMENT = deepFreeze({
  padPitch: metres(2.54),
  pad2Offset: [0, 0, metres(-2.54)],
  headerBody: {
    // S2B-XH-A plastic body: v=2.2..9.2 and u=-2.45..4.95 mm.
    offsetFromPad1: [metres(5.7), 0, metres(-1.25)],
    nominalDimensions: [metres(7.0), metres(5.75), metres(7.4)],
    // The procedural face lip and top keying rib slightly exceed the nominal
    // catalogue body envelope so they remain legible at scene scale.
    overallDimensions: [metres(7.0), metres(6.1), metres(7.6)],
  },
  matedPlug: {
    // The plug overlaps the open end of the header by about 2.3 mm.
    offsetFromPad1: [metres(9.75), metres(0.15), metres(-1.25)],
    dimensions: [metres(5.7), metres(7.5), metres(7.3)],
  },
  wireExit: {
    red: [metres(12.75), metres(3.75), metres(-2.5)],
    black: [metres(12.75), metres(3.75), 0],
  },
  innerBoardEdgeOffsetFromPad1: metres(18.161),
});

/** Recommended relative and current-scene offsets for straightforward use. */
export const ELECTRONICS_PLACEMENT = deepFreeze({
  niceNano: {
    origin: "centre of nice!nano PCB bottom face",
    boardOffset: [0, 0, 0],
    usbShellOffset: [0, metres(-1.2), metres(-17.15)],
    usbTongueOffset: [0, metres(0.15), metres(-18.25)],
    radioModuleOffset: [0, metres(0.8), metres(2.25)],
  },
  niceView: {
    origin: "centre of nice!view PCB bottom face",
    boardOffset: [0, 0, 0],
    activeSurfaceOffset: [0, metres(0.65), metres(-0.15)],
    relativeToNiceNano: [0, metres(2.7), 0],
  },
  chocofiSceneY: {
    niceNanoBoardBottom: metres(3.85),
    niceViewBoardBottom: metres(6.55),
    jstHeaderBottom: metres(4.95),
  },
});

export const ELECTRONICS_MATERIAL_ROLES = deepFreeze([
  "pcb",
  "screen",
  "screen-bezel",
  "metal",
  "metal-dark",
  "gold",
  "component",
  "component-marking",
  "passive-dark",
  "passive-ceramic",
  "led-blue",
  "led-amber",
  "connector-ivory",
  "connector-shadow",
  "flex-amber",
]);

function emptyGeometry() {
  return { positions: [], normals: [], indices: [] };
}

function subtract(left, right) {
  return [left[0] - right[0], left[1] - right[1], left[2] - right[2]];
}

function cross(left, right) {
  return [
    left[1] * right[2] - left[2] * right[1],
    left[2] * right[0] - left[0] * right[2],
    left[0] * right[1] - left[1] * right[0],
  ];
}

function normalize(vector) {
  const length = Math.hypot(...vector);
  if (length < 1e-14) throw new Error("Cannot normalize a zero-length vector");
  return vector.map((component) => component / length);
}

function addQuad(geometry, a, b, c, d) {
  const start = geometry.positions.length / 3;
  const normal = normalize(cross(subtract(b, a), subtract(c, a)));
  for (const vertex of [a, b, c, d]) {
    geometry.positions.push(...vertex);
    geometry.normals.push(...normal);
  }
  geometry.indices.push(start, start + 1, start + 2, start, start + 2, start + 3);
}

function addTriangle(geometry, a, b, c) {
  const start = geometry.positions.length / 3;
  const normal = normalize(cross(subtract(b, a), subtract(c, a)));
  for (const vertex of [a, b, c]) {
    geometry.positions.push(...vertex);
    geometry.normals.push(...normal);
  }
  geometry.indices.push(start, start + 1, start + 2);
}

function roundedRectanglePoints(halfX, halfZ, radius, segmentsPerCorner, y) {
  if (radius <= 0 || radius > Math.min(halfX, halfZ)) {
    throw new Error("Rounded rectangle radius must fit inside the extents");
  }
  const corners = [
    { centre: [halfX - radius, -halfZ + radius], start: 0 },
    { centre: [-halfX + radius, -halfZ + radius], start: -Math.PI / 2 },
    { centre: [-halfX + radius, halfZ - radius], start: -Math.PI },
    { centre: [halfX - radius, halfZ - radius], start: (-3 * Math.PI) / 2 },
  ];
  return corners.flatMap(({ centre, start }) =>
    Array.from({ length: segmentsPerCorner }, (_, segment) => {
      const angle = start - (segment * Math.PI) / (2 * segmentsPerCorner);
      return [
        centre[0] + Math.cos(angle) * radius,
        y,
        centre[1] + Math.sin(angle) * radius,
      ];
    }),
  );
}

/** Create a rounded slab centred in X/Z, with its base at Y=0. */
export function createRoundedSlabGeometry(
  width,
  depth,
  height,
  radius = Math.min(width, depth) * 0.08,
  segmentsPerCorner = 5,
) {
  if (![width, depth, height].every((value) => Number.isFinite(value) && value > 0)) {
    throw new Error("Rounded slab dimensions must be finite and positive");
  }
  const geometry = emptyGeometry();
  const bottom = roundedRectanglePoints(width / 2, depth / 2, radius, segmentsPerCorner, 0);
  const top = roundedRectanglePoints(width / 2, depth / 2, radius, segmentsPerCorner, height);
  for (let index = 0; index < bottom.length; index += 1) {
    const next = (index + 1) % bottom.length;
    addQuad(geometry, bottom[index], bottom[next], top[next], top[index]);
  }
  const topCentre = [0, height, 0];
  const bottomCentre = [0, 0, 0];
  for (let index = 0; index < bottom.length; index += 1) {
    const next = (index + 1) % bottom.length;
    addTriangle(geometry, top[index], top[next], topCentre);
    addTriangle(geometry, bottomCentre, bottom[next], bottom[index]);
  }
  return geometry;
}

/** Create a hard-edged box centred in X/Z, with its base at Y=0. */
export function createBoxGeometry(width, depth, height) {
  if (![width, depth, height].every((value) => Number.isFinite(value) && value > 0)) {
    throw new Error("Box dimensions must be finite and positive");
  }
  const x = width / 2;
  const z = depth / 2;
  const y = height;
  const geometry = emptyGeometry();
  const bottom = {
    nw: [-x, 0, -z], ne: [x, 0, -z], se: [x, 0, z], sw: [-x, 0, z],
  };
  const top = {
    nw: [-x, y, -z], ne: [x, y, -z], se: [x, y, z], sw: [-x, y, z],
  };
  addQuad(geometry, top.nw, top.sw, top.se, top.ne);
  addQuad(geometry, bottom.sw, bottom.nw, bottom.ne, bottom.se);
  addQuad(geometry, bottom.sw, bottom.se, top.se, top.sw);
  addQuad(geometry, bottom.ne, bottom.nw, top.nw, top.ne);
  addQuad(geometry, bottom.se, bottom.ne, top.ne, top.se);
  addQuad(geometry, bottom.nw, bottom.sw, top.sw, top.nw);
  return geometry;
}

/** Create an open annular pad/via centred on the Y axis, base at Y=0. */
export function createAnnularPadGeometry(
  outerRadius = metres(0.78),
  innerRadius = metres(0.43),
  height = metres(0.07),
  segments = 18,
) {
  if (innerRadius <= 0 || outerRadius <= innerRadius || height <= 0 || segments < 6) {
    throw new Error("Invalid annular pad dimensions");
  }
  const geometry = emptyGeometry();
  for (let segment = 0; segment < segments; segment += 1) {
    const next = (segment + 1) % segments;
    const a = (segment / segments) * Math.PI * 2;
    const b = (next / segments) * Math.PI * 2;
    const outerA0 = [Math.cos(a) * outerRadius, 0, Math.sin(a) * outerRadius];
    const outerB0 = [Math.cos(b) * outerRadius, 0, Math.sin(b) * outerRadius];
    const outerA1 = [outerA0[0], height, outerA0[2]];
    const outerB1 = [outerB0[0], height, outerB0[2]];
    const innerA0 = [Math.cos(a) * innerRadius, 0, Math.sin(a) * innerRadius];
    const innerB0 = [Math.cos(b) * innerRadius, 0, Math.sin(b) * innerRadius];
    const innerA1 = [innerA0[0], height, innerA0[2]];
    const innerB1 = [innerB0[0], height, innerB0[2]];
    addQuad(geometry, outerA1, innerA1, innerB1, outerB1);
    addQuad(geometry, outerB0, innerB0, innerA0, outerA0);
    addQuad(geometry, outerA0, outerA1, outerB1, outerB0);
    addQuad(geometry, innerB0, innerB1, innerA1, innerA0);
  }
  return geometry;
}

export function translateGeometry(geometry, [x, y, z]) {
  assertGeometry(geometry, "geometry to translate");
  const positions = geometry.positions.slice();
  for (let offset = 0; offset < positions.length; offset += 3) {
    positions[offset] += x;
    positions[offset + 1] += y;
    positions[offset + 2] += z;
  }
  return {
    positions,
    normals: geometry.normals.slice(),
    indices: geometry.indices.slice(),
  };
}

export function mergeGeometries(...geometries) {
  const merged = emptyGeometry();
  for (const [geometryIndex, geometry] of geometries.entries()) {
    assertGeometry(geometry, `geometry ${geometryIndex}`);
    const vertexOffset = merged.positions.length / 3;
    merged.positions.push(...geometry.positions);
    merged.normals.push(...geometry.normals);
    merged.indices.push(...geometry.indices.map((index) => index + vertexOffset));
  }
  return merged;
}

export function geometryBounds(geometry) {
  assertGeometry(geometry);
  const minimum = [Infinity, Infinity, Infinity];
  const maximum = [-Infinity, -Infinity, -Infinity];
  for (let offset = 0; offset < geometry.positions.length; offset += 3) {
    for (let axis = 0; axis < 3; axis += 1) {
      minimum[axis] = Math.min(minimum[axis], geometry.positions[offset + axis]);
      maximum[axis] = Math.max(maximum[axis], geometry.positions[offset + axis]);
    }
  }
  return {
    minimum,
    maximum,
    dimensions: maximum.map((value, axis) => value - minimum[axis]),
  };
}

export function assertGeometry(geometry, name = "geometry") {
  if (!geometry || !Array.isArray(geometry.positions) ||
      !Array.isArray(geometry.normals) || !Array.isArray(geometry.indices)) {
    throw new Error(`${name} must contain positions, normals, and indices arrays`);
  }
  if (geometry.positions.length === 0 || geometry.positions.length % 3 !== 0) {
    throw new Error(`${name} has an invalid position buffer`);
  }
  if (geometry.normals.length !== geometry.positions.length) {
    throw new Error(`${name} has a mismatched normal buffer`);
  }
  if (geometry.indices.length === 0 || geometry.indices.length % 3 !== 0) {
    throw new Error(`${name} has an invalid triangle index buffer`);
  }
  if (![...geometry.positions, ...geometry.normals].every(Number.isFinite)) {
    throw new Error(`${name} contains non-finite data`);
  }
  const vertexCount = geometry.positions.length / 3;
  if (geometry.indices.some((index) => !Number.isInteger(index) || index < 0 || index >= vertexCount)) {
    throw new Error(`${name} has an out-of-range index`);
  }
  for (let offset = 0; offset < geometry.indices.length; offset += 3) {
    const vertices = geometry.indices.slice(offset, offset + 3).map((index) =>
      geometry.positions.slice(index * 3, index * 3 + 3));
    if (Math.hypot(...cross(subtract(vertices[1], vertices[0]), subtract(vertices[2], vertices[0]))) < 1e-14) {
      throw new Error(`${name} has a degenerate triangle at ${offset / 3}`);
    }
  }
  return geometry;
}

export function createNiceNanoBoardGeometry() {
  const { width, depth, thickness, cornerRadius } = NICE_NANO_V2_DIMENSIONS.board;
  return createRoundedSlabGeometry(width, depth, thickness, cornerRadius, 7);
}

/**
 * A hollow USB-C receptacle shell. The shell mouth points toward -Z. Small
 * overlaps between procedural plates are intentional and invisible after the
 * pieces share one material.
 */
export function createUsbCReceptacleGeometry() {
  const { width, depth, height, wall } = NICE_NANO_V2_DIMENSIONS.usbC;
  const middleHeight = height - wall * 2;
  const pieces = [
    translateGeometry(createRoundedSlabGeometry(width, depth, wall, wall * 0.65, 4), [0, height - wall, 0]),
    createRoundedSlabGeometry(width, depth, wall, wall * 0.65, 4),
    translateGeometry(createBoxGeometry(wall, depth, middleHeight), [-(width - wall) / 2, wall, 0]),
    translateGeometry(createBoxGeometry(wall, depth, middleHeight), [(width - wall) / 2, wall, 0]),
  ];

  // The thicker front collar catches highlights and makes the port read as a
  // connector instead of a solid metallic brick at ambient camera distance.
  const collarDepth = metres(0.42);
  const collarWall = metres(0.48);
  const collarZ = -(depth - collarDepth) / 2;
  pieces.push(
    translateGeometry(createBoxGeometry(width + metres(0.18), collarDepth, collarWall), [0, height - collarWall, collarZ]),
    translateGeometry(createBoxGeometry(width + metres(0.18), collarDepth, collarWall), [0, 0, collarZ]),
    translateGeometry(createBoxGeometry(collarWall, collarDepth, height - collarWall * 2), [-(width - collarWall) / 2, collarWall, collarZ]),
    translateGeometry(createBoxGeometry(collarWall, collarDepth, height - collarWall * 2), [(width - collarWall) / 2, collarWall, collarZ]),
  );
  return mergeGeometries(...pieces);
}

export function createUsbCTongueGeometry() {
  const { tongueWidth, tongueDepth, tongueThickness } = NICE_NANO_V2_DIMENSIONS.usbC;
  return createRoundedSlabGeometry(
    tongueWidth,
    tongueDepth,
    tongueThickness,
    metres(0.18),
    4,
  );
}

export function createQfnGeometry(size = metres(4.1), height = metres(0.72)) {
  return createRoundedSlabGeometry(size, size, height, metres(0.18), 4);
}

export function createPassiveGeometry(
  width = metres(1.0),
  depth = metres(0.5),
  height = metres(0.32),
) {
  return createRoundedSlabGeometry(width, depth, height, metres(0.08), 3);
}

export function createMetalCanGeometry(
  width = NICE_NANO_V2_DIMENSIONS.radioModule.width,
  depth = NICE_NANO_V2_DIMENSIONS.radioModule.depth,
  height = NICE_NANO_V2_DIMENSIONS.radioModule.height,
) {
  const lip = metres(0.12);
  return mergeGeometries(
    createRoundedSlabGeometry(width, depth, height - lip, metres(0.35), 5),
    translateGeometry(
      createRoundedSlabGeometry(width - lip * 2, depth - lip * 2, lip, metres(0.28), 5),
      [0, height - lip, 0],
    ),
  );
}

export function createNiceViewBoardGeometry() {
  const { width, depth, thickness, cornerRadius } = NICE_VIEW_DIMENSIONS.board;
  return createRoundedSlabGeometry(width, depth, thickness, cornerRadius, 7);
}

export function createNiceViewSurfaceGeometry() {
  const { width, depth, thickness, cornerRadius } = NICE_VIEW_DIMENSIONS.activeSurface;
  return createRoundedSlabGeometry(width, depth, thickness, cornerRadius, 7);
}

export function createDisplayBezelGeometry() {
  const outerWidth = metres(12.2);
  const outerDepth = metres(27.4);
  const wall = metres(0.38);
  const height = metres(0.2);
  return mergeGeometries(
    translateGeometry(createRoundedSlabGeometry(outerWidth, wall, height, wall * 0.35, 3), [0, 0, -(outerDepth - wall) / 2]),
    translateGeometry(createRoundedSlabGeometry(outerWidth, wall, height, wall * 0.35, 3), [0, 0, (outerDepth - wall) / 2]),
    translateGeometry(createRoundedSlabGeometry(wall, outerDepth - wall * 2, height, wall * 0.35, 3), [-(outerWidth - wall) / 2, 0, 0]),
    translateGeometry(createRoundedSlabGeometry(wall, outerDepth - wall * 2, height, wall * 0.35, 3), [(outerWidth - wall) / 2, 0, 0]),
  );
}

/** Side-entry S2B-XH-A-style board header, open toward +X. */
export function createJstXhSideEntryHeaderGeometry() {
  const [depth, height, width] = JST_XH_STYLE_PLACEMENT.headerBody.nominalDimensions;
  const sideWall = metres(0.72);
  const floor = metres(0.72);
  const roof = metres(0.78);
  const back = metres(0.75);
  const collar = metres(0.48);
  const middleHeight = height - floor - roof;
  const pieces = [
    createRoundedSlabGeometry(depth, width, floor, metres(0.22), 4),
    translateGeometry(createRoundedSlabGeometry(depth, width, roof, metres(0.22), 4), [0, height - roof, 0]),
    translateGeometry(createBoxGeometry(depth, sideWall, middleHeight), [0, floor, -(width - sideWall) / 2]),
    translateGeometry(createBoxGeometry(depth, sideWall, middleHeight), [0, floor, (width - sideWall) / 2]),
    translateGeometry(createBoxGeometry(back, width - sideWall * 2, middleHeight), [-(depth - back) / 2, floor, 0]),
  ];

  // Raised face lip and top keying rib reproduce the silhouette visible in
  // the photograph without copying a manufacturer's CAD surface.
  const faceX = (depth - collar) / 2;
  pieces.push(
    translateGeometry(createBoxGeometry(collar, width + metres(0.2), floor + metres(0.18)), [faceX, 0, 0]),
    translateGeometry(createBoxGeometry(collar, width + metres(0.2), roof + metres(0.18)), [faceX, height - roof, 0]),
    translateGeometry(createRoundedSlabGeometry(metres(2.2), metres(1.15), metres(0.35), metres(0.12), 3), [metres(0.6), height, 0]),
  );
  return mergeGeometries(...pieces);
}

/**
 * One square right-angle header contact. Its origin is the PCB drill centre;
 * instantiate at J3 pad 1 and pad 2 rather than at the plastic-body centre.
 */
export function createJstXhRightAngleContactGeometry() {
  const pin = metres(0.64);
  const verticalHeight = metres(3.55);
  const horizontalLength = metres(8.25);
  return mergeGeometries(
    translateGeometry(createBoxGeometry(pin, pin, verticalHeight + metres(1.5)), [0, metres(-1.5), 0]),
    translateGeometry(createBoxGeometry(horizontalLength, pin, pin), [horizontalLength / 2, verticalHeight - pin / 2, 0]),
  );
}

/** Cable-side XHP-2-style housing, oriented to mate along -X. */
export function createJstXhMatedPlugGeometry() {
  const [depth, height, width] = JST_XH_STYLE_PLACEMENT.matedPlug.dimensions;
  const bodyHeight = height - metres(0.48);
  const main = createRoundedSlabGeometry(depth, width, bodyHeight, metres(0.3), 5);
  const latch = translateGeometry(
    createRoundedSlabGeometry(metres(3.0), metres(2.0), metres(0.48), metres(0.2), 4),
    [metres(-0.35), bodyHeight, 0],
  );
  const centreRib = translateGeometry(
    createRoundedSlabGeometry(metres(4.0), metres(0.5), metres(0.32), metres(0.12), 3),
    [metres(0.4), metres(1.2), 0],
  );
  return mergeGeometries(main, latch, centreRib);
}

export function createNiceNanoV2Assembly() {
  const dimensions = NICE_NANO_V2_DIMENSIONS;
  const boardTop = dimensions.board.thickness;
  const padGeometry = createAnnularPadGeometry();
  const firstPadZ = -((dimensions.sidePadCount - 1) * dimensions.sidePadPitch) / 2;
  const padX = dimensions.board.width / 2 - metres(0.72);
  const parts = [
    { name: "board", geometry: "board", materialRole: "pcb", translation: [0, 0, 0] },
    { name: "usb_shell", geometry: "usbShell", materialRole: "metal", translation: ELECTRONICS_PLACEMENT.niceNano.usbShellOffset },
    { name: "usb_tongue", geometry: "usbTongue", materialRole: "component", translation: ELECTRONICS_PLACEMENT.niceNano.usbTongueOffset },
    { name: "radio_module", geometry: "radioModule", materialRole: "metal-dark", translation: ELECTRONICS_PLACEMENT.niceNano.radioModuleOffset },
    { name: "charger_ic", geometry: "smallQfn", materialRole: "component", translation: [metres(4.9), boardTop, metres(-10.15)] },
    { name: "power_ic", geometry: "smallQfn", materialRole: "component", translation: [metres(-4.55), boardTop, metres(-8.4)] },
    { name: "crystal", geometry: "crystal", materialRole: "metal", translation: [metres(-4.3), boardTop, metres(11.7)] },
    { name: "status_led_blue", geometry: "led", materialRole: "led-blue", translation: [metres(5.75), boardTop, metres(-12.7)] },
    { name: "charge_led_amber", geometry: "led", materialRole: "led-amber", translation: [metres(4.35), boardTop, metres(-12.7)] },
  ];

  for (let index = 0; index < dimensions.sidePadCount; index += 1) {
    const z = firstPadZ + index * dimensions.sidePadPitch;
    parts.push(
      { name: `pad_left_${String(index).padStart(2, "0")}`, geometry: "pad", materialRole: "gold", translation: [-padX, boardTop, z] },
      { name: `pad_right_${String(index).padStart(2, "0")}`, geometry: "pad", materialRole: "gold", translation: [padX, boardTop, z] },
    );
  }

  const passives = [
    [-5.6, -10.8, "passive-ceramic"], [-3.9, -11.1, "passive-dark"],
    [2.7, -10.7, "passive-ceramic"], [3.5, -9.4, "passive-dark"],
    [-5.5, -6.8, "passive-dark"], [5.25, -6.5, "passive-ceramic"],
    [-5.4, 11.0, "passive-ceramic"], [5.2, 11.5, "passive-dark"],
    [-4.9, 13.0, "passive-dark"], [4.8, 13.05, "passive-ceramic"],
  ];
  for (const [x, z, materialRole] of passives) {
    parts.push({
      name: `passive_${parts.length}`,
      geometry: "passive",
      materialRole,
      translation: [metres(x), boardTop, metres(z)],
    });
  }

  return {
    origin: ELECTRONICS_PLACEMENT.niceNano.origin,
    geometries: {
      board: createNiceNanoBoardGeometry(),
      usbShell: createUsbCReceptacleGeometry(),
      usbTongue: createUsbCTongueGeometry(),
      radioModule: createMetalCanGeometry(),
      smallQfn: createQfnGeometry(metres(2.15), metres(0.56)),
      crystal: createRoundedSlabGeometry(metres(2.2), metres(1.45), metres(0.52), metres(0.16), 4),
      led: createRoundedSlabGeometry(metres(0.82), metres(0.46), metres(0.32), metres(0.08), 3),
      passive: createPassiveGeometry(),
      pad: padGeometry,
    },
    parts,
  };
}

export function createNiceViewAssembly() {
  const { board, activeSurface, headerPitch, headerCount } = NICE_VIEW_DIMENSIONS;
  const boardTop = board.thickness;
  const firstHeaderX = -((headerCount - 1) * headerPitch) / 2;
  const headerZ = board.depth / 2 - metres(1.9);
  const parts = [
    { name: "board", geometry: "board", materialRole: "pcb", translation: [0, 0, 0] },
    { name: "screen", geometry: "screen", materialRole: "screen", translation: ELECTRONICS_PLACEMENT.niceView.activeSurfaceOffset },
    { name: "bezel", geometry: "bezel", materialRole: "screen-bezel", translation: [0, boardTop + activeSurface.thickness, metres(-0.15)] },
    { name: "fpc_connector", geometry: "fpcConnector", materialRole: "connector-ivory", translation: [0, boardTop, metres(-15.8)] },
    { name: "flex_tail", geometry: "flexTail", materialRole: "flex-amber", translation: [0, boardTop + metres(0.18), metres(-18.25)] },
  ];

  for (let index = 0; index < headerCount; index += 1) {
    parts.push({
      name: `header_pad_${index}`,
      geometry: "pad",
      materialRole: "gold",
      translation: [firstHeaderX + index * headerPitch, boardTop, headerZ],
    });
  }
  for (const [index, [x, z]] of [
    [-5.7, -16.0], [5.7, -16.0], [-5.7, 16.0], [5.7, 16.0],
  ].entries()) {
    parts.push({
      name: `via_${index}`,
      geometry: "via",
      materialRole: "gold",
      translation: [metres(x), boardTop, metres(z)],
    });
  }

  return {
    origin: ELECTRONICS_PLACEMENT.niceView.origin,
    geometries: {
      board: createNiceViewBoardGeometry(),
      screen: createNiceViewSurfaceGeometry(),
      bezel: createDisplayBezelGeometry(),
      fpcConnector: createRoundedSlabGeometry(metres(8.8), metres(2.65), metres(0.9), metres(0.28), 5),
      flexTail: createRoundedSlabGeometry(metres(8.0), metres(4.0), metres(0.16), metres(0.24), 4),
      pad: createAnnularPadGeometry(metres(0.7), metres(0.38), metres(0.07), 16),
      via: createAnnularPadGeometry(metres(0.48), metres(0.26), metres(0.06), 14),
    },
    parts,
  };
}

export function createJstXhStyleAssembly() {
  const placement = JST_XH_STYLE_PLACEMENT;
  return {
    origin: "Chocofi J3 pad 1 (GND) at main-PCB top surface",
    geometries: {
      header: createJstXhSideEntryHeaderGeometry(),
      contact: createJstXhRightAngleContactGeometry(),
      plug: createJstXhMatedPlugGeometry(),
    },
    parts: [
      {
        name: "board_header",
        geometry: "header",
        materialRole: "connector-ivory",
        translation: placement.headerBody.offsetFromPad1,
      },
      {
        name: "contact_ground",
        geometry: "contact",
        materialRole: "gold",
        translation: [0, 0, 0],
      },
      {
        name: "contact_battery_plus",
        geometry: "contact",
        materialRole: "gold",
        translation: placement.pad2Offset,
      },
      {
        name: "mated_plug",
        geometry: "plug",
        materialRole: "connector-ivory",
        translation: placement.matedPlug.offsetFromPad1,
      },
    ],
    wireExitOffsets: placement.wireExit,
  };
}

/** Validate every geometry referenced by an assembly descriptor. */
export function assertAssembly(assembly, name = "electronics assembly") {
  if (!assembly || typeof assembly !== "object" || !assembly.geometries || !Array.isArray(assembly.parts)) {
    throw new Error(`${name} must contain geometries and parts`);
  }
  for (const [geometryName, geometry] of Object.entries(assembly.geometries)) {
    assertGeometry(geometry, `${name}.${geometryName}`);
  }
  const partNames = new Set();
  for (const part of assembly.parts) {
    if (!part.name || partNames.has(part.name)) throw new Error(`${name} has a duplicate/empty part name`);
    partNames.add(part.name);
    if (!assembly.geometries[part.geometry]) {
      throw new Error(`${name}.${part.name} references unknown geometry ${part.geometry}`);
    }
    if (!ELECTRONICS_MATERIAL_ROLES.includes(part.materialRole)) {
      throw new Error(`${name}.${part.name} uses unknown material role ${part.materialRole}`);
    }
    if (!Array.isArray(part.translation) || part.translation.length !== 3 || !part.translation.every(Number.isFinite)) {
      throw new Error(`${name}.${part.name} has an invalid translation`);
    }
  }
  return assembly;
}
