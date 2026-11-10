# The `.pretypist` package format

This is the reference for version 1. See [Add a keyboard](keyboard-integration.md)
for the authoring and import steps.

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

## `layout.edn` schema

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
| `:strokes` | Optional reverse map from emitted text to expected physical gesture steps; enables gesture practice |
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
