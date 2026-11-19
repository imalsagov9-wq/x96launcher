package com.x96.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Минимальный лаунчер: часы и одна строка карточек.
 * Показывает: YouTube, все установленные пользователем приложения, Google Play, Настройки.
 * Без рекомендаций, рекламы, сети и фоновых служб.
 */
public class MainActivity extends Activity {

    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String PLAY = "com.android.vending";
    // приложения, которые не нужно показывать (мост ADB AppControl)
    private static final String[] HIDDEN = { "com.cybercat.acbridge" };

    private static final int BG_TOP = 0xFF12141C;
    private static final int BG_BOTTOM = 0xFF050507;
    private static final int CARD = 0xFF1C1F29;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFF8C92A1;

    private static final class Entry {
        String label;
        Drawable image;
        boolean banner;
        Intent intent;
    }

    private HorizontalScrollView scroll;
    private LinearLayout row;
    private int cardW, cardH, gap, radius, stroke;
    private int focusIndex = 0;
    private boolean dirty = true;
    private boolean resumed = false;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            dirty = true;
            if (resumed) {
                refresh();
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setWindowAnimations(0);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        cardW = (int) (w * 0.2f);
        cardH = cardW * 9 / 16;
        gap = (int) (w * 0.025f);
        radius = (int) (cardW * 0.06f);
        stroke = Math.max(2, (int) (cardW * 0.014f));
        int side = (int) (w * 0.05f);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[] { BG_TOP, BG_BOTTOM }));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.addView(clock("HH:mm", 52, TEXT, Typeface.create("sans-serif-light", Typeface.NORMAL)));
        head.addView(clock("EEEE, d MMMM", 18, TEXT_DIM, Typeface.DEFAULT));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        hp.leftMargin = side;
        hp.topMargin = (int) (h * 0.08f);
        root.addView(head, hp);

        scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);

        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int vpad = (int) (cardH * 0.15f);
        row.setPadding(side, vpad, side, vpad);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        scroll.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL);
        sp.topMargin = (int) (h * 0.12f);
        root.addView(scroll, sp);

        setContentView(root);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
        if (!row.hasFocus()) {
            focusCurrent();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(pkgReceiver);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // нажали «Домой»: вернуться к первой карточке
        focusIndex = 0;
        scroll.scrollTo(0, 0);
        focusCurrent();
    }

    @Override
    public void onBackPressed() {
        // с главного экрана некуда возвращаться
    }

    private TextClock clock(String format, float sp, int color, Typeface tf) {
        TextClock t = new TextClock(this);
        t.setFormat24Hour(format);
        t.setFormat12Hour(format);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        return t;
    }

    private void refresh() {
        if (!dirty) {
            return;
        }
        dirty = false;
        List<Entry> list = buildEntries();
        row.removeAllViews();
        for (Entry e : list) {
            row.addView(makeCard(e));
        }
        focusCurrent();
    }

    private void focusCurrent() {
        int n = row.getChildCount();
        if (n == 0) {
            return;
        }
        int i = Math.max(0, Math.min(focusIndex, n - 1));
        row.getChildAt(i).requestFocus();
    }

    private View makeCard(final Entry e) {
        final FrameLayout card = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
        lp.rightMargin = gap;
        card.setLayoutParams(lp);

        GradientDrawable fill = new GradientDrawable();
        fill.setColor(CARD);
        fill.setCornerRadius(radius);
        card.setBackground(fill);
        card.setClipToOutline(true);

        final GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(radius);
        border.setStroke(stroke, Color.WHITE);

        ImageView img = new ImageView(this);
        img.setImageDrawable(e.image);
        if (e.banner) {
            img.setScaleType(ImageView.ScaleType.FIT_XY);
            card.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            int s = (int) (cardH * 0.5f);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                    s, s, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            ip.topMargin = (int) (cardH * 0.12f);
            card.addView(img, ip);

            TextView t = new TextView(this);
            t.setText(e.label);
            t.setTextColor(TEXT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, cardH * 0.12f);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM);
            tp.bottomMargin = (int) (cardH * 0.08f);
            tp.leftMargin = (int) (cardH * 0.06f);
            tp.rightMargin = (int) (cardH * 0.06f);
            card.addView(t, tp);
        }

        card.setFocusable(true);
        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                float s = has ? 1.1f : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(110).start();
                card.setForeground(has ? border : null);
                if (has) {
                    focusIndex = row.indexOfChild(v);
                }
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launch(e.intent);
            }
        });
        return card;
    }

    private void launch(Intent i) {
        try {
            startActivity(i);
        } catch (Exception ignored) {
            // приложение удалено или отключено: ничего не делаем
        }
    }

    private List<Entry> buildEntries() {
        PackageManager pm = getPackageManager();
        Set<String> seen = new HashSet<>();
        seen.add(getPackageName());
        for (String s : HIDDEN) {
            seen.add(s);
        }
        List<Entry> apps = new ArrayList<>();
        Entry[] pinned = new Entry[2]; // 0 = YouTube, 1 = Google Play
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, seen, apps, pinned);
        collect(pm, Intent.CATEGORY_LAUNCHER, seen, apps, pinned);
        Collections.sort(apps, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        List<Entry> out = new ArrayList<>();
        if (pinned[0] != null) {
            out.add(pinned[0]);
        }
        out.addAll(apps);
        if (pinned[1] != null) {
            out.add(pinned[1]);
        }
        Entry settings = settingsEntry(pm);
        if (settings != null) {
            out.add(settings);
        }
        return out;
    }

    private void collect(PackageManager pm, String cat, Set<String> seen,
                         List<Entry> apps, Entry[] pinned) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
        List<ResolveInfo> found = pm.queryIntentActivities(q, 0);
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (seen.contains(pkg)) {
                continue;
            }
            boolean isYoutube = YOUTUBE.equals(pkg);
            boolean isPlay = PLAY.equals(pkg);
            boolean system = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (system && !isYoutube && !isPlay) {
                continue;
            }
            seen.add(pkg);
            Entry e = entry(pm, ri, cat);
            if (isYoutube) {
                pinned[0] = e;
            } else if (isPlay) {
                pinned[1] = e;
            } else {
                apps.add(e);
            }
        }
    }

    private Entry entry(PackageManager pm, ResolveInfo ri, String cat) {
        Entry e = new Entry();
        e.label = String.valueOf(ri.loadLabel(pm));
        Drawable d = null;
        if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(cat)) {
            d = ri.activityInfo.loadBanner(pm);
            if (d == null) {
                d = ri.activityInfo.applicationInfo.loadBanner(pm);
            }
        }
        e.banner = d != null;
        if (d == null) {
            d = ri.loadIcon(pm);
        }
        e.image = d;
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
        i.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        e.intent = i;
        return e;
    }

    private Entry settingsEntry(PackageManager pm) {
        Intent si = new Intent(Settings.ACTION_SETTINGS);
        si.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ResolveInfo ri = pm.resolveActivity(si, 0);
        if (ri == null) {
            return null;
        }
        Entry e = new Entry();
        e.label = "Настройки";
        e.image = ri.loadIcon(pm);
        e.banner = false;
        e.intent = si;
        return e;
    }
}
