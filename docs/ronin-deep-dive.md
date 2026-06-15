# Ronin 机器人深度解析

> 本文面向想从 Ronin（`bots/ronin`）学习 1v1 浪冲（wave-surfer）机器人策略与工程的读者。
> 全部代码引用格式为 `文件:行号`，可点击跳转。

## 一、它是什么

Ronin 是 MechGarden 的第二代 1v1 浪冲机器人，由同作者的初代 `zen.Fencer` 演化而来。
核心建模思想是把战场看作一系列**扩散的圆环（wave）**：

- 敌方每次开火产生一个圆环，子弹以固定速度沿圆环前沿向外扩散；
- 我方预测子弹到达自己时的位置，选择**危险最低的躲避方向**；
- 反过来，我方开火也被建模成圆环，记录敌方最终会到达哪个 **GF（Guess Factor）**，从而学习瞄准。

相对于 Fencer，Ronin 的关键升级是 **DC 动态聚类枪**（KNN，带多组预训练 weight profile）和
**多缓冲器危险模型**（DrussGT-lite），另外加入 anti-shield edge aiming、火力与移动 profile selector、
能量分层火力、自适应 engagement range、surfer 检测等战术增强。

---

## 二、分层架构

```
zen.Ronin (薄壳，仅继承)
   └─ zen.ronin.Ronin (抽象主控，装配各层)
        ├─ Radar             雷达锁敌
        ├─ EnemyTracker      扫描 → 一致快照 + 派生量
        ├─ FireDetector      能量反推开火检测
        ├─ EnemyWaveTracker  波浪生命周期
        ├─ BulletShadows     子弹阴影（物理保护）
        ├─ Gun               虚拟枪阵列 + DC/GF 枪
        │  ├─ FirePowerSelector 火力 profile 选择
        │  └─ ShieldAimSelector anti-shield edge aiming
        ├─ Surfer            浪冲决策
        │  └─ MovementProfileSelector 移动 profile 选择
        └─ Motion            纯执行层（无策略）
```

各文件规模与职责：

| 文件 | 行数 | 职责 |
|------|------|------|
| `Ronin.kt` | 200 | 主控：装配各层、扫描分发、跨 round 状态回写 |
| `Gun.kt` | 388 | 枪法总控、虚拟枪选择、火力与 selector 编排 |
| `DcGun.kt` | 416 | 动态聚类 KNN 枪 + weight profile 选择 |
| `FirePowerSelector.kt` | 144 | 每对手、每发子弹的火力 profile 选择 |
| `MovementProfileSelector.kt` | 110 | 每对手、每回合的移动 profile 选择 |
| `ShieldAimSelector.kt` | 114 | anti-shield edge aiming 选择 |
| `GfGun.kt` | 220 | GF 统计枪 + 虚拟枪选择器 |
| `Danger.kt` | 257 | 多缓冲器危险模型 |
| `Wave.kt` | 226 | 波浪、波浪追踪、特征 |
| `Tracker.kt` | 214 | 快照、派生量、能量反推、shield 信号 |
| `Surfer.kt` | 237 | 浪冲决策 |
| `BulletShadows.kt` | 156 | 子弹阴影投射 |
| `Util.kt` | 192 | 几何、运动学、贴墙平滑、distancing |
| `Motion.kt` | 71 | 运动/炮塔执行 |
| `Radar.kt` | 26 | 雷达锁敌 |

**工程纪律的精髓**（AGENTS.md 强调）：每一层只做一件事，不能越权。

- `Radar` 只负责让观测新鲜，**不掺战术决策**；
- `Motion` 是纯执行层：策略决定 *去哪*，它只管 *怎么到*；
- `Gun` 拥有开火门控、火力选择、出膛子弹状态；
- Ronin 保持自包含，战术状态和调参逻辑都留在 `zen.ronin.*` 内部。

这种“分层 + 单一职责”让每个模块都能独立理解和测试，值得直接借鉴。

---

## 三、核心策略逐个拆解

### 3.1 火力反推：看不见的子弹怎么“看见”

Robocode 里**看不到敌方子弹**，但开火会扣能量。`FireDetector`（`Tracker.kt:171`）用一个精巧的**能量账户**：

```
firePower = (prevEnergy − energy) − damageWeDealt + energyGained
```

为什么要减 `damageWeDealt` 加 `energyGained`？

- 我方打中敌人 → 敌人扣血（不是开火），要剔除；
- 敌人打中我 → 敌人获得 `Rules.getBulletHitBonus` 能量回馈，要剔除。

再用**炮热冷却时间**做合法性校验（`Tracker.kt:201`）：`time >= nextFireAllowedTime`，
避免把“撞墙扣血”误判成连发。这比单看能量差精确得多，是 Robocode 里反推敌方状态的经典手法。

### 3.2 波浪系统：一切的基础

`EnemyWave`（`Wave.kt:80`）捕获开火时刻的“事实”：

- `sourceX/Y`、`fireTime`、`velocity`；
- `directAngleDeg` —— 源→我方轴承，即 **GF=0**；
- `orbitDirection` —— 轨道方向，即 **GF 的符号**；
- `features` —— 我方在开火瞬间的特征向量；
- `dangerBins` —— 开火时**烘焙一次**的融合危险直方图。

关键概念 **Guess Factor（GF）**：子弹飞行期间我方能横向移动的最大角度归一化到 `[-1, 1]`：

```
maxEscapeDeg = asin(MAX_V / bulletSpeed)
```

这让所有距离、子弹速度下的命中模式都能对齐到同一个 `[-1, 1]` 坐标。

> 💡 **学习点**：GF 把“绝对角度”转成“相对可逃逸范围的比例”，是 Robocode 高级技巧的通用语言。

### 3.3 危险模型：DrussGT-lite 多缓冲器集成

这是 Ronin 最值得学的机器学习设计（`Danger.kt:115`）。

它不是单一分段直方图，而是 **9 个不同分段的直方图并行学习**，再用**置信度加权融合**：

```kotlin
Buffer(9,  1.0,        1.0) { f -> dist3 * 3 + lat3 }              // 全期 距离×横向
Buffer(5,  1.0,        1.0) { f -> lat5 }                          // 横向细粒度
Buffer(5,  1.0,        1.0) { f -> dist5 }                         // 距离细粒度
Buffer(9,  ROLL_RETAIN,1.0) { f -> lat3 * 3 + accel3 }             // 滚动 横向×加速
Buffer(9,  1.0,        1.0) { f -> lat3 * 3 + wall3 }              // 全期 横向×墙
Buffer(27, 1.0,        1.5) { f -> (dist3*3+lat3)*3 + wall3 }      // 高维 (权重 1.5)
Buffer(27, 1.0,        1.5) { f -> (dist3*3+lat3)*3 + accel3 }     // 高维 (权重 1.5)
Buffer(9,  ROLL_RETAIN,1.0) { f -> dist3 * 3 + lat3 }              // 滚动 距离×横向
Buffer(1,  1.0,        1.0) { _ -> 0 }                             // 全局兜底
```

融合公式（`Danger.kt:184`）：

```
conf = totalWeight / (totalWeight + CONF_SCALE)   // 稀疏段置信度低
out  = coarse.shares + Σ(buffer.weight × conf × shares) / Σ(buffer.weight × conf)
```

**精髓**：高维分段（如 27 段）分辨率高但数据稀疏 → 通过 `conf` 自然 fade-in；低维分段（1 段全局）永远兜底。
这避免了“高维过拟合 / 低维欠分辨”的两难 —— **让数据自己决定该听谁的**。

> 💡 这是 ensemble learning 在游戏 AI 里的教科书级应用。

### 3.4 子弹阴影：物理层面的零危险

`BulletShadows.kt` 是一个**物理保护机制**，思路极妙：

> 我方射出的子弹如果与敌方子弹**轨迹相交**，引擎会让两颗子弹同归于尽。
> 所以“被我方子弹路径覆盖的 GF 带”是**物理上不可能被打中**的，危险读为 0。

它甚至做到了**子 tick 精度**：`BulletShadows.kt:128-149` 解二次方程 `|B(s)|² = r²`
求子弹线段与波环相交的精确参数 `s`。

撤销机制也很严谨（`BulletShadows.kt:68`）：子弹提前死亡（打中敌人/对方子弹）→ 撤销还没飞到的 shadow。
这种“事件驱动 + 可撤销”的状态管理避免了 per-tick 误判。

> 💡 这是用**确定性的物理事实**降低统计不确定性的典型范例，比任何概率模型都可靠。

### 3.5 浪冲决策：比较轨道、停顿和次波的未来代价

`Surfer.surf()`（`Surfer.kt:25`）每 tick：

1. 单遍扫描找出**最近 + 次近**两个未到达的波（无分配）；
2. 对 `dir ∈ {+1, -1}` 各做一次：**预测到达位置 → 读危险 → 加次波 + 墙风险**；
3. 如果当前 movement profile 允许 STOP，再预测刹停路径，并加入 `stopPenalty`；
4. 选代价低的方向或停顿动作驱动；
5. 用 profile 提供的 `inertiaBonus` 和 `tieNoise` 防止被敌方枪读出确定性模式。

`predictArrival`（`Surfer.kt:151`）把轨道向前仿真 30–70 tick，**内联了 `Kinematics.step`**
（`Surfer.kt:169-178`），把调用拆成本地变量，为了循环里零分配。`predictArrivalStop`（`Surfer.kt:189`）
只在 movement profile 允许停顿时参与比较。

> 💡 全速双向仍是默认核心：最大切向速度、没有可被锁定的慢/停瞬间；STOP 是 profile 控制的候选动作，不是常态。

### 3.6 枪法：虚拟枪阵列 + DC 为主

`Gun.kt` 是 Ronin 最复杂的层。每次 fire control 构造 **8 个瞄准模型**：

| 枪 | 说明 |
|----|------|
| Head-On / Linear / Circular | 几何基线 |
| GF_DISTLAT / GF_ROLL / GF_ACCEL / GF_WALL | 4 种分段的 GF 统计枪 |
| **GF_DC** | **动态聚类 KNN 枪**（主火力） |

**虚拟枪选择**（`GfGun.kt:138`）：每把枪都报一个候选角度，但只打一发；
波到达敌方时看**哪把枪本会命中**，按 `RETAIN=0.998` recency-decay 累计 → 选最好的。冷启动默认 Circular。

### 3.7 DC 枪：Ronin 的核心升级

`DcGun.kt` 是 **Dynamic Clustering KNN 枪**：

> 不用固定分段。记住**每一发历史波**（6 维特征向量 + 敌方最终到达的 GF），
> 开火时找 **K=25 个最相似的历史场景**，在它们的 GF 分布上**取峰值**。

特征向量（`DcGun.kt:62`）：距离、横向速度、推进速度、加速度、前方墙比、方向变化时长。
都归一化到 `[0,1]`，再按当前 weight profile 加权距离。Ronin 内置 3 组预训练权重（`DcGun.kt:394`），
并用已解析真实 DC wave 的预测误差给 profile 打分；观测不足 45 条或 profile feedback 不足 12 次时固定使用默认 profile，
切换时要求新 profile 至少领先 0.015（`DcGun.kt:224`）。

三个工程亮点：

**① 三向 quickselect 替代排序**（`DcGun.kt:250`）
- 完整排序是 O(n log n)，但只需要前 K 个，quickselect 是 O(n)；
- 用 **Dutch national flag 三向划分** + **median-of-three pivot**，处理“大量等距”退化情况保持 O(n)
  （单枢轴 Lomuto 会退化到 O(n²)）。

**② buffer 复用避免 GC**（`scoreBuf`/`histBuf`，`DcGun.kt:50`）
- DC 是主火力后每 scan 都跑 KNN；
- 旧代码每 scan 分配成千上万个 `Pair`/boxed `Double`，改用预分配 `Score` 数组复用。

**③ weight profile selector**（`DcGun.kt:209`）
- 每次真实 DC wave 解析时，同时比较所有 profile 的预测 GF 与实际 GF；
- `aim()` 每次查询当前胜出的 profile；profile 本身来自离线训练结果，但 Ronin 不带训练代码，保持自包含。

> 💡 这是“算法改实现”的经典：策略不变，只改数据结构，把 hot path 的分配压到零。

**峰值检测**（`DcGun.kt:335`）：对 K 个邻居做 `1/(d²+KERNEL_SMOOTH)` 核平滑直方图，
再用**半个 bot 宽度的滑动窗口**找质量最大的 GF。因为敌方有 36px 碰撞箱，窗口比单 bin 更稳。

### 3.8 火力选择：期望值 + 能量分层 + profile

三层决策：

**期望值层**（`Gun.kt:301`）：遍历 `[0.1, 0.5, 1.0, 1.5, 2.0, 3.0]`，对每个算：

```
value = (pHit × damage − (1−pHit) × power) / (1 + power/5)
```

其中 `pHit` 用当前命中率、子弹逃逸角、距离因子修正，选 value 最高的。

**能量分层**（`Gun.kt:78-83`）：

```
energyLead > 20  →  AGGRESSIVE_FLOOR = 2.0  (领先收尾)
否则             →  dcPowerFloor = 1.2     (均势/落后经济模式)
```

**profile 层**（`FirePowerSelector.kt:14`）：每个敌人维护
`BALANCED` / `ECONOMY` / `PRESSURE` / `AGGRESSIVE` 4 个 profile。先让每个 profile 探索 8 发真实子弹，
再按 `reward / shot` 选择；reward 来自 hit / miss / hit-bullet。最终 firepower 仍由 `Gun.capPower`
（`Gun.kt:322`）做能量、reserve 和 overkill 约束。

> 💡 利用了 Ronin 的**生存优势**：常常领先，但旧版用弱弹收不掉。这个改动把生存优势转化为胜势。

### 3.9 自适应机制

Ronin 有几处**实时或准实时自适应**：

**① 动态 engagement range**（`Ronin.kt:124`）

```
smoothAdvancing = 0.9×old + 0.1×advancingVelocity
targetRange = BASE(450) + 8 × smoothAdvancing,  钳位 [380, 540]
```

冲过来的敌人 → 拉开；放风筝的 → 贴近。注意注释：**火力 floor 不自适应**，
因为对“弱枪冲锋型”反而退步，这是**实验得出的边界**，值得学习。

**② Surfer 检测 + tick wave 权重**（`Gun.kt:91`）

```
smoothLat > 5.0  →  tickWeight = 0.1  (是 surfer,只对真子弹反应)
否则             →  tickWeight = 0.5  (普通 mover,tick wave 是密集训练数据)
```

“tick wave” 是**没真开火但模拟开火**造的训练样本，对**看不到子弹是否出膛**的非自适应 mover 极其有效；
但 surfer 只对真子弹反应，这时 tick wave 是噪声 → 自动降权。

> 💡 这是“**根据对手类型切换学习信号**”的精妙设计。

**③ Movement profile selector**（`MovementProfileSelector.kt:10`）

每个敌人、每个 round 只运行一个移动 profile。前 2 个 round / profile 轮流探索，round 结束按本回合受到的真实 bullet
damage 计分（`Ronin.kt:167`）。profile 控制是否允许 STOP、第二波权重、墙风险、惯性偏置和 tie noise。

**④ Anti-shield aiming**（`ShieldAimSelector.kt:14`）

`EnemyTracker.shieldLikely`（`Tracker.kt:62`）在敌人长时间静止且近期低火力开火时成立。此时 `Gun` 不直接瞄中心线，
而在左右 bot 边缘间探索（`Gun.kt:117`），真实 edge shot 结果按 hit / miss / hit-bullet 计分，
避免被 bullet shielding 稳定拦截。

**⑤ 每对手参数缓存**（`Ronin.kt:195`）：`[targetRange, dcPowerFloor]` 存在静态 Map 中，在当前 JVM 内跨 round 保留。

---

## 四、工程亮点

| 维度 | 做法 | 价值 |
|------|------|------|
| **性能** | 三向 quickselect、buffer 复用、内联物理、单遍选波 | hot path 零分配 |
| **正确性** | Δt-aware 速率、能量账户隔离、炮热校验、shadow 可撤销 | 避免状态污染 |
| **状态保留** | static registry 跨 round | 当前 battle 内学习累积，不写磁盘 |
| **鲁棒性** | 冷启动安全、按敌名隔离状态 | 永不崩 |
| **自适应** | 能量分层、动态 range、surfer 检测、火力/移动 profile、anti-shield | 因敌制宜 |
| **架构** | 严格分层 + 自包含 package | 可读可测 |

几个具体细节：

- **Δt-aware 速率**（`Tracker.kt:95`）：漏扫时把 turn rate / acceleration 除以真实 tick gap，而不是默认 1。
- **能量账户隔离**（`Tracker.kt:195`）：fire power 的反推减去我方造成的伤害、加回敌人击中我获得的能量。
- **静态 registry**：`companion object` 的 `HashMap` 是类级静态，跨 round 保留（Robocode 每局重建机器人实例）；
  `DcGun`、`DangerModel` 和各 selector 的 `forEnemy` registry 让当前 battle 内的学习持续累积。

---

## 五、几个值得追问的设计取舍

1. **为什么 Ronin 拒绝 flattener？**（`Surfer.kt:13-17` 注释）
   试过用 flatten 统计去相关化我方访问，反而把人推到“少访问但仍危险”的 GF。
   结论：**物理阴影才是真降损**，flattener 对抗不了强自适应枪。这是反直觉但实测得出的洞察。

2. **DC 为什么 ≥45 obs 才接管？**（`Gun.kt:72`，`Gun.kt:353`）
   KNN 在数据太少时方差大，先让虚拟枪选最好的。冷启动用 GF/几何枪兜底。

3. **`WallSmoothing` 为什么用 8° 步进 × 45 步？**（`Util.kt:137-138`）
   贪心旋转直到“前方 stick 点”进安全区。简单且鲁棒，贴墙不撞。

4. **静态 registry 为什么能跨 round？**
   Robocode 每局重建机器人实例，但 `companion object` 的 `HashMap` 是类级静态 → 跨 round 保留 →
   当前 JVM 内的学习不会随 round 重置。跨 battle / JVM 重启不保留这些状态。

5. **为什么 DC 用多组 weight profile，而不是固定一组权重？**（`DcGun.kt:394`）
   不同 catalog 和对手族群的相似度轴权重不同。Ronin 把离线训练好的结果内置进代码，battle 内只做轻量选择：
   观测和反馈不足时使用默认 profile，反馈足够后才允许切到明显更好的 profile。

6. **这些 profile 在什么时机切换？**
   DC weight profile 在真实 DC wave 解析后更新评分，下一次 `aim()` 查询时生效；firepower profile 每发真实子弹结算后更新，
   下一发选择；movement profile 每个 round 开始选择，round 结束按受到的 bullet damage 更新；shield aim profile 只在
   `shieldLikely` 为真时启用，并按真实 edge shot 结果更新。

---

## 六、建议的学习路径

按依赖关系由浅入深：

1. **`Wave.kt` + `Tracker.kt`** —— 理解 GF、波浪、快照、能量反推；
2. **`Danger.kt`** —— 理解多缓冲器集成与置信度融合；
3. **`DcGun.kt`** —— 理解 KNN 特征、三向 quickselect、buffer 复用；
4. **`Surfer.kt` + `MovementProfileSelector.kt` + `BulletShadows.kt`** —— 理解浪冲代价比较、移动 profile 与物理阴影保护；
5. **`Gun.kt` + `FirePowerSelector.kt` + `ShieldAimSelector.kt`** —— 理解虚拟枪、火力 EV、anti-shield 和自适应门控的整体编排；
6. **`Ronin.kt`** —— 理解主控如何把这些层缝合起来。

辅助阅读：
- `docs/robocode-physics.md` —— 引擎物理常量与公式；
- `docs/robocode-scoring.md` —— 评分构成（决定火力与生存的收益结构）；
- `docs/rumble-metrics.md` —— APS / PWIN / survival 指标定义。
