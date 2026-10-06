# Chocofi v1 source notice

The files in this directory were copied from
<https://github.com/pashutk/chocofi> at commit
`273676d11b06785fb5a1a94860a39fc36c38baba`:

| File | Upstream path | SHA-256 |
|---|---|---|
| `top.step` | `case/v1/top.step` | `73555d15108088b8f885d367077314532b04bc34741c7bbb3726d660a7e60e43` |
| `bottom.step` | `case/v1/bottom.step` | `79d5427d1427d596f045d81a9964726fcc30c37b6262e0d14effda59c6b0f7fc` |
| `top.stl` | `case/v1/top.stl` | `fc122c3fd4d57a28e9dd912c224826b70e5846be40affd7df09dc7cd26be962d` |
| `bottom.stl` | `case/v1/bottom.stl` | `ed14e322eab830092c7409b8d558514e6b860fe8d33e142f37988056fcdb26b1` |
| `chocofi.kicad_pcb` | `pcb/chocofi.kicad_pcb` | `1e8f65fc5cc3df280c716f6b1d3e730757d445d84b1a809a7a87aea7d593a9fc` |
| `LICENSE.txt` | `LICENSE.txt` | `6e471d647db50527d0c75ee5f1a55a74012ab177c73282bef0bdc7f6562b7ded` |

The upstream hardware sources are licensed under the CERN Open Hardware
Licence Version 2 – Permissive (`CERN-OHL-P-2.0`); the complete upstream
licence is retained as `LICENSE.txt`.

Modification notices:

- 2026-09-06: PRETYPIST converted the published STL case triangle meshes to
  metres/Y-up, added independently animated keycaps and a representative
  wireless assembly, and mirrored the authored left half.
- 2026-09-07: PRETYPIST switched the render source to the published STEP
  solids. `scripts/tessellate-step.py` creates checked-in render meshes at a
  documented tolerance; the GLB generator then welds them with a 30-degree
  crease boundary. The case surface cost falls from 335,672 to 26,614 stored
  triangles while preserving the B-rep dimensions and openings. It also adds
  independently constructed visual models of the nice!nano/nice!view stack,
  a separate side-entry XH-style header and mated plug, visible contacts and
  wiring. These component details are render proxies, not manufacturing CAD.
  Both B-reps pass OpenCascade's validity check. One microscopic trimmed face
  per body is below the chosen render tessellation resolution; its exact area
  and centroid are recorded rather than replaced with a degenerate triangle.

The generated `model.glb` records the pinned source hashes, derived-mesh
hashes, tessellation tolerances, triangle count, and normal crease angle in its
glTF asset metadata.
