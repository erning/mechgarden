# Mirage 雷达快速搜寻对手

本文记录 `zen.Mirage` 当前实现的 1v1 冷启动搜敌方法：每个 round 开始时机器人
还没有任何敌方信息，必须尽快让雷达扫到对手并建立稳定锁定。搜敌、锁定、重搜的
完整生命周期都收在 `Radar` 类里，`Mirage` 只负责驱动它（见第 7 节）。实现见
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
`Radar` **故意把这两个 adjust 标志留在默认的 `false`**，让三者叠加：

| 部件 | 静止开局时的最大转速 |
|------|----------------------|
| 车身 | 10°/tick |
| 炮管 | 20°/tick |
| 雷达 | 45°/tick |
| **叠加后雷达扫过的角度** | **75°/tick ≈ 1.31 rad/tick** |

`Radar.beginRound()` 让车身、炮管、雷达朝同一方向无限旋转：

```kotlin
setTurnRightRadians(angle)
setTurnGunRightRadians(angle)
setTurnRadarRightRadians(angle)
```

相比纯雷达扫描（45°/tick），叠加后达到约 75°/tick，搜敌明显更快。

## 3. 朝场地中心扫

`angle` 的转向（正负）不是随便选的，而是朝**场地中心**那一侧：

```kotlin
val centerBearing = Angles.absoluteBearing(x, y, battleFieldWidth / 2.0, battleFieldHeight / 2.0)
val angle = if (Angles.normalizeRelative(centerBearing - radarHeadingRadians) >= 0.0)
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

所以在 `Radar.onScan` 里，首帧按 offset 大小分两种处理：

```kotlin
if (!locked) {
    locked = true
    val offset = Angles.normalizeRelative(absBearing - radarHeadingRadians)
    if (Math.abs(offset) > Rules.RADAR_TURN_RATE_RADIANS) {   // > 45°
        // 越过太远：保持耦合一个 tick，让车身 + 炮管 + 雷达一起朝目标转（~75°/tick）
        coupledLockPending = true
        setTurnRightRadians(offset)
        setTurnGunRightRadians(offset)
        lock(absBearing)
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

## 5. 过冲锁定：过冲量随方位角速度自适应

只转“刚好对准”会因为对手移动而在下一 tick 滑出波束边缘、丢失扫描。所以
`Radar.lock` 指向目标绝对方位后还要**过冲**，让波束每 tick 都扫过目标再回来，
从而稳定地逐帧重新捕获。

固定 2 倍过冲（`offset × 2`）对温和对手已经几乎不丢锁，但近距离贴脸、撞击时
对手的绝对方位每 tick 变化会冲高，固定过冲盖不住，就会掉一帧（瞎 2 tick 后
重新扫回）。改为过冲量随方位角速度自适应：

```kotlin
fun lock(absBearingRadians: Double) {
    val offset = Angles.normalizeRelative(absBearingRadians - bot.radarHeadingRadians)
    val lead =
        if (无历史) Math.abs(offset)                                  // 回退到 2 倍过冲
        else Math.min(Math.abs(bearingRate) + SAFETY_MARGIN, MAX_LEAD) // 自适应：角速度 + 余量
    val direction = if (offset >= 0.0) 1.0 else -1.0                  // 朝 offset 那一侧过冲
    bot.setTurnRadarRightRadians(offset + direction * lead)
}
```

- **方向用 `sign(offset)`，不是 `sign(bearingRate)`**：朝「接近目标的那一侧」过冲，
  波束在目标两侧来回摆动、自我纠正回到目标。若改用方位漂移方向过冲，波束会顺着
  漂移一路追跑、彻底丢锁（实测会把丢失次数放大几个数量级）。
- **过冲量 = `|bearingRate| + SAFETY_MARGIN`**（`SAFETY_MARGIN = 15°`，上限
  `MAX_LEAD = 45°`）：对手方位变得越快，过冲越大，正好盖住下一 tick 的漂移加余量。
- **`bearingRate`** 由 `Radar` 自存上一帧方位/时间求得；丢锁 `search()` 时清空历史，
  重新锁定的第一帧没有历史，`lead` 回退到 `|offset|`，等价于原来的 2 倍过冲。

实测（各 200 round，统计「丢了活目标又找回」的次数）：

| 对手 | 固定 2 倍 | 自适应 |
|------|-----------|--------|
| `sample.Tracker`（贴身追打） | 491 | 0 |
| `sample.RamFire`（撞击） | 312 | 0 |
| `sample.Crazy`（温和绕行） | 0 | 0 |

贴脸对手的逐帧掉锁基本被消除。等 Mirage 之后开始平移，自身切向速度会叠加到
方位角速度上，这层自适应余量会更重要。

## 6. 丢失后重新搜寻

锁定后如果超过 `REACQUIRE_TICKS`（1 tick）没有新扫描，就判定丢锁、重新甩雷达。
这个检查在 `Radar.update()` 里，由主循环每 tick 调一次：

```kotlin
// Mirage.run()
while (true) { radar.update(); execute() }

// Radar.update()
if (locked && bot.time - lastScanTime > REACQUIRE_TICKS) search()
```

内部 `search()` 把雷达设为无限旋转、并清空方位历史（让下一帧锁定回退到 2 倍
过冲，见第 5 节），回到全速扫描直到再次扫到对手。

## 7. 封装与驱动接口

上面的搜敌、锁定、重搜逻辑全部收在 `Radar` 类里，`Mirage` 只负责驱动它，外加一
个占位枪。`Radar` 对外只有三个驱动方法和一个状态属性：

| 成员 | 调用时机 | 作用 |
|------|----------|------|
| `beginRound()` | `run()` 开头一次 | 开局叠加扫描、朝场地中心（第 2、3 节）。 |
| `update()` | 主循环每 tick | 锁定后超时无扫描就重搜（第 6 节）。 |
| `onScan(e)` | 每次 `onScannedRobot` | 维持锁定（第 4、5 节）。 |
| `state` | 任意时刻只读 | 当前所处相位（见下表）。 |

驱动骨架：

```kotlin
override fun run() {
    radar.beginRound()
    while (true) { radar.update(); execute() }
}

override fun onScannedRobot(e: ScannedRobotEvent) {
    radar.onScan(e)
    if (radar.state == Radar.State.ACQUIRING) return   // 见下
    // ……枪等其它层在这里从事件 e 自取数据……
}
```

`Radar.state` 暴露隐式的生命周期相位：

| 状态 | 含义 |
|------|------|
| `SEARCHING` | 开局扫描，还没扫到人。 |
| `ACQUIRING` | 冷启动宽 overshoot 的恢复 tick，雷达正借用车身/炮管甩波束（第 4 节）；这帧调用方不要动枪。 |
| `LOCKED` | 稳定锁定，每 tick 都有新扫描。 |
| `REACQUIRING` | 丢锁了，正在重搜。 |

**设计取舍：`Radar` 不对外暴露任何敌方数据**（方位、距离、速度一律不给）。它的产
出是「锁住对手 ⇒ 每 tick 必有一帧 `onScannedRobot`」这个保证；枪、将来的跟踪与移
动层各自在 `onScannedRobot(e)` 里从事件 `e` 采集自己要的量，不经过 `Radar`。唯一
的例外是 `state` 的 `ACQUIRING`——只有 `Radar` 知道它那帧借用了炮管，必须让调用方
让位。

## 8. 当前边界

冷启动雷达只解决“搜到并锁住对手”。锁定后 Mirage 目前仍然只朝当前敌方绝对方位
做 head-on 最小火力开火，车身不平移。跟踪、移动与波冲浪是后续层的职责。

## 参考

- [Robocode 经典版 · 游戏物理规则](../../../docs/robocode-physics.md) —— 车身/炮管/雷达转速等约束。
- `bots/mirage/src/main/kotlin/zen/mirage/Mirage.kt` —— 驱动 `Radar`（`beginRound` / `update` / `onScan`）并做占位枪。
- `bots/mirage/src/main/kotlin/zen/mirage/Radar.kt` —— 搜敌、锁定、重搜的完整生命周期与 `state` 状态机。
