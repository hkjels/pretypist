# Development

For a first local run, see [Install and use](../README.md#install-and-use).

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

## Development server

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
npm run package:example
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
glass apertures, physical pivots and PBR textures are preserved.
`author:model:legacy` rebuilds only the previous procedural Blender model
under `legacy/`. See [the built-in keyboard](built-in-keyboard.md) for the
model and display pipelines.
`audit:model` reads the finished GLB directly and reports its structural
contract and geometry cost without loading WebGL.
`verify:zmk-display` verifies the separately vendored, pinned ZMK v0.3 display
sources and the visual constants transcribed from them; it never compiles or
executes C.
`package:layout` deterministically packages the editable Modified Engram
sources. `package:example` generates and packages the small one-piece example.
`prepare:assets` performs corpus analysis, synchronization, model
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

To run the Playwright browser suite, install Chromium once, then build and
run the tests:

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
full offline release audit. The complete static site is `resources/public`.
Preview it locally with:

```sh
node scripts/serve.mjs 4173
```

Then open <http://127.0.0.1:4173>.

The build output can be deployed to a static HTTPS host. To use
[GitHub Pages](https://docs.github.com/en/pages/getting-started-with-github-pages/creating-a-github-pages-site),
publish `resources/public` as the site artifact. The current build assumes it
is served at the origin root. For a Pages project site under `/pretypist/`,
first make the asset URLs, manifest, and service worker scope use that base path.

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
