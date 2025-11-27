# 真人声音 vs AI合成语音识别 - 安卓端项目架构方案
注意!每一次行动前请务必阅读本文件!每一次行动后请务必更新本文件!每一次修改都只进行一步操作!

## 当前状态（与原设计差异）
（来自 instruction.md，覆盖原有冲突描述）
- 新增依赖：`com.antonkarpenko:ffmpeg-kit-full-gpl:2.0.1`（社区分支）
- 音频提取：先尝试 FFmpeg（最小化命令），失败则纯解码兜底，再失败自动切换 Android 原生 MediaExtractor+MediaCodec 解码直出 WAV
- 预处理：若输入采样率≠16kHz，使用 TarsosDSP RateTransposer 重采样到 16kHz；随后增益(0.9)、预加重、分帧+汉明窗，返回帧特征与最终采样率
- 多轨：原生解码路径已实现语言优选（zh/zho/chi 优先），并预留“用户选择音轨”的 UI 分支（待开发）

## 🎯 项目概述

**核心目标**：在现有视频提取WAV功能基础上，增加AI语音检测能力
**输入源**：从视频中提取的WAV音频文件
**核心功能**：音频特征提取 + 机器学习分类 + 结果显示

## 依赖与库（当前实现）
- TarsosDSP（Android 打包版，位于项目 libs 与 TarsosDSP-Android-latest 源码目录）
- ffmpeg-kit 社区分支：`com.antonkarpenko:ffmpeg-kit-full-gpl:2.0.1`（在“运行期滤镜修改”上有限制，已内置兜底方案）
- Android 原生：MediaExtractor / MediaCodec

## 📱 技术选型（总体规划）

| 组件 | 技术方案 | 理由 |
|------|----------|------|
| 音频处理 | TarsosDSP Java库 | 纯Java实现，功能全面 |
| 特征工程 | MFCC + 频谱特征 + 相位导数统计 | 平衡效果与计算复杂度 |
| 机器学习 | TensorFlow Lite Java API | 移动端优化，纯Java支持 |
| 开发语言 | Java | 与现有代码保持一致 |
| 架构模式 | MVC + 模块化 | 与现有结构兼容 |

## ⚙️ 核心参数配置（以实际实现为准）

```java
// 音频处理参数
SAMPLE_RATE = 16000;          // 统一采样率
CHANNEL_CONFIG = MONO;        // 单声道处理
FRAME_LENGTH = 512;           // 32ms帧长
FRAME_OVERLAP = 256;          // 50%重叠

// 特征提取参数
MFCC_COEFFICIENTS = 13;       // MFCC系数
MEL_FILTERS = 26;             // 梅尔滤波器
FEATURE_AGGREGATION = STATISTICAL; // 统计聚合
```

## 提取流程（AudioExtractor，当前实现）
- 尝试A（FFmpeg最小化）：`-vn -sn -dn -ac 1 -ar 16000 -c:a pcm_s16le` 输出 WAV
- 兜底B（FFmpeg纯解码）：`-vn -sn -dn -c:a pcm_s16le -f wav`（不改声道/采样率，彻底避开滤镜构建/重建）
- 兜底C（原生解码）：MediaExtractor+MediaCodec 解码为 PCM；若立体声则在 Java 层均值下混为单声道；写入 WAV 头（TarsosDSP WaveHeader）
- 多轨优选：
  - 扫描所有音轨，优先选择语言包含 zh/zho/chi 的轨道
  - 若多轨且未命中中文，暂取第一个；日志提示“TODO: 弹出UI供用户选择音轨”（待开发 UI）

说明：部分设备/输入在该 ffmpeg-kit 变体上，运行期修改滤镜图（声道/采样率）会报错（如 `all_channel_counts 不是运行期选项`、`anull/pan not connected`）。因此保留纯解码与原生解码兜底，确保稳定产出 WAV。

## 预处理流程（AudioPreprocessor，当前实现）
- 读取 WAV 头（TarsosDSP WaveHeader）。若解析失败，回退：跳过 44 字节并按 16k/16bit/单声道继续
- 若输入采样率≠16kHz，则 `RateTransposer(factor = 16000/originalSR)` 重采样至 16kHz
- 增益（0.9）→ 预加重（x[n] = x[n] − 0.97·x[n−1]）→ 分帧+汉明窗（frame=512, overlap=0）
- 返回：`onPreprocessingSuccess(file, frames, sampleRate)`，其中 sampleRate 若重采样则为 16k

## 已知问题与简易替代方案（来自 instruction.md）
- FFmpeg 功能失败是否是分支包问题？
  - 结论：是该分支（或其底层 FFmpeg 构建）在“运行期滤镜修改”方面的兼容性限制所致，并非命令语法错误
- 除了换包，还有哪些简单办法？
  - 避免在 FFmpeg 中做重采样/下混，改用“纯解码为 WAV”，并在 Java/TarsosDSP 侧完成重采样与下混（本项目已采用，最稳）
  - 使用 Android 原生解码（已内置兜底），确保任意可解码音轨均能导出 WAV
  - 如果仍想强制 FFmpeg 重采样，可尝试仅 `-ar`/`-ac` 不使用任何滤镜与 map，但在该变体上依然可能触发内部滤镜重建导致失败

## 🏗️ 项目结构扩展（规划）

在现有结构基础上新增（后续阶段）：

```
app/src/main/java/com/example/test922/
├── audio/
│   ├── processor/
│   │   ├── AudioPreprocessor.java      # 音频预处理
│   │   ├── FeatureExtractor.java       # 特征提取主类
│   │   └── FeatureAggregator.java      # 特征聚合
│   ├── features/
│   │   ├── MFCCExtractor.java          # MFCC特征
│   │   ├── SpectralFeatureExtractor.java # 频谱特征
│   │   ├── PhaseFeatureExtractor.java  # 相位特征
│   │   └── TimeDomainExtractor.java    # 时域特征
│   └── model/
│       ├── AudioClassifier.java        # 分类器封装
│       ├── ModelManager.java           # 模型管理
│       └── ClassificationResult.java   # 结果封装
├── ui/
│   ├── AudioAnalysisActivity.java      # 音频分析界面
│   └── ResultDisplayFragment.java      # 结果显示
└── util/
    ├── AudioUtils.java                 # 音频工具类
    └── FeatureUtils.java               # 特征工具类
```

## 🔧 详细实现步骤

### 阶段1: 音频预处理模块

#### 任务1.1: 音频标准化处理
**输入**: 从视频提取的WAV文件
**输出**: 标准化音频数据
- 采样率统一至16kHz

- 音量归一化处理
- 预加重滤波

**测试点**: 输出音频格式统一、音质保持、处理时间可接受

#### 任务1.2: 分帧加窗处理
**输入**: 标准化音频数据
**输出**: 分帧后的音频帧列表
- 固定长度分帧
- 汉明窗应用
- 重叠帧处理

**测试点**: 帧长度一致、窗函数正确应用、边界处理正常
未实现流程:
### 阶段2: 特征提取模块

#### 任务2.1: MFCC特征提取
**输入**: 加窗后的音频帧
**输出**: MFCC系数序列
- FFT计算
- 梅尔滤波器组应用
- 对数能量计算
- DCT变换得到MFCC

**测试点**: 系数维度正确、数值范围合理、与标准工具一致性

#### 任务2.2: 频谱特征提取
**输入**: 频谱数据
**输出**: 频谱特征向量
- 谱质心计算
- 谱滚降点检测
- 谱通量分析
- 频谱熵计算

**测试点**: 特征值在合理范围、对音频变化敏感

#### 任务2.3: 时域特征提取
**输入**: 时域音频帧
**输出**: 时域特征向量
- 短时能量计算
- 过零率统计
- 幅度统计特征

**测试点**: 计算准确、数值稳定、实时性达标

#### 任务2.4: 相位特征提取
**输入**: FFT复数结果
**输出**: 相位统计特征
- 相位导数计算
- 统计特征提取（均值、方差、偏度、峰度）
- 相位变化模式分析

**测试点**: 相位导数计算正确、统计特征有意义、时间偏移不变性

### 阶段3: 特征后处理模块

#### 任务3.1: 特征聚合
**输入**: 多帧特征序列
**输出**: 固定维度特征向量
- 帧特征统计聚合
- 维度统一化
- 特征序列压缩

**测试点**: 输出维度固定、信息保留充分、聚合策略有效

#### 任务3.2: 特征标准化
**输入**: 原始特征向量
**输出**: 标准化特征向量
- 均值方差归一化
- 数值范围缩放
- 异常值处理

**测试点**: 特征分布标准化、数值稳定性、模型兼容性

### 阶段4: 模型推理模块

#### 任务4.1: TFLite模型集成
**输入**: 标准化特征向量
**输出**: 分类置信度
- 模型文件加载
- 输入输出Tensor配置
- 推理执行
- 结果解析

**测试点**: 模型加载成功、推理时间可接受、内存使用稳定

#### 任务4.2: 结果后处理
**输入**: 原始推理结果
**输出**: 格式化分类结果
- 置信度计算
- 阈值判断
- 结果格式化
- 置信区间估计

**测试点**: 结果格式统一、阈值设置合理、置信度准确

### 阶段5: 系统集成模块

#### 任务5.1: 处理流水线集成
**输入**: WAV文件路径
**输出**: 分类结果对象
- 模块间数据流转
- 异常处理机制
- 进度回调通知
- 资源管理

**测试点**: 端到端流程通畅、异常情况处理、资源释放完整

#### 任务5.2: 结果显示集成
**输入**: 分类结果对象
**输出**: 界面显示更新
- 结果可视化
- 特征可视化
- 置信度显示
- 历史记录管理

**测试点**: 显示信息准确、界面响应及时、用户体验良好

## 🔮 双谱特征预留设计

为后续可能添加的双谱特征预留扩展点：

1. **接口预留**: `BispectralFeatureExtractor` 接口定义
2. **数据流预留**: 特征向量中预留双谱特征位置
3. **计算资源预留**: 处理时间预算中考虑双谱计算开销
4. **配置可扩展**: 特征选择配置支持动态调整

## 注意事项（来自 instruction.md）
- TarsosDSP-Android-latest 与 ffmpeg-kit 的源码与二进制均在项目目录中可查（libs/ 与 TarsosDSP-Android-latest/、ffmpeg-kit-full-gpl-2.0.1-sources/），遇到兼容性问题可对照源码定位
- 若在模拟器（x86/x86_64）上测试，建议优先真机验证原生解码路径；FFmpeg 相关行为与设备架构/ROM 构建相关

## 运行与调试（来自 instruction.md）
- 选择视频/音频 → 点击“提取音频”：若 FFmpeg 失败，自动走原生解码兜底；日志（tag: AudioExtractor）会打印失败尾部
- 点击“预处理音频”：输出帧特征与最终采样率（若重采样则为 16k）
