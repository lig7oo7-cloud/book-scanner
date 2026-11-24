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
        final List<OcrLine> lines = new ArrayList<>();
        int chars;
        /** 0~1, 글자 수로 가중 평균한 인식 확신도. 확신도를 못 주는 경우 -1 */
        float conf = -1f;

        String text() { return OcrLine.joinText(lines); }

        /** 줄 꼭짓점들만 (사각형 계산용) */
        List<float[]> quads(int minLen) {
            List<float[]> out = new ArrayList<>();
            for (OcrLine l : lines) if (l.text.trim().length() >= minLen) out.add(l.pts);
            return out;
        }
    }

    private final TextRecognizer rec =
            TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());

    /** scale: 돌려줄 좌표에 곱할 배율 (작게 줄여 인식한 뒤 원래 크기 좌표로 돌려받을 때) */
    Result run(Bitmap b, float scale) throws Exception {
        Text t = Tasks.await(rec.process(InputImage.fromBitmap(b, 0)), 30, TimeUnit.SECONDS);
        Result r = new Result();
        float sum = 0;
        int n = 0, withConf = 0;
        for (Text.TextBlock bl : t.getTextBlocks()) {
            for (Text.Line ln : bl.getLines()) {
                Point[] cp = ln.getCornerPoints();
                if (cp == null || cp.length != 4) continue;
                float[] pts = new float[8];
                for (int i = 0; i < 4; i++) {
                    pts[2 * i] = cp[i].x * scale;
                    pts[2 * i + 1] = cp[i].y * scale;
                }
                r.lines.add(new OcrLine(ln.getText(), pts));
                for (Text.Element el : ln.getElements()) {
                    int len = el.getText().length();
                    float c = el.getConfidence();
                    if (c > 0f) withConf += len;
                    sum += c * len;
                    n += len;
                }
            }
        }
        r.chars = n;
        // 확신도를 0으로만 주는 환경이면 '모름'(-1)으로 둔다 → 흐림 판정을 확신도에만 기대지 않게
        r.conf = (n > 0 && withConf > n / 2) ? sum / n : -1f;
        return r;
    }

    Result run(Bitmap b) throws Exception { return run(b, 1f); }

    void close() { rec.close(); }
}
