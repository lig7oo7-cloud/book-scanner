package com.lig7oo7.bookscan;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 글자 인식 결과 한 줄: 글자 + 그 줄을 감싸는 꼭짓점 4개(왼위·오른위·오른아래·왼아래, 픽셀).
 * 쪽 사진 옆에 p00001.ocr 로 저장해 두었다가 PDF에 '보이지 않는 글자층'으로 넣는다
 * → PDF에서 검색·복사가 된다. (안드로이드 의존 없음 → PC에서 시험 가능)
 */
public final class OcrLine {
    public final String text;
    public final float[] pts;

    public OcrLine(String text, float[] pts) {
        this.text = text;
        this.pts = pts;
    }

    float cx() { return (pts[0] + pts[2] + pts[4] + pts[6]) / 4f; }
    float cy() { return (pts[1] + pts[3] + pts[5] + pts[7]) / 4f; }

    /** 한 줄에 하나씩: x1,y1,...,x4,y4 [탭] 글자 */
    public static void write(List<OcrLine> lines, File f) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            for (OcrLine l : lines) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 8; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(String.format(Locale.US, "%.1f", l.pts[i]));
                }
                sb.append('\t').append(l.text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')).append('\n');
                w.write(sb.toString());
            }
        }
    }

    public static List<OcrLine> read(File f) {
        List<OcrLine> out = new ArrayList<>();
        if (f == null || !f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String s;
            while ((s = r.readLine()) != null) {
                int tab = s.indexOf('\t');
                if (tab < 0) continue;
                String[] p = s.substring(0, tab).split(",");
                if (p.length != 8) continue;
                float[] pts = new float[8];
                try {
                    for (int i = 0; i < 8; i++) pts[i] = Float.parseFloat(p[i]);
                } catch (NumberFormatException e) {
                    continue;
                }
                out.add(new OcrLine(s.substring(tab + 1), pts));
            }
        } catch (IOException ignored) { }
        return out;
    }

    /** 사람이 읽는 순서(위→아래)로 이은 글자 */
    public static String joinText(List<OcrLine> lines) {
        StringBuilder sb = new StringBuilder();
        for (OcrLine l : lines) sb.append(l.text).append('\n');
        return sb.toString().trim();
    }
}
