/** @externs */

// Properties supplied by Three.js, GLTFLoader and JSZip. They cross the
// Closure-compiled ClojureScript / esbuild module boundary and therefore must
// keep their public JavaScript names under :advanced optimization.
var PretypistExternal = function() {};

PretypistExternal.SRGBColorSpace;
PretypistExternal.ACESFilmicToneMapping;

PretypistExternal.prototype.scene;
PretypistExternal.prototype.children;
PretypistExternal.prototype.position;
PretypistExternal.prototype.rotation;
PretypistExternal.prototype.quaternion;
PretypistExternal.prototype.scale;
PretypistExternal.prototype.geometry;
PretypistExternal.prototype.boundingBox;
PretypistExternal.prototype.matrixWorld;
PretypistExternal.prototype.material;
PretypistExternal.prototype.skeleton;
PretypistExternal.prototype.source;
PretypistExternal.prototype.data;
PretypistExternal.prototype.isMesh;
PretypistExternal.prototype.emissive;
PretypistExternal.prototype.emissiveIntensity;
PretypistExternal.prototype.intensity;
PretypistExternal.prototype.opacity;
PretypistExternal.prototype.transparent;
PretypistExternal.prototype.depthWrite;
PretypistExternal.prototype.castShadow;
PretypistExternal.prototype.receiveShadow;
PretypistExternal.prototype.outputColorSpace;
PretypistExternal.prototype.toneMapping;
PretypistExternal.prototype.toneMappingExposure;
PretypistExternal.prototype.needsUpdate;
PretypistExternal.prototype.enabled;
PretypistExternal.prototype.theta;
PretypistExternal.prototype.phi;
PretypistExternal.prototype.radius;

PretypistExternal.prototype.add = function(var_args) {};
PretypistExternal.prototype.remove = function(var_args) {};
PretypistExternal.prototype.traverse = function(callback) {};
PretypistExternal.prototype.getObjectByName = function(name) {};
PretypistExternal.prototype.setHex = function(value) {};
PretypistExternal.prototype.setPixelRatio = function(value) {};
PretypistExternal.prototype.setSize = function(width, height, updateStyle) {};
PretypistExternal.prototype.setClearColor = function(color, alpha) {};
PretypistExternal.prototype.render = function(scene, camera) {};
PretypistExternal.prototype.lookAt = function(target) {};
PretypistExternal.prototype.distanceTo = function(target) {};
PretypistExternal.prototype.setFromVector3 = function(vector) {};
PretypistExternal.prototype.setFromSpherical = function(spherical) {};
PretypistExternal.prototype.updateProjectionMatrix = function() {};
PretypistExternal.prototype.forceContextLoss = function() {};
PretypistExternal.prototype.dispose = function() {};
PretypistExternal.prototype.clone = function() {};
PretypistExternal.prototype.copy = function(value) {};
PretypistExternal.prototype.rotateX = function(angle) {};
PretypistExternal.prototype.rotateY = function(angle) {};
PretypistExternal.prototype.rotateZ = function(angle) {};
PretypistExternal.prototype.computeBoundingBox = function() {};
PretypistExternal.prototype.updateWorldMatrix = function(updateParents, updateChildren) {};
PretypistExternal.prototype.worldToLocal = function(vector) {};
PretypistExternal.prototype.determinant = function() {};
PretypistExternal.prototype.parse = function(data, path, onLoad, onError) {};

PretypistExternal.prototype.loadAsync = function(data, options) {};
PretypistExternal.prototype.file = function(name, data) {};
PretypistExternal.prototype.async = function(type) {};

// Chromium's deferred PWA installation event and Safari's standalone flag.
PretypistExternal.prototype.userChoice;
PretypistExternal.prototype.outcome;
PretypistExternal.prototype.standalone;
PretypistExternal.prototype.prompt = function() {};
