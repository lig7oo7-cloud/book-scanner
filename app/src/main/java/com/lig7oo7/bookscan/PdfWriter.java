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
 */
public final class PdfWriter {

    public static final class Page {
        final File jpg;
        final int w, h;
        public Page(File jpg, int w, int h) { this.jpg = jpg; this.w = w; this.h = h; }
    }

    public interface Progress { void onPage(int done, int total); }

    private PdfWriter() {}

    public static void write(List<Page> pages, File out, Progress progress) throws IOException {
        int n = pages.size();
        if (n == 0) throw new IOException("페이지가 없습니다");
        int total = 2 + 3 * n;            // 1 카탈로그, 2 페이지목록, 쪽마다 (페이지, 이미지, 내용)
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
                        + "] /Resources << /XObject << /Im0 " + io + " 0 R >> >> /Contents " + co
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

                byte[] content = ("q " + f(pw) + " 0 0 " + f(ph) + " 0 0 cm /Im0 Do Q\n")
                        .getBytes(StandardCharsets.US_ASCII);
                off[co] = o.count;
                o.ascii(co + " 0 obj\n<< /Length " + content.length + " >>\nstream\n");
                o.write(content);
                o.ascii("endstream\nendobj\n");

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
