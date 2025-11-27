# 项目说明（音频提取与预处理）

本项目目标：在安卓端将输入视频/音频统一转为标准 WAV（PCM 16-bit LE，单声道，目标 16kHz），再进行轻量预处理以供后续特征与识别模型使用。

- 当前状态（与原设计差异）
  - 新增依赖：`com.antonkarpenko:ffmpeg-kit-full-gpl:2.0.1`（社区分支）
  - 音频提取：先尝试 FFmpeg（最小化命令），失败则纯解码兜底，再失败自动切换 Android 原生 MediaExtractor+MediaCodec 解码直出 WAV
  - 预处理：若输入采样率≠16kHz，使用 TarsosDSP RateTransposer 重采样到 16kHz；随后增益(0.9)、预加重、分帧+汉明窗，返回帧特征与最终采样率
  - 多轨：原生解码路径已实现语言优选（zh/zho/chi 优先），且预留“用户选择音轨”的 UI 分支（待开发）

## 依赖与库
- TarsosDSP（Android 打包版，位于项目 libs 与 TarsosDSP-Android-latest 源码目录）
- ffmpeg-kit 社区分支：`com.antonkarpenko:ffmpeg-kit-full-gpl:2.0.1`（经验证存在滤镜运行期限制，已内置兜底方案）
- Android 原生：MediaExtractor / MediaCodec

## 提取流程（AudioExtractor）
- 尝试A（FFmpeg最小化）：`-vn -sn -dn -ac 1 -ar 16000 -c:a pcm_s16le` 输出 WAV
- 兜底B（FFmpeg纯解码）：`-vn -sn -dn -c:a pcm_s16le -f wav`（不改声道/采样率，彻底避开滤镜构建/重建）
- 兜底C（原生解码）：MediaExtractor+MediaCodec 解码为 PCM；若立体声则在 Java 层均值下混为单声道；写入 WAV 头（TarsosDSP WaveHeader）
- 多轨优选：
  - 扫描所有音轨，优先选择语言包含 zh/zho/chi 的轨道
  - 若多轨且未命中中文，暂取第一个；日志提示“TODO: 弹出UI供用户选择音轨”（待开发 UI）

说明：部分设备/输入在该 ffmpeg-kit 变体上，运行期修改滤镜图（声道/采样率）会报错（如 `all_channel_counts 不是运行期选项`、`anull/pan not connected`）。因此保留纯解码与原生解码兜底，确保稳定产出 WAV。

## 预处理流程（AudioPreprocessor）
- 读取 WAV 头（TarsosDSP WaveHeader）。若解析失败，回退：跳过 44 字节并按 16k/16bit/单声道继续
- 若输入采样率≠16kHz，则 `RateTransposer(factor = 16000/originalSR)` 重采样至 16kHz
- 增益（0.9）→ 预加重（x[n] = x[n] − 0.97·x[n−1]）→ 分帧+汉明窗（frame=512, overlap=0）
- 返回：`onPreprocessingSuccess(file, frames, sampleRate)`，其中 sampleRate 若重采样则为 16k

## 已知问题与简易替代方案
- FFmpeg 功能失败是否是分支包问题？
  - 结论：是该分支（或其底层 FFmpeg 构建）在“运行期滤镜修改”方面的兼容性限制所致，并非命令语法错误
- 除了换包，还有哪些简单办法？
  - 避免在 FFmpeg 中做重采样/下混，改用“纯解码为 WAV”，并在 Java/TarsosDSP 侧完成重采样与下混（本项目已采用，最稳）
  - 使用 Android 原生解码（已内置兜底），确保任意可解码音轨均能导出 WAV
  - 如果仍想强制 FFmpeg 重采样，可尝试仅 `-ar`/`-ac` 不使用任何滤镜与 map，但在该变体上依然可能触发内部滤镜重建导致失败

## 待做（TODO）
- 增加“用户选择多轨 UI”：当存在多个音轨时弹窗展示语言/标识与轨索引，允许用户手选；默认仍按 zh/main/chinese 等优选策略自动选中
- 可选：在提取完成后提供“落地 16kHz WAV”导出（当前仅特征以 16kHz 输出）

## 注意事项
- TarsosDSP-Android-latest 与 ffmpeg-kit 的源码与二进制均在项目目录中可查（libs/ 与 TarsosDSP-Android-latest/、ffmpeg-kit-full-gpl-2.0.1-sources/），遇到兼容性问题可对照源码定位
- 若在模拟器（x86/x86_64）上测试，建议优先真机验证原生解码路径；FFmpeg 相关行为与设备架构/ROM 构建相关

## 运行与调试
- 选择视频/音频 → 点击“提取音频”：若 FFmpeg 失败，自动走原生解码兜底；日志（tag: AudioExtractor）会打印失败尾部
- 点击“预处理音频”：输出帧特征与最终采样率（若重采样则为 16k）
