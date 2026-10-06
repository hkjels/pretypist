# STEP-derived case render meshes

`top.stl` and `bottom.stl` are deterministic binary render meshes generated
from the pinned Chocofi v1 STEP solids in `../upstream/chocofi-case-v1/`.
They are checked in so the normal PRETYPIST build needs only Node and Clojure,
not a CAD kernel.

Regenerate them only when deliberately updating the case source:

```sh
python3.12 -m pip install -r scripts/requirements-cad.txt
npm run author:case-mesh
```

The authoring task verifies the source hashes before conversion and records
the exact tessellation settings, bounds, source/output hashes, B-rep face
counts, and triangle counts in `mesh-metadata.json`. Do not edit the STL or
metadata files independently.

Both source B-reps pass OpenCascade's validity check. At the documented render
tolerance OpenCascade leaves one microscopic trimmed sliver face per body
untessellated (about 0.002 mm² each). Their face indices, areas, and centroids
are retained in the metadata. The GLB build also records the resulting 35
boundary edges per body and rejects non-manifold edges; no synthetic or
degenerate triangles are inserted to conceal those sub-resolution seams.
