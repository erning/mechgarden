# Mirage 调参与诊断系统

本文记录 `zen.Mirage` 的 per-round 诊断和 A/B 调参 override，以及定下当前默认值
的实测结论。目的是让后续调参工作能快速复现、对比，并避免重复已验证为「无用」的
方向。所有 override 都通过 JVM 系统属性传入，缺省即正常对战行为，零开销。

环境说明：`Ronin` 内部自建 `java.util.Random()`（构造时按当前时间播种），`Mirage`
走 Robocode 受控的 `Math.random()`。因此即便带 `--seed`，含 Ronin 的对战仍逐次
随机、不可复现。`APS` 单样本标准差约 ±3–5（50 回合），所以只有均值差 >~5、或多次
重复一致时才算可靠信号。

## 1. 诊断输出：`mirage.debug`

设 `-Dmirage.debug=true` 后，每回合结束时（`onRoundEnded`）向机器人控制台打一行：

```
MDBG r=12 dealt=35.2 taken=54.0 fired=42 hit=4 avgPower=1.21 ticks=913 aim=GF_DC dc=482 dcAcc=0.18 fp=ECONOMY ohr=0.154@476 [HO=0.17,...,DC=0.19,AS=0.19] prof=PURE_SURF range=465 ehr=0.094@482
```

| 字段 | 含义 |
|------|------|
| `dealt`/`taken` | 本回合造成/受到的子弹伤害。 |
| `fired`/`hit` | 本回合实弹发射数 / 命中数（真实命中率 ≈ hit/fired）。 |
| `avgPower` | 本回合实弹平均威力。 |
| `ticks` | 本回合持续 tick 数，用于直接观察击杀时长。 |
| `aim` | 本回合主用的瞄准模型（`GF_DC` 等，见 `VirtualGuns.Aim`）。 |
| `dc` | DC（KNN）枪已解析的观测数；≥ `DC_PRIMARY_MIN`（45）后 DC 成为主枪。 |
| `dcAcc` | DC 当前权重 profile 的滚动预测精度（0..1，`1/(1+(\|预测-实际\|/宽度)²)`）。 |
| `fp` | 本回合选中的火力 profile。 |
| `ohr` | 我方对敌人的跨回合实弹命中率 `ohr=<rate>@<resolved shots>`；BulletHitBullet 计为未命中。 |
| `[...]` | 每个 virtual-gun 的近期虚拟命中率：`HO/LIN/CIR`、`GFD/GFR/GFA/GFW`、`DC/AS`。 |
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
| `mirage.mea` | `theory` \| `precise` | 逃逸角：默认枪用精确 MEA、浪冲用理论 MEA（见第 3.5 节）；`theory`=枪与浪冲都用理论值，`precise`=浪冲也改用精确值。 | 枪=precise / 浪=theory |
| `mirage.dchalflife` | `0`–∞ | DC（KNN）枪的近期加权半衰期（单位：观测数）。每个邻居在密度估计中的权重按 `0.5^(age/halflife)` 衰减，age 为其后又积累的观测数。`0`=均匀（无近期加权，旧行为）。见第 3.6 节。 | `400` |
| `mirage.dcclear` | `on` \| `off` | 是否在新回合清除 DC 枪未解析的 pending 波；`off` 复现跨回合污染旧行为。已解析观测始终保留。 | `on` |

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

故默认：**枪用精确 MEA，浪冲用理论 MEA**。`mirage.mea=theory` 两侧都回理论值，
`mirage.mea=precise` 浪冲也改用精确值（留作将来整套重调的起点）。

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
「我方频繁访问的 GF」加危险，把我们推商这些 GF——从而压平访问分布、干扰学习型枪的
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

枪侧精确 MEA 让枪更准；受弹触发的移动侧 flattener 让强自适应枪打不準我方——两者叠加
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

## 参考

- `bots/mirage/src/main/kotlin/zen/mirage/{Mirage,Gun,DcGun,PreciseMea,MovementProfileSelector,FirePowerSelector}.kt`
  ——诊断与 override 的实现。
- [RoboRumble / LiteRumble 评分指标说明](../../../docs/rumble-metrics.md) —— APS / PWIN / Survival 定义。
