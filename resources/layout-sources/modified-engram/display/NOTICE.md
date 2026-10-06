# Third-party notice: ZMK nice!view widget

The files below are unmodified excerpts of ZMK Firmware release `v0.3`, commit
`edf5c0814fd3ea202e43aad2d68fd32e882a518c`, from
<https://github.com/zmkfirmware/zmk>:

- `app/boards/shields/nice_view/nice_view.overlay`
- `app/boards/shields/nice_view/widgets/art.c`
- `app/boards/shields/nice_view/widgets/bolt.c`
- `app/boards/shields/nice_view/widgets/peripheral_status.c`
- `app/boards/shields/nice_view/widgets/peripheral_status.h`
- `app/boards/shields/nice_view/widgets/status.c`
- `app/boards/shields/nice_view/widgets/status.h`
- `app/boards/shields/nice_view/widgets/util.c`
- `app/boards/shields/nice_view/widgets/util.h`

The ZMK Contributors' copyright and `SPDX-License-Identifier: MIT` notices are
retained verbatim in each source file. The complete upstream MIT license is
preserved at `vendor/zmk-v0.3/LICENSE`.

PRETYPIST retains these files as reviewable evidence for the stock 160 × 68
nice!view status layout. They are not compiled, executed, shipped in the
`.pretypist` archive, or loaded as a runtime dependency. A deterministic build
step verifies the pinned evidence and extracts the two packed one-bit artwork
payloads from `art.c` into the compiled application. PRETYPIST's authored model
remains a visual proxy and is not manufacturing CAD.
