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
import com.example.test922.audio.processor.AudioExtractionListener;
import com.example.test922.audio.processor.AudioExtractor;
import com.example.test922.audio.processor.AudioPreprocessor;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements AudioExtractionListener, AudioPreprocessor.PreprocessingListener {

    private static final String TAG = "MainActivity";
    private Button extractAudioButton;
    private Button playAudioButton;
    private Button audioInfoButton;
    private Button preprocessAudioButton; // New button for preprocessing
    private TextView statusTextView;

    private Uri videoUri;
    private File extractedAudioFile;
    private File preprocessedAudioFile; // To store the path of the preprocessed file

    private MediaPlayer mediaPlayer;
    private final AudioExtractor audioExtractor = new AudioExtractor();
    private final AudioPreprocessor audioPreprocessor = new AudioPreprocessor(); // New preprocessor instance
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
                        preprocessAudioButton.setEnabled(false);
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
        preprocessAudioButton = findViewById(R.id.preprocess_audio_button); // Initialize the new button
        statusTextView = findViewById(R.id.status_text_view);

        extractAudioButton.setEnabled(false);
        playAudioButton.setEnabled(false);
        audioInfoButton.setEnabled(false);
        preprocessAudioButton.setEnabled(false); // Initially disabled

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

        preprocessAudioButton.setOnClickListener(v -> {
            if (extractedAudioFile != null && extractedAudioFile.exists()) {
                runOnUiThread(() -> statusTextView.setText(R.string.preprocessing_audio));
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String outputFileName = "preprocessed_audio_" + timestamp + ".wav";
                File outputFile = new File(getExternalFilesDir(null), outputFileName);
                executor.execute(() -> audioPreprocessor.process(extractedAudioFile, outputFile, this));
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
            preprocessAudioButton.setEnabled(true); // Enable preprocessing button
        });
    }

    @Override
    public void onExtractionFailure(String errorMessage) {
        runOnUiThread(() -> {
            statusTextView.setText(getString(R.string.extraction_failed, errorMessage));
            Log.e(TAG, "Extraction failed: " + errorMessage);
        });
    }

    // --- PreprocessingListener Callbacks ---

    @Override
    public void onPreprocessingSuccess(File processedFile, List<float[]> frames, int sampleRate) {
        this.preprocessedAudioFile = processedFile;
        runOnUiThread(() -> {
            statusTextView.setText(getString(R.string.preprocessing_succeeded, processedFile.getName()));
            Toast.makeText(this, "Preprocessing finished!", Toast.LENGTH_SHORT).show();
            // Optionally, enable a button to play the preprocessed audio
        });
    }

    @Override
    public void onPreprocessingFailed(String errorMessage) {
        runOnUiThread(() -> {
            statusTextView.setText(getString(R.string.preprocessing_failed, errorMessage));
            Log.e(TAG, "Preprocessing failed: " + errorMessage);
        });
    }
}
