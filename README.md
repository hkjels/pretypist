# PRETYPIST

PRETYPIST is a quiet, prose-first typing trainer for English and Norwegian
Bokmål. It is designed for small split keyboards with blank keycaps and
firmware-level layers: the trainer keeps the text visually primary while a
persistent 3D keyboard provides restrained physical feedback.

The first vertical slice includes four complete training modes:

- **Flow** — natural prose, live five-characters-per-word WPM, accuracy,
  corrections, per-character latency, pauses, hesitation, and rhythm metrics.
- **Horizon** — calibrated read-ahead practice with progressive, non-punitive
  masking. Aggressive current-word masking stays locked until the user's
  baseline speed and accuracy support it.
- **Chunks** — coherent prose split into two-to-five-word phrase units, with
  separately measured pauses inside chunks and at chunk boundaries.
- **Layers** — Explore, Guided, Delayed hint, Recall, and prose Transfer stages
  on the same 3D keyboard. The built-in course covers `æ`, `ø`, `å`, one-shot
  Shift, punctuation dances, and NUM/SYM transitions.

Difficulty adapts in one small dimension after a successful run. Completion,
accuracy, recent baseline speed, and repeated stalls all matter; a failed run
holds the level and offers consolidation instead of visibly demoting the user.
Each completed session is also compacted into a versioned, bounded performance
profile. The completion view plots a monochrome rhythm line, explains one
evidence-backed focus, and can start fresh relevant prose with the previous
difficulty held fixed. Observable browser key codes and inferred layout strokes
remain explicitly separate in both the profile and UI.
Returning users with recent evidence see a quiet weekly continuation surface:
cadence rather than streaks, a claim only when enough samples support it, the
preserved next focus, and direct Continue/Repeat/Choose-mode actions. Session
dates use wall-clock time while typing latencies remain on the browser's
monotonic clock.
UI copy is localized with m1p, and both included corpora contain original,
phrase-chunked prose. A deterministic corpus build derives reviewable reading,
punctuation, n-gram, layout-transition, and finger-pressure metadata. The pure
selector then chooses a passage from weakness relevance, current difficulty,
editorial tags, and prior exposure, and explains its single active dimension.

On a new install, a compact three-session calibration journey first confirms
language and layout, explains observed input versus inferred firmware gestures,
then moves through ungraded Flow, visible phrase groups, and three layer
gestures transferred immediately into prose. Its versioned checkpoint resumes
at safe session boundaries. Existing users bypass it automatically, and
skipping it never bypasses Horizon's evidence gate.

Before that calibration, a five-chapter landing story introduces read-ahead,
gesture learning, layer rhythm, and portable layout packages. Native document
scrolling drives authored camera and split-half poses on the same persistent
keyboard used by the trainer. The chapter state is semantic and versioned;
per-frame interpolation stays in a mutable scene service. Returning users open
the trainer immediately and can revisit the story through the PRETYPIST
wordmark without losing their current cursor position.

Language and mode stay in one persistent header. The typing surface reserves
its own space above the keyboard, highlights the remaining current word, and
keeps passage-selection explanations behind a disclosure. Settings are grouped
into Practice, Appearance, Keyboard, and Progress; manual difficulty and scene
lighting are available without crowding the default view. First-run telemetry
details use the same accessible disclosure pattern. The landing story keeps
copy beside the keyboard on desktop and above it on narrow screens.

The built-in layout is **Modified Engram** for a 36-key Chocofi. Its editable
data was transcribed from the authoritative ZMK configuration and is committed
to this repository. Building or running PRETYPIST does not read or depend on
the reference repository.

## Prerequisites

- Node.js 20 or newer, with npm
- The Clojure CLI (`clojure`), running on a current JDK (JDK 17 or newer is
  recommended)

Install the pinned JavaScript dependency tree from the lockfile:

```sh
npm ci
```

Clojure and ClojureScript dependencies are pinned in `deps.edn`; npm packages
are pinned in `package.json` and `package-lock.json`.

## Development

```sh
npm run dev
```

This prepares the checked-in resources, starts the cljs-reload browser REPL and
static server, watches ClojureScript and CSS, and normally opens
<http://127.0.0.1:8080>. The project uses cljs-reload directly; it has no
shadow-cljs configuration.

Useful asset commands:

```sh
npm run analyze:corpora
npm run check:corpora
npm run sync:corpora
npm run generate:model
npm run author:model:legacy
npm run audit:model
npm run audit:landing
npm run audit:release
npm run verify:zmk-display
npm run package:layout
npm run prepare:assets
```

`analyze:corpora` reads the authored English and Norwegian EDN plus the
Modified Engram layout and writes deterministic metadata to
`resources/corpora/metadata.edn`. `check:corpora` fails when that committed
output is stale. Mechanical fields describe inferred layout requirements, not
observed input.
`generate:model` adapts the preserved user-supplied `authored/model.glb` into
PRETYPIST's node and animation contract, then applies reviewable cap, fastener,
and material refinements from `surface-refinements.json`. The original source
remains intact. A separate runtime pass reduces selected surfaces to a 50,000
triangle budget, bakes case shading normals, and compacts buffers. Hardware,
glass apertures, physical pivots and PBR textures are preserved. `author:model:legacy` rebuilds
only the previous procedural Blender model under `legacy/`.
`audit:model` reads the finished GLB directly and reports its structural
contract and geometry cost without loading WebGL.
`verify:zmk-display` verifies the separately vendored, pinned ZMK v0.3 display
sources and the visual constants transcribed from them; it never compiles or
executes C.
`package:layout` deterministically packages the editable Modified Engram
sources. `prepare:assets` performs corpus analysis, synchronization, model
adaptation, display generation, and layout packaging in build order.
`audit:release` is the offline release gate: it checks the local-only browser
shell and Content Security Policy, revalidates the deterministic package and
self-contained GLB, enforces geometry and artifact budgets, and verifies that
generated corpus and nice!view assets are current.

## Tests

Run the ClojureScript unit and package tests with:

```sh
npm test
```

The suite compiles for Node and covers package parsing and validation, GLB
contracts, unsafe input, Unicode typing and correction behavior, timing and
metrics, progress migration, bounded performance analysis, weakness scoring,
chart geometry, deterministic corpus analysis, explainable selection,
consolidation invariants, calibration, adaptive progression, legend resolution,
weekly-continuity evidence, shared-resource disposal, and reverse stroke lookup.
It also runs dependency-free Node tests for the GLB geometry
auditor, electronics assembly, and pinned ZMK display verifier. Their focused
commands are `npm run test:model-audit`, `npm run test:model-electronics`, and
`npm run test:zmk-display`.

There is also a browser-level Playwright smoke test. Install its browser once,
then build and run it:

```sh
npx playwright install chromium
npm run build
npm run test:browser
```

The smoke tests open the app, confirm the built-in package and explainable
passage selection loaded, start Flow, enter text, open Settings, enter the
Layers trainer, replay an inferred gesture, transfer it into relevant prose,
and verify a useful error for an invalid package. The first-run path also walks
all five landing chapters, verifies their scroll-linked state, and chooses the
built-in layout before onboarding. A second path completes a
corrected run, verifies the rhythm analysis, and starts the offered
consolidation session. A third covers the complete first-run journey, including
Flow and Chunks calibration, the rhythm chart, `æ`/`ø`/`å` gestures, immediate
natural-prose transfer, completion persistence, and returning-user boot.
Additional paths cover direct and decomposed Norwegian input, IME boundaries,
blocked replacement edits, focus recovery, all four modes, reduced motion,
version-1 persistence migration, the weekly continuation UI, horizontal
overflow, accessible icon controls, and a deliberately unavailable WebGL
context while prose typing remains functional.
UI regression paths additionally cover both color schemes, narrow layouts,
the persistent canvas, current-word contrast, and settings focus trapping with
collapsed controls. They save visual checkpoints under `target/ui-review/`.
The surface-model check loads both the untouched source GLB and the refined
asset with the real browser GLTFLoader. It checks embedded PBR texture loading
and the four glass fasteners, and saves matched full-keyboard and close-up
renders under `target/model-review/surface-refinements/`.

## Production build

```sh
npm run build
```

The build prepares resources, compiles optimized ClojureScript, bundles
the browser code with esbuild into `resources/public/js/app.js`, and runs the
full offline release audit. The complete
static site is `resources/public`. Preview it locally with:

```sh
node scripts/serve.mjs 4173
```

Then open <http://127.0.0.1:4173>.

### Installed app and offline practice

The production site is an installable PWA. Install it from the browser's app
menu, or use the quiet **Install Pretypist** action offered after a completed
session in supporting browsers. Settings also exposes installation and offline
readiness. Serve the deployed site over HTTPS; localhost works for previews.

The first successful visit caches the complete app, both prose corpora, and the
built-in keyboard package. Imported keyboards remain in IndexedDB and practice
progress stays in localStorage. Once Settings says **Available offline**, the
app can be closed and reopened without a network connection.

Each production build fingerprints its offline assets and generates `sw.js`.
An update downloads a complete new release before it is offered. Failed
downloads retain the previous working release. Updates wait for an explicit
**Update Pretypist** action between sessions; active or paused practice and
onboarding never expose a reload action. Development builds do not register a
service worker, keeping the browser REPL and hot reload live.

The editable icon source is `resources/public/icons/mark.svg`. PNG app,
maskable, Apple touch, and favicon exports are checked in; regenerate them with
`npm run generate:icons` when changing the vector (requires `rsvg-convert` from
librsvg). Ordinary builds need no icon rasterizer. The same mark appears in the
header and loading state.

The keyboard sits on a procedural, antialiased drafting grid in its own 3D
scene. The grid fades at its edges, follows the scene camera, derives its color
from the selected theme, and becomes quieter during prose practice. It adds one
small plane and no image downloads or continuous idle animation.

## Architecture

PRETYPIST has one immutable application snapshot and a small system map for
mutable browser services:

```text
DOM event / timer / file / scene callback
                 │
                 ▼
        data action, e.g. [:typing/input ...]
                 │
                 ▼
       Nexus pure expansion (decision/state)
                 │
          declarative effects
       ┌─────────┼──────────┐
       ▼         ▼          ▼
 Replicant   persistence   Three.js controller
 whole-root     / I/O       coarse commands
   render
```

- **Replicant owns the DOM.** UI namespaces return Hiccup as pure functions of
  state, m1p resolves translation markers, and `replicant/render` renders the
  entire root. Event handlers contain data, not imperative callbacks. The
  persistent canvas is mounted and disposed through Replicant lifecycle hooks.
- **Nexus owns behavior.** Semantic actions expand into data effects. Expansion
  functions make state transitions and decisions without browser I/O; effects
  handle storage, file reads, clocks, focus, and scene commands. DOM events and
  timestamps enter through Nexus placeholders, and an after-dispatch
  interceptor coalesces state changes into one safe render microtask.
- **Three.js owns animation.** The controller encapsulates the renderer,
  loaders, PMREM studio environment, contact shadows, scene objects, key
  animation, camera interpolation, resizing, visibility handling, legends,
  nice!view surfaces, and disposal. It
  renders on demand while idle and continuously only during a transition, key
  response, or Explore drag. Layer Explore supports clamped two-axis pointer
  and arrow-key orbiting. Nexus sends coarse commands such as mode, layer,
  expected gesture, whole gesture timeline, display status, press/release,
  intensity, orbit, and camera reset; the scene loop never drives
  application-state updates. The transparent canvas is rendered directly so
  the keyboard silhouette and contact shadows composite cleanly over the page.
- **Landing scroll is a bounded mutable service.** Replicant renders five
  accessible full-viewport sections and ordinary links. An IntersectionObserver
  dispatches only semantic chapter changes; a local animation frame smooths one
  `0..4` progress value and sends it straight to the scene controller. It is
  installed and removed through Replicant lifecycle actions. Reduced motion
  rounds progress to five stable compositions, with no scroll trapping or
  per-pixel rotation.
- **Mutable objects stay out of state.** The system map holds atoms for the
  scene controller, IndexedDB handle, active package buffers, timers, and DOM
  root. The Nexus-facing snapshot remains ordinary immutable Clojure data.
- **Persistence is split by size.** Versioned settings, adaptive progress,
  recent summaries, landing/onboarding checkpoints, and installed-layout metadata use `localStorage`. Imported
  ZIP, EDN, preview, and GLB buffers use the versioned `pretypist-layouts`
  IndexedDB database. Migration functions form an explicit version boundary.
- **Input is Unicode-first.** A visually hidden, accessible textarea handles
  text input; keydown/keyup add timing and observable physical-key feedback.
  Input and prose normalize to NFC, intermediate IME composition is ignored
  until commit, virtual-keyboard deletion shares the deliberate Backspace
  path, and multi-code-point input remains ordered. Paste, drop, autocorrect
  replacement, undo/redo, and line insertion are blocked during practice with
  a localized explanation. Browser shortcuts using Meta, Control, or Alt are
  left alone.

The main namespaces are organized by responsibility:

| Area | Location | Responsibility |
|---|---|---|
| Entry and system | `src/pretypist/core.cljs`, `app.cljs` | Startup, hot reload, mutable service map |
| Actions and effects | `src/pretypist/nexus.cljs` | Nexus expansions, effects, placeholders, render boundary |
| UI and localization | `src/pretypist/ui*.cljs`, `src/pretypist/i18n.cljs` | Replicant views, controls, m1p dictionaries |
| Landing story | `src/pretypist/landing.cljc`, `ui/landing.cljs` | Versioned route state, accessible chapters, layout CTA, scroll lifecycle |
| First-run journey | `src/pretypist/onboarding.cljc`, `ui/onboarding.cljs` | Pure resumable calibration state and Replicant onboarding view |
| Typing domain | `src/pretypist/typing/` | Input progression, metrics, bounded performance analysis, chunks, calibration, adaptation |
| Corpus intelligence | `src/pretypist/corpus/`, `scripts/pretypist/corpus_build.clj` | Deterministic metadata and pure explainable passage scoring |
| Layout packages | `src/pretypist/layout/` | EDN schema helpers, validation, ZIP/GLB parsing, IndexedDB |
| Display projection | `src/pretypist/display/` | Pure projection of app WPM/layer and explicit simulated device status |
| 3D scene | `src/pretypist/scene/` | Persistent Three.js controller, model lifecycle, legends, nice!view CanvasTextures |
| Source content | `resources/corpora/`, `resources/layout-sources/` | Reviewable EDN and package source assets |

If WebGL initialization or model rendering fails, Replicant's text trainer
continues to work and presents a small fallback message.

### Landing performance budgets

`npm run audit:landing` enforces budgets on the production artifacts: optimized
JavaScript must remain below 420 KiB gzip, the compact runtime GLB below
1.5 MiB, and the complete built-in package below 0.75 MiB. Revision 14 reduces
the keyboard to 49,186 triangles and losslessly compresses the package. The adapter audits
the full-detail source before optimization and pins both stages' geometry costs.
The current renderer also clamps
device pixel ratio to 2, stops requesting frames after interpolation settles,
and pauses when the document is hidden. The runtime targets a usable first DOM
interaction within two seconds on an ordinary current laptop and a 95th
percentile scroll-frame cost below 20 ms on an integrated GPU; those two
hardware-dependent targets are checked during release profiling rather than by
the deterministic artifact audit.

### Corpus metadata and selection

`resources/corpora/en.edn` and `nb.edn` remain the editorial sources of truth.
Their phrase chunks and semantic `:tags` are hand-authored. The generated
`resources/corpora/metadata.edn` has a versioned envelope:

```clojure
{:corpus/metadata-version 1
 :layout/id "modified-engram"
 :layout/revision 14
 :generated/by "npm run analyze:corpora"
 :passages
 {:en/example
  {:text {...}
   :chunks {...}
   :reading {...}
   :syntax {...}
   :punctuation {...}
   :letters {...}
   :ngrams {...}
   :layout {...}}}}
```

The layout section counts inferred stroke classes and layer transitions, plus
resolved hand alternation and repeated-finger pressure. It is generated for
the built-in Modified Engram revision named in the envelope. Language-level
statistics remain valid with imported layouts; PRETYPIST does not pretend the
built-in mechanical analysis applies to a different keyboard.

The browser merges metadata onto passages after loading. A pure selector scores
focus occurrences, fit with the one current difficulty dimension, reviewed
prose tags, novelty and prior exposure, over-repetition, and unrelated complex
gesture pressure. Stable passage IDs break ties. Its result contains the chosen
passage, score components, target evidence, one `:active-dimension`, and m1p
copy explaining the choice. Focus matches are recalculated from the actual
sentence-safe trimmed prose, so a target cannot exist only in text omitted from
the run.

## The `.pretypist` package format

A `.pretypist` file is a ZIP container with MIME intent
`application/vnd.pretypist+zip`. Version 1 is self-contained and permits only
these files at the archive root:

| File | Required | Purpose |
|---|---:|---|
| `layout.edn` | yes | Declarative layout, behavior, stroke, model, battery, and display mapping |
| `model.glb` | yes | Binary glTF 2.0 keyboard model with embedded resources |
| `preview.webp` | no | Package preview image reserved for package browsers |
| `README.md` | no | Human-readable package notes |

There may be no duplicate, nested, or unexpected entries. Imported packages
pass exactly the same parser and validation pipeline as the built-in package.
The browser reads the ZIP with JSZip, parses EDN with
`cljs.reader/read-string`, inspects the GLB container and embedded glTF JSON,
then asks `GLTFLoader` to parse the model before installation.

Version 1 treats every package as untrusted data:

- tagged/custom EDN reader forms and discard forms are rejected; nothing is
  evaluated
- absolute paths, parent traversal, backslash paths, and remote/data/blob URLs
  are rejected
- physical key IDs, indices, and model-node mappings must be unique
- referenced keys, layers, behaviors, combos, host emissions, and defaults
  must exist
- required and mapped display GLB names must exist exactly once, and each mapped key node must
  contain a renderable mesh
- GLB buffers and images may not use URI resources; all binary data and
  textures must be embedded
- limits are 1 MiB for `layout.edn`, 30 MiB for `model.glb`, 40 MiB compressed,
  60 MiB total uncompressed, 256 physical keys, and 16 KiB per string

Validation errors retain an exact EDN-style path and are shown as readable
messages in Settings.

### `layout.edn` schema

The root value is one EDN map. Its sections are:

| Path | Meaning |
|---|---|
| `:pretypist/format-version` | Required package schema version; version 1 is supported |
| `:package/*` | Stable string ID, display name, integer revision, author, and optional license metadata |
| `:layout/languages` | Supported corpus languages, currently `:en` and/or `:nb` |
| `:layout/default-profile` | Key in `:host-profiles` |
| `:layout/default-layer` | Key in `:layers` |
| `:layout/layer-order` | Display order for semantic layers |
| `:layout/host-selection` | Declarative mapping from a firmware/profile selector to host profiles |
| `:layout/firmware-layer-indices` | Optional evidence mapping semantic names to firmware indices |
| `:theme` | Optional validated dark/light semantic colors and layer accents; no CSS or executable values |
| `:model` | Package filename, named-node contract, units, and axis |
| `:batteries` | Optional stable battery capabilities and their explicitly declared telemetry source |
| `:displays` | Optional allowlisted display template, GLB node, role, dimensions, battery, and widgets |
| `:display/default-status` | Optional validated preview values for battery, endpoint, profiles, WPM, layer, split link, and peripheral art |
| `:keys` | Physical keys with stable IDs, numeric indices, named GLB nodes, geometry metadata, and optional observable browser codes |
| `:behaviors` | Named hold-tap, tap-dance, mod-morph, macro, and safety behavior data |
| `:layers` | Semantic layers whose bindings map physical key IDs to separate display legends and actions |
| `:combos` | Optional multi-key gestures and their layer/timing constraints |
| `:strokes` | Reverse map from emitted text to expected physical gesture steps |
| `:host-profiles` | Host-specific firmware layers, overrides, and inert HID-emission descriptions |

A compact but complete version-1 package definition looks like this (the
corresponding GLB must provide every named node):

```clojure
{:pretypist/format-version 1

 :package/id "compact-example"
 :package/name "Compact Example"
 :package/revision 1
 :package/author "Example Author"
 :package/license "CC0-1.0"

 :layout/languages #{:en}
 :layout/default-profile :generic
 :layout/default-layer :base
 :layout/layer-order [:base :sym]
 :layout/host-selection
 {:kind :manual
  :persistent true
  :profiles {0 :generic}}
 :layout/firmware-layer-indices {:base 0 :sym 1}

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

 :model
 {:file "model.glb"
  :root-node "keyboard"
  :left-half-node "half_left"
  :right-half-node "half_right"
  :case-nodes ["case_left" "case_right"]
  :key-node-prefix "key_"
  :units :meters
  :up-axis :y
  :embedded-resources-only true
  :required-nodes
  ["keyboard" "half_left" "half_right" "case_left" "case_right"
   "display_screen_left"
   "key_00" "key_01" "key_02" "key_03"]}

 :batteries
 {:left {:kind :li-po :telemetry :simulated}}

 :displays
 {:left
  {:kind :nice-view
   :node "display_screen_left"
   :role :central
   :resolution [160 68]
   :surface-size-mm [11.3 26.5]
   :orientation :portrait
   :template :zmk/nice-view-status-v0.3
   :battery :left
   :widgets [:battery :endpoint :bluetooth-profiles :wpm :layer]}}

 :display/default-status
 {:source :simulated
  :batteries {:left {:percent 82 :usb-powered? false}}
  :endpoint {:transport :ble :profile 0}
  :bluetooth-profiles
  [{:index 0 :selected? true :bonded? true :connected? true}
   {:index 1 :selected? false :bonded? false :connected? false}
   {:index 2 :selected? false :bonded? false :connected? false}
   {:index 3 :selected? false :bonded? false :connected? false}
   {:index 4 :selected? false :bonded? false :connected? false}]
  :wpm {:current 0 :history [0 0 0 0 0 0 0 0 0 0]}
  :layer :base}

 :keys
 [{:id :k00 :index 0 :node "key_00"
   :hand :left :row 0 :column 0 :finger :left-index
   :observed-codes ["KeyA"]}
  {:id :k01 :index 1 :node "key_01"
   :hand :left :row 0 :column 1 :finger :left-thumb
   :observed-codes ["Period" "Comma"]}
  {:id :k02 :index 2 :node "key_02"
   :hand :right :row 0 :column 0 :finger :right-thumb}
  {:id :k03 :index 3 :node "key_03"
   :hand :right :row 0 :column 1 :finger :right-thumb
   :observed-codes ["ShiftRight"]}]

 :behaviors
 {:letter-mod
  {:type :hold-tap
   :flavor :balanced
   :tapping-term-ms 230
   :quick-tap-ms 150
   :tap [:text "a"]
   :hold [:modifier :left-control]}

  :punctuation-dance
  {:type :tap-dance
   :flavor :tap-preferred
   :tapping-term-ms 230
   :slots [:tap :hold :double-tap :double-hold]}

  :sym-latch
  {:type :hold-tap
   :flavor :tap-preferred
   :tapping-term-ms 230
   :tap [:one-shot :layer :sym]
   :hold [:layer :toggle :sym]}}

 :layers
 {:base
  {:name "Base"
   :color "#f1eee7"
   :bindings
   {:k00 {:legend "A / Ctrl"
          :behavior :letter-mod
          :actions {:tap [:text "a"]
                    :hold [:modifier :left-control]}}
    :k01 {:legend ". / ,"
          :behavior :punctuation-dance
          :actions {:tap [:text "."]
                    :hold [:disabled]
                    :double-tap [:text ","]
                    :double-hold [:disabled]}}
    :k02 {:legend "SYM*"
          :behavior :sym-latch
          :actions {:tap [:one-shot :layer :sym]
                    :hold [:layer :toggle :sym]}}
    :k03 {:legend "OS Shift"
          :actions {:tap [:one-shot :modifier :right-shift]}}}}

  :sym
  {:name "SYM"
   :color "#8b83ff"
   :bindings
   {:k00 {:legend "!"
          :actions {:tap [:text "!"]}
          :firmware-emissions {:tap :exclamation}}
    :k01 {:legend "▽"
          :actions {:tap [:transparent]}
          :firmware-action [:transparent]
          :inherited-from [:base :k01]}
    :k02 {:legend "Exit" :actions {:tap [:layer :toggle :sym]}}
    :k03 {:legend "" :actions {:tap [:disabled]}}}}}

 :combos
 [{:id :escape
   :layers #{:base}
   :keys [:k00 :k01]
   :timeout-ms 50
   :action [:key :escape]}]

 :strokes
 {"a" [[:tap :k00]]
  "A" [[:one-shot :k03] [:tap :k00]]
  "." [[:tap :k01]]
  "," [[:double-tap :k01]]
  "!" [[:one-shot :k02] [:tap :k00]]}

 :host-profiles
 {:generic
  {:name "Generic host"
   :os :generic
   :input-layout :us
   :firmware-layers {:base :base :symbols :sym}
   :overrides []
   :emissions {:exclamation [:chord :left-shift :n1]}}}}
```

The source of truth for the full 36-key real-world example is
`resources/layout-sources/modified-engram/layout.edn`.

Layer action vectors are declarative. Version 1 supports these forms used by
the built-in package:

| Shape | Meaning |
|---|---|
| `[:text "…"]` | Emit Unicode text |
| `[:dead-key "…"]` | Emit a composable dead key |
| `[:key :enter]` | Emit a non-text keyboard key |
| `[:modifier :left-alt]` | Modifier while held |
| `[:one-shot :modifier :left-shift]` | Sticky one-shot modifier |
| `[:one-shot :layer :sym]` | Sticky one-shot layer |
| `[:layer :momentary :nav]` | Layer active while held |
| `[:layer :toggle :num]` | Toggle persistent layer state |
| `[:macro :format-block]` | Refer to a named declarative macro behavior |
| `[:transparent]` | Resolve through the next lower active layer |
| `[:disabled]` | Deliberately do nothing |
| `[:media :volume-up]` | Consumer/media action |
| `[:mouse :move :left]` | Pointer or scroll action |
| `[:bluetooth :select-profile 0]` | Select a firmware Bluetooth profile |

Bindings select actions by gestures such as `:tap`, `:hold`, `:double-tap`,
`:double-hold`, and `:shift-tap`. Timings and interpretation belong in a named
behavior rather than executable code. Display `:legend` is always separate
from output.

The reverse `:strokes` index accepts expected-gesture steps such as
`[:tap :k00]`, `[:hold :k34]`, `[:release :k34]`, `[:double-tap :k14]`,
`[:double-hold :k24]`, `[:one-shot :k32]`, and `[:layer :num]`. These are used
for teaching and visualization. Host-profile emission descriptions are also
inert data and may contain `[:key ...]`, `[:chord ...]`, `[:sequence ...]`,
`[:press ...]`, and `[:release ...]`.

## Initial Modified Engram package

Editable sources are kept at:

```text
resources/layout-sources/modified-engram/
├── layout.edn
├── model.glb
├── model-adaptation.json
├── runtime-optimization.json
├── authored/model.glb
├── legacy/model.blend
├── legacy/model-topology.json
├── README.md
├── display/
│   ├── provenance.json
│   ├── NOTICE.md
│   └── vendor/zmk-v0.3/
└── upstream/chocofi-case-v1/
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

### Chocofi key-index and GLB contract

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

In package revision 4, the current firmware's left thumbs remain explicitly
`k30` one-shot Shift, `k31` Space/NAV, and `k32` one-shot SYM. The Windows host
profile changes the SYM backing layer at `k32`; it does not move the key.

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
The package source README records dimensions, assumptions, and geometry costs.

`:model :material-policy` accepts `:normalized` (the default) or `:preserve`.
The supplied model opts into preservation, retaining the authored albedo,
roughness, transparency, textures, and flat/smooth shading. Key materials are
cloned per physical key so a press can glow independently. Transparent covers
do not write opaque depth over the live screen. Legends are placed above the
actual keycap bounds, including models that nest millimetre geometry beneath
metre-scale pivots.

### nice!view status surfaces

Package revision 11 maps the two named display meshes declaratively. The left
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

### Rebuilding the case evidence meshes from STEP

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

### Model adaptation and audit

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

## Creating and importing another layout

1. Create a source directory containing `layout.edn` and a self-contained
   `model.glb`; optionally add `preview.webp` and `README.md`.
2. Give the package a stable, unique `:package/id` and start at
   `:package/revision 1`. Define physical keys independently from layer
   bindings, and map every key explicitly to a named, renderable GLB object.
3. Define semantic layers and legends, then add `:strokes` for every character
   or multi-character output the Layers trainer should visualize. Put
   host-specific HID details in `:host-profiles`, not in display legends.
4. Build a deterministic package with the repository tool:

   ```sh
   node scripts/package-layout.mjs \
     --source resources/layout-sources/my-layout \
     --output resources/public/layouts/my-layout.pretypist
   ```

5. Check the resulting archive:

   ```sh
   node scripts/package-layout.mjs \
     --output resources/public/layouts/my-layout.pretypist \
     --check
   ```

6. In PRETYPIST, open **Settings**, choose **Import layout**, and select the
   `.pretypist` file. PRETYPIST validates the package first and previews both
   color schemes without storing the archive. Choose **Install layout** only
   after reviewing that preview. The confirmed package is stored in IndexedDB
   and appears under Active keyboard layout. Imported layouts can be selected
   or removed there; the built-in Modified Engram package remains available.

The package script enforces the canonical root files and size limits, while the
browser importer performs the full semantic EDN and GLB validation. Always test
an archive in the app before distributing it.

Run `npm run preview:themes` after a production build to exercise the real
browser import preview and write deterministic dark, light, and combined
palette captures to `target/theme-previews/`. The preview reports the lowest
validated core contrast ratio for each scheme.

### Package-owned themes

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

## Browser input and inferred gestures

A browser sees the host-facing result of keyboard firmware. It cannot reliably
observe an internal layer-thumb hold, one-shot consumption, tap-dance decision,
or the precise timing of a firmware macro. `KeyboardEvent.code` can illuminate
a directly observable physical key when the layout maps that code, and the
textarea records the Unicode text the browser actually received.

For layered characters and gestures, PRETYPIST resolves the expected sequence
from `layout.edn`'s `:strokes` map and visualizes it as an **inferred expected
gesture**. This is instructional layout knowledge, not raw hardware telemetry.
The distinction is intentional: typing results and browser events are observed;
firmware-layer mechanics are inferred from the installed declarative layout.

Expected strokes are normalized into an illustrative press/release timeline.
Guided and Delayed layer hints expose replay, relevant mistakes can trigger a
restrained replay, and completion analysis can replay the selected inferred
gesture. Three.js receives the timeline once and owns its animation; with
reduced motion enabled, the same sequence uses stepped highlights without key
travel. Reviewed presentation timings are deliberately not described as
firmware timing.

After a correct isolated layer exercise, PRETYPIST remembers the output that
was just trained and offers to place it into fresh prose. The transfer run uses
the normal typing and analysis pipeline while retaining its originating focus,
so isolated movement practice reconnects immediately to reading rhythm.
