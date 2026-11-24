package com.lig7oo7.bookscan;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * JPEG 파일을 다시 압축하지 않고 그대로 PDF에 넣는다 (DCTDecode).
 * 안드로이드 PdfDocument는 사진을 무압축에 가깝게 넣어 파일이 아주 커지므로 직접 만든다.
 * 한 번에 한 장씩 디스크에서 흘려 넣어서 메모리도 거의 안 쓴다.
 *
 * 인식된 글자는 사진 위에 '보이지 않는 글자'(렌더 모드 3)로 겹쳐 넣는다 → PDF에서 검색·복사·드래그가 된다.
 * 글자는 보이지 않으므로 글꼴은 넣지 않고(Identity-H), ToUnicode 표로 코드 = 유니코드가 되게 해서 어떤 뷰어에서도 검색된다.
 */
public final class PdfWriter {

    public static final class Page {
        final File jpg;
        final int w, h;
        /** 인식된 글자 줄 (사진 픽셀 좌표). 비어 있으면 그림만 */
        final List<OcrLine> lines;
        public Page(File jpg, int w, int h) { this(jpg, w, h, null); }
        public Page(File jpg, int w, int h, List<OcrLine> lines) {
            this.jpg = jpg; this.w = w; this.h = h;
            this.lines = lines != null ? lines : java.util.Collections.emptyList();
        }
    }

    public interface Progress { void onPage(int done, int total); }

    private PdfWriter() {}

    public static void write(List<Page> pages, File out, Progress progress) throws IOException {
        int n = pages.size();
        if (n == 0) throw new IOException("페이지가 없습니다");
        int fontObj = 3 + 3 * n;          // 글꼴 4개: Type0, CIDFont, 글꼴정보, ToUnicode
        int total = fontObj + 3;          // 1 카탈로그, 2 페이지목록, 쪽마다 (페이지, 이미지, 내용), 글꼴
        long[] off = new long[total + 1];

        try (Counting o = new Counting(new BufferedOutputStream(new FileOutputStream(out), 1 << 16))) {
            o.ascii("%PDF-1.4\n");
            o.write(new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});

            off[1] = o.count;
            o.ascii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

            StringBuilder kids = new StringBuilder();
            for (int i = 0; i < n; i++) kids.append(3 + 3 * i).append(" 0 R ");
            off[2] = o.count;
            o.ascii("2 0 obj\n<< /Type /Pages /Count " + n + " /Kids [ " + kids + "] >>\nendobj\n");

            byte[] buf = new byte[1 << 16];
            for (int i = 0; i < n; i++) {
                Page p = pages.get(i);
                if (p.w <= 0 || p.h <= 0) throw new IOException("그림 크기를 못 읽음: " + p.jpg.getName());
                float pw = 595.28f;                       // A4 폭(pt), 높이는 사진 비율대로
                float ph = pw * p.h / p.w;
                int po = 3 + 3 * i, io = po + 1, co = po + 2;

                off[po] = o.count;
                o.ascii(po + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + f(pw) + " " + f(ph)
                        + "] /Resources << /XObject << /Im0 " + io + " 0 R >> /Font << /F1 " + fontObj + " 0 R >> >> /Contents " + co
                        + " 0 R >>\nendobj\n");

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

                byte[] content = (("q " + f(pw) + " 0 0 " + f(ph) + " 0 0 cm /Im0 Do Q\n")
                        + textLayer(p.lines, pw / p.w, ph)).getBytes(StandardCharsets.US_ASCII);
                off[co] = o.count;
                o.ascii(co + " 0 obj\n<< /Length " + content.length + " >>\nstream\n");
                o.write(content);
                o.ascii("endstream\nendobj\n");

                if (progress != null) progress.onPage(i + 1, n);
            }

            off[fontObj] = o.count;
            o.ascii(fontObj + " 0 obj\n<< /Type /Font /Subtype /Type0 /BaseFont /GlyphLessFont /Encoding /Identity-H"
                    + " /DescendantFonts [ " + (fontObj + 1) + " 0 R ] /ToUnicode " + (fontObj + 3) + " 0 R >>\nendobj\n");
            off[fontObj + 1] = o.count;
            o.ascii((fontObj + 1) + " 0 obj\n<< /Type /Font /Subtype /CIDFontType2 /BaseFont /GlyphLessFont"
                    + " /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >>"
                    + " /CIDToGIDMap /Identity /FontDescriptor " + (fontObj + 2) + " 0 R /DW 1000 >>\nendobj\n");
            off[fontObj + 2] = o.count;
            o.ascii((fontObj + 2) + " 0 obj\n<< /Type /FontDescriptor /FontName /GlyphLessFont /Flags 4"
                    + " /FontBBox [0 -148 1001 880] /ItalicAngle 0 /Ascent 880 /Descent -120 /CapHeight 880 /StemV 93 >>\nendobj\n");
            byte[] cmap = toUnicodeCMap();
            off[fontObj + 3] = o.count;
            o.ascii((fontObj + 3) + " 0 obj\n<< /Length " + cmap.length + " >>\nstream\n");
            o.write(cmap);
            o.ascii("\nendstream\nendobj\n");

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

    /**
     * 보이지 않는 글자층. 줄마다 왼아래 꼭짓점에서 줄 기울기대로 쓰고,
     * 가로 비율(Tz)로 글자 폭을 줄 길이에 맞춘다 → 검색하면 그 자리에 하이라이트된다.
     */
    static String textLayer(List<OcrLine> lines, float s, float ph) {
        if (lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("BT 3 Tr\n");
        for (OcrLine l : lines) {
            String t = l.text.trim();
            if (t.isEmpty()) continue;
            float[] p = l.pts;
            // 픽셀(y 아래로) → PDF 점(y 위로)
            float x0 = p[6] * s, y0 = ph - p[7] * s;          // 왼아래
            float x1 = p[4] * s, y1 = ph - p[5] * s;          // 오른아래
            float tx = p[0] * s, ty = ph - p[1] * s;          // 왼위
            float len = (float) Math.hypot(x1 - x0, y1 - y0);
            float hgt = (float) Math.hypot(tx - x0, ty - y0);
            if (len < 1 || hgt < 1) continue;
            float cos = (x1 - x0) / len, sin = (y1 - y0) / len;
            float size = hgt * 0.9f;
            int n = t.codePointCount(0, t.length());
            float tz = 100f * len / (n * size);              // 글자 폭 1000 기준
            sb.append(String.format(Locale.US, "/F1 %.2f Tf %.2f Tz %.4f %.4f %.4f %.4f %.2f %.2f Tm <",
                    size, tz, cos, sin, -sin, cos, x0 - sin * hgt * 0.15f, y0 + cos * hgt * 0.15f));   // 글자 기준선: 줄 바닥에서 조금 위
            for (int i = 0; i < t.length(); i++) {
                char ch = t.charAt(i);
                sb.append(String.format(Locale.US, "%04X", (int) ch));
            }
            sb.append("> Tj\n");
        }
        sb.append("ET\n");
        return sb.toString();
    }

    /** 2바이트 코드 = 유니코드 그대로 (UCS-2) */
    private static byte[] toUnicodeCMap() {
        StringBuilder sb = new StringBuilder();
        sb.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
          .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
          .append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
          .append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
        for (int blk = 0; blk < 256; blk += 100) {
            int cnt = Math.min(100, 256 - blk);
            sb.append(cnt).append(" beginbfrange\n");
            for (int hi = blk; hi < blk + cnt; hi++) {
                if (hi >= 0xD8 && hi <= 0xDF) {     // 서로게이트 영역은 건너뜀
                    sb.append(String.format(Locale.US, "<%02X00> <%02X00> <0020>\n", hi, hi));
                    continue;
                }
                sb.append(String.format(Locale.US, "<%02X00> <%02XFF> <%02X00>\n", hi, hi, hi));
            }
            sb.append("endbfrange\n");
        }
        sb.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
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
