# AGENTS.md

## Project

MechGarden is a Kotlin/JVM workspace for Robocode classic 1.10.3 robots.

Two active robot modules:

- **`zen.Fencer`** (`bots/fencer`) — the original layered 1v1 wave-surfer:
  radar locking, scan tracking, enemy-wave detection, virtual-gun GF array,
  bullet shadows, and multi-wave surfing. Implementation in `zen.fencer.*`.

- **`zen.Ronin`** (`bots/ronin`) — a second-generation 1v1 wave-surfer built
  to beat Fencer and advance toward expert-tier opponents. Implementation in
  `zen.ronin.*`. Key additions over Fencer:
  - **DC gun** (dynamic-clustering KNN targeting) as the primary aim, with
    pretrained distance-weight profiles embedded in the robot code.
  - **Firepower profiles** — per-opponent, per-shot selection over economy,
    balanced, pressure, and aggressive firepower shaping.
  - **Anti-shield aiming** — generic edge aiming when the tracker detects a
    stationary low-power bullet-shielding pattern.
  - **Movement profiles** — per-opponent, per-round selection over orbit/stop,
    second-wave, wall-risk, inertia, and tie-noise tradeoffs.
  - **Energy-tier firepower** — aggressive finish when leading, economy mode
    when even/behind.
  - **Adaptive tick-wave weight** — detects surfers (high lateral speed) and
    reduces virtual-shot learning density against them.
  - **Dynamic engagement range** — responds to the enemy's radial velocity
    (chargers pushed to range, kiters closed in).
  - **Per-opponent in-memory state** — danger/DC observations, selectors, and
    engagement parameters persist only for the current battle/JVM.
  - **Three-way quickselect** for O(n) KNN nearest-neighbour selection.

## Commands

Tasks run through `just` and the checked-in Gradle wrapper.

| Command | Purpose |
|---------|---------|
| `just robocode install` | Download and install the engine into `./robocode` |
| `just build` | Build every module |
| `just fmt` | Apply Spotless/ktlint formatting |
| `just lint` | Check formatting |
| `just deploy ronin` | Build and deploy `ronin.jar` |
| `just deploy fencer` | Build and deploy `fencer.jar` |
| `just duel -r Ronin -e Fencer` | Duel Ronin vs Fencer, default 35 rounds |
| `just duel -r Ronin -c basic` | Ronin vs basic catalog (3 bots) |
| `just duel -r Ronin -c top` | Ronin vs top catalog (3 bots) |
| `just duel -r Ronin -c roborumble-100` | Ronin vs the roborumble-100 catalog |
| `just run` | Launch the Robocode UI |

Use `just refs ...` to download, deploy, list, or index reference robots from
`refs.jsonc`. Catalogs: `basic`, `classic`, `expert`, `top`, `gigarumble`,
`roborumble-100`.

## Repository Layout

- `bots/fencer` — Fencer robot module.
- `bots/ronin` — Ronin robot module.
- `scripts/` — engine, reference-robot, duel, and UI launch tools.
- `docs/robocode-physics.md` — engine physics rules and constants reference.
- `docs/robocode-scoring.md` — scoring breakdown (survival, bullet damage, etc.).
- `docs/rumble-metrics.md` — APS/PWIN/survival metric definitions.
- `docs/benchmarks.md` — local duel benchmark snapshots.

## Robot Architecture (shared by Fencer and Ronin)

Both robots follow the same layered design; keep each robot's behavior explicit
and local to its own package.

- **Radar** keeps observations fresh; do not bury tactical decisions in radar.
- **Tracker** turns Robocode scans into consistent snapshots and derived facts
  (turn rate, lateral/advancing velocity, acceleration, wall distance).
- **Gun** owns targeting, virtual-gun selection, statistical guns, fire gating,
  firepower choice, and outgoing bullet state.
- **Wave tracking + surfing** own enemy-fire inference, danger model, wave
  passage bookkeeping, bullet shadows, and the orbit dodge.
- **Motion** executes the selected movement without inventing strategic goals.
- Per-opponent adaptive state lives inside each robot package and should not be
  hidden in shared infrastructure.

## Ronin-specific notes

- The DC gun is forced as the primary aim once it has ≥45 observations
  (`DC_PRIMARY_MIN`). Before that, virtual-gun selection picks the best.
- The DC gun has multiple embedded pretrained weight profiles. It scores them
  from resolved real DC waves and switches only after enough observations and
  feedback are available.
- `FirePowerSelector` chooses a power-shaping profile per real shot. Outcomes
  are scored from hit, miss, and hit-bullet events.
- `MovementProfileSelector` chooses one movement profile per round and scores it
  from real bullet damage taken at round end.
- `ShieldAimSelector` is active only when `EnemyTracker.shieldLikely` is true;
  it explores left/right edge aim and scores real edge-shot outcomes.
- `Distancing.targetRange` adapts per scan from the enemy's advancing velocity.
  `Gun.dcPowerFloor` remains a per-opponent in-memory economy floor; energy
  leads are handled by the gun's per-shot tiered floor.
- The DC gun's KNN uses a three-way quickselect (not a full sort) for O(n)
  nearest-neighbour selection; the `scoreBuf`/`histBuf` arrays are reused
  across scans (no per-scan allocation).
- Ronin must remain self-contained. Do not add dependencies on packages outside
  `bots/ronin` for robot tactics, models, profiles, or tuning.
- "Don't change the algorithm" means don't change the robot's strategy
  (targeting policy, movement policy, gun/surf models). Implementation
  algorithms (sorting, partitioning, data structures) are fair game.

## Build Constraints

Do not undo these:

1. Robots deploy as JARs, not loose classes.
2. Kotlin and Java bytecode target Java 8 for Robocode's BCEL scanner.
3. Kotlin 2.3.20 matches the stdlib bundled with the engine.
4. `kotlin-stdlib` is provided by Robocode and is not bundled.
5. Headless runs on Java 18+ need the AWT headless flag, SecurityManager
   opt-in, and `--add-opens` flags already supplied by the scripts.

## Change Discipline

- Keep generated/cache outputs out of Git; `.cache/`, `downloads/`, and
  `robocode/` are local artifacts.
- Do not reintroduce robot data-file persistence unless explicitly requested;
  current adaptive state is in-memory for the running battle/JVM only.
- Prefer small, explicit protocol/model types with clear ownership and units.
- Keep one source of truth between simulated and executed controls.
- Validate formatting with `just lint`; run `just build` for behavior-affecting
  Kotlin changes.
- Validate combat changes with APS/win-rate comparisons against appropriate
  `refs.jsonc` catalogs when the engine and reference bots are available.
- Use conventional commits; messages in English; code identifiers/paths verbatim.
