package com.example.test922.ui;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.test922.R;
import com.example.test922.audio.detector.DeepfakeDetector;
import com.example.test922.audio.detector.RawNet2Strategy;
import com.example.test922.audio.processor.AudioExtractionListener;
import com.example.test922.audio.processor.AudioExtractor;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements AudioExtractionListener {

    private static final String TAG = "MainActivity";
    private static final String MODEL_ASSET_PATH = "rawnet2.ptl"; // 模型文件路径

    private Button extractAudioButton;
    private Button playAudioButton;
    private Button audioInfoButton;
    private Button detectDeepfakeButton; // 改为检测按钮
    private TextView statusTextView;

    private Uri videoUri;
    private File extractedAudioFile;

    private MediaPlayer mediaPlayer;
    private final AudioExtractor audioExtractor = new AudioExtractor();
    private DeepfakeDetector deepfakeDetector; // 使用策略模式的检测器
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<Intent> selectVideoLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    videoUri = result.getData().getData();
                    if (videoUri != null) {
                        statusTextView.setText(getString(R.string.video_selected, videoUri.getPath()));
                        extractAudioButton.setEnabled(true);
                        // Disable other buttons until extraction is complete
                        playAudioButton.setEnabled(false);
                        audioInfoButton.setEnabled(false);
                        detectDeepfakeButton.setEnabled(false);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button selectVideoButton = findViewById(R.id.select_video_button);
        extractAudioButton = findViewById(R.id.extract_audio_button);
        playAudioButton = findViewById(R.id.play_audio_button);
        audioInfoButton = findViewById(R.id.audio_info_button);
        detectDeepfakeButton = findViewById(R.id.preprocess_audio_button); // 复用原来的按钮
        statusTextView = findViewById(R.id.status_text_view);

        extractAudioButton.setEnabled(false);
        playAudioButton.setEnabled(false);
        audioInfoButton.setEnabled(false);
        detectDeepfakeButton.setEnabled(false);

        // 初始化 Deepfake 检测器（使用 RawNet2 策略）
        initializeDetector();

        // Simplified video selection - no manual permission checks needed
        selectVideoButton.setOnClickListener(v -> openVideoSelector());

        extractAudioButton.setOnClickListener(v -> {
            if (videoUri != null) {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String outputFileName = "extracted_audio_" + timestamp + ".wav";
                File outputFile = new File(getExternalFilesDir(null), outputFileName);
                executor.execute(() -> audioExtractor.extractAudio(MainActivity.this, videoUri, outputFile, this));
            }
        });

        detectDeepfakeButton.setOnClickListener(v -> {
            if (extractedAudioFile != null && extractedAudioFile.exists()) {
                runOnUiThread(() -> statusTextView.setText(R.string.preprocessing_audio));
                executor.execute(() -> performDeepfakeDetection(extractedAudioFile));
            } else {
                Toast.makeText(this, "Extracted audio file not found.", Toast.LENGTH_SHORT).show();
            }
        });

        playAudioButton.setOnClickListener(v -> playAudio(extractedAudioFile));

        audioInfoButton.setOnClickListener(v -> {
            if (extractedAudioFile != null && extractedAudioFile.exists()) {
                Intent intent = new Intent(MainActivity.this, AudioInfoActivity.class);
                intent.putExtra(AudioInfoActivity.EXTRA_AUDIO_FILE_PATH, extractedAudioFile.getAbsolutePath());
                startActivity(intent);
            } else {
                Toast.makeText(this, "Audio file not found.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 初始化 Deepfake 检测器
     */
    private void initializeDetector() {
        executor.execute(() -> {
            deepfakeDetector = new RawNet2Strategy();
            boolean loaded = deepfakeDetector.loadModel(this, MODEL_ASSET_PATH);
            runOnUiThread(() -> {
                if (loaded) {
                    Log.i(TAG, "Deepfake 检测器初始化成功: " + deepfakeDetector.getName());
                } else {
                    Log.w(TAG, "Deepfake 检测器加载失败，检测功能可能不可用");
                }
            });
        });
    }

    /**
     * 执行 Deepfake 检测
     */
    private void performDeepfakeDetection(File audioFile) {
        if (deepfakeDetector == null) {
            runOnUiThread(() -> {
                statusTextView.setText(getString(R.string.preprocessing_failed, "检测器未初始化"));
            });
            return;
        }

        float fakeProbability = deepfakeDetector.detect(audioFile.getAbsolutePath());

        runOnUiThread(() -> {
            if (fakeProbability < 0) {
                statusTextView.setText(getString(R.string.preprocessing_failed, "检测失败"));
            } else {
                String resultText = String.format(Locale.US,
                        "检测完成 [%s]\nFake 概率: %.2f%%\n结论: %s",
                        deepfakeDetector.getName(),
                        fakeProbability * 100,
                        fakeProbability > 0.5 ? "可能是伪造音频" : "可能是真实音频");
                statusTextView.setText(resultText);
                Toast.makeText(this, "检测完成!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openVideoSelector() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("video/*");
        selectVideoLauncher.launch(intent);
    }

    private void playAudio(File audioFile) {
        if (audioFile != null && audioFile.exists()) {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            mediaPlayer = new MediaPlayer();
            try {
                mediaPlayer.setDataSource(audioFile.getAbsolutePath());
                mediaPlayer.prepare();
                mediaPlayer.start();
                Toast.makeText(this, "Playing audio...", Toast.LENGTH_SHORT).show();
                mediaPlayer.setOnCompletionListener(mp -> {
                    mp.release();
                    mediaPlayer = null;
                    Toast.makeText(this, "Playback finished.", Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                Log.e(TAG, "Failed to play audio", e);
                Toast.makeText(this, "Failed to play audio.", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Audio file not found.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    // --- AudioExtractionListener Callbacks ---

    @Override
    public void onExtractionStarted() {
        runOnUiThread(() -> statusTextView.setText(R.string.extracting_audio));
    }

    @Override
    public void onExtractionSuccess(File audioFile) {
        this.extractedAudioFile = audioFile;
        runOnUiThread(() -> {
            statusTextView.setText(getString(R.string.extraction_succeeded, extractedAudioFile.getAbsolutePath()));
            Toast.makeText(this, getString(R.string.extraction_succeeded_toast), Toast.LENGTH_SHORT).show();
            playAudioButton.setEnabled(true);
            audioInfoButton.setEnabled(true);
            detectDeepfakeButton.setEnabled(true);
        });
    }

    @Override
    public void onExtractionFailure(String errorMessage) {
        runOnUiThread(() -> {
            statusTextView.setText(getString(R.string.extraction_failed, errorMessage));
            Log.e(TAG, "Extraction failed: " + errorMessage);
        });
    }
}
