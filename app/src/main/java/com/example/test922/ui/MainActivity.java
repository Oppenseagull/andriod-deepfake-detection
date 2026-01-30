package com.example.test922.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.example.test922.R;
import com.example.test922.audio.detector.DeepfakeDetector;
import com.example.test922.audio.detector.RawNet2Strategy;
import com.example.test922.audio.processor.AudioExtractionListener;
import com.example.test922.audio.processor.AudioExtractor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements AudioExtractionListener {

    private static final String TAG = "MainActivity";
    private static final String MODEL_ASSET_PATH = "rawnet2_mobile.ptl";
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private static final int SAMPLE_RATE = 16000;

    // 支持的音频格式
    private static final String[] SUPPORTED_AUDIO_EXTENSIONS = {".wav", ".mp3", ".flac", ".m4a", ".aac", ".ogg"};

    private Button selectVideoButton;
    private Button selectAudioButton;
    private Button extractAudioButton;
    private Button startDetectionButton;
    private Button playAudioButton;
    private Button audioInfoButton;

    private Button batchFolderButton;
    private final List<File> convertedBatchFiles = new ArrayList<>();
    private final List<String> originalBatchNames = new ArrayList<>(); // 对应每个批量文件的原始文件名
    private Button recordDetectionButton;
    private TextView statusTextView;
    private ProgressBar progressBar;
    private FrameLayout waveformContainer;
    private TextView recordingHint;

    // 录音相关
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private File recordedAudioFile;

    private Uri videoUri;
    private Uri audioUri;
    private File extractedAudioFile;
    private File selectedAudioFile;

    // 输入类型枚举
    private enum InputType { NONE, VIDEO, AUDIO, BATCH }
    private InputType currentInputType = InputType.NONE;

    private boolean isModelLoaded = false;

    private MediaPlayer mediaPlayer;
    private final AudioExtractor audioExtractor = new AudioExtractor();
    private DeepfakeDetector deepfakeDetector;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // 视频选择回调
    private final ActivityResultLauncher<Intent> selectVideoLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    videoUri = result.getData().getData();
                    if (videoUri != null) {
                        currentInputType = InputType.VIDEO;
                        audioUri = null;
                        selectedAudioFile = null;
                        extractedAudioFile = null;

                        String fileName = getFileName(videoUri);
                        statusTextView.setText(getString(R.string.video_selected, fileName));
                        updateButtonStates();
                    }
                }
            });

    // 音频选择回调
    private final ActivityResultLauncher<Intent> selectAudioLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    audioUri = result.getData().getData();
                    if (audioUri != null) {
                        String fileName = getFileName(audioUri);

                        // 检查文件格式是否支持
                        if (!isSupportedAudioFormat(fileName)) {
                            Toast.makeText(this, "不支持的音频格式，请选择 WAV/MP3/FLAC/M4A/AAC/OGG 文件", Toast.LENGTH_LONG).show();
                            return;
                        }

                        currentInputType = InputType.AUDIO;
                        videoUri = null;
                        extractedAudioFile = null;

                        // 将音频 Uri 复制到本地文件
                        copyAudioToLocalFile(audioUri, fileName);
                    }
                }
            });


// 批量选择文件夹回调
    private final ActivityResultLauncher<Intent> batchFolderLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri treeUri = result.getData().getData();
                    if (treeUri != null) {
                        handleBatchFolderSelection(treeUri);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化视图
        selectVideoButton = findViewById(R.id.select_video_button);
        selectAudioButton = findViewById(R.id.btn_select_audio);
        extractAudioButton = findViewById(R.id.extract_audio_button);
        startDetectionButton = findViewById(R.id.btn_start_detection);
        playAudioButton = findViewById(R.id.play_audio_button);
        audioInfoButton = findViewById(R.id.audio_info_button);
        batchFolderButton = findViewById(R.id.btn_batch_folder);
        recordDetectionButton = findViewById(R.id.btn_record_detection);
        statusTextView = findViewById(R.id.status_text_view);
        progressBar = findViewById(R.id.progress_bar);
        waveformContainer = findViewById(R.id.waveform_container);
        recordingHint = findViewById(R.id.recording_hint);

        // 初始状态
        updateButtonStates();

        // 初始化检测器
        initializeDetector();

        // 选取视频
        selectVideoButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("video/*");
            selectVideoLauncher.launch(intent);
        });

        // 选取音频
        selectAudioButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("audio/*");
            selectAudioLauncher.launch(intent);
        });

// 批量选取：选择文件夹
        batchFolderButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            batchFolderLauncher.launch(intent);
        });

        // 实时录音检测
        recordDetectionButton.setOnClickListener(v -> {
            if (isRecording) {
                stopRecordingAndDetect();
            } else {
                startRecording();
            }
        });

        // 提取音频（仅用于视频）
        extractAudioButton.setOnClickListener(v -> {
            if (videoUri != null) {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String outputFileName = "extracted_audio_" + timestamp + ".wav";
                File outputFile = new File(getExternalFilesDir(null), outputFileName);
                showProgress(true);
                executor.execute(() -> audioExtractor.extractAudio(MainActivity.this, videoUri, outputFile, this));
            }
        });

        // 开始检测
        startDetectionButton.setOnClickListener(v -> startDetection());

        // 播放音频
        playAudioButton.setOnClickListener(v -> {
            File audioToPlay = getAudioFileForPlayback();
            if (audioToPlay != null) {
                playAudio(audioToPlay);
            } else {
                Toast.makeText(this, "没有可播放的音频文件", Toast.LENGTH_SHORT).show();
            }
        });

        // 音频信息
        audioInfoButton.setOnClickListener(v -> {
            File audioFile = getAudioFileForPlayback();
            if (audioFile != null && audioFile.exists()) {
                Intent intent = new Intent(MainActivity.this, AudioInfoActivity.class);
                intent.putExtra(AudioInfoActivity.EXTRA_AUDIO_FILE_PATH, audioFile.getAbsolutePath());
                startActivity(intent);
            } else {
                Toast.makeText(this, "音频文件不存在", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 更新按钮状态
     */
    private void updateButtonStates() {
        boolean hasVideo = (currentInputType == InputType.VIDEO && videoUri != null);
        boolean hasAudio = (currentInputType == InputType.AUDIO && selectedAudioFile != null);
        boolean hasExtractedAudio = (extractedAudioFile != null && extractedAudioFile.exists());

        // 提取音频按钮：仅在选择了视频时可用
        extractAudioButton.setEnabled(hasVideo);

        // 开始检测按钮：有音频可用且模型已加载
        boolean canDetect = isModelLoaded && (hasAudio || hasExtractedAudio);
        startDetectionButton.setEnabled(canDetect);

        // 播放/信息按钮：有可用音频时启用
        boolean hasPlayableAudio = hasAudio || hasExtractedAudio;
        playAudioButton.setEnabled(hasPlayableAudio);
        audioInfoButton.setEnabled(hasPlayableAudio);
    }

    /**
     * 初始化 Deepfake 检测器
     */
    private void initializeDetector() {
        statusTextView.setText("正在加载检测模型...");
        showProgress(true);

        executor.execute(() -> {
            deepfakeDetector = new RawNet2Strategy();
            boolean loaded = deepfakeDetector.loadModel(this, MODEL_ASSET_PATH);
            runOnUiThread(() -> {
                showProgress(false);
                isModelLoaded = loaded;
                if (loaded) {
                    Log.i(TAG, "检测器初始化成功: " + deepfakeDetector.getName());
                    statusTextView.setText("模型加载成功，请选择视频或音频文件");
                    Toast.makeText(this, "模型加载成功", Toast.LENGTH_SHORT).show();
                } else {
                    Log.e(TAG, "检测器加载失败");
                    statusTextView.setText("⚠️ 模型加载失败\n请确保 assets 中有 rawnet2_mobile.ptl 文件");
                    Toast.makeText(this, "模型加载失败，检测功能不可用", Toast.LENGTH_LONG).show();
                }
                updateButtonStates();
            });
        });
    }

    /**
     * 开始检测 - 统一入口
     */
    private void startDetection() {
        // 检查模型是否加载
        if (!isModelLoaded || deepfakeDetector == null) {
            Toast.makeText(this, "模型未加载，请稍候或重启应用", Toast.LENGTH_LONG).show();
            return;
        }

        File audioToDetect = null;

        if (currentInputType == InputType.AUDIO && selectedAudioFile != null && selectedAudioFile.exists()) {
            // 直接选取的音频，跳过提取步骤
            audioToDetect = selectedAudioFile;
        } else if (extractedAudioFile != null && extractedAudioFile.exists()) {
            // 从视频提取的音频
            audioToDetect = extractedAudioFile;
        }

        if (audioToDetect == null) {
            Toast.makeText(this, "请先选择音频文件或从视频提取音频", Toast.LENGTH_SHORT).show();
            return;
        }

        // 执行检测
        final File finalAudioFile = audioToDetect;
        showProgress(true);
        statusTextView.setText("正在检测中，请稍候...");
        startDetectionButton.setEnabled(false);

        executor.execute(() -> performDeepfakeDetection(finalAudioFile));
    }

    /**
     * 执行 Deepfake 检测
     */
    private void performDeepfakeDetection(File audioFile) {
        long startTime = System.currentTimeMillis();

        // detect() 返回的是 Real（真实）的概率
        float realProbability = deepfakeDetector.detect(audioFile.getAbsolutePath());

        long elapsed = System.currentTimeMillis() - startTime;

        runOnUiThread(() -> {
            showProgress(false);
            startDetectionButton.setEnabled(true);

            if (realProbability < 0) {
                statusTextView.setText("❌ 检测失败\n请确保音频格式正确（需要 16kHz 单声道 WAV）");
                Toast.makeText(this, "检测失败", Toast.LENGTH_SHORT).show();
            } else {
                float realPercent = realProbability * 100;
                float fakePercent = (1 - realProbability) * 100;

                String conclusion;
                String emoji;
                float confidence;

                if (realProbability > 0.5) {
                    conclusion = "真实语音";
                    emoji = "✅";
                    confidence = realPercent;
                } else {
                    conclusion = "合成语音";
                    emoji = "⚠️";
                    confidence = fakePercent;
                }

                String resultText = String.format(Locale.US,
                        "%s 检测结果：%s\n\n" +
                        "置信度: %.1f%%\n\n" +
                        "━━━━━━━━━━━━━━━\n" +
                        "真实概率: %.2f%%\n" +
                        "伪造概率: %.2f%%\n" +
                        "━━━━━━━━━━━━━━━\n\n" +
                        "模型: %s\n" +
                        "耗时: %d ms",
                        emoji, conclusion,
                        confidence,
                        realPercent, fakePercent,
                        deepfakeDetector.getName(),
                        elapsed);

                statusTextView.setText(resultText);
                Toast.makeText(this,
                        String.format("检测结果：%s，置信度 %.0f%%", conclusion, confidence),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * 将选中的音频 Uri 复制到本地文件，并转换为 16kHz 单声道 WAV
     */
    private void copyAudioToLocalFile(Uri uri, String originalFileName) {
        showProgress(true);
        statusTextView.setText("正在处理音频文件...");

        executor.execute(() -> {
            try {
                // 生成本地文件名
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String extension = getFileExtension(originalFileName);
                String tempFileName = "temp_" + timestamp + extension;
                File tempFile = new File(getExternalFilesDir(null), tempFileName);

                // 先复制原始文件到临时位置
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(tempFile)) {
                    if (is == null) {
                        throw new IOException("无法打开输入流");
                    }
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }

                // 转换为 16kHz 单声道 WAV
                String convertedFileName = "converted_" + timestamp + ".wav";
                File convertedFile = new File(getExternalFilesDir(null), convertedFileName);

                runOnUiThread(() -> statusTextView.setText("正在转换音频格式...\n(16kHz 单声道 WAV)"));

                boolean converted = convertToModelFormat(tempFile, convertedFile);

                // 删除临时文件
                if (tempFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tempFile.delete();
                }

                if (converted && convertedFile.exists() && convertedFile.length() > 44) {
                    selectedAudioFile = convertedFile;
                    runOnUiThread(() -> {
                        showProgress(false);
                        statusTextView.setText(String.format(Locale.US,
                                "已选择音频: %s\n\n" +
                                "✅ 已转换为 16kHz 单声道 WAV\n" +
                                "文件大小: %.2f KB\n\n" +
                                "点击\"开始检测\"进行分析",
                                originalFileName,
                                convertedFile.length() / 1024.0));
                        updateButtonStates();
                    });
                } else {
                    throw new IOException("音频格式转换失败");
                }

            } catch (Exception e) {
                Log.e(TAG, "处理音频文件失败", e);
                runOnUiThread(() -> {
                    showProgress(false);
                    statusTextView.setText("处理音频文件失败: " + e.getMessage());
                    Toast.makeText(this, "无法处理音频文件", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 使用 FFmpeg 将音频转换为模型所需格式：16kHz 单声道 16-bit PCM WAV
     *
     * @param inputFile  输入音频文件（任意格式）
     * @param outputFile 输出 WAV 文件
     * @return 转换成功返回 true
     */
    private boolean convertToModelFormat(File inputFile, File outputFile) {
        if (inputFile == null || !inputFile.exists()) {
            Log.e(TAG, "convertToModelFormat: 输入文件不存在");
            return false;
        }

        // 如果输出文件已存在，先删除
        if (outputFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outputFile.delete();
        }

        // FFmpeg 命令：转换为 16kHz 单声道 16-bit PCM WAV
        // -y: 覆盖输出文件
        // -i: 输入文件
        // -ar 16000: 采样率 16kHz
        // -ac 1: 单声道
        // -acodec pcm_s16le: 16-bit PCM little-endian
        // -f wav: 输出格式 WAV
        String command = String.format(Locale.US,
                "-y -i \"%s\" -ar %d -ac 1 -acodec pcm_s16le -f wav \"%s\"",
                inputFile.getAbsolutePath(),
                SAMPLE_RATE,
                outputFile.getAbsolutePath());

        Log.d(TAG, "FFmpeg 转换命令: " + command);

        try {
            FFmpegSession session = FFmpegKit.execute(command);
            boolean success = ReturnCode.isSuccess(session.getReturnCode());

            if (success) {
                Log.i(TAG, "音频转换成功: " + outputFile.getAbsolutePath() +
                        " (" + outputFile.length() / 1024 + " KB)");
            } else {
                Log.e(TAG, "FFmpeg 转换失败, returnCode=" + session.getReturnCode() +
                        ", output=" + session.getOutput());
            }

            return success;
        } catch (Exception e) {
            Log.e(TAG, "FFmpeg 执行异常", e);
            return false;
        }
    }

    /**
     * 处理批量文件夹选择
     */
    private void handleBatchFolderSelection(Uri treeUri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (Exception ignored) {}

        currentInputType = InputType.BATCH;
        statusTextView.setText("已选择文件夹，正在扫描音频文件...");
        showProgress(true);

        executor.execute(() -> prepareBatchFilesFromFolder(treeUri));
    }
    /**
     * 遍历选中文件夹，收集并转换其中的音频文件
     */
    private void prepareBatchFilesFromFolder(Uri treeUri) {
        convertedBatchFiles.clear();
        originalBatchNames.clear();

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
        );

        try (Cursor cursor = getContentResolver().query(
                childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                },
                null, null, null
        )) {
            if (cursor != null) {
                int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);

                while (cursor.moveToNext()) {
                    String docId = cursor.getString(idIndex);
                    String name = cursor.getString(nameIndex);
                    String mime = cursor.getString(mimeIndex);

                    // 跳过子目录
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        continue;
                    }

                    if (!isSupportedAudioFormat(name)) {
                        continue;
                    }

                    Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);

                    runOnUiThread(() -> statusTextView.setText("正在处理: " + name));

                    try {
                        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                                .format(new Date());
                        String extension = getFileExtension(name);
                        File tempFile = new File(
                                getExternalFilesDir(null),
                                "batch_temp_" + timestamp + extension
                        );

                        try (InputStream is = getContentResolver().openInputStream(fileUri);
                             FileOutputStream fos = new FileOutputStream(tempFile)) {
                            if (is != null) {
                                byte[] buffer = new byte[8192];
                                int bytesRead;
                                while ((bytesRead = is.read(buffer)) != -1) {
                                    fos.write(buffer, 0, bytesRead);
                                }
                            }
                        }

                        File convertedFile = new File(
                                getExternalFilesDir(null),
                                "batch_converted_" + timestamp + ".wav"
                        );

                        boolean converted = convertToModelFormat(tempFile, convertedFile);
                        if (tempFile.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            tempFile.delete();
                        }

                        if (converted && convertedFile.exists() && convertedFile.length() > 44) {
                            convertedBatchFiles.add(convertedFile);
                            originalBatchNames.add(name); // 记录原始文件名
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "批量处理单个文件失败: " + name, e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "扫描文件夹失败", e);
        }

        runOnUiThread(() -> {
            showProgress(false);
            if (convertedBatchFiles.isEmpty()) {
                statusTextView.setText("选中的文件夹中没有可用的音频文件");
            } else {
                statusTextView.setText("已准备 " + convertedBatchFiles.size()
                        + " 个文件，开始批量检测...");
                startBatchDetection();
            }
        });
    }
    /**
     * 执行批量检测，对文件夹中准备好的所有音频进行检测
     */
    private void startBatchDetection() {
        if (!isModelLoaded || deepfakeDetector == null) {
            Toast.makeText(this, "模型未加载", Toast.LENGTH_SHORT).show();
            return;
        }
        if (convertedBatchFiles.isEmpty()) {
            Toast.makeText(this, "没有待检测的文件", Toast.LENGTH_SHORT).show();
            return;
        }

        showProgress(true);
        statusTextView.setText("批量检测进行中...");

        executor.execute(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("批量检测结果:\n\n");

            for (int i = 0; i < convertedBatchFiles.size(); i++) {
                File audioFile = convertedBatchFiles.get(i);
                String displayName = (i < originalBatchNames.size())
                        ? originalBatchNames.get(i)
                        : audioFile.getName(); // 兜底

                float realProbability = deepfakeDetector.detect(audioFile.getAbsolutePath());

                if (realProbability >= 0) {
                    float realPercent = realProbability * 100;
                    float fakePercent = (1 - realProbability) * 100;
                    String conclusion = realProbability > 0.5f ? "真实" : "伪造";
                    float confidence = realProbability > 0.5f ? realPercent : fakePercent;
                    sb.append(String.format(Locale.US,
                            "%d. %s -> %s, 置信度 %.1f%% (Real: %.1f%% / Fake: %.1f%%)\n",
                            i + 1, displayName, conclusion, confidence, realPercent, fakePercent));
                } else {
                    sb.append(String.format(Locale.US,
                            "%d. %s -> 检测失败\n", i + 1, displayName));
                }
            }

            String resultText = sb.toString();
            runOnUiThread(() -> {
                showProgress(false);
                statusTextView.setText(resultText);
            });
        });
    }

    /**
     * 开始录音
     */
    private void startRecording() {
        // 检查权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO_PERMISSION);
            return;
        }

        if (!isModelLoaded) {
            Toast.makeText(this, "模型未加载，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Toast.makeText(this, "无法初始化录音器", Toast.LENGTH_SHORT).show();
                return;
            }

            isRecording = true;
            waveformContainer.setVisibility(View.VISIBLE);
            recordingHint.setText("🎙️ 录音中... 点击停止");
            recordDetectionButton.setText("停止录音");
            recordDetectionButton.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, android.R.color.holo_red_dark));

            // 准备录音文件
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            recordedAudioFile = new File(getExternalFilesDir(null), "recorded_" + timestamp + ".wav");

            audioRecord.startRecording();

            // 在后台线程写入数据
            executor.execute(this::writeAudioDataToFile);

            Toast.makeText(this, "开始录音，说完后点击停止", Toast.LENGTH_SHORT).show();

        } catch (SecurityException e) {
            Log.e(TAG, "录音权限被拒绝", e);
            Toast.makeText(this, "需要录音权限", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 将录音数据写入 WAV 文件
     */
    private void writeAudioDataToFile() {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        byte[] buffer = new byte[bufferSize];
        List<byte[]> audioChunks = new ArrayList<>();

        while (isRecording && audioRecord != null) {
            int bytesRead = audioRecord.read(buffer, 0, buffer.length);
            if (bytesRead > 0) {
                byte[] chunk = new byte[bytesRead];
                System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                audioChunks.add(chunk);
            }
        }

        // 写入 WAV 文件
        try {
            int totalDataSize = 0;
            for (byte[] chunk : audioChunks) {
                totalDataSize += chunk.length;
            }

            try (FileOutputStream fos = new FileOutputStream(recordedAudioFile)) {
                // 写入 WAV 头
                writeWavHeader(fos, totalDataSize, SAMPLE_RATE, 1, 16);
                // 写入音频数据
                for (byte[] chunk : audioChunks) {
                    fos.write(chunk);
                }
            }

            Log.i(TAG, "录音保存成功: " + recordedAudioFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "保存录音失败", e);
        }
    }

    /**
     * 写入 WAV 文件头
     */
    private void writeWavHeader(FileOutputStream out, int totalAudioLen,
                                 int sampleRate, int channels, int bitsPerSample) throws IOException {
        int totalDataLen = totalAudioLen + 36;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        byte[] header = new byte[44];

        // RIFF chunk
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';

        // fmt chunk
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; // chunk size
        header[20] = 1; header[21] = 0; // audio format (PCM)
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) blockAlign; header[33] = 0;
        header[34] = (byte) bitsPerSample; header[35] = 0;

        // data chunk
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        out.write(header, 0, 44);
    }

    /**
     * 停止录音并开始检测
     */
    private void stopRecordingAndDetect() {
        isRecording = false;

        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "停止录音时出错", e);
            }
            audioRecord = null;
        }

        waveformContainer.setVisibility(View.GONE);
        recordDetectionButton.setText("实时检测");
        recordDetectionButton.setBackgroundTintList(
                ContextCompat.getColorStateList(this, android.R.color.holo_orange_dark));

        // 等待文件写入完成后检测
        executor.execute(() -> {
            try {
                Thread.sleep(500); // 等待文件写入完成
            } catch (InterruptedException ignored) {}

            if (recordedAudioFile != null && recordedAudioFile.exists() && recordedAudioFile.length() > 44) {
                runOnUiThread(() -> {
                    selectedAudioFile = recordedAudioFile;
                    currentInputType = InputType.AUDIO;
                    statusTextView.setText("录音完成，正在检测...");
                    startDetection();
                });
            } else {
                runOnUiThread(() -> {
                    statusTextView.setText("录音太短或保存失败，请重试");
                    Toast.makeText(this, "录音失败", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording();
            } else {
                Toast.makeText(this, "需要录音权限才能使用此功能", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // --- AudioExtractionListener Callbacks ---

    @Override
    public void onExtractionStarted() {
        runOnUiThread(() -> {
            showProgress(true);
            statusTextView.setText(R.string.extracting_audio);
        });
    }

    @Override
    public void onExtractionSuccess(File audioFile) {
        this.extractedAudioFile = audioFile;
        runOnUiThread(() -> {
            showProgress(false);
            statusTextView.setText(getString(R.string.extraction_succeeded, audioFile.getName()) +
                    "\n\n点击\"开始检测\"进行分析");
            Toast.makeText(this, getString(R.string.extraction_succeeded_toast), Toast.LENGTH_SHORT).show();
            updateButtonStates();
        });
    }

    @Override
    public void onExtractionFailure(String errorMessage) {
        runOnUiThread(() -> {
            showProgress(false);
            statusTextView.setText(getString(R.string.extraction_failed, errorMessage));
            Log.e(TAG, "提取失败: " + errorMessage);
            updateButtonStates();
        });
    }

    /**
     * 获取可播放的音频文件
     */
    private File getAudioFileForPlayback() {
        if (currentInputType == InputType.AUDIO && selectedAudioFile != null && selectedAudioFile.exists()) {
            return selectedAudioFile;
        }
        if (extractedAudioFile != null && extractedAudioFile.exists()) {
            return extractedAudioFile;
        }
        return null;
    }

    /**
     * 从 Uri 获取文件名
     */
    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        result = cursor.getString(index);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            }
        }
        return result != null ? result : "unknown";
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        if (fileName == null) return ".wav";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(dotIndex).toLowerCase(Locale.US);
        }
        return ".wav";
    }

    /**
     * 检查是否是支持的音频格式
     */
    private boolean isSupportedAudioFormat(String fileName) {
        if (fileName == null) return false;
        String lowerName = fileName.toLowerCase(Locale.US);
        for (String ext : SUPPORTED_AUDIO_EXTENSIONS) {
            if (lowerName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 显示/隐藏进度条
     */
    private void showProgress(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * 播放音频文件
     */
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
                Toast.makeText(this, "正在播放...", Toast.LENGTH_SHORT).show();
                mediaPlayer.setOnCompletionListener(mp -> {
                    mp.release();
                    mediaPlayer = null;
                    Toast.makeText(this, "播放完成", Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                Log.e(TAG, "播放失败", e);
                Toast.makeText(this, "播放失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
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
        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }
    }
}
