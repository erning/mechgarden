# Robocode 经典版 · 事件模型

> 版本：以 Robocode 1.11.0 源码为准。核心实现见 `robocode.host.events.EventManager`、
> `robocode.Event`、`robocode.Condition`、`robocode.CustomEvent`。

## 1. 两条核心规则

1. **事件驱动 + 动作排队**
   - 引擎把战斗过程中发生的事封装成 `Event`，放进每个机器人的事件队列。
   - 你在 `run()` 或事件回调里调用的 `setTurnXxx`、`setFire` 等动作**不会立即执行**，
     而是进入待执行队列，等 `execute()`（或阻塞 API 隐式调用 `execute()`）时才提交给引擎，
     在下一 tick 生效。

2. **每 tick 处理一次事件队列**
   - 时间推进、子弹/机器人移动、扫描完成后，引擎调用 `EventManager.processEvents()`。
   - 队列里的系统事件 + 本 tick 触发的 `CustomEvent` 一起按**优先级**排序，
     然后依次派发给你写的 `onXxx` 回调。

## 2. 事件的生命周期

```
引擎产生事件 ──► EventManager.add(event) ──► 进入 EventQueue
                                                     │
processEvents() ◄────────────────────────────────────┘
       │
       ├── 清除超过 2 tick 的旧事件
       ├── 轮询所有 CustomEvent 的 Condition.test()
       ├── 按（优先级 desc，时间 asc）排序
       └── 依次 dispatch → onXxx()
```

细节：

- **时间戳**：事件被加入队列时打上当前 `time`。过老的事件（`MAX_EVENT_STACK = 2`）会被丢弃。
- **队列上限 256**：超过时新事件会被打印警告并丢弃。
- **关键事件（Critical event）**：`WinEvent`、`DeathEvent`、`BattleEndedEvent`、
  `RoundEndedEvent`、`SkippedTurnEvent` 不能改优先级，且即使过期也会派发。

## 3. 优先级与处理顺序

每个事件类有默认优先级（0–99，越大越优先）。常见事件默认值：

| 事件 | 默认优先级 | 备注 |
|---|---|---|
| `StatusEvent` | 99 | 每 tick 的状态快照 |
| 键盘/鼠标事件 | 98 | 仅 GUI 对战可用 |
| `WinEvent` / `BattleEndedEvent` / `SkippedTurnEvent` | 100 | 系统事件，不可改优先级 |
| `RoundEndedEvent` | 110 | 系统事件，不可改优先级 |
| `DeathEvent` | -1 | 系统事件，不可改优先级 |
| `MessageEvent` | 75 | 团队对战消息 |
| `RobotDeathEvent` | 70 | 任意机器人死亡 |
| `BulletMissedEvent` | 60 | 子弹未命中 |
| `BulletHitBulletEvent` | 55 | 子弹互撞 |
| `BulletHitEvent` | 50 | 自己的子弹命中敌人 |
| `HitRobotEvent` | 40 | 撞到其他机器人 |
| `HitWallEvent` | 30 | 撞墙 |
| `HitByBulletEvent` | 20 | 被敌人子弹命中 |
| `ScannedRobotEvent` | **10** | 扫描到机器人 |
| `PaintEvent` | 5 | 自定义绘制 |
| `CustomEvent` | **80** | 自定义事件，默认高于扫描 |

`processEvents()` 按优先级从高到低依次派发。可以用 `setEventPriority(String, int)` 修改
非关键事件类的优先级，但通常只在 `AdvancedRobot` 里这么做。

## 4. 中断（Interruptible）

可以用 `setInterruptible(priority, true)` 把某个优先级设为可中断。如果正在处理该优先级
事件时，同优先级的新事件到来，引擎会抛出 `EventInterruptedException`，终止当前回调，
转去处理新事件。

```java
setInterruptible(ScannedRobotEvent.getDefaultPriority(), true);
```

典型用途：在 `onScannedRobot` 里做复杂计算时，如果更新的扫描到达，可以放弃旧计算。

## 5. CustomEvent 的特殊规则

`CustomEvent` 不是引擎直接产生的事件，而是你自己注册的条件。

### 5.1 注册与触发

```java
addCustomEvent(new Condition("mycondition", priority) {
    public boolean test() {
        return /* 某个布尔条件 */;
    }
});
```

- 每 tick 的 `processEvents()` 会遍历所有 `Condition`，调用 `test()`。
- `test()` 返回 `true` → 生成一个 `CustomEvent` 加入队列。
- 队列排序后，按优先级派发给你的 `onCustomEvent(CustomEvent e)`。

### 5.2 关键限制

1. **`Condition.test()` 不能调用动作 API**  
   源码里会设置 `robotProxy.setTestingCondition(true)`，只应读取状态并返回布尔值。
   如果在这里调用 `setTurnXxx`，行为会被引擎限制。

2. **动作必须放在 `onCustomEvent` 里**  
   `onCustomEvent` 也是事件回调，这里设置的转向/开火同样下一 tick 生效。

3. **优先级默认 80，通常要调低**  
   比如雷达控制想基于最新扫描，就要把 `CustomEvent` 优先级设成低于
   `ScannedRobotEvent`（默认 10），例如 5。否则 CustomEvent 会比扫描先处理，
   拿到的是旧数据。

4. **移除条件**  
   如果是一次性条件，在 `onCustomEvent` 里 `removeCustomEvent(e.getCondition())`，
   否则会每 tick 重复触发。

## 6. BasicRobot 与 AdvancedRobot 的差异

| 特性 | BasicRobot | AdvancedRobot |
|---|---|---|
| 动作方式 | 阻塞式（`ahead(100)` 会卡住直到完成） | 非阻塞式（`setAhead(100)` + `execute()`） |
| 事件处理 | 阻塞调用内部会隐式处理事件 | 需要自己调用 `execute()` 触发一回合 |
| 事件机制 | 同样使用 `EventManager` 队列 | 同样使用 `EventManager` 队列 |
| CustomEvent | 可用 | 可用 |

两者共享同一套 `EventManager`，区别只是代码风格和动作触发方式。

## 7. 对战术设计的含义

- `onScannedRobot` 是“扫描到目标”最自然的入口。
- 在事件回调里设置的雷达/炮管/车身转向，和 `run()` 循环里设置的一样，
  都是**下一 tick 才生效**。
- 如果你的 `CustomEvent` 依赖 `ScannedRobotEvent` 更新过的状态，
  一定要把它的优先级设得比 `ScannedRobotEvent` 低。
