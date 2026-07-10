# Mirage 负 KNNPBI 对手优化清单

状态：实施中。

目标：优先战胜 `mirage-negative-knnpbi` catalog 中 KNNPBI 最低的 10 个对手，
并在不牺牲强敌表现的前提下提高 Mirage 的 RoboRumble APS。

研究依据：

- [`bots/mirage/docs/saguaro-aps-study.md`](bots/mirage/docs/saguaro-aps-study.md)
- `zen.Mirage 0.9.2` RoboRumble 配对快照（2026-07-10）
- Saguaro 1.0 随包对手档案中的锁定模式

## 目标对手

| 对手 | KNNPBI | Saguaro 锁定模式 | 主要方向 |
|---|---:|---|---|
| `conscience.Bulldozer 1.0a` | -39.20 | `ScoreMax` | 反撞击移动、碰撞得分。 |
| `jk.nano.Machete 2.0` | -34.61 | `PerfectPrediction` | 追击运动预测枪。 |
| `slugzilla.ButtHead 2.0` | -33.52 | `MovingBulletShielding` | 移动弹盾。 |
| `bwbaugh.nano.Tirunculus 0.0.0a` | -33.48 | `ScoreMax` | 反撞击、APS 决策。 |
| `intruder.PrairieWolf 2.61` | -32.22 | `MovingBulletShielding` | 移动弹盾。 |
| `kc.micro.rammer.MaxRisk 0.6` | -31.52 | `PerfectPrediction` | 追击运动预测枪。 |
| `sheldor.nano.SabreuseNano 1.1` | -30.92 | `PerfectPrediction` | 追击运动预测枪。 |
| `jab.micro.Sanguijuela 0.8` | -30.81 | `PerfectPrediction` | 追击运动预测枪。 |
| `mz.NanoDeath 2.56` | -30.67 | `PerfectPrediction` | 追击运动预测枪。 |
| `mn.nano.perceptual.Impact 1.3.0` | -30.01 | `PerfectPrediction` | 追击运动预测枪。 |

## Phase 0：诊断与基线

- [x] 下载并部署 `mirage-negative-knnpbi` catalog。
- [x] 记录碰撞次数及碰撞责任方。
- [x] 记录每回合最小距离和近距离停留 tick 数。
- [x] 记录最小墙距和敌方最大接近速度。
- [x] 把诊断加入 `mirage.debug`，缺省行为保持不变。
- [x] 为纯统计逻辑增加单元测试。
- [x] 运行 10 对手 `3 次 × 100 回合` 基线。

基线均值：

| 对手 | APS | Survival | dealt/r | taken/r |
|---|---:|---:|---:|---:|
| Bulldozer | 35.47 | 16.67 | 90.78 | 119.98 |
| Machete | 34.44 | 15.33 | 55.31 | 80.89 |
| ButtHead | 34.45 | 15.00 | 54.94 | 78.08 |
| Tirunculus | 47.54 | 47.17 | 78.43 | 82.52 |
| PrairieWolf | 26.96 | 38.67 | 20.01 | 78.13 |
| MaxRisk | 35.07 | 17.67 | 51.88 | 76.35 |
| SabreuseNano | 34.67 | 16.00 | 57.30 | 80.67 |
| Sanguijuela | 34.89 | 16.67 | 52.48 | 76.70 |
| NanoDeath | 39.47 | 29.67 | 55.86 | 70.89 |
| Impact | 38.66 | 26.00 | 63.90 | 81.32 |

## Phase 1：反撞击

- [x] 实现基于 heading、接近速度、横向速度、距离和碰撞历史的 `RamThreat`。
- [x] 使用 latch 防止威胁状态反复切换。
- [x] 保留现有 surfer 对两个环绕方向的评估。
- [x] 对确认的追击者 A/B 测试近距离高命中火力。
- [x] Bulldozer `3 次 × 100 回合` 平均 APS > 50。

本轮不再实现追击路径模拟、路径碰撞成本和 `onHitRobot` 紧急脱离。行为识别加窄范围
高火力已经使 9 个非 PrairieWolf 目标全部超过 60 APS，继续扩展移动层会增加强敌回归面。

## Phase 2：追击运动预测枪

- [x] 确认 6 个 `PerfectPrediction` 目标的平均 APS 分别超过 50。

因此暂缓增加 `RAM_DIRECT`、`RAM_LINEAR`、back-as-front 预测器和移动路径协议。这些能力
只在现有枪仍无法让目标达标时再实施。

## Phase 3：移动弹盾

- [x] 确认 ButtHead 已在 Phase 1 达标，PrairieWolf 仍低于 50 APS。
- [x] 预测最近、确实穿过当前机体的真实敌弹航向。
- [x] 从最低合法威力 0.1 搜索解析拦截方案。
- [x] 每条敌浪最多尝试一次，普通枪在弹盾拥有炮塔时继续学习。
- [x] 没有可靠拦截时回退现有 gun 和 surfer。
- [x] 用跨回合 A/B 选择器限制弹盾只在收益明确时锁定。
- [x] ButtHead 和 PrairieWolf 的平均 APS 分别超过 50。

显式预留下一次拦截的 gun heat 暂缓；当前实现通过真实敌浪优先占用炮塔，已经达到验收线。

## Phase 4：轻量 APS 决策

- [ ] 比较不开火、普通攻击和主动弹盾。
- [ ] 计入双方能量、预计伤害、生存分、击杀奖励和碰撞得分。
- [ ] 只在前述阶段不足以战胜 Bulldozer 或 Tirunculus 时扩展。

前述阶段已经战胜 Bulldozer 和 Tirunculus，本轮不扩展完整 APS 效用规划器。主动弹盾
选择器使用 `dealt - taken + 50 × survived` 作为窄范围的回合级效用近似。

## 验收

- [x] 10 个目标各自 `3 次 × 100 回合` 平均 APS > 50。
- [x] 10 对手等权平均 APS 多次重复方向一致。
- [ ] `classic`、`expert` 和 `roborumble-100` 无一致性回归。
- [x] 所有策略按行为识别，不硬编码对手名称。
- [x] 自适应状态只保留在当前 battle/JVM 内存中。
- [x] `just lint`、`just build` 和 `just deploy mirage` 通过。
- [ ] 发布后复查目标配对 KNNPBI。

## 实施记录

- 2026-07-10：创建清单，开始 Phase 0。
- 2026-07-10：完成 Phase 0。新增接战诊断与单元测试；10 对手 `3 次 × 100 回合`
  基线等权 APS 为 36.76。Bulldozer 5 回合机制样本每回合发生 6～63 次碰撞。
- 2026-07-10：完成反撞击识别与近距离高火力。当前候选中，Bulldozer `3 次 × 100 回合`
  平均 APS 为 70.23。
- 2026-07-10：完成行为门控主动弹盾。拒绝两个回归方案：全局强制弹盾使 6 个强敌等权
  APS 从 60.84 降至 52.41；平行制动使 PrairieWolf APS 降至 9.36、survival 降至 0%。
- 2026-07-10：最终 10 目标 `3 次 × 100 回合` 等权 APS 约为 62.76；各目标平均 APS
  范围为 54.78～70.23，全部超过 50。
- 2026-07-10：最终无 JVM override 的 10 目标单遍复验 APS 为 62.88、survival 为
  84.48%；10 个目标全部超过 50 APS。`just lint`、`just build` 和部署通过。
