package com.example.test922.audio.detector;

import android.content.Context;
import android.util.Log;

import com.example.test922.audio.processor.WavUtils;

import org.pytorch.IValue;
import org.pytorch.LiteModuleLoader;
import org.pytorch.Module;
import org.pytorch.Tensor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * RawNet2 模型的 Deepfake 检测策略实现。
 *
 * 该策略实现了与训练端一致的预处理逻辑：
 * 1. 读取 16kHz 16-bit PCM WAV 文件
 * 2. 归一化到 [-1, 1] 范围
 * 3. Pad/Trim 到固定长度 64000（4秒）
 * 4. 模型推理
 */
public class RawNet2Strategy implements DeepfakeDetector {

    private static final String TAG = "RawNet2Strategy";

    /** 目标音频长度：4秒 * 16000Hz = 64000 采样点 */
    private static final int TARGET_LENGTH = 64000;

    /** PyTorch 模型 */
    private Module mModule;

    @Override
    public boolean loadModel(Context context, String assetPath) {
        try {
            // 从 assets 复制模型到缓存目录
            String modelPath = assetCopy(context, assetPath);
            if (modelPath == null) {
                Log.e(TAG, "无法复制模型文件到缓存目录");
                return false;
            }

            // 加载 PyTorch Lite 模型
            mModule = LiteModuleLoader.load(modelPath);
            Log.i(TAG, "RawNet2 模型加载成功: " + assetPath);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "加载模型失败", e);
            return false;
        }
    }

    @Override
    public float detect(String audioFilePath) {
        if (mModule == null) {
            Log.e(TAG, "模型未加载，请先调用 loadModel()");
            return -1f;
        }

        try {
            // 1. 读取 WAV 文件，得到归一化后的 float 数组
            float[] rawAudio = WavUtils.readWavFile(audioFilePath);
            if (rawAudio == null || rawAudio.length == 0) {
                Log.e(TAG, "读取音频文件失败或文件为空: " + audioFilePath);
                return -1f;
            }

            // 2. Pad 或 Trim 到目标长度
            float[] processedAudio = padOrTrim(rawAudio);

            // 3. 转换为 PyTorch Tensor，Shape: [1, 64000]
            long[] shape = new long[]{1, TARGET_LENGTH};
            Tensor inputTensor = Tensor.fromBlob(processedAudio, shape);

            // 4. 模型推理
            IValue output = mModule.forward(IValue.from(inputTensor));
            Tensor outputTensor = output.toTensor();
            float[] scores = outputTensor.getDataAsFloatArray();

            // 5. 解析输出
            // 假设输出 shape 为 [1, 2]，其中 scores[0] 是 Bonafide 概率，scores[1] 是 Fake 概率
            // 使用 softmax 转换为概率
            if (scores.length >= 2) {
                float[] probs = softmax(scores);
                float fakeProbability = probs[1]; // 返回 Fake 的概率
                Log.d(TAG, "检测完成 - Bonafide: " + probs[0] + ", Fake: " + probs[1]);
                return fakeProbability;
            } else if (scores.length == 1) {
                // 如果模型只输出一个值，假设是 Fake 的 logit，使用 sigmoid
                float fakeProbability = sigmoid(scores[0]);
                Log.d(TAG, "检测完成 - Fake 概率: " + fakeProbability);
                return fakeProbability;
            } else {
                Log.e(TAG, "模型输出格式不正确，scores 长度: " + scores.length);
                return -1f;
            }

        } catch (Exception e) {
            Log.e(TAG, "检测过程出错", e);
            return -1f;
        }
    }

    @Override
    public String getName() {
        return "RawNet2";
    }

    /**
     * 对音频数据进行 Pad 或 Trim 处理，使其长度等于 TARGET_LENGTH。
     *
     * - 如果长度 < TARGET_LENGTH：执行循环填充（Repeat/Tile）
     * - 如果长度 > TARGET_LENGTH：截取前 TARGET_LENGTH 个采样点
     * - 如果长度 == TARGET_LENGTH：直接返回
     *
     * @param rawAudio 原始音频数据
     * @return 处理后长度为 TARGET_LENGTH 的音频数据
     */
    private float[] padOrTrim(float[] rawAudio) {
        if (rawAudio == null || rawAudio.length == 0) {
            return new float[TARGET_LENGTH];
        }

        int originalLength = rawAudio.length;

        if (originalLength == TARGET_LENGTH) {
            // 长度刚好，直接返回副本
            float[] result = new float[TARGET_LENGTH];
            System.arraycopy(rawAudio, 0, result, 0, TARGET_LENGTH);
            return result;
        } else if (originalLength > TARGET_LENGTH) {
            // 长度超过目标，截取前 TARGET_LENGTH 个点
            float[] result = new float[TARGET_LENGTH];
            System.arraycopy(rawAudio, 0, result, 0, TARGET_LENGTH);
            return result;
        } else {
            // 长度不足，循环填充（Repeat/Tile）
            // 例如 [1,2] 填充到 4 变成 [1,2,1,2]
            float[] result = new float[TARGET_LENGTH];
            int pos = 0;
            while (pos < TARGET_LENGTH) {
                int copyLen = Math.min(originalLength, TARGET_LENGTH - pos);
                System.arraycopy(rawAudio, 0, result, pos, copyLen);
                pos += copyLen;
            }
            return result;
        }
    }

    /**
     * Softmax 函数，将 logits 转换为概率
     */
    private float[] softmax(float[] logits) {
        float maxLogit = Float.NEGATIVE_INFINITY;
        for (float logit : logits) {
            if (logit > maxLogit) maxLogit = logit;
        }

        float sumExp = 0f;
        float[] expValues = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            expValues[i] = (float) Math.exp(logits[i] - maxLogit);
            sumExp += expValues[i];
        }

        float[] probs = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            probs[i] = expValues[i] / sumExp;
        }
        return probs;
    }

    /**
     * Sigmoid 函数
     */
    private float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    /**
     * 从 assets 复制文件到应用缓存目录
     */
    private String assetCopy(Context context, String assetPath) {
        File cacheFile = new File(context.getCacheDir(), assetPath);

        // 如果缓存文件已存在且有效，直接返回
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile.getAbsolutePath();
        }

        // 确保父目录存在
        File parentDir = cacheFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (InputStream is = context.getAssets().open(assetPath);
             FileOutputStream fos = new FileOutputStream(cacheFile)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            fos.flush();

            return cacheFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "复制 asset 文件失败: " + assetPath, e);
            return null;
        }
    }
}

