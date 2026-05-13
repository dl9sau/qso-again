package de.qso.again;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioRecorderService extends Service {
    static final int CHUNK_SIZE = 1024 * 1024;
    private static final int MAX_POOL_CHUNKS = 64;
    private static final int USABLE_HEAP_PERCENT = 85;
    private static final int STOP_TAIL_MS = 260;

    public static long getUsableBytesForRecording() {
        long maxHeap = Runtime.getRuntime().maxMemory();
        return maxHeap * USABLE_HEAP_PERCENT / 100;
    }

    private int sampleRate = 22050;
    private int bitDepth = 16;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;

    private final IBinder binder = new LocalBinder();
    private AudioRecord audioRecord;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private volatile boolean stopRequested = false;
    private Thread recordingThread;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int maxDurationMinutes = 5;
    private int maxRecordingBytes = 0;
    private volatile boolean autoSaveEnabled = false;

    private final Object bufferLock = new Object();
    private List<byte[]> chunks = new ArrayList<>();
    private int bytesInLastChunk = 0;
    private int firstChunkOffset = 0;
    private int totalBytesWritten = 0;
    private volatile long recordingStartTime = 0;

    private final ArrayDeque<byte[]> chunkPool = new ArrayDeque<>();

    public static final class Snapshot {
        public final List<byte[]> chunks;
        public final int firstChunkOffset;
        public final int totalBytes;
        public final long startTimeMs;
        public final long endTimeMs;
        public final int sampleRate;
        public final int bitDepth;

        Snapshot(List<byte[]> chunks, int firstChunkOffset, int totalBytes,
                 long startTimeMs, long endTimeMs, int sampleRate, int bitDepth) {
            this.chunks = chunks;
            this.firstChunkOffset = firstChunkOffset;
            this.totalBytes = totalBytes;
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.sampleRate = sampleRate;
            this.bitDepth = bitDepth;
        }

        public void writeRangeTo(OutputStream out, int srcOffset, int length) throws IOException {
            if (srcOffset < 0 || length < 0 || srcOffset + length > totalBytes) {
                throw new IllegalArgumentException("range out of bounds");
            }
            // Logical [0..totalBytes) maps to physical [firstChunkOffset..firstChunkOffset+totalBytes)
            int physOffset = srcOffset + firstChunkOffset;
            int chunkIdx = physOffset / CHUNK_SIZE;
            int inChunk = physOffset % CHUNK_SIZE;
            int remaining = length;
            while (remaining > 0) {
                byte[] chunk = chunks.get(chunkIdx);
                int avail = chunk.length - inChunk;
                int take = Math.min(remaining, avail);
                out.write(chunk, inChunk, take);
                remaining -= take;
                chunkIdx++;
                inChunk = 0;
            }
        }

        public byte[] toByteArray() {
            byte[] out = new byte[totalBytes];
            int pos = 0;
            for (int i = 0; i < chunks.size(); i++) {
                byte[] chunk = chunks.get(i);
                int srcStart = (i == 0) ? firstChunkOffset : 0;
                int avail = chunk.length - srcStart;
                int take = Math.min(avail, totalBytes - pos);
                System.arraycopy(chunk, srcStart, out, pos, take);
                pos += take;
                if (pos >= totalBytes) break;
            }
            return out;
        }
    }

    public interface RecordingListener {
        void onRecordingStopped(byte[] audioData, long startTimeMs);
        default void onSnapshotForSave(Snapshot snapshot, boolean fromAutoSave) {}
    }

    private RecordingListener listener;

    public class LocalBinder extends Binder {
        public AudioRecorderService getService() {
            return AudioRecorderService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setRecordingListener(RecordingListener listener) {
        this.listener = listener;
    }

    public void setMaxDurationMinutes(int minutes) {
        long usableBytes = getUsableBytesForRecording();
        long bytesPerSecond = (long) sampleRate * (bitDepth / 8);
        int maxMinutesByRam = (int)((usableBytes / bytesPerSecond) / 60);
        int selectedMinutes = Math.min(minutes, maxMinutesByRam);
        this.maxDurationMinutes = selectedMinutes;
        long maxBytes = (long) selectedMinutes * 60 * bytesPerSecond;
        this.maxRecordingBytes = (int) Math.min(maxBytes, (long) Integer.MAX_VALUE);
    }

    public void setSampleRate(int rate) {
        this.sampleRate = rate;
    }

    public void setBitDepth(int bits) {
        this.bitDepth = bits;
    }

    public void setAutoSaveEnabled(boolean enabled) {
        this.autoSaveEnabled = enabled;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getBitDepth() {
        return bitDepth;
    }

    public int getMaxDurationMinutes() {
        return maxDurationMinutes;
    }

    public boolean isRecording() {
        return isRecording.get();
    }

    public long getRecordingStartTime() {
        return recordingStartTime;
    }

    public int getTotalBytesWritten() {
        synchronized (bufferLock) {
            return totalBytesWritten;
        }
    }

    public long getRecordingDuration() {
        if (!isRecording.get()) return 0;
        return System.currentTimeMillis() - recordingStartTime;
    }

    private byte[] takeChunk() {
        synchronized (chunkPool) {
            byte[] c = chunkPool.pollFirst();
            if (c != null) return c;
        }
        return new byte[CHUNK_SIZE];
    }

    private void releaseChunk(byte[] chunk) {
        if (chunk == null || chunk.length != CHUNK_SIZE) return;
        synchronized (chunkPool) {
            if (chunkPool.size() < MAX_POOL_CHUNKS) chunkPool.offerLast(chunk);
        }
    }

    public void releaseSnapshot(Snapshot snap) {
        if (snap == null) return;
        for (byte[] c : snap.chunks) releaseChunk(c);
    }

    public Snapshot snapshotAndReset() {
        synchronized (bufferLock) {
            long oldStart = recordingStartTime;
            long oldEnd = oldStart + (totalBytesWritten * 1000L / Math.max(1, sampleRate * (bitDepth / 8)));
            List<byte[]> oldChunks = chunks;
            int oldTotal = totalBytesWritten;
            int oldFirstOffset = firstChunkOffset;

            chunks = new ArrayList<>();
            bytesInLastChunk = 0;
            firstChunkOffset = 0;
            totalBytesWritten = 0;
            recordingStartTime = System.currentTimeMillis();

            return new Snapshot(oldChunks, oldFirstOffset, oldTotal, oldStart, oldEnd, sampleRate, bitDepth);
        }
    }

    public void startRecording() {
        if (isRecording.get()) return;
        stopRequested = false;

        synchronized (bufferLock) {
            chunks = new ArrayList<>();
            bytesInLastChunk = 0;
            firstChunkOffset = 0;
            totalBytesWritten = 0;
        }
        recordingStartTime = System.currentTimeMillis();

        // Always capture 16-bit PCM at the AudioRecord layer. If bitDepth == 8, the recording
        // thread compresses to µ-law (G.711) before storing.
        int captureFormat = AudioFormat.ENCODING_PCM_16BIT;

        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, captureFormat);
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            minBufferSize = sampleRate * 2;
        }
        int bufferSize = minBufferSize * 2;

        audioRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            CHANNEL_CONFIG,
            captureFormat,
            bufferSize
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            return;
        }

        isRecording.set(true);
        audioRecord.startRecording();

        startForeground(1, createNotification());

        final int bufferSizeFinal = bufferSize;
        final int maxBytesFinal = maxRecordingBytes;
        final boolean encodeToMuLaw = (bitDepth == 8);

        recordingThread = new Thread(() -> {
            byte[] readBuf = new byte[bufferSizeFinal];
            byte[] muBuf = encodeToMuLaw ? new byte[bufferSizeFinal / 2] : null;

            while (isRecording.get()) {
                int read = audioRecord.read(readBuf, 0, readBuf.length);
                if (read > 0) {
                    try {
                        if (encodeToMuLaw) {
                            int muLen = MuLaw.encodePcm16(readBuf, 0, read, muBuf, 0);
                            appendToChunks(muBuf, muLen);
                        } else {
                            appendToChunks(readBuf, read);
                        }
                    } catch (OutOfMemoryError oom) {
                        // Heap exhausted on chunk allocation. Drop into auto-save flow so the
                        // recording so far is rescued to disk instead of crashing.
                        isRecording.set(false);
                        final RecordingListener l = listener;
                        handler.post(() -> {
                            Snapshot snap = snapshotAndReset();
                            if (l != null) l.onSnapshotForSave(snap, true);
                        });
                        break;
                    }
                }

                if (autoSaveEnabled) {
                    long duration = System.currentTimeMillis() - recordingStartTime;
                    long maxMs = maxDurationMinutes * 60 * 1000L;
                    boolean durationHit = (maxBytesFinal > 0 && duration >= maxMs);
                    boolean bytesHit = (maxBytesFinal > 0 && totalBytesWritten >= maxBytesFinal);
                    if (durationHit || bytesHit) {
                        Snapshot snap = snapshotAndReset();
                        RecordingListener l = listener;
                        if (l != null) handler.post(() -> l.onSnapshotForSave(snap, true));
                    }
                }
            }
        });
        recordingThread.start();
    }

    private void appendToChunks(byte[] src, int len) {
        synchronized (bufferLock) {
            int srcPos = 0;
            while (srcPos < len) {
                if (chunks.isEmpty() || bytesInLastChunk == CHUNK_SIZE) {
                    chunks.add(takeChunk());
                    bytesInLastChunk = 0;
                }
                byte[] cur = chunks.get(chunks.size() - 1);
                int space = CHUNK_SIZE - bytesInLastChunk;
                int copyLen = Math.min(space, len - srcPos);
                System.arraycopy(src, srcPos, cur, bytesInLastChunk, copyLen);
                bytesInLastChunk += copyLen;
                totalBytesWritten += copyLen;
                srcPos += copyLen;
            }

            // Loop mode: when autoSave is OFF and we've exceeded the configured cap, drop the
            // oldest bytes (byte-precise via firstChunkOffset) so the buffer becomes an exact
            // sliding window of the last maxDurationMinutes of audio.
            if (!autoSaveEnabled && maxRecordingBytes > 0 && totalBytesWritten > maxRecordingBytes) {
                int excess = totalBytesWritten - maxRecordingBytes;
                int remaining = excess;
                while (remaining > 0 && !chunks.isEmpty()) {
                    int headSize = (chunks.size() == 1) ? bytesInLastChunk : CHUNK_SIZE;
                    int spaceInHead = headSize - firstChunkOffset;
                    if (spaceInHead <= 0) break;
                    int step = Math.min(remaining, spaceInHead);
                    firstChunkOffset += step;
                    remaining -= step;
                    if (firstChunkOffset >= headSize) {
                        if (chunks.size() > 1) {
                            releaseChunk(chunks.remove(0));
                            firstChunkOffset = 0;
                        } else {
                            firstChunkOffset = 0;
                            bytesInLastChunk = 0;
                            break;
                        }
                    }
                }
                int dropped = excess - remaining;
                totalBytesWritten -= dropped;
                int bytesPerSec = Math.max(1, sampleRate * (bitDepth / 8));
                recordingStartTime += (long) dropped * 1000L / bytesPerSec;
            }
        }
    }

    public void stopRecording() {
        if (!isRecording.get() || stopRequested) return;
        // Tail-delay: keep capturing for STOP_TAIL_MS so the trailing syllable that is
        // still in the mic pipeline reaches the buffer before we shut down the hardware.
        stopRequested = true;
        handler.postDelayed(this::finishStopRecording, STOP_TAIL_MS);
    }

    private void finishStopRecording() {
        stopRequested = false;
        if (!isRecording.get()) return;

        isRecording.set(false);

        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        try {
            if (recordingThread != null) {
                recordingThread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
            audioRecord = null;
        }

        stopForeground(STOP_FOREGROUND_REMOVE);

        if (listener != null) {
            Snapshot snap = snapshotAndReset();
            if (snap.totalBytes == 0) {
                listener.onRecordingStopped(null, snap.startTimeMs);
                return;
            }
            byte[] finalData = null;
            try {
                finalData = snap.toByteArray();
            } catch (OutOfMemoryError oom) {
                // Heap can't hold a contiguous copy alongside the chunks.
                // Stream the snapshot directly to disk via the save path; no in-memory playback.
                final RecordingListener l = listener;
                final Snapshot snapForSave = snap;
                handler.post(() -> l.onSnapshotForSave(snapForSave, false));
                listener.onRecordingStopped(null, snap.startTimeMs);
                return;
            }
            releaseSnapshot(snap);
            listener.onRecordingStopped(finalData, snap.startTimeMs);
        }
    }

    private android.app.Notification createNotification() {
        return new NotificationCompat.Builder(this, "qso_again_channel")
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.recording_in_progress))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "qso_again_channel",
                "Recording",
                NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when recording is in progress");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            synchronized (chunkPool) {
                chunkPool.clear();
            }
        }
    }
}
