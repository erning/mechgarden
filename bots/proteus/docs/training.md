# Proteus 离线训练流程（M8）

> 目标：KNN 枪的嵌入权重（`Features.WEIGHTS`）不靠手调，而是离线学习后回
> 读为常量。方法对齐 BeepBoop 作者发表的流程：以「敌人实际落点的 GF」为
> 目标分布，对 KNN 核密度预测做交叉熵，梯度下降优化逐维嵌入权重；忽略
> 「改嵌入会改近邻集合」的二阶效应（近邻集合每轮重算一次）。

## 闭环

1. **采集**（机器人侧，`zen.proteus.diag.Dataset`）：把 `ENABLED` 本地改为
   `true`（**绝不要提交 true**），`just deploy proteus`，然后：
   ```bash
   python3 scripts/collect_dataset.py -c basic classic -n 35
   ```
   每场结束后脚本把 `robots/.data` 里的 .pgf 转移到 `.cache/proteus-datasets/`
   （引擎数据配额 200KB 会累加目录内全部文件，不能原地积累）。每条样本 =
   11 维特征 + 实际覆盖的 GF 区间（float32）。
2. **训练**（不进 robot jar，`src/train` source set）：
   ```bash
   ./gradlew :bots:proteus:trainEmbedding --args="--epochs 24"
   ```
   输出最终权重数组与损失轨迹。有效信号：损失单调缓慢下降且低于初始值；
   爆炸/NaN 说明学习率过大（调 `--lr`）。
3. **回读**：把输出权重粘进 `zen/proteus/aim/Features.kt` 的 `WEIGHTS`（带
   来源注释），**重新部署**。
4. **验证**：`just duel -r Proteus -c basic -n 35` 与 `-c classic -n 35` 各
   2~3 局取平均，与回读前对比。只在有稳定提升时提交权重。

## 工程细节

- 目标函数：loss = −log Σ_j α_j·1[|gf_i − c_j| < 0.1]，α = softmax(−d/T)，
  d 为加权曼哈顿距离；按批累积梯度后乘性更新（log 空间保持权重为正），
  每轮把权重总和归一（嵌入是尺度无关的）。
- 配额与流限制（踩过的坑）：Robocode 数据目录配额 200KB（统计目录内已有
  文件）、同时打开的流 ≤ 5、机器人每回合重建——所以导出是「内存缓冲 +
  战斗结束落盘」，而不是长开流。
- 采集目录不能直接用 `robocode/robots/`：大目录会让个别 jar 解析为
  invalid robot（`just duel` 的临时目录又把 .data 清掉）。采集脚本按
  pairing 建最小 robots 目录，场后收割 .data。

## 当前状态（2026-07-21）

- 数据集：basic + classic 5 个对手 × 35 回合 ≈ 15k 样本。
- 24 个 epoch：损失 1.4267 → 1.3897（−2.6%）；回读权重后 basic/classic
  与手调权重在噪声内打平（第一次学习的嵌入保留在 `Features.WEIGHTS`）。
- 下一步：更多对手与样本（expert/top 也采）、按对手分训（专家模型）、危
  险模型权重同样离线化。
