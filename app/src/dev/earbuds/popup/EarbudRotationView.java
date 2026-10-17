package dev.earbuds.popup;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.graphics.Matrix;
import android.graphics.RuntimeShader;
import android.graphics.RenderEffect;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import android.view.TextureView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Native looped renderer for the original FreeBuds 6i product turntable video.
 * API 33+ AGSL removes the white studio background while retaining compact,
 * bright detail on the dark product. The source frames and their timing are
 * decoded directly; this view does not generate or transform the rotation.
 */
public final class EarbudRotationView extends TextureView
        implements TextureView.SurfaceTextureListener {
    private static final String TAG = "EarbudRotationView";
    private static final String VIDEO_ASSET =
            "models/freebuds_6i/hero-black.mp4";
    private static final int SOURCE_WIDTH = 960;
    private static final int SOURCE_HEIGHT = 450;
    private static final float SOURCE_ASPECT =
            SOURCE_WIDTH / (float) SOURCE_HEIGHT;

    private static final String KEY_SHADER_SOURCE =
            "uniform shader videoFrame;\n" +
            "uniform float pixelStep;\n" +
            "float whiteAt(float2 p) {\n" +
            "  half4 s = videoFrame.eval(p);\n" +
            "  float m = min(s.r, min(s.g, s.b));\n" +
            "  return smoothstep(0.88, 0.985, m) * s.a;\n" +
            "}\n" +
            "half4 main(float2 p) {\n" +
            "  half4 s = videoFrame.eval(p);\n" +
            "  float m = min(s.r, min(s.g, s.b));\n" +
            "  float white = smoothstep(0.88, 0.985, m);\n" +
            "  if (white <= 0.001 || s.a <= 0.0) return s;\n" +
            "  float2 dx = float2(pixelStep, 0.0);\n" +
            "  float2 dy = float2(0.0, pixelStep);\n" +
            "  float2 dd = float2(pixelStep * 0.70710678);\n" +
            "  float around = (whiteAt(p + dx) + whiteAt(p - dx) +\n" +
            "      whiteAt(p + dy) + whiteAt(p - dy) +\n" +
            "      whiteAt(p + dd) + whiteAt(p - dd) +\n" +
            "      whiteAt(p + float2(dd.x, -dd.y)) +\n" +
            "      whiteAt(p + float2(-dd.x, dd.y))) * 0.125;\n" +
            "  float background = smoothstep(0.20, 0.60, around);\n" +
            "  float alpha = s.a * (1.0 - white * background);\n" +
            "  if (alpha <= 0.001) return half4(0.0);\n" +
            "  half3 straight = clamp((s.rgb - half3(1.0 - alpha)) / alpha,\n" +
            "      half3(0.0), half3(1.0));\n" +
            "  return half4(straight * alpha, alpha);\n" +
            "}";

    public interface Listener {
        void onReady();
        void onError();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable loopMonitor = new Runnable() {
        @Override public void run() {
            MediaPlayer active = player;
            if (released || active == null || !prepared) return;
            try {
                if (active.isPlaying()) {
                    int position = active.getCurrentPosition();
                    if (lastPositionMs >= 0 && position < lastPositionMs - 1000) {
                        Log.i(TAG, "Playback looped at " + lastPositionMs + " ms");
                    }
                    lastPositionMs = position;
                }
            } catch (IllegalStateException ignored) {
            }
            if (!released && player == active) main.postDelayed(this, 500L);
        }
    };

    private Listener listener;
    private Object keyShader;
    private boolean keyEffectReady;
    private boolean released;
    private boolean prepared;
    private boolean readySent;
    private boolean errorSent;
    private int playbackGeneration;
    private int videoWidth = SOURCE_WIDTH;
    private int videoHeight = SOURCE_HEIGHT;
    private int videoDurationMs;
    private int lastPositionMs = -1;
    private float playbackSpeed = 1f;
    private MediaPlayer player;
    private Surface surface;
    private File cachedAssetFile;

    public EarbudRotationView(Context context) {
        this(context, null);
    }

    public EarbudRotationView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setOpaque(false);
        setSurfaceTextureListener(this);
        keyEffectReady = installKeyEffect();
        updateTextureTransform();
    }

    /** The transparent GPU key uses RuntimeShader, available from Android 13. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 33;
    }

    /** Sets the original video playback rate; invalid values are ignored. */
    public void setPlaybackSpeed(float speed) {
        if (Float.isNaN(speed) || Float.isInfinite(speed) || speed < 0.1f || speed > 4f) {
            return;
        }
        playbackSpeed = speed;
        MediaPlayer active = player;
        if (prepared && active != null) applyPlaybackSpeed(active);
    }

    /** Stops and releases the decoder, surface, and any temporary asset copy. */
    public void release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::release);
            return;
        }
        if (released) return;
        released = true;
        listener = null;
        stopPlayback();
        if (Build.VERSION.SDK_INT >= 33 && keyShader != null) {
            Api33.clearEffect(this);
            keyShader = null;
        }
        Log.i(TAG, "Released rotation renderer");
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isAvailable()) {
            updateTextureTransform();
            startPlayback(getSurfaceTexture());
        }
    }

    @Override protected void onDetachedFromWindow() {
        stopPlayback();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int width, int height,
                                           int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateTextureTransform();
    }

    @Override public void onSurfaceTextureAvailable(
            SurfaceTexture texture, int width, int height) {
        updateTextureTransform();
        startPlayback(texture);
    }

    @Override public void onSurfaceTextureSizeChanged(
            SurfaceTexture texture, int width, int height) {
        updateTextureTransform();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        stopPlayback();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        MediaPlayer active = player;
        if (released || active == null || !prepared || readySent
                || texture != getSurfaceTexture()) return;
        readySent = true;
        final int generation = playbackGeneration;
        main.post(() -> {
            if (released || generation != playbackGeneration || !readySent) return;
            Log.i(TAG, "Ready " + videoWidth + "x" + videoHeight
                    + ", duration " + videoDurationMs + " ms");
            Listener callback = listener;
            if (callback != null) {
                try {
                    callback.onReady();
                } catch (RuntimeException ignored) {
                }
            }
        });
    }

    private boolean installKeyEffect() {
        if (!isSupported()) return false;
        try {
            keyShader = Api33.installEffect(this, KEY_SHADER_SOURCE);
            return keyShader != null;
        } catch (RuntimeException | LinkageError error) {
            Log.e(TAG, "Could not install transparent video key", error);
            return false;
        }
    }

    private void startPlayback(SurfaceTexture texture) {
        if (released || texture == null || player != null) return;
        final int generation = ++playbackGeneration;
        readySent = false;
        errorSent = false;
        prepared = false;
        lastPositionMs = -1;
        if (!keyEffectReady) {
            reportError(generation, "Transparent video key is unavailable");
            return;
        }

        MediaPlayer created = null;
        try {
            Surface createdSurface = new Surface(texture);
            surface = createdSurface;
            created = new MediaPlayer();
            player = created;
            created.setSurface(createdSurface);
            created.setLooping(true);
            created.setVolume(0f, 0f);
            final MediaPlayer owner = created;
            created.setOnPreparedListener(mp -> {
                if (!isCurrent(owner, generation)) return;
                prepared = true;
                videoDurationMs = safeDuration(mp);
                if (mp.getVideoWidth() > 0 && mp.getVideoHeight() > 0) {
                    videoWidth = mp.getVideoWidth();
                    videoHeight = mp.getVideoHeight();
                }
                updateTextureTransform();
                applyPlaybackSpeed(mp);
                try {
                    mp.start();
                    main.removeCallbacks(loopMonitor);
                    main.postDelayed(loopMonitor, 500L);
                } catch (RuntimeException error) {
                    failPlayback(owner, generation, "Could not start video", error);
                }
            });
            created.setOnVideoSizeChangedListener((mp, width, height) -> {
                if (!isCurrent(owner, generation) || width <= 0 || height <= 0) return;
                videoWidth = width;
                videoHeight = height;
                updateTextureTransform();
            });
            created.setOnErrorListener((mp, what, extra) -> {
                failPlayback(owner, generation,
                        "MediaPlayer error " + what + "/" + extra, null);
                return true;
            });
            created.setOnCompletionListener(mp -> {
                if (isCurrent(owner, generation)) {
                    failPlayback(owner, generation, "Looping video completed unexpectedly", null);
                }
            });
            setAssetDataSource(created);
            created.prepareAsync();
        } catch (IOException | RuntimeException error) {
            if (created != null) failPlayback(created, generation,
                    "Could not prepare video", error);
            else {
                cleanupPlayerResources();
                reportError(generation, "Could not create video player", error);
            }
        }
    }

    private void setAssetDataSource(MediaPlayer target) throws IOException {
        AssetFileDescriptor descriptor = null;
        try {
            descriptor = getContext().getAssets().openFd(VIDEO_ASSET);
        } catch (IOException | RuntimeException ignored) {
            // Compressed assets cannot be opened as a file descriptor.
        }
        if (descriptor != null) {
            try (AssetFileDescriptor asset = descriptor) {
                target.setDataSource(asset.getFileDescriptor(),
                        asset.getStartOffset(), asset.getLength());
            }
            return;
        }

        cachedAssetFile = copyAssetToCache();
        target.setDataSource(cachedAssetFile.getAbsolutePath());
    }

    private File copyAssetToCache() throws IOException {
        File directory = getContext().getCacheDir();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("Could not create video cache directory");
        }
        File copy = File.createTempFile("freebuds6i-", ".mp4", directory);
        boolean complete = false;
        try (InputStream input = getContext().getAssets().open(VIDEO_ASSET);
             FileOutputStream output = new FileOutputStream(copy)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            complete = true;
            return copy;
        } finally {
            if (!complete) copy.delete();
        }
    }

    private void updateTextureTransform() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        int sourceWidth = videoWidth > 0 ? videoWidth : SOURCE_WIDTH;
        int sourceHeight = videoHeight > 0 ? videoHeight : SOURCE_HEIGHT;
        float fitScale = Math.min(width / (float) sourceWidth,
                height / (float) sourceHeight);
        float contentScaleX = sourceWidth * fitScale / width;
        float contentScaleY = sourceHeight * fitScale / height;
        Matrix transform = new Matrix();
        transform.setScale(contentScaleX, contentScaleY,
                width * 0.5f, height * 0.5f);
        setTransform(transform);

        if (Build.VERSION.SDK_INT >= 33 && keyShader != null) {
            Api33.setPixelStep(keyShader, Math.max(1f, fitScale * 7f));
        }
    }

    private void applyPlaybackSpeed(MediaPlayer target) {
        try {
            PlaybackParams params = new PlaybackParams();
            params.setSpeed(playbackSpeed);
            params.setPitch(1f);
            target.setPlaybackParams(params);
        } catch (RuntimeException error) {
            Log.w(TAG, "Playback speed could not be applied", error);
        }
    }

    private boolean isCurrent(MediaPlayer owner, int generation) {
        return !released && player == owner && playbackGeneration == generation;
    }

    private void failPlayback(MediaPlayer owner, int generation,
                              String message, Throwable cause) {
        if (!isCurrent(owner, generation)) return;
        cleanupPlayerResources();
        reportError(generation, message, cause);
    }

    private void reportError(int generation, String message) {
        reportError(generation, message, null);
    }

    private void reportError(int generation, String message, Throwable cause) {
        main.post(() -> {
            if (released || generation != playbackGeneration || errorSent) return;
            errorSent = true;
            if (cause == null) Log.e(TAG, message);
            else Log.e(TAG, message, cause);
            Listener callback = listener;
            if (callback != null) {
                try {
                    callback.onError();
                } catch (RuntimeException ignored) {
                }
            }
        });
    }

    private int safeDuration(MediaPlayer mediaPlayer) {
        try {
            return mediaPlayer.getDuration();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private void stopPlayback() {
        playbackGeneration++;
        main.removeCallbacks(loopMonitor);
        cleanupPlayerResources();
    }

    private void cleanupPlayerResources() {
        prepared = false;
        MediaPlayer oldPlayer = player;
        player = null;
        if (oldPlayer != null) {
            oldPlayer.setOnPreparedListener(null);
            oldPlayer.setOnVideoSizeChangedListener(null);
            oldPlayer.setOnErrorListener(null);
            oldPlayer.setOnCompletionListener(null);
            try {
                oldPlayer.release();
            } catch (RuntimeException ignored) {
            }
        }
        Surface oldSurface = surface;
        surface = null;
        if (oldSurface != null) {
            try {
                oldSurface.release();
            } catch (RuntimeException ignored) {
            }
        }
        File oldCopy = cachedAssetFile;
        cachedAssetFile = null;
        if (oldCopy != null) oldCopy.delete();
    }

    private static final class Api33 {
        static Object installEffect(EarbudRotationView view, String source) {
            RuntimeShader shader = new RuntimeShader(source);
            shader.setFloatUniform("pixelStep", 2f);
            view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "videoFrame"));
            return shader;
        }

        static void setPixelStep(Object shader, float pixelStep) {
            ((RuntimeShader) shader).setFloatUniform("pixelStep", pixelStep);
        }

        static void clearEffect(EarbudRotationView view) {
            view.setRenderEffect(null);
        }
    }
}
