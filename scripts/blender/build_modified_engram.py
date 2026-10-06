"""Build PRETYPIST's quad-dominant Chocofi model inside Blender.

This file is executed through Blender's official MCP bridge by
scripts/blender-mcp-client.mjs. It creates an isolated authoring scene, leaves
unrelated Blender scenes alone, writes an editable .blend library, renders two
review images, and exports an embedded GLB for the browser pipeline.
"""

import json
import math
from pathlib import Path

import bpy
from mathutils import Vector


PROJECT = Path(PRETYPIST_PROJECT).resolve()
REFERENCE_GLB = PROJECT / "target/blender/model-reference.glb"
EXPORT_GLB = PROJECT / "target/blender/model-unstamped.glb"
BLEND_SOURCE = PROJECT / "resources/layout-sources/modified-engram/legacy/model.blend"
TOPOLOGY_REPORT = PROJECT / "resources/layout-sources/modified-engram/legacy/model-topology.json"
REVIEW_DIRECTORY = PROJECT / "target/model-review"
SCENE_NAME = "PRETYPIST_Model"
AUTHORING_REVISION = 1
REVIEW_ROUND = 3

if not REFERENCE_GLB.exists():
    raise RuntimeError(
        f"Missing {REFERENCE_GLB}; generate it with "
        "node scripts/generate-model.mjs --output target/blender/model-reference.glb"
    )


def remove_owned_scene(name: str) -> None:
    scene = bpy.data.scenes.get(name)
    if scene is None:
        return
    objects = list(scene.objects)
    bpy.data.scenes.remove(scene)
    for object_ in objects:
        if not object_.users_scene:
            bpy.data.objects.remove(object_, do_unlink=True)


# Earlier MCP inspection scenes are ours, not user-authored content. Removing
# only these exact names keeps the new import's required node names stable.
for owned_name in ("PRETYPIST_Inspect", "PRETYPIST_Model"):
    remove_owned_scene(owned_name)

scene = bpy.data.scenes.new(SCENE_NAME)
bpy.context.window.scene = scene
bpy.ops.import_scene.gltf(filepath=str(REFERENCE_GLB))
root = scene.objects.get("keyboard")
if root is None:
    raise RuntimeError("Reference GLB did not import the keyboard root")


def material(
    name: str,
    color: tuple[float, float, float, float],
    *,
    roughness: float,
    metallic: float = 0.0,
    coat: float = 0.0,
    transmission: float = 0.0,
    ior: float = 1.5,
) -> bpy.types.Material:
    value = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    value.use_nodes = True
    value.diffuse_color = color
    principled = value.node_tree.nodes.get("Principled BSDF")
    if principled:
        settings = {
            "Base Color": color,
            "Roughness": roughness,
            "Metallic": metallic,
            "Coat Weight": coat,
            "Coat Roughness": min(roughness, 0.35),
            "Transmission Weight": transmission,
            "IOR": ior,
            "Alpha": color[3],
        }
        for input_name, setting in settings.items():
            if input_name in principled.inputs:
                principled.inputs[input_name].default_value = setting
    if color[3] < 1.0:
        value.surface_render_method = "DITHERED"
        value.use_transparency_overlap = False
    return value


MATERIALS = {
    "case": material("case_fdm_black", (0.0012, 0.0014, 0.0018, 1.0), roughness=0.88),
    "keycap": material("keycap_pbt_black", (0.0032, 0.0035, 0.0043, 1.0), roughness=0.66, coat=0.06),
    "switch": material("switch_nylon_black", (0.0006, 0.0007, 0.0009, 1.0), roughness=0.78),
    "pcb": material("pcb_soldermask", (0.0025, 0.012, 0.007, 1.0), roughness=0.71),
    "screen": material("nice_view_polarizer", (0.004, 0.006, 0.0065, 1.0), roughness=0.25, coat=0.62),
    "glass": material("clear_display_cover", (0.34, 0.39, 0.40, 0.10), roughness=0.075, coat=1.0, transmission=0.0, ior=1.49),
    "gold": material("gold_plated_contact", (0.31, 0.17, 0.035, 1.0), roughness=0.29, metallic=0.82),
    "silver": material("brushed_stainless", (0.25, 0.27, 0.28, 1.0), roughness=0.32, metallic=0.88),
    "connector": material("jst_natural_nylon", (0.20, 0.19, 0.16, 1.0), roughness=0.80),
    "display_connector": material("display_fpc_nylon", (0.28, 0.26, 0.21, 1.0), roughness=0.73),
    "component": material("electronic_component", (0.020, 0.023, 0.026, 1.0), roughness=0.58),
    "ceramic": material("ceramic_component", (0.43, 0.41, 0.37, 1.0), roughness=0.73),
    "amber": material("amber_polymer", (0.47, 0.20, 0.025, 1.0), roughness=0.48),
    "red": material("battery_wire_red", (0.33, 0.016, 0.020, 1.0), roughness=0.51),
    "wire_black": material("battery_wire_black", (0.006, 0.007, 0.009, 1.0), roughness=0.61),
    "led_blue": material("status_led_blue", (0.08, 0.24, 0.32, 1.0), roughness=0.31, coat=0.2),
    "led_amber": material("charge_led_amber", (0.53, 0.20, 0.025, 1.0), roughness=0.34, coat=0.15),
}


def add_microtexture(value: bpy.types.Material, *, scale: float, strength: float, distance: float) -> None:
    nodes = value.node_tree.nodes
    links = value.node_tree.links
    for node in list(nodes):
        if node.name.startswith(f"{value.name}_micro"):
            nodes.remove(node)
    principled = nodes.get("Principled BSDF")
    if principled is None:
        return
    noise = nodes.new("ShaderNodeTexNoise")
    noise.name = f"{value.name}_microtexture"
    noise.inputs["Scale"].default_value = scale
    noise.inputs["Detail"].default_value = 3.2
    noise.inputs["Roughness"].default_value = 0.72
    bump = nodes.new("ShaderNodeBump")
    bump.name = f"{value.name}_micro_bump"
    bump.inputs["Strength"].default_value = strength
    bump.inputs["Distance"].default_value = distance
    links.new(noise.outputs["Fac"], bump.inputs["Height"])
    links.new(bump.outputs["Normal"], principled.inputs["Normal"])


add_microtexture(MATERIALS["case"], scale=760.0, strength=0.13, distance=0.00011)
add_microtexture(MATERIALS["keycap"], scale=430.0, strength=0.045, distance=0.000035)


def assign_material(object_: bpy.types.Object, value: bpy.types.Material) -> None:
    object_.data.materials.clear()
    object_.data.materials.append(value)


def mesh_from(name: str, vertices: list[tuple[float, float, float]], faces: list[tuple[int, ...]]) -> bpy.types.Mesh:
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(vertices, [], faces)
    mesh.update(calc_edges=True)
    return mesh


def box_mesh(name: str, minimum: Vector, maximum: Vector) -> bpy.types.Mesh:
    x0, y0, z0 = minimum
    x1, y1, z1 = maximum
    vertices = [
        (x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
        (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1),
    ]
    faces = [
        (0, 3, 2, 1), (4, 5, 6, 7),
        (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7),
    ]
    return mesh_from(name, vertices, faces)


def add_bevel(object_: bpy.types.Object, width: float, segments: int = 2) -> None:
    modifier = object_.modifiers.new("Manufactured edge radius", "BEVEL")
    modifier.width = width
    modifier.segments = segments
    modifier.limit_method = "ANGLE"


def bounds_for_mesh(mesh: bpy.types.Mesh) -> tuple[Vector, Vector]:
    coordinates = [vertex.co for vertex in mesh.vertices]
    return (
        Vector(tuple(min(point[axis] for point in coordinates) for axis in range(3))),
        Vector(tuple(max(point[axis] for point in coordinates) for axis in range(3))),
    )


def chaikin(points: list[tuple[float, float]]) -> list[tuple[float, float]]:
    result: list[tuple[float, float]] = []
    for index, point in enumerate(points):
        following = points[(index + 1) % len(points)]
        result.append((0.75 * point[0] + 0.25 * following[0], 0.75 * point[1] + 0.25 * following[1]))
        result.append((0.25 * point[0] + 0.75 * following[0], 0.25 * point[1] + 0.75 * following[1]))
    return result


def case_mesh(name: str, bottom: float, top: float, inset: float = 0.0) -> bpy.types.Mesh:
    # Retopologized directly from the outer bottom loop of the pinned Chocofi
    # STEP render mesh. This preserves the real stepped switch-grid and thumb
    # silhouette without retaining the CAD export's 15k long triangles.
    outline = [
        (0.058226, 0.023679), (0.045245, 0.046251), (0.042265, 0.047590),
        (0.040622, 0.047313), (0.026879, 0.039377), (0.010216, 0.034832),
        (-0.007217, 0.034814), (-0.008729, 0.034425), (-0.009684, 0.033613),
        (-0.021032, 0.014436), (-0.035145, 0.014436), (-0.035267, 0.023766),
        (-0.036034, 0.025270), (-0.037449, 0.026189), (-0.055923, 0.026189),
        (-0.057554, 0.025005), (-0.058176, 0.023089), (-0.058227, -0.017386),
        (-0.058212, -0.026894), (-0.057106, -0.028960), (-0.055162, -0.029651),
        (-0.040149, -0.029621), (-0.040158, -0.038818), (-0.039041, -0.040915),
        (-0.037072, -0.041620), (-0.022149, -0.041620), (-0.021926, -0.045657),
        (-0.021030, -0.046870), (-0.019667, -0.047517), (-0.001677, -0.047532),
        (-0.000314, -0.046888), (0.000585, -0.045678), (0.000821, -0.041072),
        (0.016012, -0.041043), (0.017899, -0.040148), (0.018745, -0.038560),
        (0.033769, -0.038572), (0.035804, -0.037813), (0.055987, -0.037671),
        (0.057537, -0.036546), (0.058131, -0.034726),
    ]
    if top > 0.001:
        # The source shell's upper perimeter sits one millimetre inboard of the
        # backplate, matching the tight lip visible in the user's photograph.
        outline = [
            (0.057226, 0.023413), (0.044440, 0.045644), (0.042026, 0.046590),
            (0.027267, 0.038446), (0.010350, 0.033832), (-0.008218, 0.033564),
            (-0.020462, 0.013436), (-0.036145, 0.013436), (-0.036209, 0.023325),
            (-0.036628, 0.024417), (-0.037758, 0.025238), (-0.055614, 0.025238),
            (-0.056745, 0.024417), (-0.057176, 0.023089), (-0.057227, -0.017391),
            (-0.057217, -0.026796), (-0.056474, -0.028185), (-0.055167, -0.028651),
            (-0.039148, -0.028619), (-0.039162, -0.038720), (-0.038559, -0.040003),
            (-0.037072, -0.040620), (-0.021146, -0.040620), (-0.021070, -0.045083),
            (-0.019471, -0.046537), (-0.001871, -0.046551), (-0.000270, -0.045101),
            (-0.000179, -0.040073), (0.015926, -0.040046), (0.017404, -0.039192),
            (0.017804, -0.037559), (0.033770, -0.037572), (0.035388, -0.036812),
            (0.055678, -0.036720), (0.056729, -0.035958), (0.057131, -0.034724),
        ]
    rounded = outline
    if inset:
        center = Vector((0.0, 0.0))
        adjusted = []
        for x, y in rounded:
            direction = (Vector((x, y)) - center).normalized()
            adjusted.append((x - direction.x * inset, y - direction.y * inset))
        rounded = adjusted
    count = len(rounded)
    vertices = [(x, y, bottom) for x, y in rounded] + [(x, y, top) for x, y in rounded]
    faces: list[tuple[int, ...]] = []
    faces.append(tuple(reversed(range(count))))
    faces.append(tuple(range(count, count * 2)))
    faces.extend((index, (index + 1) % count, (index + 1) % count + count, index + count) for index in range(count))
    return mesh_from(name, vertices, faces)


def keycap_mesh() -> bpy.types.Mesh:
    grid = 9
    bottom_width, bottom_depth = 0.0174, 0.0166
    top_width, top_depth = 0.01645, 0.01565
    top_height, dish = 0.00330, 0.00058
    vertices: list[tuple[float, float, float]] = []
    for top_surface in (False, True):
        width = top_width if top_surface else bottom_width
        depth = top_depth if top_surface else bottom_depth
        for row in range(grid):
            v = -1.0 + 2.0 * row / (grid - 1)
            for column in range(grid):
                u = -1.0 + 2.0 * column / (grid - 1)
                edge = max(abs(u), abs(v))
                z = 0.0 if not top_surface else top_height - dish * (1.0 - edge * edge)
                x = u * width / 2.0
                y = v * depth / 2.0
                if abs(u) == 1.0 and abs(v) == 1.0:
                    x -= math.copysign(0.00062, x)
                    y -= math.copysign(0.00062, y)
                vertices.append((x, y, z))
    surface = grid * grid
    faces: list[tuple[int, ...]] = []
    for offset, reverse in ((0, True), (surface, False)):
        for row in range(grid - 1):
            for column in range(grid - 1):
                a = offset + row * grid + column
                face = (a, a + 1, a + grid + 1, a + grid)
                faces.append(tuple(reversed(face)) if reverse else face)
    perimeter = (
        list(range(grid))
        + [row * grid + grid - 1 for row in range(1, grid)]
        + list(range(surface - 2, surface - grid - 1, -1))
        + [row * grid for row in range(grid - 2, 0, -1)]
    )
    for index, bottom_index in enumerate(perimeter):
        following = perimeter[(index + 1) % len(perimeter)]
        faces.append((bottom_index, following, following + surface, bottom_index + surface))
    mesh = mesh_from("blank_dished_choc_keycap_quad", vertices, faces)
    uv_layer = mesh.uv_layers.new(name="UVMap")
    top_face_start = (grid - 1) * (grid - 1)
    top_face_end = top_face_start * 2
    for polygon_index, polygon in enumerate(mesh.polygons):
        for loop_index in polygon.loop_indices:
            vertex = mesh.vertices[mesh.loops[loop_index].vertex_index].co
            uv_layer.data[loop_index].uv = ((vertex.x / bottom_width) + 0.5, (vertex.y / bottom_depth) + 0.5)
        polygon.use_smooth = top_face_start <= polygon_index < top_face_end
    return mesh


def cylinder_mesh(name: str, radius: float, bottom: float, top: float, segments: int = 16) -> bpy.types.Mesh:
    vertices = []
    for z in (bottom, top):
        vertices.extend((radius * math.cos(index * math.tau / segments), radius * math.sin(index * math.tau / segments), z) for index in range(segments))
    faces: list[tuple[int, ...]] = [tuple(reversed(range(segments))), tuple(range(segments, segments * 2))]
    faces.extend((index, (index + 1) % segments, (index + 1) % segments + segments, index + segments) for index in range(segments))
    return mesh_from(name, vertices, faces)


def tube_mesh(name: str, points: list[tuple[float, float, float]], radius: float, segments: int = 8) -> bpy.types.Mesh:
    vertices: list[tuple[float, float, float]] = []
    for point_index, point in enumerate(points):
        tangent = Vector(points[min(point_index + 1, len(points) - 1)]) - Vector(points[max(0, point_index - 1)])
        tangent.normalize()
        normal = tangent.cross(Vector((0.0, 0.0, 1.0)))
        if normal.length < 0.01:
            normal = Vector((1.0, 0.0, 0.0))
        normal.normalize()
        binormal = tangent.cross(normal).normalized()
        center = Vector(point)
        for segment in range(segments):
            angle = segment * math.tau / segments
            offset = normal * (math.cos(angle) * radius) + binormal * (math.sin(angle) * radius)
            vertices.append(tuple(center + offset))
    faces: list[tuple[int, ...]] = []
    for ring in range(len(points) - 1):
        for segment in range(segments):
            following = (segment + 1) % segments
            faces.append((ring * segments + segment, ring * segments + following, (ring + 1) * segments + following, (ring + 1) * segments + segment))
    faces.append(tuple(reversed(range(segments))))
    last = (len(points) - 1) * segments
    faces.append(tuple(last + index for index in range(segments)))
    return mesh_from(name, vertices, faces)


def smooth_path(control: list[tuple[float, float, float]], steps: int = 5) -> list[tuple[float, float, float]]:
    result: list[tuple[float, float, float]] = []
    values = [Vector(control[0]), *(Vector(point) for point in control), Vector(control[-1])]
    for index in range(1, len(values) - 2):
        p0, p1, p2, p3 = values[index - 1:index + 3]
        for step in range(steps):
            t = step / steps
            t2, t3 = t * t, t * t * t
            point = 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
            result.append(tuple(point))
    result.append(control[-1])
    return result


def frame_mesh(name: str, minimum: Vector, maximum: Vector, rim: float) -> bpy.types.Mesh:
    meshes = []
    spans = [
        (Vector((minimum.x, minimum.y, minimum.z)), Vector((minimum.x + rim, maximum.y, maximum.z))),
        (Vector((maximum.x - rim, minimum.y, minimum.z)), Vector((maximum.x, maximum.y, maximum.z))),
        (Vector((minimum.x + rim, minimum.y, minimum.z)), Vector((maximum.x - rim, minimum.y + rim, maximum.z))),
        (Vector((minimum.x + rim, maximum.y - rim, minimum.z)), Vector((maximum.x - rim, maximum.y, maximum.z))),
    ]
    vertices: list[tuple[float, float, float]] = []
    faces: list[tuple[int, ...]] = []
    for span_index, (lower, upper) in enumerate(spans):
        part = box_mesh(f"{name}_part", lower, upper)
        offset = len(vertices)
        vertices.extend(tuple(vertex.co) for vertex in part.vertices)
        faces.extend(tuple(offset + vertex for vertex in polygon.vertices) for polygon in part.polygons)
        bpy.data.meshes.remove(part)
    return mesh_from(name, vertices, faces)


def category_material(name: str) -> bpy.types.Material:
    if name.startswith("display_screen_"):
        return MATERIALS["screen"]
    if name.startswith("display_") or name.startswith("pcb_"):
        return MATERIALS["pcb"]
    if name.startswith("keyswitch_"):
        return MATERIALS["switch"]
    if name.startswith("contact_"):
        return MATERIALS["gold"]
    if name.startswith("wire_red_"):
        return MATERIALS["red"]
    if name.startswith("wire_black_"):
        return MATERIALS["wire_black"]
    if name.startswith("connector_battery_"):
        return MATERIALS["connector"]
    if name.startswith("connector_display_"):
        return MATERIALS["display_connector"]
    if name.startswith("connector_"):
        return MATERIALS["connector"]
    if name.startswith("metal_") or name.startswith("screw_"):
        return MATERIALS["silver"]
    if name.startswith("accent_led_blue_"):
        return MATERIALS["led_blue"]
    if name.startswith("accent_led_amber_"):
        return MATERIALS["led_amber"]
    if name.startswith("accent_"):
        return MATERIALS["amber"]
    if name.startswith("component_ceramic_"):
        return MATERIALS["ceramic"]
    if name.startswith("component_radio_"):
        return MATERIALS["silver"]
    if name.startswith("component_"):
        return MATERIALS["component"]
    return MATERIALS["case"]


# Shadow proxies duplicated the dense backplate. The rebuilt closed shell casts
# correctly by itself and is small enough to do so directly.
for name in ("shadow_caster_left", "shadow_caster_right"):
    object_ = scene.objects.get(name)
    if object_:
        bpy.data.objects.remove(object_, do_unlink=True)

top_mesh = case_mesh("chocofi_case_top_quad", -0.0008, 0.0038)
bottom_mesh = case_mesh("chocofi_case_bottom_quad", -0.0030, -0.00055)
for suffix in ("left", "right"):
    top_object = scene.objects[f"case_top_{suffix}"]
    top_object.data = top_mesh
    assign_material(top_object, MATERIALS["case"])
    add_bevel(top_object, 0.00018, 2)
    bottom_object = scene.objects[f"case_bottom_{suffix}"]
    bottom_object.data = bottom_mesh
    assign_material(bottom_object, MATERIALS["case"])
    add_bevel(bottom_object, 0.00018, 2)

key_mesh = keycap_mesh()
for index in range(36):
    key = scene.objects[f"key_{index:02d}"]
    key.data = key_mesh
    assign_material(key, MATERIALS["keycap"])
    add_bevel(key, 0.00027, 2)

screw_mesh = cylinder_mesh("case_screw_head_quad", 0.00082, -0.00017, 0.00017, 16)
for suffix in ("left", "right"):
    for index in range(7):
        screw = scene.objects[f"screw_{suffix}_{index:02d}"]
        screw.data = screw_mesh
        assign_material(screw, MATERIALS["silver"])
        add_bevel(screw, 0.00008, 2)
        # A dark crossed recess reads as a Phillips drive at the scene's scale.
        for axis, size in (("x", (0.00080, 0.00016, 0.00006)), ("y", (0.00016, 0.00080, 0.00006))):
            drive_mesh = box_mesh(
                f"screw_drive_{axis}_mesh",
                Vector(tuple(-dimension / 2 for dimension in size)),
                Vector(tuple(dimension / 2 for dimension in size)),
            )
            drive = bpy.data.objects.new(f"component_screw_drive_{suffix}_{index:02d}_{axis}", drive_mesh)
            drive.parent = screw.parent
            drive.location = screw.location + Vector((0.0, 0.0, 0.00019))
            scene.collection.objects.link(drive)
            assign_material(drive, MATERIALS["component"])

# Replace the remaining triangulated proxy shapes with dimension-matched,
# bevelled quad cages. The component hierarchy and measured placements survive,
# while tiny fan caps and redundant long triangles do not.
shared_replacements: dict[bpy.types.Mesh, bpy.types.Mesh] = {}
for object_ in list(scene.objects):
    if object_.type != "MESH" or object_.name.startswith(("case_top_", "case_bottom_", "key_", "screw_", "glass_", "component_screw_drive_")):
        continue
    if object_.name.startswith("wire_"):
        red = object_.name.startswith("wire_red_")
        control = [
            (0.0438 if red else 0.0428, -0.0030, 0.0087),
            (0.0565 if red else 0.0542, -0.0010, 0.0090),
            (0.0618 if red else 0.0594, 0.0070, 0.0093),
            (0.0611 if red else 0.0587, 0.0250, 0.00955),
            (0.0540 if red else 0.0521, 0.0340, 0.00915),
            (0.0506 if red else 0.0495, 0.0310, 0.0071),
        ]
        object_.data = tube_mesh(
            f"battery_wire_{'red' if red else 'black'}_quad",
            smooth_path(control, 5),
            0.00040,
            8,
        )
        assign_material(object_, MATERIALS["red"] if red else MATERIALS["wire_black"])
        continue
    old_mesh = object_.data
    if old_mesh not in shared_replacements:
        minimum, maximum = bounds_for_mesh(old_mesh)
        extents = maximum - minimum
        # Avoid zero-thickness imported decals: a physical minimum gives them a
        # real highlight and prevents degenerate triangles in glTF.
        if extents.z < 0.00008:
            maximum.z = minimum.z + 0.00008
        if object_.name.startswith("metal_display_bezel_"):
            replacement = frame_mesh(f"{old_mesh.name}_quad_frame", minimum, maximum, 0.00048)
        else:
            replacement = box_mesh(f"{old_mesh.name}_quad", minimum, maximum)
        shared_replacements[old_mesh] = replacement
    object_.data = shared_replacements[old_mesh]
    assign_material(object_, category_material(object_.name))
    minimum_dimension = min(abs(value) for value in object_.dimensions)
    flat_detail = object_.name.startswith((
        "contact_", "pcb_", "display_screen_", "metal_display_bezel_", "accent_display_flex_",
    ))
    if not flat_detail and minimum_dimension > 0.00034:
        add_bevel(object_, min(0.00016, minimum_dimension * 0.16), 2)

for suffix in ("left", "right"):
    connector = scene.objects.get(f"connector_battery_{suffix}")
    if connector:
        connector.scale = (0.68, 0.68, 0.68)
        connector.location.x = 0.0438
        connector.location.y = -0.0042


def add_glass_assembly(suffix: str) -> None:
    parent = scene.objects[f"display_{suffix}"]
    glass = bpy.data.objects.new(
        f"glass_display_cover_{suffix}",
        box_mesh(
            f"glass_display_cover_{suffix}_quad",
            Vector((-0.00735, -0.01835, 0.0)),
            Vector((0.00735, 0.01835, 0.00042)),
        ),
    )
    glass.parent = parent
    glass.location = (0.0, 0.0, 0.00128)
    scene.collection.objects.link(glass)
    assign_material(glass, MATERIALS["glass"])
    add_bevel(glass, 0.00008, 1)
    for x in (-0.0058, 0.0058):
        for y in (-0.0156, 0.0156):
            fastener = bpy.data.objects.new(
                f"screw_display_cover_{suffix}_{'p' if x > 0 else 'n'}{'p' if y > 0 else 'n'}",
                cylinder_mesh("display_cover_fastener_quad", 0.00062, 0.0, 0.00024, 12),
            )
            fastener.parent = parent
            fastener.location = (x, y, 0.00169)
            scene.collection.objects.link(fastener)
            assign_material(fastener, MATERIALS["silver"])
            add_bevel(fastener, 0.00007, 1)


add_glass_assembly("left")
add_glass_assembly("right")

for suffix in ("left", "right"):
    parent = scene.objects[f"display_{suffix}"]
    outline = [(-0.0082, -0.0172), (0.0082, -0.0172), (0.0089, -0.0250),
               (0.0039, -0.0290), (-0.0068, -0.0262)]
    count = len(outline)
    vertices = [(x, y, -0.00072) for x, y in outline] + [(x, y, -0.00008) for x, y in outline]
    faces = [tuple(reversed(range(count))), tuple(range(count, count * 2))]
    faces.extend((index, (index + 1) % count, (index + 1) % count + count, index + count) for index in range(count))
    mount = bpy.data.objects.new(
        f"pcb_display_mount_{suffix}",
        mesh_from(f"display_mount_{suffix}_quad", vertices, faces),
    )
    mount.parent = parent
    scene.collection.objects.link(mount)
    assign_material(mount, MATERIALS["pcb"])
    add_bevel(mount, 0.00010, 1)

# Meshes are smooth only where the part is actually molded/turned. Bevel
# modifiers create the highlight transitions; the broad case planes stay flat.
for object_ in scene.objects:
    if object_.type == "MESH" and object_.name.startswith(("screw_", "wire_", "glass_")):
        for polygon in object_.data.polygons:
            polygon.use_smooth = True


def descendants(object_: bpy.types.Object) -> list[bpy.types.Object]:
    result = [object_]
    for child in object_.children:
        result.extend(descendants(child))
    return result


model_objects = descendants(root)
mesh_objects = [object_ for object_ in model_objects if object_.type == "MESH"]
polygon_counts = {"quads": 0, "triangles": 0, "ngons": 0, "total": 0}
unique_meshes: set[bpy.types.Mesh] = set()
for object_ in mesh_objects:
    unique_meshes.add(object_.data)
    for polygon in object_.data.polygons:
        polygon_counts["total"] += 1
        if len(polygon.vertices) == 4:
            polygon_counts["quads"] += 1
        elif len(polygon.vertices) == 3:
            polygon_counts["triangles"] += 1
        else:
            polygon_counts["ngons"] += 1
quad_ratio = polygon_counts["quads"] / polygon_counts["total"] if polygon_counts["total"] else 0.0
if quad_ratio < 0.95:
    raise RuntimeError(f"Editable topology is only {quad_ratio:.2%} quads")

required_nodes = ["keyboard", "half_left", "half_right", "case_left", "case_right"] + [f"key_{index:02d}" for index in range(36)]
missing_nodes = [name for name in required_nodes if scene.objects.get(name) is None]
if missing_nodes:
    raise RuntimeError(f"Missing required nodes: {', '.join(missing_nodes)}")

TOPOLOGY_REPORT.parent.mkdir(parents=True, exist_ok=True)
TOPOLOGY_REPORT.write_text(json.dumps({
    "authoringRevision": AUTHORING_REVISION,
    "editableTopologyScope": "polygons weighted by visible object use below keyboard root",
    "meshObjects": len(mesh_objects),
    "uniqueMeshes": len(unique_meshes),
    "polygons": polygon_counts,
    "quadRatio": round(quad_ratio, 6),
    "requiredNodes": {"expected": len(required_nodes), "missing": missing_nodes},
    "note": "The GLB transport is triangulated by the glTF exporter; quad topology is preserved in model.blend.",
}, indent=2) + "\n", encoding="utf-8")

# Export only the model hierarchy. Render rig objects below are intentionally
# excluded from the self-contained package asset.
bpy.ops.object.select_all(action="DESELECT")
for object_ in model_objects:
    object_.select_set(True)
bpy.context.view_layer.objects.active = root
EXPORT_GLB.parent.mkdir(parents=True, exist_ok=True)
bpy.ops.export_scene.gltf(
    filepath=str(EXPORT_GLB),
    export_format="GLB",
    use_selection=True,
    # Keep shared mesh datablocks instanced in glTF. Blender still evaluates
    # visible modifiers for rendering, but forcing export_apply makes a unique
    # transport mesh for every key and repeated component.
    export_apply=False,
    export_yup=True,
    export_extras=True,
    export_cameras=False,
    export_lights=False,
)


def display_bitmap(name: str, kind: str) -> bpy.types.Image:
    width, height = 68, 160
    paper = (0.010, 0.016, 0.014, 1.0)
    ink = (0.48, 0.57, 0.52, 1.0)
    pixels = [component for _ in range(width * height) for component in paper]

    def set_pixel(x: int, y: int, color=ink) -> None:
        if 0 <= x < width and 0 <= y < height:
            offset = 4 * (x + y * width)
            pixels[offset:offset + 4] = color

    def line(x0: int, y0: int, x1: int, y1: int, color=ink) -> None:
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        error = dx + dy
        while True:
            set_pixel(x0, y0, color)
            if x0 == x1 and y0 == y1:
                break
            doubled = 2 * error
            if doubled >= dy:
                error += dy
                x0 += sx
            if doubled <= dx:
                error += dx
                y0 += sy

    def rectangle(x0: int, y0: int, x1: int, y1: int) -> None:
        line(x0, y0, x1, y0); line(x1, y0, x1, y1)
        line(x1, y1, x0, y1); line(x0, y1, x0, y0)

    def text_3x5(text: str, x: int, y: int, scale: int = 1) -> None:
        glyphs = {
            "W": ("101", "101", "101", "111", "101"),
            "I": ("111", "010", "010", "010", "111"),
            "N": ("101", "111", "111", "111", "101"),
        }
        cursor = x
        for character in text:
            for row, bits in enumerate(glyphs[character]):
                for column, bit in enumerate(bits):
                    if bit == "1":
                        for dx in range(scale):
                            for dy in range(scale):
                                set_pixel(cursor + column * scale + dx, y + (4 - row) * scale + dy)
            cursor += 4 * scale

    if kind == "balloon":
        source = (PROJECT / "src/pretypist/scene/generated/nice_view_art.cljs").read_text(encoding="utf-8")
        section = source.split(":balloon (str", 1)[1].split(":mountain", 1)[0]
        payload = "".join(part.split('"', 1)[0] for part in section.split('"')[1::2])
        packed = bytes.fromhex(payload)
        stride = 18
        for source_y in range(68):
            for source_x in range(140):
                if packed[source_y * stride + source_x // 8] & (1 << (7 - source_x % 8)):
                    set_pixel(width - 1 - source_y, height - 1 - (20 + 139 - source_x))
        # Top status strip, matching the physical peripheral panel vocabulary.
        rectangle(4, 146, 19, 155)
        line(20, 149, 22, 149)
        line(48, 149, 52, 153); line(52, 153, 56, 149)
    else:
        # Central-status display: battery, radio, WPM graph, five profiles, and
        # a BASE layer footer. It is intentionally monochrome and pixel-tight.
        rectangle(5, 144, 23, 155); line(24, 148, 26, 151)
        line(44, 146, 55, 155); line(47, 146, 55, 152); line(50, 146, 55, 149)
        rectangle(6, 85, 61, 132)
        graph = [(8, 93), (17, 96), (25, 91), (33, 105), (41, 101), (50, 116), (59, 112)]
        for first, second in zip(graph, graph[1:]):
            line(*first, *second)
        for index, x in enumerate((10, 22, 34, 46, 58), start=1):
            for angle in range(0, 360, 18):
                set_pixel(round(x + 5 * math.cos(math.radians(angle))), round(62 + 5 * math.sin(math.radians(angle))))
            # Small digit-like centre marks are enough at the real panel scale.
            line(x, 59, x, 65)
            if index % 2 == 0:
                line(x - 2, 59, x + 2, 59)
        line(8, 31, 59, 31); line(12, 24, 55, 24)
        text_3x5("WIN", 17, 9, 2)

    image = bpy.data.images.new(name, width=width, height=height, alpha=True, float_buffer=False)
    image.colorspace_settings.name = "Non-Color"
    image.pixels.foreach_set(pixels)
    image.pack()
    return image


def display_art_material(name: str, image: bpy.types.Image) -> bpy.types.Material:
    value = bpy.data.materials.new(name)
    value.use_nodes = True
    nodes = value.node_tree.nodes
    links = value.node_tree.links
    principled = nodes.get("Principled BSDF")
    texture = nodes.new("ShaderNodeTexImage")
    texture.image = image
    texture.interpolation = "Closest"
    links.new(texture.outputs["Color"], principled.inputs["Base Color"])
    principled.inputs["Roughness"].default_value = 0.28
    if "Coat Weight" in principled.inputs:
        principled.inputs["Coat Weight"].default_value = 0.34
    return value


def add_review_display(suffix: str, kind: str) -> None:
    width, height = 0.0113, 0.0265
    mesh = mesh_from(
        f"PRETYPIST_Review_Display_{suffix}_mesh",
        [(-width / 2, -height / 2, 0.0), (width / 2, -height / 2, 0.0),
         (width / 2, height / 2, 0.0), (-width / 2, height / 2, 0.0)],
        [(0, 1, 2, 3)],
    )
    uv = mesh.uv_layers.new(name="UVMap")
    for loop_index, coordinate in enumerate(((0, 0), (1, 0), (1, 1), (0, 1))):
        uv.data[loop_index].uv = coordinate
    object_ = bpy.data.objects.new(f"PRETYPIST_Review_Display_{suffix}", mesh)
    object_.parent = scene.objects[f"display_{suffix}"]
    object_.location = (0.0, 0.00015, 0.00110)
    scene.collection.objects.link(object_)
    assign_material(object_, display_art_material(
        f"PRETYPIST_Review_Display_{suffix}_material",
        display_bitmap(f"PRETYPIST_Review_Display_{suffix}_bitmap", kind),
    ))


add_review_display("left", "status")
add_review_display("right", "balloon")


def point_camera(camera: bpy.types.Object, target: Vector) -> None:
    camera.rotation_euler = (target - camera.location).to_track_quat("-Z", "Y").to_euler()


def add_area(name: str, location: tuple[float, float, float], energy: float, color: tuple[float, float, float], size: float) -> None:
    data = bpy.data.lights.new(name, "AREA")
    data.energy = energy
    data.color = color
    data.shape = "DISK"
    data.size = size
    object_ = bpy.data.objects.new(name, data)
    scene.collection.objects.link(object_)
    object_.location = location
    point_camera(object_, Vector((0.0, 0.0, 0.0)))


floor_material = material("studio_surface", (0.055, 0.052, 0.048, 1.0), roughness=0.91)
bpy.ops.mesh.primitive_plane_add(size=1.0, location=(0.0, 0.0, -0.0037))
floor = bpy.context.object
floor.name = "PRETYPIST_Studio_Surface"
floor.scale = (0.55, 0.40, 1.0)
assign_material(floor, floor_material)

camera_data = bpy.data.cameras.new("PRETYPIST_Review_Camera")
camera = bpy.data.objects.new("PRETYPIST_Review_Camera", camera_data)
scene.collection.objects.link(camera)
scene.camera = camera
camera_data.lens = 63
camera_data.sensor_width = 36

add_area("PRETYPIST_Key_Light", (-0.24, -0.18, 0.34), 3.0, (1.0, 0.86, 0.72), 0.24)
add_area("PRETYPIST_Fill_Light", (0.27, -0.05, 0.22), 0.6, (0.78, 0.86, 1.0), 0.20)
add_area("PRETYPIST_Rim_Light", (0.03, 0.28, 0.28), 2.0, (1.0, 0.93, 0.82), 0.16)

scene.render.engine = "BLENDER_EEVEE"
scene.render.resolution_x = 1400
scene.render.resolution_y = 900
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = "PNG"
scene.render.film_transparent = False
scene.render.image_settings.color_mode = "RGBA"
if scene.world is None:
    scene.world = bpy.data.worlds.new("PRETYPIST_Studio_World")
scene.world.color = (0.012, 0.012, 0.014)
scene.view_settings.look = "AgX - Medium High Contrast"
scene.view_settings.exposure = 0.0

REVIEW_DIRECTORY.mkdir(parents=True, exist_ok=True)
camera.location = (0.0, -0.325, 0.255)
camera_data.lens = 55
point_camera(camera, Vector((0.0, -0.003, 0.012)))
scene.render.filepath = str(REVIEW_DIRECTORY / f"round-{REVIEW_ROUND}-perspective.png")
bpy.ops.render.render(write_still=True)

camera.location = (0.0, -0.015, 0.415)
camera_data.lens = 55
point_camera(camera, Vector((0.0, -0.002, 0.0)))
scene.render.filepath = str(REVIEW_DIRECTORY / f"round-{REVIEW_ROUND}-top.png")
bpy.ops.render.render(write_still=True)

# Write only this isolated scene and its recursively referenced datablocks. This
# does not replace or save over the user's currently open .blend file.
BLEND_SOURCE.parent.mkdir(parents=True, exist_ok=True)
bpy.data.libraries.write(str(BLEND_SOURCE), {scene}, fake_user=True, compress=True)

result = {
    "scene": SCENE_NAME,
    "blender": bpy.app.version_string,
    "editableQuadRatio": round(quad_ratio, 6),
    "editablePolygons": polygon_counts,
    "meshObjects": len(mesh_objects),
    "uniqueMeshes": len(unique_meshes),
    "glb": str(EXPORT_GLB),
    "blend": str(BLEND_SOURCE),
    "renders": [
        str(REVIEW_DIRECTORY / f"round-{REVIEW_ROUND}-perspective.png"),
        str(REVIEW_DIRECTORY / f"round-{REVIEW_ROUND}-top.png"),
    ],
}
