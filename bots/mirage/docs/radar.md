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

## 4. 首次接触：解耦并锁定

扫到第一帧后（`onScannedRobot`）立刻切换状态：

```kotlin
if (!locked) {
    locked = true
    setTurnRightRadians(0.0)          // 停掉开局的车身旋转
    isAdjustGunForRobotTurn = true    // 解耦炮管与车身
    isAdjustRadarForGunTurn = true    // 解耦雷达与炮管
}
```

- **停车身**：开局的无限旋转只为搜敌，锁定后不再需要。
- **解耦**：把 adjust 标志设为 `true`，雷达转动从此独立，不再被车身/炮管的转动
  干扰，锁定才稳。

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
