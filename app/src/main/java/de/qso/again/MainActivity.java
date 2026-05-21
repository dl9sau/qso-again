package de.qso.again;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.Toolbar;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity implements AudioRecorderService.RecordingListener {
    private AudioRecorderService recorderService;
    private boolean serviceBound = false;
    private ExecutorService playbackExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2);
    
    private TextView tvStatus, tvRecordingTime, tvPlaybackSpeed, tvPlaybackProgress;
    private ProgressBar progressRecording;
    private SeekBar seekBarPlayback;
    private Button btnPlay, btnStartStop, btnSave;
    private Button btn0s, btn2s, btn5s, btn10s, btn20s, btn30s;
    private Button btn1m, btn2m, btn5m, btn10m, btn30m, btn60m;
    private Button btnBeginning;
    private Button btnSpeedDown, btnSpeedDefault, btnSpeedUp;
    private RecyclerView rvRecordings;
    private RecordingsAdapter recordingsAdapter;
    
    private final List<String> savedRecordings = new ArrayList<>();
    private int maxDurationMinutes = 10;
    private int recordingSampleRate = 22050;
    private int recordingBitDepth = 16;
    private int reactionTimeMs = 5000;
    private boolean suppressSaveOffset = false;

    private final float[] speedLevels = {0.5f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.5f};
    private int currentSpeedIndex = 4;
    private float playbackSpeed = 1.0f;
    private AtomicBoolean isPlaying = new AtomicBoolean(false);
    private int playbackOffset = 0;
    private int playbackLength = 0;
    private int playbackStartOffset = 0;
    private int lastSavedOffset = 0;
    
    private byte[] currentRecording = null;
    private long recordingStartTime = 0;
    private long recordingEndTime = 0;
    private AudioTrack audioTrack = null;
    private Runnable pendingPlaybackAction = null;
    private Handler handler = new Handler(Looper.getMainLooper());
    
    private final ActivityResultLauncher<String[]> requestPermissionsLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), results -> {
            boolean audioGranted = results.getOrDefault(Manifest.permission.RECORD_AUDIO, false);
            if (audioGranted) {
                bindRecorderService();
            } else {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AudioRecorderService.LocalBinder binder = (AudioRecorderService.LocalBinder) service;
            recorderService = binder.getService();
            recorderService.setRecordingListener(MainActivity.this);
            serviceBound = true;
            loadSettings();
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };
    
    private String lastAppliedLang;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SettingsActivity.applyLanguageStatic(this);
        lastAppliedLang = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
                .getString("language", "system");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        initViews();
        setupClickListeners();
        loadSavedRecordings();
        updatePlaybackUI(false);
        
        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // Android 9 und aelter: WRITE_EXTERNAL_STORAGE noetig fuer Speichern in Downloads.
            // WRITE impliziert READ, also reicht ein Request.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        } else {
            // Android 10+ (API 29+): WRITE ist im Manifest nicht mehr deklariert (maxSdkVersion=28),
            // wir brauchen nur READ_EXTERNAL_STORAGE.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        
        if (!permissions.isEmpty()) {
            requestPermissionsLauncher.launch(permissions.toArray(new String[0]));
        } else {
            bindRecorderService();
        }
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_quit) {
            showQuitDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    @Override
    public void onBackPressed() {
        showQuitDialog();
    }
    
    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvRecordingTime = findViewById(R.id.tvRecordingTime);
        progressRecording = findViewById(R.id.progressRecording);
        seekBarPlayback = findViewById(R.id.seekBarPlayback);
        tvPlaybackSpeed = findViewById(R.id.tvPlaybackSpeed);
        tvPlaybackProgress = findViewById(R.id.tvPlaybackProgress);
        btnPlay = findViewById(R.id.btnPlay);
        btnStartStop = findViewById(R.id.btnStartStop);
        btnSave = findViewById(R.id.btnSave);
        btn0s = findViewById(R.id.btn0s);
        btn2s = findViewById(R.id.btn2s);
        btn5s = findViewById(R.id.btn5s);
        btn10s = findViewById(R.id.btn10s);
        btn20s = findViewById(R.id.btn20s);
        btn30s = findViewById(R.id.btn30s);
        btn1m = findViewById(R.id.btn1m);
        btn2m = findViewById(R.id.btn2m);
        btn5m = findViewById(R.id.btn5m);
        btn10m = findViewById(R.id.btn10m);
        btn30m = findViewById(R.id.btn30m);
        btn60m = findViewById(R.id.btn60m);
        btnBeginning = findViewById(R.id.btnBeginning);
        btnSpeedDown = findViewById(R.id.btnSpeedDown);
        btnSpeedDefault = findViewById(R.id.btnSpeedDefault);
        btnSpeedUp = findViewById(R.id.btnSpeedUp);
        rvRecordings = findViewById(R.id.rvRecordings);
    }
    
    private void setupClickListeners() {
        View.OnClickListener seekBackListener = v -> {
            String tag = v.getTag() != null ? v.getTag().toString() : "";
            if (tag.isEmpty()) return;
            int seconds = parseDuration(tag);
            Runnable action = () -> {
                seekBackFromEnd(seconds);
                btnPlay.setText(R.string.stop_playback);
            };
            if (recorderService != null && recorderService.isRecording()) {
                pendingPlaybackAction = action;
                stopRecording();
            } else {
                if (isPlaying.get()) stopPlayback();
                action.run();
            }
        };
        
        btn2s.setOnClickListener(seekBackListener);
        btn5s.setOnClickListener(seekBackListener);
        btn10s.setOnClickListener(seekBackListener);
        btn20s.setOnClickListener(seekBackListener);
        btn30s.setOnClickListener(seekBackListener);
        btn1m.setOnClickListener(seekBackListener);
        btn2m.setOnClickListener(seekBackListener);
        btn5m.setOnClickListener(seekBackListener);
        btn10m.setOnClickListener(seekBackListener);
        btn30m.setOnClickListener(seekBackListener);
        btn60m.setOnClickListener(seekBackListener);
        
        View.OnClickListener seekToEndpointListener = v -> {
            final boolean toBeginning = (v.getId() == R.id.btnBeginning);
            Runnable action = () -> seekToEndpoint(toBeginning);
            if (recorderService != null && recorderService.isRecording()) {
                pendingPlaybackAction = action;
                stopRecording();
            } else {
                if (isPlaying.get()) stopPlayback();
                action.run();
            }
        };
        btn0s.setOnClickListener(seekToEndpointListener);
        btnBeginning.setOnClickListener(seekToEndpointListener);

        btnStartStop.setOnClickListener(v -> toggleRecording());
        btnPlay.setOnClickListener(v -> {
            Runnable startPlayback = () -> {
                int recordingSize = (currentRecording != null) ? currentRecording.length : 0;
                if (recordingSize > 0) {
                    int startPos = playbackOffset;
                    if (startPos < 0 || startPos >= recordingSize - 1000) {
                        startPos = 0;
                        playbackOffset = 0;
                    }
                    playFromOffset(startPos, recordingSize - startPos);
                    btnPlay.setText(R.string.stop_playback);
                } else {
                    Toast.makeText(this, R.string.no_recording, Toast.LENGTH_SHORT).show();
                }
            };
            if (recorderService != null && recorderService.isRecording()) {
                pendingPlaybackAction = startPlayback;
                stopRecording();
            } else if (isPlaying.get()) {
                stopPlayback();
                btnPlay.setText(R.string.play);
            } else {
                startPlayback.run();
            }
        });
        btnSave.setOnClickListener(v -> {
            if (recorderService != null && recorderService.isRecording()) {
                if (recorderService.getTotalBytesWritten() <= 0) {
                    Toast.makeText(this, "No recording to save", Toast.LENGTH_SHORT).show();
                    return;
                }
                AudioRecorderService.Snapshot snap = recorderService.snapshotAndReset();
                recordingStartTime = recorderService.getRecordingStartTime();
                saveSnapshotAsync(snap);
                return;
            }
            
            int alignMask = (recordingBitDepth == 8) ? ~0 : ~1;
            if (isPlaying.get()) {
                int bytesBack = reactionTimeMs * recordingSampleRate * bytesPerSample() / 1000;
                int newOffset = Math.max(0, playbackOffset - bytesBack) & alignMask;
                saveWithOffset(newOffset);
            } else {
                int recordingSize = (currentRecording != null) ? currentRecording.length : 0;
                int saveFrom;
                if (recordingSize > 0 && playbackOffset >= recordingSize - 1000) {
                    saveFrom = playbackStartOffset;
                } else if (playbackOffset > 0 && playbackOffset < recordingSize) {
                    saveFrom = playbackOffset;
                } else {
                    saveFrom = lastSavedOffset;
                }
                if (!suppressSaveOffset) {
                    int bytesBack = reactionTimeMs * recordingSampleRate * bytesPerSample() / 1000;
                    saveFrom = Math.max(0, saveFrom - bytesBack) & alignMask;
                }
                saveWithOffset(saveFrom);
            }
        });
        
        btnSpeedDown.setOnClickListener(v -> setSpeedIndex(currentSpeedIndex - 1));
        btnSpeedDefault.setOnClickListener(v -> setSpeedIndex(4));
        btnSpeedUp.setOnClickListener(v -> setSpeedIndex(currentSpeedIndex + 1));
        
        seekBarPlayback.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    long recordingSize = (currentRecording != null) ? currentRecording.length : 0;
                    int sampleRate = recordingSampleRate;
                    int bps = bytesPerSample();
                    int alignMask = (recordingBitDepth == 8) ? ~0 : ~1;
                    if (recordingSize > 0) {
                        long newOffset = Math.min(recordingSize - 1, (recordingSize * progress) / 100);
                        newOffset = newOffset & alignMask;
                        playbackOffset = (int) newOffset;
                        suppressSaveOffset = false;
                        long offsetMs = newOffset * 1000 / sampleRate / bps;
                        long totalMs = recordingSize * 1000 / sampleRate / bps;
                        tvPlaybackProgress.setText(formatDuration(offsetMs) + " / " + formatDuration(totalMs));
                    }
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (isPlaying.get()) {
                    stopPlayback();
                }
            }
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int recordingSize = (currentRecording != null) ? currentRecording.length : 0;
                if (recordingSize > 0) {
                    if (seekBar.getProgress() <= 0) {
                        // Dragged to the very beginning — do not auto-play; user must press play.
                        btnPlay.setText(R.string.play);
                    } else {
                        playbackLength = recordingSize - playbackOffset;
                        playFromOffset(playbackOffset, playbackLength);
                        btnPlay.setText(R.string.stop_playback);
                    }
                }
            }
        });
    }
    
    private void setSeekButtonsEnabledForSeconds(int seconds) {
        Button[] buttons = {btn2s, btn5s, btn10s, btn20s, btn30s, btn1m, btn2m, btn5m, btn10m, btn30m, btn60m};
        for (Button btn : buttons) {
            String tag = btn.getTag() != null ? btn.getTag().toString() : "";
            int sec = parseDuration(tag);
            btn.setEnabled(sec > 0 && sec <= seconds);
        }
    }

    private void updateButtonStates() {
        setSeekButtonsEnabledForSeconds(maxDurationMinutes * 60);
    }
    
    private void bindRecorderService() {
        Intent intent = new Intent(this, AudioRecorderService.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }
    
    private void toggleRecording() {
        if (recorderService == null) return;
        
        if (recorderService.isRecording()) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    private void tvStatus_display(int recordingStateRes) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int configMin = Integer.parseInt(prefs.getString("max_duration", "5"));
        int sampleRate = recorderService != null ? recorderService.getSampleRate() : recordingSampleRate;
        int bitDepth = recorderService != null ? recorderService.getBitDepth() : recordingBitDepth;
        long bytesPerSecond = (long) sampleRate * (bitDepth == 8 ? 1 : 2);
        long usableBytes = AudioRecorderService.getUsableBytesForRecording();
        long ramMaxMin = usableBytes / bytesPerSecond / 60;
        boolean ramLimited = configMin > ramMaxMin;
        long displayMin = ramLimited ? ramMaxMin : configMin;
        String tilde = ramLimited ? "~" : "";

	String sLoopTime = " (loop max " + tilde + SettingsActivity.formatDuration((int) displayMin) + ")";

        tvStatus.setText(getString(recordingStateRes) + sLoopTime);
    }

    
    private void startRecording() {
        if (recorderService == null) return;
        
        stopPlayback();
        currentRecording = null;
        playbackOffset = 0;
        playbackLength = 0;
        recordingStartTime = System.currentTimeMillis();
        recordingEndTime = 0;
        recorderService.setSampleRate(recordingSampleRate);
        recorderService.setMaxDurationMinutes(maxDurationMinutes);
        maxDurationMinutes = recorderService.getMaxDurationMinutes();
        recorderService.startRecording();
        
        progressRecording.setProgress(0);
        tvRecordingTime.setText("0:00");
        tvPlaybackProgress.setText("0:00 / 0:00");
        seekBarPlayback.setProgress(0);
        tvStatus_display(R.string.recording);

        handler.post(progressUpdateRunnable);
        updatePlaybackUI(false);
        btnSave.setEnabled(true);  // Enable save during recording
        btnStartStop.setText(R.string.stop);
        btnStartStop.setBackgroundTintList(
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.recording_red)));
        //tvStatus.setText(R.string.recording);
    }
    
    private void stopRecording() {
        if (recorderService == null) return;
        
        handler.removeCallbacks(progressUpdateRunnable);
        recorderService.stopRecording();
        recordingEndTime = System.currentTimeMillis();
        
        progressRecording.setProgress(0);
        tvRecordingTime.setText("0:00");
        //tvStatus.setText(R.string.recording_stopped);

        tvStatus_display(R.string.recording_stopped);
        btnStartStop.setText(R.string.record);
        btnStartStop.setBackgroundTintList(null);
        btnSave.setEnabled(true);
        
        setSeekButtonsEnabledForSeconds(maxDurationMinutes * 60);
    }

    private void updatePlaybackUI(boolean hasRecording) {
        boolean recording = recorderService != null && recorderService.isRecording();
        btnSpeedDown.setEnabled(hasRecording);
        btnSpeedDefault.setEnabled(hasRecording);
        btnSpeedUp.setEnabled(hasRecording);
        seekBarPlayback.setEnabled(hasRecording);
        btnSave.setEnabled(hasRecording || recording);
        btn0s.setEnabled(hasRecording);
        btnBeginning.setEnabled(hasRecording);

        if (hasRecording) {
            updateButtonStates();
            updatePlaybackProgressDisplay();
        } else {
            btnPlay.setText(R.string.play);
        }
    }
    
    private void loadSettings() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        maxDurationMinutes = Integer.parseInt(prefs.getString("max_duration", "5"));
        recordingSampleRate = Integer.parseInt(prefs.getString("sample_rate", "44100"));
        recordingBitDepth = Integer.parseInt(prefs.getString("bit_depth", "16"));
        boolean autoSave = prefs.getBoolean("auto_save", false);

        if (recorderService != null) {
            recorderService.setSampleRate(recordingSampleRate);
            recorderService.setBitDepth(recordingBitDepth);
            recorderService.setMaxDurationMinutes(maxDurationMinutes);
            recorderService.setAutoSaveEnabled(autoSave);
            maxDurationMinutes = recorderService.getMaxDurationMinutes();
        }

        updateButtonStates();
    }

    private int bytesPerSample() {
        return recordingBitDepth == 8 ? 1 : 2;
    }

    private void loadSavedRecordings() {
        File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        File[] files = downloadsDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith("qso-again--") && name.endsWith(".wav");
            }
        });
        
        savedRecordings.clear();
        if (files != null) {
            Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
            for (File f : files) {
                if (savedRecordings.size() < 10) savedRecordings.add(f.getName());
            }
        }
        
        recordingsAdapter = new RecordingsAdapter(savedRecordings, this::onRecordingClicked, this::onRecordingDeleted);
        rvRecordings.setLayoutManager(new LinearLayoutManager(this));
        rvRecordings.setAdapter(recordingsAdapter);
    }
    
    private void onRecordingClicked(String filename) {
        File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        File file = new File(downloadsDir, filename);
        
        try {
            byte[] pcmData = loadWavFile(file);
            currentRecording = pcmData;
            playbackLength = pcmData.length;
            recordingStartTime = parseFilenameTimestamp(filename);
            playbackOffset = 0;
            lastSavedOffset = 0;
            playbackStartOffset = 0;
            suppressSaveOffset = false;
            
            handler.post(() -> {
                Toast.makeText(this, "Loaded: " + file.getName(), Toast.LENGTH_SHORT).show();
                updatePlaybackUI(true);
                seekBarPlayback.setProgress(0);
                long totalMs = (long) pcmData.length * 1000 / bytesPerSample() / recordingSampleRate;
                tvPlaybackProgress.setText("0:00 / " + formatDuration(totalMs));
            });
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error loading: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void onRecordingDeleted(String filename) {
        new AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("Delete " + filename + "?")
            .setPositiveButton(R.string.yes, (d, w) -> {
                File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                new File(downloadsDir, filename).delete();
                loadSavedRecordings();
            })
            .setNegativeButton(R.string.no, null)
            .show();
    }
    
    private long parseFilenameTimestamp(String filename) {
        try {
            String ts = filename.replace("qso-again--", "").replace(".wav", "");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd--HH:mm:ss", Locale.getDefault());
            return sdf.parse(ts).getTime();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
    
    private byte[] loadWavFile(File file) throws Exception {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] riff = new byte[12];
            if (fis.read(riff) != 12) throw new Exception("Invalid WAV");

            int formatCode = 1;
            int sampleRate = 0;
            int bitsPerSample = 16;
            byte[] data = null;

            byte[] chunkHeader = new byte[8];
            while (fis.read(chunkHeader) == 8) {
                String id = new String(chunkHeader, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
                int size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();

                if ("fmt ".equals(id)) {
                    byte[] fmt = new byte[size];
                    if (fis.read(fmt) != size) throw new Exception("Truncated fmt chunk");
                    ByteBuffer bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
                    formatCode = bb.getShort() & 0xFFFF;
                    bb.getShort();                  // nChannels (assumed 1)
                    sampleRate = bb.getInt();
                    bb.getInt();                    // byteRate
                    bb.getShort();                  // blockAlign
                    bitsPerSample = bb.getShort() & 0xFFFF;
                } else if ("data".equals(id)) {
                    data = new byte[size];
                    int read = 0;
                    while (read < size) {
                        int n = fis.read(data, read, size - read);
                        if (n < 0) break;
                        read += n;
                    }
                    break;
                } else {
                    long skipped = 0;
                    while (skipped < size) {
                        long s = fis.skip(size - skipped);
                        if (s <= 0) break;
                        skipped += s;
                    }
                }
            }

            if (data == null) throw new Exception("No data chunk");

            recordingSampleRate = sampleRate;
            // bitDepth=8 represents µ-law in our app (format code 7); linear 8-bit PCM is not supported.
            recordingBitDepth = (formatCode == 7) ? 8 : bitsPerSample;

            return data;
        }
    }
    
    private void saveSnapshotAsync(AudioRecorderService.Snapshot snap) {
        if (snap == null || snap.totalBytes <= 0) {
            if (snap != null && recorderService != null) recorderService.releaseSnapshot(snap);
            return;
        }
        final AudioRecorderService.Snapshot finalSnap = snap;
        ioExecutor.submit(() -> {
            try {
                File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd--HH:mm:ss", Locale.getDefault());
                String filename = "qso-again--" + sdf.format(new Date(finalSnap.startTimeMs)) + ".wav";
                File outputFile = new File(downloadsDir, filename);

                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    writeWavHeader(fos, finalSnap.sampleRate, finalSnap.bitDepth, finalSnap.totalBytes);
                    finalSnap.writeRangeTo(fos, 0, finalSnap.totalBytes);
                }

                handler.post(() -> {
                    Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_LONG).show();
                    loadSavedRecordings();
                });
            } catch (Exception e) {
                e.printStackTrace();
                final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                handler.post(() -> Toast.makeText(this, "Error saving: " + msg, Toast.LENGTH_LONG).show());
            } finally {
                if (recorderService != null) recorderService.releaseSnapshot(finalSnap);
            }
        });
    }

    @Override
    public void onSnapshotForSave(AudioRecorderService.Snapshot snapshot, boolean fromAutoSave) {
        recordingStartTime = recorderService != null ? recorderService.getRecordingStartTime() : recordingStartTime;
        if (fromAutoSave) {
            Toast.makeText(this, "Auto-saving…", Toast.LENGTH_SHORT).show();
        }
        saveSnapshotAsync(snapshot);
    }

    private void saveWithOffset(int saveOffset) {
        if (currentRecording == null) {
            Toast.makeText(this, R.string.no_recording, Toast.LENGTH_SHORT).show();
            return;
        }

        final int finalOffset = saveOffset;

        ioExecutor.submit(() -> {
            try {
                byte[] recordingData = currentRecording;
                long endTime = (recordingEndTime > 0) ? recordingEndTime : System.currentTimeMillis();

                int recordingSize = (recordingData != null) ? recordingData.length : 0;
                
                if (recordingSize == 0) {
                    handler.post(() -> Toast.makeText(this, R.string.no_recording, Toast.LENGTH_SHORT).show());
                    return;
                }
                
                File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd--HH:mm:ss", Locale.getDefault());
                
                int bytesToSave = recordingSize - finalOffset;
                if (bytesToSave <= 0) {
                    handler.post(() -> Toast.makeText(this, R.string.no_recording, Toast.LENGTH_SHORT).show());
                    return;
                }
                
                int bytesPerSample = recordingBitDepth / 8;
                long audioDurationMs = (long)bytesToSave / bytesPerSample * 1000 / recordingSampleRate;
                long saveStartTime = endTime - audioDurationMs;
                
                String filename = "qso-again--" + sdf.format(new Date(saveStartTime)) + ".wav";
                File outputFile = new File(downloadsDir, filename);
                
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    writeWavHeader(fos, recordingSampleRate, recordingBitDepth, bytesToSave);

                    int position = finalOffset;
                    int remaining = bytesToSave;
                    byte[] chunk = new byte[8192];
                    while (remaining > 0) {
                        int chunkLen = Math.min(8192, remaining);
                        System.arraycopy(recordingData, position, chunk, 0, chunkLen);
                        fos.write(chunk, 0, chunkLen);
                        position += chunkLen;
                        remaining -= chunkLen;
                    }
                }
                
                handler.post(() -> {
                    Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_LONG).show();
                    loadSavedRecordings();
                });
            } catch (Exception e) {
                e.printStackTrace();
                final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                handler.post(() -> {
                    Toast.makeText(this, "Error saving: " + msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    
    private static void writeWavHeader(java.io.OutputStream out, int sampleRate, int bitsPerSample, int dataSize) throws java.io.IOException {
        int numChannels = 1;
        boolean muLaw = (bitsPerSample == 8);

        if (muLaw) {
            // Non-PCM WAV: format code 7 (µ-law), fmt chunk size 18 (+cbSize), with fact chunk.
            int byteRate = sampleRate * numChannels;
            int blockAlign = numChannels;
            int fileSize = 50 + dataSize;
            byte[] header = new byte[58];
            ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            bb.put("RIFF".getBytes());
            bb.putInt(fileSize);
            bb.put("WAVE".getBytes());
            bb.put("fmt ".getBytes());
            bb.putInt(18);
            bb.putShort((short) 7);
            bb.putShort((short) numChannels);
            bb.putInt(sampleRate);
            bb.putInt(byteRate);
            bb.putShort((short) blockAlign);
            bb.putShort((short) 8);
            bb.putShort((short) 0);
            bb.put("fact".getBytes());
            bb.putInt(4);
            bb.putInt(dataSize);
            bb.put("data".getBytes());
            bb.putInt(dataSize);
            out.write(header);
        } else {
            int byteRate = sampleRate * numChannels * (bitsPerSample / 8);
            int blockAlign = numChannels * (bitsPerSample / 8);
            int fileSize = 36 + dataSize;
            byte[] header = new byte[44];
            ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            bb.put("RIFF".getBytes());
            bb.putInt(fileSize);
            bb.put("WAVE".getBytes());
            bb.put("fmt ".getBytes());
            bb.putInt(16);
            bb.putShort((short) 1);
            bb.putShort((short) numChannels);
            bb.putInt(sampleRate);
            bb.putInt(byteRate);
            bb.putShort((short) blockAlign);
            bb.putShort((short) bitsPerSample);
            bb.put("data".getBytes());
            bb.putInt(dataSize);
            out.write(header);
        }
    }

    private void seekToEndpoint(boolean toBeginning) {
        if (currentRecording == null) {
            Toast.makeText(this, R.string.no_recording, Toast.LENGTH_SHORT).show();
            return;
        }
        int totalBytes = currentRecording.length;
        int alignMask = (recordingBitDepth == 8) ? ~0 : ~1;
        int newOffset = (toBeginning ? 0 : totalBytes) & alignMask;
        playbackOffset = newOffset;
        playbackLength = totalBytes - newOffset;
        lastSavedOffset = newOffset;
        playbackStartOffset = 0;
        suppressSaveOffset = true;
        if (totalBytes > 0) {
            seekBarPlayback.setProgress((int)((newOffset * 100L) / totalBytes));
        }
        updatePlaybackProgressDisplay();
        btnPlay.setText(R.string.play);
    }

    private void seekBackFromEnd(int seconds) {
        boolean hasRecording = (currentRecording != null);
        if (!hasRecording) {
            Toast.makeText(this, R.string.no_recording, Toast.LENGTH_SHORT).show();
            return;
        }
        
        int sampleRate = recordingSampleRate;
        int bps = bytesPerSample();
        int totalBytes = currentRecording.length;
        long bytesFromEndLong = (long) seconds * sampleRate * bps;
        int bytesFromEnd = (int) Math.min(bytesFromEndLong, (long) totalBytes);

        int newOffset = totalBytes - bytesFromEnd;
        if (newOffset < 0) {
            newOffset = 0;
            bytesFromEnd = totalBytes;
        }

        int alignMask = (recordingBitDepth == 8) ? ~0 : ~1;
        newOffset = newOffset & alignMask;
        playbackOffset = newOffset;
        playbackLength = totalBytes - newOffset;
        lastSavedOffset = newOffset;
        suppressSaveOffset = false;

        seekBarPlayback.setProgress((int)((newOffset * 100) / totalBytes));
        updatePlaybackProgressDisplay();
        
        playFromOffset(playbackOffset, playbackLength);
    }
    
    private void setSpeedIndex(int newIndex) {
        int clamped = Math.max(0, Math.min(speedLevels.length - 1, newIndex));
        currentSpeedIndex = clamped;
        playbackSpeed = speedLevels[clamped];
        tvPlaybackSpeed.setText(String.format(Locale.getDefault(), "%.1fx", playbackSpeed));
        AudioTrack track = audioTrack;
        if (track != null && isPlaying.get()) {
            int newRate = Math.max(4000, (int)(recordingSampleRate * playbackSpeed));
            try { track.setPlaybackRate(newRate); } catch (Exception ignore) {}
        }
    }

    private int parseDuration(String tag) {
        if (tag.endsWith("s")) {
            return Integer.parseInt(tag.replace("s", ""));
        } else if (tag.endsWith("m")) {
            return Integer.parseInt(tag.replace("m", "")) * 60;
        }
        return 0;
    }
    
    private void playFromOffset(int offset, int length) {
        if (currentRecording == null) return;
        int recordingSize = currentRecording.length;

        int alignMask = (recordingBitDepth == 8) ? ~0 : ~1;
        offset = offset & alignMask;
        playbackOffset = offset;
        playbackStartOffset = offset;
        playbackLength = length > 0 ? length : recordingSize - offset;

        if (offset == 0) lastSavedOffset = 0;

        final float speed = playbackSpeed;
        final int audioOffset = offset;
        final int audioLength = playbackLength;

        playbackExecutor.execute(() -> {
            stopPlayback();

            final byte[] audioData = currentRecording;
            if (audioData == null) return;

            final boolean isMuLaw = (recordingBitDepth == 8);
            final int innerAlignMask = isMuLaw ? ~0 : ~1;
            int dataStart = audioOffset & innerAlignMask;
            int dataEnd = Math.min(audioData.length, dataStart + audioLength);
            if (dataStart < 0 || dataStart >= dataEnd) return;

            final int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
            final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int adjustedSampleRate = Math.max(4000, (int)(recordingSampleRate * speed));

            AudioTrack track = null;
            try {
                int minBufferSize = AudioTrack.getMinBufferSize(adjustedSampleRate, channelConfig, audioFormat);
                // getMinBufferSize returns ERROR (-1) / ERROR_BAD_VALUE (-2) on some devices for
                // non-native rates (e.g. 11025 Hz). AudioTrack itself will still resample, so we
                // just need any reasonable buffer in bytes.
                int oneSecondBytes = adjustedSampleRate * 2;
                int bufferSizeBytes = (minBufferSize > 0)
                    ? Math.max(minBufferSize, oneSecondBytes)
                    : oneSecondBytes;

                AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
                AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(adjustedSampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build();
                track = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSizeBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    throw new RuntimeException("AudioTrack not initialized");
                }
                audioTrack = track;

                isPlaying.set(true);

                handler.post(() -> {
                    tvStatus_display(R.string.recording_stopped);
                    btnStartStop.setText(R.string.record);
                    btnPlay.setText(R.string.stop_playback);
                    updatePlaybackUI(currentRecording != null);
                    handler.post(playbackProgressRunnable);
                });

                track.play();

                byte[] writeBuf = isMuLaw ? new byte[bufferSizeBytes] : null;
                int pos = dataStart;
                while (pos < dataEnd && isPlaying.get()) {
                    int written;
                    int srcConsumed;
                    if (isMuLaw) {
                        int srcLen = Math.min(bufferSizeBytes / 2, dataEnd - pos);
                        int decodedBytes = MuLaw.decodeToPcm16(audioData, pos, srcLen, writeBuf, 0);
                        int w = track.write(writeBuf, 0, decodedBytes);
                        if (w < 0) break;
                        // Map PCM bytes written back to source-byte consumption.
                        srcConsumed = w / 2;
                        written = w;
                    } else {
                        int len = Math.min(bufferSizeBytes, dataEnd - pos);
                        int w = track.write(audioData, pos, len);
                        if (w < 0) break;
                        srcConsumed = w;
                        written = w;
                    }
                    pos += srcConsumed;
                    playbackOffset = pos & innerAlignMask;
                    if (written == 0) break;
                }

                // Drain the AudioTrack buffer so the tail isn't truncated by release().
                if (pos >= dataEnd && isPlaying.get()) {
                    int expectedFrames = isMuLaw
                        ? (dataEnd - dataStart)             // µ-law: 1 byte = 1 frame
                        : (dataEnd - dataStart) / 2;        // 16-bit: 2 bytes = 1 frame
                    long deadlineMs = System.currentTimeMillis()
                        + (long) bufferSizeBytes * 1000 / 2 / adjustedSampleRate + 200;
                    while (isPlaying.get()
                            && System.currentTimeMillis() < deadlineMs
                            && track.getPlaybackHeadPosition() < expectedFrames) {
                        try { Thread.sleep(20); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (track != null) {
                    try { track.stop(); } catch (Exception ignore) {}
                    try { track.release(); } catch (Exception ignore) {}
                }
                audioTrack = null;
                isPlaying.set(false);

                handler.post(() -> {
                    updatePlaybackUI(currentRecording != null);
                    handler.removeCallbacks(playbackProgressRunnable);
                    btnPlay.setText(R.string.play);
                    updatePlaybackProgressDisplay();
                });
            }
        });
    }
    
    private byte[] convertAudio(byte[] input, int sourceRate, int sourceBits, int targetRate, int targetBits) {
        if (sourceRate == targetRate && sourceBits == targetBits) return input;
        
        int sourceBytesPerSample = sourceBits / 8;
        int targetBytesPerSample = targetBits / 8;
        
        int numSamples = input.length / sourceBytesPerSample;
        int targetNumSamples = (int)((long)numSamples * targetRate / sourceRate);
        int targetLength = targetNumSamples * targetBytesPerSample;
        
        byte[] output = new byte[targetLength];
        
        for (int i = 0; i < targetNumSamples; i++) {
            long srcIdx = (long)i * sourceRate / targetRate;
            srcIdx = Math.min(srcIdx, numSamples - 1);
            
            int sample;
            if (sourceBits == 16) {
                int srcPos = (int)srcIdx * 2;
                sample = (input[srcPos + 1] << 8) | (input[srcPos] & 0xFF);
                if (sample >= 32768) sample -= 65536;
            } else {
                sample = (input[(int)srcIdx] - 128) * 256;
            }
            
            if (targetBits == 16) {
                if (sample > 32767) sample = 32767;
                if (sample < -32768) sample = -32768;
                int outPos = i * 2;
                output[outPos] = (byte)(sample & 0xFF);
                output[outPos + 1] = (byte)((sample >> 8) & 0xFF);
            }
        }
        
        return output;
    }
    
    private void stopPlayback() {
        isPlaying.set(false);
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
            audioTrack = null;
        }
        handler.removeCallbacks(playbackProgressRunnable);
    }
    
    private void updatePlaybackProgressDisplay() {
        int recordingSize = (currentRecording != null) ? currentRecording.length : 0;
        if (recordingSize <= 0) return;

        int bps = bytesPerSample();
        long totalMs = (long) recordingSize * 1000 / recordingSampleRate / bps;
        long offsetMs = (long) playbackOffset * 1000 / recordingSampleRate / bps;
        if (playbackOffset > 0) {
            tvPlaybackProgress.setText(formatDuration(offsetMs) + " / " + formatDuration(totalMs));
            seekBarPlayback.setProgress((int) ((offsetMs * 100) / Math.max(1, totalMs)));
        } else {
            tvPlaybackProgress.setText("00:00 / " + formatDuration(totalMs));
            seekBarPlayback.setProgress(0);
        }
    }
    
    private final Runnable progressUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (recorderService != null && recorderService.isRecording()) {
                long durationMs = recorderService.getRecordingDuration();
                int durationSeconds = (int) (durationMs / 1000);
                int maxSeconds = recorderService.getMaxDurationMinutes() * 60;
                int progress = (durationSeconds * 100) / Math.max(1, maxSeconds);

                progressRecording.setProgress(progress);
                tvRecordingTime.setText(formatDuration(durationMs));
                setSeekButtonsEnabledForSeconds(durationSeconds);

                handler.postDelayed(this, 1000);
            }
        }
    };

    private final Runnable playbackProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlaying.get()) {
                int recordingSize = (currentRecording != null) ? currentRecording.length : 0;
                if (recordingSize > 0) {
                    int bps = bytesPerSample();
                    long currentMs = (long) playbackOffset * 1000 / recordingSampleRate / bps;
                    long totalMs = (long) recordingSize * 1000 / recordingSampleRate / bps;
                    tvPlaybackProgress.setText(formatDuration(currentMs) + " / " + formatDuration(totalMs));
                    seekBarPlayback.setProgress((int) ((playbackOffset * 100L) / (long) recordingSize));
                    handler.postDelayed(this, 1000);
                }
            }
        }
    };
    
    @Override
    public void onRecordingStopped(byte[] audioData, long startTimeMs) {
        currentRecording = audioData;
        playbackLength = (audioData != null) ? audioData.length : 0;
        recordingStartTime = startTimeMs;
        playbackOffset = 0;
        lastSavedOffset = 0;
        playbackStartOffset = 0;
        suppressSaveOffset = false;

        final boolean hasBuffer = audioData != null && audioData.length > 0;
        handler.post(() -> {
            btnStartStop.setBackgroundTintList(null);
            updatePlaybackUI(hasBuffer);
            if (hasBuffer) {
                Toast.makeText(this, "Recording complete", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                    "Recording too large for memory — saved to Downloads",
                    Toast.LENGTH_LONG).show();
            }
            Runnable pending = pendingPlaybackAction;
            pendingPlaybackAction = null;
            if (pending != null && hasBuffer) pending.run();
        });
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        String currentLang = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
                .getString("language", "system");
        if (lastAppliedLang != null && !lastAppliedLang.equals(currentLang)) {
            recreate();
            return;
        }
        loadSettings();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(progressUpdateRunnable);
        handler.removeCallbacks(playbackProgressRunnable);
        stopPlayback();
        playbackExecutor.shutdown();
        ioExecutor.shutdown();
        
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }
    
    private void showQuitDialog() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.quit_title)
            .setMessage(R.string.quit_message)
            .setPositiveButton(R.string.yes, (dialog, which) -> finish())
            .setNegativeButton(R.string.no, null)
            .show();
    }
    
    private String formatDuration(long milliseconds) {
        if (milliseconds < 0) milliseconds = 0;
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }
}
