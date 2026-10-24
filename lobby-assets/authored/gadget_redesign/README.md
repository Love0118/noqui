# Original Noqui gadget redesign

The three pack models and their textures are final for parent integration.
All designs are original cuboid/pixel work created for this request. No vendor
model, downloaded texture, shared palette, shader or equipment definition was
modified. Pixel textures are generated locally with Pillow, imported into
Blockbench, inspected there, and exported with Blockbench's native codecs.

## Changed pack files

- `assets/noqui/models/hammer.json`
- `assets/noqui/models/gravity_suit.json`
- `assets/noqui/models/snow_bomb.json`
- `assets/noqui/textures/item/hammer_aurora.png` (new, 64x64)
- `assets/noqui/textures/item/gravity_harness.png` (new, 64x64)
- `assets/noqui/textures/item/snow_bomb_frost.png` (new, 64x64)
- `assets/noqui/textures/entity/equipment/humanoid/gravity_suit.png` (64x32)

Paths above are relative to `noqui/lobby-assets/pack/`. All other changes from
this work are contained in this new `authored/gadget_redesign/` directory.

## Appearance and hand placement

The mallet has a beveled raspberry rubber barrel, one gold toy star on each
striking face, a narrow teal sleeve, restrained brass details, warm ash wood
grain and a wrapped lower grip. The geometry is centered on x=8 instead of the
old x=13 offset. First-person scale is 0.64 with mirrored display slots. The
third-person scale is 0.90, deliberately more prominent outside the player.

Final right-hand third-person rotation is `[0,90,25]`, translation
`[0,3.30,1.54]`; left-hand rotation is `[0,-90,-25]` with the same translation.
This places the handle across the fist with the head forward and upward.
The earlier trial with X=90 ran along the forearm and was rejected. The wrapped
grip center lands within 0.00352 model units of the display hand origin. These
are native item display transforms; no custom player animation is introduced.

The gravity harness uses graphite plates, subtle blue steel edges, a recessed
teal core, small warm fasteners, shoulder bridges and a compact rear power pack.
Its actual vanilla humanoid texture has a matching bright chest core, teal
seams, short shoulder caps and transparent forearms/neck. Bright colors suggest
light; no additional emissive renderer is assumed. All 22 existing standalone
and combined back+gravity equipment definitions still reference this same PNG.
Native CHEST compatibility and item model identity are retained.

The snow bomb is a small stepped packed-snow shape with icy belt, snowflake
seals, arming collar and a small metal safety pin. Its fixed and ground scales
are both 0.65, yielding bounds about 0.240 x 0.268 x 0.254 blocks. First-person
scale is 0.70 with Y translation 3.8, so the complete bomb is visible above the
screen edge without covering the center. Its item ID remains `noqui:snow_bomb`.
The parent's `Snowball.setItem` integration can use the exact held stack.

## Blockbench MCP evidence

The configured `blockbench` streamable HTTP server was callable in this session
at its existing localhost endpoint. No installation or endpoint guessing was
needed. Blockbench 5.1.6 / MCP plugin 1.10.1 reported successful operation.

Actual MCP operations used `create_project`, `risky_eval`, and `set_mode`.
`risky_eval` invoked native `Codecs.java_block.parse`, native texture loading,
scoped Undo edits, `Codecs.java_block.compile`, `Codecs.project.compile`, display
mode and on-demand WebGL `Preview.screenshot`. This was actual Blockbench use.
An app screenshot was stale because the window was covered; it is not used as
evidence. All delivered Blockbench PNGs were rendered on demand instead.

Final projects:

- Hammer: `5520ada5-3211-323f-3a2d-d647ad30cb30` (35 cuboids)
- Gravity suit: `ea7dbdb3-0dbd-54a2-f5ba-c4e7eee52558` (35 cuboids)
- Snow bomb: `95ed0e27-2e79-06f7-a6bb-9314d2ad3e98` (20 cuboids)
- Native chest UV review: `9c9caccf-4330-ce11-369c-dacc95e10333`

The three named `.bbmodel` files are editable source projects with embedded
textures. `gravity_native_uv.bbmodel` separately exposes the exact 64x32 armor
UVs on inflated native torso/arm geometry. It uses a small idle arm pose only
for review. `previews/review_sheet.png` combines Blockbench item and left/right
hand renders with software native-armor views. Raw Blockbench armor front/back,
snow first-person, fixed and ground previews are also included. These are
authoring previews; Minecraft client verification remains a parent step.

## Reproduction and focused checks

Do not regenerate during the shared checkout's parent build/integration.

1. Run `generate.py` with Pillow available. It writes only these three models,
   three dedicated textures, the native suit PNG and the three `inputs/` files.
2. For each item, create an empty `java_block` project through Blockbench MCP.
   Set `globalThis.noquiGadgetName` to its ID and evaluate
   `blockbench_export.js` through `risky_eval`. This script uses this checkout's
   explicit absolute path. After the codec export it restores explicit default
   display fields because the pack requires `fixed.rotation/translation/scale`.
3. Evaluate `blockbench_previews.js` in the exported item's project. It captures
   native GUI/display previews without depending on a foreground window.
4. Run `review.py`. It checks geometry, finite bounds, six native UV faces,
   texture references, explicit transforms, embedded authoring textures, grip
   placement, snow projectile bounds and native texture regions/references.
5. Optionally create an empty `free` project and evaluate
   `blockbench_native_preview.js` for the separate armor UV review.

`review.py` passed for all three models and the native texture. The validation
result, exact model/texture hashes, preview hashes and equipment references
are in `validation.json`. Baseline and replacement hashes are in
`provenance.json`. No full pack check/build, Maven, CodeGraph sync, commit,
push, deployment or zip output was performed by this scoped asset work.

## Parent integration

Update `native-equipment.json`'s one gravity-suit PNG entry to
`58bcad527fab2874b21172559bbb27de74b7e7b7d21cf381feb3d06c66ba2024`.
The three existing model entries in `preserved-assets.json` also pin their old
hashes and will reject this explicitly requested redesign until reconciled by
the parent. `provenance.json` provides both baseline and current hashes. These
shared files were deliberately not edited here. The ordinary build discovers
the three new item texture resources from the models; no unreferenced pack
textures were added. Parent owns complete validation, hash/descriptor updates,
projectile behavior/sounds/frost integration, pack build, deployment and Git.
