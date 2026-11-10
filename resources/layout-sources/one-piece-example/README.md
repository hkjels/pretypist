# One-Piece Example

A fictional 31-key QWERTY board with one case, a base layer, and no display or
gesture mappings. It shows the minimum model hierarchy for an unsplit layout.
Browser key codes still map to the model; text practice works with any keyboard.
Calibration uses the Flow and Chunks sessions and skips the gesture session.

The model is original procedural geometry. The layout and model are generated
by `scripts/generate-example-keyboard.mjs`; run `npm run package:example` from
the repository root to regenerate the files and build the `.pretypist` archive.
