"""Reproducible sidecar-only extraction of verified upstream backwear assets.

All paths are confined to this script's directory. No resource-pack integration.
PNG data is copied or decoded verbatim; no generated imagery or voxelized icons.
"""
from __future__ import annotations

import base64
import copy
import hashlib
import json
import math
import pathlib
import re
import shutil
import struct

ROOT = pathlib.Path(__file__).resolve().parent
INVENTORY = json.loads((ROOT / "download-inventory.json").read_text(encoding="utf-8"))
DOWNLOADS = {x["path"]: x for x in INVENTORY}
SOURCES = {
    "loy": {"repo": "SL0ANE/Loy-s-Goodies", "license": "CC0-1.0", "licenseFile": "LICENSE", "author": "Loy / SL0ANE"},
    "melodies": {"repo": "Luke100000/ImmersiveMelodies", "license": "GPL-3.0", "licenseFile": "LICENSE", "author": "Immersive Melodies contributors / Luke100000"},
    "ironjetpacks": {"repo": "BlakeBr0/IronJetpacks", "license": "MIT", "licenseFile": "LICENSE", "author": "BlakeBr0"},
}


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def record(path, **extra):
    return {"path": str(path), "sha256": sha(path), "bytes": path.stat().st_size, **extra}


def source_file(key, relative):
    path = ROOT / "upstream" / key / relative
    original = DOWNLOADS[str(path)]
    assert sha(path) == original["sha256"], path
    return {**original, "format": "Blockbench java_block" if path.suffix == ".bbmodel" else "Java source" if path.suffix == ".java" else "PNG" if path.suffix == ".png" else "Minecraft Java item model JSON"}


def provenance(key):
    source = SOURCES[key]
    tree = json.loads((ROOT / "upstream" / key / "tree.json").read_text(encoding="utf-8"))
    commit = tree["sha"]
    license_path = ROOT / "upstream" / key / source["licenseFile"]
    return {"repository": source["repo"], "commit": commit, "author": source["author"], "licenseName": source["license"], "licenseURL": f"https://github.com/{source['repo']}/blob/{commit}/{source['licenseFile']}", "licenseEvidence": record(license_path)}


def bb_elements(data, selection):
    """Flatten unrotated organizational groups and select a real static pose."""
    keep = set()
    removed = []

    def walk(nodes):
        for node in nodes:
            if isinstance(node, str):
                keep.add(node)
                continue
            assert not any(node.get("rotation", [0, 0, 0])), "Unbaked group rotation"
            if node.get("export", True) is False:
                continue
            name = node.get("name", "")
            if selection == "phonograph_static" and name.startswith("disk_") and name != "disk_0":
                removed.append(node["uuid"])
                continue
            if selection == "phonograph_static" and name == "hand":
                children = node["children"]
                walk(children[:1])
                removed.extend(x["uuid"] for x in children[1:])
                continue
            walk(node.get("children", []))

    walk(data["outliner"])
    elements = []
    for original in data["elements"]:
        if original["uuid"] not in keep or original.get("export", True) is False:
            continue
        assert original.get("type", "cube") == "cube"
        element = {"name": original.get("name", "cube"), "from": original["from"], "to": original["to"], "faces": {}}
        rotations = original.get("rotation", [0, 0, 0])
        nonzero = [(axis, value) for axis, value in zip("xyz", rotations) if abs(value) > 1e-8]
        assert len(nonzero) <= 1, original
        if nonzero:
            axis, angle = nonzero[0]
            assert angle in [-45, -22.5, 22.5, 45], original
            element["rotation"] = {"origin": original.get("origin", [8, 8, 8]), "axis": axis, "angle": angle, "rescale": original.get("rescale", False)}
        for direction, face in original["faces"].items():
            if face.get("texture") is None:
                continue
            index = face["texture"]
            assert isinstance(index, int) and 0 <= index < len(data["textures"])
            width, height = data["resolution"]["width"], data["resolution"]["height"]
            uv = face["uv"]
            converted = {"uv": [uv[0] * 16 / width, uv[1] * 16 / height, uv[2] * 16 / width, uv[3] * 16 / height], "texture": f"#t{index}"}
            if face.get("rotation"):
                converted["rotation"] = face["rotation"]
            element["faces"][direction] = converted
        assert element["faces"]
        elements.append(element)
    return elements, removed


def rotate_point(point, rotation):
    result = list(point)
    for axis, angle in enumerate(rotation):
        a, b = [(1, 2), (2, 0), (0, 1)][axis]
        cosine, sine = math.cos(math.radians(angle)), math.sin(math.radians(angle))
        result[a], result[b] = result[a] * cosine - result[b] * sine, result[a] * sine + result[b] * cosine
    return result


def element_corners(element):
    result = []
    for ix in range(8):
        point = [element["to" if ix & (1 << a) else "from"][a] for a in range(3)]
        if "rotation" in element:
            rotation = element["rotation"]
            origin = rotation["origin"]
            vector = [point[a] - origin[a] for a in range(3)]
            angle = rotation["angle"]
            axis = "xyz".index(rotation["axis"])
            if rotation.get("rescale", False):
                factor = 1 / math.cos(math.radians(angle))
                vector = [v if a == axis else v * factor for a, v in enumerate(vector)]
            angles = [0, 0, 0]
            angles[axis] = angle
            point = [v + origin[a] for a, v in enumerate(rotate_point(vector, angles))]
        result.append(point)
    return result


def bounds(elements, fixed_rotation=(0, 0, 0)):
    points = []
    for element in elements:
        points.extend([[x + 8 for x in rotate_point([v - 8 for v in point], fixed_rotation)] for point in element_corners(element)])
    return {"min": [min(p[a] for p in points) for a in range(3)], "max": [max(p[a] for p in points) for a in range(3)]}


def normalize(elements, height, fixed_rotation):
    old_bounds = bounds(elements)
    center = [(lo + hi) / 2 for lo, hi in zip(old_bounds["min"], old_bounds["max"])]
    rotated_points = [rotate_point([p[a] - center[a] for a in range(3)], fixed_rotation) for e in elements for p in element_corners(e)]
    size = [max(p[a] for p in rotated_points) - min(p[a] for p in rotated_points) for a in range(3)]
    scale = min(height / size[1], 22 / size[0], 12 / size[2])
    output = copy.deepcopy(elements)
    for e in output:
        for key in ["from", "to"]:
            e[key] = [round((v - center[a]) * scale + 8, 8) for a, v in enumerate(e[key])]
        if "rotation" in e:
            e["rotation"]["origin"] = [round((v - center[a]) * scale + 8, 8) for a, v in enumerate(e["rotation"]["origin"])]
    return output, {"sourceCenter": center, "uniformScale": scale, "formula": "normalized_position = (source_position - sourceCenter) * uniformScale + [8,8,8]", "fixedRotationDegrees": list(fixed_rotation), "sourceRotatedBounds": old_bounds}


def geometry(elements, fixed_rotation):
    shape_only = [{k: e[k] for k in ["from", "to", "rotation"] if k in e} for e in elements]
    return {"format": "Minecraft Java cuboid elements", "cuboids": len(elements), "faces": sum(len(e["faces"]) for e in elements), "meshes": 0, "positiveVolumeCuboids": sum(all(t > f for f, t in zip(e["from"], e["to"])) for e in elements), "invertedHullCuboidsPreserved": sum(all(t < f for f, t in zip(e["from"], e["to"])) for e in elements), "zeroThicknessCuboids": sum(any(abs(t - f) < 1e-8 for f, t in zip(e["from"], e["to"])) for e in elements), "rotatedCuboids": sum(bool(e.get("rotation", {}).get("angle")) for e in elements), "boundsBeforeFixedDisplay": bounds(elements), "boundsAfterFixedDisplay": bounds(elements, fixed_rotation), "geometrySha256": hashlib.sha256(json.dumps(shape_only, sort_keys=True).encode()).hexdigest()}


def native_candidate(key, relative, identifier, label, height, fixed_rotation=(0, 0, 0), selection=None, notes=""):
    source_model = source_file(key, relative)
    data = json.loads(pathlib.Path(source_model["path"]).read_text(encoding="utf-8"))
    assert not re.search(r"all rights reserved", json.dumps(data, ensure_ascii=False), re.I)
    directory = ROOT / "normalized" / identifier
    directory.mkdir(parents=True, exist_ok=True)
    textures = {}
    texture_files = []
    omitted = []
    if key == "loy":
        elements, omitted = bb_elements(data, selection)
        used = sorted({int(f["texture"][2:]) for e in elements for f in e["faces"].values()})
        for index in used:
            texture = data["textures"][index]
            assert texture["source"].startswith("data:image/png;base64,")
            image = base64.b64decode(texture["source"].split(",", 1)[1])
            path = directory / "textures" / f"t{index}.png"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(image)
            assert image[:8] == b"\x89PNG\r\n\x1a\n"
            width, pixel_height = struct.unpack(">II", image[16:24])
            texture_record = record(path, format="PNG", width=width, height=pixel_height, source="Embedded PNG decoded verbatim from verified upstream .bbmodel", embeddedIndex=index, originalTextureName=texture["name"], sourceModelSHA256=source_model["sha256"])
            if pixel_height > width and pixel_height % width == 0:
                assert texture.get("frame_order_type", "loop") == "loop" and not texture.get("frame_order")
                metadata_path = path.with_suffix(".png.mcmeta")
                animation = {"frametime": texture.get("frame_time", 1)}
                if texture.get("frame_interpolate"):
                    animation["interpolate"] = True
                write_json(metadata_path, {"animation": animation})
                texture_record["animationMetadata"] = record(metadata_path, source="Derived from original Blockbench texture frame_time and full-height PNG strip", frames=pixel_height // width)
            texture_files.append(texture_record)
            textures[f"t{index}"] = f"noqui:cosmetics/{identifier}/t{index}"
        uv_note = "Per-face UVs divided by source Blockbench resolution and multiplied by 16; UV order and face rotations retained. Organizational groups have no rotations. Embedded PNG bytes decoded verbatim. World-block cullface flags omitted for an isolated item display. Animated PNG strips retain original pixels with generated mcmeta from the source frame_time."
    else:
        elements = copy.deepcopy(data["elements"])
        used = {f["texture"][1:] for e in elements for f in e["faces"].values()}
        for texture_key in sorted(used):
            location = data["textures"][texture_key]
            namespace, tail = location.split(":")
            original_relative = f"common/src/main/resources/assets/{namespace}/textures/{tail}.png"
            original = source_file(key, original_relative)
            path = directory / "textures" / f"t{texture_key}.png"
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(original["path"], path)
            image = path.read_bytes()
            width, pixel_height = struct.unpack(">II", image[16:24])
            texture_files.append({**record(path, format="PNG", width=width, height=pixel_height), "upstreamFile": original})
            textures[texture_key] = f"noqui:cosmetics/{identifier}/t{texture_key}"
        uv_note = "Original native Java per-face UVs and face rotations retained verbatim. Original _hand model selected; the generated inventory icon model is not used. PNG bytes copied verbatim."
    original_count = len(elements)
    elements, transform = normalize(elements, height, fixed_rotation)
    provenance_data = provenance(key)
    source_url = f"https://github.com/{provenance_data['repository']}/blob/{provenance_data['commit']}/{relative.replace(' ', '%20').replace('&', '%26')}"
    model = {"credit": f"Adapted from {provenance_data['author']}; {provenance_data['licenseName']}; see ATTRIBUTION.txt", "ambientocclusion": False, "gui_light": "front", "textures": textures, "elements": elements, "display": {"fixed": {"rotation": list(fixed_rotation), "translation": [0, 0, 0], "scale": [1, 1, 1]}}}
    model_path = directory / "model.json"
    item_path = directory / "item.json"
    write_json(model_path, model)
    write_json(item_path, {"model": {"type": "minecraft:model", "model": f"noqui:item/cosmetics/{identifier}"}})
    shutil.copyfile(provenance_data["licenseEvidence"]["path"], directory / "LICENSE.txt")
    license_note = "Assets explicitly released under CC0 in the upstream README; optional attribution retained."
    if key == "melodies":
        license_note = "Repository GPL-3.0 applies to models and textures; explicit exclusions cover sounds and melodies only. No sounds or melodies staged. Retain GPL license, notices, original model/texture sources, and this modification script when distributing these adaptations."
    attribution = f"Original: {provenance_data['author']}\nSource: {source_url}\nCommit: {provenance_data['commit']}\nLicense: {provenance_data['licenseName']}\nLicense URL: {provenance_data['licenseURL']}\n\nModified 2026-10-24 for Noqui backwear research: extracted static native cuboids, remapped texture resource names, recentered and uniformly scaled geometry, and replaced display settings for ItemDisplay FIXED.\n{uv_note}\n{notes}\n\n{license_note}\nOriginal sources remain under upstream/ and are identified by SHA256 in candidates.json. Changes are reproducible with normalize_assets.py.\n"
    (directory / "ATTRIBUTION.txt").write_text(attribution, encoding="utf-8")
    result = {"id": identifier, "label": label, "labelKo": label, "slot": "back", "status": "native_ready_parent_visual_qa_required", "sourceURL": source_url, "sourceRawURL": source_model["sourceURL"], **provenance_data, "licenseNotes": license_note, "sourceModelFiles": [source_model], "sourceTextureFiles": texture_files, "modelPath": str(model_path), "modelSHA256": sha(model_path), "texturePaths": [t["path"] for t in texture_files], "normalizedModel": record(model_path, format="Minecraft Java item model JSON"), "normalizedTextures": texture_files, "itemDefinition": record(item_path), "geometry": geometry(elements, fixed_rotation), "sourceCuboidsSelected": original_count, "sourceCuboidsTotal": len(data["elements"]), "conversion": {"performed": True, "scriptPath": str(ROOT / "normalize_assets.py"), "notes": uv_note + " " + notes, "transform": transform, "omittedAlternativePoseGroupUUIDs": omitted}, "attachmentConversionNotes": f"Rigid back-slot adaptation. Runtime anchor y=1.1, back offset=0.28, runtime scale=0.8, ItemDisplay FIXED, 16 units/block. Geometry centered at [8,8,8]; fixed rotation {list(fixed_rotation)} is intentional and must be retained. {notes} Parent must check yaw (+Z player-front convention), body clearance, size, and transparency in game.", "integrationTargets": {"model": f"assets/noqui/models/item/cosmetics/{identifier}.json", "item": f"assets/noqui/items/{identifier}.json", "textures": {x["path"]: f"assets/noqui/textures/cosmetics/{identifier}/{pathlib.Path(x['path']).name}" for x in texture_files}}, "verification": {"upstreamGitBlobVerified": True, "jsonParsed": True, "texturesPresent": True, "uvPreserved": True, "meshVoxelization": False, "gameRenderTested": False, "deployed": False}}
    result["integrationTargets"]["textureMetadata"] = {x["animationMetadata"]["path"]: f"assets/noqui/textures/cosmetics/{identifier}/{pathlib.Path(x['animationMetadata']['path']).name}" for x in texture_files if "animationMetadata" in x}
    assert result["geometry"]["positiveVolumeCuboids"] > 0, identifier
    return result


LOYS = [
    ("armor & costumes/packs/230516_backpack.bbmodel", "back_trail_rucksack", "탐험 배낭", 12, (0, 180, 0), None, "Distinct CC0 backpack by Loy; not the existing jashduck backpack. Source straps point -Z; fixed yaw 180 moves them toward player-front +Z."),
    ("tools & weapons/instruments/220913_guitar.bbmodel", "back_acoustic_guitar", "어쿠스틱 기타", 22, (0, 0, 0), None, "Rigid guitar prop worn vertically on the back; retains real body, neck, strings and bridge cuboids."),
    ("tools & weapons/shields/230419_shield_0_0.bbmodel", "back_kite_shield", "기사의 방패", 18, (0, 0, 0), None, "Tall bent-panel shield with a separate grip; inspect outward-facing panel and grip clearance."),
    ("tools & weapons/shields/230618_small_shield.bbmodel", "back_round_shield", "둥근 방패", 14, (0, 0, 0), None, "Octagonal round shield built from cuboids, distinct from the tall shield."),
    ("furniture & decoration/220130_snow_globe.bbmodel", "back_winter_snow_globe", "겨울 스노볼", 12, (0, 0, 0), None, "Seasonal novelty back attachment. Preserve translucent shell texture; parent must check alpha ordering."),
    ("furniture & decoration/220614_phonograph.bbmodel", "back_phonograph", "축음기 배낭", 14, (0, 0, 0), "phonograph_static", "Rigid novelty prop. Exactly one disk variant (disk_0) and the first hand frame are selected; 15 other disk groups and 15 other hand frames are excluded to prevent overlapping alternatives."),
    ("furniture & decoration/230627_easel.bbmodel", "back_artist_easel", "화가의 이젤", 20, (0, 0, 0), None, "Artist's rigid travel easel adapted as backwear; includes three-dimensional support legs. Depth capped at 12 model units before runtime scale."),
    ("furniture & decoration/230728_gravestone.bbmodel", "back_halloween_gravestone", "할로윈 묘비", 16, (0, 0, 0), None, "Halloween novelty back attachment; original freestanding gravestone prop retained as rigid cuboids."),
    ("tools & weapons/230418_crux.bbmodel", "back_cleric_cross", "성직자의 십자가", 18, (0, 0, 0), None, "Rigid decorative cross carried on back, adapted from original CC0 prop."),
    ("tools & weapons/axes/230429_double_axe.bbmodel", "back_double_axe", "양날 도끼", 20, (0, 90, 0), None, "Back-carried double axe; fixed yaw 90 turns the original blade plane across the player's back."),
    ("tools & weapons/hammer/230512_hammer.bbmodel", "back_smith_hammer", "대장장이 망치", 16, (0, 0, 0), None, "Back-carried smith's hammer; preserves original angled cube geometry."),
    ("tools & weapons/swords/230419_katana_shell.bbmodel", "back_katana_scabbard", "카타나 칼집", 24, (0, 0, 0), None, "Original asset is a scabbard only; no blade is invented or claimed. Suitable as long rigid back attachment."),
    ("tools & weapons/wands/230418_wizard_wand.bbmodel", "back_arcane_staff", "마법사의 지팡이", 24, (0, 0, 0), None, "Back-carried staff with distinct orb and cage geometry; preserves both embedded textures."),
    ("tools & weapons/bows/230418_bow_0_0.bbmodel", "back_hunters_bow", "사냥꾼의 활", 22, (0, 90, 0), None, "Rigid unpulled hunting bow adapted as backwear; genuine curved limb cuboids and bowstring retained, not an inventory sprite."),
]

MELODIES = [
    ("bagpipe", "back_bagpipe", "백파이프", 18, (0, 0, 0)),
    ("didgeridoo", "back_didgeridoo", "디저리두", 24, (0, 0, 0)),
    ("ender_bass", "back_ender_bass", "엔더 베이스", 12, (0, 0, 0)),
    ("handpan", "back_handpan", "핸드팬", 14, (90, 0, 0)),
    ("lute", "back_lute", "류트", 18, (0, 0, 0)),
    ("piano", "back_portable_keyboard", "휴대용 건반", 18, (0, 0, 90)),
    ("tiny_drum", "back_tiny_drum", "작은 북", 12, (0, 0, 0)),
    ("triangle", "back_triangle", "트라이앵글", 14, (0, 0, 0)),
    ("trumpet", "back_trumpet", "트럼펫", 18, (90, 0, 0)),
    ("vielle", "back_vielle", "비엘 현악기", 18, (0, 0, 0)),
]


def main():
    candidates = []
    for relative, identifier, label, height, rotation, selection, notes in LOYS:
        candidates.append(native_candidate("loy", "models/java-model/" + relative, identifier, label, height, rotation, selection, notes))
    for instrument, identifier, label, height, rotation in MELODIES:
        candidates.append(native_candidate("melodies", f"common/src/main/resources/assets/immersive_melodies/models/item/{instrument}_hand.json", identifier, label, height, rotation, notes="Original mod instrument prop adapted as rigid backwear. Playback functionality and sound assets are not included."))
    key = "ironjetpacks"
    model_file = source_file(key, "src/main/java/com/blakebr0/ironjetpacks/client/model/JetpackModel.java")
    jetpack_textures = [source_file(key, "src/main/resources/assets/ironjetpacks/textures/entity/equipment/humanoid/" + name) for name in ["jetpack.png", "jetpack_overlay.png"]]
    prov = provenance(key)
    candidates.append({"id": "back_twin_canister_jetpack", "label": "쌍통 제트팩", "labelKo": "쌍통 제트팩", "slot": "back", "status": "convertible_source_staged", "sourceURL": f"https://github.com/{prov['repository']}/blob/{prov['commit']}/src/main/java/com/blakebr0/ironjetpacks/client/model/JetpackModel.java", **prov, "licenseNotes": "MIT repository license; no asset-specific reserved-rights notice found in the inspected model, resource tree, or README. Preserve BlakeBr0 copyright and full MIT notice. Artwork includes grayscale tint base and colored overlay; preserve the source textures and renderer's composition during conversion.", "sourceModelFiles": [model_file], "sourceTextureFiles": jetpack_textures, "modelPath": model_file["path"], "modelSHA256": model_file["sha256"], "texturePaths": [t["path"] for t in jetpack_textures], "geometry": {"format": "Java CubeListBuilder .addBox source", "sourceCuboidsIncludingEnergyAlternatives": 23, "visibleCuboidsAtOneEnergyState": 13, "staticCuboids": 11, "energyBarAlternativesPerSide": 6, "meshes": 0, "textureResolution": [64, 64]}, "conversion": {"performed": False, "notes": "Not exported. Parse 11 fixed addBox definitions plus two energy bars at exactly one i value (0..5), use texOffs box UV unwrapping and mirror() flags, invert source Y, recenter at [8,8,8], and preserve base/overlay composition. Humanoid base body/limbs from createMesh are not part of the jetpack and must be omitted."}, "attachmentConversionNotes": "Native cuboid conversion is plausible and small (13 visible cubes), but UV unwrap, mirroring and overlay still require verified conversion. Use back anchor y=1.1, back offset=0.28, runtime scale=0.8. No normalized model is claimed.", "verification": {"upstreamGitBlobVerified": True, "modelInspected": True, "texturesPresent": True, "gameRenderTested": False, "deployed": False}})
    assert len(candidates) == 25 and len({x["id"] for x in candidates}) == 25
    hashes = [c["geometry"].get("geometrySha256") for c in candidates if c["conversion"]["performed"]]
    assert len(hashes) == len(set(hashes)), "Duplicate normalized geometries"
    write_json(ROOT / "candidates.json", candidates)
    write_json(ROOT / "ready.json", [c for c in candidates if c["conversion"]["performed"]])
    print(json.dumps({"candidates": len(candidates), "nativeReady": len(hashes), "sourceOnlyConvertible": 1, "nativeCuboids": {c["id"]: c["geometry"]["cuboids"] for c in candidates if c["conversion"]["performed"]}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
