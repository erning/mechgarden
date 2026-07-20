# AGENTS.md

## Scope

These instructions apply to `bots/proteus/**`. The root `AGENTS.md` still applies.
Where the two conflict, this file takes precedence for Proteus work.

## Proteus Role

`zen.Proteus` is MechGarden's adaptive 1v1 robot, built to approach and
eventually surpass `kc.mega.BeepBoop` with original code. The design context is
`docs/beepboop-analysis.md`: adopt the published methods (path surfing, KNN with
learned embeddings, gated danger ensembles, uncertainty-aware switches, correct
bullet shadowing), never copy BeepBoop's code or class layout. Every subsystem
has its own implementation written for this module.

The selectable shell is `zen.Proteus` (a one-line concrete class); the
implementation is `abstract class Proteus` in `zen.proteus.Proteus` plus helpers
under `zen.proteus.*`. Keep Proteus self-contained: no imports from other robot
modules (see the root `AGENTS.md` self-containment rule).

Current state: M6 complete (see `docs/roadmap.md`) — radar infinity lock; enemy
fire detection with compensation; expanding enemy waves with precise
intersection; a gated danger-model ensemble (hit bins, flattener, simulated
HOT/linear/circular/avg-linear guns, CurrentGF, KNN danger) with dynamic
accuracy weights and bullet-shadow discounts; three-option true surfing; a
dual-tree KNN gun (main / anti-surfer hard-switched on our hit-rate interval,
didHit-marked); guarded firepower (kill-shot / disable guard over a distance
rule) and plan-linked active bullet shadowing at fire time. A best-first
PathSurfer exists but is disabled behind `Mover.MOVEMENT_ENGINE`. Later
milestones plug into the existing seams documented in `docs/architecture.md`;
extend them instead of adding parallel systems.

## Design Docs

- `bots/proteus/docs/architecture.md` — target architecture, per-tick pipeline,
  command-channel discipline, and how each subsystem evolves.
- `bots/proteus/docs/roadmap.md` — milestone plan M1–M8 with measurable exit
  criteria and the catalogs used to validate each step.

## Units And Coordinates

Proteus runtime code uses radians throughout.

- Use Robocode radians APIs: `headingRadians`, `gunHeadingRadians`,
  `radarHeadingRadians`, `bearingRadians`, `setTurnRightRadians`,
  `setTurnGunRightRadians`, and `setTurnRadarRightRadians`.
- Geometry helpers and any snapshot/tracker/gun/motion state use radians.
- Do not store degrees in internal runtime models.
- Degrees are allowed only at boundaries where no radians API exists, in docs, or
  in debug output. Keep conversion close to the boundary.
- Robocode coordinates are unchanged: +x is east, +y is north; heading and
  bearing use north as 0 and increase clockwise.

## Naming

Use complete unit names for angle variables and properties, because runtime
angles must be radians and mixing units causes subtle tactical bugs.

- Runtime angle values must end with `Radians` (use `Radians`, not `Rad`).
- Use `absoluteBearingRadians`, not `absBearing` or `bearingRad`; use
  `gunHeadingRadians`, not `gunHeadingRad`.
- Do not force unit suffixes for distances, positions, velocities, or tick
  counts; names like `enemyDistance`, `wallDistance`, and `scanGap` are clear
  from the domain.
- Short names (`x`, `y`, `dx`, `dy`) are fine for local, conventional math; once
  a value is stored, returned, or crosses a layer boundary, use a descriptive
  name.

## Layer Boundaries

Keep Proteus layered so early prototypes do not turn into global strategy code.

- `zen.proteus.state.GameState` is the single source of truth for both robots'
  state. Subsystems read snapshots from it; only `Proteus` touches the robot
  API and engine events.
- Subsystems never call `set*` directly. Each writes only its own channel of
  `zen.proteus.control.Controls` (radar → `Radar`, body → `Mover`, gun/fire →
  `Aimer`), and the main loop commits the frame once with `execute()`.
- `Radar` owns scan coverage, target lock, and reacquire search only. It does
  not infer enemy tactics.
- `Mover` owns body movement and, later, wave surfing and danger estimation.
  `Aimer` owns aiming, fire gating, firepower, and outgoing-bullet bookkeeping.
- Enemy-fact derivation belongs in `GameState`; opponent classification belongs
  in the strategy layer when added (see `docs/architecture.md`).

## State And Persistence

- Keep Proteus adaptive state in memory for the running battle/JVM only. Do not
  write Robocode data files.
- Round-local state is fine; cross-round state must have a clear owner. When KNN
  trees and other learned state arrive, keep them in per-enemy static registries
  that survive Robocode's per-round robot rebuild. No global singletons for
  tactical state.

## Build And Validation

- Follow the root Kotlin/JVM and Robocode deployment constraints; do not change
  build constraints locally.
- After Kotlin changes, run `just lint` at minimum. Run `just build` for behavior
  changes.
- Verify deployability with `just deploy proteus`.
- Validate combat changes with `just duel -r Proteus -e <enemy>` or the catalog
  named by the current milestone in `docs/roadmap.md`; record results there.
  Use `-n 35` per battle and average several runs instead of one long battle
  (35-round runs vary by several APS; 2-3 runs is enough for A/B calls).
