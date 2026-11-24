package com.lig7oo7.bookscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 찍은 쪽 넘겨보기: 책 이미지 위에서 인식된 글자를 확인·복사 */
public class PageViewerActivity extends Activity implements PageView.Listener {
    static final String EXTRA_INDEX = "index";

    private Session session;
    private List<File> pages;
    private int index;
    private PageView view;
    private TextView title, info, modeBtn;
    private int mode = 1;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int loadSeq;

    private static final String[] MODES = {"🖼 원본", "▭ 인식 영역", "가 글자 덮기"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        session = new Session(this);
        pages = session.pages();
        if (pages.isEmpty()) {
            Toast.makeText(this, "찍은 쪽이 없어요", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        int want = getIntent().getIntExtra(EXTRA_INDEX, -1);
        index = want < 0 || want >= pages.size() ? pages.size() - 1 : want;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1E1E1E);

        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(10), 0, dp(10));
        root.addView(title);

        FrameLayout frame = new FrameLayout(this);
        view = new PageView(this);
        view.setListener(this);
        view.setMode(mode);
        frame.addView(view, new FrameLayout.LayoutParams(-1, -1));
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 아래: 선택한 줄 / 안내
        ScrollView infoScroll = new ScrollView(this);
        info = new TextView(this);
        info.setTextColor(0xFFE0E0E0);
        info.setTextSize(16);
        info.setTextIsSelectable(true);
        info.setPadding(dp(16), dp(10), dp(16), dp(10));
        infoScroll.addView(info);
        root.addView(infoScroll, new LinearLayout.LayoutParams(-1, dp(96)));

        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(6), dp(6), dp(6), dp(14));
        TextView prev = btn("◀", 0x55FFFFFF);
        prev.setOnClickListener(v -> onSwipe(-1));
        modeBtn = btn(MODES[mode], 0xFF2E7D6B);
        modeBtn.setOnClickListener(v -> {
            mode = (mode + 1) % 3;
            modeBtn.setText(MODES[mode]);
            view.setMode(mode);
        });
        TextView all = btn("📄 쪽 전체 글자", 0x55FFFFFF);
        all.setOnClickListener(v -> showAllText());
        TextView del = btn("🗑", 0x55FF5252);
        del.setOnClickListener(v -> deletePage());
        TextView next = btn("▶", 0x55FFFFFF);
        next.setOnClickListener(v -> onSwipe(+1));
        bar.addView(prev, lp(0.6f));
        bar.addView(modeBtn, lp(1.3f));
        bar.addView(all, lp(1.5f));
        bar.addView(del, lp(0.6f));
        bar.addView(next, lp(0.6f));
        root.addView(bar);

        setContentView(root);
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void load() {
        File f = pages.get(index);
        title.setText((index + 1) + " / " + pages.size() + "쪽");
        info.setText("불러오는 중…");
        final int seq = ++loadSeq;
        io.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int origW = o.outWidth;
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 2000) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o);
            List<PdfWriter.Line> lines = PageFiles.readLines(f);
            int chars = 0;
            for (PdfWriter.Line l : lines) chars += l.text.replaceAll("\\s", "").length();
            final int c = chars;
            main.post(() -> {
                if (seq != loadSeq) return;
                view.setPage(bm, origW, lines);
                info.setText(lines.isEmpty()
                        ? "이 쪽은 인식된 글자가 없어요 (그림만 있거나 인식 전 사진)"
                        : "줄 " + lines.size() + "개 · " + c + "자 인식\n줄을 톡 누르면 그 줄 글자가 여기 나와요. 두 손가락으로 확대, 두 번 톡 = 확대/원래대로.");
            });
        });
    }

    @Override
    public void onLineTap(PdfWriter.Line line) {
        if (line == null) return;
        info.setText(line.text);
    }

    @Override
    public void onSwipe(int dir) {
        int n = index + dir;
        if (n < 0 || n >= pages.size()) {
            Toast.makeText(this, dir > 0 ? "마지막 쪽이에요" : "첫 쪽이에요", Toast.LENGTH_SHORT).show();
            return;
        }
        index = n;
        load();
    }

    private void showAllText() {
        String t = readText(Session.textOf(pages.get(index)));
        TextView tv = new TextView(this);
        tv.setTextIsSelectable(true);
        tv.setTextSize(15);
        tv.setPadding(dp(20), dp(16), dp(20), dp(16));
        tv.setText(t.isEmpty() ? "(인식된 글자 없음)" : t);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle((index + 1) + "쪽 인식 글자")
                .setView(sv)
                .setPositiveButton("복사", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("page", t));
                    Toast.makeText(this, "복사했어요", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void deletePage() {
        new AlertDialog.Builder(this)
                .setMessage((index + 1) + "쪽을 지울까요? (다시 찍으려면 지운 뒤 촬영 화면에서 찍으면 맨 뒤에 붙어요)")
                .setPositiveButton("지우기", (d, w) -> {
                    Session.delete(pages.get(index));
                    pages = session.pages();
                    if (pages.isEmpty()) { finish(); return; }
                    if (index >= pages.size()) index = pages.size() - 1;
                    load();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private static String readText(File f) {
        if (!f.exists()) return "";
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new String(b, 0, n, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView btn(String t, int bg) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(14);
        v.setSingleLine(true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(4), dp(12), dp(4), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(22));
        v.setBackground(g);
        v.setClickable(true);
        return v;
    }

    private LinearLayout.LayoutParams lp(float w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, w);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }
}
