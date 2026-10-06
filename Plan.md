# PRETYPIST product and implementation plan

## Direction

PRETYPIST should feel more ambitious and magical without becoming a game HUD.
The public-facing journey may be theatrical; the training surface should remain
quiet enough for sustained reading and typing.

The next phase has three connected goals:

1. Turn session measurements into a clear, trustworthy learning loop.
2. Introduce the product through a memorable 3D landing and onboarding journey.
3. Let layout packages carry a safe visual identity as well as geometry and
   behavior.

Complexity should be progressive rather than exposed as settings. PRETYPIST
may make sophisticated decisions internally, but it should explain only the
one decision that matters to the next run.

## Implementation status

Milestones A through E now form a complete learning-loop slice:

- [x] Progress schema version 2 migrates version 1 data and carries a bounded
  performance profile.
- [x] Completed events compact into character, boundary, observed physical-key,
  inferred stroke, and inferred layer-transition evidence. Startup reading
  time is excluded from character skill latency.
- [x] Confidence-weighted weakness scoring selects at most one deterministic
  focus and preserves the observed/inferred distinction.
- [x] Completion presents a pure Replicant monochrome rhythm chart, keyboard-
  focusable notable markers, one improvement, one focus, and one next step.
- [x] “Consolidate this” starts fresh relevant prose with the difficulty
  snapshot from before adaptive progression; completing it cannot increase
  difficulty.
- [x] Focused tests cover migration, profile bounds, EWMA behavior, Unicode,
  chart geometry, prescriptions, fresh-passage selection, and the
  consolidation invariant. Browser coverage exercises the complete handoff.
- [x] Declarative layout strokes normalize into ordered illustrative timelines
  for every schema gesture kind, including explicit safety releases.
- [x] Nexus sends one coarse playback command while Three.js owns timing, key
  travel, cancellation, and reduced-motion stepped highlights.
- [x] Guided and Delayed hints can replay expected movement; relevant prose and
  layer-practice errors replay it automatically, while Recall remains blank.
- [x] Layer practice remembers the character just completed and offers an
  immediate bridge into fresh, coherent prose containing that output.
- [x] A deterministic build task enriches every authored passage with
  reviewable reading, phrase, punctuation, Unicode, n-gram, inferred layer,
  hand-alternation, and repeated-finger metadata.
- [x] Automatic selection scores weakness relevance, current difficulty,
  editorial tags, prior exposure, useful novelty, repetition, and unrelated
  gesture pressure with deterministic tie-breaking.
- [x] Each selected passage carries one active dimension, concrete evidence,
  and localized “why this passage” copy; consolidation uses its captured
  pre-progression difficulty.
- [x] The original corpus now contains nine English and eight Norwegian scenes,
  including natural transfer material for capitals, dialogue, digits,
  Norwegian letters, NUM, SYM, and punctuation.
- [x] New users begin with a versioned, resumable layout/language introduction
  followed by ungraded Flow, visibly grouped Chunks, and layer-to-prose
  transfer calibration. Existing users migrate directly to normal practice.
- [x] Onboarding exposes observed browser input separately from inferred
  firmware gestures, introduces the monochrome rhythm trace without a public
  score, and previews Horizon only when the ordinary adaptive evidence gate is
  satisfied.
- [x] Interruption restarts only the unfinished short passage; completed
  checkpoints and gesture steps persist. Users may skip onboarding without
  bypassing Horizon's evidence requirement.

The current profile deliberately stores boundary classes rather than an
unbounded history of phrases. Word- and authored-chunk-specific long-lived
evidence can be added later only if bounded aggregation proves useful.

## Product principles

- Prose remains the primary object. Visual effects support anticipation,
  rhythm, and physical understanding rather than competing with the text.
- Adaptation is legible. Every changed run answers “why this, why now?” in one
  sentence.
- Increase exactly one difficulty dimension after a successful run. Failure
  produces consolidation, never visible demotion.
- Prefer coherent passages over generated drills. Weak characters, gestures,
  and transitions influence selection, but do not turn the experience into
  word soup.
- Distinguish observed browser input from inferred keyboard gestures everywhere.
- Keep ordinary settings compact. Expert controls belong behind the existing
  manual-difficulty override or authoring tools, not in the primary UI.
- Motion, WebGL, and custom package themes are enhancements. Reading and typing
  must remain functional without them.
- Store all learning data locally. No account, analytics service, or remote
  inference is required.

## End-to-end journey

```text
Landing story
    ↓
Choose built-in layout or import .pretypist
    ↓
Three-session calibration and onboarding
    ↓
Normal practice session
    ↓
Quiet completion analysis
    ↓
One explained next step
    ↓
Consolidation or slightly harder practice
```

Returning users should see a prominent “Continue training” path. They may
revisit the landing story, but should never be forced through it again.

## 1. Adaptive consolidation

### Performance profile

Extend persisted progress from a single baseline and mode difficulty into a
small evidence model. Keep aggregates bounded and versioned.

Track confidence-weighted performance for:

- characters and punctuation
- words and explicit phrase chunks
- physical key IDs when browser codes make them observable
- inferred strokes such as one-shot Shift, NUM + letter, and SYM + punctuation
- transitions between base, NUM, SYM, NAV, and MOUSE
- pauses within chunks, at chunk boundaries, and at word boundaries
- correction sequences and repeated stalls

Each record should contain a sample count, exponentially weighted latency,
error rate, last-practised time, and confidence. Do not preserve an unbounded
event history in `localStorage`; retain detailed events only for the active
session and compact them at completion.

### Weakness scoring

Use declarative weights to combine:

- normalized latency against the user's recent baseline
- error and correction rate
- stall frequency
- recency and confidence
- whether the issue interrupts a phrase rather than occurring at a natural
  boundary

A low-confidence observation should not immediately define the next session.
Repeated evidence should gradually outweigh novelty. Tests must prove that one
bad or unusually fast run cannot dominate the profile.

### Consolidation selection

At completion, choose at most one primary weakness and one supporting context.
Examples:

- `NUM → å` is slow inside Norwegian words.
- Commas cause a pause before the next phrase.
- One-shot Shift is accurate but interrupts rhythm.
- The user is reading one word at a time in Horizon.

The next run should reuse the weakness in fresh, coherent phrases at a familiar
speed. It should not increase any other difficulty dimension. A successful
consolidation run may then return to normal progression.

Suggested semantic actions include:

```clojure
[:profile/session-analyzed summary]
[:adaptation/next-run-selected prescription]
[:session/start-prescription prescription]
[:session/consolidation-complete prescription-id]
```

Nexus expansions decide what to practise. Corpus loading, persistence, clocks,
and scene playback remain effects.

## 2. Completion view and monochrome chart

The completion view should feel like a short reflection, not a report card.

Show:

- WPM and accuracy as quiet summary values
- one sentence describing the strongest improvement
- one sentence naming the largest useful weakness
- a monochrome line chart across the completed passage
- the proposed next-run change
- “Continue” and “Consolidate this” actions when applicable

### Chart design

Use a pure Replicant SVG chart rather than Three.js or an imperative charting
dependency. The horizontal axis represents progress through the passage. The
primary line represents a smoothed rhythm measure, preferably rolling
characters-per-minute or inverse latency. A faint baseline represents the
user's recent normal pace.

Sparse markers identify:

- errors and corrections
- stalls
- chunk boundaries
- inferred layer gestures

Do not draw dense axes, tooltips, gradients, or multiple saturated series. The
chart must remain understandable in monochrome and expose an accessible text
summary. A keyboard user should be able to move through notable events and read
their phrase, latency, and classification.

The chart calculation belongs in a pure `pretypist.typing.analysis` namespace
and should accept a completed session plus declarative sampling parameters.

## 3. Gesture timelines and replay

Convert layout strokes into a normalized, pure timeline representation:

```clojure
{:character "å"
 :source :inferred
 :steps [{:at-ms 0   :kind :hold :key-id :num-thumb}
         {:at-ms 140 :kind :tap  :key-id :aa-key}
         {:at-ms 260 :kind :release :key-id :num-thumb}]}
```

Support tap, hold, release, double-tap, double-hold, one-shot, modifier, layer,
toggle, disabled, and transparent behavior. Tap dances need two visibly
separate actuations; one-shots need an activation pulse followed by the target
key. Unknown timing should use reviewed illustrative defaults and must never be
presented as measured firmware telemetry.

The Three.js controller should receive one coarse `play-gesture` command and
own the animation loop. Application state records playback status, not every
animation frame. Provide replay in Guided, Delayed hint, completion analysis,
and immediately after a relevant error. Respect reduced motion by replacing
travel animation with stepped highlights.

## 4. Layer-to-prose transfer

Every isolated layer exercise should end with a transfer option. Transfer
material must be natural prose containing the trained output several times in
different contexts.

Examples:

- `æ`, `ø`, and `å` → a short Norwegian scene rather than a list of words
- one-shot Shift → sentence openings and proper names
- comma/period tap dance → clauses with varied phrase lengths
- apostrophe/quote behavior → short dialogue in English
- NUM/SYM transitions → dates, measurements, and ordinary punctuation

The transfer result should feed the same weakness profile as Flow and Chunks,
while retaining the originating gesture ID for analysis.

## 5. Autonomous passage selection

Do not add a panel of corpus filters. Enrich passages at build time and let the
selector work within an automatic difficulty budget.

### Passage metadata

Add or derive tags for:

- language and approximate reading complexity
- phrase and chunk lengths
- punctuation types and density
- letter, n-gram, and Norwegian-character frequencies
- required layout strokes and layer transitions
- hand alternation and repeated-finger pressure when a layout can resolve it
- sentence length and syntactic variety
- prior exposure

Store authored chunks and semantic tags in the EDN corpus. Generate mechanical
statistics with a deterministic build command so they remain reviewable and do
not slow the browser.

### Selector

Score eligible passages using:

```text
weakness relevance
+ appropriate difficulty
+ coherence/editorial quality
+ useful novelty
- excessive repetition
- simultaneous difficulty changes
```

The result is a prescription containing the passage, target evidence, active
difficulty dimension, and a human-readable explanation. Keep scoring weights
as declarative data with deterministic tie-breaking and focused tests.

## 6. Landing page and scroll-driven 3D story

The landing page appears before onboarding and establishes three ideas:

1. PRETYPIST trains reading rhythm and phrases, not random words.
2. It understands layers and inferred physical gestures.
3. The user may bring any self-contained `.pretypist` layout package.

### Story structure

Use a small number of full-viewport chapters with restrained copy:

1. **Read beyond the caret.** One keyboard half rests close to camera while a
   prose phrase advances behind it.
2. **Learn the movement beneath the text.** Scrolling rotates the half toward
   a top view; keys and layer gestures illuminate in sequence.
3. **Keep the rhythm through layers.** The second half enters, the split opens,
   and NUM/SYM transitions travel across the board.
4. **Bring your own keyboard.** The model separates into named keys, layers,
   display surfaces, and theme colors, then resolves back into the assembled
   keyboard.
5. **Begin quietly.** The full board settles into the trainer's menu camera and
   presents “Start with Modified Engram” and “Import my layout.”

This is the appropriate place to over-engineer camera movement, lighting cues,
key choreography, and display animation. Copy should remain short enough that
the scene tells half the story.

### Scroll architecture

Replicant owns the landing DOM and accessible chapter content. A landing scene
service owns scroll-linked interpolation and the animation frame. It may keep
the latest scroll target in the mutable system map, but must not dispatch 60
Nexus state changes per second.

Intersection observers dispatch semantic chapter changes such as:

```clojure
[:landing/chapter-entered :movement]
[:landing/import-requested]
[:onboarding/start {:layout-id "modified-engram"}]
```

The scene controller receives coarse chapter poses and interpolates between
them from local scroll progress. Loading, listener cleanup, visibility pause,
resize handling, and GLB disposal follow the existing scene-controller rules.

### Accessibility and performance

- Reduced motion uses crossfades or static chapter poses and ordinary document
  scrolling.
- All product claims and actions exist in DOM text without WebGL.
- Scrolling must never be trapped.
- Keyboard controls can advance chapters and reach both calls to action.
- Use the existing GLB rather than a second hero model.
- Defer model loading until the landing canvas approaches the viewport.
- Clamp pixel ratio and stop rendering after interpolation settles.
- Establish performance budgets for JavaScript, GLB size, first interaction,
  and sustained frame time on an ordinary integrated GPU.

Routing may initially be simple application state (`:landing`, `:onboarding`,
`:trainer`) without adding a routing library. A persisted `:intro-seen?` flag
changes the primary returning-user CTA but does not remove access to the story.

## 7. Three-session onboarding and calibration

Onboarding follows the landing CTA and should take three short sessions.

### Step 0: layout and language

- Confirm Modified Engram or import a `.pretypist` package.
- Preview the keyboard in 3D.
- Choose English or Norwegian Bokmål.
- Explain that browser input observes emitted characters while layer gestures
  are inferred from the package.

### Session 1: natural baseline

A short Flow passage with no masking establishes accuracy, character latency,
WPM, correction behavior, and ordinary word-boundary pauses. Do not grade or
compare the user publicly.

### Session 2: phrase rhythm

A short Chunks passage with visible two-to-four-word grouping compares pauses
inside and between phrases. Introduce the completion line chart.

### Session 3: keyboard familiarity

Use a few layout-specific gestures followed immediately by natural transfer
prose. If accuracy and baseline thresholds are met, preview gentle Horizon
fading; otherwise keep everything visible and revisit later.

At the end, summarize the starting profile in plain language and propose the
first normal run. Allow skipping calibration, but keep aggressive Horizon
stages locked until there is sufficient evidence.

## 8. More magic in the application

The trainer can be more expressive without becoming noisy:

- Carry the landing camera seamlessly into onboarding and the menu.
- Let the keyboard subtly “breathe” into clarity when a session pauses, then
  settle back when typing resumes.
- Animate inferred gestures as light moving through a sequence rather than
  simultaneous glowing keys.
- Give correct key travel a tiny material response, not a neon flash.
- Let nice!view surfaces reflect the current chapter, onboarding step, layer,
  or completion summary.
- Use restrained layout-specific colors in focus rings, caret, layer legends,
  and scene accents.
- Introduce gentle scene composition changes between Flow, Horizon, Chunks,
  and Layers instead of adding interface furniture.

Avoid depth of field in prose modes because it previously produced rims and
edge artifacts. Prefer authored camera position, material separation, subtle
normal/roughness maps, improved ambient occlusion, and better contact shadows.
Any optional flourish must have a static reduced-motion equivalent.

## 9. Layout-owned theme tokens

Version 1 packages can gain an optional, backward-compatible `:theme` map in
`layout.edn`; no additional root file is necessary. A package must never carry
arbitrary CSS, selectors, fonts, URLs, shaders, or executable expressions.

Implemented schema:

```clojure
:theme
{:version 1
 :schemes
 {:dark
  {:background "#090a0f"
   :foreground "#f1eee7"
   :muted-foreground "#686a75"
   :accent "#8b83ff"
   :error "#b96f72"
   :focus "#aaa4ff"
   :prose-current "#f1eee7"
   :prose-completed "#74757e"
   :prose-upcoming "#686a75"
   :panel "#111218"}

  :light
  {:background "#f7f6f2"
   :foreground "#111216"
   :muted-foreground "#616269"
   :accent "#5b50c8"
   :error "#994b52"
   :focus "#493db9"
   :prose-current "#111216"
   :prose-completed "#6b6c72"
   :prose-upcoming "#595a62"
   :panel "#ebe9e3"}}

 :layer-colors
 {:base "#c8c4bd"
  :num "#d6a75d"
  :sym "#8b83ff"
  :nav "#6297d5"
  :mouse "#54aaa2"}}
```

### Validation and application

- Allow only documented semantic token keys.
- Accept opaque `#RRGGBB` colors initially; reject CSS functions and all URL
  forms.
- Reject unknown nested keys, excessive strings, and non-map schemes with an
  exact EDN path.
- Calculate minimum text/background and focus/background contrast. Reject
  unreadable core pairs rather than silently changing the author's identity.
- Merge omitted optional tokens with PRETYPIST defaults.
- A package may provide dark, light, or both schemes. Missing schemes fall back
  entirely to the corresponding PRETYPIST scheme rather than mechanically
  inverting colors.
- Keep the user preference global: `:system`, `:dark`, or `:light`. Do not add
  separate appearance controls per layout.
- Project the selected scheme into a fixed set of `--pt-*` custom properties
  on the application root. Replicant still owns the DOM attributes.
- Send layer colors and reviewed scene accents to Three.js as a coarse theme
  command. Do not let packages set exposure, post-processing, shader source,
  or arbitrary material parameters.
- Store the resolved theme in immutable application state; keep media-query
  listeners in an effect/service.

Add authoring diagnostics that render both schemes, show contrast failures,
and take deterministic preview screenshots. Package import should preview the
theme before installation.

## 10. Session continuity

Add a small home-state continuation message based on recent local summaries:

> Three focused sessions this week. Comma transitions are becoming steadier.

Prefer cadence over streaks. Missing a day should have no negative language or
visual penalty. Useful actions are “Continue,” “Repeat last focus,” and “Choose
a mode.” Keep only enough weekly metadata to compute bounded trends.

The landing page's returning-user CTA should lead directly here. Completion
should preserve the next prescription so closing the browser does not lose the
learning thread.

## 11. Input and browser hardening

Expand unit and browser coverage for:

- Norwegian `æ`, `ø`, and `å` as direct and composed input
- dead-key composition and IME composition boundaries
- one input event containing multiple Unicode code points
- Backspace during and after an error
- held Backspace and key-repeat timing
- focus loss, tab switching, and focus restoration
- paste, drop, autocorrect, and replacement input
- mobile or unsupported virtual keyboards failing gracefully
- Meta/Control/Alt browser shortcuts remaining untouched
- Space never being confused with the string `"Space"`
- page scrolling suppressed only while active typing requires it
- imported layouts with missing observed key codes

Browser tests should cover both languages, all four modes, onboarding, a
consolidation run, theme import, reduced motion, WebGL failure, and persistence
migration.

## Delivery sequence

Each milestone should be a complete usable slice with tests and documentation.

### Milestone A: analysis foundation

- Add versioned performance-profile and prescription data.
- Create pure event-to-analysis functions and bounded persistence migration.
- Add weakness scoring and deterministic tests.
- Preserve current UI behavior until prescriptions are available.

### Milestone B: completion and consolidation

- Add the monochrome rhythm chart and accessible event summary.
- Explain one improvement and one weakness.
- Start a real consolidation run from completion.
- Prove no difficulty increase occurs during consolidation.

### Milestone C: gesture playback and transfer

- [x] Normalize strokes into timelines.
- [x] Add scene `play-gesture`/cancel commands and reduced-motion behavior.
- [x] Add error replay and layer-to-prose transfer prescriptions.

### Milestone D: corpus intelligence

- [x] Add deterministic passage analysis and reviewable tags.
- [x] Implement automatic prescription-based passage scoring.
- [x] Expand original English and Norwegian transfer material.

### Milestone E: onboarding

- [x] Add layout/language introduction and the three calibration sessions.
- [x] Persist completion and interruption safely.
- [x] Gate Horizon masking with evidence rather than a manual prompt.

### Milestone F: package themes

- [x] Finalize the optional theme schema and exact-path validation.
- [x] Add dark/light resolution, contrast checks, import preview, and CSS projection.
- [x] Move Modified Engram's current palette into its package as the reference
  implementation.

### Milestone G: landing story

- [x] Build accessible chapter markup and calls to action first.
- [x] Add the scroll service and chapter camera poses.
- [x] Choreograph the split-half model, gestures, displays, and theme reveal.
- [x] Tune performance and reduced-motion behavior before adding further effects.

### Milestone H: continuity, hardening, and visual finish

- [x] Add the gentle weekly continuation surface.
- [x] Complete the Unicode/input/browser matrix.
- [x] Add subtle roughness/normal detail and ambient-occlusion improvements.
- [x] Run production performance, accessibility, disposal, and package-security
  audits.

Milestone H keeps latency timing monotonic while adding real wall-clock session
dates for a bounded seven-day continuation view. Claims require enough recent
evidence, never use streak language, and preserve the next prescription across
browser restarts. The input boundary now normalizes NFC, commits IME text once,
handles virtual Backspace deliberately, blocks replacement edits visibly, and
leaves browser shortcuts untouched.

The built-in revision-8 key mesh adds compact UVs for tiny deterministic
roughness/normal variation, plus disposable model-owned contact occlusion under
mapped keys. It uses no post-processing. The release command now enforces a
same-origin browser shell and CSP, canonical self-contained package structure,
GLB contract/topology, generated-asset freshness, and artifact budgets. The
six-case browser suite covers both languages, every mode, onboarding,
consolidation, imports/themes, reduced motion, persistence migration, IME/edit
intent, and usable prose with WebGL deliberately unavailable.

## Definition of done

This phase is complete when a new user can discover PRETYPIST through the
landing story, import or choose a layout, complete calibration, receive a
specific evidence-backed recommendation, replay an inferred gesture, practise
it in coherent prose, and return later to continue that exact learning thread.

The experience must work in English and Norwegian Bokmål, with reduced motion,
without WebGL, and with an unthemed version 1 package. A themed package must be
unable to inject CSS or remote resources. All adaptive decisions, theme
resolution, chart data, gesture timelines, persistence migrations, and package
validation must be directly unit-testable; the complete journey needs a
browser-level smoke test.
