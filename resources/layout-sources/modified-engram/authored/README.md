# Supplied Chocofi model

`model.glb` is the exact user-supplied file. Its SHA-256 is
`01b84edda68fd4b6501bdc0f9d8e2dc9bb7a1bbcdcfd5dd286c437b819b24f08`.
Keep this source intact; the adapted `../model.glb` is generated with
`npm run generate:model`.

The source identifies itself as a photo-matched Chocofi reconstruction using
the v1 case and exact key XY positions. It describes accessory dimensions, cap
shape, and height placement as photograph-based estimates. It was exported by
trimesh with separate named objects, millimetre vertex data, and a metre/Y-up
conversion on each object. Its embedded display photographs are retained in
the source but replaced by live status surfaces in PRETYPIST.

The adapter adds centred metre-scale animation pivots and maps `L_SW2_Keycap`
through the 36 documented switch references to `key_00` through `key_35`.
The original file and BIN bytes remain intact. Revision 12 selectively refines
the derived asset using `../surface-refinements.json`: cap profiles, surface
normals, embedded PBR micrograin maps, and glass fastener shafts. Case topology,
wiring, glass and component mounting positions retain their source geometry.
See `../model-adaptation.json` for the complete mapping and refinement ledger.

This dense GLB has 959,092 source triangles and 18,408 triangles exceeding
aspect ratio 1,000. The refined render has 973,424 triangles: photographic quads
become dynamic display planes, new cap patches replace the old cap triangles,
and four shafts are added. Its geometry audit explicitly
allows these inherited counts only with this exact source hash. It is not a
quad-editable source, and no quad percentage can be established from this GLB.
The previous procedural Blender model is retained separately under `../legacy/`.
