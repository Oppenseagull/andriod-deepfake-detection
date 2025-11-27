package com.example.test922.ui;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.test922.R;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioInfoActivity extends AppCompatActivity {

    private static final String TAG = "AudioInfoActivity";
    public static final String EXTRA_AUDIO_FILE_PATH = "audio_file_path";

    private TextView audioHeaderInfoTextView;
    private WaveformView waveformView;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio_info);

        audioHeaderInfoTextView = findViewById(R.id.audio_header_info_text_view);
        waveformView = findViewById(R.id.waveform_view_details);

        String audioFilePath = getIntent().getStringExtra(EXTRA_AUDIO_FILE_PATH);
        if (audioFilePath != null && !audioFilePath.isEmpty()) {
            File audioFile = new File(audioFilePath);
            if (audioFile.exists()) {
                displayAudioInfo(audioFile);
            } else {
                Toast.makeText(this, "Audio file not found.", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "No audio file path provided.", Toast.LENGTH_SHORT).show();
        }
    }

    private void displayAudioInfo(File audioFile) {
        executorService.submit(() -> {
            try {
                // 1. MediaExtractor to get audio format and metadata
                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(audioFile.getAbsolutePath());
                MediaFormat format = null;
                int audioTrackIndex = -1;

                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat trackFormat = extractor.getTrackFormat(i);
                    String mime = trackFormat.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioTrackIndex = i;
                        format = trackFormat;
                        break;
                    }
                }

                if (audioTrackIndex == -1 || format == null) {
                    Log.e(TAG, "No audio track found in the file.");
                    extractor.release();
                    return;
                }

                extractor.selectTrack(audioTrackIndex);

                // Extract header info
                int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                long durationUs = format.getLong(MediaFormat.KEY_DURATION);
                String mime = format.getString(MediaFormat.KEY_MIME);
                int bitRate = format.containsKey(MediaFormat.KEY_BIT_RATE) ? format.getInteger(MediaFormat.KEY_BIT_RATE) : -1;

                final String headerInfo = "MIME Type: " + mime + "\n" +
                        "Sample Rate: " + sampleRate + " Hz\n" +
                        "Channels: " + channelCount + "\n" +
                        "Duration: " + (durationUs / 1000000.0) + " s\n" +
                        "Bitrate: " + (bitRate > 0 ? (bitRate / 1000) + " kbps" : "N/A");

                runOnUiThread(() -> audioHeaderInfoTextView.setText(headerInfo));


                // 2. MediaCodec to decode audio to PCM
                MediaCodec codec = MediaCodec.createDecoderByType(mime);
                codec.configure(format, null, null, 0);
                codec.start();

                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                boolean isEOS = false;
                List<Short> pcmData = new ArrayList<>();

                while (!isEOS) {
                    int inputBufIndex = codec.dequeueInputBuffer(10000);
                    if (inputBufIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputBufIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);

                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            isEOS = true;
                        } else {
                            codec.queueInputBuffer(inputBufIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }

                    int outputBufIndex = codec.dequeueOutputBuffer(bufferInfo, 10000);
                    if (outputBufIndex >= 0) {
                        ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufIndex);
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            ShortBuffer shortBuffer = outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer();
                            while (shortBuffer.hasRemaining()) {
                                pcmData.add(shortBuffer.get());
                            }
                        }
                        codec.releaseOutputBuffer(outputBufIndex, false);

                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break;
                        }
                    }
                }

                codec.stop();
                codec.release();
                extractor.release();

                // 3. Convert PCM data to float array for WaveformView
                int downsampleSize = 4096;
                if (pcmData.isEmpty()) {
                    Log.w(TAG, "PCM data is empty.");
                    return;
                }
                float[] waveform = new float[Math.min(pcmData.size(), downsampleSize)];
                int step = Math.max(1, pcmData.size() / downsampleSize);

                for (int i = 0; i < waveform.length; i++) {
                    waveform[i] = (float) pcmData.get(i * step) / (float) Short.MAX_VALUE;
                }

                // 4. Update UI on the main thread
                long finalDurationMs = durationUs / 1000;
                runOnUiThread(() -> waveformView.setWaveform(waveform, finalDurationMs));

            } catch (IOException e) {
                Log.e(TAG, "Error processing audio file", e);
                runOnUiThread(() -> Toast.makeText(AudioInfoActivity.this, "Could not process audio file.", Toast.LENGTH_SHORT).show());
            }
        });
    }
}
