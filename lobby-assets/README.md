# Noqui lobby assets

The pack targets exactly resource format **97.1** and uses native `minecraft:model` item definitions. `lobby-cosmetics.json` is the canonical catalog: schema version 1, with `id`, Korean `label`, and `slot` (`head`, `face`, or `back`) for each cosmetic. All catalog entries and the five existing gadget models must have actual cuboid geometry, complete texture UVs, and explicit `fixed` transforms. The downloaded 64×64 slim female shopkeeper skin by Munasharna (navy tuxedo, purple hair, monocle) and five exact user-provided quiz cues are preserved. No quiz music, full source archives, or mod/client JARs are included. Native torso meshes use the pinned MIT obj-cubed 26.3 core shaders.

Prices belong to the Java catalog/configuration: all cosmetics default to 200 stars / 20 answer points, with per-product overrides. The asset catalog deliberately contains no prices. Existing cosmetic IDs, labels, and slots remain stable so saved ownership and equipment remain valid. `preserved-assets.json` pins the original six catalog entries and the exact hashes of the original 34 pack files; additions do not regenerate or replace those assets.

Build and verify using Python3's standard library:

```text
python3 noqui/lobby-assets/build_pack.py
python3 noqui/lobby-assets/build_pack.py --check
python3 noqui/lobby-assets/test_pack.py
```

The normal build writes the small tracked `noqui/lobby-assets/lobby-pack.properties` descriptor and ignored `.runtime/noqui-cosmetics-integration/pack/<sha256>.zip` plus `pack-manifest.json`. `--output PATH` selects another artifact directory. `--check` validates all committed inputs, builds the ZIP twice in memory and compares the resulting URL/SHA1 with the tracked descriptor. It does not modify sources. PNG/OGG inputs are already compressed; ZIP entries use STORED mode, fixed timestamps/permissions and sorted paths to avoid zlib-version hash drift across Windows/Linux. Text inputs are pinned to LF through `.gitattributes`. No Pillow, downloader or original ZIP is needed for normal build/check. For local provenance verification add `--original-zip PATH`.

**Maven integration owner:** bundle `noqui/lobby-assets/lobby-pack.properties` and `noqui/lobby-assets/lobby-cosmetics.json` at the JAR classpath root under those same filenames. Exclude `pack/`, vendor files, previews, and the ZIP from the JAR. The descriptor contains `url` and `sha1`. The pack UUID is `7d5243d5-c30e-4bdd-a039-71ec1ad10001`; public URL is `https://noqui.danta.qzz.io/packs/<sha256>.zip`. Publishing is a separate parent-owned step. Keep using `addResourcePack` so existing BGM packs remain applied.

`cosmetics-provenance.json` records each additional cosmetic's pinned model hash, texture resources, source IDs, and actual conversion steps. Every source retains its author, source/license URLs, exact vendor license, and hashes of the selected upstream evidence. The pack includes an identical `COSMETICS_SOURCES.json`, human-readable attribution in `ASSET_NOTICES.txt`, and the full license files in `licenses/`. An original adaptation must be explicitly labeled and carry parent approval; it must not be described as a downloaded model. The original asset, skin, and sound provenance files remain in force.

Validation derives the model set from the catalog rather than a fixed count. It checks closed pack contents, exact 97.1 bounds, item-definition references, finite cuboid/rotation bounds, nonzero-volume geometry and depth, valid native face directions, nonzero UV rectangles, texture aliases, source/model/license hashes, and preservation of existing assets. Multi-axis element rotations use native X-then-Y-then-Z order; legacy axis/angle notation takes precedence, matching the [official 1.21.11 format notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-11). Mixed solid geometry with planar decorative faces is supported; models made only of zero-thickness planes do not enter the solid cosmetic count. Degenerate UV faces are removed during conversion rather than bundled as texture errors. Geometry signatures include element rotations and ignore color, element order, translation, and uniform scale to reject recolor-only duplicates. Identical texture content must use one shared resource; only referenced textures enter the pack. Upstream atlases remain shared where practical rather than being copied once per item. The pack has an 8 MB upper bound.

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
| `noqui:hammer` | Held item | Wooden handle, steel head and brass ends |
| `noqui:snow_bomb` | Held item | Stepped ice sphere and fuse |
| `noqui:gravity_suit` | Torso, y1.1 | Open exoskeleton frame, shoulders, core and back coils |

Models retain their source cuboids and UVs. Headwear and glasses share the real HEAD slot through `noqui:wearable_head`, with custom-model-data string indices 0 and 1. Each model has a native `display.head` transform fitted to crown or eye height. Back accessories use the real CHEST slot and native humanoid body-bone meshes; the pinned MIT obj-cubed renderer decodes their geometry from equipment PNG layers. No cosmetic armor stands, displays or teleport loops are used. The gravity suit has combined equipment assets so the physical gadget keeps its serial and behavior while a back accessory is selected. Real occupied armor moves into an ordinary free inventory pocket; equipping waits when no pocket is available. Managed wardrobe stacks are removed before inventory snapshots and reconciled afterward. `assets/noqui/textures/entity/shopkeeper.png` resolves as `noqui:entity/shopkeeper` for the native Mannequin's SLIM skin patch.

Optional authoring/preview tools require Pillow and NumPy. `author_assets.py --original-zip PATH` regenerates only the original model/palette inputs and restores the unchanged downloaded skin from vendor/munasharna/ and copies the exact five sounds; it reads pinned vendor inputs already present and downloads them only if absent. Avoid running it during an integration freeze. `preview_assets.py` renders every catalog/gadget JSON and all of its actual face textures into ignored `.runtime/noqui-cosmetics-integration/pack/previews/` (or `--output PATH`). It creates overview sheets, detailed 20-item pages, individual images with each model's GUI rotation/scale, attachment-scale mannequins, and a preview manifest tying images to model/texture hashes. These are software renders for geometry/UV QA, not screenshots proving in-game rendering. In-game26.3 verification remains an integration step.

The [Minecraft26.3 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3) specify97.1. The [1.21.9 pack metadata notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-9) define `min_format`/`max_format` major/minor arrays. Modern item definitions use `assets/noqui/items/<id>.json` and reference `assets/noqui/models/<id>.json`.

See `asset-provenance.json`, `sound-provenance.json`, `pack/ASSET_NOTICES.txt` and the full MIT notice. SimpleHats models/textures were rejected because their asset rights are reserved. Kenney's CC0 Pirate Kit and other broad mesh collections were inspected as candidates; they did not provide the eleven native cuboid wearables/UV layout required here, so the remaining models use expressly labeled original fallbacks. No purchase or outside account is required.

The shop skin is the unchanged public download [Purple Hair Girl in Tuxedo by Munasharna](https://www.minecraftskins.com/skin/17371832/purple-hair-girl-in-tuxedo/). The author labels it Slim (3px arms) and explains its hat/monocle and jacket/tie layers. `vendor/munasharna/provenance.json` pins the PNG SHA256, source URL, attribution, and known licensing limits. No explicit open-content or general redistribution license is published on the author/work page; neither public availability nor the site's license from uploaders is presented as such a grant. The pack retains `SKIN_SOURCE_NOTICE.txt` and the original download bytes.

The previous silver-haired purple-striped asset rendered as authored; it was unsuitable for the requested formal shopkeeper. The old preview also omitted five clothing overlay parts and used an undersized hat. The corrected preview includes all twelve native parts, native 0.5/0.25 overlay inflation, correct leg offsets, and bottom-face UV orientation. These geometry/UV values were compared against the official Minecraft 26.3 `PlayerModel` (client SHA1 `e877b6a07acd633fb3bb475002175cec036e7b87`): 72 faces and all 288 vertex/UV pairs match. The runtime continues to use native `Mannequin` with the bundled SLIM skin patch and explicitly enables all skin layers on reused NPCs.

For independent verification, run `verification/NativeSkinMesh.java` with the official 26.3 client and its libraries on the classpath, passing an output JSON path. Then run `python noqui/lobby-assets/verify_native_skin.py <mesh.json>`. No client JAR or downloaded library is included in this repository or resource pack. This verifies native geometry/UV mapping; a live Minecraft 26.3 client still needs to confirm the newly deployed pack and NPC.

For deployment, the parent publishes the new content-hash ZIP before Paper starts, then deploys the JAR and **replaces the existing live plugin-data lobby-pack.properties through reviewed deployment**. The runtime intentionally preserves an existing descriptor and will not overwrite it merely because the JAR resource changed. Keep the pack UUID, six existing cosmetic IDs/slots, five gadget models, NPC skin, and five original cues unchanged.

## Native equipment authoring

`native-equipment.json` pins every native equipment definition, texture and shader. Normal pack validation and packaging use only Python's standard library. Optional regeneration uses Pillow plus `npm ci` in this directory, then `python native_wearables.py`. The pinned exporter, MIT license, shader sources and normalized single-atlas back inputs are preserved in `vendor/`; regeneration does not require an ignored runtime input. `vendor/native-head-baseline` preserves the original five head models, and validation permits only their native head transform to change.
