export const surfaceViewer = `
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
  full:{position:[0,.25,.32],target:[0,.009,0]},
  front:{position:[-.1,.025,.072],target:[-.108,.005,.04]},
  rear:{position:[-.13,.045,-.1],target:[-.1,.009,-.024]},
  underside:{position:[-.1,-.085,.025],target:[-.115,.005,.004]},
  thumb:{position:[.035,.035,.078],target:[.055,.007,.026]}
};
window.surfaceReview={show(view){camera.position.set(...views[view].position); camera.lookAt(...views[view].target);renderer.render(scene,camera);},
  audit(){const materials=[];loaded.scene.traverse(n=>{if(n.material)materials.push(n.material)});
    return {shafts:loaded.scene.getObjectsByProperty('isMesh',true).filter(n=>n.name.includes('screw_shaft')).length,
      detailed:materials.filter(m=>m.normalMap&&m.roughnessMap).length,
      conductors:materials.filter(m=>m.metalness===1).length};}};
window.surfaceReview.show('full'); document.body.dataset.ready='true';
`;
