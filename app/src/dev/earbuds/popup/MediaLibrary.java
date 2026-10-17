package dev.earbuds.popup;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Private, validated media storage for model-specific popup artwork. */
public final class MediaLibrary {
    private static final long MAX_IMPORT_BYTES = 40L * 1024L * 1024L;
    private static final int MAX_IMAGE_EDGE = 4096;
    private static final int MAX_VIDEO_EDGE = 1920;
    private static final long MAX_VIDEO_DURATION_MS = 60_000L;
    private static final int DECODE_EDGE = 1024;
    private static final int META_MAGIC = 0x45424D31;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "earbud-media");
        thread.setDaemon(true);
        return thread;
    });

    public interface Callback {
        void onImported(String assetId);
        void onError(String message);
    }

    enum Kind {
        IMAGE,
        VIDEO
    }

    static final class AssetInfo {
        final String assetId;
        final File file;
        final Kind kind;
        final String format;
        final boolean animated;
        final int width;
        final int height;
        final long durationMillis;
        final long byteCount;

        AssetInfo(String assetId, File file, Kind kind, String format, boolean animated,
                  int width, int height, long durationMillis, long byteCount) {
            this.assetId = assetId;
            this.file = file;
            this.kind = kind;
            this.format = format;
            this.animated = animated;
            this.width = width;
            this.height = height;
            this.durationMillis = durationMillis;
            this.byteCount = byteCount;
        }
    }

    private MediaLibrary() {}

    public static void importMedia(Context context, Uri uri, Callback callback) {
        if (callback == null) return;
        if (context == null || uri == null) {
            postError(callback, "无法读取所选文件");
            return;
        }
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        final Context appContext = app;
        IO.execute(() -> importOnWorker(appContext, uri, callback));
    }

    public static String describe(Context context, String assetId) {
        if (assetId == null || assetId.trim().isEmpty()) return "未选择素材";
        if ("builtin:freebuds_6i".equals(assetId)) return "内置素材未随公开版提供，请导入自有媒体";
        if (assetId.startsWith("media:")) return "自定义媒体";
        return "素材不可用，请重新导入";
    }

    static void executeIo(Runnable work) {
        if (work != null) IO.execute(work);
    }

    /** Resolves a previously validated imported-media ID. Call from a worker thread. */
    static AssetInfo resolve(Context context, String modelId, String assetId) {
        if (context == null || assetId == null || assetId.isEmpty()) return null;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        String token = importedToken(assetId);
        if (token == null) return null;
        File root = mediaDirectory(app);
        File media = new File(root, token + ".media");
        File metadata = new File(root, token + ".meta");
        if (!media.isFile() || !metadata.isFile()) return null;
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(new FileInputStream(metadata)))) {
            if (input.readInt() != META_MAGIC || input.readInt() != 1) return null;
            int kindOrdinal = input.readUnsignedByte();
            if (kindOrdinal >= Kind.values().length) return null;
            Kind kind = Kind.values()[kindOrdinal];
            String format = input.readUTF();
            boolean animated = input.readBoolean();
            int width = input.readInt();
            int height = input.readInt();
            long duration = input.readLong();
            long byteCount = input.readLong();
            if (media.length() != byteCount || byteCount <= 0L
                    || byteCount > MAX_IMPORT_BYTES) return null;
            if (kind == Kind.IMAGE && (width <= 0 || height <= 0
                    || width > MAX_IMAGE_EDGE || height > MAX_IMAGE_EDGE)) return null;
            if (kind == Kind.VIDEO && (width <= 0 || height <= 0
                    || width > MAX_VIDEO_EDGE || height > MAX_VIDEO_EDGE
                    || duration <= 0L || duration > MAX_VIDEO_DURATION_MS)) return null;
            return new AssetInfo(assetId, media, kind, format, animated, width,
                    height, duration, byteCount);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Decodes a validated image to at most 1024 pixels on its longest edge.
     * This method is intended for the media worker, not the UI thread.
     */
    static Drawable decodeImage(Context context, AssetInfo asset) throws IOException {
        if (asset == null || asset.kind != Kind.IMAGE || !asset.file.isFile()) return null;
        int[] size = readImageBounds(asset.file);
        if (size == null || size[0] <= 0 || size[1] <= 0
                || size[0] > MAX_IMAGE_EDGE || size[1] > MAX_IMAGE_EDGE) {
            throw new IOException("图片尺寸无效");
        }

        float scale = Math.min(1f, (float) DECODE_EDGE / Math.max(size[0], size[1]));
        int targetWidth = Math.max(1, Math.round(size[0] * scale));
        int targetHeight = Math.max(1, Math.round(size[1] * scale));
        if (Build.VERSION.SDK_INT >= 28) {
            return decodeWithImageDecoder(asset.file, targetWidth, targetHeight);
        }
        Bitmap bitmap = decodeBitmap(asset.file, targetWidth, targetHeight);
        if (bitmap == null) throw new IOException("图片无法解码");
        return new android.graphics.drawable.BitmapDrawable(
                context.getResources(), bitmap);
    }

    private static Drawable decodeWithImageDecoder(File file, int targetWidth,
                                                    int targetHeight) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(file);
        return ImageDecoder.decodeDrawable(source, (decoder, info, src) ->
                decoder.setTargetSize(targetWidth, targetHeight));
    }

    private static Bitmap decodeBitmap(File file, int targetWidth, int targetHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = sampleSize(bounds.outWidth, bounds.outHeight, Math.max(targetWidth, targetHeight));
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static void importOnWorker(Context context, Uri uri, Callback callback) {
        File root = mediaDirectory(context);
        File staged = null;
        File finalFile = null;
        File metaFile = null;
        File stagedMeta = null;
        String resultId = null;
        String failure = null;
        try {
            if (!root.isDirectory() && !root.mkdirs()) {
                throw new IOException("无法创建素材目录");
            }
            staged = File.createTempFile("incoming-", ".tmp", root);
            ContentResolver resolver = context.getContentResolver();
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) throw new IOException("无法读取所选文件");
                copyBounded(input, staged, MAX_IMPORT_BYTES);
            }

            MediaInfo info = validateMedia(staged);
            String declaredMime = resolver.getType(uri);
            if (!mimeMatches(declaredMime, info.kind)) {
                throw new IOException("文件类型与实际内容不匹配");
            }

            String token = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
            resultId = "media:" + token;
            finalFile = new File(root, token + ".media");
            metaFile = new File(root, token + ".meta");
            stagedMeta = new File(root, token + ".meta.tmp");
            if (!staged.renameTo(finalFile)) {
                throw new IOException("无法保存素材");
            }
            writeMetadata(stagedMeta, info, finalFile.length());
            if (!stagedMeta.renameTo(metaFile)) {
                throw new IOException("无法保存素材索引");
            }
            staged = null;
            stagedMeta = null;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            failure = messageFor(e);
            if (finalFile != null) finalFile.delete();
            if (metaFile != null) metaFile.delete();
            resultId = null;
        } finally {
            if (staged != null) staged.delete();
            if (stagedMeta != null) stagedMeta.delete();
        }

        if (resultId != null) {
            final String importedId = resultId;
            MAIN.post(() -> safeImported(callback, importedId));
        } else {
            final String error = failure == null ? "无法导入素材" : failure;
            MAIN.post(() -> safeError(callback, error));
        }
    }

    private static final class MediaInfo {
        final Kind kind;
        final String format;
        final boolean animated;
        final int width;
        final int height;
        final long durationMillis;

        MediaInfo(Kind kind, String format, boolean animated,
                  int width, int height, long durationMillis) {
            this.kind = kind;
            this.format = format;
            this.animated = animated;
            this.width = width;
            this.height = height;
            this.durationMillis = durationMillis;
        }
    }

    private static MediaInfo validateMedia(File file) throws IOException {
        if (file.length() <= 0L || file.length() > MAX_IMPORT_BYTES) {
            throw new IOException("文件超过40 MiB或为空");
        }
        String signature = sniff(file);
        if ("mp4".equals(signature)) return validateVideo(file);
        if (signature == null) throw new IOException("仅支持图片、GIF、动态 WebP 和 MP4");
        int[] bounds = readImageBounds(file);
        if (bounds == null || bounds[0] <= 0 || bounds[1] <= 0
                || bounds[0] > MAX_IMAGE_EDGE || bounds[1] > MAX_IMAGE_EDGE) {
            throw new IOException("图片尺寸无效或超过4096×4096");
        }
        boolean animated = "gif".equals(signature) || isAnimatedWebp(file);
        validateImageDecode(file, bounds[0], bounds[1]);
        return new MediaInfo(Kind.IMAGE, signature, animated, bounds[0], bounds[1], 0L);
    }

    private static MediaInfo validateVideo(File file) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            int width = parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            long duration = parseLong(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (width <= 0 || height <= 0 || width > MAX_VIDEO_EDGE
                    || height > MAX_VIDEO_EDGE || duration <= 0L
                    || duration > MAX_VIDEO_DURATION_MS) {
                throw new IOException("视频须不超过1920×1920且时长不超过60秒");
            }
            return new MediaInfo(Kind.VIDEO, "mp4", false, width, height, duration);
        } catch (RuntimeException e) {
            throw new IOException("MP4视频无法解码", e);
        } finally {
            try {
                retriever.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void validateImageDecode(File file, int width, int height)
            throws IOException {
        float scale = Math.min(1f, (float) DECODE_EDGE / Math.max(width, height));
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        if (Build.VERSION.SDK_INT >= 28) {
            Drawable drawable = decodeWithImageDecoder(file, targetWidth, targetHeight);
            if (drawable == null) throw new IOException("图片无法解码");
            if (drawable instanceof AnimatedImageDrawable) {
                ((AnimatedImageDrawable) drawable).stop();
            }
            return;
        }
        Bitmap bitmap = decodeBitmap(file, targetWidth, targetHeight);
        if (bitmap == null) throw new IOException("图片无法解码");
        bitmap.recycle();
    }

    private static int[] readImageBounds(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        options.inScaled = false;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        return new int[]{options.outWidth, options.outHeight};
    }

    private static boolean isAnimatedWebp(File file) {
        if (Build.VERSION.SDK_INT < 28) return false;
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] header = new byte[32];
            int count = readUpTo(input, header);
            if (count < 21 || !ascii(header, 0, "RIFF")
                    || !ascii(header, 8, "WEBP")) return false;
            String chunk = new String(header, 12, 4, StandardCharsets.US_ASCII);
            if ("ANIM".equals(chunk)) return true;
            if (!"VP8X".equals(chunk)) return false;
            return (header[20] & 0x02) != 0;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String sniff(File file) throws IOException {
        byte[] head = new byte[16];
        int read;
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            read = readUpTo(input, head);
        }
        if (read >= 6 && (ascii(head, 0, "GIF87a") || ascii(head, 0, "GIF89a"))) {
            return "gif";
        }
        if (read >= 12 && ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP")) {
            return "webp";
        }
        if (read >= 8 && (head[0] & 0xff) == 0x89 && ascii(head, 1, "PNG")
                && head[4] == 13 && head[5] == 10 && (head[6] & 0xff) == 0x1a
                && head[7] == 10) {
            return "png";
        }
        if (read >= 3 && (head[0] & 0xff) == 0xff
                && (head[1] & 0xff) == 0xd8 && (head[2] & 0xff) == 0xff) {
            return "jpeg";
        }
        if (read >= 12 && ascii(head, 4, "ftyp")) return "mp4";
        return null;
    }

    private static boolean mimeMatches(String mime, Kind kind) {
        if (mime == null || mime.isEmpty()
                || "application/octet-stream".equalsIgnoreCase(mime)
                || "binary/octet-stream".equalsIgnoreCase(mime)) return true;
        String normalized = mime.toLowerCase(Locale.ROOT);
        return kind == Kind.IMAGE ? normalized.startsWith("image/")
                : normalized.equals("video/mp4") || normalized.equals("application/mp4");
    }

    private static void copyBounded(InputStream input, File destination, long limit)
            throws IOException {
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[16 * 1024];
            long total = 0L;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IOException("文件超过40 MiB");
                output.write(buffer, 0, count);
            }
            output.flush();
            if (output instanceof BufferedOutputStream) {
                ((BufferedOutputStream) output).flush();
            }
        }
        try (FileOutputStream sync = new FileOutputStream(destination, true)) {
            sync.getFD().sync();
        }
    }

    private static void writeMetadata(File file, MediaInfo info, long byteCount)
            throws IOException {
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            output.writeInt(META_MAGIC);
            output.writeInt(1);
            output.writeByte(info.kind.ordinal());
            output.writeUTF(info.format);
            output.writeBoolean(info.animated);
            output.writeInt(info.width);
            output.writeInt(info.height);
            output.writeLong(info.durationMillis);
            output.writeLong(byteCount);
            output.flush();
        }
        try (FileOutputStream sync = new FileOutputStream(file, true)) {
            sync.getFD().sync();
        }
    }

    private static File mediaDirectory(Context context) {
        return new File(context.getFilesDir(), "popup-media");
    }

    private static String importedToken(String assetId) {
        if (!assetId.startsWith("media:")) return null;
        String token = assetId.substring("media:".length());
        if (token.length() != 36) return null;
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || ch == '-')) {
                return null;
            }
        }
        return token;
    }

    private static int sampleSize(int width, int height, int maxEdge) {
        int sample = 1;
        int edge = Math.max(width, height);
        while (edge / sample > maxEdge) sample *= 2;
        return sample;
    }

    private static int readUpTo(InputStream input, byte[] bytes) throws IOException {
        int total = 0;
        while (total < bytes.length) {
            int count = input.read(bytes, total, bytes.length - total);
            if (count < 0) break;
            total += count;
        }
        return total;
    }

    private static int parseInt(String value) {
        try {
            return value == null ? -1 : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static long parseLong(String value) {
        try {
            return value == null ? -1L : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static boolean ascii(byte[] bytes, int offset, String value) {
        if (offset < 0 || offset + value.length() > bytes.length) return false;
        for (int i = 0; i < value.length(); i++) {
            if ((byte) value.charAt(i) != bytes[offset + i]) return false;
        }
        return true;
    }

    private static String messageFor(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? "素材导入失败" : message;
    }

    private static void postError(Callback callback, String message) {
        MAIN.post(() -> safeError(callback, message));
    }

    private static void safeImported(Callback callback, String assetId) {
        try {
            callback.onImported(assetId);
        } catch (RuntimeException ignored) {
        }
    }

    private static void safeError(Callback callback, String message) {
        try {
            callback.onError(message);
        } catch (RuntimeException ignored) {
        }
    }
}
