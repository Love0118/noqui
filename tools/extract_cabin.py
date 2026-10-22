"""Extract a sanitized, deterministic cabin chunk crop. Requires nbtlib==2.0.4.

Never runs serverpack code or extracts arbitrary ZIP paths. The destination must
not exist. Source NBT version is preserved for Paper's normal data fixer.
"""
from __future__ import annotations

import argparse
from collections import Counter
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct
import zipfile
import zlib

import nbtlib as n

CHUNKS = (18, 33, -20, -5)  # inclusive: x 288..543, z -320..-65
SPAWN = (411.5, 11.0, -205.5, 0.3003012, 0.0)
CENTER = (416.0, -192.0)
BANNED = {"minecraft:" + s for s in (
    "command_block", "chain_command_block", "repeating_command_block",
    "spawner", "mob_spawner", "trial_spawner", "structure_block", "jigsaw", "end_gateway",
)}
DROP_KEYS = {
    "Items", "items", "Item", "item", "Book", "book", "RecordItem",
    "LootTable", "LootTableSeed", "loot_table", "loot_table_seed",
    "Command", "LastOutput", "BukkitValues", "PublicBukkitValues",
    "clickEvent", "click_event", "Bees", "bees",
}


def nbt_bytes(tag):
    out = io.BytesIO()
    tag.write(out)
    return out.getvalue()


def read_nbt(raw):
    return n.File.parse(io.BytesIO(raw))


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


def read_chunk(raw, cx, cz):
    idx = cx % 32 + (cz % 32) * 32
    location = int.from_bytes(raw[idx * 4:idx * 4 + 4], "big")
    offset, sectors = location >> 8, location & 255
    if not offset or not sectors:
        raise ValueError(f"Missing required chunk {cx},{cz}")
    start = offset * 4096
    if start < 8192 or start + sectors * 4096 > len(raw):
        raise ValueError("Invalid region allocation")
    length = struct.unpack_from(">I", raw, start)[0]
    if not 1 < length <= sectors * 4096 - 4:
        raise ValueError("Invalid chunk length")
    kind = raw[start + 4]
    payload = raw[start + 5:start + 4 + length]
    if kind == 2:
        payload = zlib.decompress(payload)
    elif kind == 1:
        payload = gzip.decompress(payload)
    elif kind != 3:
        raise ValueError(f"Unsupported compression {kind}; external chunks refused")
    doc = read_nbt(payload)
    if (int(doc["xPos"]), int(doc["zPos"])) != (cx, cz):
        raise ValueError("Chunk coordinate mismatch")
    if str(doc["Status"]) not in {"full", "minecraft:full"}:
        raise ValueError("Crop requires fully generated source chunks")
    return doc


def packed_indices(states):
    palette = states["palette"]
    if len(palette) == 1:
        return [0] * 4096
    bits = max(4, (len(palette) - 1).bit_length())
    per_long, mask = 64 // bits, (1 << bits) - 1
    words = states["data"]
    if len(words) != (4096 + per_long - 1) // per_long:
        raise ValueError("Unexpected padded block-state packing")
    values = [((int(words[i // per_long]) & ((1 << 64) - 1)) >>
               ((i % per_long) * bits)) & mask for i in range(4096)]
    if max(values) >= len(palette):
        raise ValueError("Block palette index out of bounds")
    return values


def scrub(tag):
    """Remove inventory/loot and click actions, including JSON sign components."""
    if isinstance(tag, n.Compound):
        return n.Compound({k: scrub(v) for k, v in tag.items() if k not in DROP_KEYS})
    if isinstance(tag, n.List):
        return type(tag)(scrub(v) for v in tag)
    if isinstance(tag, n.String) and str(tag).lstrip().startswith(("{", "[")):
        try:
            def clean_json(v):
                if isinstance(v, dict):
                    return {k: clean_json(w) for k, w in v.items() if k not in DROP_KEYS}
                if isinstance(v, list):
                    return [clean_json(w) for w in v]
                return v
            value = json.loads(str(tag))
            cleaned = clean_json(value)
            if value != cleaned:
                return n.String(json.dumps(cleaned, ensure_ascii=False, separators=(",", ":")))
        except (ValueError, TypeError):
            pass
    return copy.deepcopy(tag)


def sanitize_chunk(original, counts):
    chunk = copy.deepcopy(original)
    for section in chunk["sections"]:
        states = section.get("block_states")
        if states:
            palette = states["palette"]
            forbidden = {i for i, p in enumerate(palette) if str(p["Name"]) in BANNED}
            if forbidden:
                counts["dangerous_blocks_removed"] += sum(i in forbidden for i in packed_indices(states))
                for i in forbidden:
                    palette[i] = n.Compound({"Name": n.String("minecraft:air")})
        for key in ("BlockLight", "SkyLight"):
            section.pop(key, None)
    kept = []
    for entity in chunk.get("block_entities", []):
        if str(entity["id"]) in BANNED:
            counts["dangerous_block_entities_removed"] += 1
        else:
            counts["decorative_block_entities_retained"] += 1
            kept.append(scrub(entity))
    chunk["block_entities"] = n.List[n.Compound](kept)
    for key in ("Entities", "entities", "BukkitValues", "ChunkBukkitValues"):
        if key in chunk:
            counts["embedded_actor_tags_removed"] += 1
            chunk.pop(key)
    for key in ("block_ticks", "fluid_ticks"):
        counts[key + "_removed"] += len(chunk.get(key, []))
        chunk[key] = n.List[n.Compound]()
    counts["postprocessing_entries_removed"] += sum(len(v) for v in chunk.get("PostProcessing", []))
    chunk["PostProcessing"] = n.List[n.List[n.Short]]()
    chunk["structures"] = n.Compound({"References": n.Compound(), "starts": n.Compound()})
    chunk.pop("Heightmaps", None)
    chunk["isLightOn"] = n.Byte(0)
    chunk.pop("starlight.light_version", None)
    return chunk


def write_region(chunks, path):
    header, body = bytearray(8192), bytearray()
    sector = 2
    for (cx, cz), chunk in sorted(chunks.items()):
        payload = zlib.compress(nbt_bytes(chunk), 9)
        entry = struct.pack(">I", len(payload) + 1) + b"\x02" + payload
        count = (len(entry) + 4095) // 4096
        if count > 255:
            raise ValueError("Oversized chunk")
        idx = cx % 32 + (cz % 32) * 32
        header[idx * 4:idx * 4 + 4] = ((sector << 8) | count).to_bytes(4, "big")
        body.extend(entry + b"\0" * (count * 4096 - len(entry)))
        sector += count
    path.write_bytes(header + body)


def block_at(chunk, x, y, z):
    section = next(s for s in chunk["sections"] if int(s["Y"]) == y // 16)
    states = section["block_states"]
    idx = (y % 16) * 256 + (z % 16) * 16 + x % 16
    return str(states["palette"][packed_indices(states)[idx]]["Name"])


def verify_geometry(source, result):
    assert len(source["sections"]) == len(result["sections"])
    for a, b in zip(source["sections"], result["sections"]):
        assert a["Y"] == b["Y"]
        if "biomes" in a:
            assert nbt_bytes(a["biomes"]) == nbt_bytes(b["biomes"])
        if "block_states" not in a:
            continue
        pa, pb = a["block_states"], b["block_states"]
        assert len(pa["palette"]) == len(pb["palette"])
        if "data" in pa:
            assert nbt_bytes(pa["data"]) == nbt_bytes(pb["data"])
        for p, q in zip(pa["palette"], pb["palette"]):
            if str(p["Name"]) in BANNED:
                assert q == n.Compound({"Name": n.String("minecraft:air")})
            else:
                assert nbt_bytes(p) == nbt_bytes(q)


def extract(archive, output):
    if output.exists():
        raise FileExistsError(f"Refusing to overwrite {output}")
    archive_hash = sha256(archive.read_bytes())
    counts, regions, sources = Counter(), {}, {}
    with zipfile.ZipFile(archive) as z:
        levels = [name for name in z.namelist() if name.endswith("/cabin/level.dat")]
        if len(levels) != 1:
            raise ValueError("Expected exactly one cabin world")
        prefix = levels[0][:-len("level.dat")]
        level = read_nbt(gzip.decompress(z.read(levels[0])))
        data = level["Data"]
        if int(data["DataVersion"]) != 4440:
            raise ValueError("Reviewed format is Java 1.21.8 (DataVersion 4440)")
        source_version = data["Version"].unpack()
        source_spawn = {k: data[k].unpack() for k in ("SpawnX", "SpawnY", "SpawnZ")}
        cache = {}
        minx, maxx, minz, maxz = CHUNKS
        for cx in range(minx, maxx + 1):
            for cz in range(minz, maxz + 1):
                r = (cx // 32, cz // 32)
                if r not in cache:
                    cache[r] = z.read(prefix + f"region/r.{r[0]}.{r[1]}.mca")
                source = read_chunk(cache[r], cx, cz)
                if int(source["DataVersion"]) != 4440:
                    raise ValueError("Mixed chunk versions require separate review")
                sources[(cx, cz)] = source
                regions.setdefault(r, {})[(cx, cz)] = sanitize_chunk(source, counts)
        for key in ("Player", "DragonFight", "WanderingTraderId", "CustomBossEvents"):
            data.pop(key, None)
        data["LevelName"] = n.String("noqui_cabin")
        data["SpawnX"], data["SpawnY"], data["SpawnZ"] = n.Int(411), n.Int(11), n.Int(-206)
        data["SpawnAngle"] = n.Float(SPAWN[3])
        data["BorderCenterX"], data["BorderCenterZ"] = map(n.Double, CENTER)
        data["BorderSize"], data["BorderSizeLerpTarget"] = n.Double(250), n.Double(250)
        data["BorderSizeLerpTime"] = n.Long(0)
        data["ScheduledEvents"] = n.List[n.Compound]()
        data["DataPacks"] = n.Compound({"Enabled": n.List[n.String](["vanilla"]), "Disabled": n.List[n.String]()})
        data["GameType"] = n.Int(2)
        data["WanderingTraderSpawnChance"], data["WanderingTraderSpawnDelay"] = n.Int(0), n.Int(2147483647)
        rules = data["GameRules"]
        for key, value in {"doMobSpawning": "false", "doTraderSpawning": "false", "doPatrolSpawning": "false", "doDaylightCycle": "false", "doWeatherCycle": "false", "doFireTick": "false", "mobGriefing": "false", "randomTickSpeed": "0", "spawnRadius": "0"}.items():
            rules[key] = n.String(value)
        data["WorldGenSettings"]["generate_features"] = n.Byte(0)
        data["WorldGenSettings"]["dimensions"]["minecraft:overworld"]["generator"] = n.Compound({
            "type": n.String("minecraft:flat"), "settings": n.Compound({
                "biome": n.String("minecraft:plains"), "features": n.Byte(0), "lakes": n.Byte(0),
                "structure_overrides": n.List[n.String](),
                "layers": n.List[n.Compound]([n.Compound({"height": n.Int(1), "block": n.String("minecraft:air")})]),
            }),
        })
        (output / "region").mkdir(parents=True)
        (output / "level.dat").write_bytes(gzip.compress(nbt_bytes(level), mtime=0))
        for r, chunks in regions.items():
            write_region(chunks, output / "region" / f"r.{r[0]}.{r[1]}.mca")

    # Re-read serialized region allocations and validate every retained section.
    seen = set()
    block_ids = Counter()
    for path in sorted((output / "region").glob("*.mca")):
        _, rx, rz, _ = path.name.split(".")
        raw = path.read_bytes()
        for idx in range(1024):
            if not int.from_bytes(raw[idx * 4:idx * 4 + 4], "big"):
                continue
            cx, cz = int(rx) * 32 + idx % 32, int(rz) * 32 + idx // 32
            assert (cx, cz) in sources and (cx, cz) not in seen
            result = read_chunk(raw, cx, cz)
            verify_geometry(sources[(cx, cz)], result)
            assert not result["block_ticks"] and not result["fluid_ticks"]
            assert not result["PostProcessing"]
            for entity in result["block_entities"]:
                assert str(entity["id"]) not in BANNED
                assert nbt_bytes(entity) == nbt_bytes(scrub(entity))
            assert not any(key in result for key in ("Entities", "entities", "ChunkBukkitValues"))
            for section in result["sections"]:
                if "block_states" in section:
                    states = section["block_states"]
                    assert not any(str(p["Name"]) in BANNED for p in states["palette"])
                    for i, num in Counter(packed_indices(states)).items():
                        block_ids[str(states["palette"][i]["Name"])] += num
            if (cx, cz) == (25, -13):
                assert block_at(result, 411, 10, -206) == "minecraft:stone_bricks"
                assert block_at(result, 411, 11, -206) == "minecraft:air"
                assert block_at(result, 411, 12, -206) == "minecraft:air"
            seen.add((cx, cz))
    assert seen == set(sources) and len(seen) == 256
    loaded = read_nbt(gzip.decompress((output / "level.dat").read_bytes()))["Data"]
    assert "Player" not in loaded and not loaded["ScheduledEvents"]
    assert int(loaded["DataVersion"]) == 4440 and float(loaded["BorderSize"]) == 250
    files = [{"path": p.relative_to(output).as_posix(), "bytes": p.stat().st_size, "sha256": sha256(p.read_bytes())} for p in sorted(output.rglob("*")) if p.is_file()]
    fingerprint = sha256("".join(f"{f['path']}\0{f['sha256']}\n" for f in files).encode())
    manifest = {"source_archive": str(archive), "source_archive_sha256": archive_hash,
        "source_world": "cabin", "target_world": "noqui_cabin", "source_version": source_version, "source_spawn": source_spawn,
        "retained_chunks_inclusive": list(CHUNKS), "retained_blocks_inclusive": [288, 543, -320, -65],
        "chunk_count": len(seen), "border_center": list(CENTER), "border_size": 250,
        "play_bounds": [291, 541, -317, -67], "safe_spawn_xyz_yaw_pitch": list(SPAWN),
        "sanitization_counts": dict(counts), "retained_block_counts": dict(sorted(block_ids.items())),
        "world_files": files, "world_content_fingerprint_sha256": fingerprint,
        "checks": ["256 expected chunks, exact allocations and coordinates", "all block states and biomes preserved except explicit dangerous block replacements", "no external actors/playerdata/datapacks/POI copied", "no command/spawner/structure/jigsaw/end-gateway palettes or block entities", "no scheduled ticks or postprocessing", "all block-entity inventory/loot and sign click actions scrubbed", "spawn solid floor and two air blocks", "level NBT round-trip; source DataVersion retained"]}
    (output.parent / "extraction-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: manifest[k] for k in ("source_archive_sha256", "chunk_count", "sanitization_counts", "world_files", "world_content_fingerprint_sha256", "checks")}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    extract(args.archive, args.output)
