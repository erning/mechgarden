# Mirage 雷达快速搜寻对手

本文记录 `zen.Mirage` 当前实现的 1v1 冷启动搜敌方法：每个 round 开始时机器人
还没有任何敌方信息，必须尽快让雷达扫到对手并建立稳定锁定。实现见
`bots/mirage/src/main/kotlin/zen/mirage/{Mirage,Radar}.kt`。

## 1. 问题

1v1 每个 round 重新开局，双方在场地内随机生成，朝向也随机。锁定之前的每一
tick 都是“瞎子”：枪、移动、波判断都拿不到新鲜观测。所以冷启动只追求两件事：

1. 尽快拿到第一帧 `onScannedRobot`。
2. 拿到后立刻切换成稳定的 1v1 锁定，让后续 tick 持续有新鲜扫描。

雷达独立扫描的最大转速只有 45°/tick（见
[Robocode 经典版 · 游戏物理规则](../../../docs/robocode-physics.md)）。如果只转
雷达，最坏要扫小半圈才碰到对手。

## 2. 叠加扫描：把车身和炮管的转速也借过来

Robocode 里雷达的**绝对**转动 = 车身转动 + 炮管转动 + 雷达自身转动，除非用
`setAdjustRadarForGunTurn` / `setAdjustGunForRobotTurn` 把它们解耦。开局阶段
Mirage **故意把这两个 adjust 标志留在默认的 `false`**，让三者叠加：

| 部件 | 静止开局时的最大转速 |
|------|----------------------|
| 车身 | 10°/tick |
| 炮管 | 20°/tick |
| 雷达 | 45°/tick |
| **叠加后雷达扫过的角度** | **75°/tick ≈ 1.31 rad/tick** |

`acquireSweep()` 让车身、炮管、雷达朝同一方向无限旋转：

```kotlin
setTurnRightRadians(dir)
setTurnGunRightRadians(dir)
setTurnRadarRightRadians(dir)
```

相比纯雷达扫描（45°/tick），叠加后达到约 75°/tick，搜敌明显更快。

## 3. 朝场地中心扫

`dir` 不是随便选的方向，而是朝**场地中心**那一侧转：

```kotlin
val centerBearing = Angles.absoluteBearing(x, y, battleFieldWidth / 2.0, battleFieldHeight / 2.0)
val dir = if (Angles.normalizeRelative(centerBearing - radarHeadingRadians) >= 0.0)
    Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
```

理由：敌方出生点在场地内均匀分布。自己若生成在靠边的位置，对手更可能在“朝向
内部”的半边，先扫内侧期望命中更快。

实测：1000 round 对 `sample.Crazy`，叠加 + 朝中心的方式平均约 **2.2 tick**
找到对手（最坏 5 tick）；纯雷达固定方向扫描约 4.3 tick（最坏 8 tick）。

## 4. 首次接触：先把波束甩回来，再解耦锁定

冷启动的快速扫描要付出代价：开局三件套叠加约 75°/tick，扫到第一帧时波束往往
已经**越过对手一大截**，offset 可能超过解耦雷达单 tick 能转的 45°。如果这一帧
直接解耦、只靠雷达自身 45°/tick 去做过冲，下一 tick 波束转不回对手，就会瞎好
几 tick：实测最坏要 7–8 tick 才重新扫回。

所以首帧按 offset 大小分两种处理：

```kotlin
if (!locked) {
    locked = true
    val offset = Angles.normalizeRelative(absBearing - radarHeadingRadians)
    if (Math.abs(offset) > Rules.RADAR_TURN_RATE_RADIANS) {   // > 45°
        // 越过太远：保持耦合一个 tick，让车身 + 炮管 + 雷达一起朝目标转（~75°/tick）
        coupledLockPending = true
        setTurnRightRadians(offset)
        setTurnGunRightRadians(offset)
        radar.lock(absBearing)
        return
    }
    // 在解耦雷达够得着的范围内：停车身并立即解耦
    setTurnRightRadians(0.0)
    isAdjustGunForRobotTurn = true
    isAdjustRadarForGunTurn = true
}
```

- **offset > 45°**：先别解耦，借开局还在转的车身（10°/tick）和炮管（20°/tick），
  与雷达（45°/tick）叠加成约 75°/tick，一个 tick 把波束甩回对手；下一帧
  （`coupledLockPending`）再停车身、置 adjust 标志解耦，进入稳定锁。
- **offset ≤ 45°**：解耦雷达本就够得着，照旧停车身、立即解耦。阈值取雷达自身
  转速 `RADAR_TURN_RATE_RADIANS`：更小的 offset 若仍用三者叠加，反而会冲过头到
  另一侧、又超出 45°。
- **解耦**：把 adjust 标志设为 `true`，雷达转动从此独立，不再被车身/炮管干扰，
  锁定才稳。

实测：100 round 对 `sample.Crazy`，修法前约 5% 的 round 在首帧后立刻掉锁、瞎
2–8 tick；修法后这类冷启动掉锁降为 0，且首帧搜敌时间不变（仍约 2.2 tick）。

## 5. 2 倍过冲锁定

`Radar.lock` 把雷达转向目标的绝对方位，并**过冲 2 倍**：

```kotlin
fun lock(absBearingRad: Double) {
    val offset = Angles.normalizeRelative(absBearingRad - bot.radarHeadingRadians)
    bot.setTurnRadarRightRadians(offset * LOCK_OVERSHOOT)   // LOCK_OVERSHOOT = 2.0
}
```

只转“刚好对准”会因为对手移动而在下一 tick 滑出波束边缘、丢失扫描。过冲 2 倍
让波束每 tick 都扫过目标再回来，从而稳定地逐帧重新捕获。

## 6. 丢失后重新搜寻

主循环里，如果锁定状态下超过 `REACQUIRE_TICKS`（1 tick）没有新扫描，就重新
甩雷达搜寻：

```kotlin
while (true) {
    if (locked && time - lastScanTime > REACQUIRE_TICKS) radar.search()
    execute()
}
```

`Radar.search()` 把雷达设为无限旋转，回到全速扫描直到再次扫到对手。

## 7. 当前边界

冷启动雷达只解决“搜到并锁住对手”。锁定后 Mirage 目前仍然只朝当前敌方绝对方位
做 head-on 最小火力开火，车身不平移。跟踪、移动与波冲浪是后续层的职责。

## 参考

- [Robocode 经典版 · 游戏物理规则](../../../docs/robocode-physics.md) —— 车身/炮管/雷达转速等约束。
- `bots/mirage/src/main/kotlin/zen/mirage/Mirage.kt` —— 开局叠加扫描、首帧解耦、主循环重搜。
- `bots/mirage/src/main/kotlin/zen/mirage/Radar.kt` —— 过冲锁定与重新搜寻。
