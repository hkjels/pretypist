#!/usr/bin/env python3
"""Create compact, deterministic render meshes from the pinned Chocofi STEP solids.

This is an optional asset-authoring tool. The generated binary STL files are
checked in and consumed by the ordinary Node-based model build, so PRETYPIST's
development and production commands do not require OpenCascade.

Install the pinned authoring dependency in an isolated environment:

    python3.12 -m pip install -r scripts/requirements-cad.txt
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import struct
import sys
from dataclasses import dataclass
from pathlib import Path

try:
    from OCP.BRep import BRep_Tool
    from OCP.BRepCheck import BRepCheck_Analyzer
    from OCP.BRepGProp import BRepGProp
    from OCP.BRepMesh import BRepMesh_IncrementalMesh
    from OCP.BRepTools import BRepTools
    from OCP.IFSelect import IFSelect_RetDone
    from OCP.GProp import GProp_GProps
    from OCP.STEPControl import STEPControl_Reader
    from OCP.TopAbs import TopAbs_FACE, TopAbs_REVERSED
    from OCP.TopExp import TopExp_Explorer
    from OCP.TopLoc import TopLoc_Location
    from OCP.TopoDS import TopoDS
except ImportError as error:  # pragma: no cover - exercised only without authoring deps
    raise SystemExit(
        "Missing cadquery-ocp. Install the pinned authoring dependency with "
        "`python3.12 -m pip install -r scripts/requirements-cad.txt`."
    ) from error


PROJECT_ROOT = Path(__file__).resolve().parent.parent
SOURCE_DIRECTORY = (
    PROJECT_ROOT
    / "resources"
    / "layout-sources"
    / "modified-engram"
    / "upstream"
    / "chocofi-case-v1"
)
OUTPUT_DIRECTORY = (
    PROJECT_ROOT
    / "resources"
    / "layout-sources"
    / "modified-engram"
    / "render-mesh"
)

PINNED_SOURCE_HASHES = {
    "top.step": "73555d15108088b8f885d367077314532b04bc34741c7bbb3726d660a7e60e43",
    "bottom.step": "79d5427d1427d596f045d81a9964726fcc30c37b6262e0d14effda59c6b0f7fc",
}


@dataclass(frozen=True)
class Triangle:
    normal: tuple[float, float, float]
    points: tuple[
        tuple[float, float, float],
        tuple[float, float, float],
        tuple[float, float, float],
    ]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_shape(path: Path):
    expected_hash = PINNED_SOURCE_HASHES[path.name]
    actual_hash = sha256(path)
    if actual_hash != expected_hash:
        raise ValueError(
            f"{path.name} does not match the pinned Chocofi source: "
            f"expected {expected_hash}, got {actual_hash}"
        )

    reader = STEPControl_Reader()
    if reader.ReadFile(str(path)) != IFSelect_RetDone:
        raise ValueError(f"OpenCascade could not read {path}")
    if reader.TransferRoots() <= 0:
        raise ValueError(f"OpenCascade found no transferable roots in {path}")
    shape = reader.OneShape()
    if shape.IsNull():
        raise ValueError(f"OpenCascade produced an empty shape for {path}")
    if not BRepCheck_Analyzer(shape).IsValid():
        raise ValueError(f"OpenCascade reports an invalid B-rep in {path}")
    BRepTools.Clean_s(shape)
    return shape


def vector_normal(
    a: tuple[float, float, float],
    b: tuple[float, float, float],
    c: tuple[float, float, float],
) -> tuple[float, float, float]:
    ab = (b[0] - a[0], b[1] - a[1], b[2] - a[2])
    ac = (c[0] - a[0], c[1] - a[1], c[2] - a[2])
    cross = (
        ab[1] * ac[2] - ab[2] * ac[1],
        ab[2] * ac[0] - ab[0] * ac[2],
        ab[0] * ac[1] - ab[1] * ac[0],
    )
    length = math.sqrt(sum(component * component for component in cross))
    if length <= 1e-12:
        raise ValueError("OpenCascade produced a degenerate render triangle")
    return tuple(component / length for component in cross)


def point_tuple(point, transform) -> tuple[float, float, float]:
    transformed = point.Transformed(transform)
    return (transformed.X(), transformed.Y(), transformed.Z())


def tessellate(shape, linear_deflection_mm: float, angular_deflection_radians: float):
    mesher = BRepMesh_IncrementalMesh(
        shape,
        linear_deflection_mm,
        False,
        angular_deflection_radians,
        False,
    )
    if not mesher.IsDone():
        raise ValueError("OpenCascade did not complete tessellation")

    triangles: list[Triangle] = []
    face_count = 0
    untessellated_faces: list[dict[str, object]] = []
    explorer = TopExp_Explorer(shape, TopAbs_FACE)
    while explorer.More():
        face_count += 1
        face = TopoDS.Face(explorer.Current())
        location = TopLoc_Location()
        triangulation = BRep_Tool.Triangulation_s(face, location)
        # OpenCascade can decline to triangulate a microscopic trimmed sliver
        # face at this render tolerance. Preserve an exact record of that
        # non-zero B-rep surface instead of manufacturing a degenerate render
        # triangle or incorrectly describing it as a zero-area face.
        if triangulation is None:
            properties = GProp_GProps()
            BRepGProp.SurfaceProperties_s(face, properties)
            centroid = properties.CentreOfMass()
            untessellated_faces.append(
                {
                    "faceIndex": face_count,
                    "surfaceAreaSquareMillimetres": properties.Mass(),
                    "centroidMillimetres": [centroid.X(), centroid.Y(), centroid.Z()],
                }
            )
            explorer.Next()
            continue
        transform = location.Transformation()
        reversed_face = face.Orientation() == TopAbs_REVERSED
        for triangle_index in range(1, triangulation.NbTriangles() + 1):
            indices = list(triangulation.Triangle(triangle_index).Get())
            if reversed_face:
                indices[1], indices[2] = indices[2], indices[1]
            points = tuple(
                point_tuple(triangulation.Node(node_index), transform)
                for node_index in indices
            )
            triangles.append(Triangle(vector_normal(*points), points))
        explorer.Next()

    if not triangles:
        raise ValueError("STEP solid produced no render triangles")
    return face_count, untessellated_faces, triangles


def write_binary_stl(path: Path, triangles: list[Triangle]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    header_text = b"PRETYPIST Chocofi STEP render mesh; millimetres; deterministic OCCT"
    header = header_text[:80].ljust(80, b"\0")
    with path.open("wb") as output:
        output.write(header)
        output.write(struct.pack("<I", len(triangles)))
        for triangle in triangles:
            values = (*triangle.normal, *triangle.points[0], *triangle.points[1], *triangle.points[2])
            output.write(struct.pack("<12fH", *values, 0))


def bounds(triangles: list[Triangle]) -> dict[str, list[float]]:
    points = [point for triangle in triangles for point in triangle.points]
    return {
        "minimum": [min(point[axis] for point in points) for axis in range(3)],
        "maximum": [max(point[axis] for point in points) for axis in range(3)],
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--linear-deflection-mm", type=float, default=0.08)
    parser.add_argument("--angular-deflection-deg", type=float, default=12.0)
    parser.add_argument("--source-directory", type=Path, default=SOURCE_DIRECTORY)
    parser.add_argument("--output-directory", type=Path, default=OUTPUT_DIRECTORY)
    return parser.parse_args()


def main() -> int:
    options = parse_args()
    if options.linear_deflection_mm <= 0:
        raise ValueError("Linear deflection must be positive")
    if not 0 < options.angular_deflection_deg < 90:
        raise ValueError("Angular deflection must be between 0 and 90 degrees")

    metadata = {
        "formatVersion": 1,
        "generator": "scripts/tessellate-step.py",
        "cadqueryOcpVersion": "8.0.1.0.0",
        "units": "millimetres",
        "linearDeflectionMm": options.linear_deflection_mm,
        "angularDeflectionDegrees": options.angular_deflection_deg,
        "meshes": {},
    }
    for name in ("top", "bottom"):
        source = options.source_directory / f"{name}.step"
        output = options.output_directory / f"{name}.stl"
        shape = read_shape(source)
        face_count, untessellated_faces, triangles = tessellate(
            shape,
            options.linear_deflection_mm,
            math.radians(options.angular_deflection_deg),
        )
        write_binary_stl(output, triangles)
        metadata["meshes"][name] = {
            "source": str(source.relative_to(PROJECT_ROOT)),
            "sourceSha256": sha256(source),
            "output": str(output.relative_to(PROJECT_ROOT)),
            "outputSha256": sha256(output),
            "brepValid": True,
            "brepFaces": face_count,
            "untessellatedFaces": untessellated_faces,
            "triangles": len(triangles),
            "boundsMillimetres": bounds(triangles),
        }
        print(
            f"{name}: {face_count} B-rep faces "
            f"({len(untessellated_faces)} sub-resolution face(s) untessellated) "
            f"-> {len(triangles)} triangles"
        )

    metadata_path = options.output_directory / "mesh-metadata.json"
    metadata_path.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {metadata_path.relative_to(PROJECT_ROOT)}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
