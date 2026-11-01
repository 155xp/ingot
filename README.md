# Ingot

A native Apple Metal rendering backend for Minecraft 26.3 (Fabric) on macOS, built for Apple silicon: more frames,
less power and less memory than vanilla, with no other mods needed.

Work in progress.

## Power modes

Options > Video Settings has a **Power Mode** button at the top and an **Ingot Settings...** screen:

- **Auto** (default): Battery mode on battery power or in Low Power Mode, Performance mode on the charger.
- **Performance**: the frame rate from Video Settings (Unlimited goes past the display's refresh rate), all work on
  the performance cores.
- **Battery**: frame rate capped to the display's refresh rate (120 on ProMotion), chunk building and world generation
  on the efficiency cores. Optional: half resolution (one pixel per point; menus and text stay sharp).

Settings are saved in `config/ingot.properties`.

## Modrinth profile

`./gradlew build && python3 tools/make_mrpack.py` writes `build/mrpack/Ingot-<version>.mrpack`: Fabric Loader and
Ingot only, Auto power mode, render distance 32 with simulation distance 8, Unlimited Max Framerate. Open it with the
Modrinth App (or drag it onto the window) to create the profile, then set the profile's Java and memory settings to
4096 MB and these Java arguments (measured: the same frame times with less than half the memory of the vanilla
launcher's ZGC flags):

```
-XX:+UseG1GC -XX:MaxGCPauseMillis=40 -XX:+UseCompactObjectHeaders -XX:+UseStringDeduplication
```

## Site

`site/` is [ingot.ericomg.com](https://ingot.ericomg.com) (Vite and React; `cd site && bun install && bun run build`, deployed
with `vercel deploy --prod`). Its figures are drawn from the benchmark numbers by `site/scripts/columns.mjs`.

## Measurements

`bench/` measures frame times, CPU and GPU energy and memory against vanilla; see `bench/README.md` and
`docs/ARCHITECTURE.md` ("Energy efficiency").
