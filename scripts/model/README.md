# Procedural model helpers

`electronics.mjs` contains original, dependency-free triangle geometry for the
small electronics visible on the Chocofi. All geometry is expressed in metres,
uses Y-up coordinates, and has the `{positions, normals, indices}` shape expected
by `scripts/generate-model.mjs`'s glTF builder.

The three high-level assembly functions return:

- `geometries`: reusable meshes keyed by a local geometry name
- `parts`: named instances with a geometry key, material role, and translation
- `origin`: a description of the assembly coordinate origin

The intended integration pattern is:

1. Call `createNiceNanoV2Assembly`, `createNiceViewAssembly`, or
   `createJstXhStyleAssembly` while constructing the GLB.
2. Add each entry in `geometries` to the existing glTF builder once, mapping its
   `materialRole` values to the application's material indices.
3. Append one node for each `parts` entry, using its translation and the mesh ID
   for its geometry key. Add the usual `_left`/`_right` suffix to final node
   names if desired.
4. Place the nice!nano at the transformed `niceNano` PCB anchor, and the
   nice!view at the same X/Z centre plus `niceView.relativeToNiceNano` in Y.
5. Place the JST assembly origin at transformed `batteryJ3Pad1`. J3 remains the
   electrical pin anchor; the header and plug offsets already point toward the
   inner edge. Use `wireExitOffsets` as the first points for the red/black tube
   curves. Mirroring the completed left-half parent produces the right half.

The helpers deliberately do not include textures, materials, node hierarchy, or
Three.js objects. Mutable renderer state remains outside the generated model,
and the same geometry can be reused by other exporters.

Run the focused checks with:

```sh
node --test scripts/model/electronics.test.mjs
```

The dimensions and provenance are documented at the top of the module. No
manufacturer or community CAD binaries are copied into this directory.

