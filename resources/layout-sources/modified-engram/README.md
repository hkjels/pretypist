# Modified Engram layout package source

This directory is the editable source for PRETYPIST's built-in **Modified
Engram** package. `layout.edn` is the reviewable layout definition,
`authored/model.glb` is the exact user-supplied model, and `model.glb` is its
adapted browser asset. `model-adaptation.json` records the mapping and measured
geometry costs. The build packages `layout.edn`, `model.glb`, and this README
into the deterministic `modified-engram.pretypist` ZIP. The app loads it through
the same parser and validator as an imported package.

The data was transcribed from the read-only Chocofi ZMK repository. The
authoritative evidence was:

- `README.md`: verified matrix conversion, layer tables, behavioral intent,
  host convention, and conversion decisions.
- `config/chocofi.keymap`: executable layer bindings, timing values, macros,
  tap dances, modifier morph, Bluetooth actions, and safety combo.
- `src/profile_layout.c`: persistent Bluetooth-profile-to-host-overlay rule.
- `boards/shields/chocofi/chocofi.dtsi` and the two half overlays: the
  42-position transform and split orientation.

The upstream audit identifies the original Vial input as SHA-256
`f2ef1416057696e03e4e394fc8d85b808205ff4e915d0b9f4ce1743e10f9230f`.
PRETYPIST does not need that repository or the Vial file at build time or at
runtime.

Package revision 12 refines the user's detailed Chocofi model. It retains the
current left-thumb order: outer one-shot Shift (`k30`), middle Space/NAV
(`k31`), and inner one-shot SYM (`k32`). Windows changes the SYM backing layer
at `k32`, not its physical position.

## Supplied model and compatibility adaptation

The original file is preserved at `authored/model.glb`, with SHA-256
`01b84edda68fd4b6501bdc0f9d8e2dc9bb7a1bbcdcfd5dd286c437b819b24f08`.
Its embedded provenance identifies the Chocofi v1 case and PCB at commit
`273676d11b06785fb5a1a94860a39fc36c38baba`. The accessory shapes, cap profile,
and vertical placement are described by the supplied asset as photograph-based
estimates, not manufacturing measurements.

`npm run generate:model` maps the explicit left/right SW references to
`key_00` through `key_35`, adds `keyboard`, `half_left/right`, and
`case_left/right` groups, and creates centred metre-scale pivots. Original
mesh transforms are compensated to preserve mounting positions and key pivots.
Key press travel is 2.4 mm along local negative Y. Legends use measured cap
bounds, and only the caps move; sockets and switches remain stationary.

The two original photograph planes are replaced with live
`display_screen_left/right` anchors. Their active aperture is 10.9 × 25.5 mm,
matching the supplied mesh. Glass, PCB, connector dimensions and cable routing
remain intact. Original image bytes remain embedded as
source evidence, but the photographic screen planes are not rendered.

`surface-refinements.json` is the editable parameter source for
`scripts/model/refine-surfaces.mjs`, applied by the adapter. It adds:

- Stainless-steel PBR materials for screw heads/shafts and brushed USB shells,
  with crease-aware normals and embedded deterministic normal/roughness maps.
- A darker black case with fine micrograin and lower roughness than the caps.
  Case vertex positions and triangle indices are unchanged; compact UVs are added.
- Smooth blank keycaps with the source footprint, rotations and chamfer heights,
  a 0.65 mm dish and a subtle 0.35 mm inward corner adjustment. These profile
  choices interpret the owner's description; they are not measurements of a cap.
- Four 2 mm diameter shafts through the existing 2.4 mm glass holes. Shafts
  overlap the screw heads and enter the brass standoffs; the standoffs now meet
  the model's glass underside. Hole geometry is not changed.

Textures are embedded PNGs using standard glTF PBR and `KHR_texture_transform`;
no custom shader code, remote texture, Blender, or runtime rebuild is needed.
The entire original BIN is retained as a prefix, with new data appended.

`:model :material-policy :preserve` keeps the resulting GLB materials, textures,
and shading. Key materials are cloned for independent glow, and transparent
cover materials have depth writes disabled so they do not hide the live LCD.
Layouts without this option keep the existing `:normalized` runtime palette.

The source contains 959,092 triangles; the refined scene renders 973,424.
The case still accounts for 671,344 triangles.
There are zero degenerates and 18,408 inherited triangles above aspect ratio
1,000. The adapter verifies those costs against this pinned source rather than
claiming that the old procedural model's topology audit applies. This supplied
GLB does not expose editable quad topology. The previous `.blend` and quad
report remain available under `legacy/`.

## Stable key and node order

PRETYPIST removes the six unpopulated positions from ZMK's 42-position
Corne-compatible transform and uses these 36 stable indices:

| Indices | Physical positions | Base roles |
|---|---|---|
| 00–04 | Left top, outside → inside | `B Y O U quote-dance` |
| 05–09 | Right top, inside → outside | `L D W V Z` |
| 10–14 | Left home, outside → inside | `C I/GUI E/Alt A dot-dance` |
| 15–19 | Right home, inside → outside | `H T S N Q` |
| 20–24 | Left bottom, outside → inside | `G X J K dash-dance` |
| 25–29 | Right bottom, inside → outside | `R M F P NUM-latch` |
| 30–32 | Left thumbs, outside → inside | one-shot Shift, Space/NAV, one-shot SYM |
| 33–35 | Right thumbs, inside → outside | one-shot Ctrl, Enter/NUM, Right Alt |

Every key record also carries its original `:firmware-position`. This is
important for the left thumb cluster: the ZMK transform enumerates those keys
inner-to-outer (`36 37 38`), while PRETYPIST's required order is
outer-to-inner (`k30 k31 k32`). Right thumbs remain `39 40 41`.

The model contract is name-based, never traversal-order-based. Its named
objects are `keyboard`, `half_left`, `half_right`, `case_left`, `case_right`,
`display_screen_left`, `display_screen_right`, and exactly `key_00` through
`key_35`. The model is Y-up and measured in meters. All buffers and textures
must stay embedded in the GLB.

## Functional layers

The package presents five semantic layers. Firmware has two internal SYM
backing layers and a transparent Windows base overlay, but PRETYPIST keeps
those implementation details inside host-profile metadata.

| Layer | Key content |
|---|---|
| Base | Modified Engram letters, three four-state punctuation dances, two positional home-row modifiers, one-shot modifiers/layer, Space/NAV, Enter/NUM, and the secondary NUM one-shot/toggle latch |
| NUM | Norwegian `ø æ å`, digits, keypad operators, editing keys, and a NUM toggle exit |
| SYM | Complete ASCII punctuation with a mirrored bracket row; `$` becomes `€` with Shift |
| NAV | A 2×3 media block, editing/navigation keys, and the MOUSE toggle |
| MOUSE | Five Bluetooth profiles, pointer/scroll controls, three buttons, editing keys, MOUSE exit, and the two-corner profile-clear combo |

`[:transparent]` deliberately falls through to a lower active layer;
`[:disabled]` deliberately does nothing. They are not interchangeable.

## Behaviors retained from ZMK

- All punctuation dance terms are 230 ms.
- Dot dance: tap `.`, hold disabled, double-tap `,`, double-hold disabled.
- Dash dance: tap `-`, hold `->`, double-tap `_`, double-hold `->>`.
- Quote dance: tap apostrophe, hold pipe, double-tap composable acute dead
  key, double-hold the fenced-code-block macro.
- `I/GUI` and `E/Alt` are balanced positional hold-taps with a 230 ms tapping
  term, 150 ms quick-tap term, 100 ms prior-idle guard, and opposite-hand
  early-hold triggers.
- The secondary NUM key taps for one-shot NUM and holds to toggle NUM. Holding
  the Enter thumb is the primary momentary NUM gesture.
- Shift and right Ctrl on Base are sticky/one-shot modifiers. SYM is a
  sticky/one-shot layer.
- The MOUSE bottom outside corners (`k20` and `k29`) are inert separately.
  They must be pressed within 500 ms and held together for 700 ms to clear
  only the selected Bluetooth profile; early release cancels it.

The literal `~`, `^`, and backtick actions append Space after their Norwegian
dead-key chord. The quote dance's acute accent intentionally does not append
Space, so it remains composable.

## Host profiles

The host profile is selected by the active ZMK Bluetooth slot:

| UI label | ZMK index | PRETYPIST profile | Firmware behavior |
|---|---:|---|---|
| BT 1 · Mac | 0 | `:mac-no` | Base + macOS SYM |
| BT 2 · Win | 1 | `:win-no` | transparent WIN overlay + Windows SYM |
| BT 3 | 2 | `:mac-no` | Base + macOS SYM |
| BT 4 | 3 | `:mac-no` | Base + macOS SYM |
| BT 5 | 4 | `:mac-no` | Base + macOS SYM |

Both hosts must use their standard Norwegian input layout. USB follows the
currently selected Bluetooth profile. The active selection persists, and the
firmware listener reapplies it after reconnection or reboot.

The visible semantic output is the same on both hosts. Their HID chords are
not. `:host-profiles/:emissions` records every host-sensitive chord, including
apostrophe/pipe/acute, arrow macros, all SYM punctuation, dollar/euro morph,
literal dead keys, and the fenced-block macro. Windows also has three explicit
path overrides for the quote dance, dash dance, and one-shot SYM backing
layer. This keeps the display and reverse stroke lookup semantic while still
preserving the firmware evidence.

## `layout.edn` schema, format version 1

The sibling `layout.edn` is a complete format-version-1 example. Its root is a
single EDN map with these fields:

```clojure
{:pretypist/format-version 1
 :package/id "stable-machine-id"
 :package/name "Human readable name"
 :package/revision 1
 :package/author "Author"

 :layout/languages #{:en :nb}
 :layout/default-profile :mac-no
 :layout/default-layer :base
 :layout/layer-order [:base :num :sym :nav :mouse]
 :layout/host-selection {...}
 :layout/firmware-layer-indices {...}

 :theme
 {:version 1
  :schemes
  {:dark {:background "#090a0f"
          :foreground "#f1eee7"
          :muted-foreground "#686a75"
          :accent "#8b83ff"
          :error "#b96f72"
          :focus "#aaa4ff"
          :prose-current "#f1eee7"
          :prose-completed "#74757e"
          :prose-upcoming "#686a75"
          :panel "#111218"}
   :light {:background "#f7f6f2"
           :foreground "#111216"
           :muted-foreground "#616269"
           :accent "#5b50c8"
           :error "#994b52"
           :focus "#493db9"
           :prose-current "#111216"
           :prose-completed "#6b6c72"
           :prose-upcoming "#595a62"
           :panel "#ebe9e3"}}
  :layer-colors {:base "#c8c4bd"
                 :num "#d6a75d"
                 :sym "#8b83ff"
                 :nav "#6297d5"
                 :mouse "#54aaa2"}}

 :model {:file "model.glb"
         :material-policy :preserve
         :root-node "keyboard"
         :left-half-node "half_left"
         :right-half-node "half_right"
         :case-nodes ["case_left" "case_right"]
         :key-node-prefix "key_"
         :units :meters
         :up-axis :y
         :embedded-resources-only true
         :required-nodes [...]}

 :batteries
 {:left  {:kind :li-po :telemetry :simulated}
  :right {:kind :li-po :telemetry :simulated}}

 :displays
 {:left {:kind :nice-view
         :node "display_screen_left"
         :role :central
         :resolution [160 68]
         :surface-size-mm [10.9 25.5]
         :orientation :portrait
         :template :zmk/nice-view-status-v0.3
         :battery :left
         :widgets [:battery :endpoint :bluetooth-profiles :wpm :layer]}
  :right {:kind :nice-view
          :node "display_screen_right"
          :role :peripheral
          :resolution [160 68]
          :surface-size-mm [10.9 25.5]
          :orientation :portrait
          :template :zmk/nice-view-peripheral-v0.3
          :battery :right
          :widgets [:battery :split-connection :peripheral-art]}}

 :display/default-status
 {:source :simulated
  :batteries {:left {:percent 82 :usb-powered? false}
              :right {:percent 76 :usb-powered? false}}
  :endpoint {:transport :ble :profile 0}
  :bluetooth-profiles
  [{:index 0 :selected? true :bonded? true :connected? true}
   {:index 1 :selected? false :bonded? false :connected? false}
   {:index 2 :selected? false :bonded? false :connected? false}
   {:index 3 :selected? false :bonded? false :connected? false}
   {:index 4 :selected? false :bonded? false :connected? false}]
  :wpm {:current 42 :history [0 0 12 18 24 31 36 39 42 42]}
  :layer :base
  :split-connection :connected
  :peripheral-art :balloon}

 :keys [{:id :k00
         :index 0
         :node "key_00"
         :hand :left
         :row 0
         :column 0
         :finger :left-pinky
         :firmware-position 1
         :observed-codes ["KeyB"]}
        ...]

 :behaviors {:behavior-id {...}}
 :layers {:base {:name "Base"
                 :color "#f1eee7"
                 :bindings {:k00 {:legend "B"
                                  :actions {:tap [:text "b"]}}
                            ...}}
          ...}
 :combos [{:id :combo-id :layers #{:mouse} :keys [:k20 :k29] ...}]
 :strokes {"b" [[:tap :k00]]
           "B" [[:one-shot :k30] [:tap :k00]]
           "å" [[:hold :k34] [:tap :k22] [:release :k34]]
           ...}
 :host-profiles {:mac-no {...} :win-no {...}}}
```

Physical keys and layer bindings are separate. Every layer binding is keyed
by a stable physical key ID; display `:legend` is separate from semantic
`:actions`; each key ID and numeric index is unique; and every key has an
explicit GLB node. A binding may also carry `:firmware-action`,
`:firmware-behavior`, or `:firmware-emissions` when preserving low-level ZMK
details matters.

The version-1 action vocabulary used by this package is:

| Shape | Meaning |
|---|---|
| `[:text s]` | Emit Unicode text |
| `[:dead-key s]` | Emit a composable dead key |
| `[:key key-id]` | Non-text keyboard key |
| `[:modifier modifier-id]` | Modifier while held |
| `[:one-shot :modifier modifier-id]` | Sticky one-shot modifier |
| `[:one-shot :layer layer-id]` | Sticky one-shot layer |
| `[:layer :momentary layer-id]` | Active while held |
| `[:layer :toggle layer-id]` | Toggle persistent layer state |
| `[:macro macro-id]` | Named declarative macro from `:behaviors` |
| `[:transparent]` | Resolve through the next lower active layer |
| `[:disabled]` | No output |
| `[:media operation]` | Consumer/media action |
| `[:mouse operation direction-or-button]` | Pointer action |
| `[:bluetooth :select-profile index]` | Select a persisted ZMK profile |
| `[:bluetooth :clear-selected-profile]` | Clear only the active profile |

An `:actions` map can use `:tap`, `:hold`, `:double-tap`, `:double-hold`, and
`:shift-tap`. The associated declarative behavior determines timing and
resolution. This represents tap, hold, double-tap, double-hold, one-shot,
layer, toggle, modifier, disabled, and transparent behavior without code or
custom reader forms.

`:strokes` is the reverse semantic-output-to-physical-gesture index used by
the layer trainer. Its gesture vocabulary is `[:tap key-id]`,
`[:hold key-id]`, `[:release key-id]`, `[:double-tap key-id]`, and
`[:double-hold key-id]`, plus `[:one-shot key-id]` for sticky modifiers and
layers. The complete data includes lowercase and uppercase English letters,
Norwegian letters, digits, whitespace, punctuation, and the two arrow macros.

Host emission values are inert declarative data. They use `[:key ...]`,
`[:chord ...]`, `[:sequence ...]`, `[:press ...]`, and `[:release ...]` to
record the ZMK HID sequence. PRETYPIST never evaluates EDN and accepts no
tagged/custom reader forms.

## nice!view templates and telemetry boundary

The display configuration is data, not executable drawing code. Version 1
allows only PRETYPIST's reviewed nice!view templates. Each entry maps a stable
display ID to a named GLB surface, role, native pixel resolution, physical
active-area size, orientation, battery ID, and an exact widget set. Import
validation reports failures at the corresponding EDN path and also requires
every mapped display node to exist uniquely in the GLB.

At runtime the left central template shows battery/USB power, endpoint and five
Bluetooth profiles, PRETYPIST WPM, and the demonstrated layer. The right
peripheral template shows its independent battery, split connection, and
illustration. The surfaces are event-driven CanvasTextures rather than baked
model artwork, so the GLB remains blank and replaceable.

Only WPM and the demonstrated layer come from real PRETYPIST state. Browser
keyboard events do not expose ZMK battery, USB-power, Bluetooth bond, or split
telemetry, so package defaults for those fields must declare
`:telemetry :simulated` / `:source :simulated`; the display renders a small
`SIM` marker. `:usb-powered?` means VBUS is present and does not claim that
charge current was measured.

The `display/` directory contains the exact MIT-licensed ZMK `v0.3` sources,
their hashes and provenance, and a notice. The asset generator verifies that
evidence before extracting only the two reviewed packed 1-bit bitmaps into a
deterministic ClojureScript namespace; it neither compiles nor executes C:

```sh
npm run verify:zmk-display
npm run generate:zmk-display-assets
npm run check:zmk-display-assets
node scripts/verify-zmk-display.mjs --check --zmk-root /path/to/zmk
```

The optional checkout comparison accepts only the pinned commit. Arbitrary
`status.c` interpretation is intentionally not part of package loading or the
normal build; a new firmware widget requires a reviewed, allowlisted template.

## Browser observation boundary

Web keyboard events reveal host output, not arbitrary internal firmware-layer
thumb timing. `:observed-codes` lists only browser codes that can reasonably
identify a physical key after the firmware emits a corresponding HID usage.
Layer holds, one-shots, tap-dance classification, and multi-key gestures are
therefore shown from `:strokes` as **expected/inferred gestures**. They are not
claimed as raw hardware telemetry.

## Rebuilding the compatibility adaptation

```sh
npm run generate:model
npm run audit:model
node scripts/adapt-authored-model.mjs --check
npm run package:layout
```

The adapter preserves the entire source BIN chunk, positions, materials and
images. `model-adaptation.json` records all 36 key mappings, both display
anchors and dimensions, and exact source/output hashes. Changing the source
requires reviewing its names and geometry costs before updating the pinned
hash. No Blender installation or external reference repository is needed for
normal builds.

The legacy Blender reconstruction can still be regenerated with
`npm run author:model:legacy`; its outputs stay under `legacy/` and cannot
replace the selected authored asset.
