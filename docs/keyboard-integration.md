# Add a keyboard

A `.pretypist` package combines a layout definition with a 3D keyboard model.
You can import an existing package or create one for your own keyboard.

## Import an existing package

1. Open **Settings → Keyboard → Import layout** and select a `.pretypist` file.
2. Review the layout preview in both color schemes, then choose **Install layout**.

PRETYPIST validates the package before previewing it. Installing it stores it
locally and makes it active. In **Settings → Keyboard**, you can switch between
layouts and remove imported layouts that aren't active.

## Build a layout package

Start with the [One-Piece Example](../resources/public/layouts/one-piece-example.pretypist):
a 31-key QWERTY board with one case, one layer, and no display or gesture
mappings. Its [source layout](../resources/layout-sources/one-piece-example/layout.edn)
is a minimal working example. Run `npm run package:example` to rebuild it.
Without a usable `:strokes` mapping, calibration ends after phrase rhythm and
the Layers mode is unavailable. Prose practice still works.

Use the [package format and schema](package-format.md) as a reference. The
[built-in layout](../resources/layout-sources/modified-engram/layout.edn)
shows a split board with layers, gestures, and displays.

1. Create a source directory containing `layout.edn` and a self-contained
   `model.glb`; optionally add `preview.webp` and `README.md`.
2. Give the package a stable, unique `:package/id` and start at
   `:package/revision 1`. Define physical keys independently from layer
   bindings, and map every key explicitly to a named, renderable GLB object.
3. Define semantic layers and legends. For gesture practice, add `:strokes` for
   the outputs the Layers trainer should visualize. Put
   host-specific HID details in `:host-profiles`, not in display legends.
4. From the repository root, build a package with the repository tool:

   ```sh
   node scripts/package-layout.mjs \
     --source resources/layout-sources/my-layout \
     --output target/my-layout.pretypist
   ```

5. Check the resulting archive and its embedded GLB:

   ```sh
   node scripts/package-layout.mjs \
     --output target/my-layout.pretypist \
     --check
   ```

6. [Import the package](#import-an-existing-package) in PRETYPIST to test it.

The package script enforces the canonical root files and size limits, while the
browser importer performs the full semantic EDN and GLB validation. Always test
an archive in the app before distributing it.

Run `npm run preview:themes` after a production build to exercise the real
browser import preview and write deterministic dark, light, and combined
palette captures to `target/theme-previews/`. The preview reports the lowest
validated core contrast ratio for each scheme.

## Model and key animation

Export a binary glTF 2.0 model in metres, with Y up and textures embedded.
Use unique node names and match them to the model and key mappings in
`layout.edn`. Each key must be a mesh or a group containing a mesh. Keep
keycaps blank if you want PRETYPIST to draw layer legends. Orient the key
pivots so pressing a key moves it along local negative Y.

See the [package schema](package-format.md#layoutedn-schema) for model and
display fields, and the [built-in model](built-in-keyboard.md#chocofi-key-index-and-glb-contract)
for a concrete example.

## Package-owned themes

`:theme` is optional and backward-compatible within package format version 1.
Its own `:version` is currently `1`. A package can declare `:dark`, `:light`, or
both under `:schemes`; an absent scheme uses the complete PRETYPIST fallback.
A present scheme may omit individual tokens, which inherit safe defaults.

The only accepted scheme keys are `:background`, `:foreground`,
`:muted-foreground`, `:accent`, `:error`, `:focus`, `:prose-current`,
`:prose-completed`, `:prose-upcoming`, and `:panel`. The only accepted
`:layer-colors` keys are `:base`, `:num`, `:sym`, `:nav`, and `:mouse`. Every
value must be an opaque `#RRGGBB` color. Unknown keys, alpha values, CSS
functions, selectors, URLs, fonts, shaders, and renderer settings are rejected
with an exact EDN path. Core text pairs must meet WCAG AA-style contrast
thresholds (4.5:1 for normal text, 3:1 for muted/supporting UI and focus).

The global Appearance setting remains `Follow system`, `Dark`, or `Light` and
applies to every layout. Replicant projects the resolved semantic palette onto
a fixed `--pt-*` property set on the application root. Nexus sends only the
resolved layer colors to the persistent Three.js scene. Packages cannot affect
exposure, lights, post-processing, shader code, arbitrary materials, or DOM
structure.

The static shell also ships a restrictive Content Security Policy: scripts,
fetches, and runtime assets remain same-origin, while `blob:`/`data:` images are
allowed only where embedded GLB decoding needs them. Inline script and
evaluated-code execution are not permitted.
