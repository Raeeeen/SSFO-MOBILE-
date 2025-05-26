package com.rod.dtrsystem;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FaceNetHelper {

    private Interpreter tflite;
    public static Map<String, List<Float>> embeddings = new HashMap<>();

    public FaceNetHelper(Context context) {
        try {
            tflite = new Interpreter(loadModelFile(context, "facenet.tflite"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private MappedByteBuffer loadModelFile(Context context, String modelFile) throws IOException {
        FileInputStream fileInputStream = new FileInputStream(context.getAssets().openFd(modelFile).getFileDescriptor());
        FileChannel fileChannel = fileInputStream.getChannel();
        long startOffset = context.getAssets().openFd(modelFile).getStartOffset();
        long declaredLength = context.getAssets().openFd(modelFile).getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    public float[] getFaceEmbedding(Bitmap bitmap) {
        // 1. Resize bitmap to 160x160
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, 160, 160, true);

        // 2. Normalize the bitmap into a ByteBuffer
        ByteBuffer imgData = ByteBuffer.allocateDirect(1 * 160 * 160 * 3 * 4); // float size = 4 bytes
        imgData.order(ByteOrder.nativeOrder());
        imgData.rewind();

        int[] intValues = new int[160 * 160];
        resized.getPixels(intValues, 0, 160, 0, 0, 160, 160);

        for (int pixelValue : intValues) {
            float r = ((pixelValue >> 16) & 0xFF) / 255.0f;
            float g = ((pixelValue >> 8) & 0xFF) / 255.0f;
            float b = (pixelValue & 0xFF) / 255.0f;

            imgData.putFloat(r);
            imgData.putFloat(g);
            imgData.putFloat(b);
        }

        // 3. Correct output buffer for 512-dimensional embeddings
        float[][] embeddings = new float[1][512];
        tflite.run(imgData, embeddings);

        return embeddings[0]; // returns a float[512]
    }

}
