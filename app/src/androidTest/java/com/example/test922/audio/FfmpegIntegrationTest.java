package com.example.test922.audio;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.example.test922.audio.processor.WavUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@RunWith(AndroidJUnit4.class)
public class FfmpegIntegrationTest {

    private Context ctx;
    private File inWav;
    private File outWav;

    @Before
    public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        inWav = new File(ctx.getCacheDir(), "ff_in_8k_mono.wav");
        outWav = new File(ctx.getCacheDir(), "ff_out_16k_norm.wav");
        if (outWav.exists()) outWav.delete();
        writeSineWavMono16(inWav, 8000, 1.0, 440.0, 1.0);
        assertTrue("输入WAV未生成", inWav.exists());
        assertTrue("输入WAV头无效", WavUtils.verifyRiffWave(inWav));
    }

    @After
    public void tearDown() {
        if (inWav != null && inWav.exists()) inWav.delete();
        if (outWav != null && outWav.exists()) outWav.delete();
    }

    @Test
    public void testFfmpeg_ResampleAndNormalize() {
        String cmd = "-y -hide_banner -nostdin -loglevel info " +
                "-i \"" + inWav.getAbsolutePath() + "\" -vn " +
                "-af \"aresample=out_sample_rate=16000:out_channel_layout=mono:out_sample_fmt=s16:resampler=soxr:precision=33:cutoff=0.97,dynaudnorm=f=150:g=15\" " +
                "-c:a pcm_s16le \"" + outWav.getAbsolutePath() + "\"";
        FFmpegSession s = FFmpegKit.execute(cmd);
        assertTrue("FFmpeg执行失败, rc=" + (s!=null?s.getReturnCode():null), ReturnCode.isSuccess(s.getReturnCode()));
        assertTrue("未生成输出文件", outWav.exists());
        assertTrue("WAV头校验失败", WavUtils.verifyRiffWave(outWav));
        WavUtils.WavInfo info = WavUtils.parse(outWav);
        assertTrue("解析WAV失败", info.valid);
        assertEquals("采样率应为16k", 16000, info.sampleRate);
        assertEquals("声道应为1", 1, info.channels);
        assertEquals("位深应为16", 16, info.bitsPerSample);
        assertTrue("数据区过小", info.dataSize > 16000); // 至少1秒数据
    }

    private static void writeSineWavMono16(File out, int sr, double seconds, double freq, double amp) throws IOException {
        int total = (int) Math.round(sr * seconds);
        short[] pcm = new short[total];
        for (int i = 0; i < total; i++) {
            double v = Math.sin(2*Math.PI*freq*i/sr) * amp;
            int s = (int) Math.round(v * 30000);
            pcm[i] = (short) Math.max(Math.min(s, 32767), -32768);
        }
        writeWavMono16(out, sr, pcm);
    }

    private static void writeWavMono16(File out, int sampleRate, short[] samples) throws IOException {
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
}

