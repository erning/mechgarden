# Robocode 经典版 · 子弹命中判定

> 版本：以 Robocode 1.11.0 源码为准。相关实现见 `robocode.battle.peer.BulletPeer` 与 `robocode.battle.peer.RobotPeer`。

## 1. 对手的受击箱

Robocode 经典版里，每个机器人都有一个**不旋转的 36×36 像素正方形**碰撞箱（AABB），中心与机器人坐标 `(x, y)` 重合：

```
left   = x - 18
right  = x + 18
bottom = y - 18
top    = y + 18
```

这个正方形**不会随着车身朝向旋转**，始终与坐标轴对齐。所有涉及子弹、撞墙、撞机器人的碰撞检测都基于这个固定正方形。

## 2. 子弹被当作一条“飞行线段”

子弹在战场上不是一个点，也不是一个圆。引擎在每一回合用一条**从上一帧位置到本帧位置的线段**来做命中检测：

```java
// BulletPeer.updateMovement()
lastX = x;
lastY = y;
x += velocity * sin(heading);
y += velocity * cos(heading);
boundingLine.setLine(lastX, lastY, x, y);
```

命中判定就是检查这条线段是否与对手的 36×36 正方形相交：

```java
// BulletPeer.checkRobotCollision()
if (otherRobot.getBoundingBox().intersectsLine(boundingLine)) {
    // 命中
}
```

也就是说，只要子弹**本回合飞过的路径**穿进或擦到对手的正方形，就算击中。

## 3. 每回合处理顺序

理解下面这个顺序对写瞄准/走位代码很重要（`docs/robocode-physics.md` 也有说明）：

1. 渲染战场画面。
2. 所有机器人执行代码，直到完成一个动作或调用 `execute()`。
3. 时间推进 `time++`。
4. **所有子弹先移动并检测碰撞**（包括子弹-机器人、子弹-子弹、子弹-墙）。
5. 所有机器人再移动、转向、扫描、降低炮热。
6. 处理事件队列，触发 `onBulletHit`、`onHitByBullet` 等回调。

因此，**你这回合调用 `fire()` 后，子弹要到下一回合才开始飞行并被判定命中**。

## 4. 命中后的处理

子弹命中对手后，引擎会：

- 把子弹状态设为 `HIT_VICTIM`。
- 目标按 `Rules.getBulletDamage(power)` 扣血。
- 射击方按 `Rules.getBulletHitBonus(power)` 回血，即 `3 × power`。
- 如果目标能量降到 `0` 及以下，目标被击杀。
- 向目标发送 `HitByBulletEvent`，向射击方发送 `BulletHitEvent`。

## 5. 其他碰撞的判定差异

| 碰撞类型 | 判定方式 |
|---------|---------|
| 子弹-机器人 | 子弹飞行线段与机器人 36×36 AABB 相交 |
| 子弹-墙 | 子弹半径 `RADIUS = 3`，触及战场边界 `x ± 3` / `y ± 3` 即消失 |
| 子弹-子弹 | 两条子弹飞行线段相交，则双方同时销毁 |
| 机器人-机器人 | 两个 36×36 AABB 相交 |

## 6. 实战含义

- **瞄准**：子弹命中只需要飞行路径穿过对手正方形，不需要精确击中中心。预测对手下一回合位置时，可以把目标区域按 36×36 来考虑。
- **走位**：因为碰撞箱不旋转，车身朝向不影响被弹面积；左右/前后移动时被弹面积相同。
- **开火时机**：由于子弹在下一回合才动，`fire()` 必须在预计命中角度已经对准之后再调用，否则子弹会沿当前炮管方向飞出。
