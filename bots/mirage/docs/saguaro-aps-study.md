# Saguaro 弱对手 APS 策略研究

状态：研究完成，暂未实施。

本文分析 `oog.mega.saguaro.Saguaro 1.0` 为什么能在弱对手上获得接近满分的
APS，并据此识别 `zen.Mirage` 后续最值得验证的改进方向。研究对象是本地参考 JAR
及其作者公开的同版本源码；本轮只做分析，不修改 Mirage 的行为。

## 1. 结论

Saguaro 对弱对手的高 APS，主要来自**压低对手得分**，而不是更快造成伤害：

- 对可预测的简单枪，选择专门的 `ShotDodger` 或 `WavePoison` 移动；
- 对适合拦截的枪，主动发射低威力子弹与敌弹相撞；
- 用 `ScoreMax` 联合选择移动、火力、弹影和不开火，以预计得分占比而不是单发收益
  作为目标；
- 随 JAR 发布大量对手档案，直接为已知对手锁定经过离线验证的模式。

Mirage 当前在弱对手上的主要差距是 `taken/r`，不是 `dealt/r`。继续单纯收近距离或
提高火力，不能解决这个差距。优先方向应是识别并精确躲避简单枪；主动弹盾的潜在
收益更大，但实现成本和行为风险也明显更高。

## 2. 研究材料与方法

本地研究对象：

- `.cache/refs/oog.mega.saguaro.Saguaro_1.0.jar`
- 公开源码提交 `ea8745128a065c6f79d221e7ebae50944fcb188c`
- 本地 JAR 与公开仓库 `release/` 中同版本 JAR 的 SHA-256 完全一致

分析包含三部分：

1. 阅读模式选择、移动、弹盾、火力和得分评估源码；
2. 解码 JAR 内 1211 份对手档案的锁定模式及累计得分占比；
3. 让 Saguaro 和 Mirage 分别对战 4 个弱对手，观察 APS、survival、`dealt/r`、
   `taken/r` 和 Saguaro 的模式日志。

本地 duel 使用同一 seed `20260711`，每组 35 回合。该样本足以确认行为机制，但不应
当作发布基准；正式 A/B 仍需遵守 [调参与诊断系统](tuning.md) 中的多次重复要求。

## 3. 本地对战观察

### 3.1 Saguaro 与 Mirage 的得分差距

| 对手 | Saguaro 锁定模式 | Saguaro APS | Saguaro survival | Saguaro dealt/r | Saguaro taken/r | Mirage APS | Mirage survival | Mirage dealt/r | Mirage taken/r |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `kawigi.sbf.FloodMini 1.4` | `BulletShielding` | 98.54 | 100.00 | 7.86 | 1.03 | 75.40 | 94.29 | 35.63 | 27.86 |
| `wiki.BasicGFSurfer 1.02` | `WavePoisonShift` | 96.65 | 100.00 | 60.71 | 4.57 | 80.49 | 97.14 | 52.80 | 27.43 |
| `wiki.nano.RaikoNano 1.1` | `MovingBulletShielding` | 89.32 | 100.00 | 12.31 | 8.94 | 77.98 | 97.14 | 36.83 | 26.80 |
| `zen.Lindada 0.2` | `BulletShielding` | 97.96 | 100.00 | 10.66 | 1.51 | 71.71 | 88.57 | 38.60 | 30.29 |

FloodMini 和 Lindada 最能说明问题：Mirage 每回合造成的伤害约为 Saguaro 的
3～4 倍，但 APS 分别低 23.14 和 26.25。Saguaro 通过弹盾把 `taken/r` 压到约
1 点，并保持 100% survival；造成伤害较少并不妨碍它获得接近满分的得分占比。

因此，Mirage 对弱对手的首要优化指标应是：

1. `taken/r`；
2. survival；
3. APS；
4. 在前三项不退步的前提下，再观察 `dealt/r` 和击杀时长。

### 3.2 Saguaro 对 Mirage 的冷启动弱点

发布 JAR 中没有 Mirage 的对手档案。35 回合对战中，Saguaro 依次尝试了
`BulletShielding`、`MovingBulletShielding`、`ScoreMax`、`PerfectPrediction`、
`ShotDodger` 等模式，最终只有 47.01 APS，survival 为 37.14%。

这说明 Mirage 容易击败它，部分原因不是 Saguaro 的通用能力弱，而是 Mirage
属于未知对手，迫使它在有限回合内支付多模式探索成本。Saguaro 在弱对手上的极高
APS 和它面对未知对手时的表现，来自同一设计取舍：用大量离线档案换取已知对手上的
确定策略。

## 4. 对手档案与模式分布

Saguaro 自述为“8 bots in a trench coat”。发布 JAR 中共有 1211 份对手档案，
全部已经锁定模式。档案保存锁定模式、累计双方原始得分和量化后的得分占比；模式
选择器在证据充分后淘汰劣势模式，并把最终模式写回对手档案。

| 模式 | 档案数 | 档案得分占比中位数 | 主要用途 |
|---|---:|---:|---|
| `ShotDodger` | 431 | 99.08% | 识别敌枪专家，避开预测弹道。 |
| `ScoreMax` | 255 | 82.74% | 通用的移动、火力和得分联合优化。 |
| `BulletShielding` | 181 | 96.43% | 静止或微动，主动拦截敌弹。 |
| `MovingBulletShielding` | 122 | 96.77% | 先调整位置，再持续主动弹盾。 |
| `WavePoison` | 45 | 96.25% | 利用开火节奏误导简单 GF 枪。 |
| `WavePoisonShift` | 104 | 95.76% | 在敌方开火前额外进行微小反向。 |
| `PerfectPrediction` | 40 | 83.06% | 针对运动可精确预测的对手。 |
| `AntiBasicSurfer` | 33 | 91.37% | 使用离散火力破坏基础 surfer 的学习。 |

`ShotDodger`、两种弹盾和两种 `WavePoison` 合计 883 份，占全部档案的 72.9%。这比
`ScoreMax` 的 255 份更能解释 Saguaro 的弱对手优势：通用规划器是底座，真正接近
满分的配对多数依赖专门的得分压制策略。

## 5. 关键策略

### 5.1 `ShotDodger`：选出最准的敌枪专家

`ShotDodger` 不把所有简单枪预测混成一份 danger prior，而是分别维护和评估多个
专家：

- Head-on；
- Linear；
- Averaged Linear；
- Linear Constant Divisor；
- Circular；
- 不补偿敌方开火时 body turn 的对应变体；
- Battlefield Center；
- Droid Impact Heading。

每条敌浪通过或命中后，观察器按实际 GF 区间为各专家评分，并选择当前表现最好的
专家。移动规划时，如果该专家预测的 GF 落入候选路径的机器人包围区间，路径会得到
无限危险；否则危险按预测弹道与包围区间之间的角距离平方反比增长。

这种做法有两个特点：

- 对确定性的弱枪，一旦识别成功，移动目标是明确避开一条弹道，而不是在多个混合
  danger 峰值之间折中；
- 专家尚未确认时先使用默认 Head-on；是否继续采用 `ShotDodger`，由外层模式选择器
  根据累计得分证据决定。

Mirage 已经有 Head-on、Linear 和 Wall-linear 的模拟瞄准先验，但当前把它们融合为
一份固定分布，没有单独记录每个模型对该对手的预测准确度。这是最容易借用 Saguaro
思路的缺口。

### 5.2 `WavePoison`：利用 gun heat 控制开火时状态

`WavePoison` 不是普通的随机反向。它以敌方 gun heat 为时钟，在约 250 px 距离做
受控环绕：

1. 敌枪冷却期间移动；
2. 在敌枪即将就绪时制动、停止或慢爬；
3. `WavePoisonShift` 在预计开火前 2 tick 做一次很小的反向；
4. 检测到敌方开火后立即重新加速；
5. 在下一次开火前再次停止。

敌方简单 GF gun 会根据开火时看到的低速或反向状态瞄准，子弹离膛后 Saguaro 才
完成主要位移。BasicGFSurfer 的发布档案正是锁定 `WavePoisonShift`，累计得分占比
为 96.67%。

### 5.3 主动弹盾：用 gun heat 换取对手零伤害

`BulletShielding` 以即将到达的真实敌浪为优先任务：

1. 从多种瞄准模型和已学习偏移预测敌弹航向；
2. 搜索可与敌弹精确相撞的己方子弹威力和发射角；
3. 优先选择低于敌弹威力、碰撞阴影较宽、碰撞较早的方案；
4. 必要时在开火前移动很小距离，改善碰撞几何，再撤回该位移；
5. 只有确认不会耽误下一次拦截时，才允许普通攻击或补刀。

Mirage 当前的 `BulletShadows` 是**被动弹影**：正常进攻子弹恰好穿过敌浪时，浪冲
可以利用阴影。`ShieldDetector` 和 edge aim 是**反制敌方弹盾**。它们都不等于
Saguaro 的主动弹盾：Mirage 目前不会专门为拦截敌弹而规划己方子弹。

这解释了 FloodMini、RaikoNano 和 Lindada 上的差距，也说明主动弹盾会同时侵入枪热
管理、火力选择、移动和浪冲，不能当作一个局部 aim trick 添加。

### 5.4 `ScoreMax`：直接优化预计得分占比

`ScoreMaxPlanner` 对每条候选移动路径比较三类射击选择：

- 不开火；
- 只制造有利弹影；
- 普通攻击，并搜索 0.1 到当前上限之间的火力。

`BranchedPlanScorer` 对近期射击事件的命中和未命中建立分支，累计双方子弹伤害、
撞击伤害、能量消耗、命中能量返还、击杀奖励、50 点生存分和 10 点最后存活奖励，
再根据双方学习到的命中率、常用火力和剩余能量估计后续战斗。最终比较的是计划执行
前后预计得分占比的变化。

因此，不开火是一等候选。如果开火会自耗能量、降低 survival、占用弹盾 gun heat，
或只制造无法转化为得分的过杀，规划器可以选择保留能量。它也会把火力限制到敌方
剩余能量需要的范围内。

Mirage 当前的火力 EV 主要评估单发净能量收益，现有调参也已经证明：盲目提高火力会
降低 survival。Saguaro 的启示不是再增加一个火力 profile，而是把“不开火”和
“减少对手未来得分”纳入同一个 APS 效用函数。

## 6. Mirage 的能力差距

| 能力 | Mirage 当前状态 | 与 Saguaro 的差距 |
|---|---|---|
| 经验型 wave surfing | 已有多缓冲 danger、路径危险和跨回合内存学习。 | 通用能力完整，不是本轮主要缺口。 |
| 简单枪先验 | 已融合 Head-on、Linear、Wall-linear。 | 没有按对手选出最准专家，也没有对预测弹道做硬避让。 |
| gun heat 利用 | 已检测开火并创建敌浪。 | 没有围绕敌枪就绪时刻安排停、走和微反向。 |
| 被动 bullet shadow | 已跟踪正常进攻子弹形成的阴影。 | 没有主动规划 bullet-bullet collision。 |
| 反弹盾 | 已有 `ShieldDetector`、hold fire 和 edge aim。 | 这是反制敌人弹盾，不是自己使用弹盾。 |
| 火力 EV | 已比较火力 profile 和单发净收益。 | 没有直接建模双方最终得分占比，也没有常规 `no fire` 候选。 |
| 对手适应 | 静态 registry 在当前 battle/JVM 内跨回合保留。 | 不写磁盘档案，无法像 Saguaro 一样随包锁定 1211 个对手。 |

## 7. 后续优化建议

### 7.1 第一优先级：`ShotDodger-lite`

先在 Mirage 现有 simulated targeting 和 surfer 上增加一个窄层，而不是移植完整
模式系统：

1. 分别保留 Head-on、Linear、Wall-linear 等专家的预测 GF；
2. 在敌浪通过或命中时，按机器人实际包围区间给各专家记分；
3. 要求足够的已解析敌浪和明确的领先幅度，才启用最佳专家；
4. 启用后，对与最佳专家预测区间相交的候选路径增加强危险；
5. 置信度不足或专家失准时，立即回退到当前通用 danger；
6. 使用独立 A/B override，避免与正式默认耦合。

这一阶段复用 Mirage 已有的预测、敌浪、路径模拟和跨回合内存状态，改动边界最小，
对应的又是 Saguaro 最大的 431 份锁定档案。

### 7.2 第二优先级：开火时机移动

在专家确认敌枪高度可预测后，再增加 gun-heat-timed 候选路径：

- 开火前制动或短暂微反向；
- 检测到开火后加速；
- 只对简单枪启用；
- 一旦单回合受伤超过阈值，按现有 latch 模式回退。

该阶段应先验证 BasicGFSurfer，避免把 250 px 和固定 2 tick 等 Saguaro 参数直接
复制到 Mirage。

### 7.3 第三优先级：主动弹盾原型

如果前两阶段仍不能显著降低 FloodMini、RaikoNano 和 Lindada 的 `taken/r`，再做
主动弹盾原型：

- 只处理航向预测稳定的最近敌浪；
- 从最低合法火力开始搜索碰撞方案；
- 把 gun heat 预留给下一次拦截；
- 没有可行拦截时继续使用现有 surfer；
- 普通攻击必须证明不会降低拦截覆盖率。

主动弹盾的验收不能只看 bullet-bullet collision 数量。最终指标仍是 APS、survival
和 `taken/r`；如果拦截成功却因自耗能量或延长战斗而降低得分，应判为失败。

### 7.4 第四优先级：轻量 APS 效用函数

不必立即复制 `BranchedPlanScorer`。可以先让枪在三个候选之间比较：

- 不开火；
- 当前普通攻击；
- 主动弹盾或高价值弹影。

效用至少包含预计命中伤害、开火能量成本、敌方预计伤害、双方剩余能量、生存和最后
存活奖励，以及击杀奖励。只有轻量模型能在弱敌目录上稳定提升，才值得扩展为完整的
移动和射击联合规划。

## 8. 验证方案

第一轮目标对手：

- `kawigi.sbf.FloodMini 1.4`
- `wiki.BasicGFSurfer 1.02`
- `wiki.nano.RaikoNano 1.1`
- `zen.Lindada 0.2`

每个阶段使用相同协议做 baseline 和 candidate A/B，至少 3 次 × 100 回合，并保留
每次 APS、survival、`dealt/r`、`taken/r`、敌方命中率和触发次数。验收重点：

1. 弱对手等权平均 APS 多次重复方向一致；
2. `taken/r` 有明确下降，而不是依赖单次 survival 波动；
3. 改进确实在目标对手上触发；
4. classic、expert 和 Ronin 防回归对照没有一致性负面变化；
5. 结果超过 `tuning.md` 记录的单样本噪声后，才考虑默认开启。

## 9. 不建议直接复制的设计

- **随包发布对手档案。** Mirage 的规则要求自适应状态只保留在当前 battle/JVM
  内，不写 Robocode 数据文件；Saguaro 的冷启动表现也说明离线档案会把代价转移给
  未知对手。
- **完整的 8 模式探索器。** Mirage 的本地实验已经多次观察到探索扰动和样本噪声。
  应优先根据敌枪可预测性进行窄分类，而不是逐轮试遍所有策略。
- **通用撞击收尾。** Mirage 先前的 ram 实验没有稳定收益。只有主动弹盾使敌人失能、
  且无存活敌浪时，才值得重新单独评估。
- **直接复制参数。** Saguaro 的 250 px、开火前 2 tick、弹盾火力采样等参数依赖它
  自己的移动和枪热规划，不能视为 Mirage 的默认值。

## 参考

- [Saguaro README](https://github.com/oogilbert/Saguaro/blob/ea8745128a065c6f79d221e7ebae50944fcb188c/README.md)
- [ModeController](https://github.com/oogilbert/Saguaro/blob/ea8745128a065c6f79d221e7ebae50944fcb188c/mode/ModeController.java)
- [ModeSelector](https://github.com/oogilbert/Saguaro/blob/ea8745128a065c6f79d221e7ebae50944fcb188c/mode/ModeSelector.java)
- [ModePerformanceProfile](https://github.com/oogilbert/Saguaro/blob/ea8745128a065c6f79d221e7ebae50944fcb188c/mode/ModePerformanceProfile.java)
- [ShotDodgerObservationProfile](https://github.com/oogilbert/Saguaro/blob/ea8745128a065c6f79d221e7ebae50944fcb188c/mode/shotdodger/ShotDodgerObservationProfile.java)
- [ShotDodgerPlanner](https://github.com/oogilbert/Saguaro/blob/ea8745128a065c6f79d221e7ebae50944fcb188c/mode/shotdodger/ShotDodgerPlanner.java)
- [WavePoisonPlanner](https://github.com/oogilbert/Saguaro/blob/ea8745128a065c6f79d221e7ebae50944fcb188c/mode/shotdodger/WavePoisonPlanner.java)
- [BulletShieldMode](https://github.com/oogilbert/Saguaro/blob/ea8745128a065c6f79d221e7ebae50944fcb188c/mode/shield/BulletShieldMode.java)
- [ScoreMaxPlanner](https://github.com/oogilbert/Saguaro/blob/ea8745128a065c6f79d221e7ebae50944fcb188c/mode/scoremax/ScoreMaxPlanner.java)
- [BranchedPlanScorer](https://github.com/oogilbert/Saguaro/blob/ea8745128a065c6f79d221e7ebae50944fcb188c/mode/scoremax/BranchedPlanScorer.java)
- [RoboRumble / LiteRumble 评分指标](../../../docs/rumble-metrics.md)
