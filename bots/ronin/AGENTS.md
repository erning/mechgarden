# AGENTS.md

## Scope

These instructions apply to `bots/ronin/**`. The root `AGENTS.md` still applies.
Where the two conflict, this file takes precedence for Ronin work.

## Ronin Role

`zen.Ronin` is a second-generation 1v1 wave-surfer built to beat Fencer and
advance toward expert-tier opponents. Implementation lives in `zen.ronin.*`.

Key additions over Fencer:

- **DC gun** (dynamic-clustering KNN targeting) as the primary aim, with
  pretrained distance-weight profiles embedded in the robot code.
- **Firepower profiles** — per-opponent, per-shot selection over economy,
  balanced, pressure, and aggressive firepower shaping.
- **Anti-shield aiming** — generic edge aiming when the tracker detects a
  stationary low-power bullet-shielding pattern.
- **Movement profiles** — per-opponent, per-round selection over orbit/stop,
  second-wave, wall-risk, inertia, and tie-noise tradeoffs.
- **Energy-tier firepower** — aggressive finish when leading, economy mode when
  even/behind.
- **Adaptive tick-wave weight** — detects surfers (high lateral speed) and
  reduces virtual-shot learning density against them.
- **Dynamic engagement range** — responds to the enemy's radial velocity
  (chargers pushed to range, kiters closed in).
- **Per-opponent in-memory state** — danger/DC observations, selectors, and
  engagement parameters persist only for the current battle/JVM.
- **Three-way quickselect** for O(n) KNN nearest-neighbour selection.

## Status

Ronin is complete and frozen. Its strategy and algorithms are not under active
development:

- Do not change combat behavior — targeting, movement, firepower, gun/surf
  models — to chase better results.
- Do not rework implementation algorithms (sorting, partitioning, data
  structures) for performance either; the robot is done, not a tuning target.
- New tactics or approaches belong in a new robot module, not retrofitted here.

Still fine without asking: bug fixes that restore intended behavior, build or
deployment repairs, formatting, and documentation. Anything that changes how
Ronin fights needs an explicit request.

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
- The engagement range (`targetRange`, a Ronin-owned field persisted per
  opponent) adapts per scan from the enemy's advancing velocity and is threaded
  into the surfer/motion; `Distancing` is stateless. The economy firepower floor
  is the constant `Gun.DC_POWER_FLOOR_BASE`; energy leads are handled by the
  gun's per-shot tiered floor.
- The DC gun's KNN uses a three-way quickselect (not a full sort) for O(n)
  nearest-neighbour selection; the `scoreBuf`/`histBuf` arrays are reused across
  scans (no per-scan allocation).

## Design Docs

Ronin-specific architecture and design notes live under `bots/ronin/docs/`:

- `bots/ronin/docs/deep-dive.md` — full walkthrough of Ronin's strategy and
  engineering, with clickable `file:line` references.
- `bots/ronin/docs/benchmarks.md` — local `just duel` benchmark snapshots.

General Robocode techniques Ronin builds on (DC/KNN, GuessFactor, flattening,
surfing styles) are documented under the root `docs/`.

## Self-Containment

Ronin must remain self-contained. Do not add dependencies on packages outside
`bots/ronin` for robot tactics, models, profiles, or tuning.

## Build And Validation

- Follow the root Kotlin/JVM and Robocode deployment constraints; do not change
  build constraints locally.
- After Kotlin changes, run `just lint` at minimum. Run `just build` for
  behavior changes.
- Verify deployability with `just deploy ronin`.
- Validate combat changes with APS/win-rate comparisons against appropriate
  `refs.jsonc` catalogs, e.g. `just duel -r Ronin -c basic` or
  `just duel -r Ronin -e Fencer`.
