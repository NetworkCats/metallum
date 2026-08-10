## Metallum
Metallum is an experimental rendering backend for Minecraft on macOS that uses Apple’s Metal API instead of OpenGL/Vulkan. It provides a more native rendering path and aims to improve performance and efficiency on Apple Silicon.

On macOS 26 and newer, Metallum uses the Metal 4 command-buffer, command-allocator, argument-table, compiler, and explicit-residency APIs. On systems without the complete Metal 4 feature set, it automatically uses the Metal 3 command path instead. No configuration switch is required.

This project is still experimental. Performance, stability, and compatibility may vary depending on your system and installed mods. If you encounter bugs, please report them on GitHub.

Compatible with Sodium.

vibecoded as hell

## Requirements
- macOS
- Apple Silicon (M1 or newer)
