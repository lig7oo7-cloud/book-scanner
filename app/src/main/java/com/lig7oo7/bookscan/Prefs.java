package com.lig7oo7.bookscan;

import android.content.Context;
import android.content.SharedPreferences;

/** 설정값 모음. 기본값은 여기 한 곳에서만 정한다. */
public final class Prefs {
    private Prefs() {}

    public static final int MODE_AUTO = 0, MODE_INTERVAL = 1, MODE_MANUAL = 2;

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("scan", Context.MODE_PRIVATE); }

    // 촬영 방식
    static int mode(Context c) { return sp(c).getInt("mode", MODE_AUTO); }
    static void setMode(Context c, int m) { sp(c).edit().putInt("mode", m).apply(); }

    /** 움직임이 멈춘 뒤 이만큼(ms) 가만히 있으면 찍는다 */
    static int stableMs(Context c) { return sp(c).getInt("stableMs", 800); }
    /** 1(둔감)~10(민감) */
    static int sensitivity(Context c) { return sp(c).getInt("sens", 5); }
    /** 간격 모드 초 */
    static int intervalSec(Context c) { return sp(c).getInt("intervalSec", 4); }
    /** 같은 페이지 판정값: 이보다 차이가 작으면 같은 쪽으로 보고 건너뜀 (0이면 끔) */
    static int dupThreshold(Context c) { return sp(c).getInt("dup", 6); }

    /** 펼친 두 쪽을 왼쪽/오른쪽으로 나눠 저장 */
    static boolean split(Context c) { return sp(c).getBoolean("split", false); }
    /** 나눌 때 오른쪽을 먼저 (세로쓰기·일본식 책) */
    static boolean rightFirst(Context c) { return sp(c).getBoolean("rightFirst", false); }
    /** 추가 회전 0/90/180/270 — 폰을 눕혀 내려찍을 때 방향 보정 */
    static int extraRotation(Context c) { return sp(c).getInt("rot", 0); }

    /** 저장 사진 긴 변 최대 픽셀 */
    static int maxLong(Context c) { return sp(c).getInt("maxLong", 2400); }
    static int jpegQuality(Context c) { return sp(c).getInt("quality", 85); }

    static boolean sound(Context c) { return sp(c).getBoolean("sound", true); }

    static String bookName(Context c) { return sp(c).getString("book", ""); }
    static void setBookName(Context c, String s) { sp(c).edit().putString("book", s).apply(); }
}
