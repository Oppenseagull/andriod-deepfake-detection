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

    public static WavInfo parse(File f) {
        WavInfo info = new WavInfo();
        if (f == null || !f.exists() || f.length() < 44) return info;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] id = new byte[4];
            raf.readFully(id); if (!"RIFF".equals(new String(id, StandardCharsets.US_ASCII))) return info;
            raf.skipBytes(4); raf.readFully(id); if (!"WAVE".equals(new String(id, StandardCharsets.US_ASCII))) return info;
            while (raf.getFilePointer() + 8 <= raf.length()) {
                raf.readFully(id); int chunkSize = Integer.reverseBytes(raf.readInt()); String cid = new String(id, StandardCharsets.US_ASCII);
                if ("fmt ".equals(cid)) {
                    long fmtStart = raf.getFilePointer();
                    raf.readShort(); // formatTag
                    int channels = Short.toUnsignedInt(Short.reverseBytes(raf.readShort()));
                    int sampleRate = Integer.reverseBytes(raf.readInt());
                    raf.skipBytes(6);
                    int bitsPerSample = Short.toUnsignedInt(Short.reverseBytes(raf.readShort()));
                    long toSkip = chunkSize - (raf.getFilePointer() - fmtStart); if (toSkip > 0) raf.skipBytes((int) toSkip);
                    info.channels = channels; info.sampleRate = sampleRate; info.bitsPerSample = bitsPerSample;
                } else if ("data".equals(cid)) {
                    info.dataOffset = raf.getFilePointer(); info.dataSize = Integer.toUnsignedLong(chunkSize);
                    info.valid = info.sampleRate > 0 && info.channels > 0 && info.bitsPerSample > 0; break;
                } else {
                    raf.skipBytes(chunkSize);
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

    public static byte[] buildHeader(int dataBytes, int sampleRate, int channels, int bitsPerSample) {
        byte[] header = new byte[44];
        long riffSize = 36L + dataBytes;
        header[0]='R'; header[1]='I'; header[2]='F'; header[3]='F';
        writeLE32(header,4,(int)riffSize);
        header[8]='W'; header[9]='A'; header[10]='V'; header[11]='E';
        header[12]='f'; header[13]='m'; header[14]='t'; header[15]=' ';
        writeLE32(header,16,16);
        writeLE16(header,20,(short)1);
        writeLE16(header,22,(short)channels);
        writeLE32(header,24,sampleRate);
        int byteRate = sampleRate*channels*bitsPerSample/8;
        writeLE32(header,28,byteRate);
        writeLE16(header,32,(short)(channels*bitsPerSample/8));
        writeLE16(header,34,(short)bitsPerSample);
        header[36]='d'; header[37]='a'; header[38]='t'; header[39]='a';
        writeLE32(header,40,dataBytes);
        return header;
    }

    public static boolean rewriteMinimal(File f, int sampleRate, int channels, int bitsPerSample) {
        if (f==null||!f.exists()) return false;
        long dataBytes = f.length()-44; if (dataBytes<0) dataBytes=0;
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            byte[] header = buildHeader((int)dataBytes, sampleRate, channels, bitsPerSample);
            raf.seek(0); raf.write(header);
            try { raf.getFD().sync(); } catch (IOException ignore) {}
            return verifyRiffWave(f);
        } catch (IOException e) { Log.e(TAG, "rewriteMinimal IO失败", e); return false; }
    }

    private static void writeLE16(byte[] arr,int pos,short v){ arr[pos]=(byte)(v&0xFF); arr[pos+1]=(byte)((v>>>8)&0xFF);}
    private static void writeLE32(byte[] arr,int pos,int v){ arr[pos]=(byte)(v&0xFF); arr[pos+1]=(byte)((v>>>8)&0xFF); arr[pos+2]=(byte)((v>>>16)&0xFF); arr[pos+3]=(byte)((v>>>24)&0xFF);}
}

