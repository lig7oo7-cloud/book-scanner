package com.lig7oo7.bookscan;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * 검색되는 PDF 만들기.
 * - 쪽 사진(JPEG)은 다시 압축하지 않고 그대로 넣는다 (DCTDecode) → 그림·도표 그대로 보임
 * - 그 위에 '보이지 않는 글자층'을 인식된 줄 위치에 맞춰 깐다 → 검색·복사·AI 텍스트 추출 가능
 *   (Tesseract·OCRmyPDF와 같은 방식: 모양이 없는 글꼴 + 렌더링 모드 3)
 */
public final class PdfWriter {

    /** 인식된 한 줄: 사진 픽셀 좌표 꼭짓점(왼위·오른위·오른아래·왼아래)과 글자 */
    public static final class Line {
        final float[] p;
        final String text;
        public Line(float[] p, String text) { this.p = p; this.text = text; }
    }

    public static final class Page {
        final File jpg;
        final int w, h;
        final List<Line> lines;
        public Page(File jpg, int w, int h) { this(jpg, w, h, null); }
        public Page(File jpg, int w, int h, List<Line> lines) {
            this.jpg = jpg; this.w = w; this.h = h;
            this.lines = lines == null ? Collections.<Line>emptyList() : lines;
        }
    }

    public interface Progress { void onPage(int done, int total); }

    /** 모든 글자가 빈 모양인 아주 작은 TrueType 글꼴 */
    private static final String GLYPHLESS_TTF = "AAEAAAAKAIAAAwAgT1MvMkT/RUAAAAEoAAAAYGNtYXAADABzAAABkAAAADRnbHlmAAAAAAAAAcwAAAABaGVhZC1stO4AAACsAAAANmhoZWED6QH2AAAA5AAAACRobXR4AfQAAAAAAYgAAAAGbG9jYQAAAAAAAAHEAAAABm1heHAAAwACAAABCAAAACBuYW1lGZ8ZNAAAAdAAAABycG9zdDIGAmcAAAJEAAAAKQABAAAAAQAAHZ5skV8PPPUAAwPoAAAAAOcrOgcAAAAA5ys6BwAAAAAAAAAAAAAAAwACAAAAAAAAAAEAAAPoAAAAAAH0AAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAEAAAACAAAAAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAwH0AZAABQAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAPz8/PwAAACAAIAPoAAAAAAPoAAAAAAAAAAAAAAAAAAAAAAAgAAAB9AAAAAAAAAAAAAIAAAADAAAAFAADAAEAAAAUAAQAIAAAAAQABAABAAAAIP//AAAAIP///+EAAQAAAAAAAAAAAAAAAAAAAAAAAAAEADYAAQAAAAAAAQANAAAAAQAAAAAAAgAHAA0AAwABBAkAAQAaABQAAwABBAkAAgAOAC5HbHlwaExlc3NGb250UmVndWxhcgBHAGwAeQBwAGgATABlAHMAcwBGAG8AbgB0AFIAZQBnAHUAbABhAHIAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAECAmcxAAAA";

    private static final int FIRST_PAGE_OBJ = 9;   // 1 카탈로그, 2 쪽목록, 3~8 글꼴

    private PdfWriter() {}

    public static void write(List<Page> pages, File out, Progress progress) throws IOException {
        int n = pages.size();
        if (n == 0) throw new IOException("페이지가 없습니다");
        int total = FIRST_PAGE_OBJ - 1 + 3 * n;
        long[] off = new long[total + 1];

        try (Counting o = new Counting(new BufferedOutputStream(new FileOutputStream(out), 1 << 16))) {
            o.ascii("%PDF-1.5\n");
            o.write(new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});

            off[1] = o.count;
            o.ascii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

            StringBuilder kids = new StringBuilder();
            for (int i = 0; i < n; i++) kids.append(FIRST_PAGE_OBJ + 3 * i).append(" 0 R ");
            off[2] = o.count;
            o.ascii("2 0 obj\n<< /Type /Pages /Count " + n + " /Kids [ " + kids + "] >>\nendobj\n");

            writeFont(o, off);

            byte[] buf = new byte[1 << 16];
            for (int i = 0; i < n; i++) {
                Page p = pages.get(i);
                if (p.w <= 0 || p.h <= 0) throw new IOException("그림 크기를 못 읽음: " + p.jpg.getName());
                float pw = 595.28f;                       // A4 폭(pt), 높이는 사진 비율대로
                float ph = pw * p.h / p.w;
                int po = FIRST_PAGE_OBJ + 3 * i, io = po + 1, co = po + 2;

                off[po] = o.count;
                o.ascii(po + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + f(pw) + " " + f(ph)
                        + "] /Resources << /XObject << /Im0 " + io + " 0 R >> /Font << /F1 3 0 R >> >> /Contents "
                        + co + " 0 R >>\nendobj\n");

                long len = p.jpg.length();
                off[io] = o.count;
                o.ascii(io + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + p.w + " /Height " + p.h
                        + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + len
                        + " >>\nstream\n");
                long copied = 0;
                try (InputStream in = new FileInputStream(p.jpg)) {
                    int r;
                    while ((r = in.read(buf)) > 0) { o.write(buf, 0, r); copied += r; }
                }
                if (copied != len) throw new IOException("파일이 읽는 중에 바뀜: " + p.jpg.getName());
                o.ascii("\nendstream\nendobj\n");

                byte[] content = deflate(pageContent(p, pw, ph));
                off[co] = o.count;
                o.ascii(co + " 0 obj\n<< /Length " + content.length + " /Filter /FlateDecode >>\nstream\n");
                o.write(content);
                o.ascii("\nendstream\nendobj\n");

                if (progress != null) progress.onPage(i + 1, n);
            }

            long xref = o.count;
            StringBuilder sb = new StringBuilder();
            sb.append("xref\n0 ").append(total + 1).append('\n');
            sb.append("0000000000 65535 f \n");
            for (int k = 1; k <= total; k++) sb.append(String.format(Locale.US, "%010d 00000 n \n", off[k]));
            sb.append("trailer\n<< /Size ").append(total + 1).append(" /Root 1 0 R >>\nstartxref\n")
              .append(xref).append("\n%%EOF\n");
            o.ascii(sb.toString());
        }
    }

    /** 사진 그리기 + 보이지 않는 글자 */
    static byte[] pageContent(Page p, float pw, float ph) {
        StringBuilder c = new StringBuilder();
        c.append("q ").append(f(pw)).append(" 0 0 ").append(f(ph)).append(" 0 0 cm /Im0 Do Q\n");
        float s = pw / p.w;                                // 사진 픽셀 → pt
        for (Line ln : p.lines) {
            String t = ln.text.replace('\n', ' ').trim();
            if (t.isEmpty()) continue;
            float[] q = ln.p;
            // PDF는 아래가 0 → y 뒤집기. 기준선 = 왼아래, 방향 = 왼위→오른위
            float blx = q[6] * s, bly = ph - q[7] * s;
            float dx = (q[2] - q[0]) * s, dy = -(q[3] - q[1]) * s;
            float width = (float) Math.hypot(dx, dy);
            float height = (float) Math.hypot((q[6] - q[0]) * s, (q[7] - q[1]) * s);
            if (width < 1 || height < 1) continue;
            float cos = dx / width, sin = dy / width;
            float fs = height;                                         // 글자 크기 = 줄 높이
            int units = t.length();                                    // UTF-16 단위 수
            float natural = units * fs * 0.5f;                          // 글꼴 폭 500/1000
            float tz = Math.max(1f, Math.min(10000f, 100f * width / natural));
            c.append("BT 3 Tr /F1 ").append(f(fs)).append(" Tf ").append(f(tz)).append(" Tz ")
             .append(f(cos)).append(' ').append(f(sin)).append(' ').append(f(-sin)).append(' ').append(f(cos))
             .append(' ').append(f(blx)).append(' ').append(f(bly)).append(" Tm <");
            for (int i = 0; i < t.length(); i++) c.append(String.format(Locale.US, "%04X", (int) t.charAt(i)));
            c.append("> Tj ET\n");
        }
        return c.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** 3: Type0 글꼴, 4: CID 글꼴, 5: 글꼴 설명, 6: 글꼴 파일, 7: 유니코드 대응표, 8: CID→글리프 */
    private static void writeFont(Counting o, long[] off) throws IOException {
        off[3] = o.count;
        o.ascii("3 0 obj\n<< /Type /Font /Subtype /Type0 /BaseFont /GlyphLessFont /Encoding /Identity-H"
                + " /DescendantFonts [4 0 R] /ToUnicode 7 0 R >>\nendobj\n");
        off[4] = o.count;
        o.ascii("4 0 obj\n<< /Type /Font /Subtype /CIDFontType2 /BaseFont /GlyphLessFont"
                + " /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >>"
                + " /FontDescriptor 5 0 R /DW 500 /CIDToGIDMap 8 0 R >>\nendobj\n");
        off[5] = o.count;
        o.ascii("5 0 obj\n<< /Type /FontDescriptor /FontName /GlyphLessFont /Flags 5 /FontBBox [0 0 500 1000]"
                + " /ItalicAngle 0 /Ascent 1000 /Descent 0 /CapHeight 1000 /StemV 80 /FontFile2 6 0 R >>\nendobj\n");
        byte[] ttf = Base64.getDecoder().decode(GLYPHLESS_TTF);
        off[6] = o.count;
        o.ascii("6 0 obj\n<< /Length " + ttf.length + " /Length1 " + ttf.length + " >>\nstream\n");
        o.write(ttf);
        o.ascii("\nendstream\nendobj\n");
        byte[] cmap = ("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                + "/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n"
                + "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n"
                + "1 beginbfrange\n<0000> <FFFF> <0000>\nendbfrange\n"
                + "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
                .getBytes(StandardCharsets.US_ASCII);
        off[7] = o.count;
        o.ascii("7 0 obj\n<< /Length " + cmap.length + " >>\nstream\n");
        o.write(cmap);
        o.ascii("\nendstream\nendobj\n");
        // 모든 CID(=유니코드 값)를 1번 글리프(빈 모양)로
        byte[] map = new byte[65536 * 2];
        for (int i = 0; i < 65536; i++) map[2 * i + 1] = 1;
        byte[] z = deflate(map);
        off[8] = o.count;
        o.ascii("8 0 obj\n<< /Length " + z.length + " /Filter /FlateDecode >>\nstream\n");
        o.write(z);
        o.ascii("\nendstream\nendobj\n");
    }

    private static byte[] deflate(byte[] in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(bo, new Deflater(Deflater.BEST_COMPRESSION))) {
            d.write(in);
        }
        return bo.toByteArray();
    }

    private static String f(float v) { return String.format(Locale.US, "%.2f", v); }

    private static final class Counting extends FilterOutputStream {
        long count;
        Counting(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int o, int l) throws IOException { out.write(b, o, l); count += l; }
        @Override public void write(byte[] b) throws IOException { write(b, 0, b.length); }
        void ascii(String s) throws IOException { write(s.getBytes(StandardCharsets.US_ASCII)); }
    }
}
