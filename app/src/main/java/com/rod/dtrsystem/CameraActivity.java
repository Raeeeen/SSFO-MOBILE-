package com.rod.dtrsystem;

import android.Manifest;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.media.Image;
import android.os.Bundle;
import android.util.Size;
import android.widget.EditText;
import android.widget.Toast;
import android.app.AlertDialog;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashMap;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.util.Log;
import android.graphics.BitmapFactory;

public class CameraActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private PreviewView previewView;
    private GraphicOverlay graphicOverlay;
    private ExecutorService cameraExecutor;
    private FaceDetector faceDetector;
    private FaceNetHelper faceNetHelper;
    private boolean isPromptShowing = false;
    private boolean isEmbeddingsLoaded = false;
    private DatabaseReference databaseReference;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        previewView = findViewById(R.id.previewView);
        graphicOverlay = findViewById(R.id.graphicOverlay);

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        FaceDetectorOptions options =
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .enableTracking()
                        .build();

        faceDetector = FaceDetection.getClient(options);

        faceNetHelper = new FaceNetHelper(this);

        databaseReference = FirebaseDatabase.getInstance().getReference("embeddings");

        loadEmbeddingsFromFirebase(); // Load known embeddings on startup
    }

    private void loadEmbeddingsFromFirebase() {
        FaceNetHelper.embeddings = new HashMap<>();
        databaseReference.get().addOnSuccessListener(snapshot -> {
            for (DataSnapshot child : snapshot.getChildren()) {
                String name = child.getKey();
                List<Double> doubleEmbedding = (List<Double>) child.getValue();
                if (doubleEmbedding != null) {
                    float[] embeddingArray = toPrimitiveArray(convertDoubleListToFloat(doubleEmbedding));
                    float[] normalized = normalizeEmbedding(embeddingArray);
                    FaceNetHelper.embeddings.put(name, toList(normalized));
                }
            }
            isEmbeddingsLoaded = true; // ✅ Mark as loaded after success
        }).addOnFailureListener(e -> {
            Toast.makeText(this, "Failed to load embeddings", Toast.LENGTH_SHORT).show();
        });
    }

    private List<Float> convertDoubleListToFloat(List<Double> doubleList) {
        Float[] floatArray = new Float[doubleList.size()];
        for (int i = 0; i < doubleList.size(); i++) {
            floatArray[i] = doubleList.get(i).floatValue();
        }
        return Arrays.asList(floatArray);
    }

    private float[] toPrimitiveArray(List<Float> list) {
        float[] array = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImageProxy(ImageProxy image) {
        if (!isEmbeddingsLoaded) {
            image.close();
            return; // ❌ Skip processing if embeddings not yet loaded
        }

        if (image == null || image.getImage() == null) {
            return;
        }

        InputImage inputImage = InputImage.fromMediaImage(image.getImage(), image.getImageInfo().getRotationDegrees());

        faceDetector.process(inputImage)
                .addOnSuccessListener(faces -> {
                    graphicOverlay.clear();
                    for (Face face : faces) {
                        graphicOverlay.add(new FaceGraphic(
                                graphicOverlay,
                                face,
                                true,
                                image.getWidth(),
                                image.getHeight()
                        ));

                        Bitmap faceBitmap = cropFaceFromImageProxy(image, face);
                        if (faceBitmap != null && !isPromptShowing) {
                            Bitmap normalizedFace = normalizeLighting(faceBitmap);  // 🔥 Preprocess lighting
                            float[] rawEmbedding = faceNetHelper.getFaceEmbedding(normalizedFace);
                            Log.d("FaceEmbedding", "Normalized Embedding: " + Arrays.toString(rawEmbedding));


                            isPromptShowing = true;
                            runOnUiThread(() -> {
                                String matchedName = findSimilarEmbedding(rawEmbedding);
                                if (matchedName != null) {
                                    showAlreadyRegisteredDialogAndExit(matchedName);
                                } else {
                                    promptNameAndSaveEmbedding(rawEmbedding);
                                }
                            });
                        }
                    }
                    graphicOverlay.postInvalidate();
                })
                .addOnFailureListener(Throwable::printStackTrace)
                .addOnCompleteListener(task -> image.close());
    }

    private String findSimilarEmbedding(float[] newEmbedding) {
        if (FaceNetHelper.embeddings == null) return null;
        for (String key : FaceNetHelper.embeddings.keySet()) {
            List<Float> known = FaceNetHelper.embeddings.get(key);
            if (known == null) continue;

            float distance = calculateDistance(newEmbedding, known);
            if (distance < 0.6f) { // Threshold
                return key;
            }
        }
        return null;
    }

    private float calculateDistance(float[] emb1, List<Float> emb2) {
        float sum = 0f;
        for (int i = 0; i < emb1.length; i++) {
            float diff = emb1[i] - emb2.get(i);
            sum += diff * diff;
        }
        return (float) Math.sqrt(sum);
    }

    private Bitmap normalizeLighting(Bitmap bitmap) {
        // Convert to grayscale
        Bitmap grayscale = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int pixel = bitmap.getPixel(x, y);
                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                int gray = (r + g + b) / 3;
                int grayPixel = 0xff000000 | (gray << 16) | (gray << 8) | gray;
                grayscale.setPixel(x, y, grayPixel);
            }
        }

        // Histogram Equalization
        int[] histogram = new int[256];
        int totalPixels = grayscale.getWidth() * grayscale.getHeight();

        for (int y = 0; y < grayscale.getHeight(); y++) {
            for (int x = 0; x < grayscale.getWidth(); x++) {
                int pixel = grayscale.getPixel(x, y) & 0xff;
                histogram[pixel]++;
            }
        }

        int[] cdf = new int[256];
        cdf[0] = histogram[0];
        for (int i = 1; i < 256; i++) {
            cdf[i] = cdf[i - 1] + histogram[i];
        }

        Bitmap equalized = Bitmap.createBitmap(grayscale.getWidth(), grayscale.getHeight(), Bitmap.Config.ARGB_8888);
        for (int y = 0; y < grayscale.getHeight(); y++) {
            for (int x = 0; x < grayscale.getWidth(); x++) {
                int pixel = grayscale.getPixel(x, y) & 0xff;
                int newVal = (cdf[pixel] - cdf[0]) * 255 / (totalPixels - cdf[0]);
                int newPixel = 0xff000000 | (newVal << 16) | (newVal << 8) | newVal;
                equalized.setPixel(x, y, newPixel);
            }
        }

        // Optional: Apply gamma correction to enhance brightness consistency
        float gamma = 1.2f;
        Bitmap gammaCorrected = Bitmap.createBitmap(equalized.getWidth(), equalized.getHeight(), Bitmap.Config.ARGB_8888);
        for (int y = 0; y < equalized.getHeight(); y++) {
            for (int x = 0; x < equalized.getWidth(); x++) {
                int pixel = equalized.getPixel(x, y) & 0xff;
                int corrected = (int) (255 * Math.pow((pixel / 255.0), 1.0 / gamma));
                int newPixel = 0xff000000 | (corrected << 16) | (corrected << 8) | corrected;
                gammaCorrected.setPixel(x, y, newPixel);
            }
        }

        return gammaCorrected;
    }


    private float[] normalizeEmbedding(float[] embedding) {
        float norm = 0f;
        for (float v : embedding) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] /= norm;
        }
        return embedding;
    }

    private void showAlreadyRegisteredDialogAndExit(String name) {
        new AlertDialog.Builder(this)
                .setTitle("Already Registered")
                .setMessage("This face is already registered as " + name + ".")
                .setCancelable(false)
                .setPositiveButton("OK", (dialog, which) -> {
                    dialog.dismiss();
                    isPromptShowing = false;
                    finish(); // Go back to MainActivity
                })
                .show();
    }

    private void promptNameAndSaveEmbedding(float[] embedding) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Full Name");

        final EditText input = new EditText(this);
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                saveEmbeddingToFirebase(name, embedding);
            } else {
                Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show();
                isPromptShowing = false;
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            dialog.cancel();
            isPromptShowing = false;
        });

        builder.show();
    }

    private void saveEmbeddingToFirebase(String name, float[] embedding) {
        databaseReference.child(name).setValue(toList(embedding))
                .addOnSuccessListener(aVoid -> {
                    FaceNetHelper.embeddings.put(name, toList(embedding));
                    Toast.makeText(this, "Saved to Firebase", Toast.LENGTH_SHORT).show();
                    finish(); // After saving, go back to MainActivity
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to save", Toast.LENGTH_SHORT).show();
                    isPromptShowing = false;
                });
    }

    private List<Float> toList(float[] embedding) {
        Float[] boxedArray = new Float[embedding.length];
        for (int i = 0; i < embedding.length; i++) {
            boxedArray[i] = embedding[i];
        }
        return Arrays.asList(boxedArray);
    }

    private Bitmap cropFaceFromImageProxy(ImageProxy image, Face face) {
        try {
            Bitmap bitmap = imageProxyToBitmap(image);
            if (bitmap == null) return null;

            RectF boundingBox = new RectF(face.getBoundingBox());
            boundingBox.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight());

            int padding = 20;
            int left = Math.max((int) boundingBox.left - padding, 0);
            int top = Math.max((int) boundingBox.top - padding, 0);
            int width = Math.min((int) boundingBox.width() + 2 * padding, bitmap.getWidth() - left);
            int height = Math.min((int) boundingBox.height() + 2 * padding, bitmap.getHeight() - top);

            return Bitmap.createBitmap(bitmap, left, top, width, height);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private Bitmap imageProxyToBitmap(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes.length < 3) return null;

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(
                nv21,
                ImageFormat.NV21,
                image.getWidth(),
                image.getHeight(),
                null
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 100, out);
        byte[] imageBytes = out.toByteArray();

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}
