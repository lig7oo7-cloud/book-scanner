package com.lig7oo7.bookscan;

import android.graphics.Bitmap;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.util.concurrent.TimeUnit;

/** 폰 안에서 도는 한글 글자 인식 (ML Kit). 백그라운드 스레드에서만 부를 것. */
final class Ocr {
    static final class Result {
        String text = "";
        int chars;
        /** 0~1, 글자 수로 가중 평균한 인식 확신도 */
        float conf;
    }

    private final TextRecognizer rec =
            TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());

    Result run(Bitmap b) throws Exception {
        Text t = Tasks.await(rec.process(InputImage.fromBitmap(b, 0)), 30, TimeUnit.SECONDS);
        Result r = new Result();
        StringBuilder sb = new StringBuilder();
        float sum = 0;
        int n = 0;
        for (Text.TextBlock bl : t.getTextBlocks()) {
            for (Text.Line ln : bl.getLines()) {
                sb.append(ln.getText()).append('\n');
                for (Text.Element el : ln.getElements()) {
                    int len = el.getText().length();
                    sum += el.getConfidence() * len;
                    n += len;
                }
            }
            sb.append('\n');
        }
        r.text = sb.toString().trim();
        r.chars = n;
        r.conf = n > 0 ? sum / n : 0f;
        return r;
    }

    void close() { rec.close(); }
}
