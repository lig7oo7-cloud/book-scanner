package com.lig7oo7.bookscan;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaActionSound;
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
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity implements MotionAnalyzer.Listener {

    private static final String AUTHORITY = "com.lig7oo7.bookscan.files";

    private Session session;
    private PreviewView previewView;
    private View flash;
    private TextView statusText, countText, modeBtn, pauseBtn, torchBtn;
    private ImageView thumb;

    private Camera camera;
    private ImageCapture imageCapture;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService analysisExec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaActionSound shutterSound;

    // 분석 스레드와 같이 쓰는 상태
    private volatile boolean busy, paused, torch;
    private volatile int mode, stableMs, dupT;
    private volatile float motionT, stableT;
    private volatile int[] lastSig, lastCapSig;
    private volatile boolean needMotion;
    private int motionFrames;
    private long stableSince;
    private String lastStatus = "";

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
            main.postDelayed(() -> askCleanup(pdf), 400);
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
        needMotion = false;
        stableSince = 0;
        motionFrames = 0;
    }

    private String modeLabel() {
        switch (mode) {
            case Prefs.MODE_INTERVAL: return "⏱ 간격 " + Prefs.intervalSec(this) + "초";
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
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
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

        flash = new View(this);
        flash.setBackgroundColor(Color.WHITE);
        flash.setAlpha(0f);
        root.addView(flash, new FrameLayout.LayoutParams(-1, -1));

        // 위쪽: 상태, 쪽수, 설정
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(10), dp(12), dp(10));
        top.setBackgroundColor(0x99000000);
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
        top.addView(settings);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // 아래쪽
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xAA000000);
        bottom.setPadding(dp(10), dp(10), dp(10), dp(16));

        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER);
        modeBtn = btn("", 0xFF2E7D6B);
        modeBtn.setOnClickListener(v -> {
            int m = (mode + 1) % 3;
            Prefs.setMode(this, m);
            loadPrefs();
            Toast.makeText(this, modeHelp(), Toast.LENGTH_SHORT).show();
        });
        pauseBtn = btn("⏸ 일시정지", 0x55FFFFFF);
        pauseBtn.setOnClickListener(v -> setPaused(!paused));
        torchBtn = btn("💡 조명", 0x55FFFFFF);
        torchBtn.setOnClickListener(v -> {
            torch = !torch;
            if (camera != null) camera.getCameraControl().enableTorch(torch);
            torchBtn.setText(torch ? "💡 조명 끄기" : "💡 조명");
        });
        row1.addView(modeBtn, lp(1f));
        row1.addView(pauseBtn, lp(1f));
        row1.addView(torchBtn, lp(1f));
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
        LinearLayout.LayoutParams ulp = lp(1f);
        row2.addView(undo, ulp);

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

        TextView done = btn("✔ 완료·PDF", 0xFFE65100);
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
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private String modeHelp() {
        switch (mode) {
            case Prefs.MODE_INTERVAL: return "간격 모드: 정해진 초마다 찍어요 (같은 쪽은 건너뜀)";
            case Prefs.MODE_MANUAL: return "수동 모드: 셔터나 볼륨키로 찍어요";
            default: return "자동 모드: 책장을 넘기고 손을 떼면 찍어요";
        }
    }

    private void setPaused(boolean p) {
        paused = p;
        pauseBtn.setText(p ? "▶ 재개" : "⏸ 일시정지");
        stableSince = 0;
        if (p) setStatus("일시정지");
    }

    private void setStatus(String s) {
        if (s.equals(lastStatus)) return;
        lastStatus = s;
        if (Looper.myLooper() == Looper.getMainLooper()) statusText.setText(s);
        else main.post(() -> statusText.setText(s));
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
            Bitmap bm = Processor.thumb(last, 1400);
            main.post(() -> {
                ImageView iv = new ImageView(this);
                iv.setAdjustViewBounds(true);
                iv.setImageBitmap(bm);
                new AlertDialog.Builder(this)
                        .setTitle("마지막 쪽 (" + session.count() + ")")
                        .setView(iv)
                        .setPositiveButton("닫기", null)
                        .setNegativeButton("지우고 다시 찍기", (d, w) -> undo())
                        .show();
            });
        });
    }

    // ───────────────────────── 카메라 ─────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                ProcessCameraProvider provider = f.get();
                provider.unbindAll();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(95)
                        .build();

                @SuppressWarnings("deprecation")
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(480, 640))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExec, new MotionAnalyzer(this));

                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture, analysis);
                if (torch) camera.getCameraControl().enableTorch(true);
                setStatus(modeHelp());
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
            if (mode == Prefs.MODE_MANUAL && !paused) setStatus("셔터·볼륨키로 찍기");
            return;
        }
        if (paused || busy) {
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
                setStatus(String.format(Locale.KOREA, "🔍 멈춤 확인 %.1f초", (stableMs - held) / 1000f));
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
        if (busy || imageCapture == null) return;
        int[] sig = lastSig;
        if (dupCheck && dupT > 0 && sig != null && lastCapSig != null
                && MotionAnalyzer.meanAbsDiff(sig, lastCapSig, true) < dupT) {
            setStatus("같은 쪽이라 건너뜀 — 넘겨주세요");
            return;
        }
        busy = true;
        lastCapSig = sig;
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
                    List<File> saved = Processor.process(MainActivity.this, session, jpeg, rot);
                    main.post(() -> {
                        lastGroup = saved;
                        refreshCount();
                    });
                } catch (Throwable t) {
                    if (!closed) image.close();
                    toast("저장 실패: " + t.getMessage());
                } finally {
                    busy = false;
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException e) {
                busy = false;
                toast("촬영 실패: " + e.getMessage());
            }
        });
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
        for (File f : g) if (f.delete()) n++;
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
        main.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    // ───────────────────────── PDF ─────────────────────────

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
        new AlertDialog.Builder(this)
                .setTitle("책 이름 (" + n + "쪽)")
                .setView(et)
                .setPositiveButton("PDF 만들기", (d, w) -> makePdf(et.getText().toString()))
                .setNegativeButton("취소", null)
                .show();
    }

    private void makePdf(String rawName) {
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
            try {
                List<File> files = session.pages();
                List<PdfWriter.Page> pages = new ArrayList<>();
                for (File f : files) {
                    int[] s = Processor.size(f);
                    pages.add(new PdfWriter.Page(f, s[0], s[1]));
                }
                File dir = new File(getCacheDir(), "pdf");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                File out = new File(dir, fname + ".pdf");
                PdfWriter.write(pages, out, (done, total) ->
                        main.post(() -> msg.setText("PDF 만드는 중… " + done + " / " + total)));
                main.post(() -> {
                    dlg.dismiss();
                    share(out);
                });
            } catch (Throwable t) {
                main.post(() -> {
                    dlg.dismiss();
                    toast("PDF 실패: " + t.getMessage());
                });
            }
        });
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

    private void askCleanup(File pdf) {
        long mb = Math.max(1, pdf.length() / (1024 * 1024));
        new AlertDialog.Builder(this)
                .setTitle("드라이브에 올라갔나요?")
                .setMessage("드라이브 앱에서 업로드가 시작됐으면, 폰에 있는 사진 "
                        + session.count() + "장과 PDF(" + mb + "MB)를 지워서 공간을 비울 수 있어요.\n\n"
                        + "드라이브 앱 알림에서 업로드 완료를 확인한 뒤 지우는 게 안전해요.")
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
                .setNegativeButton("나중에", null)
                .show();
    }
}
