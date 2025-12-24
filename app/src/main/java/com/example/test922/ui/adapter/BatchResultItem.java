package com.example.test922.ui.adapter;

/**
 * 批量检测结果数据模型
 */
public class BatchResultItem {
    private String fileName;
    private String filePath;
    private float realProbability;  // -1 表示检测中，-2 表示失败
    private boolean isProcessing;

    public BatchResultItem(String fileName, String filePath) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.realProbability = -1f;
        this.isProcessing = true;
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

    public boolean isProcessing() {
        return isProcessing;
    }

    public void setProcessing(boolean processing) {
        isProcessing = processing;
    }

    public void setFailed() {
        this.realProbability = -2f;
        this.isProcessing = false;
    }

    public boolean isFailed() {
        return realProbability == -2f;
    }

    /**
     * 获取显示用的结果文本
     */
    public String getResultText() {
        if (isProcessing) {
            return "⏳ 检测中...";
        } else if (isFailed()) {
            return "❌ 失败";
        } else {
            if (realProbability > 0.5f) {
                return String.format("✅ 真实 %.0f%%", realProbability * 100);
            } else {
                return String.format("⚠️ 伪造 %.0f%%", (1 - realProbability) * 100);
            }
        }
    }

    /**
     * 获取结果颜色
     */
    public int getResultColor() {
        if (isProcessing) {
            return 0xFF888888; // 灰色
        } else if (isFailed()) {
            return 0xFFFF0000; // 红色
        } else if (realProbability > 0.5f) {
            return 0xFF4CAF50; // 绿色
        } else {
            return 0xFFFF9800; // 橙色
        }
    }
}

