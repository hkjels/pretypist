import { test, expect } from "@playwright/test";
import { build } from "esbuild";
import { readFile, mkdir } from "node:fs/promises";

// Review-only scene: same tone mapping, room environment and three-point colors
// as the application, with close cameras that let us inspect physical surfaces.
const viewer = `
import * as T from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
const renderer = new T.WebGLRenderer({antialias:true, preserveDrawingBuffer:true});
renderer.setSize(1100,800); renderer.setPixelRatio(1);
renderer.outputColorSpace=T.SRGBColorSpace;
renderer.toneMapping=T.ACESFilmicToneMapping; renderer.toneMappingExposure=.84;
document.body.appendChild(renderer.domElement);
const scene=new T.Scene(); scene.background=new T.Color('#14151a');
const room=new RoomEnvironment(), generator=new T.PMREMGenerator(renderer);
const environment=generator.fromScene(room,.04); scene.environment=environment.texture;
scene.environmentIntensity=.46; room.dispose(); generator.dispose();
scene.add(new T.HemisphereLight(0xfff7eb,0x111216,.44));
for(const [color,power,position] of [[0xffe2c2,2.1,[-.24,.34,.26]], [0xe9edf2,1,[.27,.2,.18]], [0xd5dde7,.6,[.04,.25,-.28]]]) {
  const light=new T.DirectionalLight(color,power); light.position.set(...position);scene.add(light);
}
const camera=new T.PerspectiveCamera(32,1100/800,.0001,2);
const loaded=await new GLTFLoader().loadAsync('/surface-model.glb');
scene.add(loaded.scene);
loaded.scene.traverse(n=>{if(n.material?.transparent)n.material.depthWrite=false;});
const views={
  caps:{position:[-.13,.048,.037],target:[-.116,.014,-.001]},
  fasteners:{position:[-.018,.045,.027],target:[-.043,.017,.012]},
  usb:{position:[-.028,.037,-.071],target:[-.043,.012,-.034]},
  full:{position:[0,.25,.32],target:[0,.009,0]}
};
window.surfaceReview={show(view){camera.position.set(...views[view].position); camera.lookAt(...views[view].target);renderer.render(scene,camera);},
  audit(){const materials=[];loaded.scene.traverse(n=>{if(n.material)materials.push(n.material)});
    return {shafts:loaded.scene.getObjectsByProperty('isMesh',true).filter(n=>n.name.includes('screw_shaft')).length,
      detailed:materials.filter(m=>m.normalMap&&m.roughnessMap).length,
      conductors:materials.filter(m=>m.metalness===1).length};}};
window.surfaceReview.show('full'); document.body.dataset.ready='true';
`;

test("refined GLB surfaces render with embedded PBR maps and glass fasteners", async ({ page }) => {
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("console", message => { if (message.type() === "error") errors.push(message.text()); });
  const bundle = await build({ stdin: { contents: viewer, resolveDir: process.cwd(), sourcefile: "surface-review.js" },
    bundle: true, write: false, format: "esm", platform: "browser" });
  const refined = await readFile("resources/layout-sources/modified-engram/model.glb");
  const original = await readFile("resources/layout-sources/modified-engram/authored/model.glb");
  let model = original;
  await page.route("**/surface-model.glb", route => route.fulfill({contentType:"model/gltf-binary",body:model}));
  await page.route("**/surface-review.js", route => route.fulfill({contentType:"text/javascript",body:Buffer.from(bundle.outputFiles[0].contents)}));
  await page.route("**/surface-review", route => route.fulfill({contentType:"text/html",body:
    '<!doctype html><html><body style="margin:0;background:#14151a"><script type="module" src="/surface-review.js"></script></body></html>'}));
  await page.setViewportSize({width:1100,height:800});
  await mkdir("target/model-review/surface-refinements", {recursive:true});
  for (const [name, data] of [["before",original],["after",refined]]) {
    model = data;
    await page.goto("/surface-review");
    await expect(page.locator("body")).toHaveAttribute("data-ready", "true");
    if (name === "after") {
      const audit = await page.evaluate(() => window.surfaceReview.audit());
      expect(audit.shafts).toBe(4); expect(audit.detailed).toBeGreaterThan(10); expect(audit.conductors).toBeGreaterThanOrEqual(10);
    }
    for (const view of ["full","caps","fasteners","usb"]) {
      await page.evaluate(view => window.surfaceReview.show(view), view);
      await page.screenshot({path:`target/model-review/surface-refinements/${name}-${view}.png`});
    }
  }
  expect(errors).toEqual([]);
});
