package com.rod.dtrsystem;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import com.google.mlkit.vision.face.Face;

public class FaceGraphic extends GraphicOverlay.Graphic {

    private static final float BOX_STROKE_WIDTH = 8.0f;
    private final Paint boxPaint;
    private final Face face;
    private final boolean isFrontCamera;
    private final int previewWidth;
    private final int previewHeight;

    public FaceGraphic(GraphicOverlay overlay, Face face, boolean isFrontCamera, int previewWidth, int previewHeight) {
        super(overlay);
        this.face = face;
        this.isFrontCamera = isFrontCamera;
        this.previewWidth = previewWidth;
        this.previewHeight = previewHeight;

        boxPaint = new Paint();
        boxPaint.setColor(Color.GREEN);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(BOX_STROKE_WIDTH);
    }

    @Override
    public void draw(Canvas canvas) {
        if (face == null) {
            return;
        }

        RectF rect = new RectF(face.getBoundingBox());

        // Scale the bounding box from camera frame to overlay view size
        float scaleX = getOverlay().getWidth() / (float) previewWidth;
        float scaleY = getOverlay().getHeight() / (float) previewHeight;

        rect.left *= scaleX;
        rect.right *= scaleX;
        rect.top *= scaleY;
        rect.bottom *= scaleY;

        // Mirror horizontally if using front camera
        if (isFrontCamera) {
            float centerX = getOverlay().getWidth() / 2f;
            float mirroredLeft = centerX + (centerX - rect.right);
            float mirroredRight = centerX + (centerX - rect.left);
            rect.left = mirroredLeft;
            rect.right = mirroredRight;
        }

        // Draw the rectangle
        canvas.drawRect(rect, boxPaint);
    }
}
