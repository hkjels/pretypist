# PRETYPIST

PRETYPIST is a prose-first typing trainer for English and Norwegian Bokmål.
Practice reading ahead, typing in phrases, and using layers with feedback from
a 3D keyboard. It comes with Modified Engram for the 36-key Chocofi and supports
[other keyboard layouts](docs/keyboard-integration.md).

## Install and use

To run locally, you’ll need Node.js 20 or newer and the Clojure CLI with a JDK
(17 or newer recommended). From this directory, run:

```sh
npm ci
npm run build
node scripts/serve.mjs 4173
```

Open <http://127.0.0.1:4173>, choose your language, and follow the first-run
guide or start a Flow session. See the [practice guide](docs/training.md) for
the other training modes.

In a supported browser, install PRETYPIST from the browser’s app menu or choose
**Install Pretypist** after a session. Once Settings says **Available offline**,
you can practice without a connection. Progress stays on your device.

See the [docs](docs/README.md) for keyboard integration and development.

## License

The app code is licensed under the [MIT License](LICENSE).
See the [Chocofi hardware notice](resources/layout-sources/modified-engram/upstream/chocofi-case-v1/NOTICE.md)
and [ZMK display notice](resources/layout-sources/modified-engram/display/NOTICE.md)
for the bundled assets.
