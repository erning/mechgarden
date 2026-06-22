# AGENTS.md

## Scope

These instructions apply to `bots/mirage/**`. The root `AGENTS.md` still applies.
Where the two conflict, this file takes precedence for Mirage work.

## Mirage Role

`zen.Mirage` is MechGarden's third defensive, movement-first 1v1 wave-surfer.
Implementation lives in `zen.mirage.*`.

Current state: a full movement-first 1v1 wave-surfer. A cold-start radar locks
the enemy; a scan→track→fire pipeline drives a virtual-gun array plus a
DC (KNN) dynamic-clustering gun (forced primary once it has learned enough);
enemy shots are detected from energy and modelled as expanding waves; the body
surfs the nearest wave toward the lowest-danger guess factor, protected by
bullet shadows cast by our own outgoing bullets; bullet-shielding and anti-shield
edge-aim are handled. Adaptive state (danger ensemble, DC observations) lives in
per-enemy static registries that survive the per-round robot rebuild. See
`docs/tuning.md` for the diagnostic + A/B override system.

Keep Mirage self-contained: put all tactics, models, profiles, tuning, and state
under `zen.mirage.*`, and depend on no other robot's implementation (see the root
`AGENTS.md` self-containment rule). Other robots may be used as engineering
references, but do not copy their complexity before Mirage needs it.

## Design Docs

- `bots/mirage/docs/radar.md` — the cold-start radar fast-search and lock.
- `bots/mirage/docs/tuning.md` — per-round diagnostics (`mirage.debug`) and the
  A/B tuning overrides, with the measurements that settled the current defaults.

## Units And Coordinates

Mirage runtime code uses radians throughout.

- Use Robocode radians APIs: `headingRadians`, `gunHeadingRadians`,
  `radarHeadingRadians`, `bearingRadians`, `setTurnRightRadians`,
  `setTurnGunRightRadians`, and `setTurnRadarRightRadians`.
- Geometry helpers and any future snapshot/tracker/gun/motion state use radians.
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

Keep Mirage layered so early prototypes do not turn into global strategy code.

- `Radar` owns scan coverage, target lock, and reacquire search only. It does not
  infer enemy tactics.
- `Gun` owns aiming, fire gating, firepower selection, and our outgoing bullet
  state.
- `Motion` only executes already chosen movement controls; it does not invent
  strategic goals.
- Enemy-fact derivation, enemy-wave tracking, danger modeling, and dodge
  decisions belong in their own layers when added, not buried in `Radar` or the
  main robot.

## State And Persistence

- Keep Mirage adaptive state in memory for the running battle/JVM only. Do not
  write Robocode data files.
- Round-local state is fine; cross-round state must have a clear owner. No global
  singletons for tactical state.

## Build And Validation

- Follow the root Kotlin/JVM and Robocode deployment constraints; do not change
  build constraints locally.
- After Kotlin changes, run `just lint` at minimum. Run `just build` for behavior
  changes.
- Verify deployability with `just deploy mirage`.
- Validate combat changes with `just duel -r Mirage -e <enemy>` or an appropriate
  catalog.
