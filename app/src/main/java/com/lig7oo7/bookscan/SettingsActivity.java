package com.lig7oo7.bookscan;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private EditText stable, sens, interval, dup, maxLong, quality;
    private Switch split, rightFirst, sound;
    private RadioGroup rot;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(12), dp(20), dp(40));
        sv.addView(col);

        header(col, "자동 촬영");
        stable = num(col, "멈춤 확인 시간 (초)", "책장에서 손을 떼고 이만큼 가만있으면 찍어요. 흔들린 사진이 많으면 늘리세요.",
                String.valueOf(Prefs.stableMs(this) / 1000f), true);
        sens = num(col, "움직임 민감도 (1~10)", "높을수록 작은 움직임도 '넘김'으로 봐요. 안 찍히면 올리고, 너무 자주 찍히면 내리세요.",
                String.valueOf(Prefs.sensitivity(this)), false);
        dup = num(col, "같은 쪽 판정값 (0=끄기)", "직전 쪽과 차이가 이보다 작으면 건너뜀. 넘겼는데 안 찍히면 낮추고, 같은 쪽이 두 번 찍히면 올리세요.",
                String.valueOf(Prefs.dupThreshold(this)), false);
        interval = num(col, "간격 모드 (초)", "간격 모드일 때 몇 초마다 찍을지", String.valueOf(Prefs.intervalSec(this)), false);

        header(col, "페이지");
        split = sw(col, "펼친 두 쪽 나누기", "책을 펼쳐 두 쪽을 한 번에 찍고 왼쪽·오른쪽으로 저장", Prefs.split(this));
        rightFirst = sw(col, "오른쪽 쪽 먼저", "오른쪽에서 왼쪽으로 읽는 책일 때만", Prefs.rightFirst(this));

        label(col, "추가 회전", "폰을 눕혀서 내려찍으면 사진이 옆으로 누울 수 있어요. 결과가 돌아가 있으면 맞춰주세요.");
        rot = new RadioGroup(this);
        rot.setOrientation(RadioGroup.HORIZONTAL);
        int cur = Prefs.extraRotation(this);
        for (int d : new int[]{0, 90, 180, 270}) {
            RadioButton r = new RadioButton(this);
            r.setText(d + "°");
            r.setId(1000 + d);
            rot.addView(r);
            if (d == cur) r.setChecked(true);
        }
        col.addView(rot);

        header(col, "화질·용량");
        maxLong = num(col, "사진 긴 변 최대 (픽셀)", "2400이면 글씨 인식에 충분. 줄이면 용량↓ (쪽당 약 0.3~0.6MB)",
                String.valueOf(Prefs.maxLong(this)), false);
        quality = num(col, "JPEG 품질 (50~95)", "85 권장", String.valueOf(Prefs.jpegQuality(this)), false);
        sound = sw(col, "셔터 소리", null, Prefs.sound(this));

        Button save = new Button(this);
        save.setText("저장");
        save.setOnClickListener(v -> {
            if (save()) {
                Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(24);
        col.addView(save, lp);

        setContentView(sv);
    }

    private boolean save() {
        try {
            float st = Float.parseFloat(stable.getText().toString().trim());
            int se = Integer.parseInt(sens.getText().toString().trim());
            int du = Integer.parseInt(dup.getText().toString().trim());
            int iv = Integer.parseInt(interval.getText().toString().trim());
            int ml = Integer.parseInt(maxLong.getText().toString().trim());
            int q = Integer.parseInt(quality.getText().toString().trim());
            SharedPreferences.Editor e = Prefs.sp(this).edit();
            e.putInt("stableMs", Math.round(clamp(st, 0.2f, 5f) * 1000));
            e.putInt("sens", (int) clamp(se, 1, 10));
            e.putInt("dup", (int) clamp(du, 0, 50));
            e.putInt("intervalSec", (int) clamp(iv, 1, 60));
            e.putInt("maxLong", (int) clamp(ml, 800, 5000));
            e.putInt("quality", (int) clamp(q, 50, 95));
            e.putBoolean("split", split.isChecked());
            e.putBoolean("rightFirst", rightFirst.isChecked());
            e.putBoolean("sound", sound.isChecked());
            int id = rot.getCheckedRadioButtonId();
            e.putInt("rot", id >= 1000 ? id - 1000 : 0);
            e.apply();
            return true;
        } catch (NumberFormatException ex) {
            Toast.makeText(this, "숫자를 확인해주세요", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void header(LinearLayout col, String t) {
        TextView h = new TextView(this);
        h.setText(t);
        h.setTextSize(18);
        h.setTextColor(0xFF2E7D6B);
        h.setPadding(0, dp(22), 0, dp(4));
        col.addView(h);
    }

    private void label(LinearLayout col, String title, String help) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setPadding(0, dp(12), 0, 0);
        col.addView(t);
        if (help != null) {
            TextView hv = new TextView(this);
            hv.setText(help);
            hv.setTextSize(13);
            hv.setTextColor(0xFF777777);
            col.addView(hv);
        }
    }

    private EditText num(LinearLayout col, String title, String help, String value, boolean decimal) {
        label(col, title, help);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setText(value);
        e.setGravity(Gravity.START);
        col.addView(e, new LinearLayout.LayoutParams(dp(140), -2));
        return e;
    }

    private Switch sw(LinearLayout col, String title, String help, boolean checked) {
        Switch s = new Switch(this);
        s.setText(title);
        s.setTextSize(16);
        s.setChecked(checked);
        s.setPadding(0, dp(12), 0, 0);
        col.addView(s, new LinearLayout.LayoutParams(-1, -2));
        if (help != null) {
            TextView hv = new TextView(this);
            hv.setText(help);
            hv.setTextSize(13);
            hv.setTextColor(0xFF777777);
            col.addView(hv);
        }
        return s;
    }
}
