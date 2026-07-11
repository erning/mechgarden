# Mirage 调参与诊断系统

本文记录 `zen.Mirage` 的 per-round 诊断和 A/B 调参 override，以及定下当前默认值
的实测结论。目的是让后续调参工作能快速复现、对比，并避免重复已验证为「无用」的
方向。所有 override 都通过 JVM 系统属性传入，缺省即正常对战行为；关闭诊断时不做
字符串格式化。

环境说明：`Ronin` 内部自建 `java.util.Random()`（构造时按当前时间播种），`Mirage`
走 Robocode 受控的 `Math.random()`。因此即便带 `--seed`，含 Ronin 的对战仍逐次
随机、不可复现。`APS` 单样本标准差约 ±3–5（50 回合），所以只有均值差 >~5、或多次
重复一致时才算可靠信号。

## 1. 诊断输出：`mirage.debug`

设 `-Dmirage.debug=true` 后，每回合结束时（`onRoundEnded`）向机器人控制台打一行：

```
MDBG r=12 dealt=35.2 taken=54.0 fired=42 hit=4 avgPower=1.21 ticks=913 aim=GF_DC mea=theory dc=482 dcAcc=0.18 fp=ECONOMY ohr=0.154@476 [HO=0.17,...,DC=0.19,AS=0.19] stopGo=likely/3 episodes=24 dwell=3.50 intervals=23 mean=30.17 cv=0.58 prof=PURE_SURF range=465 ehr=0.094@482
```

| 字段 | 含义 |
|------|------|
| `dealt`/`taken` | 本回合造成/受到的子弹伤害。 |
| `fired`/`hit` | 本回合实弹发射数 / 命中数（真实命中率 ≈ hit/fired）。 |
| `avgPower` | 本回合实弹平均威力。 |
| `ticks` | 本回合持续 tick 数，用于直接观察击杀时长。 |
| `ram`/`ramTaken` | 碰撞次数、我方责任碰撞次数，以及碰撞伤害。 |
| `minDist`/`close`/`wall`/`closing` | 最小距离、100/150 px 内停留 tick、最小墙距和最大接近速度。 |
| `enemyPower` | 本回合检测到的敌方平均开火威力与开火次数。 |
| `ramThreat` | 反撞击识别状态、证据 tick 和剩余 latch tick。 |
| `ashield`/`shieldPolicy` | 主动弹盾规划、发射、拦截、未拦截计数，以及跨回合 A/B 选择状态。 |
| `aim` | 本回合主用的瞄准模型（`GF_DC` 等，见 `VirtualGuns.Aim`）。 |
| `mea` | 枪侧当前使用的 GF 归一化尺度：`precise` 或 `theory`。 |
| `dc` | DC（KNN）枪已解析的观测数；≥ `DC_PRIMARY_MIN`（45）后 DC 成为主枪。 |
| `dcAcc` | DC 当前权重 profile 的滚动预测精度（0..1，`1/(1+(\|预测-实际\|/宽度)²)`）。 |
| `fp` | 本回合选中的火力 profile。 |
| `ohr` | 我方对敌人的跨回合实弹命中率 `ohr=<rate>@<resolved shots>`；BulletHitBullet 计为未命中。 |
| `[...]` | 每个 virtual-gun 的近期虚拟命中率：`HO/LIN/CIR`、`GFD/GFR/GFA/GFW`、`DC/AS`。 |
| `stopGo` | 周期停走分类状态，以及最近 5 回合中合格证据的数量。 |
| `episodes`/`dwell` | 本回合完整停车次数与平均低速持续 tick。 |
| `intervals`/`mean`/`cv` | 停车起点间隔数、平均周期与变异系数。 |
| `prof`/`range` | 本回合移动 profile 与接战距离。 |
| `ehr` | 敌方对我们的累计命中率 `ehr=<rate>@<waves>`（`ThreatStats`：命中波数 / (躲过+命中)），NaN 显示 `n/a`。跨回合累积；≥60 波后用于威胁分档（见第 5 节 Phase 2）。 |

用 `--show-output` 跑可看到这些行：

```
JDK_JAVA_OPTIONS="-Dmirage.debug=true" python3 scripts/duel.py -r mirage -e ronin -n 20 --show-output
```

不设该属性时不会格式化或打印日志；跨回合枪效统计仍以常数级计数更新。

## 2. A/B override 一览

每个 override 都用 JVM 系统属性 `-D…=…` 传入；缺省值即当前正式默认。

| 属性 | 取值 | 作用 | 默认 |
|------|------|------|------|
| `mirage.aim` | `dc` \| `best` | `dc`=DC 学够后强制为主枪；`best`=按虚拟命中率在含 DC 的全部模型里选。 | `dc` |
| `mirage.powerfloor` | `0.1`–`3.0` | DC 枪火力地板（覆盖 `DC_POWER_FLOOR_BASE`）。 | `1.2` |
| `mirage.dck` | `1`–`200` | DC 枪 KNN 邻居数 k。 | `25` |
| `mirage.profile` | `auto` \| `PURE_SURF` \| `NOISY_ORBIT` \| `WALL_SAFE` \| `STOP_SURF` \| `SURVIVAL_SEARCH` | 移动 profile：`auto`=按受血量在 `PURE_SURF`/`NOISY_ORBIT` 间 explore/exploit；指定名=整场强制该 profile；缺省=稳定的 `PURE_SURF`。 | `PURE_SURF` |
| `mirage.power` | `auto` \| `balanced` \| `economy` \| `pressure` \| `aggressive` | 火力 profile：`auto`=按每发净能量 explore/exploit；指定名=强制；缺省使用 survival policy 的稳定配置。 | `ECONOMY` |
| `mirage.mea` | `theory` \| `precise` | 逃逸角 override：`theory` 强制枪使用理论 MEA；`precise` 强制枪使用精确 MEA，并让浪冲也使用精确 MEA。优先级低于 `mirage.stopgomea=force/off`，高于自动检测。 | 自动枪 / 理论浪冲 |
| `mirage.stopgomea` | `auto` \| `force` \| `off` | 枪侧周期停走 MEA 策略：`auto` 对已确认的远距离周期停走目标切换到理论 MEA 的独立 DC 模型；`force` 全程强制 theory；`off` 全程强制 precise。 | `auto` |
| `mirage.stickyorbit` | `on` \| `off` | 停车时沿用 Tracker 保存的最近非零轨道方向，避免 GF 符号翻转；`off` 复现速度为零时固定 `+1` 的旧行为。 | `on` |
| `mirage.dchalflife` | `0`–∞ | DC（KNN）枪的近期加权半衰期（单位：观测数）。每个邻居在密度估计中的权重按 `0.5^(age/halflife)` 衰减，age 为其后又积累的观测数。`0`=均匀（无近期加权，旧行为）。见第 3.6 节。 | `400` |
| `mirage.dcclear` | `on` \| `off` | 是否在新回合清除 DC 枪未解析的 pending 波；`off` 复现跨回合污染旧行为。已解析观测始终保留。 | `on` |
| `mirage.asgun` | `on` \| `off` \| `force` | hit-aware anti-surfer DC：缺省或 `on` 按置信度门槛自动切换；`off` 禁用并行训练；`force` 在 DC 就绪后强制使用，仅供 A/B。 | `on`（门控） |
| `mirage.antiram` | `on` \| `off` | 是否按接近速度、航向、横向速度和碰撞证据识别追撞者，并在 latch 生效时切换近距离高火力。 | `on` |
| `mirage.ramescape` | `on` \| `off` | 只控制 Phase 8 的 anti-ram 逃逸移动；`off` 保留近距离火力响应。 | `on` |
| `mirage.activeshield` | `force` \| `off` | `force`=每回合强制主动弹盾；`off`=禁用。缺省由跨回合 A/B 选择器决定。 | `adaptive` |
| `mirage.shotdodger` | `on` \| `off` \| `force` | 控制简单枪专家选择；`force` 跳过置信度门槛，仅供诊断。 | `on` |
| `mirage.shotweight` | `0`–∞ | 最佳简单枪专家叠加到 surf danger 的归一化峰值权重。 | `0.55` |

例如测一个固定移动 profile：

```
JDK_JAVA_OPTIONS="-Dmirage.profile=NOISY_ORBIT" python3 scripts/duel.py -r mirage -e ronin -n 100
```

## 3. 已验证的结论（为何默认是这些值）

下列每个结论都用「同一协议、多次重复、50–100 回合」测过，差异若落在 ±3–5 的噪声
带内即判为无效。

### 3.1 主枪必须强制 DC，不能信虚拟命中率

`mirage.aim=best`（按虚拟命中率选，含 DC）相对 `dc`（强制 DC）**全面更差**（50r×3）：

| 对手 | aim=dc | aim=best |
|------|--------|----------|
| Ronin | 53.4 | 45.5 |
| SandboxDT | 51.5 | 43.6 |

DC 的虚拟命中率虽常低于分段 GF 枪，但其 KNN 峰值定位的真实命中更高。故默认 `dc`。

### 3.2 移动/火力的逐轮 explore/exploit 不帮忙，反而轻微扰动同源战

把移动 profile 选择器（`mirage.profile=auto`，逐轮 PURE_SURF↔NOISY_ORBIT 探索）和
火力选择器（`mirage.power=auto`，逐发 BALANCED↔ECONOMY 探索）打开后：

- 火力选择器对预测得准的对手**始终选回 BALANCED**（对这类对手，命中率几乎与功率
  无关，高功率单发伤害更高 → 净能量更高；低功率快子弹并未净赚）。
- 移动选择器对同源 surfer 对战**轻微变差**（选择器开 ~47.5 vs 关 50.4）：兄弟枪会
  学习我方访问模式，**稳定**的访问分布比不断切换的更难被锁定。
- 第三方目录（6 对手）上，关闭选择器的稳定配置 APS **64.0**，高于打开的 62.0，也高
  于 Ronin 的 59.9。

故两选择器默认关闭（稳定默认），探索逻辑保留在 `auto` 模式下供将来按对手复测。

### 3.3 火力地板、DC 的 k 都在噪声带内，不构成可靠改进

- `mirage.powerfloor` 0.1 / 0.5 / 1.2：个别对手表面看 0.5 略好（~51），但 5×50 重复
  后回落到 48.7±3.5，与默认重叠——是噪声；另一些对手反而偏好 1.2（低功率明显变差）。
  无单一地板对全部对手最优，故保持 `1.2`。
- `mirage.dck` 25 vs 40：5×50 重复后 Ronin 51.5→49.1，k=40 不更好（早期单样本的
  +5 是幸运样本）。保持 `25`。

### 3.4 同源对手直接对战是 ~50% 硬币战（在精确 MEA 之前）

`Mirage` 与 `Ronin` 同源、实力相当。纯参数微调（火力地板、DC 的 k、移动/火力
选择器）无法把这个胶着战稳定推向 >55%——差异都落在 ±3–5 的噪声带内。真正拉开
差距需要新的**能力**，下一节就是一个。

### 3.5 精确 MEA：枪侧是净胜，浪冲侧要整套重调

教科书逃逸角 `asin(MAX_V / bulletSpeed)` 假设目标静止且能瞬间切向全速逃逸，忽略
墙、当前速度、转向限制。精确 MEA（`PreciseMea`）从敌方开火位置出发，逐 tick 模拟
目标按最快墙平滑逃逸，取其能达到的最大方位偏角。实测中比理论值小约 5–15%
（如 SandboxDT 的 1.86 功率子弹：理论 0.588，精确约 0.52）。

关键发现是**枪侧与浪冲侧要分开看**（同一 50r×3 协议，A/B 对照）：

- **枪侧用精确 MEA：净胜。** DC 的预测精度从 ~0.16 升到 ~0.19，实弹命中率从 ~8%
  升到 ~14%；打同源 surfer 直接拉开胜率：Ronin 50.7→57.6（DEALT 全面上升）。
  第三方 6 对手目录从 ~64% 升到 ~66%。
- **浪冲侧用精确 MEA：净负。** 它重塑了危险模型的 GF 几何（更窄的逃逸区间 → 更宽
  的 hull 窗口 → 过度规避），而该模型是在理论 MEA 下调参的。单独换会
  让 SandboxDT 从 54.2 降到 51.1（100r×4）。要让它付头，得连带 bin 数、kernel 宽度、
  shadow 一起重调——独立换不划算。

故默认：**普通目标的枪使用精确 MEA，浪冲使用理论 MEA**。Phase 10 只对已确认的
远距离周期停走目标，把枪切换到理论 MEA 的独立 DC 模型。`mirage.mea=theory` 两侧
都回理论值，`mirage.mea=precise` 让浪冲也改用精确值（留作将来整套重调的起点）。

### 3.6 DC 枪近期加权（recency KNN）：对自适应对手净胜（默认 `dchalflife=400`）

学 BeepBoop 的时间衰减 KNN：DC 枪原本只按特征距离给邻居加权、所有历史观测等权。
改为在密度估计里给每个邻居乘一个近期权重 `0.5^(age/halflife)`（age = 该观测之后又
积累的观测数），让**近期**观测主导——更贴合自适应对手「当前」的移动习惯，早期冷启动
的噪声样本随之淡出。邻居选择仍按特征距离（保持尖锐），近期权重只作用于最终密度。

12 样本 A/B（`dchalflife=0` 旧行为 vs `400`）：

| 对手 | 旧 APS/SURV/DEALT | 新 APS/SURV/DEALT |
|------|-------------------|-------------------|
| Firestarter | 35.0 / 26.0 / 32.2 | 39.4 / 30.8 / 36.0 |
| Phoenix | 37.1 / 44.3 / 25.3 | 39.8 / 48.1 / 27.1 |
| Gilgalad | 37.0 / 38.0 / 29.0 | 38.8 / 39.3 / 31.2 |
| SandboxDT | 51.8 / 61.9 / 38.0 | 51.1 / 61.9 / 36.5 |

DEALT 处处 +1.8~3.8（最可靠的枪效信号，受回合胜负噪声影响小），三个强自适应对手的
APS/SURV 一致上升，SandboxDT 持平。半衰期扫描：`400` 优于 `250`/`800`/`1600`；
近期加权下 `k` 仍以 `25` 最优（`50`/`100` 更差——尖锐 KNN + 近期加权是正确组合）。

### 3.7 自适应 flattener（按对手识别开关）：实证排除

试过「双 buffer」特征级识别：命中模型 vs flattener 模型，按每次被命中时「谁的百分位
更高地解释了实际命中 GF」在线调 flattener 权重（name-free、可泛化）。结论负面：

- flatten 本身对所有测试对手都非正收益（Phoenix 12 样本 base 42.85 vs flat=0.6
  44.52，差异在噪声内；Firestarter −8.4、Gilgalad −4.3、SandboxDT 10 样本 −3.5）。
  与既有结论（flatten 普遍负面）一致。
- 百分位信号无法区分「吃 flatten」与「不吃」：干净 anticipate_max 下 Phoenix 0.556、
  Gilgalad 0.562、SandboxDT 0.566 全挤在一起，只有 Firestarter（0.519）偏低。
  「flattener 能解释命中」是大多数枪的共性，与 flatten 是否有效无关。

故不引入该机制。（注：本节说的是**枪侧** flattener——按对手调「预测敌方 GF」的
flatten 权重，结论负面。下面 3.8 节验证的是另一个机制——**移动侧** flattener，即让我们
少去高频 GF，结论相反，是净胜的。）

### 3.8 移动侧 flattener（受弹触发的自适应开关）：对强自适应枪净胜

与 3.7 的枪侧 flattener 不同：移动侧 flattener（`Surfer` 里的 `VisitFlattener`）给
「我方频繁访问的 GF」加危险，把我们推离这些 GF——从而压平访问分布、干扰学习型枪的
锁定。`mirage.flatten` 是该权重的全局 override。

诊断显示 SandboxDT 命中我方在 GF 0 附近有 ~1.6× 峰（均与 9.1% 对比中央 bin
14.7%），说明我方移动有中心趋向，可被学习型枪利用。强行 `mirage.flatten=0.35`（全局
常开）的 200回合×3 A/B：

| 对手 | 旧 APS/SURV/TAKEN | flat=0.35 APS/SURV/TAKEN |
|------|-------------------|-------------------------|
| SandboxDT | 51.5 / 61.5 / 48.6 | 53.2 / 65.5 / 46.4 |
| Firestarter | 38.0 / 30.0 / — | 42.3 / 35.5 / — |

但 flattener 对弱枪净负（FloodMini −3）：弱枪下我方本来就有安全的 GF，flatten 会把我们
从安全 GF 推到仍危险的 GF（这与既有结论「flatten 普遍负面」一致）。因此不能设为全局
默认。

关键设计是**按近期受弹触发**：flattener 只在「敌枪持续重创我方」时开启。机制：
`MovementProfileSelector` 维护一个全局受弹 EMA 与带滞后的开关（arm 3 回合后，EMA
≥40 开启、≤30 关闭）。强枪（SandboxDT ~48、Firestarter 高）锁定为开；弱枪（FloodMini
~20、RaikoNano 低）保持关。这正好对应 flattener 有益的场景——被重创时没有真正安全的 GF
可损失，重新分布访问只会扰乱敌方锁定，不会变差。

实测（各对手 100回合×2，对比触发器关闭的旧版本）：

| 对手 | 旧 APS | 新 APS | Δ |
|------|--------|--------|------|
| SandboxDT | 51.5 | 54.8 | +3.3 |
| Firestarter | 38.0 | 40.7 | +2.7 |
| Ronin | 62.1 | 64.8 | +2.7 |
| RaikoNano | 78.4 | 79.2 | +0.8 |
| FloodMini | 78.3 | 78.8 | +0.5 |

强自适应枪一致上升，弱枪持平（触发器保持关），零退化。flattener 权重扫掠：0.2 略弱、
0.35 最优、0.5+ 过度反而变差。

## 4. 当前实测定位（枪精确 MEA + 受弹触发 flattener）

| 对手 | Mirage APS | 备注 |
|------|------------|------|
| Ronin | ~63 | 稳定胜出（同源 surfer）|
| SandboxDT | ~54 | 受弹触发 flattener 后从 ~51.5 升到 ~54（200r：SURV 61.5→66，TAKEN 48.6→45.3）|
| Firestarter | ~41 | flattener 触发后上升（38→41）|
| 第三方弱枪 | ~79 | FloodMini/RaikoNano 全胜（触发器保持关，不受影响）|
| expert 目录 | ~57 | PWIN 100%（赢 Pear、CassiusClay、SandboxDT）|

枪侧精确 MEA 让枪更准；受弹触发的移动侧 flattener 让强自适应枪打不准我方——两者叠加
让 Mirage 在 SandboxDT 这类顶尖 surfer+枪组合上从硬币战 (~51%) 提升到稳定微胜 (~54%)。

## 5. 得分提升 Phase 0 基线（2026-07-09）

本节记录 0.9.2 得分提升工作的 Phase 0.1 基线。机器人 `zen.Mirage 0.9.1`，
commit `29f8edf`，35 回合/配对，无 system property override
（正式默认）。命令 `just deploy mirage` 后 `just duel -r Mirage -c <catalog>`。

| 目录 (配对数) | APS% | PWIN% | Survival% | dealt/r | taken/r | 遍数 |
|---|---|---|---|---|---|---|
| basic (4) | 76.14 | 100.0 | 94.28 | 41.9 | 29.4 | 3 |
| classic (3) | 63.25 | 100.0 | 81.90 | 31.6 | 36.7 | 3 |
| roborumble-100 (101) | 53.37 | 64.11 | 64.74 | 34.6 | 43.3 | 2 |

逐遍明细（APS / PWIN / Survival / dealt/r / taken/r）：

- basic：74.00/100/91.43/40.1/30.5 · 77.60/100/95.71/42.4/28.1 ·
  76.83/100/95.71/43.1/29.6
- classic：62.38/100/80.00/32.2/37.2 · 63.17/100/80.95/31.3/35.6 ·
  64.21/100/84.76/31.2/37.4
- roborumble-100：53.59/60.40/65.32/34.4/43.2 · 53.14/67.82/64.16/34.7/43.3

原始 TSV 留档于 `.cache/baseline-mirage-*.tsv`（本地 artifact，不入 Git）。

### 关键校准（影响后续验收口径）

1. **本地 roborumble-100 ≠ rumble 快照。** 本地这 101 个是偏强子集，APS 53.37、
   PWIN 64；rumble 快照（0.9.1，~1100 对手全场）是 APS 81.23、PWIN 95.38、
   Survival 91.65。两者口径不同，**不要直接比绝对值**。本地目录是 A/B 对照的
   受控基准，rumble 快照用于发布后观测。后续阶段的「APS 上升」
   都指相对本表同目录基线的增量。
2. **101 配对聚合很稳。** roborumble-100 两遍 APS 差 0.45、dealt/taken 差 <0.3；
   PWIN 抖动大（60↔68）是因为它是「配对数跨 50% 的离散计数」，不作主判据。
   弱目录（basic/classic 只有 3–4 配对）单遍噪声大，故跑 3 遍取均值。
3. **dealt/r 远低于子弹击杀所需的 ~100，即使 survival 接近满。** basic survival
   ~94% 但 dealt/r 才 ~42 → 不少胜局并非我方子弹击杀收尾（敌方撞墙/自耗/
   inactivity 等替我们处死），既没打满伤害也丢了 20% 击杀加成。这修正了
   初始分析中「胜局 dealt 近似恒定、预期不动」的口径：**在弱对手目录上，
   随方案 A/B/C 把非子弹死亡转成子弹击杀，dealt/r 会上升**；dealt「不动」
   只在强敌目录（已经子弹击杀为主）成立。所以弱目录验收应同时看 taken 下降
   和 dealt 上升，强敌目录才以 taken/回合时长为主。

### Phase 1 · B.1（残局贴脸收尾，`mirage.endgame`）

敌人失能（energy ≈ 0、无存活波）时把 targetRange 覆盖到贴脸（90px），让
`Gun.capPower` 的致死弹贴脸命中，抢在 inactivity zap 前拿到击杀归属与 20%
加成。默认关，`mirage.endgame=on`/`ram` 开启（`ram` 为 B.3 撞击预留）。

验证（Lindada 100r×5，endgame 在此最常触发；survival ±为 5 次的总体标准差）：

| 开关 | APS | Survival | dealt/r | taken/r |
|---|---|---|---|---|
| off | 74.39 | 92.80±2.04 | 38.5 | 29.9 |
| on  | 74.96 | 93.00±2.61 | 39.2 | 29.2 |

机制确认（debug 可见 `range=90` 在失能回合出现）；taken −0.7、dealt +0.8、
APS +0.56，survival 落在 ±2 噪声内（零回归）。**踩坑记录**：n=2 时曾读出
survival −3.5 的假回归，扩到 n=5 后落回噪声——单样本不可信，endgame 这类
低频触发特性尤其要多跑。触发罕见（仅部分对手/部分种子会失能），故
basic/classic 目录上落在噪声内、看不出效果。效果虽小但稳定为正、零回归，
默认暂关，等 Phase 1（B.1+B.2）整体验收后连同默认翻转与版本 bump 一起上。

### Phase 1 · B.2（FireDetector zap 误判修复，always-on）

inactivity zap 把双方各放 0.1/tick，被 `FireDetector` 的合法窗口
（≥ `MIN_BULLET_POWER − SLACK` = 0.09）判成 power 0.1 的开火，每约
11 tick 生成一条幻影波，污染浪冲学习、也会让 B.1 的「无存活波」条件
永不满足。修法：`FireDetector` 追踪我方能量残差 `ourResidual =
ΔourEnergy − ourFirePending`（只扣我方开火成本），当敌方掉能 ∈[0.09,0.11]
且我方残差 ∈[0.06,0.14] 同时成立时判为 zap、跳过该次检测。`detect` 加
`ourEnergy` 参数，`Gun` 开火后回调 `ourFire(power)`。命中增益/损失不记账：
命中会重置 inactivity，与 zap 不共存，且正常对局里残差远在 zap 窗外（敌弹
命中至少造成 0.4 掉能）。

验证：roborumble-100（101 配对）APS 53.20（基线 53.37）、survival 64.53
（64.74），均在噪声内——零回归，正常火检不受影响。basic×3 更明显：APS
76.14→78.41、survival 94.29→95.95、taken 29.4→26.8（FloodMini/BasicGFSurfer
这类防守型对手易拖成低命中僵持、吃 zap，幻影波移除后浪冲更干净；4 配对
仅供参考）。always-on 修复，不走开关。

### Phase 2 · 方案 A（威胁感知距离，`mirage.harvest`）

低威胁敌人（命中率低）时把接战距离沿阶梯下调 350→300→260 以缩短击杀时长、
压低 taken。实现 `HarvestController`：tier LOW = `wavesObserved >= 60` 且
`enemyHitRate < 0.08`，回退 latch（单回合 taken > 12 → NORMAL 锁 3 回合）。
接入 targetRange 优先级链（`mirage.range` > 残局 > policy > harvest > 公式）。
开关 `mirage.harvest`（`off`/`on`/数值），阈值/波数可调
（`mirage.harvestRate` / `mirage.harvestWaves`）。

踩坑：MIN_WAVES 初定 30 时，Ronin 在 r=1（41 波、ehr 暂低读 7.3%）闪一下
LOW→range=350；提到 60 后强敌全程不触发（Ronin min range 428）。

验收：basic +0.80 APS（仅 RaikoNano 触发）；roborumble-100×2 mean APS
+0.31、survival +0.32、taken −0.35（#1 读 +0.94 是单样本偏画）。抬阈值到
0.12 更差（APS 53.19/surv 64.29，backoff 抽搐）→ 0.08 是甜点。**结论：方案
A 收益小**——roborumble 是竞技场，多数对手打我们 >8%，触发面窄，不是初始方案
预期的「收益最大」。零回归、小正，默认暂关，主要增益转向 Phase 3 火力。

### Phase 3 · 方案 C（火力上调）——核心反甲：ECONOMY 上限是生存特性

预期是最大火力杠杆，实测**不成立**。直接验证 `mirage.power=balanced`
（全场撤 ECONOMY 的 1.4 上限）：roborumble-100 survival **−8.6**、APS −0.8
（dealt +3.7、taken −4.7 但 survival 崩盘）。高威力未命中自耗能量、对强
敌自禁。**ECONOMY 的 1.4 上限不是输出泄漏，是压制自禁的生存特性**；
`AGGRESSIVE_FLOOR`（energyLead>20→2.0）门控有害，只是被 ECONOMY 钳死休眠。

C.1（窄而安全版）：LOW 档时旁路为 BALANCED/1.2，开关 `mirage.harvestpower`。
roborumble 验收 APS 53.00（A-only 53.69、基线 53.37）——中性偏负，LOW 档面
窄。默认关。C.2 仅记录（selector 恒短路回 ECONOMY，不动 score）。C.3 别除
（数据表明 energyLead 门控有害，扩展方向反了）。

**阶段性结论**：方案 A/C 两大预期杠杆在本地 101 强子集上均小（弱敌少 +
提火力自禁）。本地可复现的小正只有 B.1（残局，窄）与 B.2（zap 修复）。真
正增益需上 rumble 全场（弱敌多）验证；本地基线仅供 A/B 对照。

### Phase 4 · 方案 D（反 surfer 第二枪，`mirage.asgun`）——本地净负，默认关

实现 `dcAsGun`：与主 DC 同走真实开火训练路径（`dcGun.onFire` 本就不收 tick
wave），仅把 recency 半衰期从 400 降到 50（`mirage.ashalflife` 可调），经
`DcGun.halfLifeOverride` 参数注入；`VirtualGuns.Aim.GF_DC_AS` 入枚举自动进
vgun 评分；主 DC 就绪后按 vgun 命中率在 GF_DC/GF_DC_AS 间二选一。机制确认
（对 Ronin AS 被选 18/30 回合）。

验收净负：roborumble-100 APS 52.77（−0.60）、survival 62.00（−2.74）；
expert ΔAPS −0.71。快衰减 AS 追逐近期噪声、命中率更低，主 DC 的 400 半衰期
是 §3.6 验证过的甜点。**默认关**，留作 rumble 全场复测，复测仍负则删。

### Phase 5 · 0.9.2 本地候选与总结

0.9.2 本地候选默认开 B.1（`mirage.endgame`）+ A 距离（`mirage.harvest`），B.2 zap
修复 always-on；C.1（`mirage.harvestpower`）与 D（`mirage.asgun`）保持关。
basic 3×：APS +0.39 / taken −1.7 / survival −0.95（噪声内）。

**跨阶段元结论**：初始方案预期的两大杠杆（A 距离、C 火力）在本地 101 强子集上
都小。A：roborumble +0.31 APS（弱敌少，8% 阈值面窄）。C：核心反甲——
ECONOMY 1.4 上限是**生存特性**（全场撤它 survival −8.6，高威力未命中自禁），
不是输出泄漏；C.3 扩展 energyLead 门控方向反了（该门控有害，被 ECONOMY
钳死休眠）。D：快衰减 AS 枪净负（追逐近期噪声，主 DC 400 半衰期更优）。
本地可复现的小正只有 B.1（残局，窄）与 B.2（zap 修复）。0.9.2 尚未发布；
是否发布由后续发布前优化与综合回归决定。

### Phase 6 · 发布前 score 优化：DC pending 跨回合修复

先补齐进攻诊断：`OffenseStats` 跨回合统计我方实弹命中率，`MDBG` 增加
`ohr`、`avgPower`、`ticks`，并把原先重复的 `GF` 虚拟枪缩写改成唯一标签。
同时加入 `OffenseStats` 与 DC 回合边界的单元测试。

依次验证了三个基于压制态的 score 方案：枪效优势时切 BALANCED、收距至
350 px、敌方 0–4 能量时收距至 200 px 补刀。它们都出现过局部正样本，但最终
目录 A/B 不稳定或为负，因此行为代码删除，不进入默认配置。这个过程确认了两点：

- `--seed` 只能控制使用 `Math.random()` 的机器人；不少参考机器人自建
  `java.util.Random()`，同 seed 仍有明显噪声，必须看多次目录聚合。
- `BulletPeer` 会把伤害得分裁剪到敌方剩余能量，`Gun.capPower` 的恰好致死
  公式正确；过杀不能提高 APS。

随后发现 DC 枪的真实跨回合错误：`DcGun.forEnemy` 有意让 observations 与 profile
跨回合保留，但 `pending` 也随静态实例保留。Robocode 每回合把 `time` 重置为 0，
旧 pending 波会在后续回合到达旧 `fireTime` 后，用新回合无关位置结算，污染 KNN。
修复为 `DcGun.beginRound()` 只清 pending、保留已解析学习；默认开启，
`mirage.dcclear=off` 可复现旧行为。

3×100 回合 A/B：

| 目录 | ΔAPS | ΔSurvival | 结论 |
|---|---:|---:|---|
| basic | −0.40 | −0.75 | 小负，处于弱目录噪声带 |
| classic | +2.26 | +3.44 | 三次 APS 全正 |
| expert | +0.48 | +1.00 | 综合小正 |
| 9 个对手等权汇总 | +0.78 | +1.23 | 综合为正 |

该改动既消除了确定性错误，综合 A/B 也为正，作为本轮唯一默认行为改进保留。

### Phase 7 · 负 KNNPBI 对手优化：反撞击与主动弹盾

针对 `mirage-negative-knnpbi` 中 KNNPBI 最低的 10 个对手，先做 `3 × 100` 回合基线，
等权 APS 为 36.76。诊断显示 Bulldozer 单回合可发生 6～63 次碰撞；PrairieWolf 则是
另一类问题：碰撞很少，但基线 `taken/r` 78.13、`dealt/r` 20.01，长期被低效换血压制。

反撞击采用行为识别，不使用对手名称：连续观察敌方推进速度、距离收缩、行进航向和
横向速度，6 tick 证据后开启 40 tick latch；碰撞立即开启 latch。威胁生效时，枪切到
`AGGRESSIVE` 并保留 1.2 火力地板。`mirage.antiram=off` 可恢复旧行为。仅这一处窄火力
切换就让 Bulldozer 的 `3 × 100` 平均 APS 从 35.47 提到 70.23，其余 8 个非
PrairieWolf 目标也全部超过 60 APS。

PrairieWolf 使用主动弹盾：对最近、确实穿过当前机体的真实敌浪求恒速解析交点，从
0.1 威力发射拦截弹；每条敌浪最多尝试一次。弹盾拥有炮塔时，普通枪仍更新虚拟枪、
DC 和 tick-wave 学习，但不发射实弹；弹盾结果单独记账，不污染普通枪命中率。

强制全局弹盾会把 `classic + expert` 6 对手等权 APS 从 60.84 降到 52.41，因此缺省
使用回合级 A/B 选择器。普通模式发生“未存活、`taken >= 65`、`dealt <= 40`”时，
只安排一个弹盾试验回合；至少积累 3 个普通回合、普通平均效用不高于 10，并取得
2 个弹盾回合后，只有弹盾效用 `dealt - taken + 50 × survived` 高出普通模式 10
以上才锁定。选择器状态只在当前
battle/JVM 内存中按对手保留。

最终 `3 × 100` 回合均值：

| 对手 | 基线 APS | 候选 APS | Δ |
|---|---:|---:|---:|
| Bulldozer | 35.47 | 70.23 | +34.76 |
| Machete | 34.44 | 60.87 | +26.43 |
| ButtHead | 34.45 | 61.28 | +26.83 |
| Tirunculus | 47.54 | 65.18 | +17.64 |
| PrairieWolf | 26.96 | 54.78 | +27.82 |
| MaxRisk | 35.07 | 62.83 | +27.76 |
| SabreuseNano | 34.67 | 61.61 | +26.94 |
| Sanguijuela | 34.89 | 63.44 | +28.55 |
| NanoDeath | 39.47 | 64.35 | +24.88 |
| Impact | 38.66 | 63.05 | +24.39 |

10 对手等权 APS 从 36.76 提到约 62.76；最终无参数单遍复验为 62.88。复杂反撞击路径
模拟和追击预测枪没有进入本轮：
较小的行为识别、火力切换和主动弹盾已经使全部目标达标，继续扩展只会增加回归面。

### Phase 8 · 低 APS 对手优化：Anti-ram 逃逸移动

Phase 7 的 `RamThreatDetector` 只切换近距离火力，不改变移动。第二轮目标取
`mirage-knnpbi-11-100` 中本地 APS 最低的 10 个对手；诊断确认其中包含硬 rammer、
条件式 rammer 和完全不碰撞的长距离对手，所有策略继续按行为触发。

新增 `AntiRamPlanner`：

- `RamThreatDetector.Snapshot` 暴露置信度、预计碰撞时间和追击方向；
- 对左右两条逃逸走廊模拟 28 tick 的自身运动与简单 pursuer，比较最小距离、最终
  距离、墙面空间和近距离压力；
- 非紧急状态只给 surfer 一个有上限的方向偏好，并禁止停车；
- 碰撞紧迫或 `onHitRobot` 后 18 tick 内，沿敌人到 Mirage 的径向外侧偏转 35°，
  直接驶向更开放的走廊；
- 主动弹盾已经通过跨回合试验锁定时，弹盾优先，暂停 anti-ram 移动，避免破坏拦截
  几何；
- `mirage.ramescape=off` 只关闭新移动，保留 Phase 7 的 anti-ram 火力，供 A/B 使用。

第一版只改变 surfer 环绕方向。它把 SledgeHammer 的碰撞从每回合几十次降到个位数，
却让双方在约 40 px 距离长时间并行，敌方命中率升至 70%～90%，`taken/r` 超过 100；
因此拒绝。最终版增加径向外逃分量后保留。

`mirage-low-aps` 的 `3 次 × 100 回合` 结果：

| 对手 | 基线 APS | 候选 APS | Δ |
|---|---:|---:|---:|
| `sul.Bicephal 1.2` | 65.24 | 70.08 | +4.84 |
| `gh.micro.GrubbmThree 1.01` | 64.80 | 68.84 | +4.04 |
| `demetrix.nano.SledgeHammer 0.22` | 65.10 | 70.85 | +5.75 |
| `benhorner.PureAggression 0.2.6` | 66.75 | 75.92 | +9.17 |
| `wiki.WaveRammer 1.0` | 64.54 | 71.35 | +6.81 |
| `exauge.LemonDrop 1.6.130` | 64.12 | 65.96 | +1.84 |
| `oog.nano.Caligula 1.15` | 65.29 | 77.65 | +12.36 |
| `wompi.Kowari 1.6` | 66.22 | 70.88 | +4.66 |
| `suzushin7.nano.Galaxy03 1.01` | 69.11 | 76.20 | +7.09 |
| `jcs.AutoBot 4.2.1` | 70.44 | 70.01 | −0.43 |
| **等权平均** | **66.16** | **71.78** | **+5.62** |

Survival 从 87.43% 提高到 95.06%。`taken/r` 从 44.2 升到约 57.7，因为逃逸移动把
大量碰撞击杀转换成双方继续发射子弹的回合；现有 `taken/r` 只统计子弹伤害，不含
每次 0.6 的碰撞能量损失。APS 和 survival 同时明显上升，说明该变化不是防御回退。

防回归：

| 目录或对手 | 候选 | 对照 | ΔAPS |
|---|---:|---:|---:|
| `mirage-knnpbi-11-100` | 86.42 | 81.78（此前两遍均值） | +4.64 |
| `classic + expert + top` | 45.62 | 45.46（`mirage.antiram=off`） | +0.16 |
| `roborumble-100` | 52.84（两遍均值） | 53.20（`mirage.ramescape=off`） | −0.36 |
| Saguaro 1.0 | 54.95 | 48.91（`mirage.ramescape=off`） | +6.04 |
| Knight 0.6.28 | 37.48 | 35.83（`mirage.ramescape=off`） | +1.65 |
| PrairieWolf 2.61 | 51.18 | 47.47（`mirage.antiram=off`） | +3.71 |

`roborumble-100` 的 −0.36 小于该目录的 0.5 APS 验收容差，强敌 9 对手汇总与两个
疑似敏感对手专项均无一致性回归。最终候选默认开启。

### Phase 9 · `ShotDodger-lite` 与得分压制实验

#### 简单枪专家

`ShotDodger` 在每条真实敌浪创建时分别固化 Head-on、Linear、Circular 和
Wall-linear 的预测 GF。敌弹命中时，用真实 GF 计算四个专家的误差；敌浪未命中但
某条预测弹道穿过 Mirage 实际覆盖的 hull 区间时，只给对应专家记一次失准。统计按
对手保存在 battle/JVM 内存中，跨回合保留，不写数据文件。

只有同时满足以下条件时才叠加专家 danger 峰：

- 至少 8 个有效观测；
- 滚动准确率至少为 0.62；
- 相对当前预测不同的次优专家领先至少 0.12；
- 连续失准少于 3 次。

启用后，把归一化权重 0.55 的指数峰叠加到原 empirical danger，不替换现有
wave surfing；连续 3 次失准会立即回退。`mirage.shotdodger=off` 是纯 A/B 对照，
`mirage.shotweight` 可调整峰值权重。LemonDrop、RaikoMX 和 Lacrimas 的准确率长期
只有约 0.1～0.2，调试日志确认没有启用；Bicephal 稳定选择 Linear，Kowari 间歇
选择 Linear，GrubbmThree 主要选择 Circular 或 Wall-linear。

实际启用的三个目标执行 `3 次 × 100 回合` A/B：

| 对手 | 候选 APS | `shotdodger=off` | ΔAPS |
|---|---:|---:|---:|
| Bicephal | 71.67 | 69.86 | +1.81 |
| Kowari | 71.14 | 71.01 | +0.13 |
| GrubbmThree | 69.49 | 69.17 | +0.32 |
| **等权平均** | **70.77** | **70.01** | **+0.76** |

三对手 Survival 为 97.11%/96.89%，`taken/r` 为 71.48/74.57。完整
`mirage-low-aps` 单次 100 回合 A/B 为 72.31/71.04 APS、95.80%/93.79%
Survival、56.9/58.3 `taken/r`。最终无 override 的 35 回合复验为 72.03 APS、
93.99% Survival，10 个目标全部达到 65 APS 或以上。因此专家层默认开启。

防回归结果：`mirage-knnpbi-11-100` 为 86.32 APS、98.76% Survival；
`roborumble-100` 为 52.63 APS，与 Phase 8 两遍均值 52.84 相差 −0.21。强敌调试
中 `appliedWaves=0`，A/B 分差来自部分参考机器人不受 `--seed` 控制的自建随机数。
PrairieWolf 同样全程未启用专家；两次 100 回合候选均值为 50.40 APS。

#### Phase 3 负面实验

三个后续方案均未进入默认行为：

1. 对已确认专家测试 gun-heat-timed `brake` 和 `shift`。三个目标 100 回合等权 APS
   分别为 70.08、71.35，普通移动为 71.59；`taken/r` 分别为 76.5、70.9、69.5。
   两种时机移动均删除。
2. 把主动弹盾 trial 扩展到“存活但单回合高伤”对手。LemonDrop 与 AutoBot 的
   100 回合等权结果为 68.84 APS、86.5% Survival，对照为 70.67、89.0%；
   `taken/r` 也没有下降，因此扩展 trial 删除。
3. 比较普通攻击、强制主动弹盾和完全不开火。两对手 35 回合等权 APS 分别为
   73.21、60.69、19.39，普通攻击显著最好。

保留一项安全修复：`ActiveShieldPolicy` 不从已识别 ram 回合安排试验；未验证 trial
在本回合检测到 ram 后立即让位给 anti-ram。只有已经通过跨回合效用比较并锁定的
主动弹盾继续保持优先级。

### Phase 10 · 最新负 KNNPBI 目录：周期停走 MEA 与停车 GF 符号

#### 目录与验收口径

以 2026-07-11 08:24:37 UTC 的 RoboRumble 页面为快照，严格按 KNNPBI 从低到高
保留 100 个对手，写入 `mirage-knnpbi-worst-100`。本轮统一使用 35 回合/对手。

目录准备时修复了 `scripts/duel.py` 的多机器人 JAR 展开问题：目录条目只匹配从 JAR
文件名推导出的主类，Fractal 等 companion class 仍可直接选择，但不再让 100 个目录
条目膨胀成 103 个实际对手。目录匹配也改为沿用 `refs.jsonc` 的声明顺序，后续运行会
从 PrairieWolf、Drifter 开始，严格按 KNNPBI 顺序逐项研究。

#### Drifter 根因与 theory-MEA 候选

`tcf.Drifter 29` 每隔约 20 tick 或在新敌浪出现时重新规划移动：从两个半径、每 15°
一个方向的 48 个候选落点中模拟 25 tick，并允许把最大速度设为 0。20 回合轨迹中，
完全停车占 13.65%，`|v| ≤ 1` 占 17.84%。

Mirage 对 Drifter 的问题不是不开枪：每回合约发射 41～116 发，实弹命中率约 11%，
但 `dealt/r` 只有 29～31。精确 MEA 会随 Drifter 当前制动相位改变 GF 尺度，同一种
周期运动因而被映射到不同 GF。强制 theory MEA 的 `3 次 × 100 回合` 结果如下：

| 配置 | APS | Survival | `dealt/r` | `taken/r` |
|---|---:|---:|---:|---:|
| 精确 MEA | 53.11 | 约 67% | 28.90 | 41.07 |
| 理论 MEA | 58.51 | 约 73% | 32.63 | 38.63 |
| 差值 | +5.40 | 约 +6 个百分点 | +3.73 | −2.44 |

新增的 `StopGoDetector` 不识别机器人名称，只累计运动证据：

- 每回合至少 10 次完整停车，平均低速持续时间至少为 2.2 tick；
- 至少 10 个停车起点间隔，平均周期在 27～45 tick，变异系数不超过 0.75；
- 最近 5 回合中至少 3 回合合格才启用，连续 3 回合不合格即退出；
- 扫描间隔超过 2 tick 时中断当前停车与周期间隔，避免把雷达缺口当作静止；
- 只有距离不小于 250 px 时，枪才切换到 theory MEA。

精确 MEA 与理论 MEA 使用两个独立 DC 模型，并从第一枚实弹开始并行训练，避免把两种
GF 归一化尺度混入同一 KNN 数据集。第一版把最小周期设为 12 tick，虽然 worst-100
从 78.88 升到 79.51，却会误判 Dookious、YersiniaPestis 和 VirginSteele。收紧证据
窗口和周期后，Dookious 与 VirginSteele 保持 0 个触发回合；YersiniaPestis 只短暂
触发一段，随后自动退出。

BRAKE 专家没有保留：101 个刹车样本中，其虚拟命中率约为 5%，低于 DC 的约 6%，
且从未成为首选。

#### 停车时 GF 符号翻转

继续研究 Dookious、Pris 和 deBroglie 后发现了一个独立 bug：`Tracker` 已经把最近的
非零横向轨道方向保存在 `lateralDirection`，但 `Gun` 仍用 `lateralSpeed >= 0` 计算
`orbitSign`。目标从负向轨道停车时，横向速度恰好为 0，GF 符号便被错误翻成 `+1`，
同时污染 DC 与分段 GF 枪的训练标签。

修复后，速度为 0 时沿用 Tracker 的 sticky direction；只有冷启动尚无方向时才回退
到横向速度符号。五个停车相关对手的 35 回合直接 A/B 如下：

| 对手 | 旧 APS | 修复后 APS | Δ |
|---|---:|---:|---:|
| Dookious | 31.08 | 36.22 | +5.14 |
| Pris | 31.90 | 45.56 | +13.66 |
| deBroglie | 40.25 | 46.16 | +5.91 |
| Drifter | 57.69 | 56.33 | −1.36 |
| PrairieWolf | 69.40 | 73.17 | +3.77 |
| **等权平均** | **46.06** | **51.49** | **+5.43** |

等权 Survival 从 53.71% 升到 63.43%，`dealt/r` 从 33.8 升到 36.5，`taken/r`
从 49.0 降到 48.1。Drifter 的 APS 差异在单次 35 回合噪声带内，且其 `dealt/r`
仍从 30.3 升到 32.5。

#### 最终 35 回合验收

| 配置 | APS | Survival | `dealt/r` | `taken/r` |
|---|---:|---:|---:|---:|
| 旧行为：precise-only + 停车固定 `+1` | 78.88 | 91.37% | 53.0 | 25.5 |
| Stop/Go 3/5 检测 + 旧轨道符号 | 78.42 | 90.89% | 52.6 | 25.9 |
| **最终：Stop/Go + sticky orbit** | **79.07** | **91.86%** | **53.6** | **25.7** |

最终相对旧行为为 `+0.19 APS`、`+0.49` 个 Survival 百分点、`+0.59 dealt/r`；
100 个配对中 55 个为正、45 个为负。相对尚未修复轨道符号的上一候选，sticky orbit
单独贡献 `+0.65 APS`、`+0.97` 个 Survival 百分点和 `+0.95 dealt/r`。

强敌保护使用 Ronin 与 SandboxDT，各 35 回合：等权 APS 从 54.06 升到 57.22，
Survival 从 62.86% 升到 70.00%，`taken/r` 从 45.5 降到 41.7。随后 12 个调试回合
中 `stopGoLikely=0`、`theoryMea=0`，确认增益不是误触发周期停走策略。

#### 本轮拒绝的方案

- 最低三组强制 `BALANCED` 首轮有收益，但复验不稳定；`PRESSURE`、逐发火力探索和
  score-aware 自动门控均未通过，相关候选代码已删除。
- `NOISY_ORBIT` 与 `SURVIVAL_SEARCH` 在最低三组的等权 APS 分别只有 34.43 和
  33.89，低于现有 PURE/FLAT 组合。
- 完全关闭停车 Head-On 会明显伤害 PrairieWolf；把触发门槛从 6 提到 12 tick 也没有
  给 Dookious、Pris、deBroglie 带来一致收益，因此保持原门槛。
- 加入第七个低速阶段特征的独立 phase-DC，把五组等权 APS 从 51.49 降到 43.17，
  已完整删除。
- 对最低三组全程强制 theory MEA 只有小幅且不稳定的聚合变化；theory MEA 继续只由
  严格的周期停走检测触发。

### Phase 11 · 顶级 surfer：hit-aware anti-surfer DC

#### 五个目标的共同失分结构

本轮集中研究 Dookious、Pris、Phoenix、Cyanide 和 YersiniaPestis，每个对手仍按
RoboRumble 口径运行 35 回合。公开资料和 JAR 内源码显示，它们都在中远距离使用成熟的
自适应枪与规避策略，但实现并不相同：

- Dookious 和 Phoenix 使用 Wave Surfing、普通 GF 枪与 Anti-Surfer 枪；
- Pris 使用强化学习移动与神经网络 GF 枪；
- Cyanide 的分段 GF 枪显式使用加速度、速度变化时间和正反墙距；
- YersiniaPestis 使用 DC-GF 枪与 DC-Wave Surfing，并区分普通和 Anti-Surfer 目标。

35 回合诊断没有发现 skipped turn、异常、零发射回合或新的 fire gate bug。Mirage 每
100 tick 约发射 6.24～7.00 发，真实命中率约为 11%～13%。主要差距之一确实是火力：
Mirage 的平均弹力约为 1.01～1.14，对手约为 1.70～1.83；但全局提功率会因自耗能导致
Survival 崩溃，不能直接解决。

五个对手每回合约有 25.3～31.3 次短暂停车，平均持续 2.37～3.48 tick。这些停车围绕
Mirage 的在途子弹波发生，是成熟 surfer 的战术刹车，不是 Drifter 式固定周期停走，
因此没有放宽 `StopGoDetector`。

#### 真正的 Anti-Surfer 密度

旧 `dcAsGun` 只把 recency 半衰期从 400 缩短到 50，本质上仍是普通 DC 枪；Phase 4
已经证明它会追逐近期噪声。本轮用经典 Anti-Surfer 语义替换该实验模型：

- 普通已解析实弹波对访问 GF 贡献 `+1`；
- 如果这枚实弹真正命中，在子弹接触坐标对应的 GF 额外施加 `−2`；访问位置与命中位置
  不必相同；
- 普通 DC 与 Anti-Surfer DC 使用相同的 400 观测半衰期并从第一枚实弹起并行训练；
- `BulletHitEvent` 通过 bullet identity 找到对应 pending 波，并立即计算实际接触 GF；
- 致死子弹不会再产生下一次扫描，因此新回合清理 pending 波前，会把它保留为只有 `−2`
  命中密度、没有伪造访问位置的 observation；
- 新模型只改变自己的 KNN 密度，不污染普通 DC、theory-MEA DC 或分段 GF 数据。

原因是学习型 surfer 被某个 GF 命中后，会把该位置记为危险区并在后续避开。普通密度枪
继续追逐历史命中位置，恰好落在对手下一次最想避开的区域；命中负权重则把枪口移向尚未
被对手标成危险的相似访问。

全程强制 hit-aware AS 会伤害部分普通目标，因此不能全局强制。最初的门控还存在两个
实现问题：仲裁用的 `VirtualGuns` 每回合重建，80 样本门槛实际上不可达；而 warmup
阶段的 `best()` 又可能绕过门槛提前选择 AS。本轮把仲裁证据拆成独立的
`AntiSurferEvidence`：pending virtual wave 仍是回合内状态，只有普通 DC 与 AS 的
would-hit 结果按对手跨回合保留，不改变原有 early aim、firepower 或其他虚拟枪评分。

最终使用无名称、带滞回的门控：

- 普通 DC 与 AS 都至少有 45 个已解析 observation；
- 只累计 precise-MEA 且两个模型都已就绪的可比样本；
- 至少有 100 个近期加权的已解析 virtual-gun 样本；
- 普通 DC 的近期虚拟命中率不高于 0.20；
- AS 的近期虚拟命中率至少领先普通 DC 0.03 才进入；
- 进入后保持到领先低于 0.005，或普通 DC 命中率高于 0.22，再退出。

这让正常工作良好的普通 DC 保持主枪，同时避免相邻两枚 virtual wave 解析后反复切枪、
耽误炮口对齐。`mirage.debug` 的 `as=main/as@attempts/on|off/sel:n` 会显示跨回合证据、
当前状态和本轮实际 AS 发射数。`mirage.asgun=force` 保留强制探针，
`mirage.asgun=off` 可恢复旧行为。

#### 主动盾试验预算修复

诊断还发现 `ActiveShieldPolicy` 在两次失败试验后没有 rejected 状态，坏 normal 回合会
不断重新安排试验；而且只要进入 shield mode，即使没有发出盾弹，也会把整轮记为 shield
样本。本轮修复为：

- 只有本回合实际发出盾弹，才记录为 shield 样本；
- 两个有效 shield 样本后只评估一次，成功则 latch，失败则 rejected；
- 如果试验轮一直没有发出盾弹，最多尝试 4 轮，随后 rejected；
- rejected 后不再重复试验，也不会因 normal 平均值后来下降而延迟 latch；
- latch 后继续观察真实收益；累计 5 个有效 shield 样本后，如果平均收益已不再比 normal
  高 10，则解除错误锁定并 rejected。

这既保留真正有效的主动盾，也限制无效试验和两样本偶然优势造成的长期成本。Pris 的诊断
日志曾出现前两轮误 latch、随后二十多轮 shield 平均收益转负的情况；重新评估会终止该
路径。

#### 拒绝的实验

- `mirage.aim=best` 在五组从约 42 APS 降到约 33，继续证明虚拟命中率不能直接替代
  forced-DC 主枪策略。
- 全程 `BALANCED`、`PRESSURE` 和 `AGGRESSIVE` 都提高 `dealt/r`，但因慢弹和自耗能
  让 Survival 大幅下降；把 BALANCED 封顶到 1.6 仍未通过。
- 完全禁停的两次 35 回合结果一正一负，聚合不稳定；其中一轮五组 APS 为 40.70，
  低于同轮基线 43.67，因此没有改变现有停车语义。
- 把推进速度改成有符号特征，五组两次合并提高 4.55 APS，但 Ronin 和 SandboxDT
  分别出现约 8 APS 的单轮回退；追加低权重方向维度也没有复现收益，相关代码删除。
- 在途子弹波相位特征只比有符号推进候选再高约 0.35 APS，并让 Phoenix 回退，收益不足以
  支撑额外模型维度，相关代码删除。
- 把短暂刹车的 Head-On 门控收窄后，五组聚合没有收益，并把 PrairieWolf 从约 69 APS
  压到 40.90，保持现有 6 tick 门槛。

#### 最终 35 回合验收

先在同一版主动盾策略和同一个 `--seed` 下，只切换 `mirage.asgun`。Java 21 无法让所有
对手的自建 RNG 完全确定，因此这不是逐 tick 重放，但比不同代码阶段的独立结果更适合
判断方向：

| 对手 | AS off APS / Survival | 门控 AS APS / Survival | ΔAPS |
|---|---:|---:|---:|
| Dookious | 37.45 / 45.71% | 41.69 / 51.43% | +4.24 |
| Pris | 37.24 / 40.00% | 45.29 / 54.29% | +8.05 |
| Phoenix | 35.39 / 42.86% | 35.60 / 40.00% | +0.21 |
| Cyanide | 55.84 / 77.14% | 53.92 / 71.43% | −1.92 |
| YersiniaPestis | 43.48 / 54.29% | 47.17 / 60.00% | +3.69 |
| **等权平均** | **41.88 / 52.00%** | **44.73 / 55.43%** | **+2.85** |

最终完整 `mirage-knnpbi-worst-100` 仍与 Phase 10 最终轮比较。100 个唯一对手全部完成，
每组 35 回合：

| 指标 | Phase 10 | Phase 11 | 变化 |
|---|---:|---:|---:|
| APS | 79.07 | **79.36** | +0.29 |
| Survival | **91.86%** | 91.63% | −0.23 |
| `dealt/r` | 53.6 | **54.0** | +0.4 |
| `taken/r` | 25.7 | **25.3** | −0.4 |
| PWIN | **93%** | 91% | −2 |

同一完整目录中，重点五组的独立绝对结果如下：

| 对手 | Phase 10 APS / Survival | Phase 11 APS / Survival | ΔAPS |
|---|---:|---:|---:|
| Dookious | 30.14 / 31.43% | 35.40 / 40.00% | +5.26 |
| Pris | 37.82 / 45.71% | 45.74 / 57.14% | +7.92 |
| Phoenix | 40.59 / 48.57% | 43.04 / 54.29% | +2.45 |
| Cyanide | 42.91 / 57.14% | 43.99 / 54.29% | +1.08 |
| YersiniaPestis | 45.93 / 60.00% | 38.27 / 42.86% | −7.66 |
| **等权平均** | **39.48 / 48.57%** | **41.29 / 49.72%** | **+1.81** |

YersiniaPestis 在同种子 A/B 中提高 3.69，但在两次独立完整目录中波动明显，因此不能把
单轮下降归因于新枪，也不能宣称它已经稳定改善。可以确认的是：门控 AS 对重点五组的
同配置 A/B 为正，完整目录 APS、伤害输出和承伤也没有回退；下一轮仍应优先降低
YersiniaPestis、Phoenix 等强 surfer 的 35 回合方差。

## 参考

- `bots/mirage/src/main/kotlin/zen/mirage/{Mirage,Gun,DcGun,Targeting,OrbitDirection,StopGoDetector,StopGoMeaPolicy,PreciseMea,MovementProfileSelector,FirePowerSelector,ActiveShieldGun,ActiveShieldPolicy,RamThreatDetector,AntiRamPlanner,ShotDodger,SimulatedTargeting,Surfer}.kt`
  ——诊断与 override 的实现。
- [RoboRumble / LiteRumble 评分指标说明](../../../docs/rumble-metrics.md) —— APS / PWIN / Survival 定义。
