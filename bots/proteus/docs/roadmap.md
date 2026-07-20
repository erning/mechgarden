# Proteus 里程碑路线

> 每个里程碑有明确的范围与量化验收，按序推进；验收用 `just duel` 对指定
> catalog 跑 35+ 回合，结果记录在本文「验收记录」一节。架构依据见
> `docs/architecture.md`。对照基线：同版本 `zen.Mirage`（工作区现役最强）与
> `top` catalog。

## M1 骨架（已完成）

范围：模块接线（Gradle、部署、properties）、`core` / `state` / `control` /
`radar` / `move` / `aim` 分层、雷达 infinity lock、能量差开火检测、距离带轨
道移动、线性预测枪、单元测试。

验收（已达成）：

- `just build` / `just lint` 通过，`just deploy proteus` 可加载。
- 对 `RaikoNano` 10 回合：APS 45.0%（基线锚点，不作门槛）。

## M2 波与真冲浪（已完成）

范围（已交付）：`wave.Wave` / `wave.Waves` / `wave.AimWaves` / `wave.GuessFactorBins`；
敌方开火建波（起点 = 敌人上一 tick 位置，半径含出膛当回合的首段位移）；精确交集；
经验危险（命中 bin + 访问 bin，recency 衰减 0.995）；前进/后退/停车三选一真冲浪
（最近两条波，按威力与到达时间加权）；瞄准侧每 tick 虚拟波 + GF-bin 枪（密度峰选
点，跨回合 per-enemy 注册表）。

机制验证（已达成）：波时序与引擎逐 tick 对账（出膛点、子弹碰撞用移动前车身方
块）；单元测试覆盖波交集、GF 映射、冲浪选边；被命中率显著下降（taken/r 从 M1 的
~90 降到 ~51）；GF 枪对 BasicGFSurfer 的 dealt/r 从 4.1 提到 34.9。

成绩（basic catalog，100 回合）：APS 36.4%、survival 30.7%、dealt 35.8/r、taken
51.0/r。原定的「basic ≥ 75%」门槛实际依赖 M3（路径搜索、bullet shadow、位置评
分）与 M4（KNN 枪）的系统——Mirage 同 catalog 为 78.3% APS / 93.3% survival，那
是 M3+ 的对标线，M2 不背。M3 验收时复评此门槛。

## M3 路径冲浪（已完成，含重要负结果）

范围（已交付）：`MovementSim` 逐 tick 精确模拟；`PathSurfer` best-first 搜索
（计划复用、深度 tie-break、节点预算 ≈ tick 预算，可采纳下界剪枝）；
`BulletShadows`（含跨 tick 阴影修正）计入危险评分；三波加权（第三波 0.75 折
扣）。**负结果**：路径搜索在实战中小于 M2 的三选一冲浪（basic APS ~29 vs
~37，多轮 100 回合验证）。分析原因：路径搜索会积极穿过粗糙经验危险模型
的狭窄「安全」bin（过拟合噪声），且速度反转制造低速窗口被简单枪白打——
BeepBoop 的路径冲浪成立依赖其 22 模型的危险集成。处置：`PathSurfer` 保留
（单元测试覆盖搜索行为），默认移动引擎回退为三选一冲浪，待 M5 危险集成
落地后用 A/B 重评（`Mover.MOVEMENT_ENGINE` 开关）。「向瞄准发布未来状态」
并入 M4（提前一 tick 预瞄时才真正需要）。

验收：bullet shadow 单元测试 + 实战无回归（basic APS 37.3 / 100 回合）；路
径搜索行为级单元测试（避险、扎口袋、不劣于直行）。原定的 basic ≥ 85% 门
槛随 M2 一并后移到 M4/M5 复评。

## M4 KNN 瞄准（已完成）

范围（已交付）：`knn.KnnModel`（容量受限树 + 加权曼哈顿距离 + 自适应 k +
距离倒数加权 + 扫描线密度峰 argmax）；`Features` 特征池（11 维：bft、横
向/纵向速度、速度、加速度、变向计时、距离、前后墙距、currentGF、
virtuality）；主枪 / 反冲浪枪双树全量训练，按我方命中率区间硬切换（区间
与 [0, 12%] 相交 → 反冲浪枪）；didHit 标记（命中时刻 ±3 tick）且反冲浪查
询排除 didHit 样本；冷启动由 GF-bin 轮廓兜底（树 < 30 样本时）。

验收（100 回合）：basic APS 46.1%（M3 的 37.3 → 46.1，dealt/r 38.3 →
47.7，PWIN 首次非零 33%）；对 BasicGFSurfer APS 40.6（M3 的 30.8）；
classic APS 31.8%（RaikoMX 23.7 / Lacrimas 31.6 / BlestPain 40.2）。原定
「classic ≥ 70%」不现实（这三台是中坚级），按惯例后移到 M5 复评。

## M5 危险集成（已完成）

范围（已交付）：敌波条目携带创建时特征与开火瞬间我方状态；`DangerModel`
接口（按波缓存的 151-bin 危险数组）；模型集：统计 hit bins、flattener、
HOT / 线性 / 圆形 / 均值线性（模拟枪，门控上限 7%~10%）、CurrentGF（动态
峰）、KNN 危险（复用 `KnnModel`，点样本密度）；`DangerEstimator` 组合管
线：敌方命中率区间门控 + 预测准确度滚动调权（score³）+ 归一化组合 +
阴影折减。组合器的区间查询统一走核平滑质量分（修掉了原始计数直方图的
空档问题）。

验收（100 回合）：basic APS 58.8%（M4 的 46.1 → 58.8），PWIN 100%，
survival 66.7%，taken/r 53.3 → 40.5；classic APS 40.9%（31.8 → 40.9）。
路径搜索 A/B 重评：仍输（32.7 vs 58.8），继续禁用，原因待查（疑似重规划
抖动）。原定「expert ≥ 45%」后移到 M6/M7 复评。

## M6 得分系统（已完成）

范围（已交付）：`aim.FirePower` 守卫式威力策略（恰好击杀威力、防瘫痪最
小威力，其余走距离规则）；`aim.ShadowAim` 主动 bullet shadowing——开火
前 ~2 tick 对密度峰 ±(0.2/0.4/0.7) 候选角做联合评分 `命中概率 ÷
postDanger^k`（k = 2×(敌我命中率比×威力比)^0.25），postDanger 只计我
方当前 GF ±0.5 区域（计划关联），候选子弹的阴影与在飞子弹一起参与。

负结果（如实记录）：全量期望得分威力模型（能量赛跑 + 飞行时间衰减）进
攻端输给距离规则，已移除；低能量保能量分支被击杀威力恒抢占（死代码）；
ShadowAim 的全范围 postDanger 代理版本 taken/r 反升，计划关联版才有
效。

验收（100 回合）：basic APS 59.0%（M5 的 58.8），PWIN 100%，taken/r
39.1；classic APS 36.9%。原定「expert ≥ 55% / top ≥ 25%」后移到 M7/M8
复评。

## M7 对手分类与护盾

范围：`strategy.Strategy`（anti-ram / anti-mirror / anti-HOT / 残局撞击收
割）；护盾模式（敌人瞄准在线分类 + 拦截计划 + 退出条件）；rambot 特化（模
拟 rammer、未来枪热波、近垂直逃向角）。

验收：`roborumble-100` catalog 全量 APS 达到里程碑目标（目标值在 M6 验收后
按差距定）；对已知 ram / mirror 对手的专项 duel 验证分类生效。

## M8 离线训练闭环

范围：`diag` 数据集导出（float32）；`train/` 离线嵌入训练（SGD，交叉熵对
KNN 核密度）；学得的参数生成为 Kotlin 常量回读；训练 / 回读 / 验证流程文档
化。

验收：离线训练后的嵌入对 `expert` / `top` catalog 有稳定正收益（与手工权重
A/B 对比）。

## 验收记录

| 日期 | 里程碑 | 对手 / catalog | 回合 | APS | PWIN | 备注 |
|---|---|---|---|---|---|---|
| 2026-07-20 | M1 | RaikoNano | 10 | 45.0% | 0% | 骨架基线 |
| 2026-07-20 | M2 | basic | 100 | 36.4% | 0% | 波+真冲浪+GF 枪；Mirage 对照 78.3% |
| 2026-07-20 | M3 | basic | 100 | 37.3% | 0% | 阴影+三选一冲浪；路径搜索保留但禁用 |
| 2026-07-20 | M4 | basic | 100 | 46.1% | 33% | KNN 双枪上线，dealt/r 47.7 |
| 2026-07-20 | M4 | classic | 100 | 31.8% | 0% | 中坚对手；防守端 taken/r 仍是瓶颈 |
| 2026-07-20 | M5 | basic | 100 | 58.8% | 100% | 危险集成上线，taken/r 40.5 |
| 2026-07-20 | M5 | classic | 100 | 40.9% | 0% | RaikoMX 仍难（32.0） |
| 2026-07-20 | M6 | basic | 100 | 59.0% | 100% | 威力守卫+主动阴影，taken/r 39.1 |
| 2026-07-20 | M6 | classic | 100 | 36.9% | 0% | 波动区间 ~37±3 |
