package com.example.test922.ui.adapter;

import android.graphics.Color;

import java.util.Locale;

/**
 * 批量检测结果数据模型
 */
public class BatchResultItem {
    private final String fileName;
    private final String filePath;
    private float realProbability;  // -1 表示检测中，-2 表示失败
    private boolean isProcessing;
    private long detectionTimeMs;

    public BatchResultItem(String fileName, String filePath) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.realProbability = -1f;
        this.isProcessing = true;
        this.detectionTimeMs = 0;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public float getRealProbability() {
        return realProbability;
    }

    public void setRealProbability(float realProbability) {
        this.realProbability = realProbability;
        this.isProcessing = false;
    }

    public void setResult(float realProbability, long detectionTimeMs) {
        this.realProbability = realProbability;
        this.detectionTimeMs = detectionTimeMs;
        this.isProcessing = false;
    }

    public boolean isProcessing() {
        return isProcessing;
    }

    public void setFailed() {
        this.realProbability = -2f;
        this.isProcessing = false;
    }

    public boolean isFailed() {
        return realProbability == -2f;
    }

    /**
     * 获取结果显示文本
     */
    public String getResultText() {
        if (isProcessing) {
            return "检测中...";
        } else if (isFailed()) {
            return "检测失败";
        } else {
            float realPercent = realProbability * 100;
            if (realProbability > 0.5) {
                return String.format(Locale.US, "✅ 真实 %.1f%% (%dms)", realPercent, detectionTimeMs);
            } else {
                return String.format(Locale.US, "⚠️ 伪造 %.1f%% (%dms)", 100 - realPercent, detectionTimeMs);
            }
        }
    }

    /**
     * 获取结果颜色
     */
    public int getResultColor() {
        if (isProcessing) {
            return Color.GRAY;
        } else if (isFailed()) {
            return Color.RED;
        } else if (realProbability > 0.5) {
            return Color.parseColor("#4CAF50"); // 绿色
        } else {
            return Color.parseColor("#FF9800"); // 橙色
        }
    }
}
