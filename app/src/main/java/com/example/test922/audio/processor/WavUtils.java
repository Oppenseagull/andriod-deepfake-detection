package com.example.test922.audio.processor;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * 统一 WAV 解析/校验/头构建工具。
 */
public final class WavUtils {
    private static final String TAG = "WavUtils";
    private WavUtils() {}

    public static final class WavInfo {
        public boolean valid;
        public int sampleRate;
        public int channels;
        public int bitsPerSample;
        public long dataOffset;
        public long dataSize;
    }

    /**
     * 读取 16-bit PCM WAV 文件，返回归一化后的 float 数组。
     *
     * 功能极其纯粹：
     * 1. 读取 16-bit PCM WAV 文件
     * 2. 将 short 类型数据转换为 float
     * 3. 执行归一化：floatVal = shortVal / 32768.0f
     *
     * 不做任何其他处理（不做预加重，不做切片）。
     *
     * @param filePath WAV 文件的绝对路径
     * @return 归一化后的音频数据，范围 [-1.0, 1.0)；失败时返回 null
     */
    public static float[] readWavFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            Log.e(TAG, "readWavFile: 文件路径为空");
            return null;
        }

        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            Log.e(TAG, "readWavFile: 文件不存在: " + filePath);
            return null;
        }

        // 解析 WAV 头
        WavInfo info = parse(file);
        if (!info.valid) {
            Log.e(TAG, "readWavFile: WAV 头解析失败: " + filePath);
            return null;
        }

        // 目前只支持 16-bit PCM
        if (info.bitsPerSample != 16) {
            Log.e(TAG, "readWavFile: 不支持的位深度: " + info.bitsPerSample + "，仅支持 16-bit");
            return null;
        }

        int bytesPerSample = 2; // 16-bit = 2 bytes
        int channels = Math.max(1, info.channels);
        int frameSize = bytesPerSample * channels;
        long totalFrames = info.dataSize / frameSize;

        if (totalFrames <= 0 || totalFrames > Integer.MAX_VALUE) {
            Log.e(TAG, "readWavFile: 无效的帧数: " + totalFrames);
            return null;
        }

        float[] audioData = new float[(int) totalFrames];

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(info.dataOffset);

            byte[] buffer = new byte[4096];
            int audioIndex = 0;

            while (audioIndex < audioData.length) {
                int bytesToRead = (int) Math.min(buffer.length, (long)(audioData.length - audioIndex) * frameSize);
                int bytesRead = raf.read(buffer, 0, bytesToRead);
                if (bytesRead <= 0) break;

                // 处理读取的字节
                for (int offset = 0; offset + frameSize <= bytesRead && audioIndex < audioData.length; offset += frameSize) {
                    // 如果是多声道，取所有声道的平均值
                    float sampleSum = 0f;
                    for (int ch = 0; ch < channels; ch++) {
                        int sampleOffset = offset + ch * bytesPerSample;
                        // 读取 16-bit little-endian 采样
                        int lo = buffer[sampleOffset] & 0xFF;
                        int hi = buffer[sampleOffset + 1];
                        short sampleValue = (short) ((hi << 8) | lo);
                        // 归一化到 [-1.0, 1.0)
                        sampleSum += sampleValue / 32768.0f;
                    }
                    audioData[audioIndex++] = sampleSum / channels;
                }
            }

            return audioData;

        } catch (IOException e) {
            Log.e(TAG, "readWavFile: IO 错误", e);
            return null;
        }
    }

    public static WavInfo parse(File f) {
        WavInfo info = new WavInfo();
        if (f == null || !f.exists() || f.length() < 44) return info;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] id = new byte[4];
            raf.readFully(id); if (!"RIFF".equals(new String(id, StandardCharsets.US_ASCII))) return info;
            raf.skipBytes(4); raf.readFully(id); if (!"WAVE".equals(new String(id, StandardCharsets.US_ASCII))) return info;
            while (raf.getFilePointer() + 8 <= raf.length()) {
                raf.readFully(id); int chunkSize = Integer.reverseBytes(raf.readInt()); String cid = new String(id, StandardCharsets.US_ASCII);
                // 根据 RIFF 标准，Chunk 必须 word-aligned（偶数字节对齐）
                // 如果 chunkSize 是奇数，文件中会有 1 个 padding byte，但不计入 chunkSize
                int paddedChunkSize = (chunkSize % 2 == 1) ? chunkSize + 1 : chunkSize;

                if ("fmt ".equals(cid)) {
                    long fmtStart = raf.getFilePointer();
                    raf.readShort(); // formatTag
                    int channels = Short.toUnsignedInt(Short.reverseBytes(raf.readShort()));
                    int sampleRate = Integer.reverseBytes(raf.readInt());
                    raf.skipBytes(6);
                    int bitsPerSample = Short.toUnsignedInt(Short.reverseBytes(raf.readShort()));
                    long toSkip = paddedChunkSize - (raf.getFilePointer() - fmtStart); if (toSkip > 0) raf.skipBytes((int) toSkip);
                    info.channels = channels; info.sampleRate = sampleRate; info.bitsPerSample = bitsPerSample;
                } else if ("data".equals(cid)) {
                    info.dataOffset = raf.getFilePointer(); info.dataSize = Integer.toUnsignedLong(chunkSize);
                    info.valid = info.sampleRate > 0 && info.channels > 0 && info.bitsPerSample > 0; break;
                } else {
                    raf.skipBytes(paddedChunkSize);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "parse IO失败", e);
        }
        return info;
    }

    public static boolean verifyRiffWave(File f) {
        if (f == null || !f.exists() || f.length() < 12) return false;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[12]; int r = in.read(b); if (r < 12) return false;
            return b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F'&&b[8]=='W'&&b[9]=='A'&&b[10]=='V'&&b[11]=='E';
        } catch (IOException e) { return false; }
    }

    /**
     * 将 PCM 原始数据写入 WAV 文件。
     *
     * @param outputFile 输出文件
     * @param pcmData    PCM 原始字节数据
     * @param sampleRate 采样率 (如 16000)
     * @param channels   声道数 (1=单声道, 2=立体声)
     * @param bitsPerSample 位深度 (通常为 16)
     * @return 成功返回 true
     */
    public static boolean writeWavFile(File outputFile, byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        if (outputFile == null || pcmData == null || pcmData.length == 0) {
            Log.e(TAG, "writeWavFile: 无效参数");
            return false;
        }

        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
            // 写入 WAV 头 (44 字节)
            // RIFF chunk
            raf.writeBytes("RIFF");
            raf.writeInt(Integer.reverseBytes(36 + pcmData.length)); // 文件大小 - 8
            raf.writeBytes("WAVE");

            // fmt sub-chunk
            raf.writeBytes("fmt ");
            raf.writeInt(Integer.reverseBytes(16)); // fmt chunk size (PCM = 16)
            raf.writeShort(Short.reverseBytes((short) 1)); // audio format (PCM = 1)
            raf.writeShort(Short.reverseBytes((short) channels));
            raf.writeInt(Integer.reverseBytes(sampleRate));
            raf.writeInt(Integer.reverseBytes(byteRate));
            raf.writeShort(Short.reverseBytes((short) blockAlign));
            raf.writeShort(Short.reverseBytes((short) bitsPerSample));

            // data sub-chunk
            raf.writeBytes("data");
            raf.writeInt(Integer.reverseBytes(pcmData.length));
            raf.write(pcmData);

            Log.i(TAG, "writeWavFile: 成功写入 " + outputFile.getAbsolutePath() + " (" + pcmData.length + " bytes)");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "writeWavFile: IO 错误", e);
            return false;
        }
    }

    /**
     * 将 short 数组 PCM 数据写入 WAV 文件。
     */
    public static boolean writeWavFile(File outputFile, short[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        if (pcmData == null || pcmData.length == 0) return false;
        // 将 short[] 转换为 byte[] (little-endian)
        byte[] byteData = new byte[pcmData.length * 2];
        for (int i = 0; i < pcmData.length; i++) {
            byteData[i * 2] = (byte) (pcmData[i] & 0xFF);
            byteData[i * 2 + 1] = (byte) ((pcmData[i] >> 8) & 0xFF);
        }
        return writeWavFile(outputFile, byteData, sampleRate, channels, bitsPerSample);
    }

    /**
     * 构建 WAV 文件头（44 字节）
     *
     * @param totalPcmBytes PCM 数据的总字节数
     * @param sampleRate    采样率（如 16000, 44100, 48000）
     * @param channels      声道数（1=单声道, 2=立体声）
     * @param bitsPerSample 位深度（通常为 16）
     * @return 44 字节的 WAV 头
     */
    public static byte[] buildHeader(int totalPcmBytes, int sampleRate, int channels, int bitsPerSample) {
        int totalDataLen = totalPcmBytes + 36;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        byte[] header = new byte[44];

        // RIFF chunk descriptor
        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        // Chunk size (file size - 8)
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        // Format
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';

        // fmt sub-chunk
        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        // Subchunk1 size (16 for PCM)
        header[16] = 16;
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        // Audio format (1 = PCM)
        header[20] = 1;
        header[21] = 0;
        // Number of channels
        header[22] = (byte) channels;
        header[23] = 0;
        // Sample rate
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        // Byte rate
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        // Block align
        header[32] = (byte) blockAlign;
        header[33] = 0;
        // Bits per sample
        header[34] = (byte) bitsPerSample;
        header[35] = 0;

        // data sub-chunk
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        // Subchunk2 size (PCM data size)
        header[40] = (byte) (totalPcmBytes & 0xff);
        header[41] = (byte) ((totalPcmBytes >> 8) & 0xff);
        header[42] = (byte) ((totalPcmBytes >> 16) & 0xff);
        header[43] = (byte) ((totalPcmBytes >> 24) & 0xff);

        return header;
    }
}
