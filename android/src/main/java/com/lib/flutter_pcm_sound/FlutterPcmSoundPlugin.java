package com.lib.flutter_pcm_sound;

import android.os.Build;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * FlutterPcmSoundPlugin implements a "one pedal" PCM sound playback mechanism.
 * Playback starts automatically when samples are fed and stops when no more samples are available.
 */
public class FlutterPcmSoundPlugin implements
    FlutterPlugin,
    MethodChannel.MethodCallHandler
{
    private static final String CHANNEL_NAME = "flutter_pcm_sound/methods";
    private static final int MAX_BYTES_PER_BUFFER = 4096;

    private MethodChannel mMethodChannel;
    private Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    private Thread playbackThread;
    private volatile boolean mShouldCleanup = false;

    private AudioTrack mAudioTrack;
    private int mNumChannels;
    private int mMinBufferSize;
    private boolean mDidSetup = false;

    private final AtomicLong mFeedThreshold = new AtomicLong(8000);
    private final AtomicLong mTotalFeeds = new AtomicLong(0);
    private final AtomicLong mQueuedBytes = new AtomicLong(0);
    private long mLastLowBufferFeed = 0;
    private long mLastZeroFeed = 0;

    // Thread-safe queue for storing audio samples
    private final LinkedBlockingQueue<ByteBuffer> mSamples = new LinkedBlockingQueue<>();

    // Log level enum (kept for potential future use)
    private enum LogLevel {
        NONE,
        ERROR,
        STANDARD,
        VERBOSE
    }

    private LogLevel mLogLevel = LogLevel.VERBOSE;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        BinaryMessenger messenger = binding.getBinaryMessenger();
        mMethodChannel = new MethodChannel(messenger, CHANNEL_NAME);
        mMethodChannel.setMethodCallHandler(this);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        mMethodChannel.setMethodCallHandler(null);
        cleanup();
    }

    @Override
    @SuppressWarnings("deprecation") // Needed for compatibility with Android < 23
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        try {
            switch (call.method) {
                case "setLogLevel": {
                    result.success(true);
                    break;
                }
                case "setup": {
                    int sampleRate = call.argument("sample_rate");
                    mNumChannels = call.argument("num_channels");

                    // Cleanup existing resources if any
                    if (mAudioTrack != null) {
                        cleanup();
                    }

                    int channelConfig = (mNumChannels == 2) ?
                        AudioFormat.CHANNEL_OUT_STEREO :
                        AudioFormat.CHANNEL_OUT_MONO;

                    mMinBufferSize = AudioTrack.getMinBufferSize(
                        sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);

                    if (mMinBufferSize == AudioTrack.ERROR || mMinBufferSize == AudioTrack.ERROR_BAD_VALUE) {
                        result.error("AudioTrackError", "Invalid buffer size.", null);
                        return;
                    }

                    if (Build.VERSION.SDK_INT >= 23) { // Android 6 (Marshmallow) and above
                        mAudioTrack = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(channelConfig)
                                    .build())
                            .setBufferSizeInBytes(mMinBufferSize)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build();
                    } else {
                        mAudioTrack = new AudioTrack(
                            AudioManager.STREAM_MUSIC,
                            sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT,
                            mMinBufferSize,
                            AudioTrack.MODE_STREAM);
                    }

                    if (mAudioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                        result.error("AudioTrackError", "AudioTrack initialization failed.", null);
                        mAudioTrack.release();
                        mAudioTrack = null;
                        return;
                    }

                    // reset
                    mSamples.clear();
                    mQueuedBytes.set(0);
                    mTotalFeeds.set(0);
                    mLastLowBufferFeed = 0;
                    mLastZeroFeed = 0;
                    mShouldCleanup = false;

                    // start playback thread
                    playbackThread = new Thread(this::playbackThreadLoop, "PCMPlaybackThread");
                    playbackThread.setPriority(Thread.MAX_PRIORITY);
                    playbackThread.start();

                    mDidSetup = true;

                    result.success(true);
                    break;
                }
                case "feed": {

                    // check setup (to match iOS behavior)
                    if (mDidSetup == false) {
                        result.error("Setup", "must call setup first", null);
                        return;
                    }

                    byte[] buffer = call.argument("buffer");
                    if (buffer == null || buffer.length == 0) {
                        result.success(true);
                        break;
                    }

                    for (int offset = 0; offset < buffer.length; offset += MAX_BYTES_PER_BUFFER) {
                        int length = Math.min(buffer.length - offset, MAX_BYTES_PER_BUFFER);
                        ByteBuffer chunk = ByteBuffer.wrap(buffer, offset, length);
                        mSamples.add(chunk);
                        mQueuedBytes.addAndGet(length);
                    }
                    mTotalFeeds.incrementAndGet();

                    result.success(true);
                    break;
                }
                case "setFeedThreshold": {
                    long feedThreshold = ((Number) call.argument("feed_threshold")).longValue();
                    mFeedThreshold.set(feedThreshold);
                    result.success(true);
                    break;
                }
                case "release": {
                    cleanup();
                    result.success(true);
                    break;
                }
                default:
                    result.notImplemented();
                    break;
            }


        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String stackTrace = sw.toString();
            result.error("androidException", e.toString(), stackTrace);
            return;
        }
    }

    /**
     * Cleans up resources by stopping the playback thread and releasing AudioTrack.
     */
    private void cleanup() {
        // stop playback thread
        if (playbackThread != null) {
            mShouldCleanup = true;
            playbackThread.interrupt();
            try {
                playbackThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            playbackThread = null;
            mDidSetup = false;
        }

        mSamples.clear();
        mQueuedBytes.set(0);
    }

    /**
     * Invokes the 'OnFeedSamples' callback with the number of remaining frames.
     */
    private void invokeFeedCallback(long remainingFrames) {
        Map<String, Object> response = new HashMap<>();
        response.put("remaining_frames", remainingFrames);
        mMethodChannel.invokeMethod("OnFeedSamples", response);
    }

    /**
     * The main loop of the playback thread.
     */
    private void playbackThreadLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

        mAudioTrack.play();

        while (!mShouldCleanup) {
            ByteBuffer data = null;
            try {
                // blocks indefinitely until new data
                data = mSamples.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                continue;
            }

            int bytesToWrite = data.remaining();
            mQueuedBytes.addAndGet(-bytesToWrite);

            // write
            mAudioTrack.write(data, bytesToWrite, AudioTrack.WRITE_BLOCKING);

            long remainingFrames = mQueuedBytes.get() / (2L * mNumChannels);
            long totalFeeds = mTotalFeeds.get();
            long feedThreshold = mFeedThreshold.get();

            // check for events
            boolean isLowBufferEvent = (remainingFrames <= feedThreshold) && (mLastLowBufferFeed != totalFeeds);
            boolean isZeroCrossingEvent = (remainingFrames == 0) && (mLastZeroFeed != totalFeeds);

            // send events
            if (isLowBufferEvent || isZeroCrossingEvent) {
                if (isLowBufferEvent) {mLastLowBufferFeed = totalFeeds;}
                if (isZeroCrossingEvent) {mLastZeroFeed = totalFeeds;}
                mainThreadHandler.post(() -> invokeFeedCallback(remainingFrames));
            }
        }

        mAudioTrack.stop();
        mAudioTrack.flush();
        mAudioTrack.release();
        mAudioTrack = null;
    }
}
