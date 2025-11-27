package com.example.test922.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class WaveformView extends View {

    private Paint axisPaint;
    private Paint wavePaint;
    private Paint textPaint;
    private float[] waveform;
    private long durationMs; // Total duration of the original audio in milliseconds

    private int paddingLeft = 100;
    private int paddingTop = 50;
    private int paddingRight = 50;
    private int paddingBottom = 50;

    private final Rect textBounds = new Rect();

    public WaveformView(Context context) {
        super(context);
        init();
    }

    public WaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WaveformView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        wavePaint = new Paint();
        wavePaint.setColor(Color.BLUE);
        wavePaint.setStrokeWidth(2f);
        wavePaint.setAntiAlias(true);

        axisPaint = new Paint();
        axisPaint.setColor(Color.GRAY);
        axisPaint.setStrokeWidth(2f);

        textPaint = new Paint();
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(25f);
        textPaint.setAntiAlias(true);
    }

    public void setWaveform(float[] waveform, long durationMs) {
        this.waveform = waveform;
        this.durationMs = durationMs;
        invalidate(); // Request a redraw
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();

        // Define drawing area
        int graphWidth = width - paddingLeft - paddingRight;
        int graphHeight = height - paddingTop - paddingBottom;
        int centerY = paddingTop + graphHeight / 2;

        // Draw Axes
        // Y-axis
        canvas.drawLine(paddingLeft, paddingTop, paddingLeft, paddingTop + graphHeight, axisPaint);
        // X-axis (center line)
        canvas.drawLine(paddingLeft, centerY, paddingLeft + graphWidth, centerY, axisPaint);

        // Draw Y-axis labels (Amplitude)
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("1.0", paddingLeft - 10, paddingTop + textPaint.getTextSize() / 2, textPaint);
        canvas.drawText("0.0", paddingLeft - 10, centerY + textPaint.getTextSize() / 2, textPaint);
        canvas.drawText("-1.0", paddingLeft - 10, paddingTop + graphHeight, textPaint);

        // Draw X-axis labels (Time)
        if (durationMs > 0) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            int labelCount = 5; // Number of time labels
            for (int i = 0; i <= labelCount; i++) {
                float x = paddingLeft + (i * (float) graphWidth / labelCount);
                float timeSec = (i * (float) durationMs / 1000) / labelCount;
                canvas.drawText(String.format("%.2fs", timeSec), x, height - paddingBottom + 30, textPaint);
            }
        }

        if (waveform == null) {
            return;
        }

        // Draw waveform
        float xScale = (float) graphWidth / (waveform.length - 1);
        float graphCenterY = paddingTop + (float) graphHeight / 2;

        for (int i = 0; i < waveform.length - 1; i++) {
            float startX = paddingLeft + i * xScale;
            float startY = graphCenterY - waveform[i] * (graphHeight / 2f);
            float stopX = paddingLeft + (i + 1) * xScale;
            float stopY = graphCenterY - waveform[i + 1] * (graphHeight / 2f);
            canvas.drawLine(startX, startY, stopX, stopY, wavePaint);
        }
    }
}
