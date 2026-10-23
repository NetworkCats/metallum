## Metallum
Metallum is an experimental rendering backend for Minecraft on macOS that uses Apple’s Metal API instead of OpenGL/Vulkan. It provides a more native rendering path and aims to improve performance and efficiency on Apple Silicon.

Metallum targets Metal 4 exclusively: it uses the Metal 4 command-buffer, command-allocator, argument-table, compiler, and explicit-residency APIs, and compiles shaders as MSL 4. If the Mac does not support Metal 4, Minecraft falls back to its own Vulkan or OpenGL backend.

This project is still experimental. Performance, stability, and compatibility may vary depending on your system and installed mods. If you encounter bugs, please report them on GitHub.

Supports Minecraft 26.3. Compatible with Sodium, with backend-detection shims for Distant Horizons and Shine.

### Options

- **Display P3** (Sodium's video settings → Metallum, or `config/metallum.json` → `displayP3`): presents in Display P3 for the saturated look Minecraft had before 26.3. Off by default (sRGB). Requires a restart.

### Diagnostic switches

- `-Dmetallum.stats=true` enables low-overhead renderer counters (logged every 600 frames by default).
- Range-based resource synchronization is enabled by default. Use `-Dmetallum.globalFence=true` (or `-Dmetallum.resourceTracker=false`) to force the conservative global-fence fallback for diagnostics.
- Pipelines compile on Minecraft's background executor. Native Metal 4 pipeline archives are stored under `config/metallum/pipeline-cache`.

vibecoded as hell

## Requirements
- macOS 26 or newer (Metal 4)
- Apple Silicon (M1 or newer)
- Minecraft 26.3 with Fabric Loader 0.19.5+

## Development
- `./gradlew build` compiles and runs the unit tests (JDK 25).
- `./gradlew runClientGameTest` boots Minecraft on the Metal backend (macOS only), renders the title screen and a test world, and saves screenshots to `build/run/clientGameTest/screenshots`.
