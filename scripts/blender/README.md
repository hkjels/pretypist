# Legacy Blender MCP model authoring

The current model is the user-supplied `authored/model.glb`, adapted by
`scripts/adapt-authored-model.mjs`. This program is retained only for the old
procedural model; none of its output replaces the selected asset.

`build_modified_engram.py` is the reviewable, repeatable Blender 5.2 authoring
program for the built-in Chocofi scene. It is sent to Blender through the
official MCP extension's local execution bridge by `../blender-mcp-client.mjs`.
It never saves over the user's open file: the PRETYPIST model is built in an
isolated scene and written as a separate Blender library.

With Blender and its MCP bridge running:

```sh
npm run author:model:legacy
```

Outputs:

- `resources/layout-sources/modified-engram/legacy/model.blend` — editable source
- `resources/layout-sources/modified-engram/legacy/model-topology.json` — quad audit
- `resources/layout-sources/modified-engram/legacy/model.glb` — stamped browser asset
- `target/model-review/round-3-*.png` — review-only renders

The build needs the checked-in package sources only. The attached user photo
guided visual decisions but is neither copied into the repository nor required
to rebuild the asset.
