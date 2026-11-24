package com.lig7oo7.bookscan;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaActionSound;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends ComponentActivity implements MotionAnalyzer.Listener {

    private static final String AUTHORITY = "com.lig7oo7.bookscan.files";
    private static final int MAX_RETRY = 2;

    private Session session;
    private PreviewView previewView;
    private CropOverlay cropOverlay;
    private View flash;
    private TextView statusText, resultText, countText, modeBtn, pauseBtn, torchBtn, cropBtn;
    private ImageView thumb;

    private Camera camera;
    private ImageCapture imageCapture;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService analysisExec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaActionSound shutterSound;
    private ToneGenerator tone;
    private Ocr ocr;                    // io 스레드에서만 사용
    private MotionAnalyzer analyzer;
    private TextTracker tracker;

    // 분석 스레드와 같이 쓰는 상태
    private volatile boolean busy, paused, torch;
    private volatile int mode, stableMs, dupT;
    private volatile float motionT, stableT;
    private volatile int[] lastSig, lastCapSig;
    private volatile boolean needMotion;
    private int motionFrames;
    private long stableSince;
    private String lastStatus = "";
    private int retries;

    private List<File> lastGroup;
    private File pendingCleanupPdf;

    private ActivityResultLauncher<String> permLauncher;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        session = new Session(this);
        shutterSound = new MediaActionSound();
        shutterSound.load(MediaActionSound.SHUTTER_CLICK);
        try { tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90); } catch (Exception ignored) { }
        buildUi();

        permLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) startCamera();
            else setStatus("카메라 권한이 필요해요. 설정에서 허용해주세요.");
        });
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPrefs();
        refreshCount();
        main.removeCallbacks(intervalTick);
        main.postDelayed(intervalTick, Prefs.intervalSec(this) * 1000L);
        if (pendingCleanupPdf != null) {
            File pdf = pendingCleanupPdf;
            pendingCleanupPdf = null;
            main.postDelayed(() -> askCleanupAfterShare(pdf), 400);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(intervalTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        shutterSound.release();
        if (tone != null) tone.release();
        io.execute(() -> { if (ocr != null) ocr.close(); });
        if (tracker != null) tracker.close();
        io.shutdown();
        analysisExec.shutdown();
    }

    private void loadPrefs() {
        mode = Prefs.mode(this);
        stableMs = Prefs.stableMs(this);
        dupT = Prefs.dupThreshold(this);
        int sens = Math.max(1, Math.min(10, Prefs.sensitivity(this)));
        motionT = 4f + (10 - sens) * 1.2f;          // 민감도 5 → 10
        stableT = Math.max(2.5f, motionT * 0.4f);    // 민감도 5 → 4
        modeBtn.setText(modeLabel());
        if (!cropOverlay.isEditing()) cropOverlay.setQuad(Prefs.crop(this));
        applyCropMode();
        needMotion = false;
        stableSince = 0;
        motionFrames = 0;
    }

    private String modeLabel() {
        switch (mode) {
            case Prefs.MODE_INTERVAL: return "⏱ " + Prefs.intervalSec(this) + "초";
            case Prefs.MODE_MANUAL: return "✋ 수동";
            default: return "🅰 자동";
        }
    }

    // ───────────────────────── 화면 ─────────────────────────

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView btn(String text, int bg) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(6), dp(10), dp(6), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(22));
        t.setBackground(g);
        t.setClickable(true);
        return t;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);   // 실제로 찍히는 범위 그대로 보이게
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        cropOverlay = new CropOverlay(this);
        root.addView(cropOverlay, new FrameLayout.LayoutParams(-1, -1));

        flash = new View(this);
        flash.setBackgroundColor(Color.WHITE);
        flash.setAlpha(0f);
        root.addView(flash, new FrameLayout.LayoutParams(-1, -1));

        // 위쪽: 상태, 결과, 쪽수, 설정
        LinearLayout topCol = new LinearLayout(this);
        topCol.setOrientation(LinearLayout.VERTICAL);
        topCol.setBackgroundColor(0x99000000);
        topCol.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(15);
        top.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1f));
        countText = new TextView(this);
        countText.setTextColor(0xFFFFD54F);
        countText.setTextSize(20);
        countText.setPadding(dp(8), 0, dp(12), 0);
        top.addView(countText);
        TextView settings = btn("⚙", 0x55FFFFFF);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        top.addView(settings, new LinearLayout.LayoutParams(dp(48), -2));
        topCol.addView(top);
        resultText = new TextView(this);
        resultText.setTextSize(14);
        resultText.setTextColor(0xFFB2DFDB);
        resultText.setPadding(0, dp(4), 0, 0);
        topCol.addView(resultText);
        root.addView(topCol, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // 아래쪽
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xAA000000);
        bottom.setPadding(dp(8), dp(10), dp(8), dp(16));

        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER);
        modeBtn = btn("", 0xFF2E7D6B);
        modeBtn.setOnClickListener(v -> {
            Prefs.setMode(this, (mode + 1) % 3);
            loadPrefs();
            Toast.makeText(this, modeHelp(), Toast.LENGTH_SHORT).show();
        });
        pauseBtn = btn("⏸ 멈춤", 0x55FFFFFF);
        pauseBtn.setOnClickListener(v -> setPaused(!paused));
        cropBtn = btn("✂ 범위", 0x55FFFFFF);
        cropBtn.setOnClickListener(v -> cropMenu());
        torchBtn = btn("💡", 0x55FFFFFF);
        torchBtn.setOnClickListener(v -> {
            torch = !torch;
            if (camera != null) camera.getCameraControl().enableTorch(torch);
            torchBtn.setText(torch ? "💡끄기" : "💡");
        });
        row1.addView(modeBtn, lp(1.2f));
        row1.addView(pauseBtn, lp(1f));
        row1.addView(cropBtn, lp(1.1f));
        row1.addView(torchBtn, lp(0.7f));
        bottom.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.setPadding(0, dp(12), 0, 0);

        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(0x33FFFFFF);
        thumb.setOnClickListener(v -> showLast());
        row2.addView(thumb, new LinearLayout.LayoutParams(dp(56), dp(72)));

        TextView undo = btn("↶ 되돌리기", 0x55FFFFFF);
        undo.setOnClickListener(v -> undo());
        row2.addView(undo, lp(1f));

        TextView shutter = new TextView(this);
        GradientDrawable sg = new GradientDrawable();
        sg.setShape(GradientDrawable.OVAL);
        sg.setColor(Color.WHITE);
        sg.setStroke(dp(5), 0xFF2E7D6B);
        shutter.setBackground(sg);
        shutter.setClickable(true);
        shutter.setOnClickListener(v -> capture(false));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(76), dp(76));
        slp.setMargins(dp(8), 0, dp(8), 0);
        row2.addView(shutter, slp);

        TextView done = btn("✔ 완료", 0xFFE65100);
        done.setOnClickListener(v -> finishBook());
        row2.addView(done, lp(1f));
        bottom.addView(row2);

        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        previewView.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP && camera != null) {
                MeteringPoint pt = previewView.getMeteringPointFactory().createPoint(e.getX(), e.getY());
                camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(pt).build());
                v.performClick();
            }
            return true;
        });
    }

    private LinearLayout.LayoutParams lp(float w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private String modeHelp() {
        switch (mode) {
            case Prefs.MODE_INTERVAL: return "간격 모드: 정해진 초마다 찍어요 (같은 쪽은 건너뜀)";
            case Prefs.MODE_MANUAL: return "수동 모드: 셔터나 볼륨키로 찍어요";
            default: return "자동 모드: 책장을 넘기고 손을 떼면 찍어요";
        }
    }

    private void applyCropMode() {
        int cm = Prefs.cropMode(this);
        if (cm == Prefs.CROP_AUTO) {
            if (tracker == null) {
                tracker = new TextTracker((q, lines) -> main.post(() -> cropOverlay.setLive(q)));
            }
            tracker.setMargin(Prefs.marginPct(this));
            if (analyzer != null) analyzer.setTracker(tracker);
            cropOverlay.setShowFixed(false);
            cropBtn.setText("▣ 자동테두리");
        } else {
            if (analyzer != null) analyzer.setTracker(null);
            if (tracker != null) tracker.reset();
            cropOverlay.setLive(null);
            cropOverlay.setShowFixed(cm == Prefs.CROP_FIXED);
            cropBtn.setText(cm == Prefs.CROP_FIXED ? "✂ 고정범위" : "□ 전체");
        }
    }

    private void cropMenu() {
        if (cropOverlay.isEditing()) {
            toggleCropEdit();
            return;
        }
        String[] items = {
                "▣ 자동 테두리 — 글자를 따라 테두리가 움직여요 (추천)",
                "✂ 고정 범위 — 거치대에 고정했을 때, 모서리 직접 맞추기",
                "□ 자르지 않음 — 화면 전체 저장"};
        new AlertDialog.Builder(this)
                .setTitle("자르기 방식")
                .setItems(items, (d, which) -> {
                    Prefs.setCropMode(this, which);
                    loadPrefs();
                    if (which == Prefs.CROP_FIXED) toggleCropEdit();
                })
                .show();
    }

    private void toggleCropEdit() {
        if (!cropOverlay.isEditing()) {
            cropOverlay.setEditing(true);
            setPaused(true);
            cropBtn.setText("✔ 범위 저장");
            setStatus("노란 점 4개를 책(펼친 면) 모서리에 맞추세요");
            setResult("다 맞췄으면 '✔ 범위 저장'을 누르세요", 0xFFB2DFDB);
        } else {
            cropOverlay.setEditing(false);
            Prefs.setCrop(this, cropOverlay.getQuad());
            loadPrefs();
            setPaused(false);
            setResult("범위 저장 — 이제 이 범위만 잘라 반듯하게 펴서 저장해요", 0xFFB2DFDB);
            return;
        }
    }

    private void setPaused(boolean p) {
        paused = p;
        pauseBtn.setText(p ? "▶ 재개" : "⏸ 멈춤");
        stableSince = 0;
        if (p) setStatus("일시정지");
    }

    private void setStatus(String s) {
        if (s.equals(lastStatus)) return;
        lastStatus = s;
        if (Looper.myLooper() == Looper.getMainLooper()) statusText.setText(s);
        else main.post(() -> statusText.setText(s));
    }

    private void setResult(String s, int color) {
        main.post(() -> {
            resultText.setText(s);
            resultText.setTextColor(color);
        });
    }

    private void refreshCount() {
        countText.setText(session.count() + "쪽");
        File last = session.last();
        if (last == null) {
            thumb.setImageBitmap(null);
            return;
        }
        io.execute(() -> {
            Bitmap bm = Processor.thumb(last, 200);
            main.post(() -> thumb.setImageBitmap(bm));
        });
    }

    private void showLast() {
        File last = session.last();
        if (last == null) return;
        io.execute(() -> {
            Bitmap bm0 = Processor.thumb(last, 1400);
            String text = readText(Session.textOf(last));
            List<OcrLine> lines = OcrLine.read(Session.ocrOf(last));
            Bitmap bm = bm0 == null ? null : drawBoxes(bm0, lines, Processor.size(last));
            main.post(() -> {
                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                ImageView iv = new ImageView(this);
                iv.setAdjustViewBounds(true);
                iv.setImageBitmap(bm);
                col.addView(iv);
                if (!text.isEmpty()) {
                    TextView tv = new TextView(this);
                    tv.setPadding(dp(16), dp(12), dp(16), dp(12));
                    tv.setTextSize(13);
                    tv.setTextIsSelectable(true);
                    tv.setText("초록 상자 = 글자로 인식된 줄 (" + lines.size() + "줄)\n\n"
                            + (text.length() > 1500 ? text.substring(0, 1500) + "…" : text));
                    col.addView(tv);
                }
                android.widget.ScrollView sv = new android.widget.ScrollView(this);
                sv.addView(col);
                new AlertDialog.Builder(this)
                        .setTitle("마지막 쪽 (" + session.count() + ")")
                        .setView(sv)
                        .setPositiveButton("닫기", null)
                        .setNegativeButton("지우고 다시 찍기", (d, w) -> undo())
                        .show();
            });
        });
    }

    /** 쪽 사진 위에 인식된 줄마다 초록 상자 — 어디가 읽혔고 어디가 빠졌는지 한눈에 */
    private static Bitmap drawBoxes(Bitmap src, List<OcrLine> lines, int[] full) {
        if (lines.isEmpty() || full[0] <= 0) return src;
        Bitmap b = src.isMutable() ? src : src.copy(Bitmap.Config.ARGB_8888, true);
        if (b != src) src.recycle();
        float sc = (float) b.getWidth() / full[0];
        Canvas c = new Canvas(b);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0x3300E676);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1.5f, b.getWidth() / 500f));
        edge.setColor(0xFF00C853);
        for (OcrLine l : lines) {
            Path p = new Path();
            p.moveTo(l.pts[0] * sc, l.pts[1] * sc);
            for (int i = 1; i < 4; i++) p.lineTo(l.pts[2 * i] * sc, l.pts[2 * i + 1] * sc);
            p.close();
            c.drawPath(p, fill);
            c.drawPath(p, edge);
        }
        return b;
    }

    private static String readText(File f) {
        if (!f.exists()) return "";
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024)];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8") : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ───────────────────────── 카메라 ─────────────────────────

    @SuppressWarnings("deprecation")
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                ProcessCameraProvider provider = f.get();
                provider.unbindAll();

                // 미리보기와 촬영을 같은 4:3으로 맞춰야 범위 표시가 정확하다
                Preview preview = new Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)   // 글자 선명도 우선
                        .setJpegQuality(95)
                        .build();

                // 글자 줄을 찾으려면 너무 작으면 안 됨 → 1280x960 (4:3)
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(960, 1280))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analyzer = new MotionAnalyzer(this);
                analysis.setAnalyzer(analysisExec, analyzer);
                applyCropMode();

                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture, analysis);
                if (torch) camera.getCameraControl().enableTorch(true);
                setStatus(modeHelp());
                if (Prefs.cropMode(this) == Prefs.CROP_AUTO)
                    setResult("초록 테두리가 글자를 감싸면 준비 완료", 0xFFB2DFDB);
            } catch (Exception e) {
                setStatus("카메라를 열지 못했어요: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** 분석 스레드에서 프레임마다 불림 — 자동 모드의 판단 */
    @Override
    public void onFrame(float diff, int[] sig) {
        lastSig = sig;
        if (diff < 0 || mode != Prefs.MODE_AUTO) {
            if (mode == Prefs.MODE_MANUAL && !paused && !busy) setStatus("셔터·볼륨키로 찍기");
            return;
        }
        if (paused) return;
        if (busy) {            // 상태 글은 촬영 단계(초점·촬영·글자 찾기)가 직접 띄움
            stableSince = 0;
            return;
        }
        long now = SystemClock.elapsedRealtime();

        if (needMotion) {
            if (diff > motionT) {
                if (++motionFrames >= 2) {        // 두 프레임 이상 움직여야 '넘겼다'로 인정
                    needMotion = false;
                    stableSince = 0;
                }
            } else {
                motionFrames = 0;
            }
            setStatus("📖 다음 장으로 넘기세요");
            return;
        }

        if (diff < stableT) {
            if (stableSince == 0) stableSince = now;
            long held = now - stableSince;
            if (held >= stableMs) {
                needMotion = true;
                motionFrames = 0;
                stableSince = 0;
                main.post(() -> capture(true));
            } else {
                setStatus(String.format(Locale.KOREA, "⏳ 멈춤 확인 %.1f초", (stableMs - held) / 1000f));
            }
        } else {
            stableSince = 0;
            setStatus("✋ 움직이는 중");
        }
    }

    private final Runnable intervalTick = new Runnable() {
        @Override
        public void run() {
            if (mode == Prefs.MODE_INTERVAL && !paused) {
                capture(true);
                setStatus("⏱ " + Prefs.intervalSec(MainActivity.this) + "초마다 촬영 중");
            }
            main.postDelayed(this, Prefs.intervalSec(MainActivity.this) * 1000L);
        }
    };

    /** 메인 스레드에서 호출. dupCheck=true면 직전에 찍은 쪽과 같으면 건너뜀 */
    private void capture(boolean dupCheck) {
        if (busy || imageCapture == null || cropOverlay.isEditing()) return;
        int[] sig = lastSig;
        if (dupCheck && dupT > 0 && sig != null && lastCapSig != null
                && MotionAnalyzer.meanAbsDiff(sig, lastCapSig, true) < dupT) {
            setStatus("같은 쪽이라 건너뜀 — 넘겨주세요");
            return;
        }
        busy = true;
        lastCapSig = sig;
        final boolean manual = !dupCheck;
        final float[] quad = Prefs.cropMode(this) == Prefs.CROP_AUTO && tracker != null ? tracker.fresh(3000) : null;
        if (analyzer != null) analyzer.setTracker(null);      // 찍고 처리하는 동안 미리보기 글자 찾기 쉬기 (CPU 양보)
        setStatus("🎯 초점 맞추는 중…");
        // 자동 촬영은 손을 뗀 직후라 초점이 안 맞았을 수 있다 → 가운데에 초점·노출을 다시 맞춘 뒤 찍는다
        focusThen(() -> shoot(manual, quad));
    }

    /** 초점 맞추기가 끝나면(또는 1.5초가 지나면) next 실행 — 메인 스레드 */
    private void focusThen(Runnable next) {
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable go = () -> { if (once.compareAndSet(false, true)) next.run(); };
        main.postDelayed(go, 1500);
        try {
            MeteringPoint pt = new SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f);
            FocusMeteringAction a = new FocusMeteringAction.Builder(pt,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build();
            camera.getCameraControl().startFocusAndMetering(a)
                    .addListener(go, ContextCompat.getMainExecutor(this));
        } catch (Exception e) {
            go.run();
        }
    }

    private void shoot(boolean manual, float[] quad) {
        if (imageCapture == null) { busy = false; resumeTracker(); return; }
        setStatus("📸 찍는 중…");
        flash.setAlpha(0.8f);
        flash.animate().alpha(0f).setDuration(250).start();
        if (Prefs.sound(this)) shutterSound.play(MediaActionSound.SHUTTER_CLICK);

        imageCapture.takePicture(io, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                boolean closed = false;
                try {
                    ByteBuffer buf = image.getPlanes()[0].getBuffer();
                    buf.rewind();
                    byte[] jpeg = new byte[buf.remaining()];
                    buf.get(jpeg);
                    int rot = image.getImageInfo().getRotationDegrees();
                    image.close();
                    closed = true;
                    Ocr o = null;
                    if (Prefs.ocrCheck(MainActivity.this)) {
                        if (ocr == null) ocr = new Ocr();
                        o = ocr;
                    }
                    setStatus("🔎 테두리·글자 찾는 중…");
                    Processor.Result r = Processor.process(MainActivity.this, session, jpeg, rot, quad, o);
                    judge(r, manual);
                } catch (Throwable t) {
                    if (!closed) image.close();
                    toast("저장 실패: " + t.getMessage());
                } finally {
                    busy = false;
                    main.post(MainActivity.this::resumeTracker);
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException e) {
                busy = false;
                main.post(MainActivity.this::resumeTracker);
                toast("촬영 실패: " + e.getMessage());
            }
        });
    }

    private void resumeTracker() {
        if (analyzer != null && tracker != null && Prefs.cropMode(this) == Prefs.CROP_AUTO) analyzer.setTracker(tracker);
    }

    /** io 스레드: 글자 인식 결과로 통과/재촬영 판단 */
    private void judge(Processor.Result r, boolean manual) {
        int min = Prefs.ocrMin(this);
        String pages = (r.files.size() == 2 ? "2쪽" : "1쪽") + (r.autoMissed ? "(테두리 못 찾음)" : "");
        String peek = "";
        if (r.firstText != null && !r.firstText.isEmpty()) {
            String one = r.firstText.replace('\n', ' ').trim();
            peek = "\n“" + (one.length() > 40 ? one.substring(0, 40) + "…" : one) + "”";
        }
        if (!r.ocrRan) {
            retries = 0;
            setResult("✓ " + pages + " 저장 (글자 확인 꺼짐)", 0xFFB2DFDB);
        } else if (r.totalChars < Processor.MIN_CHARS) {
            retries = 0;
            setResult("✓ " + pages + " 저장 · 글자 거의 없음(" + r.totalChars + "자) — 그림·빈 쪽이 아니면 확인하세요", 0xFFFFD54F);
        } else if (r.worstConf < 0) {
            retries = 0;
            setResult("✓ " + pages + " 저장 · 글자 " + r.totalChars + "자 · " + r.lines + "줄 인식" + peek, 0xFF80CBC4);
        } else {
            int pct = Math.round(r.worstConf * 100);
            if (pct >= min) {
                retries = 0;
                setResult("✓ " + pages + " 저장 · 읽힘 " + pct + "% · " + r.totalChars + "자 · " + r.lines + "줄" + peek, 0xFF80CBC4);
            } else if (!manual && retries < MAX_RETRY) {
                retries++;
                r.deleteAll();
                beep();
                // 같은 쪽을 다시 찍도록: 중복판정·넘김대기 풀기
                main.post(() -> {
                    lastCapSig = null;
                    needMotion = false;
                    stableSince = 0;
                    refreshCount();
                });
                setResult("⚠ 흐림 (" + pct + "%) — 그대로 두면 다시 찍어요 " + retries + "/" + MAX_RETRY, 0xFFFF8A65);
                return;
            } else {
                retries = 0;
                beep();
                setResult("⚠ " + pages + " 저장했지만 잘 안 읽혀요 (" + pct + "%) — 썸네일로 확인하세요", 0xFFFF8A65);
            }
        }
        List<File> saved = r.files;
        main.post(() -> {
            lastGroup = saved;
            refreshCount();
        });
    }

    private void beep() {
        if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_NACK, 300);
    }

    private void undo() {
        if (busy) return;
        List<File> g = lastGroup;
        if (g == null || g.isEmpty()) {
            File l = session.last();
            if (l == null) return;
            g = Collections.singletonList(l);
        }
        int n = 0;
        for (File f : g) if (Session.delete(f)) n++;
        lastGroup = null;
        lastCapSig = null;
        needMotion = false;      // 같은 쪽을 바로 다시 찍을 수 있게
        stableSince = 0;
        refreshCount();
        toast("마지막 " + n + "쪽 지웠어요");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getRepeatCount() == 0) capture(false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true;
        return super.onKeyUp(keyCode, event);
    }

    private void toast(String s) {
        main.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    // ───────────────────────── 완료: PDF + 글자 → 드라이브 ─────────────────────────

    private void finishBook() {
        int n = session.count();
        if (n == 0) {
            toast("아직 찍은 쪽이 없어요");
            return;
        }
        setPaused(true);
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        String def = Prefs.bookName(this);
        if (def.isEmpty()) def = "책_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(new Date());
        et.setText(def);
        et.setSelectAllOnFocus(true);
        String where = Prefs.driveReady(this)
                ? "\n드라이브 '책스캔' 폴더로 자동으로 올라가요."
                : "\n(드라이브 자동 연결 전이라 공유창으로 보내요 — ⚙ 설정에서 연결)";
        new AlertDialog.Builder(this)
                .setTitle("책 이름 (" + n + "쪽)")
                .setMessage("글자 검색되는 PDF와 글자 파일(.txt)을 만들어요." + where)
                .setView(et)
                .setPositiveButton("만들기", (d, w) -> makeAndSend(et.getText().toString()))
                .setNegativeButton("취소", (d, w) -> setPaused(false))
                .show();
    }

    private void makeAndSend(String rawName) {
        String name = rawName.replaceAll("[\\\\/:*?\"<>|\\n]", "_").trim();
        if (name.isEmpty()) name = "책";
        Prefs.setBookName(this, name);
        final String fname = name;

        TextView msg = new TextView(this);
        msg.setPadding(dp(24), dp(20), dp(24), dp(20));
        msg.setTextSize(16);
        msg.setText("PDF 만드는 중…");
        AlertDialog dlg = new AlertDialog.Builder(this).setView(msg).setCancelable(false).show();

        io.execute(() -> {
            File pdfTmp, txtTmp;
            try {
                List<File> files = session.pages();
                List<PdfWriter.Page> pages = new ArrayList<>();
                for (File f : files) {
                    int[] s = Processor.size(f);
                    pages.add(new PdfWriter.Page(f, s[0], s[1], OcrLine.read(Session.ocrOf(f))));
                }
                File dir = new File(getCacheDir(), "pdf");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                pdfTmp = new File(dir, fname + ".pdf");
                PdfWriter.write(pages, pdfTmp, (done, total) ->
                        main.post(() -> msg.setText("PDF 만드는 중… " + done + " / " + total)));
                txtTmp = new File(dir, fname + ".txt");
                writeBookText(files, txtTmp);
            } catch (Throwable t) {
                main.post(() -> { dlg.dismiss(); toast("PDF 실패: " + t.getMessage()); });
                return;
            }
            final File pdf = pdfTmp, txt = txtTmp;

            if (!Prefs.driveReady(this)) {
                main.post(() -> { dlg.dismiss(); share(pdf); });
                return;
            }

            // 드라이브로 자동 업로드
            try {
                DriveUploader up = new DriveUploader(Prefs.driveUrl(this), Prefs.driveKey(this));
                long mb = Math.max(1, pdf.length() >> 20);
                String folderUrl = up.upload(fname, pdf, "application/pdf", (d, t) ->
                        main.post(() -> msg.setText("드라이브에 올리는 중… " + (d >> 20) + " / " + mb + "MB")));
                if (txt.length() > 0) {
                    main.post(() -> msg.setText("글자 파일 올리는 중…"));
                    up.upload(fname, txt, "text/plain", null);
                }
                main.post(() -> { dlg.dismiss(); uploaded(fname, folderUrl, pdf, txt); });
            } catch (Throwable t) {
                main.post(() -> {
                    dlg.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("드라이브 업로드 실패")
                            .setMessage(String.valueOf(t.getMessage()) + "\n\n찍은 쪽은 폰에 그대로 있어요.")
                            .setPositiveButton("다시 시도", (d, w) -> makeAndSend(fname))
                            .setNeutralButton("공유창으로 보내기", (d, w) -> share(pdf))
                            .setNegativeButton("닫기", null)
                            .show();
                });
            }
        });
    }

    /** 쪽마다 인식된 글자를 한 파일로 — 헤르메스·검색용 */
    private static void writeBookText(List<File> pages, File out) throws Exception {
        try (OutputStream os = new FileOutputStream(out)) {
            int i = 0;
            for (File p : pages) {
                i++;
                String t = readText(Session.textOf(p));
                os.write(("\n===== " + i + "쪽 =====\n").getBytes("UTF-8"));
                os.write(t.getBytes("UTF-8"));
                os.write('\n');
            }
        }
    }

    private void uploaded(String book, String folderUrl, File pdf, File txt) {
        int n = session.count();
        boolean del = Prefs.deleteAfterUpload(this);
        if (del) {
            session.clear();
            //noinspection ResultOfMethodCallIgnored
            pdf.delete();
            //noinspection ResultOfMethodCallIgnored
            txt.delete();
            Prefs.setBookName(this, "");
            lastGroup = null;
            lastCapSig = null;
            refreshCount();
        }
        setPaused(false);
        setResult("☁ '" + book + "' " + n + "쪽 드라이브 업로드 완료", 0xFF80CBC4);
        new AlertDialog.Builder(this)
                .setTitle("드라이브에 올렸어요")
                .setMessage("책스캔/" + book + " 폴더에 PDF와 글자 파일이 있어요."
                        + (del ? "\n폰에 있던 사진 " + n + "장은 지웠어요." : ""))
                .setPositiveButton("확인", null)
                .setNeutralButton("폴더 열기", (d, w) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(folderUrl))); }
                    catch (Exception ignored) { }
                })
                .show();
    }

    private void share(File pdf) {
        Uri uri = FileProvider.getUriForFile(this, AUTHORITY, pdf);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/pdf");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_TITLE, pdf.getName());
        send.setClipData(ClipData.newRawUri(pdf.getName(), uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        pendingCleanupPdf = pdf;
        startActivity(Intent.createChooser(send, "PDF 보내기 — 'Drive'를 고르세요"));
    }

    private void askCleanupAfterShare(File pdf) {
        long mb = Math.max(1, pdf.length() / (1024 * 1024));
        new AlertDialog.Builder(this)
                .setTitle("드라이브에 올라갔나요?")
                .setMessage("드라이브 앱에서 업로드가 끝났으면, 폰에 있는 사진 "
                        + session.count() + "장과 PDF(" + mb + "MB)를 지워서 공간을 비울 수 있어요.")
                .setPositiveButton("지우고 새 책 시작", (d, w) -> {
                    session.clear();
                    //noinspection ResultOfMethodCallIgnored
                    pdf.delete();
                    Prefs.setBookName(this, "");
                    lastGroup = null;
                    lastCapSig = null;
                    refreshCount();
                    setPaused(false);
                    toast("정리했어요. 새 책을 찍으세요");
                })
                .setNeutralButton("다시 보내기", (d, w) -> share(pdf))
                .setNegativeButton("나중에", (d, w) -> setPaused(false))
                .show();
    }
}
