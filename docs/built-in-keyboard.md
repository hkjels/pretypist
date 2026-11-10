# Built-in keyboard: Modified Engram

PRETYPIST ships Modified Engram for the 36-key Chocofi. The layout was
transcribed from the authoritative ZMK configuration. The checked-in sources
are enough to build and run the app without the reference repository. See the
[package source README](../resources/layout-sources/modified-engram/README.md)
for dimensions, assumptions, and provenance.

Editable sources and generated assets are kept at:

```text
resources/layout-sources/modified-engram/
├── layout.edn
├── model.glb
├── model-adaptation.json
├── runtime-optimization.json
├── surface-refinements.json
├── authored/model.glb
├── legacy/model.blend
├── legacy/model-topology.json
├── README.md
├── render-mesh/
├── display/
│   ├── provenance.json
│   ├── NOTICE.md
│   └── vendor/zmk-v0.3/
└── upstream/chocofi-case-v1/
    ├── top.step
    ├── bottom.step
    ├── top.stl
    ├── bottom.stl
    ├── chocofi.kicad_pcb
    ├── LICENSE.txt
    └── NOTICE.md
```

The generated distributable is:

```text
resources/public/layouts/modified-engram.pretypist
```

Rebuild and verify the deterministic artifacts with:

```sh
npm run generate:model
npm run audit:model
npm run verify:zmk-display
npm run package:layout
node scripts/adapt-authored-model.mjs --check
node scripts/package-layout.mjs --check
```

The built-in archive is fetched and passed through the public JSZip/EDN/GLB
import pipeline. It is not compiled into ClojureScript as a special case.

## Chocofi key-index and GLB contract

Modified Engram uses exactly 36 physical indices and exactly 36 named key
objects:

| Indices | Position order |
|---|---|
| `00`–`04` | left top row, outside to inside |
| `05`–`09` | right top row, inside to outside |
| `10`–`14` | left home row, outside to inside |
| `15`–`19` | right home row, inside to outside |
| `20`–`24` | left bottom row, outside to inside |
| `25`–`29` | right bottom row, inside to outside |
| `30`–`32` | left thumbs, outside to inside |
| `33`–`35` | right thumbs, inside to outside |

The left thumb keys are `k30` one-shot Shift, `k31` Space/NAV, and `k32`
one-shot SYM. The Windows host profile changes the layer behind the SYM key
at `k32`, while keeping its physical position.

The model must expose these unique, discoverable names (lookup never depends
on traversal order):

```text
keyboard
half_left
half_right
case_left
case_right
display_screen_left
display_screen_right
key_00 … key_35
```

It must be binary glTF 2.0, Y-up, consistently measured in meters, split into
two halves with exactly 3×5 finger keys and three thumb keys per half. Keycaps
remain blank; PRETYPIST creates planes with cached CanvasTextures for legends when
the active layer changes. Each named key may be a mesh or a transform group
containing meshes.

Revision 12 refines the user's supplied detailed Chocofi model. Its original GLB
is retained byte-for-byte under `authored/`. A deterministic adapter maps the
named SW references to the 36 stable indices, creates centred metre-scale
half/key pivots, and replaces the two static display photographs with live
status anchors. `surface-refinements.json` controls dished, subtly inset-corner
caps, embedded case micrograin, and physically based steel for the screws and
USB housings. Four shafts traverse the existing glass holes, and the brass
standoffs meet the glass underside. These targeted shape/material changes do
not alter case topology, wiring, USB dimensions, key pivots or display mappings.
Revision 14 derives a 49,186-triangle browser asset through the build-time
runtime optimization pass described below.

`:model :material-policy` accepts `:normalized` (the default) or `:preserve`.
The supplied model opts into preservation, retaining the authored albedo,
roughness, transparency, textures, and flat/smooth shading. Key materials are
cloned per physical key so a press can glow independently. Transparent covers
do not write opaque depth over the live screen. Legends are placed above the
actual keycap bounds, including models that nest millimetre geometry beneath
metre-scale pivots.

## nice!view status surfaces

The package maps the two named display surfaces declaratively. The left
display uses the allowlisted `:zmk/nice-view-status-v0.3` template for battery,
endpoint, five Bluetooth profiles, a ten-sample WPM graph, and the highest
demonstrated layer. The right display uses
`:zmk/nice-view-peripheral-v0.3` for its independent battery, split-link state,
and peripheral illustration. Its selectable balloon and mountain scenes use
the exact packed 1-bit artwork from the pinned stock ZMK v0.3 widget; the
balloon is the built-in default. Three.js attaches reflective, monochrome 160 ×
68 CanvasTextures just above the mapped screen meshes. They redraw only when
their canonical status changes and are disposed when a model is replaced.

PRETYPIST can supply its own measured WPM and demonstrated semantic layer.
Battery percentage, USB power, Bluetooth bonding/connection, and split status
remain explicitly `:simulated` because ordinary browser keyboard events do not
expose that firmware telemetry. The on-screen `SIM` mark makes that boundary
visible. The ZMK bolt is described as USB power present, not proof of battery
charge current.

PRETYPIST does not interpret arbitrary `status.c` during normal builds. That
would require executing the ZMK/Zephyr/LVGL preprocessing environment and would
turn imported C into an unsafe, unstable package interface. Instead, the
repository keeps exact MIT-licensed ZMK `v0.3` source evidence and uses a
read-only, allowlisted verifier plus a deterministic bitmap extractor:

```sh
npm run verify:zmk-display
npm run generate:zmk-display-assets
npm run check:zmk-display-assets
node scripts/verify-zmk-display.mjs --check --zmk-root /path/to/zmk
```

The verifier pins commit
`edf5c0814fd3ea202e43aad2d68fd32e882a518c`, checks exact file hashes and
structural visual constants for both central and peripheral widgets, rejects
unknown revisions, and never compiles or executes C. Supporting a later stock
widget is therefore a deliberate reviewed template/version change, not an
implicit source translation.

## Rebuilding the case evidence meshes from STEP

The checked-in `render-mesh/top.stl` and `bottom.stl` keep the normal app build
self-contained. To regenerate them after deliberately replacing the pinned
STEP sources, use Python 3.12 and the pinned authoring-only OpenCascade wheel:

```sh
python3.12 -m pip install -r scripts/requirements-cad.txt
npm run author:case-mesh
npm run author:model:legacy
```

The authoring task verifies the source STEP hashes, uses fixed 0.08 mm linear
and 12-degree angular deflection, writes deterministic binary STL and metadata,
and never runs during `npm run dev`, `npm test`, or `npm run build`. The second
command uses those checked-in sources as dimensional/provenance evidence for
the Blender-authored browser scene; it is not part of an ordinary app build.

Both source B-reps pass OpenCascade's validity check. At this render tolerance,
one roughly 0.002 mm² trimmed sliver face per body is not tessellated. The mesh
metadata records each face's index, exact area, and centroid. This produces 35
sub-resolution boundary edges per body; the generator rejects non-manifold
edges and degenerate triangles rather than inventing geometry over those seams.

## Model adaptation and audit

`npm run generate:model` adapts the reviewed `authored/model.glb` and writes
`model.glb` plus `model-adaptation.json`. It rejects an unreviewed source hash
and runs two deterministic stages. The full-detail adaptation retains the
original BIN prefix and applies the surface refinements. The runtime pass
then simplifies selected meshes with meshoptimizer (bundled with Three.js).
`runtime-optimization.json` records a 50,000-triangle budget and per-part error
targets: 0.06–0.07 mm for cases, 0.035 mm for caps, 0.04 mm for wiring and PCBs,
and 0.06 mm for other selected components. Normals and UVs guide edge collapse;
borders, bounding extrema and keycap dish centres are protected. The glass rim
uses complete 12-sided tube cross-sections with redundant longitudinal rings
removed at a 0.001 mm sampled chord-distance target. Case normals are baked
from the detailed source, preserving sharp creases and flat panels while
keeping coarse bevels visually smooth. Closed surfaces and hole topology are
verified; problematic collapse neighbourhoods retain their full geometry.
The meshoptimizer error metric is not a measured maximum surface-distance bound.

Retained positions and UVs are exact source values. Fasteners, USB housings,
glass plates/apertures and all node transforms remain unchanged. Unused buffers
and LCD photos are removed; identical buffers are shared and fitting indices
use 16 bits. Rendered PBR materials, texture pixels and samplers are preserved
exactly. The original authoring GLB remains byte-for-byte intact under `authored/`.

The report records both stages, source/output hashes, all physical mappings
and the simplification settings. The package uses lossless DEFLATE compression
for the GLB, through the existing bounded ZIP reader. No runtime decoder or
new dependency is required. Eight matched browser views guard visible detail,
including the main view, case seam, underside, USB port, keycaps and fasteners.
Small lighting differences are accepted at extreme zoom; the main view,
keycaps and hardware have tighter pixel-difference limits.
Review images and pixel metrics are written to `target/model-review/optimization/`.

```sh
npm run generate:model
npm run audit:model
node scripts/adapt-authored-model.mjs --check
npm run package:layout
```

The full-detail refinement has 973,424 rendered triangles (671,344 in the case).
The runtime model has 49,186 (15,712 in the case), with zero degenerates
and 329 triangles above aspect ratio 1,000. It is 1,386,620 bytes, down from
16,935,940. Both audit stages pin their geometry limits to the supplied source
hash and check all 43 required keys, halves, case and display nodes. Ordinary
imported packages still use the same self-contained package validation and size limits.

This GLB does not expose editable quad topology. The previous 99.23% quad
report and 6.8/10 critic score describe only the earlier procedural model,
retained under `legacy/`; they are not claims about this replacement.

For a future replacement, preserve the named hierarchy, metre-scale animation
pivots, and local Y press direction. Use `:material-policy :preserve` to retain
authored materials. Displays may name an empty, unit-scale anchor; its runtime
surface sits 0.345 mm above that origin with its top toward local negative Z.
Set `:surface-size-mm` to the actual visible aperture (10.9 × 25.5 mm in the
current model). Keep every buffer and image embedded.

The optional `npm run author:model:legacy` command uses Blender MCP to rebuild
the old procedural model under `legacy/`. It cannot overwrite the selected
authored asset. Normal builds do not require Blender or an external checkout.
