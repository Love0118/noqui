"""Synthetic format/sanitization regressions, without running serverpack code."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import nbtlib as n

from extract_cabin import (
    extract, nbt_bytes, packed_indices, read_chunk, sanitize_chunk,
    scrub, verify_geometry, write_region,
)
from collections import Counter


class ExtractionTests(unittest.TestCase):
    def chunk(self):
        # Negative coordinates and a palette crossing padded 64-bit boundaries.
        words = [sum(((j * 16 + k) % 2) << (k * 4) for k in range(16)) for j in range(256)]
        words = [w if w < 2**63 else w - 2**64 for w in words]
        return n.File({
            "xPos": n.Int(-1), "zPos": n.Int(-33), "DataVersion": n.Int(4440),
            "Status": n.String("minecraft:full"),
            "sections": n.List[n.Compound]([n.Compound({
                "Y": n.Byte(0),
                "block_states": n.Compound({"palette": n.List[n.Compound]([
                    n.Compound({"Name": n.String("minecraft:stone_bricks")}),
                    n.Compound({"Name": n.String("minecraft:command_block"), "Properties": n.Compound({"facing": n.String("up")})}),
                ]), "data": n.LongArray(words)}),
                "SkyLight": n.ByteArray([0] * 2048),
            })]),
            "block_entities": n.List[n.Compound]([
                n.Compound({"id": n.String("minecraft:command_block"), "Command": n.String("stop")}),
                n.Compound({"id": n.String("minecraft:mob_spawner"), "SpawnData": n.Compound()}),
                n.Compound({"id": n.String("minecraft:barrel"), "Items": n.List[n.Compound]([n.Compound()]), "LootTable": n.String("minecraft:chests/abandoned_mineshaft")}),
            ]),
            "block_ticks": n.List[n.Compound]([n.Compound()]),
            "fluid_ticks": n.List[n.Compound]([n.Compound()]),
            "PostProcessing": n.List[n.List[n.Short]]([n.List[n.Short]([n.Short(1)])]),
            "Entities": n.List[n.Compound]([n.Compound()]),
        })

    def test_dangerous_states_replaced_without_changing_other_blocks(self):
        source = self.chunk()
        before = nbt_bytes(source)
        counts = Counter()
        result = sanitize_chunk(source, counts)
        verify_geometry(source, result)
        self.assertEqual(before, nbt_bytes(source))
        self.assertEqual(counts["dangerous_blocks_removed"], 2048)
        self.assertEqual(counts["dangerous_block_entities_removed"], 2)
        self.assertEqual(packed_indices(source["sections"][0]["block_states"]), [i % 2 for i in range(4096)])
        self.assertNotIn("Entities", result)
        self.assertFalse(result["block_ticks"])
        self.assertFalse(result["fluid_ticks"])
        self.assertFalse(result["PostProcessing"])
        self.assertNotIn("Items", result["block_entities"][0])
        self.assertNotIn("LootTable", result["block_entities"][0])

    def test_sign_json_actions_removed_text_preserved(self):
        source = n.Compound({"messages": n.List[n.String]([n.String(json.dumps({
            "text": "Quiz house", "extra": [{"text": " start", "clickEvent": {"action": "run_command", "value": "/op user"}}],
            "click_event": {"action": "open_url", "url": "https://example.invalid"},
        }))])})
        result = scrub(source)
        message = json.loads(str(result["messages"][0]))
        self.assertEqual(message, {"text": "Quiz house", "extra": [{"text": " start"}]})
        self.assertEqual(nbt_bytes(result), nbt_bytes(scrub(result)))

    def test_region_roundtrip_negative_coordinates_and_missing_chunk(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "r.-1.-2.mca"
            source = self.chunk()
            write_region({(-1, -33): source}, path)
            raw = path.read_bytes()
            self.assertEqual(nbt_bytes(read_chunk(raw, -1, -33)), nbt_bytes(source))
            with self.assertRaisesRegex(ValueError, "Missing"):
                read_chunk(raw, -2, -33)
            with self.assertRaisesRegex(ValueError, "Invalid region allocation"):
                read_chunk(raw[:8192], -1, -33)

    def test_changed_geometry_is_detected(self):
        source = self.chunk()
        result = copy.deepcopy(source)
        result["sections"][0]["block_states"]["palette"][0]["Name"] = n.String("minecraft:air")
        with self.assertRaises(AssertionError):
            verify_geometry(source, result)

    def test_existing_destination_is_refused_before_reading_archive(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(FileExistsError):
                extract(Path(folder) / "absent.zip", Path(folder))


if __name__ == "__main__":
    unittest.main()
