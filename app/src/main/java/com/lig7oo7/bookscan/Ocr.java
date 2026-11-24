package com.lig7oo7.bookscan;

import android.graphics.Bitmap;
import android.graphics.Point;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 폰 안에서 도는 한글 글자 인식 (ML Kit). 백그라운드 스레드에서만 부를 것. */
final class Ocr {
    static final class Result {
        String text = "";
        /** 공백 뺀 글자 수 */
        int chars;
        /** 0~1 인식 확신도 (못 구하면 -1) */
        float conf = -1f;
        /** 줄마다 꼭짓점(사진 픽셀)과 글자 — PDF 투명 글자층용 */
        final List<PdfWriter.Line> lines = new ArrayList<>();
        long ms;
    }

    private final TextRecognizer rec =
            TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());

    Result run(Bitmap b) throws Exception {
        long t0 = System.currentTimeMillis();
        Text t = Tasks.await(rec.process(InputImage.fromBitmap(b, 0)), 30, TimeUnit.SECONDS);
        Result r = new Result();
        StringBuilder sb = new StringBuilder();
        float sum = 0;
        int weight = 0;
        for (Text.TextBlock bl : t.getTextBlocks()) {
            for (Text.Line ln : bl.getLines()) {
                String s = ln.getText();
                sb.append(s).append('\n');
                int c = s.replaceAll("\\s", "").length();
                r.chars += c;
                float conf = lineConf(ln);
                if (conf >= 0 && c > 0) { sum += conf * c; weight += c; }
                Point[] cp = ln.getCornerPoints();
                if (cp != null && cp.length == 4) {
                    float[] p = new float[8];
                    for (int i = 0; i < 4; i++) { p[2 * i] = cp[i].x; p[2 * i + 1] = cp[i].y; }
                    r.lines.add(new PdfWriter.Line(p, s));
                }
            }
            sb.append('\n');
        }
        r.text = sb.toString().trim();
        r.conf = weight > 0 ? sum / weight : -1f;
        r.ms = System.currentTimeMillis() - t0;
        return r;
    }

    /** 줄 확신도: 줄 값이 없으면 단어들 평균. 이 기능이 없는 기기·버전이면 -1 */
    private static float lineConf(Text.Line ln) {
        try {
            float c = ln.getConfidence();
            if (c > 0f) return c;
            float s = 0;
            int n = 0;
            for (Text.Element e : ln.getElements()) {
                int len = e.getText().length();
                s += e.getConfidence() * len;
                n += len;
            }
            return n > 0 && s > 0 ? s / n : -1f;
        } catch (Throwable e) {
            return -1f;
        }
    }

    void close() { rec.close(); }
}
