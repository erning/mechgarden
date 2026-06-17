# Robocode 经典版 · `setXxx` 命令与动作排队

> 版本：以 Robocode 1.11.0 源码为准。核心实现见
> `robocode.host.proxies.BasicRobotProxy` 与 `robocode.AdvancedRobot`。

## 1. 核心规则

在 `AdvancedRobot` 里，所有 `setXxx()` 调用**都不会立即生效**，而是把目标值写进一个
内部 `commands` 对象。真正提交给引擎是在调用 `execute()` 时； thereafter 这些命令会
在后续 tick 里逐步执行。

同一 tick 内多次调用同一类 `setXxx()`，遵循两条规则：

1. **移动、转向、速度：最后一次调用覆盖前面的值。**
2. **开火：`setFire()` / `setFireBullet()` 受炮热限制，同一 tick 只能成功一次。**

## 2. 移动与转向：last one wins

底层都是直接改写同一个 pending 值：

| API | 底层字段 |
|---|---|
| `setAhead()` / `setBack()` | `commands.setDistanceRemaining(distance)` |
| `setTurnRightRadians()` / `setTurnLeftRadians()` | `commands.setBodyTurnRemaining(radians)` |
| `setTurnGunRightRadians()` / `setTurnGunLeftRadians()` | `commands.setGunTurnRemaining(radians)` |
| `setTurnRadarRightRadians()` / `setTurnRadarLeftRadians()` | `commands.setRadarTurnRemaining(radians)` |

所以同一 tick 内：

```java
setAhead(100);
setAhead(50);
```

最终排队的是 `50`，第一个被覆盖。官方示例也明确说明：

```java
setTurnRightRadians(Math.PI);
setTurnRightRadians(-Math.PI / 2);
// 最终执行的是最后一次 setTurnRightRadians(-Math.PI / 2)
```

## 3. 开火：`setFire` 每 tick 最多一次

`setFire()` / `setFireBullet()` 每次调用都会检查当前炮热：

```java
if (getGunHeatImpl() > 0 || getEnergyImpl() == 0) {
    return null;  // 无法开火
}
```

同一 tick 内：

```java
setFire(3.0);
setFire(3.0);  // 第一次已增加 firedHeat，第二次被忽略
```

因此实际只有第一发能成功。想再开火必须等炮热降到 0。

## 4. 命令是“持续的”，不是单 tick 的

`setXxx()` 设置的是**目标剩余量**，不是“本 tick 动多少”。例如：

```java
setAhead(100);
execute();
while (true) {
    execute();  // 机器人会继续走完剩余距离
}
```

机器人会分多个 tick 逐步走完 100 像素。如果你想中途停下来，需要显式覆盖：

```java
setAhead(0);
```

转向同理：`setTurnRightRadians(Math.PI)` 会分多个 tick 转完 180°。

## 5. `execute()` 的作用

- 把当前 `commands` 里的目标值提交给引擎。
- 一次 `execute()` 对应一回合；调用后机器人代码暂停，等待引擎处理完这一 tick。
- 在 `BasicRobot` 里，阻塞式 API（如 `ahead()`、`turnRight()`）内部会隐式调用 `execute()`。

## 6. 解耦标志是持久状态

`setAdjustGunForRobotTurn(true)`、`setAdjustRadarForGunTurn(true)` 这类标志一旦设置就会
一直生效，直到你再次改为 `false`。它们不是每 tick 都要调用。

## 7. 在事件回调里调用 `setXxx`

在 `onScannedRobot()`、`onCustomEvent()` 等回调里调用 `setTurnRadarRightRadians(...)`，
同样是写入 `commands`，并在**下一 tick** 的 `execute()` 时生效。因此事件回调里覆盖转向，
与 `run()` 循环里覆盖转向没有本质区别，都是“最后一个写的值生效”。

## 8. 实战要点

- **避免在 `run()` 里每 tick 重复设置同一个无限转向**，否则会把雷达/车身锁定在“永远转向”
  的状态。例如 `setTurnRadarRightRadians(Double.POSITIVE_INFINITY)` 每 tick 调用一次，
  雷达会不停地以最大速度旋转。
- **锁定雷达时应在 `onScannedRobot` 里重新设置转向**，因为每个 tick 都需要根据最新方位
  调整。
- **想取消一个正在执行的移动/转向，必须显式设 0**，而不是停止调用 `setXxx`。

## 9. 一句话总结

> 同类 `setXxx()` 最后一次调用覆盖前面；开火受炮热限制每 tick 一次；命令一旦设置就持续
> 生效，直到完成或被新命令覆盖。
