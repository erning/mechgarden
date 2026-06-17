# AGENTS.md

## Scope

These instructions apply to `bots/fencer/**`. The root `AGENTS.md` still applies.
Where the two conflict, this file takes precedence for Fencer work.

## Fencer Role

`zen.Fencer` is MechGarden's original layered 1v1 wave-surfer and the baseline
the newer robots are measured against. Implementation lives in `zen.fencer.*`.

Core capabilities:

- Radar locking and scan tracking.
- Enemy-wave detection and multi-wave surfing.
- A virtual-gun GuessFactor array for targeting.
- Bullet shadows in the danger model.

## Status

Fencer is complete and frozen. Its strategy and algorithms are not under active
development:

- Do not change combat behavior — targeting, movement, gun/surf models — to
  chase better results.
- Do not rework implementation algorithms (sorting, partitioning, data
  structures) for performance either; the robot is done, not a tuning target.
- New tactics or approaches belong in a new robot module, not retrofitted here.

Still fine without asking: bug fixes that restore intended behavior, build or
deployment repairs, formatting, and documentation. Anything that changes how
Fencer fights needs an explicit request.

## Self-Containment

Fencer must remain self-contained. Do not add dependencies on packages outside
`bots/fencer` for robot tactics, models, profiles, or tuning. Other robots may
borrow ideas from Fencer, but Fencer imports nothing from them.

## Layer Boundaries

Keep Fencer's behavior local to each layer (see the shared architecture in the
root `AGENTS.md`):

- `Radar` keeps observations fresh and owns lock/reacquire; no tactics.
- `EnemyTracker` / `Snapshot` turn scans into same-tick facts and derived
  kinematics; no prediction or fire inference.
- `Gun` / `VirtualGuns` / `GuessFactorGun` own targeting, virtual-gun selection,
  fire gating, and outgoing bullet state.
- `EnemyWaveTracker` / `Surfer` / `GuessFactorDanger` / `BulletShadows` own
  enemy-fire inference, the danger model, wave bookkeeping, and the dodge.
- `MotionController` only executes already chosen movement controls.

## State And Persistence

- Keep adaptive state in memory for the running battle/JVM only. Do not write
  Robocode data files.
- Per-opponent state has a clear owner inside `zen.fencer.*`; no global
  singletons for tactical state.

## Build And Validation

- Follow the root Kotlin/JVM and Robocode deployment constraints; do not change
  build constraints locally.
- After Kotlin changes, run `just lint` at minimum. Run `just build` for
  behavior changes.
- Verify deployability with `just deploy fencer`.
- Validate combat changes with `just duel -r Fencer -e <enemy>` or an
  appropriate catalog.
