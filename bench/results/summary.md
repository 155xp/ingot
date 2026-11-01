# Ingot benchmark summary

Generated 2026-11-01T21:35:53+00:00 by `bench/report.py`. Each number is the median over the runs of a configuration (see bench/README.md for how every metric is defined).

Energy columns: **game CPU** is the CPU energy of the game process (all its threads, the integrated server too); **GPU** is the whole GPU, every process (system-wide); **total** is both; the display, DRAM and the rest of the Mac are not included. **mJ/frame** = (game CPU + GPU energy) / frames. Uncapped and capped (`--fps-cap`) runs are separate tables.

## Scenario `far`, capped at 120 FPS (vsync off), 30 s measured (10 s warmup)

Apple M4 Pro (Mac16,8, 24 GB), macOS 27.2; framebuffer 2560x1440, render distance 32, seed 20261004, world template `affa0778`.

| config | runs | avg FPS | 1% low | 0.1% low | p50 ms | p99 ms | vs vanilla (avg / 1% low) | backend | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| vanilla | 2 | 99.0 | 58.7 | 42.4 | 9.54 | 15.43 | - | OpenGL | avg range 98-100 (1%); background 2.0 cores; below the FPS cap in 2 run(s); dirty tree |
| ingot-battery | 4 | 119.5 | 110.2 | 71.8 | 8.36 | 8.45 | +21% / +88% | Metal | avg range 119-120 (0%); background 2.1 cores; dirty tree |
| ingot-profile-battery | 1 | 119.3 | 95.7 | 45.8 | 8.36 | 8.47 | +20% / +63% | Metal | background 2.2 cores; dirty tree |
| ingot-battery-direct | 2 | 119.5 | 113.8 | 93.3 | 8.36 | 8.43 | +21% / +94% | Metal | avg range 119-120 (0%); background 2.1 cores; dirty tree |
| ingot-battery-direct-fastmath | 2 | 119.5 | 111.0 | 74.6 | 8.36 | 8.43 | +21% / +89% | Metal | avg range 120-120 (0%); background 2.2 cores; dirty tree |
| ingot-battery-fastmath | 2 | 119.5 | 113.1 | 92.6 | 8.36 | 8.43 | +21% / +93% | Metal | avg range 119-120 (0%); background 2.2 cores; dirty tree |

Per phase (avg FPS / 1% low):

| config | spin | fly |
|---|---:|---:|
| vanilla | 98.7 / 58.3 | 99.7 / 60.1 |
| ingot-battery | 119.5 / 106.9 | 119.6 / 117.5 |
| ingot-profile-battery | 119.4 / 100.5 | 119.2 / 87.3 |
| ingot-battery-direct | 119.5 / 112.1 | 119.6 / 117.6 |
| ingot-battery-direct-fastmath | 119.5 / 107.7 | 119.6 / 118.1 |
| ingot-battery-fastmath | 119.5 / 112.1 | 119.5 / 115.2 |

Energy over the measured frames (equal 120 FPS, so W and mJ/frame rank alike):

Idle GPU baseline (10 s with the game not running, before each batch): **0.190 W** (median of 3 batches; display awake). 'GPU over idle' is GPU W minus it: roughly what the game adds.

| config | runs | avg FPS | game CPU W | GPU W | GPU over idle W | total W | mJ/frame | vs vanilla (mJ/frame / total W) | CPU J | GPU J | P-core share | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| vanilla | 2 | 99.0 | 3.49 | 4.62 | 4.44 | 8.11 | 81.90 | - | 104.7 | 138.6 | 100% | mJ/frame range 80.83-82.98 (3%); below the FPS cap in 2 run(s); vanilla frame limiter |
| ingot-battery | 4 | 119.5 | 1.58 | 8.47 | 8.26 | 10.04 | 84.08 | +3% / +24% | 47.3 | 254.1 | 100% | mJ/frame range 59.78-92.34 (39%); ingot frame limiter |
| ingot-profile-battery | 1 | 119.3 | 1.87 | 7.88 | 7.68 | 9.75 | 81.70 | -0% / +20% | 56.2 | 236.3 | 100% | ingot frame limiter |
| ingot-battery-direct | 2 | 119.5 | 1.75 | 9.05 | 8.83 | 10.80 | 90.34 | +10% / +33% | 52.3 | 271.7 | 100% | mJ/frame range 86.64-94.03 (8%); ingot frame limiter |
| ingot-battery-direct-fastmath | 2 | 119.5 | 1.70 | 7.36 | 7.13 | 9.05 | 75.73 | -8% / +12% | 50.9 | 220.7 | 100% | mJ/frame range 69.11-82.36 (17%); ingot frame limiter |
| ingot-battery-fastmath | 2 | 119.5 | 1.58 | 7.08 | 6.86 | 8.66 | 72.46 | -12% / +7% | 47.4 | 212.5 | 100% | mJ/frame range 67.92-77.00 (13%); ingot frame limiter |

Energy sampler cost (median): 0.41% of one CPU core, 4.7 mW; frames during a sample took +0.0% vs frames half an interval later.

Mods per configuration:

- **vanilla**: ingot 0.1.0 (disabled)
- **ingot-battery**: ingot 0.1.0
- **ingot-profile-battery**: ingot 0.1.0
- **ingot-battery-direct**: ingot 0.1.0
- **ingot-battery-direct-fastmath**: ingot 0.1.0
- **ingot-battery-fastmath**: ingot 0.1.0

## Scenario `standard`, uncapped, 20 s measured (5 s warmup)

Apple M4 Pro (Mac16,8, 24 GB), macOS 27.2; framebuffer 2560x1440, render distance 16, seed 20261004, world template `6065a31f`.

| config | runs | avg FPS | 1% low | 0.1% low | p50 ms | p99 ms | vs vanilla (avg / 1% low) | backend | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| ingot | 1 | 538.2 | 283.9 | 206.5 | 1.70 | 3.23 | - | Metal | background 2.5 cores; dirty tree |

Per phase (avg FPS / 1% low):

| config | spin | fly |
|---|---:|---:|
| ingot | 472.7 / 274.0 | 669.3 / 387.9 |

Energy over the measured frames (uncapped: a faster config renders more frames, so compare mJ/frame):

Idle GPU baseline (10 s with the game not running, before each batch): **0.186 W** (median of 1 batch; display awake). 'GPU over idle' is GPU W minus it: roughly what the game adds.

| config | runs | avg FPS | game CPU W | GPU W | GPU over idle W | total W | mJ/frame | vs vanilla (mJ/frame / total W) | CPU J | GPU J | P-core share | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| ingot | 1 | 538.2 | 2.24 | 33.61 | 33.43 | 35.85 | 66.61 | - | 44.7 | 672.3 | 100% |  |

Energy sampler cost (median): 0.42% of one CPU core, 4.8 mW; frames during a sample took -1.5% vs frames half an interval later.

Mods per configuration:

- **ingot**: ingot 0.1.0

## Scenario `standard`, uncapped, 30 s measured (10 s warmup)

Apple M4 Pro (Mac16,8, 24 GB), macOS 27.2; framebuffer 1280x720, render distance 16, seed 20261004, world template `6065a31f`.

| config | runs | avg FPS | 1% low | 0.1% low | p50 ms | p99 ms | vs vanilla (avg / 1% low) | backend | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| ingot-half | 1 | 120.0 | 104.7 | 96.9 | 8.30 | 9.34 | - | Metal | background 2.5 cores; frame rate locked to the 120 Hz display refresh (avg 120.0 FPS, p50 8.30 ms) although vsync is off (1x); dirty tree |

Per phase (avg FPS / 1% low):

| config | spin | fly |
|---|---:|---:|
| ingot-half | 120.0 / 103.8 | 120.0 / 107.2 |

Energy over the measured frames (uncapped: a faster config renders more frames, so compare mJ/frame):

Idle GPU baseline (10 s with the game not running, before each batch): **0.182 W** (median of 1 batch; display awake). 'GPU over idle' is GPU W minus it: roughly what the game adds.

| config | runs | avg FPS | game CPU W | GPU W | GPU over idle W | total W | mJ/frame | vs vanilla (mJ/frame / total W) | CPU J | GPU J | P-core share | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| ingot-half | 1 | 120.0 | 1.03 | 1.72 | 1.53 | 2.74 | 22.86 | - | 30.8 | 51.5 | 99% |  |

Energy sampler cost (median): 0.44% of one CPU core, 4.1 mW; frames during a sample took -0.3% vs frames half an interval later.

Mods per configuration:

- **ingot-half**: ingot 0.1.0

## Scenario `standard`, uncapped, 30 s measured (10 s warmup)

Apple M4 Pro (Mac16,8, 24 GB), macOS 27.2; framebuffer 2560x1440, render distance 16, seed 20261004, world template `6065a31f`.

| config | runs | avg FPS | 1% low | 0.1% low | p50 ms | p99 ms | vs vanilla (avg / 1% low) | backend | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| vanilla | 1 | 212.1 | 73.9 | 52.4 | 4.18 | 10.80 | - | OpenGL | background 1.9 cores; dirty tree |

Per phase (avg FPS / 1% low):

| config | spin | fly |
|---|---:|---:|
| vanilla | 230.3 / 80.6 | 175.8 / 70.4 |

Energy over the measured frames (uncapped: a faster config renders more frames, so compare mJ/frame):

Idle GPU baseline (10 s with the game not running, before each batch): **0.182 W** (median of 1 batch; display awake). 'GPU over idle' is GPU W minus it: roughly what the game adds.

| config | runs | avg FPS | game CPU W | GPU W | GPU over idle W | total W | mJ/frame | vs vanilla (mJ/frame / total W) | CPU J | GPU J | P-core share | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| vanilla | 1 | 212.1 | 2.89 | 9.67 | 9.49 | 12.56 | 59.21 | - | 86.6 | 290.2 | 100% |  |

Energy sampler cost (median): 0.38% of one CPU core, 5.9 mW; frames during a sample took -2.6% vs frames half an interval later.

Mods per configuration:

- **vanilla**: ingot 0.1.0 (disabled)

## Scenario `standard`, capped at 120 FPS (vsync off), 10 s measured (3 s warmup)

Apple M4 Pro (Mac16,8, 24 GB), macOS 27.2; framebuffer 2560x1440, render distance 16, seed 20261004, world template `6065a31f`.

| config | runs | avg FPS | 1% low | 0.1% low | p50 ms | p99 ms | vs vanilla (avg / 1% low) | backend | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| ingot-merge | 2 | 119.5 | 100.7 | 61.7 | 8.36 | 8.54 | - | Metal | avg range 120-120 (0%); background 2.8 cores; JIT still compiling in 2 run(s); dirty tree |

Per phase (avg FPS / 1% low):

| config | spin | fly |
|---|---:|---:|
| ingot-merge | 119.5 / 95.3 | 119.5 / 114.1 |

Energy: not measured in these runs (run.sh --no-energy, or results from before the energy sampler).


Mods per configuration:

- **ingot-merge**: ingot 0.1.0

## Scenario `standard`, capped at 120 FPS (vsync off), 30 s measured (10 s warmup)

Apple M4 Pro (Mac16,8, 24 GB), macOS 27.2; framebuffer 1280x720, render distance 16, seed 20261004, world template `6065a31f`.

| config | runs | avg FPS | 1% low | 0.1% low | p50 ms | p99 ms | vs vanilla (avg / 1% low) | backend | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| ingot-battery-half | 2 | 119.5 | 113.8 | 101.8 | 8.36 | 8.54 | - | Metal | avg range 120-120 (0%); background 2.3 cores; dirty tree |

Per phase (avg FPS / 1% low):

| config | spin | fly |
|---|---:|---:|
| ingot-battery-half | 119.5 / 114.8 | 119.5 / 112.1 |

Energy over the measured frames (equal 120 FPS, so W and mJ/frame rank alike):

Idle GPU baseline (10 s with the game not running, before each batch): **0.066 W** (median of 1 batch; display awake). 'GPU over idle' is GPU W minus it: roughly what the game adds.

| config | runs | avg FPS | game CPU W | GPU W | GPU over idle W | total W | mJ/frame | vs vanilla (mJ/frame / total W) | CPU J | GPU J | P-core share | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| ingot-battery-half | 2 | 119.5 | 1.03 | 1.37 | 1.30 | 2.40 | 20.10 | - | 31.0 | 41.1 | 99% | mJ/frame range 19.64-20.55 (5%); ingot frame limiter |

Energy sampler cost (median): 0.46% of one CPU core, 4.0 mW; frames during a sample took +0.0% vs frames half an interval later.

Mods per configuration:

- **ingot-battery-half**: ingot 0.1.0

## Scenario `standard`, capped at 120 FPS (vsync off), 30 s measured (10 s warmup)

Apple M4 Pro (Mac16,8, 24 GB), macOS 27.2; framebuffer 2560x1440, render distance 16, seed 20261004, world template `6065a31f`.

| config | runs | avg FPS | 1% low | 0.1% low | p50 ms | p99 ms | vs vanilla (avg / 1% low) | backend | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| vanilla | 5 | 115.4 | 69.5 | 57.3 | 8.33 | 13.06 | - | OpenGL | avg range 114-117 (2%); background 2.0 cores; below the FPS cap in 4 run(s); dirty tree |
| ingot | 2 | 119.5 | 111.5 | 93.6 | 8.36 | 8.54 | +4% / +61% | Metal | avg range 119-120 (0%); background 2.1 cores; dirty tree |
| ingot-battery | 4 | 119.5 | 110.3 | 89.8 | 8.36 | 8.57 | +4% / +59% | Metal | avg range 119-120 (0%); background 2.1 cores; dirty tree |
| ingot-nomerge | 1 | 119.5 | 110.9 | 100.4 | 8.36 | 8.70 | +4% / +60% | Metal | background 2.0 cores; dirty tree |
| ingot-g1 | 2 | 119.4 | 108.4 | 83.0 | 8.36 | 8.62 | +3% / +56% | Metal | avg range 119-119 (0%); background 2.1 cores; dirty tree |
| ingot-merge | 1 | 119.5 | 113.2 | 108.7 | 8.36 | 8.66 | +4% / +63% | Metal | background 1.9 cores; dirty tree |

Per phase (avg FPS / 1% low):

| config | spin | fly |
|---|---:|---:|
| vanilla | 116.2 / 71.7 | 115.0 / 67.2 |
| ingot | 119.5 / 109.6 | 119.5 / 116.2 |
| ingot-battery | 119.5 / 108.5 | 119.5 / 115.5 |
| ingot-nomerge | 119.5 / 110.1 | 119.5 / 113.8 |
| ingot-g1 | 119.5 / 110.8 | 119.3 / 104.2 |
| ingot-merge | 119.5 / 113.3 | 119.5 / 113.1 |

Energy over the measured frames (equal 120 FPS, so W and mJ/frame rank alike):

Idle GPU baseline (10 s with the game not running, before each batch): **0.164 W** (median of 3 batches; display awake). 'GPU over idle' is GPU W minus it: roughly what the game adds.

Warning, baseline of 2026-11-01T20:37:44+00:00: other processes used the GPU during the idle baseline (Helium Helper 8%).

| config | runs | avg FPS | game CPU W | GPU W | GPU over idle W | total W | mJ/frame | vs vanilla (mJ/frame / total W) | CPU J | GPU J | P-core share | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| vanilla | 5 | 115.4 | 1.72 | 2.19 | 1.98 | 3.80 | 32.67 | - | 51.5 | 65.5 | 100% | mJ/frame range 29.65-38.68 (28%); energy warnings in 2 run(s); below the FPS cap in 4 run(s); vanilla frame limiter |
| ingot | 2 | 119.5 | 0.98 | 2.49 | 2.43 | 3.47 | 29.02 | -11% / -9% | 29.3 | 74.8 | 99% | mJ/frame range 28.95-29.11 (1%); ingot frame limiter |
| ingot-battery | 4 | 119.5 | 0.92 | 2.08 | 1.96 | 2.95 | 24.72 | -24% / -22% | 27.5 | 62.4 | 98% | mJ/frame range 21.71-28.75 (28%); ingot frame limiter |
| ingot-nomerge | 1 | 119.5 | 0.97 | 2.09 | 1.64 | 3.06 | 25.66 | -21% / -19% | 29.2 | 62.8 | 99% | energy warnings in 1 run(s); ingot frame limiter |
| ingot-g1 | 2 | 119.4 | 1.05 | 2.19 | 1.74 | 3.25 | 27.18 | -17% / -15% | 31.6 | 65.8 | 99% | mJ/frame range 27.10-27.26 (1%); energy warnings in 2 run(s); ingot frame limiter |
| ingot-merge | 1 | 119.5 | 0.86 | 2.15 | 1.69 | 3.02 | 25.23 | -23% / -21% | 26.0 | 64.5 | 99% | energy warnings in 1 run(s); ingot frame limiter |

Energy sampler cost (median): 0.48% of one CPU core, 3.7 mW; frames during a sample took +0.0% vs frames half an interval later.

Mods per configuration:

- **vanilla**: ingot 0.1.0 (disabled)
- **ingot**: ingot 0.1.0
- **ingot-battery**: ingot 0.1.0
- **ingot-nomerge**: ingot 0.1.0
- **ingot-g1**: ingot 0.1.0
- **ingot-merge**: ingot 0.1.0

Skipped runs: 14 disturbed (busy machine), 2 invalid.
