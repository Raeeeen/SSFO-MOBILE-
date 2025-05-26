package com.rod.dtrsystem;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class GraphicOverlay extends View {

    private final List<Graphic> graphics = new ArrayList<>();

    public GraphicOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    // 🛠 FIX: Add getOverlay() here
    public static abstract class Graphic {
        private final GraphicOverlay overlay;

        public Graphic(GraphicOverlay overlay) {
            this.overlay = overlay;
        }

        // ✅ ADD THIS METHOD
        public GraphicOverlay getOverlay() {
            return overlay;
        }

        public abstract void draw(Canvas canvas);
    }

    public void clear() {
        graphics.clear();
        postInvalidate();
    }

    public void add(Graphic graphic) {
        graphics.add(graphic);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Graphic graphic : graphics) {
            graphic.draw(canvas);
        }
    }
}
