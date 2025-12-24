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
    public boolean loadModel(Context context, String assetName) {
        try {
            // 从 assets 复制模型到私有目录，获取绝对路径
            String modelPath = assetFilePath(context, assetName);
            if (modelPath == null) {
                Log.e(TAG, "无法复制模型文件到私有目录");
                return false;
            }

            // 加载 PyTorch Lite 模型
            mModule = LiteModuleLoader.load(modelPath);
            Log.i(TAG, "RawNet2 模型加载成功: " + assetName);
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
            Log.d(TAG, "读取音频成功，原始长度: " + rawAudio.length);

            // 2. Pad 或 Trim 到目标长度
            float[] processedAudio = padOrTrim(rawAudio);
            Log.d(TAG, "Pad/Trim 后长度: " + processedAudio.length);

            // 3. 转换为 PyTorch Tensor，Shape: [1, 64000]
            long[] shape = new long[]{1, TARGET_LENGTH};
            Tensor inputTensor = Tensor.fromBlob(processedAudio, shape);

            // 4. 模型推理
            IValue output = mModule.forward(IValue.from(inputTensor));
            Tensor outputTensor = output.toTensor();
            float[] scores = outputTensor.getDataAsFloatArray();

            Log.d(TAG, "模型输出长度: " + scores.length);
            for (int i = 0; i < scores.length; i++) {
                Log.d(TAG, "scores[" + i + "] = " + scores[i]);
            }

            // 5. 解析输出
            // 输出 shape 为 [1, 2]，是 Logits
            // index 0: Fake (伪造) 的 logit
            // index 1: Real (真实) 的 logit
            // 返回 Real 的概率
            if (scores.length >= 2) {
                float[] probs = softmax(scores);
                float realProbability = probs[1]; // index 1 是 Real
                Log.d(TAG, "检测完成 - Fake: " + String.format("%.4f", probs[0])
                        + ", Real: " + String.format("%.4f", probs[1]));
                return realProbability;
            } else if (scores.length == 1) {
                // 如果模型只输出一个值，假设是 Real 的 logit，使用 sigmoid
                float realProbability = sigmoid(scores[0]);
                Log.d(TAG, "检测完成 - Real 概率: " + String.format("%.4f", realProbability));
                return realProbability;
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
     * - 如果长度 < TARGET_LENGTH：执行循环填充（Loop/Tile），不补零
     * - 如果长度 > TARGET_LENGTH：截取前 TARGET_LENGTH 个采样点
     * - 如果长度 == TARGET_LENGTH：直接返回副本
     *
     * @param rawAudio 原始音频数据
     * @return 处理后长度为 TARGET_LENGTH 的音频数据
     */
    private float[] padOrTrim(float[] rawAudio) {
        if (rawAudio == null || rawAudio.length == 0) {
            // 边界情况：返回静音数据
            Log.w(TAG, "padOrTrim: 输入为空，返回静音数据");
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
            Log.d(TAG, "Trim: " + originalLength + " -> " + TARGET_LENGTH);
            return result;
        } else {
            // 长度不足，循环填充（Loop/Tile）
            // 例如 [1,2] 填充到 4 变成 [1,2,1,2]
            float[] result = new float[TARGET_LENGTH];
            int pos = 0;
            while (pos < TARGET_LENGTH) {
                int copyLen = Math.min(originalLength, TARGET_LENGTH - pos);
                System.arraycopy(rawAudio, 0, result, pos, copyLen);
                pos += copyLen;
            }
            Log.d(TAG, "Pad (Loop): " + originalLength + " -> " + TARGET_LENGTH);
            return result;
        }
    }

    /**
     * Softmax 函数，将 logits 转换为概率
     * 使用数值稳定的实现（减去最大值）
     */
    private float[] softmax(float[] logits) {
        // 找到最大值以保持数值稳定性
        float maxLogit = Float.NEGATIVE_INFINITY;
        for (float logit : logits) {
            if (logit > maxLogit) maxLogit = logit;
        }

        // 计算 exp(logit - max) 并求和
        float sumExp = 0f;
        float[] expValues = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            expValues[i] = (float) Math.exp(logits[i] - maxLogit);
            sumExp += expValues[i];
        }

        // 归一化得到概率
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
     * 将 assets 中的文件复制到 App 私有目录，并返回绝对路径。
     * PyTorch 的 LiteModuleLoader.load() 需要绝对文件路径，不能直接读 assets。
     *
     * @param context   Android Context
     * @param assetName assets 中的文件名
     * @return 复制后的文件绝对路径，失败时返回 null
     */
    private String assetFilePath(Context context, String assetName) {
        // 使用 getFilesDir() 获取持久化的私有目录
        File file = new File(context.getFilesDir(), assetName);

        // 如果文件已存在且大小大于 0，直接返回路径（避免重复复制）
        if (file.exists() && file.length() > 0) {
            Log.d(TAG, "模型文件已存在，跳过复制: " + file.getAbsolutePath());
            return file.getAbsolutePath();
        }

        // 确保父目录存在
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            boolean created = parentDir.mkdirs();
            if (!created) {
                Log.w(TAG, "创建父目录失败: " + parentDir.getAbsolutePath());
            }
        }

        // 从 assets 复制到私有目录
        try (InputStream is = context.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(file)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytes = 0;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }
            fos.flush();

            Log.i(TAG, "模型文件复制成功: " + file.getAbsolutePath()
                    + " (" + (totalBytes / 1024) + " KB)");
            return file.getAbsolutePath();

        } catch (IOException e) {
            Log.e(TAG, "复制 asset 文件失败: " + assetName, e);
            return null;
        }
    }
}
