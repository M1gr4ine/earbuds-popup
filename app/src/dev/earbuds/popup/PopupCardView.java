package dev.earbuds.popup;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Outline;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;

/** Native renderer shared by the explicit preview and the connection overlay. */
public final class PopupCardView extends FrameLayout {
    private static final String TAG = "PopupCardView";
    private static final int HEADER_HEIGHT_DP = 72;
    private static final int BATTERY_ROW_HEIGHT_DP = 58;
    private static final int BOTTOM_PADDING_DP = 12;
    private static final int CUSTOM_MIN_HEIGHT_DP = 240;
    private static final int CUSTOM_TOP_SCRIM_DP = 112;
    private static final int CUSTOM_BOTTOM_SCRIM_DP = 92;
    private static final int CUSTOM_BATTERY_BOTTOM_DP = 6;
    private static final float TRAVEL_DP = 18f;
    private static final long ENTER_DURATION_MS = 260L;
    private static final long EXIT_DURATION_MS = 180L;
    private static final PathInterpolator UI_EASE_OUT =
            new PathInterpolator(0.23f, 1f, 0.32f, 1f);
    private static final int TEXT_PRIMARY = Color.rgb(33, 36, 41);
    private static final int TEXT_SECONDARY = Color.rgb(120, 126, 134);
    private static final int ACCENT_CONNECTED = Color.rgb(42, 178, 116);
    private static final int ACCENT_PREVIEW = Color.rgb(79, 119, 216);

    private final Context appContext;
    private final DeviceStore.DeviceRecord device;
    private final PopupConfig config;
    private final Runnable dismiss;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameLayout card;
    private final FrameLayout mediaContainer;
    private final ImageView imageView;
    private ImageView earbudImageView;
    private EarbudRotationView earbudRotationView;
    private final LinearLayout batteryRow;
    private final LinearLayout batteryCells;
    private final BatteryCellView leftBatteryCell;
    private final BatteryCellView rightBatteryCell;
    private final BatteryCellView caseBatteryCell;
    private final BatteryCellView overallBatteryCell;
    private final LinearLayout unknownBatteryCell;
    private final BatteryBadgeView unknownBatteryBadge;
    private final TextView unknownBatteryText;
    private final int travelY;
    private final String modelId;
    private final boolean customMedia;

    private ValueAnimator motionAnimator;
    private AnimatedImageDrawable animatedImage;
    private TextureView videoTexture;
    private MediaPlayer videoPlayer;
    private Surface videoSurface;
    private volatile boolean released;
    private int mediaGeneration;
    private int videoWidth;
    private int videoHeight;
    private int videoFramesSinceLog;
    private long lastVideoFrameLogAt;
    private float videoSpeed = 1f;

    public PopupCardView(Context context, DeviceStore.DeviceRecord device,
                         PopupConfig config, BatterySnapshot battery,
                         boolean preview, Runnable dismiss) {
        super(context);
        Context applicationContext = context.getApplicationContext();
        this.appContext = applicationContext == null ? context : applicationContext;
        this.device = device;
        this.modelId = device == null || device.modelId == null ? "" : device.modelId;
        PopupConfig supplied = config == null
                ? PopupConfig.defaults(this.modelId) : config.copy();
        this.config = supplied.sanitized();
        this.dismiss = dismiss;
        this.videoSpeed = validVideoSpeed(this.config.videoSpeed);
        String selectedAssetId = this.config.assetId == null ? "" : this.config.assetId.trim();
        this.customMedia = selectedAssetId.startsWith("media:");
        this.travelY = dp(this.config.position == 0 ? -TRAVEL_DP : TRAVEL_DP);

        setBackgroundColor(Color.TRANSPARENT);
        setClipChildren(false);
        setClipToPadding(false);
        setClickable(false);
        setFocusable(false);
        setMinimumHeight(dp(heightDp()));
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        card = new FrameLayout(context);
        GradientDrawable cardBackground = new GradientDrawable();
        int configuredColor = this.config.backgroundColor;
        int backgroundAlpha = Math.max(0, Math.min(100, this.config.backgroundAlpha));
        int effectiveAlpha = Math.round(Color.alpha(configuredColor) * backgroundAlpha / 100f);
        cardBackground.setColor(Color.argb(effectiveAlpha, Color.red(configuredColor),
                Color.green(configuredColor), Color.blue(configuredColor)));
        cardBackground.setCornerRadius(dp(Math.max(0, this.config.radiusDp)));
        card.setBackground(cardBackground);
        // Give every child (including TextureView and scrims) the same silhouette.
        // Overlay windows have no shadow inset: elevation would be cut off at the
        // window edge and leave a dark rim beneath the bottom rounded corners.
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        dp(PopupCardView.this.config.radiusDp));
            }
        });
        card.setClipToOutline(true);
        card.setElevation(0f);
        addView(card, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));

        mediaContainer = new FrameLayout(context);
        mediaContainer.setClipChildren(true);
        mediaContainer.setClipToPadding(true);
        imageView = new ImageView(context);
        imageView.setScaleType(this.config.fitCrop
                ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(false);
        imageView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int placeholderId = context.getResources().getIdentifier(
                "ic_launcher_foreground", "drawable", context.getPackageName());
        if (placeholderId != 0) {
            Drawable placeholder = context.getDrawable(placeholderId).mutate();
            placeholder.setTint(TEXT_SECONDARY);
            imageView.setImageDrawable(placeholder);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
        mediaContainer.addView(imageView, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout header = createHeader(context, preview, customMedia);

        batteryRow = new LinearLayout(context);
        batteryRow.setOrientation(LinearLayout.HORIZONTAL);
        batteryRow.setGravity(Gravity.CENTER_VERTICAL);
        batteryRow.setPadding(dp(20), 0, dp(20), 0);
        batteryRow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        batteryCells = new LinearLayout(context);
        batteryCells.setOrientation(LinearLayout.HORIZONTAL);
        batteryCells.setGravity(Gravity.CENTER_VERTICAL);
        batteryRow.addView(batteryCells, new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1f));

        leftBatteryCell = new BatteryCellView(context, "\u5DE6\u8033", customMedia);
        rightBatteryCell = new BatteryCellView(context, "\u53F3\u8033", customMedia);
        caseBatteryCell = new BatteryCellView(context, "\u5145\u7535\u76D2", customMedia);
        overallBatteryCell = new BatteryCellView(context, "\u603B\u4F53", customMedia);
        batteryCells.addView(leftBatteryCell, new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1f));
        batteryCells.addView(rightBatteryCell, new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1f));
        batteryCells.addView(caseBatteryCell, new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1f));
        batteryCells.addView(overallBatteryCell, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        unknownBatteryCell = new LinearLayout(context);
        unknownBatteryCell.setOrientation(LinearLayout.HORIZONTAL);
        unknownBatteryCell.setGravity(Gravity.CENTER_VERTICAL);
        unknownBatteryBadge = new BatteryBadgeView(context);
        unknownBatteryBadge.setLightOnDark(customMedia);
        unknownBatteryBadge.setBattery(-1, -1, false);
        unknownBatteryCell.addView(unknownBatteryBadge, new LinearLayout.LayoutParams(
                dp(26), dp(18)));
        unknownBatteryText = new TextView(context);
        unknownBatteryText.setText("\u7535\u91CF\u672A\u77E5");
        unknownBatteryText.setTextColor(customMedia ? Color.WHITE : TEXT_SECONDARY);
        unknownBatteryText.setTextSize(12f);
        LinearLayout.LayoutParams unknownTextParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        unknownTextParams.leftMargin = dp(6);
        unknownBatteryCell.addView(unknownBatteryText, unknownTextParams);
        batteryCells.addView(unknownBatteryCell, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        if (customMedia) {
            card.addView(mediaContainer, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            if (this.config.showEarbuds) {
                earbudImageView = new ImageView(context);
                earbudImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                earbudImageView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                FrameLayout.LayoutParams earbudParams = new FrameLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
                earbudParams.topMargin = dp(HEADER_HEIGHT_DP);
                earbudParams.bottomMargin = dp(this.config.showBattery
                        ? BATTERY_ROW_HEIGHT_DP + CUSTOM_BATTERY_BOTTOM_DP : BOTTOM_PADDING_DP);
                earbudParams.leftMargin = dp(24);
                earbudParams.rightMargin = dp(24);
                card.addView(earbudImageView, earbudParams);
                if (DeviceModel.MODEL_FREEBUDS_6I.equals(this.modelId)
                        && this.config.earbudAssetId.isEmpty()
                        && EarbudRotationView.isSupported()
                        && ValueAnimator.areAnimatorsEnabled()) {
                    try {
                        earbudRotationView = new EarbudRotationView(context,
                                new EarbudRotationView.Listener() {
                            @Override public void onReady() {
                                if (released || earbudRotationView == null) return;
                                earbudRotationView.setAlpha(1f);
                                earbudImageView.setVisibility(View.INVISIBLE);
                            }
                            @Override public void onError() {
                                if (released || earbudRotationView == null) return;
                                earbudRotationView.setAlpha(0f);
                                earbudImageView.setVisibility(View.VISIBLE);
                            }
                        });
                        // A visible TextureView must attach before it can publish its
                        // first video frame. Keep a nonzero, imperceptible alpha so
                        // HWUI also draws the texture before its ready callback.
                        earbudRotationView.setAlpha(0.001f);
                        earbudRotationView.setPlaybackSpeed(this.config.videoSpeed);
                        card.addView(earbudRotationView,
                                new FrameLayout.LayoutParams(earbudParams));
                    } catch (RuntimeException | LinkageError error) {
                        if (earbudRotationView != null) earbudRotationView.release();
                        earbudRotationView = null;
                        Log.w(TAG, "Earbud rotation unavailable; keeping static cutout", error);
                    }
                }
            }
            card.addView(new GradientScrimView(context, true), new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(CUSTOM_TOP_SCRIM_DP), Gravity.TOP));
            GradientScrimView bottomScrim = new GradientScrimView(context, false);
            bottomScrim.setVisibility(this.config.showBattery ? View.VISIBLE : View.GONE);
            card.addView(bottomScrim, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(CUSTOM_BOTTOM_SCRIM_DP), Gravity.BOTTOM));
            card.addView(header, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(HEADER_HEIGHT_DP), Gravity.TOP));
            FrameLayout.LayoutParams batteryParams = new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(BATTERY_ROW_HEIGHT_DP), Gravity.BOTTOM);
            batteryParams.bottomMargin = dp(CUSTOM_BATTERY_BOTTOM_DP);
            card.addView(batteryRow, batteryParams);
        } else {
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setClipChildren(true);
            card.addView(content, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            content.addView(header, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(HEADER_HEIGHT_DP)));
            content.addView(mediaContainer, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(this.config.mediaHeightDp)));
            content.addView(batteryRow, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(BATTERY_ROW_HEIGHT_DP)));
            content.addView(new View(context), new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(BOTTOM_PADDING_DP)));
        }

        if (!this.config.showBattery) batteryRow.setVisibility(View.GONE);
        else renderBattery(battery, false);

        setAlpha(0f);
        setTranslationY(travelY);
        loadSelectedMedia();
        loadEarbudImage();
    }

    /** Total height for the host WindowManager region, in density-independent pixels. */
    public int heightDp() {
        if (customMedia) return Math.max(CUSTOM_MIN_HEIGHT_DP,
                Math.max(0, config.mediaHeightDp));
        return HEADER_HEIGHT_DP + Math.max(0, config.mediaHeightDp)
                + (config.showBattery ? BATTERY_ROW_HEIGHT_DP : 0) + BOTTOM_PADDING_DP;
    }

    /** Enter from above for top placement and from below for center/bottom placement. */
    public void playEntrance() {
        if (!onMainThread()) {
            main.post(this::playEntrance);
            return;
        }
        if (released) return;
        animateTo(1f, 0f, ENTER_DURATION_MS, null);
    }

    /** Animate out, then let the host remove its overlay window. */
    public void playExit(Runnable completion) {
        if (!onMainThread()) {
            main.post(() -> playExit(completion));
            return;
        }
        if (released) {
            if (completion != null) completion.run();
            return;
        }
        animateTo(0f, travelY, EXIT_DURATION_MS, completion);
    }

    /** Stop and release all transient renderer state before the host discards this view. */
    public void release() {
        if (!onMainThread()) {
            main.post(this::release);
            return;
        }
        if (released) return;
        released = true;
        stopBatteryAnimations();
        mediaGeneration++;
        cancelMotion();
        stopAnimatedImage();
        releaseVideoPlayer();
        if (videoTexture != null) {
            videoTexture.setSurfaceTextureListener(null);
            videoTexture = null;
        }
        if (earbudRotationView != null) earbudRotationView.release();
        imageView.setImageDrawable(null);
        if (earbudImageView != null) earbudImageView.setImageDrawable(null);
        mediaContainer.removeAllViews();
    }

    /** Replace only the displayed battery snapshot; never synthesize missing component values. */
    public void setBattery(BatterySnapshot snapshot) {
        if (!onMainThread()) {
            main.post(() -> setBattery(snapshot));
            return;
        }
        if (released || !config.showBattery) return;
        renderBattery(snapshot, true);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        release();
    }

    private LinearLayout createHeader(Context context, boolean preview, boolean onImage) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), 0, dp(12), 0);

        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(info, new LinearLayout.LayoutParams(0,
                LayoutParams.MATCH_PARENT, 1f));

        TextView name = new TextView(context);
        String deviceName = device == null || device.name == null ? "" : device.name.trim();
        name.setText(deviceName.isEmpty() ? "蓝牙设备" : deviceName);
        name.setTextColor(onImage ? Color.WHITE : TEXT_PRIMARY);
        name.setTextSize(17f);
        name.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(name, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout status = new LinearLayout(context);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(3);
        info.addView(status, statusParams);

        View dotView = new View(context);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(preview ? ACCENT_PREVIEW : ACCENT_CONNECTED);
        dotView.setBackground(dot);
        status.addView(dotView, new LinearLayout.LayoutParams(dp(6), dp(6)));

        TextView statusText = new TextView(context);
        statusText.setText(preview ? "预览" : "已连接");
        statusText.setTextColor(onImage ? 0xFFE9EAEC : TEXT_SECONDARY);
        statusText.setTextSize(12f);
        LinearLayout.LayoutParams statusTextParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        statusTextParams.leftMargin = dp(6);
        status.addView(statusText, statusTextParams);

        CloseButton closeButton = new CloseButton(context, onImage);
        closeButton.setContentDescription("关闭");
        header.addView(closeButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        closeButton.setOnClickListener(view -> {
            if (!released && dismiss != null) dismiss.run();
        });
        return header;
    }

    private void renderBattery(BatterySnapshot snapshot) {
        renderBattery(snapshot, true);
    }

    private void renderBattery(BatterySnapshot snapshot, boolean animateLevels) {
        if (snapshot == null) snapshot = BatterySnapshot.unknown();

        int left = validLevel(snapshot.left);
        int right = validLevel(snapshot.right);
        int caseLevel = validLevel(snapshot.caseLevel);
        int overall = validLevel(snapshot.overall);
        boolean hasComponentLevels = left >= 0 || right >= 0 || caseLevel >= 0;
        boolean hasComponentStatus = snapshot.leftCharging >= 0
                || snapshot.rightCharging >= 0 || snapshot.caseCharging >= 0;
        boolean hasComponents = hasComponentLevels || hasComponentStatus;

        leftBatteryCell.setBattery(left, snapshot.leftCharging, animateLevels);
        rightBatteryCell.setBattery(right, snapshot.rightCharging, animateLevels);
        caseBatteryCell.setBattery(caseLevel, snapshot.caseCharging, animateLevels);
        overallBatteryCell.setBattery(overall, -1, animateLevels);

        leftBatteryCell.setVisibility(hasComponents ? View.VISIBLE : View.GONE);
        rightBatteryCell.setVisibility(hasComponents ? View.VISIBLE : View.GONE);
        caseBatteryCell.setVisibility(hasComponents ? View.VISIBLE : View.GONE);
        overallBatteryCell.setVisibility(!hasComponents && overall >= 0
                ? View.VISIBLE : View.GONE);
        unknownBatteryCell.setVisibility(!hasComponents && overall < 0
                ? View.VISIBLE : View.GONE);

        batteryRow.setContentDescription(buildBatteryDescription(
                left, right, caseLevel, overall, hasComponents, snapshot));
    }

    private String buildBatteryDescription(int left, int right, int caseLevel,
                                          int overall, boolean hasComponents,
                                          BatterySnapshot snapshot) {
        StringBuilder text = new StringBuilder("\u7535\u91CF");
        if (hasComponents) {
            appendLevel(text, "\u5DE6\u8033", left);
            if (snapshot.leftCharging == 1) text.append("\uFF0C\u5DE6\u8033\u6B63\u5728\u5145\u7535");
            appendLevel(text, "\u53F3\u8033", right);
            if (snapshot.rightCharging == 1) text.append("\uFF0C\u53F3\u8033\u6B63\u5728\u5145\u7535");
            appendLevel(text, "\u5145\u7535\u76D2", caseLevel);
            if (snapshot.caseCharging == 1) text.append("\uFF0C\u5145\u7535\u76D2\u6B63\u5728\u5145\u7535");
        } else if (overall >= 0) {
            appendLevel(text, "\u6574\u4F53", overall);
        } else {
            text.append("\u672A\u77E5");
        }
        return text.toString();
    }

    private static void appendLevel(StringBuilder text, String label, int level) {
        text.append("，").append(label).append(" ");
        text.append(level >= 0 ? level + "%" : "未知");
    }

    private void stopBatteryAnimations() {
        leftBatteryCell.stopAnimators();
        rightBatteryCell.stopAnimators();
        caseBatteryCell.stopAnimators();
        overallBatteryCell.stopAnimators();
        unknownBatteryBadge.stopAnimators();
    }

    private void loadEarbudImage() {
        if (earbudImageView == null) return;
        final int generation = mediaGeneration;
        MediaLibrary.executeIo(() -> {
            Drawable decoded = null;
            if (!released) {
                try {
                    decoded = MediaLibrary.decodeEarbudImage(appContext, modelId,
                            config.earbudAssetId);
                } catch (IOException | RuntimeException | OutOfMemoryError error) {
                    Log.w(TAG, "Earbud image unavailable", error);
                }
            }
            final Drawable image = decoded;
            main.post(() -> {
                if (released || generation != mediaGeneration) {
                    stopIfAnimated(image);
                    return;
                }
                earbudImageView.setImageDrawable(image);
            });
        });
    }

    private void loadSelectedMedia() {
        String assetId = config.assetId == null ? "" : config.assetId.trim();
        if (assetId.isEmpty()) return;

        final int generation = ++mediaGeneration;
        final String expectedModel = modelId;
        MediaLibrary.executeIo(() -> {
            MediaLibrary.AssetInfo asset = MediaLibrary.resolve(
                    appContext, expectedModel, assetId);
            Drawable decoded = null;
            if (!released && asset != null && asset.kind == MediaLibrary.Kind.IMAGE) {
                try {
                    decoded = MediaLibrary.decodeImage(appContext, asset);
                } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
                    decoded = null;
                }
            }
            final Drawable image = decoded;
            main.post(() -> installMedia(generation, asset, image));
        });
    }

    private void installMedia(int generation, MediaLibrary.AssetInfo asset, Drawable image) {
        if (released || generation != mediaGeneration || asset == null) {
            stopIfAnimated(image);
            return;
        }
        if (asset.kind == MediaLibrary.Kind.IMAGE) {
            if (image == null) return;
            imageView.setScaleType(config.fitCrop
                    ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            imageView.setImageDrawable(image);
            if (image instanceof AnimatedImageDrawable) {
                animatedImage = (AnimatedImageDrawable) image;
                animatedImage.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                if (ValueAnimator.areAnimatorsEnabled()) animatedImage.start();
            }
            return;
        }
        installVideo(asset, generation);
    }

    private void installVideo(MediaLibrary.AssetInfo asset, int generation) {
        if (released || generation != mediaGeneration || asset.file == null
                || !asset.file.isFile()) return;
        videoWidth = asset.width;
        videoHeight = asset.height;
        videoFramesSinceLog = 0;
        lastVideoFrameLogAt = 0L;
        TextureView texture = new TextureView(getContext());
        texture.setOpaque(false);
        texture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        videoTexture = texture;
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(
                    android.graphics.SurfaceTexture surfaceTexture,
                    int bufferWidth, int bufferHeight) {
                if (released || generation != mediaGeneration
                        || videoTexture != texture || videoPlayer != null) return;
                startVideo(asset, texture, surfaceTexture, generation);
                updateVideoTransform(texture, texture.getWidth(), texture.getHeight(),
                        videoWidth, videoHeight);
            }

            @Override
            public void onSurfaceTextureSizeChanged(
                    android.graphics.SurfaceTexture surfaceTexture,
                    int bufferWidth, int bufferHeight) {
                if (released || videoTexture != texture) return;
                // Listener dimensions describe producer buffers, not the TextureView viewport.
                Log.d(TAG, "video buffer changed=" + bufferWidth + "x" + bufferHeight
                        + " viewport=" + texture.getWidth() + "x" + texture.getHeight());
                updateVideoTransform(texture, texture.getWidth(), texture.getHeight(),
                        videoWidth, videoHeight);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(
                    android.graphics.SurfaceTexture surfaceTexture) {
                if (videoTexture == texture) {
                    releaseVideoPlayer();
                    videoTexture = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(
                    android.graphics.SurfaceTexture surfaceTexture) {
                if (released || videoTexture != texture) return;
                long now = SystemClock.elapsedRealtime();
                if (lastVideoFrameLogAt == 0L) lastVideoFrameLogAt = now;
                videoFramesSinceLog++;
                if (now - lastVideoFrameLogAt >= 1000L) {
                    boolean playing = false;
                    int positionMs = -1;
                    MediaPlayer player = videoPlayer;
                    if (player != null) {
                        try {
                            playing = player.isPlaying();
                            positionMs = player.getCurrentPosition();
                        } catch (RuntimeException ignored) {
                        }
                    }
                    Log.i(TAG, "video frames updated=" + videoFramesSinceLog
                            + " playing=" + playing + " positionMs=" + positionMs);
                    videoFramesSinceLog = 0;
                    lastVideoFrameLogAt = now;
                }
            }
        });
        mediaContainer.addView(texture, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
    }

    private void startVideo(MediaLibrary.AssetInfo asset, TextureView texture,
                            android.graphics.SurfaceTexture surfaceTexture, int generation) {
        if (released || generation != mediaGeneration) return;
        MediaPlayer player = new MediaPlayer();
        videoPlayer = player;
        videoSurface = new Surface(surfaceTexture);
        try {
            player.setDataSource(asset.file.getAbsolutePath());
            player.setSurface(videoSurface);
            player.setVolume(0f, 0f);
            player.setOnPreparedListener(prepared -> {
                if (released || generation != mediaGeneration || videoPlayer != prepared
                        || videoTexture != texture) {
                    releaseSpecificPlayer(prepared);
                    return;
                }
                int preparedWidth = prepared.getVideoWidth();
                int preparedHeight = prepared.getVideoHeight();
                if (preparedWidth > 0 && preparedHeight > 0) {
                    videoWidth = preparedWidth;
                    videoHeight = preparedHeight;
                }
                updateVideoTransform(texture, texture.getWidth(), texture.getHeight(),
                        videoWidth, videoHeight);
                Log.i(TAG, "video prepared view=" + texture.getWidth() + "x"
                        + texture.getHeight() + " video=" + videoWidth + "x" + videoHeight
                        + " fitCrop=" + config.fitCrop + " speed=" + videoSpeed);
                try {
                    prepared.setLooping(true);
                } catch (RuntimeException loopError) {
                    Log.w(TAG, "video native looping unavailable", loopError);
                }
                try {
                    if (Build.VERSION.SDK_INT >= 23) {
                        prepared.setPlaybackParams(new PlaybackParams().setSpeed(videoSpeed));
                    }
                } catch (RuntimeException ignored) {
                    // Play at the platform default if the selected speed is rejected.
                }
                try {
                    prepared.start();
                    Log.i(TAG, "video start requested");
                } catch (RuntimeException ignored) {
                    releaseVideoPlayer();
                }
            });
            player.setOnCompletionListener(completed -> {
                if (videoPlayer == completed && !released) {
                    // Native looping is configured before start; never manually restart
                    // the player here, which could flash a blank TextureView frame.
                    Log.w(TAG, "video ended despite native looping; keeping final frame");
                }
            });
            player.setOnErrorListener((failed, what, extra) -> {
                if (videoPlayer == failed) releaseVideoPlayer();
                return true;
            });
            player.prepareAsync();
        } catch (IOException | RuntimeException ignored) {
            releaseVideoPlayer();
        }
    }

    private void updateVideoTransform(TextureView texture, int viewWidth, int viewHeight,
                                      int videoWidth, int videoHeight) {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return;
        float viewAspect = (float) viewWidth / viewHeight;
        float videoAspect = (float) videoWidth / videoHeight;
        float scaleX = 1f;
        float scaleY = 1f;
        if (config.fitCrop) {
            if (videoAspect > viewAspect) scaleX = videoAspect / viewAspect;
            else scaleY = viewAspect / videoAspect;
        } else {
            if (videoAspect > viewAspect) scaleY = viewAspect / videoAspect;
            else scaleX = videoAspect / viewAspect;
        }
        Matrix transform = new Matrix();
        transform.setScale(scaleX, scaleY, viewWidth * 0.5f, viewHeight * 0.5f);
        texture.setTransform(transform);
        Log.d(TAG, "video transform view=" + viewWidth + "x" + viewHeight
                + " video=" + videoWidth + "x" + videoHeight
                + " scale=" + scaleX + "x" + scaleY
                + " mode=" + (config.fitCrop ? "crop" : "fit"));
    }

    private void releaseVideoPlayer() {
        videoWidth = 0;
        videoHeight = 0;
        videoFramesSinceLog = 0;
        lastVideoFrameLogAt = 0L;
        MediaPlayer player = videoPlayer;
        videoPlayer = null;
        if (player != null) {
            try {
                player.setOnPreparedListener(null);
                player.setOnCompletionListener(null);
                player.setOnErrorListener(null);
            } catch (RuntimeException ignored) {
            }
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
        }
        Surface surface = videoSurface;
        videoSurface = null;
        if (surface != null) {
            try {
                surface.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void releaseSpecificPlayer(MediaPlayer player) {
        if (player == null) return;
        try {
            player.setOnPreparedListener(null);
            player.setOnCompletionListener(null);
            player.setOnErrorListener(null);
        } catch (RuntimeException ignored) {
        }
        try {
            player.release();
        } catch (RuntimeException ignored) {
        }
    }

    private void stopAnimatedImage() {
        if (animatedImage == null) return;
        try {
            animatedImage.stop();
            animatedImage.setCallback(null);
        } catch (RuntimeException ignored) {
        }
        animatedImage = null;
    }

    private static void stopIfAnimated(Drawable drawable) {
        if (!(drawable instanceof AnimatedImageDrawable)) return;
        try {
            ((AnimatedImageDrawable) drawable).stop();
            drawable.setCallback(null);
        } catch (RuntimeException ignored) {
        }
    }

    private void animateTo(float targetAlpha, float targetTranslationY, long durationMs,
                           Runnable completion) {
        cancelMotion();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            setAlpha(targetAlpha);
            setTranslationY(targetTranslationY);
            if (completion != null) completion.run();
            return;
        }

        final float startAlpha = getAlpha();
        final float startTranslation = getTranslationY();
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        motionAnimator = animator;
        animator.setDuration(durationMs);
        animator.setInterpolator(UI_EASE_OUT);
        animator.addUpdateListener(valueAnimator -> {
            if (released || motionAnimator != animator) return;
            float fraction = (float) valueAnimator.getAnimatedValue();
            setAlpha(startAlpha + (targetAlpha - startAlpha) * fraction);
            setTranslationY(startTranslation
                    + (targetTranslationY - startTranslation) * fraction);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (motionAnimator != animator) return;
                motionAnimator = null;
                if (!released && !cancelled && completion != null) completion.run();
            }
        });
        animator.start();
    }

    private void cancelMotion() {
        ValueAnimator previous = motionAnimator;
        motionAnimator = null;
        if (previous != null) previous.cancel();
    }

    private boolean onMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int validLevel(int value) {
        return value >= 0 && value <= 100 ? value : -1;
    }

    private static float validVideoSpeed(float speed) {
        if (Float.isNaN(speed) || Float.isInfinite(speed)) return 1f;
        return Math.max(0.5f, Math.min(2f, speed));
    }

    /** Lightweight contrast aid over edge-to-edge user media. */
    private final class GradientScrimView extends View {
        private final boolean top;
        private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GradientScrimView(Context context, boolean top) {
            super(context);
            this.top = top;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            if (width <= 0 || height <= 0) return;
            int[] colors = top
                    ? new int[]{0xC9000000, 0x00000000}
                    : new int[]{0x00000000, 0xD9000000};
            scrimPaint.setShader(new LinearGradient(
                    0f, 0f, 0f, height, colors, null, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (scrimPaint.getShader() != null) {
                canvas.drawRect(0f, 0f, getWidth(), getHeight(), scrimPaint);
            }
        }
    }

    private final class BatteryCellView extends LinearLayout {
        private final TextView valueView;
        private final BatteryBadgeView badgeView;

        BatteryCellView(Context context, String label, boolean onImage) {
            super(context);
            setOrientation(LinearLayout.VERTICAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, dp(2), dp(3), dp(2));

            TextView labelView = new TextView(context);
            labelView.setText(label);
            labelView.setTextColor(onImage ? 0xFFE9EAEC : TEXT_SECONDARY);
            labelView.setTextSize(11f);
            labelView.setMaxLines(1);
            addView(labelView, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(18)));

            LinearLayout valueRow = new LinearLayout(context);
            valueRow.setOrientation(LinearLayout.HORIZONTAL);
            valueRow.setGravity(Gravity.CENTER_VERTICAL);
            addView(valueRow, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, 0, 1f));

            badgeView = new BatteryBadgeView(context);
            badgeView.setLightOnDark(onImage);
            valueRow.addView(badgeView, new LinearLayout.LayoutParams(dp(26), dp(18)));

            valueView = new TextView(context);
            valueView.setText("--");
            valueView.setTextColor(onImage ? Color.WHITE : TEXT_PRIMARY);
            valueView.setTextSize(14f);
            valueView.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL));
            valueView.setMaxLines(1);
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                    0, LayoutParams.WRAP_CONTENT, 1f);
            valueParams.leftMargin = dp(3);
            valueRow.addView(valueView, valueParams);
        }

        void setBattery(int level, int charging, boolean animateLevel) {
            int safeLevel = validLevel(level);
            badgeView.setBattery(safeLevel, charging, animateLevel);
            valueView.setText(safeLevel >= 0 ? safeLevel + "%" : "--");
        }

        void stopAnimators() {
            badgeView.stopAnimators();
        }
    }

    private final class CloseButton extends View {
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CloseButton(Context context, boolean onImage) {
            super(context);
            setClickable(true);
            setFocusable(true);
            setBackground(new RippleDrawable(
                    ColorStateList.valueOf(onImage ? 0x33FFFFFF : 0x16000000),
                    null,
                    new CircleMask(Color.WHITE)));
            strokePaint.setColor(onImage ? Color.WHITE : Color.rgb(137, 142, 148));
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeWidth(dp(1.8f));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float centerX = getWidth() * 0.5f;
            float centerY = getHeight() * 0.5f;
            float half = dp(5.5f);
            canvas.drawLine(centerX - half, centerY - half,
                    centerX + half, centerY + half, strokePaint);
            canvas.drawLine(centerX + half, centerY - half,
                    centerX - half, centerY + half, strokePaint);
        }

        private final class CircleMask extends GradientDrawable {
            CircleMask(int color) {
                setShape(GradientDrawable.OVAL);
                setColor(color);
            }
        }
    }
}
