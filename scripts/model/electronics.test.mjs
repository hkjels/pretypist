import assert from "node:assert/strict";
import test from "node:test";

import {
  CHOCOFI_V1_ELECTRONICS_ANCHORS_MM,
  JST_XH_STYLE_PLACEMENT,
  NICE_NANO_V2_DIMENSIONS,
  NICE_VIEW_DIMENSIONS,
  assertAssembly,
  assertGeometry,
  createJstXhMatedPlugGeometry,
  createJstXhSideEntryHeaderGeometry,
  createJstXhStyleAssembly,
  createNiceNanoV2Assembly,
  createNiceNanoBoardGeometry,
  createNiceViewAssembly,
  geometryBounds,
} from "./electronics.mjs";

const tolerance = 1e-9;

function assertDimensions(actual, expected, label) {
  assert.equal(actual.length, expected.length);
  actual.forEach((value, index) => {
    assert.ok(
      Math.abs(value - expected[index]) <= tolerance,
      `${label} axis ${index}: expected ${expected[index]}, got ${value}`,
    );
  });
}

test("all exported electronics assemblies contain valid referenced geometry", () => {
  assertAssembly(createNiceNanoV2Assembly(), "niceNano");
  assertAssembly(createNiceViewAssembly(), "niceView");
  assertAssembly(createJstXhStyleAssembly(), "jstXh");
});

test("nice!nano and nice!view board envelopes retain their documented dimensions", () => {
  const nano = geometryBounds(assertGeometry(createNiceNanoBoardGeometry()));
  assertDimensions(nano.dimensions, [
    NICE_NANO_V2_DIMENSIONS.board.width,
    NICE_NANO_V2_DIMENSIONS.board.thickness,
    NICE_NANO_V2_DIMENSIONS.board.depth,
  ], "nice!nano board");

  const displayAssembly = createNiceViewAssembly();
  const view = geometryBounds(displayAssembly.geometries.board);
  assertDimensions(view.dimensions, [
    NICE_VIEW_DIMENSIONS.board.width,
    NICE_VIEW_DIMENSIONS.board.thickness,
    NICE_VIEW_DIMENSIONS.board.depth,
  ], "nice!view board");
});

test("JST header and plug are separate, dimensioned meshes pointing toward the inner edge", () => {
  const header = geometryBounds(createJstXhSideEntryHeaderGeometry());
  const plug = geometryBounds(createJstXhMatedPlugGeometry());
  const expectedHeader = JST_XH_STYLE_PLACEMENT.headerBody.overallDimensions;
  const expectedPlug = JST_XH_STYLE_PLACEMENT.matedPlug.dimensions;

  assertDimensions(header.dimensions, expectedHeader, "JST header");
  assertDimensions(plug.dimensions, expectedPlug, "JST plug");
  assert.ok(JST_XH_STYLE_PLACEMENT.headerBody.offsetFromPad1[0] > 0);
  assert.ok(JST_XH_STYLE_PLACEMENT.matedPlug.offsetFromPad1[0] >
    JST_XH_STYLE_PLACEMENT.headerBody.offsetFromPad1[0]);
  assert.ok(JST_XH_STYLE_PLACEMENT.wireExit.red[0] >
    JST_XH_STYLE_PLACEMENT.matedPlug.offsetFromPad1[0]);
  assert.equal(JST_XH_STYLE_PLACEMENT.pad2Offset[2], -0.00254);
});

test("J3 placement agrees with pinned board edge and polarity", () => {
  const anchors = CHOCOFI_V1_ELECTRONICS_ANCHORS_MM;
  assert.equal(anchors.batteryJ3Pad1[0], anchors.batteryJ3Pad2[0]);
  assert.ok(anchors.batteryJ3Pad2[1] < anchors.batteryJ3Pad1[1]);
  const edgeDistance = anchors.innerBoardEdgeAtJ3 - anchors.batteryJ3Pad1[0];
  assert.ok(Math.abs(edgeDistance * 0.001 -
    JST_XH_STYLE_PLACEMENT.innerBoardEdgeOffsetFromPad1) <= tolerance);
});
