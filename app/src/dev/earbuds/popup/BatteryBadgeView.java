package dev.earbuds.popup;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.PathInterpolator;

/** Small native battery glyph with truthful empty state and level-scaled fill. */
public final class BatteryBadgeView extends View {
    private static final int OUTLINE = Color.rgb(126, 133, 141);
    private static final int GREEN = Color.rgb(49, 174, 100);
    private static final int LOW_RED = Color.rgb(222, 77, 72);
    private static final PathInterpolator LEVEL_EASE =
            new PathInterpolator(0.23f, 1f, 0.32f, 1f);

    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF();
    private final RectF interior = new RectF();
    private final RectF terminal = new RectF();
    private final RectF fill = new RectF();
    private final Path lightning = new Path();

    private int level = -1;
    private int charging = -1;
    private float displayedLevel = -1f;
    private float chargeAlpha = 1f;
    private boolean lightOnDark;
    private ValueAnimator levelAnimator;
    private ValueAnimator chargeAnimator;

    public BatteryBadgeView(Context context) {
        super(context);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeCap(Paint.Cap.ROUND);
        outlinePaint.setStrokeJoin(Paint.Join.ROUND);
        outlinePaint.setColor(OUTLINE);
        outlinePaint.setStrokeWidth(dp(1.25f));
        fillPaint.setStyle(Paint.Style.FILL);
        glyphPaint.setStyle(Paint.Style.FILL);
        setContentDescription("电量未知");
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setLightOnDark(boolean enabled) {
        if (lightOnDark == enabled) return;
        lightOnDark = enabled;
        invalidate();
    }

    void setBattery(int newLevel, int newCharging, boolean animateLevel) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(() -> setBattery(newLevel, newCharging, animateLevel));
            return;
        }

        int normalizedLevel = newLevel >= 0 && newLevel <= 100 ? newLevel : -1;
        int normalizedCharging = newCharging == 1 ? 1
                : newCharging == 0 ? 0 : -1;
        int previousLevel = level;
        boolean levelChanged = normalizedLevel != previousLevel;
        level = normalizedLevel;
        charging = normalizedCharging;

        if (levelChanged) {
            cancelLevelAnimator();
            if (animateLevel && previousLevel >= 0 && normalizedLevel >= 0
                    && ValueAnimator.areAnimatorsEnabled()
                    && isAttachedToWindow() && isShown()) {
                float start = displayedLevel >= 0f ? displayedLevel : previousLevel;
                ValueAnimator animator = ValueAnimator.ofFloat(start, normalizedLevel);
                levelAnimator = animator;
                animator.setDuration(200L);
                animator.setInterpolator(LEVEL_EASE);
                animator.addUpdateListener(value -> {
                    if (levelAnimator != animator) return;
                    displayedLevel = (float) value.getAnimatedValue();
                    invalidate();
                });
                animator.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator animation) {
                        if (levelAnimator != animator) return;
                        levelAnimator = null;
                        displayedLevel = normalizedLevel;
                        invalidate();
                    }
                });
                animator.start();
            } else {
                displayedLevel = normalizedLevel;
            }
        }

        if (normalizedLevel >= 0) {
            setContentDescription("电量 " + normalizedLevel + "%"
                    + (normalizedCharging == 1 ? "，正在充电" : ""));
        } else {
            setContentDescription("电量未知"
                    + (normalizedCharging == 1 ? "，正在充电" : ""));
        }

        updateChargeAnimation();
        invalidate();
    }

    /** Stops both fill and charging animators; the last numeric level remains visible. */
    void stopAnimators() {
        cancelLevelAnimator();
        cancelChargeAnimator();
        chargeAlpha = 1f;
        displayedLevel = level >= 0 ? level : -1f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float stroke = dp(1.25f);
        float terminalWidth = dp(3.2f);
        float sidePad = stroke * 0.5f;
        float verticalPad = dp(2f);

        body.set(sidePad, verticalPad, getWidth() - terminalWidth - sidePad,
                getHeight() - verticalPad);
        float radius = dp(2.2f);
        float inset = stroke + dp(1.5f);
        interior.set(body.left + inset, body.top + inset,
                body.right - inset, body.bottom - inset);
        float terminalHeight = body.height() * 0.40f;
        terminal.set(body.right + stroke * 0.25f,
                body.centerY() - terminalHeight * 0.5f,
                getWidth() - sidePad, body.centerY() + terminalHeight * 0.5f);

        int shownLevel = displayedLevel < 0f ? -1 : Math.round(displayedLevel);
        if (shownLevel >= 0) {
            float fraction = Math.max(0f, Math.min(1f, displayedLevel / 100f));
            float availableWidth = Math.max(0f, interior.width() - dp(0.3f));
            float filledWidth = availableWidth * fraction;
            if (filledWidth > 0f) {
                fill.set(interior.left, interior.top,
                        Math.min(interior.right, interior.left + filledWidth), interior.bottom);
                fillPaint.setColor(shownLevel <= 20 ? LOW_RED : GREEN);
                canvas.drawRoundRect(fill, dp(1.25f), dp(1.25f), fillPaint);
            }
        }

        outlinePaint.setColor(lightOnDark ? Color.WHITE : OUTLINE);
        canvas.drawRoundRect(body, radius, radius, outlinePaint);
        fillPaint.setColor(lightOnDark ? Color.WHITE : OUTLINE);
        canvas.drawRoundRect(terminal, dp(0.8f), dp(0.8f), fillPaint);

        if (shownLevel < 0) drawUnknown(canvas, body, density);
        if (charging == 1) drawLightning(canvas, body, shownLevel, density);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateChargeAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimators();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (isShown()) updateChargeAnimation();
        else {
            cancelChargeAnimator();
            chargeAlpha = 1f;
            invalidate();
        }
    }

    private void drawUnknown(Canvas canvas, RectF battery, float density) {
        glyphPaint.setColor(lightOnDark ? Color.WHITE : OUTLINE);
        glyphPaint.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        glyphPaint.setTextSize(dp(8.5f));
        glyphPaint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics metrics = glyphPaint.getFontMetrics();
        float baseline = battery.centerY() - (metrics.ascent + metrics.descent) * 0.5f;
        float x = charging == 1 ? battery.centerX() - dp(2f) : battery.centerX();
        canvas.drawText("?", x, baseline, glyphPaint);
    }

    private void drawLightning(Canvas canvas, RectF battery, int shownLevel, float density) {
        float centerX = battery.centerX();
        if (shownLevel < 0) centerX += dp(3.8f);
        float centerY = battery.centerY();
        float unit = density;
        lightning.reset();
        lightning.moveTo(centerX + 0.6f * unit, centerY - 3.5f * unit);
        lightning.lineTo(centerX - 1.8f * unit, centerY + 0.3f * unit);
        lightning.lineTo(centerX - 0.1f * unit, centerY + 0.3f * unit);
        lightning.lineTo(centerX - 0.7f * unit, centerY + 3.5f * unit);
        lightning.lineTo(centerX + 2.0f * unit, centerY - 0.5f * unit);
        lightning.lineTo(centerX + 0.4f * unit, centerY - 0.5f * unit);
        lightning.close();

        int color;
        if (lightOnDark || shownLevel >= 55) color = Color.WHITE;
        else if (shownLevel >= 0 && shownLevel <= 20) color = LOW_RED;
        else color = GREEN;
        glyphPaint.setColor(color);
        glyphPaint.setAlpha(Math.round(255f * chargeAlpha));
        glyphPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(lightning, glyphPaint);
        glyphPaint.setAlpha(255);
    }

    private void updateChargeAnimation() {
        if (charging != 1 || !ValueAnimator.areAnimatorsEnabled()
                || !isAttachedToWindow() || !isShown()) {
            cancelChargeAnimator();
            chargeAlpha = 1f;
            invalidate();
            return;
        }
        if (chargeAnimator != null && chargeAnimator.isRunning()) return;

        ValueAnimator animator = ValueAnimator.ofFloat(0.82f, 1f);
        chargeAnimator = animator;
        animator.setDuration(1000L);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(value -> {
            if (chargeAnimator != animator) return;
            chargeAlpha = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void cancelLevelAnimator() {
        ValueAnimator old = levelAnimator;
        levelAnimator = null;
        if (old != null) old.cancel();
    }

    private void cancelChargeAnimator() {
        ValueAnimator old = chargeAnimator;
        chargeAnimator = null;
        if (old != null) old.cancel();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
