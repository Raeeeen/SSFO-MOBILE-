package com.rod.dtrsystem;

import android.os.Bundle;
import android.util.Size;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.database.*;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.graphics.*;

public class ScanActivity extends AppCompatActivity {

    private PreviewView previewView;
    private ExecutorService cameraExecutor;
    private FaceDetector faceDetector;
    private FaceNetHelper faceNetHelper;
    private boolean isRecognizing = false;

    private Map<String, List<Float>> knownEmbeddings = new HashMap<>(); // Store name => embedding

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        previewView = findViewById(R.id.previewView);
        cameraExecutor = Executors.newSingleThreadExecutor();

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .enableTracking()
                .build();
        faceDetector = FaceDetection.getClient(options);

        faceNetHelper = new FaceNetHelper(this);

        loadEmbeddingsFromFirebase(); // Load stored embeddings first
    }

    private void loadEmbeddingsFromFirebase() {
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("embeddings");

        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                knownEmbeddings.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    String name = child.getKey();
                    List<Float> embedding = (List<Float>) child.getValue();
                    knownEmbeddings.put(name, embedding);
                }
                startCamera(); // Start camera after loading embeddings
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(ScanActivity.this, "Failed to load embeddings", Toast.LENGTH_SHORT).show();
            }
        });
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

            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImageProxy(ImageProxy image) {
        if (image == null || image.getImage() == null || isRecognizing) {
            image.close();
            return;
        }

        InputImage inputImage = InputImage.fromMediaImage(image.getImage(), image.getImageInfo().getRotationDegrees());

        faceDetector.process(inputImage)
                .addOnSuccessListener(faces -> {
                    if (!faces.isEmpty()) {
                        isRecognizing = true;
                        Face face = faces.get(0); // Take the first detected face

                        Bitmap faceBitmap = cropFaceFromImageProxy(image, face);
                        if (faceBitmap != null) {
                            float[] embedding = faceNetHelper.getFaceEmbedding(faceBitmap);
                            recognizeFace(embedding);
                        }
                    }
                })
                .addOnFailureListener(Throwable::printStackTrace)
                .addOnCompleteListener(task -> image.close());
    }

    private void recognizeFace(float[] embedding) {
        String bestMatch = null;
        float bestDistance = Float.MAX_VALUE;

        for (Map.Entry<String, List<Float>> entry : knownEmbeddings.entrySet()) {
            float distance = calculateDistance(embedding, entry.getValue());
            if (distance < bestDistance) {
                bestDistance = distance;
                bestMatch = entry.getKey();
            }
        }

        if (bestDistance < 0.6f) {
            String finalBestMatch = bestMatch;

            // Current PH time
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"));

            String yearMonth = String.format(Locale.getDefault(), "%04d-%02d",
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH) + 1);
            String day = String.format(Locale.getDefault(), "%02d", calendar.get(Calendar.DAY_OF_MONTH));

            int hour24 = calendar.get(Calendar.HOUR_OF_DAY);
            int hour = calendar.get(Calendar.HOUR);
            if (hour == 0) hour = 12;
            int minute = calendar.get(Calendar.MINUTE);
            String amPm = calendar.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM";
            String currentTime = String.format(Locale.getDefault(), "%02d:%02d %s", hour, minute, amPm);

            // Determine time slot group based on AM/PM
            List<String> order;
            if (calendar.get(Calendar.AM_PM) == Calendar.AM) {
                order = Arrays.asList("AM_IN", "AM_OUT");
            } else {
                order = Arrays.asList("PM_IN", "PM_OUT");
            }

            // Firebase ref
            DatabaseReference ref = FirebaseDatabase.getInstance().getReference("logs")
                    .child(finalBestMatch)
                    .child(yearMonth)
                    .child(day);

            ref.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    String recordedSlot = null;

                    for (String slot : order) {
                        if (!snapshot.hasChild(slot)) {
                            ref.child(slot).setValue(currentTime);
                            recordedSlot = slot;
                            break;
                        }
                    }

                    String promptMessage;
                    if (recordedSlot != null) {
                        String[] parts = recordedSlot.split("_");
                        String period = parts[0]; // AM or PM
                        String inOut = parts[1];  // IN or OUT

                        promptMessage = String.format(
                                "Recorded %s %s for %s at %s",
                                period, inOut, finalBestMatch, currentTime
                        );
                    } else {
                        promptMessage = String.format("All %s slots already recorded for today.", amPm);
                    }

                    // ✅ Show the message using AlertDialog instead of Toast
                    runOnUiThread(() -> {
                        new AlertDialog.Builder(ScanActivity.this)
                                .setTitle("Time Recorded")
                                .setMessage(promptMessage)
                                .setCancelable(false)
                                .setPositiveButton("OK", (dialog, which) -> {
                                    dialog.dismiss();
                                    finish(); // Exit ScanActivity after confirmation
                                })
                                .show();
                    });
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    runOnUiThread(() ->
                            new AlertDialog.Builder(ScanActivity.this)
                                    .setTitle("Error")
                                    .setMessage("Failed to record time. Please try again.")
                                    .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                                    .show()
                    );
                }
            });
        } else {
            runOnUiThread(() -> Toast.makeText(this, "No match found", Toast.LENGTH_SHORT).show());
            // Allow scanning again after a short delay
            previewView.postDelayed(() -> isRecognizing = false, 1000);
        }
    }

    private float calculateDistance(float[] emb1, List<Float> emb2) {
        float sum = 0f;
        for (int i = 0; i < emb1.length; i++) {
            float emb2Value = ((Number) emb2.get(i)).floatValue();
            float diff = emb1[i] - emb2Value;
            sum += diff * diff;
        }
        return (float) Math.sqrt(sum);
    }


    private Bitmap cropFaceFromImageProxy(ImageProxy image, Face face) {
        try {
            Bitmap bitmap = imageProxyToBitmap(image);
            if (bitmap == null) return null;

            RectF boundingBox = new RectF(face.getBoundingBox());
            boundingBox.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight());

            return Bitmap.createBitmap(bitmap,
                    (int) boundingBox.left,
                    (int) boundingBox.top,
                    (int) boundingBox.width(),
                    (int) boundingBox.height());
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
}
