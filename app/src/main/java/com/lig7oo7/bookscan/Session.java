package com.lig7oo7.bookscan;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 지금 찍고 있는 책 한 권의 페이지들. 앱 내부 저장소(files/pages)에
 * p00001.jpg(스캔) + p00001.txt(인식된 글자) 순서로 쌓인다. 앱을 껐다 켜도 이어서 찍을 수 있다.
 */
public final class Session {
    final File dir;
    private int next;

    Session(Context c) {
        dir = new File(c.getFilesDir(), "pages");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        int max = 0;
        for (File f : list()) max = Math.max(max, index(f));
        next = max + 1;
    }

    private File[] list() {
        File[] fs = dir.listFiles((d, n) -> n.startsWith("p") && n.endsWith(".jpg"));
        if (fs == null) return new File[0];
        Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
        return fs;
    }

    private static int index(File f) {
        try {
            String n = f.getName();
            return Integer.parseInt(n.substring(1, n.length() - 4));
        } catch (Exception e) {
            return 0;
        }
    }

    static File textOf(File jpg) {
        String p = jpg.getPath();
        return new File(p.substring(0, p.length() - 4) + ".txt");
    }

    synchronized List<File> pages() { return new ArrayList<>(Arrays.asList(list())); }

    synchronized int count() { return list().length; }

    synchronized File newFile() {
        return new File(dir, String.format(Locale.US, "p%05d.jpg", next++));
    }

    synchronized File last() {
        File[] fs = list();
        return fs.length == 0 ? null : fs[fs.length - 1];
    }

    /** 쪽 하나(사진+글자) 지우기 */
    static boolean delete(File jpg) {
        //noinspection ResultOfMethodCallIgnored
        textOf(jpg).delete();
        //noinspection ResultOfMethodCallIgnored
        PageFiles.linesOf(jpg).delete();
        return jpg.delete();
    }

    synchronized void clear() {
        File[] all = dir.listFiles();
        if (all != null) for (File f : all) //noinspection ResultOfMethodCallIgnored
            f.delete();
        next = 1;
    }
}
