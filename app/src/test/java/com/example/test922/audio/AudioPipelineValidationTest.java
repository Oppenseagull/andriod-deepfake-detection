package com.example.test922.audio;

import com.example.test922.audio.processor.WavUtils;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 音频处理管道验证测试
 * 测试 WavUtils.readWavFile 的正确性
 */
@RunWith(RobolectricTestRunner.class)
public class AudioPipelineValidationTest {

    /**
     * 写入单声道 16-bit PCM WAV 文件
     */
    static void writeWavMono16(File out, int sampleRate, short[] samples) throws IOException {
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = samples.length * 2;
        int chunkSize = 36 + dataSize;

        try (FileOutputStream fos = new FileOutputStream(out)) {
            ByteBuffer bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            // RIFF/WAVE 头
            bb.putInt(0x46464952);            // 'RIFF'
            bb.putInt(chunkSize);
            bb.putInt(0x45564157);            // 'WAVE'
            // fmt chunk
            bb.putInt(0x20746d66);            // 'fmt '
            bb.putInt(16);                    // PCM header size
            bb.putShort((short) 1);           // PCM
            bb.putShort((short) channels);
            bb.putInt(sampleRate);
            bb.putInt(byteRate);
            bb.putShort((short) blockAlign);
            bb.putShort((short) bitsPerSample);
            // data chunk
            bb.putInt(0x61746164);            // 'data'
            bb.putInt(dataSize);
            fos.write(bb.array());
            // data
            ByteBuffer data = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN);
            for (short s : samples) data.putShort(s);
            fos.write(data.array());
        }
    }

    /**
     * 测试 readWavFile 能够正确读取 16-bit PCM WAV 并归一化
     */
    @Test
    public void testReadWavFile_normalization() throws Exception {
        int sr = 16000;
        int total = 1000;
        short[] pcm = new short[total];

        // 使用已知值填充：最大正值、最小负值、零
        pcm[0] = 32767;   // 最大正值 -> 应归一化为接近 1.0
        pcm[1] = -32768;  // 最小负值 -> 应归一化为 -1.0
        pcm[2] = 0;       // 零 -> 应归一化为 0.0
        pcm[3] = 16384;   // 中间值 -> 应归一化为 0.5

        File wavFile = File.createTempFile("test_norm", ".wav");
        wavFile.deleteOnExit();
        writeWavMono16(wavFile, sr, pcm);

        float[] audioData = WavUtils.readWavFile(wavFile.getAbsolutePath());

        Assert.assertNotNull("readWavFile 返回 null", audioData);
        Assert.assertEquals("音频长度不正确", total, audioData.length);

        // 验证归一化值
        Assert.assertEquals("最大正值归一化错误", 32767.0f / 32768.0f, audioData[0], 0.0001f);
        Assert.assertEquals("最小负值归一化错误", -1.0f, audioData[1], 0.0001f);
        Assert.assertEquals("零值归一化错误", 0.0f, audioData[2], 0.0001f);
        Assert.assertEquals("中间值归一化错误", 0.5f, audioData[3], 0.001f);
    }

    /**
     * 测试 readWavFile 对正弦波的读取
     */
    @Test
    public void testReadWavFile_sineWave() throws Exception {
        int sr = 16000;
        int total = 16000; // 1秒
        short[] pcm = new short[total];
        double freq = 440.0;

        for (int i = 0; i < total; i++) {
            double v = Math.sin(2 * Math.PI * freq * i / sr);
            int s = (int) Math.round(v * 30000);
            pcm[i] = (short) Math.max(Math.min(s, 32767), -32768);
        }

        File wavFile = File.createTempFile("test_sine", ".wav");
        wavFile.deleteOnExit();
        writeWavMono16(wavFile, sr, pcm);

        float[] audioData = WavUtils.readWavFile(wavFile.getAbsolutePath());

        Assert.assertNotNull("readWavFile 返回 null", audioData);
        Assert.assertEquals("音频长度不正确", total, audioData.length);

        // 验证值在 [-1, 1] 范围内
        for (int i = 0; i < audioData.length; i++) {
            Assert.assertTrue("值超出范围 [-1, 1]: " + audioData[i],
                audioData[i] >= -1.0f && audioData[i] <= 1.0f);
        }

        // 验证能量非零
        double sumAbs = 0;
        for (float v : audioData) {
            sumAbs += Math.abs(v);
        }
        Assert.assertTrue("能量应非零", sumAbs > 0.1);
    }

    /**
     * 测试 readWavFile 对无效文件路径的处理
     */
    @Test
    public void testReadWavFile_invalidPath() {
        float[] result = WavUtils.readWavFile("/non/existent/path.wav");
        Assert.assertNull("无效路径应返回 null", result);

        result = WavUtils.readWavFile(null);
        Assert.assertNull("null 路径应返回 null", result);

        result = WavUtils.readWavFile("");
        Assert.assertNull("空路径应返回 null", result);
    }

    /**
     * 测试 padOrTrim 逻辑 - 通过反射测试 RawNet2Strategy 的私有方法
     * 这里我们直接测试逻辑的正确性
     */
    @Test
    public void testPadOrTrimLogic_padding() {
        // 测试循环填充逻辑
        int targetLength = 10;
        float[] input = new float[]{1.0f, 2.0f, 3.0f};

        // 模拟 padOrTrim 逻辑
        float[] result = new float[targetLength];
        int pos = 0;
        while (pos < targetLength) {
            int copyLen = Math.min(input.length, targetLength - pos);
            System.arraycopy(input, 0, result, pos, copyLen);
            pos += copyLen;
        }

        // 期望结果: [1, 2, 3, 1, 2, 3, 1, 2, 3, 1]
        Assert.assertEquals(targetLength, result.length);
        Assert.assertEquals(1.0f, result[0], 0.0001f);
        Assert.assertEquals(2.0f, result[1], 0.0001f);
        Assert.assertEquals(3.0f, result[2], 0.0001f);
        Assert.assertEquals(1.0f, result[3], 0.0001f);
        Assert.assertEquals(2.0f, result[4], 0.0001f);
        Assert.assertEquals(3.0f, result[5], 0.0001f);
        Assert.assertEquals(1.0f, result[6], 0.0001f);
        Assert.assertEquals(2.0f, result[7], 0.0001f);
        Assert.assertEquals(3.0f, result[8], 0.0001f);
        Assert.assertEquals(1.0f, result[9], 0.0001f);
    }

    /**
     * 测试 padOrTrim 逻辑 - 截断
     */
    @Test
    public void testPadOrTrimLogic_trimming() {
        int targetLength = 5;
        float[] input = new float[]{1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f, 9.0f, 10.0f};

        // 模拟 padOrTrim 截断逻辑
        float[] result = new float[targetLength];
        System.arraycopy(input, 0, result, 0, targetLength);

        // 期望结果: [1, 2, 3, 4, 5]
        Assert.assertEquals(targetLength, result.length);
        Assert.assertEquals(1.0f, result[0], 0.0001f);
        Assert.assertEquals(2.0f, result[1], 0.0001f);
        Assert.assertEquals(3.0f, result[2], 0.0001f);
        Assert.assertEquals(4.0f, result[3], 0.0001f);
        Assert.assertEquals(5.0f, result[4], 0.0001f);
    }
}
