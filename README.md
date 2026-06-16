# MechGarden

A Kotlin/JVM workspace for building, benchmarking, and dueling
[Robocode](https://robocode.sourceforge.io/) classic 1.11.0 robots.

## Robots

### zen.Ronin (`bots/ronin`)

A second-generation 1v1 wave-surfer designed to beat expert-tier opponents.

**Gun:** virtual-gun array — head-on, linear, circular leads, four segmented
GuessFactor guns (distance × lateral, distance × lateral rolling, lateral ×
acceleration, distance × lateral × wall-room), and a **dynamic-clustering KNN
gun** that fires as the primary aim once it has enough data. Energy-tier
firepower: aggressive finish (power 2.0) when leading, economy mode
(power 1.2 + bullet shadows) when even or behind.

**Movement:** wave surfing with a multi-buffer danger model (9 confidence-
weighted ensemble buffers), bullet shadows (own in-flight bullets physically
protect guess factors), adaptive engagement range (responds to the enemy's
radial velocity), and multi-wave anticipation.

**Adaptation:** tick-wave learning weight adapts to surfer detection; DC-gun
observations and engagement parameters are retained per opponent within the
current battle/JVM.

**Performance:** DC gun KNN uses a three-way quickselect (O(n) average) with
reused buffers — the per-scan KNN is allocation-free.

### zen.Fencer (`bots/fencer`)

The original layered 1v1 wave-surfer with radar locking, scan tracking,
enemy-wave detection, virtual-gun GF array, bullet shadows, and wave surfing.

## Quick Start

```bash
# Install the Robocode engine
just robocode install

# Build all modules
just build

# Deploy a robot
just deploy ronin

# Duel (100 rounds, headless)
just duel -r Ronin -e Fencer -n 100

# Benchmark vs a reference catalog
just duel -r Ronin -c basic -n 100       # 3 basic bots
just duel -r Ronin -c expert -n 100       # 3 expert bots
just duel -r Ronin -c top -n 100          # 6 GigaRumble top bots

# Launch the Robocode UI
just run
```

## Reference Catalogs

Downloaded from the Robocode community archive via `just refs`:

| Catalog | Bots | Tier |
|---------|------|------|
| `basic` | FloodMini, BasicGFSurfer, RaikoNano | Beginner |
| `classic` | RaikoMX, Lacrimas, BlestPain | Intermediate |
| `expert` | Pear, CassiusClay, SandboxDT | Expert |
| `top` | DrussGT, Diamond, Shadow, Gilgalad, ScalarR, BeepBoop | GigaRumble top |

```bash
just refs download --catalog basic      # download jar files
just refs deploy --catalog expert       # symlink into robocode/robots/
just refs list --all                    # list all available
```

## Repository Layout

```
bots/
  fencer/          zen.Fencer — original wave-surfer
  ronin/           zen.Ronin — advanced wave-surfer with DC gun
scripts/                  Engine, duel, reference-robot, and UI tools
docs/                     Physics, scoring, and rumble-metric references
```

## Build

Requires JDK 18+ (runs on Java 8 bytecode target). Uses the checked-in Gradle
wrapper and [Spotless](https://github.com/diffplug/spotless)/ktlint for
formatting.

```bash
just build    # build everything
just fmt      # auto-format
just lint     # check formatting
```

## Documentation

- [`docs/robocode-physics.md`](docs/robocode-physics.md) — engine physics rules,
  constants, and turn order.
- [`docs/robocode-scoring.md`](docs/robocode-scoring.md) — scoring breakdown
  (survival, bullet damage, kill bonuses).
- [`docs/rumble-metrics.md`](docs/rumble-metrics.md) — APS, PWIN, survival
  metric definitions used by the duel harness.
