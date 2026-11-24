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

/** 쪽마다 함께 저장하는 파일: p00001.txt(글자), p00001.lines(줄 위치 — PDF 글자층용) */
final class PageFiles {
    private PageFiles() {}

    static File linesOf(File jpg) {
        String p = jpg.getPath();
        return new File(p.substring(0, p.length() - 4) + ".lines");
    }

    static void writeText(File jpg, String text) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(Session.textOf(jpg)), StandardCharsets.UTF_8)) {
            w.write(text);
        }
    }

    /** 한 줄에: x0,y0,x1,y1,x2,y2,x3,y3 [탭] 글자 */
    static void writeLines(File jpg, List<PdfWriter.Line> lines) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(linesOf(jpg)), StandardCharsets.UTF_8)) {
            for (PdfWriter.Line l : lines) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 8; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(String.format(Locale.US, "%.1f", l.p[i]));
                }
                sb.append('\t').append(l.text.replace('\t', ' ').replace('\n', ' ')).append('\n');
                w.write(sb.toString());
            }
        }
    }

    static List<PdfWriter.Line> readLines(File jpg) {
        List<PdfWriter.Line> out = new ArrayList<>();
        File f = linesOf(jpg);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String s;
            while ((s = r.readLine()) != null) {
                int tab = s.indexOf('\t');
                if (tab < 0) continue;
                String[] n = s.substring(0, tab).split(",");
                if (n.length != 8) continue;
                float[] p = new float[8];
                for (int i = 0; i < 8; i++) p[i] = Float.parseFloat(n[i]);
                out.add(new PdfWriter.Line(p, s.substring(tab + 1)));
            }
        } catch (Exception ignored) { }
        return out;
    }
}
