# Moundi hint overlay (26.3 only)

Moundi players and spectators receive a separate optional resource pack. A
transparent PINK bossbar carries the font glyphs; no progress bar is visible.
Other minigames keep the ordinary action bar. The overlay clears on pause,
reveal, leaving Moundi, disconnect, or plugin shutdown. Until this pack loads,
each changed hint is sent once to chat. No game timing or browser playback is
owned by the overlay.

The hint is centered above Moundi's action bar. Up to 160 characters wrap into
six rows without truncation. Every row has zero native font advance. The
hint uses native GUI text scale rather than a fixed physical pixel size. It is
twice the former size at GUI 2 in a 720p window, and scales up or down with the
client's GUI setting; window bounds can still reduce it to fit. Its lower edge
stays fixed so the gap above the action bar is unchanged. Only
encoded GUI glyphs are repositioned; ordinary GUI/world text keeps its vanilla
position. Window layout is obtained from the 26.3 GUI projection. This follows
the font-ascent and screen-layout technique in
the user's Plague Inc project, using a dedicated namespace and marker range.

The pack replaces only `text.vsh` and the two unused PINK bossbar sprites.
Vanilla's fragment shader remains in use. It contains no terrain shaders or
shared shader includes from Sodium's unsupported shader list. The existing
Noqui equipment/sounds pack and the mine's WHITE/RED regeneration bar remain
separate.

## Rebuild

Python/Pillow are needed only to regenerate the Noto Sans KR font atlas:

```powershell
python noqui/hint-assets/build_pack.py --font '<NotoSansKR variable TTF>' --output '<output directory>'
```

The font file is the OFL font identified by `font-provenance.json`; its license
ships in the pack. To validate and package the checked-in atlas without Pillow:

```powershell
python noqui/hint-assets/build_pack.py --output '<output directory>'
```

The deterministic ZIP is named by SHA-256. This also updates the bundled
`hint-pack.properties` descriptor with its HTTPS URL and SHA-1. Upload that
immutable public ZIP before deploying the plugin. Java metrics are measured
from the same atlas with Minecraft's bitmap-advance rounding.

## Verification

`HintOverlayTest` covers pack identity/readiness, one-time chat fallback,
owned-bar cleanup, phase transitions and full-length wrapping. `QuizHudTest`
checks the role routing and preserves Moundi's action bar.

`tools/HintGpuCheck.java` uses LWJGL, the real 26.3 client JAR and the generated
pack. Compile it with the plugin classes, Maven test classpath and LWJGL
core/OpenGL/GLFW plus native libraries; pass client JAR, pack ZIP and output
directory. It captures all glyph vertices and checks window bounds, native
advance, and separation from the game display at GUI scales 1–4. It renders
36 short/maximum-length cases across 640×480, 1920×1080, 1920×1006,
2560×1440 and 3840×2160 (excluding GUI scales the vanilla client disallows).
This is an actual GPU shader check, not a Minecraft world screenshot. Other
GPU vendors and Vulkan still require client validation.
