package com.example.test922.audio.processor;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale; // 新增

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.Session;

public class AudioExtractor {

    private static final String TAG = "AudioExtractor";

    /** RawNet2 模型所需的目标采样率 */
    public static final int TARGET_SAMPLE_RATE = 16000;

    /** RawNet2 模型所需的目标声道数 */
    public static final int TARGET_CHANNELS = 1;

    // 使用 FFmpegKit 解封装音频到 PCM16 WAV（多轨优先匹配中文/主音轨，失败回退）
    // 修改：添加重采样到 16kHz 单声道
    private boolean extractWithFFmpeg(String inputPath, File outFile) {
        if (inputPath == null) return false;
        // 优先按语言/标题/handler 匹配中文/主音轨
        String[] selectors = new String[]{
                "0:a:m:language:chi",
                "0:a:m:language:zho",
                "0:a:m:language:zh",
                "0:a:m:language:cmn",
                "0:a:m:language:yue",
                "0:a:m:title:main",
                "0:a:m:handler_name:main",
                "0:a:m:title:中文",
                "0:a:m:title:国语",
                "0:a:m:title:普通话",
                "0:a:m:handler_name:中文",
                "0:a:m:handler_name:main",
                "0:a:0" // 回退首轨
        };
        for (int i = 0; i < selectors.length; i++) {
            safeDelete(outFile);
            String map = selectors[i];
            // 添加 -ar 16000 -ac 1 进行重采样到 16kHz 单声道
            String cmd = "-y -hide_banner -nostdin -loglevel info -i " + escapePath(inputPath)
                    + " -vn -map " + map
                    + " -ar " + TARGET_SAMPLE_RATE + " -ac " + TARGET_CHANNELS
                    + " -acodec pcm_s16le " + escapePath(outFile.getAbsolutePath());
            Log.d(TAG, "FFmpeg 解封装命令尝试(" + (i+1) + "/" + selectors.length + "): " + cmd);
            FFmpegSession s = FFmpegKit.execute(cmd);
            if (ReturnCode.isSuccess(s.getReturnCode()) && outFile.exists() && WavUtils.verifyRiffWave(outFile) && outFile.length() > 100) {
                Log.d(TAG, "FFmpeg 使用映射成功: -map " + map);
                return true;
            }
            Log.w(TAG, "FFmpeg -map 失败 rc=" + s.getReturnCode() + " 映射=" + map + " 尾日志:\n" + tail(safeLogs(s)));
        }
        // 最终回退：不指定 -map 让 FFmpeg 自选
        safeDelete(outFile);
        // 添加 -ar 16000 -ac 1 进行重采样到 16kHz 单声道
        String fallback = "-y -hide_banner -nostdin -loglevel info -i " + escapePath(inputPath)
                + " -vn -ar " + TARGET_SAMPLE_RATE + " -ac " + TARGET_CHANNELS
                + " -acodec pcm_s16le " + escapePath(outFile.getAbsolutePath());
        Log.d(TAG, "FFmpeg 解封装最终回退: " + fallback);
        FFmpegSession s = FFmpegKit.execute(fallback);
        if (ReturnCode.isSuccess(s.getReturnCode()) && outFile.exists() && WavUtils.verifyRiffWave(outFile) && outFile.length() > 100) {
            return true;
        }
        Log.w(TAG, "FFmpeg 解封装失败 rc=" + s.getReturnCode() + " 输出存在?=" + outFile.exists() + " 尾日志:\n" + tail(safeLogs(s)));
        if (outFile.exists()) safeDelete(outFile);
        return false;
    }

    // MediaCodec 解码为 PCM16 WAV（保留输出格式采样率与声道），仅负责解码不重采样
    // 注意：此方法输出的 WAV 可能不是 16kHz 单声道，需要后续用 FFmpeg 转换
    private boolean decodeWithMediaCodecInternal(Context context, Uri videoUri, File outFile) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, videoUri, null);
        } catch (IOException e) {
            Log.e(TAG, "MediaExtractor setDataSource 失败: " + e.getMessage());
            return false;
        }
        List<Integer> audioTracks = new ArrayList<>();
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) audioTracks.add(i);
        }
        if (audioTracks.isEmpty()) {
            Log.e(TAG, "未找到音频轨道");
            extractor.release();
            return false;
        }
        // 启发式选择最佳音轨
        int selectedTrack = pickBestAudioTrackIndex(extractor, audioTracks);
        if (audioTracks.size() > 1) {
            Log.w(TAG, "检测到多音轨: " + summarizeAudioTracks(extractor, audioTracks) + "; 选择轨道=" + selectedTrack + ". \nTODO: 这里可弹出 UI 供用户选择音轨（以后开发）。");
        }
        MediaFormat audioFormat = extractor.getTrackFormat(selectedTrack);
        extractor.selectTrack(selectedTrack);
        String mime = audioFormat.getString(MediaFormat.KEY_MIME);
        if (mime == null || !mime.startsWith("audio/")) {
            Log.e(TAG, "选中音轨的 MIME 无效: " + mime);
            extractor.release();
            return false;
        }
        MediaCodec codec;
        try { codec = MediaCodec.createDecoderByType(mime); } catch (IOException e) { extractor.release(); Log.e(TAG, "创建解码器失败: " + e.getMessage()); return false; }
        int sampleRate = audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int channelCount = audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        try { codec.configure(audioFormat, null, null, 0); codec.start(); } catch (Exception e) { codec.release(); extractor.release(); Log.e(TAG, "配置解码器失败: " + e.getMessage()); return false; }
        try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {
            raf.setLength(0);
            raf.write(new byte[44]);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEOS = false, outputEOS = false;
            long totalPcmBytes = 0;
            while (!outputEOS) {
                if (!inputEOS) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        if (inBuf != null) {
                            int size = extractor.readSampleData(inBuf, 0);
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEOS = true;
                            } else {
                                long pts = extractor.getSampleTime();
                                codec.queueInputBuffer(inIndex, 0, size, pts, 0);
                                extractor.advance();
                            }
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEOS = true;
                    if (info.size > 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        if (outBuf != null) {
                            byte[] chunk = ensureLittleEndian(outBuf, info.size);
                            raf.write(chunk);
                            totalPcmBytes += chunk.length;
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFmt = codec.getOutputFormat();
                    if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channelCount = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            }
            byte[] header = WavUtils.buildHeader((int) totalPcmBytes, sampleRate, channelCount, 16);
            raf.seek(0); raf.write(header);
            try { raf.getFD().sync(); } catch (IOException ignore) {}
            if (!WavUtils.verifyRiffWave(outFile)) {
                Log.w(TAG, "写入WAV头后校验失败");
            } else {
                Log.d(TAG, "WAV头校验通过 head=" + getFileHeadHex(outFile, 12));
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "写入WAV失败: " + e.getMessage());
            return false;
        } finally {
            try { codec.stop(); } catch (Exception ignore) {}
            try { codec.release(); } catch (Exception ignore) {}
            try { extractor.release(); } catch (Exception ignore) {}
        }
    }

    private byte[] ensureLittleEndian(ByteBuffer buffer, int size) {
        ByteBuffer data = buffer.duplicate();
        data.limit(data.position() + size);
        ShortBuffer sb = data.order(ByteOrder.nativeOrder()).asShortBuffer();
        short[] shorts = new short[sb.remaining()];
        sb.get(shorts);
        ByteBuffer out = ByteBuffer.allocate(shorts.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        out.asShortBuffer().put(shorts);
        return out.array();
    }

    private String safeLogs(Session session) { try { return session.getAllLogsAsString(); } catch (Throwable t) { return ""; } }
    private String tail(String s) { if (s==null) return ""; return s.length()>4000? s.substring(s.length()-4000): s; }

    private String getPathFromUri(Context context, Uri uri) {
        if (uri == null) return null;
        if ("file".equalsIgnoreCase(uri.getScheme())) return uri.getPath();
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            File tempFile = null;
            try {
                tempFile = File.createTempFile("ffmpeg_input", ".tmp", context.getCacheDir());
                tempFile.deleteOnExit();
                try (InputStream in = context.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(tempFile)) {
                    if (in == null) return null;
                    byte[] buf = new byte[8192]; int r; while ((r = in.read(buf)) != -1) out.write(buf,0,r);
                }
                return tempFile.getAbsolutePath();
            } catch (IOException e) {
                Log.e(TAG, "复制 content URI 失败", e);
                if (tempFile != null) tempFile.delete();
                return null;
            }
        }
        return null;
    }

    private boolean copyFile(File src, File dst) {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192]; int r; while ((r=in.read(buf))!=-1) out.write(buf,0,r); out.flush(); return true;
        } catch (IOException e) { Log.e(TAG, "复制文件失败", e); return false; }
    }

    private String escapePath(String path) { if (path==null) return ""; if (path.startsWith("\"")&&path.endsWith("\"")) return path; return '"'+path+'"'; }
    private void safeDelete(File f){ try { if(f!=null&&f.exists()){ f.delete(); } } catch (Exception ignore) {} }

    private String getFileHeadHex(File f, int n) {
        if (f==null||!f.exists()) return "(nofile)";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[n]; int r=in.read(b); StringBuilder sb=new StringBuilder();
            for (int i=0;i<r;i++){ sb.append(String.format("%02X", b[i])).append(i+1<r?" ":""); }
            return sb.toString();
        } catch (IOException e){ return "(ioerr)"; }
    }

    // 依据 language/title/handler 关键字为多音轨打分并返回最佳轨道索引
    private int pickBestAudioTrackIndex(MediaExtractor extractor, List<Integer> audioTracks) {
        int bestIndex = audioTracks.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (int idx : audioTracks) {
            MediaFormat f = extractor.getTrackFormat(idx);
            String lang = getStringOrNull(f, MediaFormat.KEY_LANGUAGE);
            if (lang == null) lang = getStringOrNull(f, "language");
            String title = getStringOrNull(f, "title");
            if (title == null) title = getStringOrNull(f, "track-title");
            String handler = getStringOrNull(f, "handler_name");
            if (handler == null) handler = getStringOrNull(f, "handler-name");
            int ch = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : -1;
            int sr = f.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) : -1;
            int br = f.containsKey(MediaFormat.KEY_BIT_RATE) ? f.getInteger(MediaFormat.KEY_BIT_RATE) : -1;

            int score = scoreByKeywords(lang, title, handler) + scoreByFormat(ch, sr, br);
            Log.d(TAG, "音轨候选 index=" + idx + " lang=" + lang + " title=" + title + " handler=" + handler + " ch=" + ch + " sr=" + sr + " br=" + br + " score=" + score);
            if (score > bestScore) { bestScore = score; bestIndex = idx; }
        }
        return bestIndex;
    }

    // 多音轨概要日志
    private String summarizeAudioTracks(MediaExtractor extractor, List<Integer> audioTracks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < audioTracks.size(); i++) {
            int idx = audioTracks.get(i);
            MediaFormat f = extractor.getTrackFormat(idx);
            String lang = getStringOrNull(f, MediaFormat.KEY_LANGUAGE);
            if (lang == null) lang = getStringOrNull(f, "language");
            String title = getStringOrNull(f, "title");
            if (title == null) title = getStringOrNull(f, "track-title");
            String handler = getStringOrNull(f, "handler_name");
            if (handler == null) handler = getStringOrNull(f, "handler-name");
            int ch = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : -1;
            int sr = f.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) : -1;
            if (i > 0) sb.append(" | ");
            sb.append("#").append(idx).append("(").append(lang).append(",").append(title).append(",").append(handler).append(",").append(ch).append("ch,").append(sr).append("Hz)");
        }
        return sb.toString();
    }

    private int scoreByKeywords(String lang, String title, String handler) {
        int score = 0;
        String l = safeLower(lang);
        String t = safeLower(title);
        String h = safeLower(handler);
        if (l.contains("zh")) score += 120;
        if (l.contains("chi") || l.contains("zho")) score += 110;
        if (l.contains("cmn") || l.contains("mandarin")) score += 100;
        if (l.contains("yue") || l.contains("cantonese")) score += 90;
        if (l.equals("ch")) score += 80;
        if (t.contains("中文") || t.contains("国语") || t.contains("普通话")) score += 95;
        if (t.contains("main") || t.contains("default") || t.contains("原声") || t.contains("主")) score += 60;
        if (h.contains("main") || h.contains("default")) score += 50;
        return score;
    }

    private int scoreByFormat(int ch, int sr, int br) {
        int score = 0;
        if (ch > 0 && ch <= 2) score += 10; else if (ch > 2) score -= 5;
        if (sr == 16000) score += 15; else if (sr == 44100 || sr == 48000) score += 8;
        if (br > 0) score += 1;
        return score;
    }

    private String getStringOrNull(MediaFormat f, String key) {
        try { return f != null && f.containsKey(key) ? f.getString(key) : null; } catch (Throwable ignore) { return null; }
    }

    private String safeLower(String s) { return s == null ? "" : s.toLowerCase(Locale.US); }

    /**
     * 将任意音频文件转换为 16kHz 单声道 16-bit PCM WAV 格式。
     * 用于直接选取的音频文件在检测前的预处理。
     *
     * @param context  上下文
     * @param inputUri 输入音频文件的 Uri
     * @param outputFile 输出的 WAV 文件
     * @param listener 转换结果回调
     */
    public void convertTo16kHzMono(Context context, Uri inputUri, File outputFile, AudioExtractionListener listener) {
        listener.onExtractionStarted();

        String inputPath = getPathFromUri(context, inputUri);
        if (inputPath == null) {
            listener.onExtractionFailure("无法获取输入音频文件路径");
            return;
        }

        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            listener.onExtractionFailure("无法创建输出目录: " + parent.getAbsolutePath());
            return;
        }

        if (outputFile.exists() && !outputFile.delete()) {
            Log.w(TAG, "无法删除已存在的输出文件：" + outputFile.getAbsolutePath());
        }

        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            Log.d(TAG, "开始音频格式转换: " + inputPath + " -> 16kHz 单声道 WAV");

            boolean success = convertWithFFmpeg(inputPath, outputFile);
            long dt = System.currentTimeMillis() - t0;

            if (success) {
                Log.d(TAG, "音频转换成功, 用时=" + dt + "ms 大小=" + outputFile.length());
                listener.onExtractionSuccess(outputFile);
            } else {
                Log.e(TAG, "音频转换失败");
                listener.onExtractionFailure("音频格式转换失败，请确保文件是有效的音频格式");
            }
        }).start();
    }

    /**
     * 使用 FFmpeg 将音频转换为 16kHz 单声道 16-bit PCM WAV。
     *
     * @param inputPath 输入文件路径
     * @param outputFile 输出 WAV 文件
     * @return 转换是否成功
     */
    private boolean convertWithFFmpeg(String inputPath, File outputFile) {
        if (inputPath == null) return false;

        safeDelete(outputFile);

        // FFmpeg 命令：转换为 16kHz 单声道 16-bit PCM WAV
        String cmd = "-y -hide_banner -nostdin -loglevel info -i " + escapePath(inputPath)
                + " -vn -ar " + TARGET_SAMPLE_RATE + " -ac " + TARGET_CHANNELS
                + " -acodec pcm_s16le " + escapePath(outputFile.getAbsolutePath());

        Log.d(TAG, "FFmpeg 音频转换命令: " + cmd);
        FFmpegSession session = FFmpegKit.execute(cmd);

        if (ReturnCode.isSuccess(session.getReturnCode()) && outputFile.exists()
                && WavUtils.verifyRiffWave(outputFile) && outputFile.length() > 100) {
            // 验证输出文件的格式
            WavUtils.WavInfo info = WavUtils.parse(outputFile);
            if (info.valid) {
                Log.d(TAG, "转换后 WAV 信息: sampleRate=" + info.sampleRate
                        + " channels=" + info.channels + " bits=" + info.bitsPerSample);
            }
            return true;
        }

        Log.e(TAG, "FFmpeg 音频转换失败 rc=" + session.getReturnCode()
                + " 日志:\n" + tail(safeLogs(session)));
        if (outputFile.exists()) safeDelete(outputFile);
        return false;
    }

    /**
     * 检查音频文件是否已经是 16kHz 单声道 16-bit PCM WAV 格式。
     *
     * @param file 要检查的文件
     * @return 如果格式正确返回 true
     */
    public static boolean isCorrectFormat(File file) {
        if (file == null || !file.exists()) return false;

        WavUtils.WavInfo info = WavUtils.parse(file);
        if (!info.valid) return false;

        boolean correct = info.sampleRate == TARGET_SAMPLE_RATE
                && info.channels == TARGET_CHANNELS
                && info.bitsPerSample == 16;

        if (!correct) {
            Log.d(TAG, "音频格式不匹配: sampleRate=" + info.sampleRate
                    + "(需要" + TARGET_SAMPLE_RATE + ") channels=" + info.channels
                    + "(需要" + TARGET_CHANNELS + ") bits=" + info.bitsPerSample + "(需要16)");
        }

        return correct;
    }

    public void extractAudio(Context context, Uri videoUri, File extractedAudioFile, AudioExtractionListener listener) {
        listener.onExtractionStarted();
        String inputFilePath = getPathFromUri(context, videoUri);
        if (inputFilePath == null) {
            listener.onExtractionFailure("无法获取输入视频文件路径");
            return;
        }
        File parent = extractedAudioFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            listener.onExtractionFailure("无法创建输出目录: " + parent.getAbsolutePath());
            return;
        }
        if (extractedAudioFile.exists() && !extractedAudioFile.delete()) {
            Log.w(TAG, "无法删除已存在的输出文件：" + extractedAudioFile.getAbsolutePath());
        }
        // 新线程：先尝试 FFmpeg 解封装为 PCM16 WAV；失败再回退 MediaCodec
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            Log.d(TAG, "开始 FFmpeg 音频抽取（重采样到 16kHz 单声道）");
            boolean ffOk = extractWithFFmpeg(inputFilePath, extractedAudioFile);
            long dt = System.currentTimeMillis() - t0;
            if (ffOk) {
                Log.d(TAG, "FFmpeg 抽取成功, 用时=" + dt + "ms 大小=" + extractedAudioFile.length() + " 头=" + getFileHeadHex(extractedAudioFile,12));
                // 验证输出格式
                logWavInfo(extractedAudioFile);
                listener.onExtractionSuccess(extractedAudioFile);
            } else {
                Log.w(TAG, "FFmpeg 抽取失败, 回退 MediaCodec 解码路径");
                // MediaCodec 解码得到的可能不是 16kHz，需要再用 FFmpeg 转换
                File tempWav = new File(extractedAudioFile.getParent(), "temp_mediacodec_" + System.currentTimeMillis() + ".wav");
                boolean decodeOk = decodeWithMediaCodecInternal(context, videoUri, tempWav);
                if (decodeOk && tempWav.exists()) {
                    // 用 FFmpeg 转换为 16kHz 单声道
                    Log.d(TAG, "MediaCodec 解码成功，开始 FFmpeg 重采样到 16kHz 单声道");
                    boolean convertOk = convertWithFFmpeg(tempWav.getAbsolutePath(), extractedAudioFile);
                    safeDelete(tempWav);
                    if (convertOk) {
                        logWavInfo(extractedAudioFile);
                        listener.onExtractionSuccess(extractedAudioFile);
                    } else {
                        listener.onExtractionFailure("音频重采样失败");
                    }
                } else {
                    safeDelete(tempWav);
                    listener.onExtractionFailure("MediaCodec 解码失败");
                }
            }
        }).start();
    }

    /**
     * 打印 WAV 文件信息用于调试
     */
    private void logWavInfo(File wavFile) {
        if (wavFile == null || !wavFile.exists()) return;
        WavUtils.WavInfo info = WavUtils.parse(wavFile);
        if (info.valid) {
            Log.i(TAG, String.format("WAV 信息: sampleRate=%d, channels=%d, bits=%d, dataSize=%d",
                    info.sampleRate, info.channels, info.bitsPerSample, info.dataSize));
            // 检查是否符合模型要求
            if (info.sampleRate != TARGET_SAMPLE_RATE || info.channels != TARGET_CHANNELS) {
                Log.w(TAG, "⚠️ 警告：WAV 格式不符合模型要求！需要 " + TARGET_SAMPLE_RATE + "Hz 单声道");
            }
        }
    }
}
