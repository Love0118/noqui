# Noqui lobby assets

The pack targets resource format **97.1** and uses native `minecraft:model` item definitions. It contains eleven cuboid models with explicit `fixed` transforms, a bundled64×64 slim female shopkeeper skin (navy suit, chestnut hair, gold monocle), and five exact user-provided quiz cues. It contains no quiz music, shaders or full source archive.

Build and verify using Python3's standard library:

```text
python3 noqui/lobby-assets/build_pack.py
python3 noqui/lobby-assets/build_pack.py --check
python3 noqui/lobby-assets/test_pack.py
```

The normal build writes the small tracked `noqui/lobby-assets/lobby-pack.properties` descriptor and ignored `.runtime/noqui-lobby-pack/<sha256>.zip` plus `pack-manifest.json`. `--check` validates all committed inputs, builds the ZIP twice in memory and compares the resulting URL/SHA1 with the tracked descriptor. It does not modify sources. PNG/OGG inputs are already compressed; ZIP entries use STORED mode, fixed timestamps/permissions and sorted paths to avoid zlib-version hash drift across Windows/Linux. Text inputs are pinned to LF through `.gitattributes`. No Pillow, downloader or original ZIP is needed for normal build/check. For local provenance verification add `--original-zip PATH`.

**Maven integration owner:** include only `noqui/lobby-assets/lobby-pack.properties` at the JAR classpath root `/lobby-pack.properties`. Do not include `pack/`, vendor files, previews or the ZIP in the JAR. The descriptor contains `url` and `sha1`. The pack UUID is `7d5243d5-c30e-4bdd-a039-71ec1ad10001`; public URL is `https://noqui.danta.qzz.io/packs/<sha256>.zip`. Publishing is a separate parent-owned step. Keep using `addResourcePack` so existing BGM packs remain applied.

| Key | Intended attachment | Geometry |
|---|---|---|
| `noqui:pirate_hat` | Head, y1.85 | Tricorn brim, ribbon, skull and feather |
| `noqui:backpack` | Back, y1.1 / back0.28 / runtime scale0.8 | MIT7-cuboid bag and shoulder straps |
| `noqui:glasses` | Face, y1.62 / forward0.28 | Open frames, bridge and temples |
| `noqui:crown` | Head | Gold prongs and gems |
| `noqui:cap` | Head | Stepped crown, visor and badge |
| `noqui:bunny` | Head | Headband, two raised ears and pink inner panels |
| `noqui:triple_boots` | Boots, y0.12 | Pair of blue boots with3 tier stripes and thrusters |
| `noqui:quad_boots` | Boots | Pair of purple boots with4 tier stripes and thrusters |
| `noqui:hammer` | Hand/display, y1.05 / forward0.4 | Wooden handle, steel head and brass ends |
| `noqui:snow_bomb` | Hand/display | Stepped ice sphere and fuse |
| `noqui:gravity_suit` | Torso, y1.1 | Open exoskeleton frame, shoulders, core and back coils |

Models are centered at8/8/8 with16 units per block. Their front faces point toward local+Z, matching a yaw0 player's forward direction. Only the backpack receives the runtime's0.8 scaling. Model transforms do not mutate equipment slots or defense inventory. `assets/noqui/textures/entity/shopkeeper.png` resolves as `noqui:entity/shopkeeper` for the native Mannequin's SLIM skin patch.

Optional authoring/preview tools require Pillow and NumPy. `author_assets.py --original-zip PATH` regenerates committed model/texture inputs and copies the exact five sounds; it reads pinned vendor inputs already present and downloads them only if absent. `preview_assets.py` renders the actual JSON cuboids/UV textures and actual skin, with attachment-scale mannequins, into ignored `.runtime/noqui-lobby-pack/previews/`. These are software renders for geometry/UV QA, not screenshots proving in-game rendering. In-game26.3 verification remains an integration step.

The [Minecraft26.3 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3) specify97.1. The [1.21.9 pack metadata notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-9) define `min_format`/`max_format` major/minor arrays. Modern item definitions use `assets/noqui/items/<id>.json` and reference `assets/noqui/models/<id>.json`.

See `asset-provenance.json`, `sound-provenance.json`, `pack/ASSET_NOTICES.txt` and the full MIT notice. SimpleHats models/textures were rejected because their asset rights are reserved. Kenney's CC0 Pirate Kit and other broad mesh collections were inspected as candidates; they did not provide the eleven native cuboid wearables/UV layout required here, so the remaining models use expressly labeled original fallbacks. No purchase or outside account is required.
