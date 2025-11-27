package com.example.test922.audio;

import com.example.test922.audio.processor.AudioPreprocessor;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
public class AudioPipelineValidationTest {

    // 仅保留预处理测试所需的 WAV 写入工具
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

    // 简单 DFT 幅度谱（用于日志验证）
    static double[] dftMagnitude(float[] x) {
        int N = x.length;
        double[] mag = new double[N];
        for (int k = 0; k < N; k++) {
            double re = 0, im = 0;
            for (int n = 0; n < N; n++) {
                double ang = -2 * Math.PI * k * n / N;
                double c = Math.cos(ang), s = Math.sin(ang);
                re += x[n] * c;
                im += x[n] * s;
            }
            mag[k] = Math.hypot(re, im);
        }
        return mag;
    }

    static int argmax(double[] a, int start, int end) {
        int idx = start;
        double best = Double.NEGATIVE_INFINITY;
        for (int i = start; i < end; i++) {
            if (a[i] > best) { best = a[i]; idx = i; }
        }
        return idx;
    }

    // 预处理正确性（16kHz，无重采样）
    @Test
    public void testPreprocessImpulse_noResample() throws Exception {
        int sr = 16000;
        int total = 2048; // 至少覆盖4帧
        short[] pcm = new short[total];
        pcm[0] = 32767; // 单样本脉冲

        File in = File.createTempFile("impulse16k_in", ".wav");
        in.deleteOnExit();
        writeWavMono16(in, sr, pcm);
        File out = File.createTempFile("impulse16k_out", ".wav");
        out.deleteOnExit();

        final List<float[]> frames = new ArrayList<>();
        final int[] finalSr = { -1 };
        CountDownLatch latch = new CountDownLatch(1);

        new AudioPreprocessor().process(in, out, new AudioPreprocessor.PreprocessingListener() {
            @Override public void onPreprocessingSuccess(File processedFile, List<float[]> outFrames, int sampleRate) {
                frames.addAll(outFrames);
                finalSr[0] = sampleRate;
                latch.countDown();
            }
            @Override public void onPreprocessingFailed(String errorMessage) {
                latch.countDown();
                Assert.fail("预处理失败: " + errorMessage);
            }
        });

        boolean ok = latch.await(7, TimeUnit.SECONDS);
        Assert.assertTrue("预处理超时", ok);
        Assert.assertEquals("输出采样率错误", 16000, finalSr[0]);
        Assert.assertFalse("未产生任何帧", frames.isEmpty());

        float[] f0 = frames.get(0);
        Assert.assertEquals("帧长应为512", 512, f0.length);

        // 稳健性断言：能量非零，且主能量集中在前若干样本（脉冲经处理后仍以开头为主）
        double sumAbs = 0, maxAbs = 0; int maxIdx = 0;
        for (int i = 0; i < f0.length; i++) {
            double a = Math.abs(f0[i]);
            sumAbs += a;
            if (a > maxAbs) { maxAbs = a; maxIdx = i; }
        }
        Assert.assertTrue("能量应非零", sumAbs > 1e-6);
        Assert.assertTrue("主能量不在前端", maxIdx < 8);
    }

    // 预处理返回采样率应为16k（含重采样路径）
    @Test
    public void testPreprocess_resampleFlag() throws Exception {
        int sr = 8000; // 触发重采样
        int total = 8000; // 1秒
        short[] pcm = new short[total];
        double freq = 440.0;
        for (int i = 0; i < total; i++) {
            double v = Math.sin(2*Math.PI*freq*i/sr);
            int s = (int) Math.round(v * 30000);
            pcm[i] = (short) Math.max(Math.min(s, 32767), -32768);
        }

        File in = File.createTempFile("sine8k_in", ".wav");
        in.deleteOnExit();
        writeWavMono16(in, sr, pcm);
        File out = File.createTempFile("sine8k_out", ".wav");
        out.deleteOnExit();

        final List<float[]> frames = new ArrayList<>();
        final int[] finalSr = { -1 };
        CountDownLatch latch = new CountDownLatch(1);

        new AudioPreprocessor().process(in, out, new AudioPreprocessor.PreprocessingListener() {
            @Override public void onPreprocessingSuccess(File processedFile, List<float[]> outFrames, int sampleRate) {
                frames.addAll(outFrames);
                finalSr[0] = sampleRate;
                latch.countDown();
            }
            @Override public void onPreprocessingFailed(String errorMessage) {
                latch.countDown();
                Assert.fail("预处理失败: " + errorMessage);
            }
        });

        boolean ok = latch.await(10, TimeUnit.SECONDS);
        Assert.assertTrue("预处理超时", ok);
        Assert.assertEquals("重采样后采样率应为16k", 16000, finalSr[0]);
        Assert.assertFalse("未产生任何帧", frames.isEmpty());

        // 频谱证据：主峰位置应落在440Hz附近（<= 半个频点分辨率）
        float[] f0 = frames.get(0);
        double[] mag = dftMagnitude(f0);
        int N = f0.length;
        int kPeak = argmax(mag, 1, N/2); // 忽略直流
        double df = finalSr[0] / (double) N;
        double fPeak = kPeak * df;
        // 次峰（排除邻域±1个bin，以减少窗引起的主瓣扩展影响）
        int second = -1;
        double best = Double.NEGATIVE_INFINITY;
        for (int k = 1; k < N/2; k++) {
            if (Math.abs(k - kPeak) <= 1) continue;
            if (mag[k] > best) { best = mag[k]; second = k; }
        }
        double main = mag[kPeak];
        double side = best;
        double ratioDb = 20 * Math.log10((main + 1e-12) / (side + 1e-12));
        double binHz = df;
        double tolHz = binHz / 2.0; // 频点分辨率的一半
        double fErr = Math.abs(fPeak - 440.0);

        System.out.println(String.format("[Sine] N=%d, df=%.3f Hz, peakBin=%d, fPeak=%.2f Hz, fErr=%.2f Hz, peak-vs-second=%.2f dB", N, df, kPeak, fPeak, fErr, ratioDb));

        Assert.assertTrue("主峰频率偏差过大", fErr <= tolHz + 1e-6);
        // 汉明窗旁瓣典型<-40dB，这里给宽松阈值
        Assert.assertTrue("主峰不显著(与次峰的差值应足够大)", ratioDb > 20);
    }
}
