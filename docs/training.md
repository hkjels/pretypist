# Practice guide

Start by choosing English or Norwegian Bokmål and confirming your keyboard
layout. The optional first-run calibration moves from ungraded prose to phrase
groups, followed by layer gestures in prose if the layout maps them. You can
skip calibration or resume at a session boundary.

## Training modes

- **Flow** — type natural prose and review speed, accuracy, corrections, and rhythm.
- **Horizon** — practice reading ahead with progressively hidden text. More
  demanding masking unlocks when your baseline speed and accuracy support it.
- **Chunks** — type prose in short phrase groups and compare pauses within and
  between phrases.
- **Layers** — explore a layout, practice a gesture with hints, recall it, and
  then use it in fresh prose. The built-in course covers `æ`, `ø`, `å`,
  one-shot Shift, punctuation dances, and NUM/SYM transitions.

Difficulty changes one thing at a time. If a session is difficult, PRETYPIST
can keep the level steady and offer another passage focused on the same skill.
The completion view shows a rhythm line and suggests a focus when it has
enough evidence. Returning users can continue, repeat, or pick a different mode.

Settings includes manual difficulty, passage length, appearance, scene
lighting, and layout selection. To practice on another keyboard, see
[how to import a layout](keyboard-integration.md#import-an-existing-package).

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
