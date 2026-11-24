package com.lig7oo7.bookscan;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 구글 드라이브 업로드. 사용자 구글 계정에 배포한 Apps Script(웹 앱)가
 * '이어 올리기(resumable) 주소'를 만들어 주면, 파일은 그 주소로 8MB씩 직접 올린다.
 * → 앱에 구글 로그인·클라우드 콘솔 설정이 필요 없고, 끊겨도 이어서 올린다.
 */
final class DriveUploader {
    interface Progress { void on(long done, long total); }

    private static final int CHUNK = 8 * 1024 * 1024;   // 256KB의 배수여야 함

    private final String scriptUrl;

    DriveUploader(String url, String key) throws IOException {
        String sep = url.contains("?") ? "&" : "?";
        this.scriptUrl = url + sep + "key=" + URLEncoder.encode(key, "UTF-8");
    }

    /** 연결 확인. 성공하면 드라이브 '책스캔' 폴더 주소를 돌려준다 */
    String ping() throws IOException {
        JSONObject r = call(new JSONObjectBuilder().put("action", "ping").build());
        return r.optString("folderUrl");
    }

    /** 파일 하나 올리기. 돌려주는 값: 책 폴더 주소 */
    String upload(String book, File f, String mime, Progress p) throws IOException {
        long total = f.length();
        JSONObject r = call(new JSONObjectBuilder()
                .put("action", "start").put("book", book).put("name", f.getName())
                .put("mime", mime).put("size", total).build());
        String session = r.optString("url");
        if (session.isEmpty()) throw new IOException("업로드 주소를 못 받았어요");

        byte[] buf = new byte[CHUNK];
        long off = 0;
        int failures = 0;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            while (off < total) {
                int len = (int) Math.min(CHUNK, total - off);
                try {
                    raf.seek(off);
                    raf.readFully(buf, 0, len);
                    HttpURLConnection c = open(session);
                    c.setRequestMethod("PUT");
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(len);
                    c.setRequestProperty("Content-Range", "bytes " + off + "-" + (off + len - 1) + "/" + total);
                    try (OutputStream os = c.getOutputStream()) { os.write(buf, 0, len); }
                    int code = c.getResponseCode();
                    if (code == 200 || code == 201) {
                        off = total;
                    } else if (code == 308) {
                        off = rangeEnd(c.getHeaderField("Range"));
                        failures = 0;
                    } else {
                        throw new IOException("드라이브 응답 " + code + " " + readErr(c));
                    }
                    c.disconnect();
                    if (p != null) p.on(off, total);
                } catch (IOException e) {
                    if (++failures > 6) throw e;
                    sleep(2000L * failures);
                    off = queryOffset(session, total);
                }
            }
        }
        return r.optString("folderUrl");
    }

    // ── 내부 ──

    private static long rangeEnd(String range) {
        // "bytes=0-8388607" → 8388608
        if (range == null) return 0;
        int i = range.lastIndexOf('-');
        return Long.parseLong(range.substring(i + 1).trim()) + 1;
    }

    private static long queryOffset(String session, long total) throws IOException {
        HttpURLConnection c = open(session);
        c.setRequestMethod("PUT");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(0);
        c.setRequestProperty("Content-Range", "bytes */" + total);
        c.getOutputStream().close();
        int code = c.getResponseCode();
        try {
            if (code == 200 || code == 201) return total;
            if (code == 308) return rangeEnd(c.getHeaderField("Range"));
            throw new IOException("이어 올리기 실패 " + code);
        } finally {
            c.disconnect();
        }
    }

    private JSONObject call(JSONObject body) throws IOException {
        HttpURLConnection c = open(scriptUrl);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream os = c.getOutputStream()) { os.write(data); }
        int code = c.getResponseCode();
        String text;
        // Apps Script는 결과를 다른 주소로 넘겨준다(302) → 그 주소를 GET
        for (int hop = 0; code >= 300 && code < 400 && hop < 5; hop++) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (loc == null) throw new IOException("Apps Script 넘김 주소 없음");
            c = open(loc);
            c.setInstanceFollowRedirects(false);
            code = c.getResponseCode();
        }
        if (code != 200) {
            String err = readErr(c);
            throw new IOException("Apps Script 응답 " + code + (code == 401 || code == 403
                    ? " (웹 앱 '액세스 권한: 모든 사용자'로 배포했는지 확인)" : "") + " " + err);
        }
        try (InputStream in = c.getInputStream()) { text = read(in); }
        c.disconnect();
        JSONObject r;
        try {
            r = new JSONObject(text);
        } catch (Exception e) {
            throw new IOException("Apps Script 주소가 맞는지 확인해주세요 (/exec 로 끝나야 해요)");
        }
        if (!r.optBoolean("ok")) throw new IOException(r.optString("error", "알 수 없는 오류"));
        return r;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        return c;
    }

    private static String readErr(HttpURLConnection c) {
        try (InputStream in = c.getErrorStream()) {
            if (in == null) return "";
            String s = read(in);
            return s.length() > 200 ? s.substring(0, 200) : s;
        } catch (IOException e) {
            return "";
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int r;
        while ((r = in.read(b)) > 0) bo.write(b, 0, r);
        return bo.toString("UTF-8");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    /** JSONObject.put 이 예외를 던져서 감싼 것 */
    private static final class JSONObjectBuilder {
        private final JSONObject o = new JSONObject();
        JSONObjectBuilder put(String k, Object v) {
            try { o.put(k, v); } catch (Exception ignored) { }
            return this;
        }
        JSONObject build() { return o; }
    }
}
