package com.example.test922.audio.detector;

import android.content.Context;

/**
 * Deepfake 检测策略接口。
 * 定义了不同检测模型（如 RawNet2、AASIST）的统一接口。
 */
public interface DeepfakeDetector {

    /**
     * 加载模型资源
     *
     * @param context   Android Context
     * @param assetPath 模型文件在 assets 中的路径
     * @return 加载成功返回 true，否则返回 false
     */
    boolean loadModel(Context context, String assetPath);

    /**
     * 执行检测
     *
     * @param audioFilePath WAV 文件的绝对路径（16kHz, 16-bit PCM, Mono）
     * @return Fake 的概率 (0.0 - 1.0)，值越高表示越可能是伪造音频
     */
    float detect(String audioFilePath);

    /**
     * 获取策略名称
     *
     * @return 策略/模型名称
     */
    String getName();
}

