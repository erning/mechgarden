# BeepBoop 源码深度解读

> 分析对象：`kc.mega.BeepBoop 2.0`（2024 年 1 月发布），作者 Kevin Clark（Kev）。分析依据：机器人 JAR 内嵌的完整 Java 源码（RWPCL 协议，`robot.include.source=true`），辅以作者在 RoboWiki 上发表的《Understanding BeepBoop》。文中所有类名、常量与数值均直接取自源码。

## 1. 概览

BeepBoop 是 Kev 时隔十余年重返 Robocode 后的作品，长期占据 Roborumble 1v1 综合榜第一。它继承 `AdvancedRobot`，构建于 Robocode 1.9.4.8，代码规模约 96.7 KB。

从大的技术路线上看，它是「KNN + Wave Surfing」这一代顶级机器人的集大成者：

- 移动：**Path Surfing**——作者提出的一种 Wave Surfing 推广形式，把「前进 / 后退 / 停车直到波通过」推广为对任意移动路径序列做 best-first 搜索。
- 瞄准：**KNN GuessFactor 瞄准**——两把 KNN 枪（普通 / 反冲浪）按命中率动态切换。
- 危险评估：22 个危险模型组成的**动态加权集成**，按敌方命中率门控、按预测准确度在线调权。
- 特色系统：Bullet Shielding（子弹护盾）、主动 Bullet Shadowing（主动挡弹）、基于期望得分最大化的子弹威力选择、离线梯度下降学习的 KNN 嵌入函数。

KNN、搜索、物理模拟只是骨架；BeepBoop 真正的强大在于大量「针对对手类型」的特殊处理（见第 8 节）和离线训练出来的参数（见第 4.5 节）。

## 2. 代码组织与总体架构

源码按子系统分包，每个类职责单一。包结构如下（第三方类用 * 标注）：

| 包 / 类 | 职责 |
|---|---|
| `kc.mega.BeepBoop` | 主类。事件分发、每 tick 主循环、模式开关（SHIELD / TC / MC / MC2k7） |
| `kc.mega.game.GameState` | 双方当前状态、100 tick 历史、10 tick 未来预测、敌人开火检测（能量差） |
| `kc.mega.game.BotState` | 单个机器人的状态（位置、航向、速度、能量、枪热、开火计数） |
| `kc.mega.game.PredictState` | 不可变状态基类；1 tick 精确前推、趋势外推、碰撞检测 |
| `kc.mega.game.Physics` | Robocode 物理公式纯函数（加减速、转弯限速、子弹速度 / 伤害 / 枪热） |
| `kc.mega.game.BattleField` | 战场几何：镜像点、两种贴墙平滑、轨道墙距离、撞墙处理（枚举单例） |
| `kc.mega.game.HitRateTracker` | 命中率统计：Beta 先验、置信区间、按最大逃逸角加权校正 |
| `kc.mega.scan.Scanner` | 1v1 雷达：infinity lock 窄带锁定，丢失后 3 次反转转全场扫描 |
| `kc.mega.shared.Strategy` | 对手分类与特殊模式总开关（反 ram、反镜像、护盾、撞击等，见第 8 节） |
| `kc.mega.shared.WaveManager` | 波注册中心；维护 bullet shadow 的叠加与失效 |
| `kc.mega.wave.Wave` | 波基类：推进、精确交集（precise intersection）、bullet shadow 计算 |
| `kc.mega.wave.Waves` | 波列表管理：按威力 / 位置匹配命中事件 |
| `kc.mega.wave.WaveWithFeatures` | 特征化波：计算全部原始特征（见 4.2 节） |
| `kc.mega.wave.GFBins` | 151 个 GuessFactor bin；区间覆盖权重、指数核平滑 |
| `kc.mega.wave.Bullet` | 在飞子弹（位置、航向、速度、发射时刻） |
| `kc.mega.model.Model` | 机器学习模型基类（name + train/onTurn 钩子） |
| `kc.mega.model.WaveKNN` | KNN 核心：Rednaxela KD 树、可学习嵌入、softmax 加权（见 4.1 节） |
| `kc.mega.aim.Aimer` | 瞄准主控：每 tick 造波、选枪、开火决策、主动 bullet shadowing |
| `kc.mega.aim.BulletPowerSelector` | 子弹威力选择：期望得分最大化（见第 6 节） |
| `kc.mega.aim.models.AimModels` | 两把 KNN 枪的定义（特征集 + 离线学得的参数） |
| `kc.mega.aim.models.KNNAimModel` | KNN 瞄准模型：GF 密度峰搜索、候选角度采样 |
| `kc.mega.aim.wave.AimWave(s)` | 瞄准用波（我方开火视角）及管理 |
| `kc.mega.move.Mover` | 移动主控：维护敌方波（含 gun heat 波）、驱动危险学习 |
| `kc.mega.move.PathSurfer` | Path Surfing 搜索：best-first、计划复用、执行第一步（见 3.1 节） |
| `kc.mega.move.Paths` | 预计算路径库：按（初速、长度、距离、末速）建 KD 树查表 |
| `kc.mega.move.Simulation` | 单条路径的逐 tick 精确模拟（物理、敌人外推、未来枪热波） |
| `kc.mega.move.DangerEstimator` | 22 个危险模型的集成：门控、动态权重、平滑、阴影（见 3.4 节） |
| `kc.mega.move.ShadowScorer` | 为主动 bullet shadowing 评估候选开火角度 |
| `kc.mega.move.models.DangerModels` | 全部危险模型的工厂（特征集 + 参数） |
| `kc.mega.move.models.KNNDangerModel` | KNN 危险模型：邻居 hitGF 加权投到 bin 上 |
| `kc.mega.move.models.SimpleDangerModels` | 模拟经典瞄准法（HOT / 线性 / 圆形 / 平均线性 / nano 线性 / 当前 GF） |
| `kc.mega.move.models.APMFlattener` | 反模式匹配 flattener：实时模拟敌方 PM 瞄准并惩罚被预测点 |
| `kc.mega.move.wave.MovementWave(s)` | 移动用波（敌方开火视角）及管理、可冲浪波筛选 |
| `kc.mega.shield.Shielder` | Bullet Shielding：5 个子弹预测器在线竞争、护盾计划（见第 7 节） |
| `kc.mega.utils.*` | MathUtils（softmax/归一化）、Geom（几何/逃逸角）、MovingAverage、Range、DatasetWriter（离线数据导出）、Painter（调试绘图） |
| `ags.utils.KdTree` * | Rednaxela 的 KD 树实现（曼哈顿距离版本） |
| `jk.math.FastTrig` * | 查表法快速三角函数 |
| `wiki.mc2k7.RaikoGun` * | Movement Challenge 2K7 用的标准枪（仅挑战模式） |

每 tick 的完整时间线（`BeepBoop.doTurn` 主流程 + tick 间隙的引擎事件回调）：

**0. tick 间隙（事件回调）**：`onScannedRobot` 只暂存扫描事件；`onBulletHit`（我方命中）给开火时刻 ±3 tick 内的 AimWave 打 `didHit` 标记；`onHitByBullet` / `onBulletHitBullet` 按威力与位置找回对应波、记录真实弹着点并触发危险学习（`learnFromBullet` → `onSeeBullet`），子弹相撞还会让 `WaveManager` 重算全部阴影；`onSkippedTurn` 只做无扫描的状态更新。

**1. 状态更新（`GameState`）**：本 tick 有扫描（`lastScanEvent.getTime() == getTime()`）→ `gs.update(scan)`：先用 bot API 真实值更新我方状态（位置、航向、速度、能量、枪热递减），再由扫描重建敌人状态（位置 = 我方位置按 bearing/distance 投影）、检测敌人开火（能量差 0.09999~3.0001 且上 tick 枪热 ≈ 0，补偿撞墙与我方子弹的能量扰动）、与移动侧发布的未来状态对账（偏差 > 1e-4 作废重建）；无扫描 → `gs.updateWithoutScan()`，敌人状态按滑行外推。

**2. 雷达（`Scanner`）**：有扫描 → `scan()` 发 `setTurnRadarRightRadians(radarTurn + sign × π/32)`——infinity lock，扫过目标多转 5.625°，保证下 tick 仍有扫描（本回合首次扫描还会把炮管从开局的全速转向中回正 π/10）；无扫描 → `search()` 朝最后已知敌人位置全速转，方向每反转一次计数，满 3 次后改单向全场扫描直到重新锁定。回合第一 tick 例外：`onRoundStart` 直接发 `setTurnGunRightRadians(±∞)` 与 `setTurnRadarRightRadians(±∞)` 全速转向场地中心，并设置 `setAdjustRadarForGunTurn(true)` / `setAdjustGunForRobotTurn(true)` 让雷达、炮管、车身转动解耦。

**3. 策略（`Strategy.strategize()`）**：只更新标志位、不发指令——antiRam、antiMirror、walkingStickSmooth、antiBasicSurfer、护盾退出、tryToDisable / ram（第 8 节）。

**4a. 护盾分支（`strategy.shield` 为真，`Shielder.shield()`）**：检测到敌人开火时造波、用当前最佳子弹预测器生成「wiggle × 威力」的候选 Plan 并入队；随后执行队首 Plan——车身转到与敌连线平行、炮管转 fireAngle、开火前 1 tick wiggle ±0.1px、到点 `setFire`；无 Plan 时停车、车身保持平行、枪指敌人待命。

**4b. 移动分支（否则，`Mover.move()`）**，内部严格按序：

1. 敌人本轮已开过火 → `makeMovementWave()`：按能量检测建真实波（`hasBullet = enemyFiredLastTick`，真弹确认后顶替旧的枪热虚拟波），并向前看未来 1~2 tick 的两个敌人预测状态，枪口即将冷却就造 gun heat 虚拟波；
2. `movementWaves.update(...)` 推进所有在飞波；完成越过的波：登记敌命中率（`logShotPassed`）、训练访问类危险模型（`onVisit`）；
3. `dangerEstimator.onTurn(...)`：刷新各波危险缓存、各模型 `onTurn`（APMFlattener 累积历史）、重算估计器激活集与动态权重；
4. `surfer.surf(...)`：弹掉旧计划首元素 → best-first 搜索 → 回溯出新计划 → 发出本 tick 全部车身指令（`setTurnRightRadians`、`setAhead`、`setMaxVelocity(8 − ε)`），并把计划的未来状态交给 `GameState.setMyFutureStates(...)` 供瞄准使用。

**5. 瞄准（`Aimer.aim()`）**，内部严格按序：

1. `aimWaves.update(...)` 推进瞄准波，完成的波同时训练 Main 与 AntiSurfer 两把 KNN 枪并登记我方命中统计；
2. `BulletPowerSelector.bestBulletPower(...)` 算最优威力（封顶 3，无数据时 0.15）；
3. 开火判定：枪热归零、炮管剩余转角 < 0.001、威力条件满足 → `setFire`（用上一 tick 定下的威力）；
4. 造本 tick 的 AimWave（`hasBullet` = 本 tick 是否开火）；
5. 默认跟枪：`setTurnGunRightRadians`（以预测的下一 tick 我方位置指向敌人）；
6. 枪热 ≤ 3 tick → 建下一 tick 的临时瞄准波：> 1 tick 用 `getAimGFFast` 快速粗瞄，= 1 tick 走约 15 个候选角 + 阴影评分精瞄（覆盖第 5 步的默认角度）；否则（远离开火或本 tick 刚开过火）执行 `pickAimModel()` 决定两把 KNN 枪用哪把。

**6. 绘图与提交**：`Painter.paint(...)`（仅 UI 重绘的 tick 收集图形），`execute()` 把本 tick 排队的全部指令一次性提交给引擎。

注意：指令虽按上述顺序排队，物理上却是在本 tick 末尾同时生效的——这个顺序真正服务的是**数据依赖**：移动先发布未来计划，瞄准才能用预测位置与阴影评分；雷达先于一切，保证扫描链不断。

另有若干编译期模式开关：`SHIELD`（默认开）、`TC`（Targeting Challenge）、`MC`（Movement Challenge）、`MC2k7`（此时换用 RaikoGun 射击）、`VERBOSE`。

### 指令的排队、提交与通道分工

各子系统在 `doTurn()` 里只调 `set*` 系列把指令排入队列，真正的提交发生在 `run()` 循环末尾的 `execute()`（`BeepBoop.java:65`）：本 tick 排队的全部指令一次性发给引擎，由引擎在这个 tick 的物理更新里统一应用（车身移动、炮管/雷达转动、新子弹出现），随后产生下一 tick 的事件。同一通道在同一 tick 被多次赋值时是覆盖语义（`execute()` 只提交最后一次赋值），但 BeepBoop 靠调度结构保证每个通道每 tick 只有一个写入点，不依赖覆盖行为：

- 雷达：仅 `Scanner`（`Scanner.java:36/50/60`；MC2k7 模式另有 `RaikoGun.java:184`）。
- 车身转向：三处调用点互斥——`PathSurfer.java:96`（正常模式）、`Shielder.java:107`（护盾、无计划）、`Shielder.java:208`（护盾、执行计划）；`doTurn()` 按 `strategy.shield` 二选一分发，护盾内部两个分支也是二选一。
- 炮管与开火：正常模式仅 `Aimer`，护盾模式仅 `Shielder`。

唯一被重写的动作指令是 `setFire`（`BeepBoop.java:224`）：内部调用 `setFireBullet`，子弹真正产生时同步登记到 `GameState`（能量与统计）和 `WaveManager`（建子弹波），护盾模式下跳过登记（`BeepBoop.java:227-230`）。例外情况：没有扫描事件或已死亡时 `doTurn()` 直接返回，该 tick 不发任何指令，但 `execute()` 仍会被调用以推进时钟。

## 3. 移动：Path Surfing

### 3.1 路径表示与搜索

Path Surfing 是 True Surfing 的推广。一条「路径」是 `1 / 0 / -1` 的序列，分别代表该 tick 前进、停车、后退；对 rambot 时每个方向还要再乘上「左转 / 右转」两个选项。单元素路径（如 `[1]`）会被模拟器自动顺延为「保持该方向直到波通过」，所以它与 True Surfing 的「一直前进 / 一直后退 / 停住」完全兼容，但还能表达「前进 3 tick 再倒车 5 tick」这类组合动作。

搜索由 `PathSurfer` 完成，本质是一棵按危险度排序的 best-first 树（A\* 变体）：

- 每个节点展开 4~6 个候选扩展：上一 tick 最佳计划对应的扩展（**计划复用**，排序时给约 100 的危险偏置，强烈偏好沿用旧计划）、前进、后退、停车，以及两条 goto 式启发路径（见 3.2）。
- 剪枝：子节点按 `getDanger()` 升序排列，一旦「当前危险 + 启发式下界」超过已知最优就整支剪掉。启发式下界取自上一 tick 搜索树在各波上留下的最小可达危险（乘 0.9 的保守系数）；若相邻两波的到达时间差 ≤ 3 tick（波重叠，下界不可靠）则不剪枝。
- 搜索深度为 3，对应三条波：第 1、2 条逐 tick 精确模拟 + 精确交集，第 3 条只做近似评分（危险打 `THIRD_WAVE_DISCOUNT = 0.75` 的折扣）。
- 搜索结束后从最优叶节点回溯出完整计划，每 tick 只执行计划的第一步，下一 tick 重新搜索（弹掉已执行的首元素）。

### 3.2 Paths：预计算路径库

goto 式路径不靠在线搜索，而是查表：

- 离线（机器人启动时）按初速 0~8（步长 0.5，17 档）枚举所有合法路径：长度上限 50 tick，每条路径**最多允许一次方向反转**，速度推进用真实物理（加速 1、减速 `clip(1 + v/2, 1, 2)`、限速 ±8）。
- 每个前缀路径按「(末速 × 3, 累计距离, 随机数)」插入对应长度的 KD 树。第三维随机数使得「距离与末速都相同但形状不同」的路径被随机返回——这是 BeepBoop「随机化末速度」的来源之一，让它会时而「先停再走」、时而「先走再停」，影响下一条波的可达区域。
- 使用方式：在已模拟的前进 / 后退轨迹上找近似危险最低的点，取该点的行驶距离与一个随机目标末速（0 或 ±8），查表得到一条能到达该低危险区的路径加入候选。

### 3.3 Simulation：逐 tick 精确模拟

对每条候选扩展，`Simulation` 逐 tick 推进：

- 我方：按轨道 / 反 ram / 撞击策略算目标航向 → 贴墙平滑（见下）→ 按真实物理（转弯限速 `10° − 0.75°×|v|`、加减速、36px 半径撞墙钳回）前推。
- 敌方：普通情况按「匀速 + 保持当前转弯率」外推；反 ram 时用追踪模型——敌人以带提前量的追踪角（`我方速度 × sin(航向差) / 15`）冲向我方，会做撞墙检测，相撞后停住。
- 波：精确模拟时对每条波做 precise intersection——把 36×36 的车身方块与波环 `[r, r+speed]` 求交，得到本 tick 会被击中的角度偏移区间，累计进「访问区间」。
- 波通过后再额外模拟几步用于位置评分：普通 5 tick、反 ram 20 tick、撞击时距离 ÷ 20。
- **未来枪热波**（仅反 ram）：模拟过程中若敌人枪热将在未来某 tick 冷却完成（最多向前看约 15 tick），就地在模拟出的未来状态上造一条「模拟波」加入波列表——于是搜索树会提前躲避 rammer 尚未发射的子弹。这会产生「提前扭一下让圆形瞄准落空」这类行为。

贴墙平滑有两套：默认用 fancy stick（按速度对四面墙逐面平滑）；`Strategy` 判断对手是带撞墙检测的简单瞄准法或镜像 bot 时，换用更宽松的 walking stick（150px 探杆、25px 边距），让敌人的预测反而更准、更单调——方便躲。

### 3.4 路径评分

节点总分 = `(基础危险 + 波危险) × 位置乘子`。

波危险：对该节点触到的每条波，把访问区间换算成 GF 区间，向 `DangerEstimator` 查询区间危险，按波权重累加：

```
波权重 = (0.2 + 子弹威力) / sqrt(4 + max(1, 距波到达的 tick 数))
```

即威力越大、到达越快的波权重越高（开根号时间衰减）。

位置乘子体现战术偏好：

- 基础项 `exp(起点距敌 / 终点距敌)`：惩罚接近敌人（撞击策略下取倒数）。
- 反 ram：再惩罚接近模拟敌人、贴墙、被墙夹住的位置。
- 普通：放大自己的最大逃逸角、压缩敌人的最大逃逸角（利用墙限制对方的 MEA）。
- 反镜像：`Strategy.getAntiMirrorDanger` 给出**负危险**奖励——把路径终点镜像到场地中心对称点，若该镜像点恰好落在敌人子弹的 GF 附近（镜像 bot 会朝镜像点开火），奖励 `0.75 × exp(-30 × |ΔGF|) / (1 + tick 数)`，奖励幅度以该波的子弹危险值为上限。

执行层面的小动作：不停车时 `setAhead(±∞)` 让引擎按最大加减速执行；停车时给 `(random − 0.5) / 1e12` 的趋零随机量；`setMaxVelocity(8 − random / 1e12)`——经典的 8−ε 技巧，让精确匹配速度的瞄准法失准，同时让自己看起来永远在加减速。

## 4. KNN 体系

### 4.1 WaveKNN：KNN 核心

所有 KNN 模型共用 `WaveKNN`（基于 Rednaxela 的 KD 树，曼哈顿距离）：

- **嵌入函数**：特征向量先归一化（4.2 节），再逐维嵌入为 `w × (1e-4 + b + x)^a`。`w、b、a` 三个参数是**离线用随机梯度下降学出来的**（瞄准枪）；手工设定或离线学习的线性权重（移动模型，`params` 只有一个元素时退化为线性 `w × x`）。
- **可选 MLP**：嵌入向量还可再过一个小型全连接网络（ReLU 激活，最后一层无激活），输出逐维乘回嵌入——相当于给每个特征学一个上下文相关的门控。只有反冲浪枪用了（17 → 32 → 17）。
- **邻居数自适应**：`k = min(maxNeighbors, max(5, 树大小 / neighborhoodSizeDivider))`，数据越多邻居越多。
- **邻居加权**：`logit = 距离 × distanceScale`（distanceScale 为负，越近权重越大）后做 softmax，权重和为 1。
- 树容量：瞄准枪 50000，移动模型 3000（flattener 5000），超过后 KD 树内部淘汰。

瞄准模型存的值是「能命中敌人的 GF 区间」（`Range`），移动危险模型存的是标量 `hitGF()`。

### 4.2 特征全集

`WaveWithFeatures` 在波创建时计算原始特征；`WaveKNN.getNormalizedFeatures` 再做归一化。下表是全部归一化特征（即 KNN 的候选特征池）：

| 特征名 | 含义 | 归一化 |
|---|---|---|
| `virtuality` | 虚拟度：无子弹的波离真子弹波有多远（取枪热剩余 tick 与距上次开火 tick +1 的较小者；真子弹为 0） | ÷ 5 |
| `power` | 子弹威力 | ÷ 3 |
| `bft` | 子弹飞行时间 = 距离 ÷ 弹速 | ÷ 100 |
| `accel` | 加速度（带符号，减速为负，范围 [-2, 2]） | max(2 + a, 0) ÷ 2 |
| `accelSign` | 加速度符号 | signum |
| `latVel` | 横向速度分量 `|v × sin(相对航向)|` | ÷ 8 |
| `vel` | 速度绝对值 | ÷ 8 |
| `vel=8` | 是否全速（\|v\| > 7.9） | 0 / 1 |
| `advVel` | 纵向速度分量 `v × cos(相对航向)` | (x + 16) ÷ 8 |
| `advDir` | 纵向前进方向 `移动方向 × cos(相对航向)` | (x + 1) ÷ 2 |
| `vChangeTimer` | 距上次速度变化的 tick 数 | min(t, 70) ÷ bft |
| `dirChangeTimer` | 距上次速度方向反转的 tick 数 | 同上 |
| `decelTimer` | 距上次沿当前方向减速的 tick 数 | 同上 |
| `distanceLast10` | 近约 10 tick 的位移 | ÷ 80 |
| `distanceLast20` | 近约 20 tick 的位移 | ÷ 160 |
| `mirrorOffset` | 开火方下一位置相对场地中心的偏移角 × 轨道方向（反镜像特征） | + π |
| `orbitalWallAhead` / `orbitalWallReverse` | 轨道墙距离：沿轨道方向（正 / 反向）还能转多少个 MEA 才撞墙（二分 12 次，上限 1.5） | ÷ 1.5 |
| `maeWallAhead` / `maeWallReverse` | 墙压缩比：场内精确 MAE ÷ 理论 MAE（1 = 墙无影响） | 原值 |
| `stickWallAhead` / `stickWallReverse` | stick 墙特征 1：以轨道航向为基准，墙平滑会改变多少航向（160px 探杆） | ÷ (π/2) |
| `stickWallAhead2` / `stickWallReverse2` | stick 墙特征 2：以敌人当前航向为基准 | ÷ (π/2) |
| `stickWallAhead=0` / `stickWallReverse=0` | 墙平滑无影响的指示位 | 0 / 1 |
| `gameTime` | 开火时刻 | ÷ 500 |
| `shotsFired` | 开火方本场已发射数 | ÷ 1000 |
| `currentGF` | 敌人当前位置在最近一条波上的 GF | (1 + x) ÷ 2 |
| `didHit` | 该波附近 ±3 tick 内我方真有子弹命中过（反冲浪标记） | 0 / 1 |
| `didCollide` | 该波附近我方子弹与敌弹相撞过 | 0 / 1 |

三类墙特征分工不同（作者认为墙特征对瞄准极其重要）：

- **orbital**：RaikoMicro / Thorn 式的经典轨道墙距离；
- **MAE 比**：直接度量墙把精确最大逃逸角压缩了多少；
- **stick**：度量「按 David Alves 的 walking stick 墙平滑，敌人航向会被迫改变多少」——模拟对方机器人自己的贴墙行为。

### 4.3 KNN 的分类与职责

BeepBoop 的 KNN 分两大用途：**瞄准**（2 个模型，二选一）与**移动危险评估**（14 个 KNN 模型 + 1 个 PM 模拟器，加权集成）。

#### 瞄准 KNN（`AimModels`）

| 模型 | 职责 | 特征（20 / 17 个） | 参数 |
|---|---|---|---|
| `Main`（反随机 / 普通枪） | 打非冲浪者 | power、virtuality、bft、accel、vel、vel=8、advDir、dirChangeTimer、decelTimer、distanceLast20、mirrorOffset、maeWallAhead、maeWallReverse、stickWallAhead、stickWallReverse、stickWallAhead2、stickWallReverse2、stickWallAhead=0、stickWallReverse=0、shotsFired | 嵌入全参数离线 SGD 学得；distanceScale −0.6551；maxNeighbors 200；divider 5 |
| `AntiSurfer`（反冲浪枪） | 打会躲子弹的冲浪者 | virtuality、bft、accel、vel、vel=8、advDir、dirChangeTimer、decelTimer、distanceLast10、mirrorOffset、maeWallAhead、maeWallReverse、stickWallAhead、stickWallReverse、stickWallAhead2、didHit、didCollide | 外加 17→32→17 MLP；distanceScale −0.1824；maxNeighbors 100；divider 5 |

反冲浪的关键在 `didHit`：命中冲浪者后，对方会刻意避开被打中的角度。BeepBoop 把「子弹命中时刻 ±3 tick 内经过的波」标记 `didHit = 1`（相撞则标 `didCollide`），而瞄准时的查询永远带 `didHit = 0`——于是那些「命中之后」的样本在嵌入空间里与当前查询距离变远，自然被挤出近邻。注意作者训练后发现：**反冲浪枪并没有给 recency（近期性）多大权重**，这与大多数反冲浪枪「只用近期扫描 / 高滚动平均」的做法相反。

#### 移动危险 KNN（`DangerModels`）

全部以「敌方子弹的 hitGF」为标签，分四小类：

1. **通用简单模型**（手工权重）：`Simple`（bft、vel、latVel、advDir、maeWallAhead）、`Simple2`（bft、accel、vel、advDir、maeWallAhead、stickWallAhead）。
2. **按具体对手离线训练的模型**（权重 SGD 学得，以训练对手命名）：`Thorn`、`Sedan`、`Druss`（DrussGT）、`Diamond`、`Komarious`、`Splinter`、`WaveShark`、`MicroAspid`。特征集各不相同（3~13 个，详见 `DangerModels`），例如 `Druss` 用了 13 个特征，`Splinter` 只有 bft、latVel、maeWallAhead 三个。它们的作用是：当对手命中率水平表明它是一把「强 GF 枪」时，用针对性训练过的嵌入去躲。
3. **Flattener 模型**（4 个，特征都含 `virtuality`，用访问数据训练）：`Flattener1`（对 Diamond、DrussGT 训练）、`Flattener2`（对 WaveSerpent、CassiusClay、Gressuffard）、`Flattener3`（对 Tomcat、Gilgalad）、`Flattener4`（对 BeepBoop 0.1、Komarious、Cunobelin）。flattener 的经典作用是把「自己去过的位置」也记为危险，抹平被对手学到的移动轮廓。
4. **APMFlattener**：不是 KNN，而是在线模拟对手的模式匹配枪——维护一份「以敌为观察者」的我方运动符号串（横向速度量化成字符），实时找出最多 10 个历史匹配、回放子弹飞行时间得到敌人 PM 会算出的提前量角，把这些角度的 GF bin 涂危险（匹配串越长权重越大）。数据不足 64 tick 时不启用。

### 4.4 多 KNN 的选择机制

两套选择机制完全不同，这是理解 BeepBoop 的关键。

**瞄准：二选一的硬切换。** `Aimer.pickAimModel()` 在远离开火时刻时执行：

```
我方命中率置信区间与 [0, 12%] 有重叠 → AntiSurfer，否则 → Main
```

即「我打不中它（命中率可能低于 12%）→ 它是冲浪者 → 换反冲浪枪」。没有用传统的 Virtual Guns 胜场统计，因为两把枪都始终在用每 tick 的虚拟波全量训练，切换成本为零；命中率本身就足够区分对手类型。

**移动：门控 + 动态加权的软集成。** `DangerEstimator` 持有 22 个估计器（上节 14 个 KNN/PM + HOT、Linear、LinearWithWalls、Circular、AvgLinear、NanoLinearFixedSpeed、CurrentGF 共 7 个模拟瞄准模型）。每个估计器有四要素：

- `minHitRate / maxHitRate`：**激活门控**。敌方命中率置信区间与该区间重叠才激活。含义是「敌人能打出这个命中率，说明它的枪属于这一类」。例如 Linear / Circular / NanoLinear 上限 7%（敌人打得准就说明它不是简单线性枪）；Druss / Sedan 下限 8%、Diamond 下限 7%、Thorn / Simple2 / APM 下限 6%、Komarious 下限 5%、WaveShark / MicroAspid 下限 3%、flattener 下限 9% / 2%；AvgLinear 与 CurrentGF 的区间被设为 1（100%），实际上只靠下一条规则兜底激活。
- **强制激活**：归一化权重大于 `1.33 / 22 ≈ 6%` 时无视门控强制激活——预测得准的模型可以「破格」。
- **动态权重**：每次观测到敌弹真实落点（我方中弹或子弹相撞时更新），看该模型在真实 hitGF 附近给出的危险有多高，写入滚动平均（rate 0.98），权重 = `avgBulletDanger^3`（前两次观测前权重为 1）。给敌人实际弹着点分配更高危险的模型获得更高权重。按作者说明，调权刻意以子弹相撞（而非子弹命中）为依据，避免「我方总往自认安全处移动」带来的选择偏差。
- `multiplier`：离线学得的基准倍率（0.15~3.5 不等，如 WaveShark 3.5、Linear 0.15）。

特殊状态另有两条覆盖规则：`antiHOT` 时 HOT 模型权重 ×2；`antiRam` 时只保留 Circular、Linear、Sedan、NanoLinearFixedSpeed 四个模型（倍率 1、1、1、0.5），且模拟波只用 Circular 与 NanoLinearFixedSpeed。

**组合管线**（`DangerEstimator.DangerTracker`，结果按波缓存、按需失效）：

1. 各激活模型输出 151 bin 危险数组，各自归一化；
2. 按「动态权重 × multiplier」加权求和（命中模型与访问模型分开求和再相加；虚拟波只用命中模型）；
3. 指数核 bin 平滑（λ = 13，核宽约 ±23 bin）；
4. 叠加 bullet shadow：`危险 × (1 − shadow × 0.98)`（不完全信任阴影）；
5. 再归一化。

**模拟瞄准模型**（`SimpleDangerModels`）负责「近乎完美地躲掉简单枪」：从敌人视角实时仿真 HOT（当前方位 + 两个外推方位 + GF=0 先验 + 中央偏高的背景）、迭代线性 / 圆形（含撞墙停止变体）、平均线性、固定弹速 13 的 nano 线性、Current GF（当前 GF 及其镜像），各自在对应 GF 上涂一个窄峰（λ = 20）。

### 4.5 离线训练工作流

KNN 的嵌入参数不是手调的，而是离线学出来的：

1. 比赛时用 `DatasetWriter` 把特征与标签以 float32 二进制写入数据文件（默认关闭，源码中多数调用点被注释，属开发期工具）。
2. 离线训练：以「敌人实际落点的 GF 区间」为目标分布，用交叉熵损失对比 KNN 经核密度估计给出的预测分布，对嵌入参数 `w、a、b` 做随机梯度下降。忽略「改嵌入会改近邻集合」这一二阶效应，实践中无碍。
3. 学得的参数直接硬编码回 `AimModels` / `DangerModels`（包括反冲浪枪的 MLP 权重矩阵）。
4. 作者指出这一方法与 DrussGT / ScalarR 的遗传算法路线效果相当且结果**可解释**：例如对无墙特征的 Grinnik 学出的模型墙特征权重为 0，对 Thorn 则给墙特征很高权重。

## 5. 瞄准系统（Aimer）

**每 tick 都造波**：首次开火后，每个 tick 都创建一条 AimWave（`hasBullet` 仅在真正开火的 tick 为真）。无子弹的虚拟波同样记录敌人实际被波扫过的精确区间，既提供了远比子弹密集的训练数据，也让 `virtuality` 特征能区分「这波带弹 / 离带弹波多远」。真正用于瞄准的是一条临时波（枪热 ≤ 3 tick 时，用预测的下 tick 状态构建）。

**开火决策**：枪热归零、炮管到位（转角 < 0.001）、且新最优威力没有比已瞄准威力大幅下降（按开火后**剩余能量**之比 > 0.9 判断，低能量时最优威力骤降会暂缓开火）才开火；开火用上一 tick 定下的威力（瞄准在开火前 tick 完成）。

**决策层级**：瞄准没有统一的「虚拟枪擂台」，而是四层各自决策——`Strategy` 决定护盾与否（第 7 节）；`Aimer.pickAimModel()` 按我方命中率置信区间在两把 KNN 枪间硬切换（见 4.4，作者明确不用 Virtual Guns 胜率统计，因为两把枪每 tick 都用虚拟波全量训练，切换零成本）；开火前 1 tick 对约 15 个候选射角做「命中概率 ÷ 阴影后危险」的联合评分取 argmax（见下）；威力由 `BulletPowerSelector` 独立优化（第 6 节）。车身转向、炮管转向、开火是三条独立指令通道，移动与瞄准各管各的，无需相互仲裁。

**与移动的时序配合**：GF 是相对开火位置算的，而 BeepBoop 自身在动——解法是每 tick 先 `mover.move()` 后 `aimer.aim()`（`BeepBoop.java:129-133`），`PathSurfer.surf()` 把计划模拟出的未来状态交给 `GameState.setMyFutureStates(...)`；瞄准临时波以 `gs.getMyState(1)`（预测的下一 tick 位置，即子弹真正的出膛点）为波源，摆炮管也用同一预测状态（`Aimer.java:116`）。`GameState` 每 tick 把预测状态与实际对账，位置/航向/速度偏差 > 1e-4 即作废重建（退化为趋势外推），预测不会带病使用。训练用的 AimWave 则改用开火当 tick 的真实 history，`WaveWithFeatures` 内部把 history 再错一拍，对齐「瞄准发生在波创建前一 tick」。

**选点**：`KNNAimModel` 用扫描线算法求邻居 GF 区间的加权密度峰（区间起点 +weight、终点 −weight，排序后扫一遍取高度最大区间的中点，等价于盒核 KDE 的 argmax）。距开火还早时用一半邻居快速估算（`getAimGFFast`）。

**主动 Bullet Shadowing**（BeepBoop 在顶级 bot 中的独门技术）：开火瞬间不是只打密度峰，而是评估约 15 个候选角度——密度峰角度、按 Gumbel-max 技巧从密度分布采样的角度、以及 `ShadowScorer` 找出的「能产生掩护我方当前移动计划的 bullet shadow」的角度。`ShadowScorer` 对我方计划路径的终点发假想子弹，算它在敌方新波上投下的阴影，取阴影中点 GF 为候选；再逐候选评估「叠加这颗子弹的阴影后，我方当前计划的危险与最小可达危险各降多少」。最终评分 =

```
命中概率得分 ÷ (新阴影下危险)^(2 × (敌我命中率比 × 敌我威力比)^0.25)
```

炮管本 tick 转得到的角度再加 10% 奖励。敌人越准、弹越重，shadow 收益权重越高；对强对手约 40% 的子弹会故意不打「最可能命中」的角度而去制造阴影。

**Bullet shadow 计算**（`Wave.addBulletShadowsForTime`）遵循作者提出的「Correct Bullet Shadowing」：Robocode 按随机顺序逐颗判定子弹碰撞，一颗子弹可能撞上另一颗的「上一 tick 线段」，所以除标准 (t, t) 阴影外，还要补两个 50% 权重的 (t−1, t) 与 (t, t−1) 阴影。从归属上看，bullet shadow 的数据链路属于移动侧：`Wave` 负责计算、`WaveManager` 负责维护（我方开火、敌方新波、子弹相撞时更新）、`DangerEstimator` 以 0.98 系数把它折进冲浪危险；而 `ShadowScorer` 是中间的桥——身在 `kc.mega.move` 包、读取冲浪计划，却由 `Aimer` 持有并调用（`Aimer.java:52`），主动制造阴影的决策属于瞄准侧（这也是 `Aimer` 构造时要传入 `Mover` 引用的原因）。

## 6. 子弹威力选择（BulletPowerSelector）

不用手工规则，而是对 37 档候选威力（2.99 ~ 0.1）逐一估算**回合结束时的期望得分**，选最大者：

1. 把每个威力换算成「每 tick 速率」：伤害率 `bulletDamage ÷ 冷却`、命中返还 `3p ÷ 冷却`、开火耗能 `p ÷ 冷却`；命中率用带 Beta(1, 11) 先验、按 MEA 加权校正的估计，敌人命中率封顶 15%，并假设自己不比敌人差。
2. 假设双方保持当前命中率匀速消耗能量，线性外推回合剩余 tick 与总伤害。
3. **胜率估计**：用 Lagrange 乘子求「距当前平均命中率点最近的平局点」——即我方至少要打多准、同时敌人至多打多准才会输；再用正态近似二项分布 CDF 算出「我方超过该命中率的概率 × 敌人低于该命中率的概率」，取几何平均。
4. 期望得分 = 伤害分 ×（1 + 20% 胜率加成）+ 60 × 胜率；前 5 轮优化得分**比值**（总榜按百分比计分），之后优化**差值**。

涌现行为：落后时倾向高威力搏命（胜率低时赢局分权重上升）、敌人残血时自动降到「恰好击杀威力」（伤害公式反解）、贴脸（< 100px）或反 ram 时火力全开、能量 < 5 且领先时只打「能量差 − 0.11」的威力甚至停火保能量、永远保留 0.05 能量防瘫痪。`tryToDisable` 策略开启时击杀威力再减 0.011——故意留敌人一口气以便撞击拿 bonus。

对 BasicSurfer 类对手改用 7 档特殊威力（含 `nextAfter(2.45, -1)` 这种「刚好小于 2.45」的取值），利用其子弹威力分档 bug 干扰其冲浪（见 8.5）。

## 7. Bullet Shielding（子弹护盾）

对瞄准足够可预测的对手，BeepBoop 干脆不开移动和瞄准，而是站定朝敌方来弹的弹道上开火，让两颗子弹空中相撞，实现零伤害。

- **启用**：编译期 `SHIELD = true`，敌人名字（含版本号）在离线预计算的 `SHIELDABLE` 名单（297 个 bot）中。护盾模式下 Mover/Aimer 完全停摆。
- **关闭**（任一满足）：敌人不在名单；超过 300 tick 敌人一枪未开；敌人累计伤害 > 150（护盾失败止损）。关闭时清空双方命中率统计（护盾期间的数据被人为扭曲），转入正常移动 + 瞄准。
- **介入与衔接**：护盾的替换发生在 `doTurn()` 的分发（`BeepBoop.java:126-137`）——`strategy.shield` 为真时 `mover.move()` 与 `aimer.aim()` 完全不被调用，炮管指向与开火由 `Shielder` 自己发指令；`onHitByBullet` / `onBulletHitBullet` 事件按同一标志路由给 `Shielder`（更新预测器偏差统计）或 `Mover`/`Aimer`。护盾只关不开；关闭后两把 KNN 枪的树是空的（护盾期间没造 AimWave），先以 0.15 小威力开火攒数据（`Aimer.java:78`），命中率统计从清零状态重新积累。
- **敌人瞄准识别**：5 个子弹预测器在线竞争——标准上一帧绝对方位、未调 `setAdjustGunForRobotTurn` 的污染方位、近似 / 精确的自位置外推方位、带轨道方向偏移的方位。每个变体维护「实际弹向相对我的基准的偏差」历史（最多 500 条），按「小偏差、近期、重复出现」打分选最可能偏差，再按 baseScore（5e-5 ~ 1e-5 的先验排序）+ 众数得分选最佳变体。本质上是一个在线的敌人瞄准分类器。
- **开火计划（Plan）**：敌人开火时，对「wiggle ± 0.1px × 若干威力」的组合逐一建模：预测敌弹弹道，精确计算我方子弹能在何处与之相撞形成阴影，枪线指向阴影中点。wiggle 是为了打破两弹完全共线时引擎不判相撞的边界情况。威力候选为 10 档等比序列外加「恰好同 tick 到达同一点」的精确交汇威力；评分偏好：阴影存在且宽、不 wiggle、威力低于按双方能量比例缩放的目标值。
- 无计划时：停车，车身转到与敌连线平行，枪直指敌人待命。

## 8. 对手分类体系

BeepBoop 没有一个集中的「对手类型枚举」，而是分布在 `Strategy`、`Aimer`、`DangerEstimator` 中的一组**在线、不确定性感知**的分类器（除护盾名单外都不需要知道对手名字）。归纳如下：

### 8.1 可被子弹护盾压制的对手（静态名单 + 动态退出）

判据：名字（含版本号）在 297 个 bot 的 `SHIELDABLE` 名单中——这些都是瞄准可预测（HOT / 线性 / 圆形 / 模式匹配）且不会提防护盾的 bot。名单中按名字可辨识风格的代表：`apv.Aspid 1.7`（模式匹配）、`kc.micro.Thorn 1.252`（Kev 自己的 GF 微型枪）、`jam.micro.RaikoMicro 1.44`（经典 GF 枪）。运行中还会按第 7 节的三个条件随时退出。

### 8.2 Rambot（动态识别）

判据：EMA（rate 0.998）跟踪「敌人朝我冲的程度」（靠近速度、70px 内、朝向分量的组合），得分 > 0.7 或距离小于「(靠近速度 + 8) × 15」即进入 `antiRam`。代表 rambot：`gh.micro.Grinnik 1.0`、`dmh.robocode.robot.BlackDeath 9.2`。

对策是全套特化的：路径搜索加入转向选项与模拟 rammer、位置乘子惩罚贴墙贴敌、模拟未来 15 tick 的枪热波、危险评估只信 Circular / Linear / Sedan / NanoLinearFixedSpeed、子弹威力全开、目标航向取 `max(0.7, 1.5 − 距离/400)` 的近垂直逃向角。

### 8.3 镜像 bot（动态识别）

判据：EMA 跟踪「敌人位置与我镜像点（场地中心对称）的距离」，< 90px 判定为镜像。代表镜像 bot：`casey.Flee 1.0`、`mld.Moebius 2.9.3`。

对策：`antiMirror` 给冲浪路径加负危险奖励，诱导自己走到「敌人朝我镜像点开火时，子弹会落空」的位置（镜像 bot 的子弹飞向镜像点）；同时启用宽松的 walking stick 墙平滑。

### 8.4 HOT 枪手（默认假设，动态撤销）

`antiHOT` 默认开启：假设对手打正前方。我方被「非正前方」的子弹命中超过 1 次（前 5 轮为 0 次容忍）才撤销。开启时 HOT 危险模型权重 ×2。典型 HOT 对手：sample 系列（如 `sample.SpinBot`）及大量新手 bot。

### 8.5 BasicSurfer 类（动态识别 + 利用 bug）

判据：第 0 轮，或我方命中率置信区间上界 > 20%（能轻松打中 → 对方是冲浪新手 / BasicSurfer 派生）。对策：换用 7 档特殊子弹威力，利用 BasicSurfer 按威力分档估计子弹速度的 bug 干扰其冲浪。代表：`wiki.BasicSurfer` 及其派生。

### 8.6 冲浪者 vs 普通对手（枪的 KNN 切换）

判据：我方命中率置信区间与 [0, 12%] 重叠 → 对手是冲浪者，用 `AntiSurfer` 枪；否则用 `Main` 枪（见 4.4）。代表冲浪者：`jk.mega.DrussGT`、`voidious.Diamond`、`voidious.mini.Komarious`——移动侧的 KNN 危险模型也直接以它们命名（对它们的枪离线训练）。

### 8.7 按命中率水平分层的危险模型（移动侧门控）

敌人打得越准，说明它的枪越复杂，激活的危险模型随之升级（见 4.4 的门控表）：

- 命中率 < 7%：敌人大概率是简单枪——HOT、Linear、LinearWithWalls、Circular、NanoLinearFixedSpeed、CurrentGF、Simple、Splinter 等低门槛模型主导；
- 3%~6%：WaveShark、MicroAspid、Komarious、Simple2、APMFlattener 加入；
- ≥ 6%~9%：Thorn、Sedan、Druss、Diamond 与四个 Flattener 这些「对强 GF 枪训练」的模型主导。

### 8.8 瘫痪 / 低能量对手（收割策略）

`tryToDisable`：我方能量 > 3、敌人能量 < 95、场上无危险波、敌人枪热长期为负（停火）时，故意把子弹威力降到「留 0.011 能量」；敌人能量 < 0.03 时转入 `ram`——撞死对手拿撞击 bonus（雷达变红并打印 `BEEEEEEEEEEEEEEP!`）。

## 9. 其他值得注意的实现细节

- **雷达**：开局枪与雷达全速转向场地中心；锁定用 infinity lock（扫过目标多转 π/32，每 tick 都有扫描）；丢失扫描（多因 skip turn）则朝最后已知位置扫描，反转 3 次后改单向全场扫描。
- **能量与开火检测**：敌人开火靠能量差检测（0.09999 < 能量差 < 3.0001 且上 tick 枪热 ≈ 0），并补偿撞墙伤害与我方子弹造成的能量扰动；检测到开火即生成真实波，否则用「能量下降即波、子弹确认后升级」的经典做法；另有 gun heat wave——预测敌人枪口下一 tick 冷却完成就提前造一条虚拟波开始冲浪。
- **命中率统计的不确定性接口**：`HitRateTracker.hitRateInBounds(min, max)` 用整个置信区间（Wald 区间，样本 < 50 时 z = 1.282 即双侧 80%，否则 z = 0.842 即双侧 60%）与给定区间求交——所有策略开关（换枪、换墙平滑、模型门控）统一走这个「可能成立即算成立」的接口。
- **性能**：三角函数走 `jk.math.FastTrig` 查表；危险按波缓存、事件驱动失效；绘图仅在 UI 重绘 tick 收集，headless 零开销。
- **已知小瑕疵**（如实记录）：`WaveKNN.isEmpty()` 返回值语义写反（恰好被调用处当 hasData 用而变对）；`updatedShadowWaves` 因返回值问题几乎收录所有处理过的波（仅影响缓存失效频率）；shadow 过滤的一个 `||` 条件恒真属死代码。

## 10. 小结

BeepBoop 的架构可以概括为一句话：**在每个子系统里，用「学习 + 不确定性」取代「手调规则」**。

- 移动从「三选一」的真冲浪推广到路径空间的 A\* 搜索，并用预计算 KD 树把 goto 路径规划变成查表；
- 危险评估是 22 个模型的门控加权集成，简单枪靠模拟器精确躲、强枪靠按对手离线训练的 KNN 躲、模式枪靠在线 PM 仿真躲；
- 瞄准两把枪靠命中率区间硬切换，嵌入参数全部离线 SGD 学得且可解释；
- 子弹威力、主动挡弹、子弹护盾、撞击收割等「非主流程」系统各自闭环，共同把得分效率推到极限。

它对后来者最大的启示也许不是某个具体算法，而是两点方法论：一切可调结构（嵌入函数、集成权重、门控阈值）都离线学习并回读为常量；一切在线决策都通过置信区间感知不确定性，从而无需知道对手是谁。

## 参考资料

- [BeepBoop - RoboWiki](https://robowiki.net/wiki/BeepBoop)
- [Understanding BeepBoop - RoboWiki](https://robowiki.net/wiki/BeepBoop/Understanding_BeepBoop)
- 源码：`kc.mega.BeepBoop_2.0.jar` 内嵌 Java 源文件（RWPCL）
