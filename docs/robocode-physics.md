# Robocode 经典版 · 游戏物理规则

> 来源：[RoboWiki / Game Physics](https://robowiki.net/wiki/Robocode/Game_Physics)、[The Book of Robocode 速查表](https://book.robocode.dev/appendices/quick-reference.html)、[`Rules` API](https://robocode.sourceforge.io/docs/robocode/robocode/Rules.html)。数值以 Robocode 1.10.x 为准。
>
> **重要**：引擎内所有常量都定义在 `robocode.Rules` 类里。写机器人战术和 engine-facing
> 代码时应引用 `Rules.MAX_VELOCITY` 等常量，并用 `Rules.getBulletDamage(power)` 这类
> 静态方法计算，**不要硬编码**这些数字。

## 实现范围

`docs/robocode-physics.md` 只记录 Robocode 经典版的外部规则事实。机器人实现需要物理
预测时，应在机器人自己的 package 中保持自包含，并显式说明与引擎规则的对应关系。

## 1. 坐标系与战场

- 笛卡尔坐标，原点 **(0,0) 在左下角**，X 向右增大，Y 向上增大。
- **方向角采用顺时针、以北为 0°**：0°/360° = 北，90° = 东，180° = 南，270° = 西。
  （注意：这与数学上常见的“逆时针、以东为 0°”相反，三角函数换算时要小心。）
- 默认战场尺寸 **800 × 600** 像素，但**不保证固定**——机器人必须用 `getBattleFieldWidth()` / `getBattleFieldHeight()` 自适应。
- 机器人**碰撞箱是不旋转的 36 × 36 像素正方形**（轴对齐，AABB）。

## 2. 移动（平移）

| 量 | 值 | `Rules` 常量 |
|----|----|-------------|
| 加速度 | **1 像素/回合²** | `ACCELERATION` |
| 减速度 | **2 像素/回合²**（刹车比加速快） | `DECELERATION` |
| 最大速度 | **8 像素/回合** | `MAX_VELOCITY` |

- 速度可正可负（负为倒车），绝对值上限 8。
- 想停下时，引擎按减速度 2 主动刹车。
- 位移公式 `d = v·t`，速度变化 `v = v₀ + a·t`。

## 3. 转向（车身 / 炮管 / 雷达）

三者**速率叠加**：炮管转速叠加在车身上，雷达转速叠加在炮管上。

| 部件 | 最大转速 | `Rules` 常量 |
|------|---------|-------------|
| 车身 | **(10 − 0.75 × |velocity|) 度/回合** | `MAX_TURN_RATE`（= 10，静止时） |
| 炮管（相对车身） | **20 度/回合** | `GUN_TURN_RATE` |
| 雷达（相对炮管） | **45 度/回合** | `RADAR_TURN_RATE` |

- **车身转速随速度下降**：静止时最快 10°/回合，全速（8）时只剩 `10 − 0.75×8 = 4`°/回合。
  → 想急转弯就得减速。用 `Rules.getTurnRate(velocity)` 计算。
- 默认情况下炮管会随车身一起转、雷达随炮管一起转。可分别用
  `setAdjustGunForRobotTurn(true)` / `setAdjustRadarForGunTurn(true)` 解耦，让瞄准/扫描独立于车身——这是稳定锁定与瞄准的基础。

## 4. 雷达与扫描

- 扫描半径 **`RADAR_SCAN_RADIUS = 1200` 像素**。
- 只有**本回合雷达扫过的扇形弧内**、且距离 ≤ 1200 的敌人才会触发 `onScannedRobot` 事件。雷达不转就扫不到——必须持续转动雷达。
- 1v1 锁定的常用做法：让雷达每回合“过冲”一点点反向扫，把敌人锁在扫描弧中央。

## 5. 子弹（开火）

开火功率 **firepower ∈ [0.1, 3.0]**（`MIN_BULLET_POWER` / `MAX_BULLET_POWER`）。

| 量 | 公式 | `Rules` 方法 |
|----|------|-------------|
| 子弹速度 | **20 − 3 × power** 像素/回合 | `getBulletSpeed(power)` |
| 子弹伤害 | power ≤ 1：**4 × power**；power > 1：**4 × power + 2 × (power − 1)** | `getBulletDamage(power)` |
| 开火产生的炮热 | **1 + power / 5** | `getGunHeat(power)` |
| 击中返还能量 | **3 × power**（给开火方回血） | — |
| 开火消耗能量 | **= power**（从自身能量扣除） | — |

要点：
- **开火即扣能量**（扣 `power`），击中敌人才返还 `3×power` 净赚，打空则净亏 `power`。
- 子弹越强越慢但伤害越高；远距离宜用低功率（命中率换速度），近距离宜用高功率。
- 子弹速度恒定、**不受重力/空气阻力影响**，沿直线飞。

## 6. 炮管热量（射速）

- 炮热 > 0 时**不能开火**，必须等冷却到 0。
- **默认冷却速率 0.1/回合**（可由对战设置 `gunCoolingRate` 修改）。
- 战斗开始时炮是热的（初始炮热 3.0，约前 30 回合无法开火）。
- 射速由功率决定：开 power=3 的炮产生炮热 `1+3/5=1.6`，需 16 回合冷却；开 power=0.1 产生 `1.02`，约 11 回合。**功率越高，射速越慢。**

## 7. 能量

- **初始能量 100**（1v1 / 标准对战）。
- 能量来源：击中敌人 `+3×power`，撞击敌人造成伤害也按规则得分（见记分文档），但**开火、被击中、撞墙、被撞都会扣能量**。
- **能量降到 0 → 机器人被“禁用”（disabled）**：不能移动、转向、开火，只能等待；此时再受任何伤害即被摧毁。
- 能量 < 0 → 机器人被摧毁出局。
- 低能量时应停止盲目开火（开火也耗能），优先靠走位续命。

## 8. 碰撞

### 撞墙
- 伤害 **max(|velocity| × 0.5 − 1, 0)**（`getWallHitDamage(velocity)`）：低速擦墙不掉血，高速撞墙最多约 3 点。
- 撞墙后机器人停在墙边，速度归零，触发 `onHitWall`。
- 实战必须做**撞墙平滑（wall smoothing）**，靠近边界时提前转向，避免高速撞墙掉血、被卡。

### 撞机器人（ramming）
- 每次碰撞**双方各扣 0.6 能量**（`ROBOT_HIT_DAMAGE`）。
- 主动撞击（朝对方移动）的一方有得分奖励（见记分文档），相关奖励常量 `ROBOT_HIT_BONUS = 1.2`。
- 碰撞触发 `onHitRobot`，双方移动受阻。

## 9. 每回合处理顺序（Turn Order）

理解这个顺序对预测/瞄准至关重要——**子弹比机器人先移动**：

1. 渲染战场画面（仅显示）
2. 所有机器人执行代码，直到发出一个动作（阻塞型）或调用 `execute()`
3. 时间 `time` +1
4. **所有子弹移动**并检测碰撞（先于机器人移动 → 你这回合发的子弹下回合才动）
5. **所有机器人移动**：依次更新 炮管 → 雷达 → 车身朝向 → 加/减速 → 速度 → 位移；同时炮热下降
6. 所有机器人执行**扫描**（触发 `onScannedRobot` 等）
7. 机器人代码恢复执行
8. 处理事件队列（`onHitByBullet`、`onHitWall`、`onHitRobot` 等回调）

> 含义：当你 `fire()` 后，子弹要到**下一回合**才开始飞行；做线性/圆周预测瞄准时，必须把这一回合的处理顺序和子弹飞行时间算进去。

## 关键常量速查（`robocode.Rules`）

```
ACCELERATION        = 1        // 像素/回合²
DECELERATION        = 2        // 像素/回合²
MAX_VELOCITY        = 8        // 像素/回合
MAX_TURN_RATE       = 10       // 度/回合（静止时车身）
GUN_TURN_RATE       = 20       // 度/回合（相对车身）
RADAR_TURN_RATE     = 45       // 度/回合（相对炮管）
RADAR_SCAN_RADIUS   = 1200     // 像素
MIN_BULLET_POWER    = 0.1
MAX_BULLET_POWER    = 3.0
ROBOT_HIT_DAMAGE    = 0.6      // 撞击双方各扣
ROBOT_HIT_BONUS     = 1.2

// 方法（不要自己硬编码公式，直接调用）：
getTurnRate(v)          // 10 - 0.75*|v|
getBulletSpeed(p)       // 20 - 3*p
getBulletDamage(p)      // 4*p (+ 2*(p-1) if p>1)
getBulletHitBonus(p)    // 击杀奖励相关
getGunHeat(p)           // 1 + p/5
getWallHitDamage(v)     // max(|v|*0.5 - 1, 0)
```
