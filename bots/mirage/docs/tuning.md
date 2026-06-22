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
MDBG r=12 dealt=35.2 taken=54.0 fired=42 hit=4 aim=GF_DC dc=482 dcAcc=0.18 fp=BALANCED [HE=0.17,...] prof=PURE_SURF range=465
```

| 字段 | 含义 |
|------|------|
| `dealt`/`taken` | 本回合造成/受到的子弹伤害。 |
| `fired`/`hit` | 本回合实弹发射数 / 命中数（真实命中率 ≈ hit/fired）。 |
| `aim` | 本回合主用的瞄准模型（`GF_DC` 等，见 `VirtualGuns.Aim`）。 |
| `dc` | DC（KNN）枪已解析的观测数；≥ `DC_PRIMARY_MIN`（45）后 DC 成为主枪。 |
| `dcAcc` | DC 当前权重 profile 的滚动预测精度（0..1，`1/(1+(\|预测-实际\|/宽度)²)`）。 |
| `fp` | 本回合选中的火力 profile。 |
| `[...]` | 每个 virtual-gun 的近期虚拟命中率（`HE/LI/CI` = head-on/linear/circular，`GF…` = 各分段 GF 枪与 DC）。 |
| `prof`/`range` | 本回合移动 profile 与接战距离。 |

用 `--show-output` 跑可看到这些行：

```
JDK_JAVA_OPTIONS="-Dmirage.debug=true" python3 scripts/duel.py -r mirage -e ronin -n 20 --show-output
```

不设该属性时，诊断计数与打印完全不触发，对正式对战零影响。

## 2. A/B override 一览

每个 override 都用 JVM 系统属性 `-D…=…` 传入；缺省值即当前正式默认。

| 属性 | 取值 | 作用 | 默认 |
|------|------|------|------|
| `mirage.aim` | `dc` \| `best` | `dc`=DC 学够后强制为主枪；`best`=按虚拟命中率在含 DC 的全部模型里选。 | `dc` |
| `mirage.powerfloor` | `0.1`–`3.0` | DC 枪火力地板（覆盖 `DC_POWER_FLOOR_BASE`）。 | `1.2` |
| `mirage.dck` | `1`–`200` | DC 枪 KNN 邻居数 k。 | `25` |
| `mirage.profile` | `auto` \| `PURE_SURF` \| `NOISY_ORBIT` \| `WALL_SAFE` \| `STOP_SURF` \| `SURVIVAL_SEARCH` | 移动 profile：`auto`=按受血量在 `PURE_SURF`/`NOISY_ORBIT` 间 explore/exploit；指定名=整场强制该 profile；缺省=稳定的 `PURE_SURF`。 | `PURE_SURF` |
| `mirage.power` | `auto` \| `balanced` \| `economy` \| `pressure` \| `aggressive` | 火力 profile：`auto`=按每发净能量 explore/exploit；指定名=强制；缺省=稳定的 `BALANCED`。 | `BALANCED` |
| `mirage.mea` | `theory` \| `precise` | 逃逸角：默认枪用精确 MEA、浪冲用理论 MEA（见第 3.5 节）；`theory`=枪与浪冲都用理论值，`precise`=浪冲也改用精确值。 | 枪=precise / 浪=theory |
| `mirage.dchalflife` | `0`–∞ | DC（KNN）枪的近期加权半衰期（单位：观测数）。每个邻居在密度估计中的权重按 `0.5^(age/halflife)` 衰减，age 为其后又积累的观测数。`0`=均匀（无近期加权，旧行为）。见第 3.6 节。 | `400` |

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

## 参考

- `bots/mirage/src/main/kotlin/zen/mirage/{Mirage,Gun,DcGun,PreciseMea,MovementProfileSelector,FirePowerSelector}.kt`
  ——诊断与 override 的实现。
- [RoboRumble / LiteRumble 评分指标说明](../../../docs/rumble-metrics.md) —— APS / PWIN / Survival 定义。
