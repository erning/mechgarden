# AGENTS.md

## Project

MechGarden is a Kotlin/JVM workspace for Robocode classic 1.11.0 robots. Each
robot is self-contained: all of its tactics, models, profiles, tuning, and
adaptive state live under `bots/<name>/` and depend on nothing outside that
module.

Active robot modules:

- **`zen.Fencer`** (`bots/fencer`) — the original layered 1v1 wave-surfer.
  Implementation in `zen.fencer.*`.
- **`zen.Ronin`** (`bots/ronin`) — second-generation 1v1 wave-surfer built to
  beat Fencer and advance toward expert-tier opponents. Implementation in
  `zen.ronin.*`.
- **`zen.Mirage`** (`bots/mirage`) — third-generation defensive, movement-first
  1v1 wave-surfer. Implementation in `zen.mirage.*`.

Each module has its own `bots/<name>/AGENTS.md` with robot-specific rules. That
file applies to work inside its directory and takes precedence over this one
where the two conflict.

## Commands

Tasks run through `just` and the checked-in Gradle wrapper.

| Command | Purpose |
|---------|---------|
| `just robocode install` | Install the engine + sources into `./robocode` (`--no-source` to skip) |
| `just build` | Build every module |
| `just fmt` | Apply Spotless/ktlint formatting |
| `just lint` | Check formatting |
| `just deploy <bot>` | Build and deploy `<bot>.jar` (e.g. `just deploy ronin`) |
| `just duel -r Ronin -e Fencer` | Duel two robots, default 35 rounds |
| `just duel -r Ronin -c basic` | Duel a robot against a catalog (3 bots) |
| `just duel -r Ronin -c roborumble-100` | Duel against the roborumble-100 catalog |
| `just run` | Launch the Robocode UI |

Use `just refs ...` to download, deploy, list, or index reference robots from
`refs.jsonc`. Catalogs: `basic`, `classic`, `expert`, `top`, `gigarumble`,
`roborumble-100`, `mirage-negative-knnpbi`.

## Repository Layout

- `bots/fencer` — Fencer robot module (`bots/fencer/AGENTS.md`).
- `bots/ronin` — Ronin robot module (`bots/ronin/AGENTS.md`, `bots/ronin/docs/`).
- `bots/mirage` — Mirage robot module (`bots/mirage/AGENTS.md`, `bots/mirage/docs/`).
- `scripts/` — engine, reference-robot, duel, and UI launch tools.
- `robocode/source/` — the engine's Robocode source tree (installed alongside
  the engine; a local artifact, not in Git). Read it here instead of
  decompiling `robocode/libs/*.jar`.
- `docs/` — engine- and Robocode-general references only (physics, scoring,
  metrics, shared targeting/surfing techniques). Robot-specific architecture,
  design docs, and benchmarks live under `bots/<name>/docs/`.
- `docs/robocode-physics.md` — engine physics rules and constants reference.
- `docs/robocode-scoring.md` — scoring breakdown (survival, bullet damage, etc.).
- `docs/rumble-metrics.md` — APS/PWIN/survival metric definitions.

## Robot Independence

Each robot is its own design. There is no shared architecture mandated here: a
robot's layering, models, and tactics live entirely in its own module and its
`bots/<name>/AGENTS.md`. Robots may happen to resemble one another, but that is
incidental — do not treat any one robot's structure as a workspace-wide
contract, and do not factor shared behavior up into common infrastructure.

This independence is also a hard dependency rule: do not add dependencies
between `zen.fencer.*`, `zen.ronin.*`, and `zen.mirage.*` for robot tactics,
models, profiles, or tuning. Robots may serve as engineering references for one
another, but code is copied or reimplemented locally, never shared by import.

## Build Constraints

Do not undo these:

1. Robots deploy as JARs, not loose classes.
2. Kotlin and Java bytecode target Java 8 for Robocode's BCEL scanner.
3. Kotlin 2.4.0 matches the stdlib bundled with the engine.
4. `kotlin-stdlib` is provided by Robocode and is not bundled.
5. Headless runs on Java 18+ need the AWT headless flag, SecurityManager
   opt-in, and `--add-opens` flags already supplied by the scripts.

## Change Discipline

- Keep generated/cache outputs out of Git; `.cache/` and `robocode/` are local
  artifacts.
- Do not reintroduce robot data-file persistence unless explicitly requested;
  current adaptive state is in-memory for the running battle/JVM only.
- Prefer small, explicit protocol/model types with clear ownership and units.
- Keep one source of truth between simulated and executed controls.
- Validate formatting with `just lint`; run `just build` for behavior-affecting
  Kotlin changes.
- Validate combat changes with APS/win-rate comparisons against appropriate
  `refs.jsonc` catalogs when the engine and reference bots are available.
- Use conventional commits; messages in English; code identifiers/paths verbatim.
