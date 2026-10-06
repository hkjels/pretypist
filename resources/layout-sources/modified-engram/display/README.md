# Stock ZMK nice!view authoring evidence

This directory preserves the exact, minimal upstream evidence used to render
PRETYPIST's nice!view display surfaces. It is not firmware and is not loaded by
the browser. The C files are retained as inert source text so visual details do
not depend on a developer's external ZMK checkout.

## Pinned source

- Repository: <https://github.com/zmkfirmware/zmk>
- Release tag: `v0.3`
- Tag object: `3cf9006a9377f21c99e944e29e2a507d8c3e9069`
- Commit: `edf5c0814fd3ea202e43aad2d68fd32e882a518c`
- Upstream directory:
  `app/boards/shields/nice_view/widgets/`
- License: MIT; the unmodified upstream license is vendored at
  `vendor/zmk-v0.3/LICENSE`

`provenance.json` records the only allowed revision, every copied path, exact
byte size and SHA-256 hash, and the small set of visual constants transcribed
for authoring. `NOTICE.md` records attribution and the fidelity boundary.

The allowlist contains the complete eight-file stock widget directory: the
central status widget, peripheral widget, drawing helpers, charging-bolt
image, and the two peripheral artwork bitmaps. The display overlay proves the
native resolution, and the repository license preserves the grant. ZMK's
build wrapper, Kconfig, and unrelated firmware are intentionally excluded.

## Verified visual constants

The stock panel and central widget are 160 × 68 pixels. ZMK draws three
68-pixel square canvases, rotates each by 900 tenths of a degree, and places
them at right-aligned `(0, 0)`, left-aligned `(24, 0)`, and left-aligned
`(-44, 0)`. The middle canvas contains five Bluetooth profile centres. The
other recorded constants cover the WPM frame, layer label, battery outline,
and 11 × 18-pixel charging glyph. On a peripheral half, ZMK reserves the
rightmost 68-pixel canvas for battery/connection state and randomly selects a
140 × 68-pixel balloon or mountain bitmap for the remaining display area.

These are display-pixel/layout facts. They do not establish the physical PCB,
glass, bezel, connector, or component dimensions, and must not be used as
manufacturing CAD.

## Verification

From the PRETYPIST repository root:

```sh
node scripts/verify-zmk-display.mjs --check
```

To compare an explicit upstream checkout as an additional audit:

```sh
node scripts/verify-zmk-display.mjs --check --zmk-root /path/to/zmk
```

The explicit checkout must have `HEAD` at the pinned commit. Only allowlisted
paths are opened; each must be a regular file beneath the supplied root. The
tool verifies exact bytes and semantic markers but never preprocesses,
compiles, or executes C and never updates `layout.edn`.

To deterministically extract the reviewed balloon and mountain pixel payloads
from `art.c` into the runtime ClojureScript namespace:

```sh
npm run generate:zmk-display-assets
npm run check:zmk-display-assets
```

Generation first performs the complete evidence verification above, accepts
only the two known `LV_IMG_CF_INDEXED_1BIT` descriptors, validates dimensions,
palettes, row padding and byte counts, then writes only their packed one-bit
pixel payloads. The normal production asset preparation runs this generator.

Unknown revisions are rejected. Supporting a later ZMK release requires a
reviewed change to the verifier's revision allowlist, these vendored files,
and `provenance.json`; it is never an implicit refresh.
