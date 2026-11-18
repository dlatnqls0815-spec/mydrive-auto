package io.github.k8ecs.ui;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Settings and status screen shared by the head unit app (TCP to the module) and the phone app
 * (Bluetooth to the module). API 19 compatible, no lambdas, no resources. The module validates every change.
 *
 * Layout: header, live board (speed, drive mode, lateral G, the three items), master switch,
 * segmented tabs with toggles and +/- steppers (hold to repeat), save bar, tools.
 */
public final class SettingsPanel {
    /** One request line in, one reply line out. Implementations may block; never called on the UI thread. */
    public interface Transport {
        String call(String line) throws IOException;
        void close();
    }

    // ---------- palette (dark first, follows the system theme) ----------
    private static final class Palette {
        int bg, surface, surface2, line, fg, muted, accent, onAccent, eco, sport, warn, info;
    }

    private static Palette palette(boolean dark) {
        Palette p = new Palette();
        if (dark) {
            // Astryx Neutral (github.com/meta/astryx, MIT): near-white accent, colour only for state.
            p.bg = 0xFF1B1B1B; p.surface = 0xFF262626; p.surface2 = 0xFF3A3A3A; p.line = 0xFF333333;
            p.fg = 0xFFFFFFFF; p.muted = 0xFF9E9E9E; p.accent = 0xFFF1F1F1; p.onAccent = 0xFF111111;
            p.eco = 0xFF97C897; p.sport = 0xFFFF705D; p.warn = 0xFFFFCE2F; p.info = 0xFFA1CAFF;
        } else {
            p.bg = 0xFFF1F1F1; p.surface = 0xFFFFFFFF; p.surface2 = 0xFFE2E2E2; p.line = 0xFFE2E2E2;
            p.fg = 0xFF000000; p.muted = 0xFF474747; p.accent = 0xFF1B1B1B; p.onAccent = 0xFFFFFFFF;
            p.eco = 0xFF2F7D33; p.sport = 0xFFC9303A; p.warn = 0xFFA87512; p.info = 0xFF0067CE;
        }
        return p;
    }

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private static final Typeface CONDENSED = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    private final Activity activity;
    private final Transport transport;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object io = new Object();
    private Palette c;

    private String headerLabel;
    private Runnable headerAction;

    private TextView connDot, connText, message;
    private TextView speedValue, modeChip, footer;
    private MeterRow speedRow, latRow, pedalRow;
    private TextView[] itemState = new TextView[3], itemBadge = new TextView[3];
    private boolean carKnowsEngNormal;
    private Toggle enabled, suspOn, steerOn, engOn, engNormal, suspLinkEng, suspLinkSteer, engLinkSusp, engLinkSteer;
    private Stepper speedUp, hyst, cornerOn, cornerOff, dwell, maxPerMin;
    private Stepper steerSpeedUp, steerHyst, steerCornerOn, steerCornerOff, steerDwell;
    private Stepper engPedal, engHold;
    private final LinearLayout[] tabs = new LinearLayout[3];
    private final TextView[] tabButtons = new TextView[3];
    private TextView saveButton;
    // head unit pages: 0 main (live board, full screen), 1-3 settings, 4 tools
    private final TextView[] pageButtons = new TextView[5];
    private View mainPage, settingsArea, toolsPage;
    private final View[] linkCards = new View[3];
    private TextView statusPill, reasonText, mainMessage, topSave;
    private volatile boolean polling;
    private boolean loaded, filling, dirty, wide, forceDark;
    private volatile boolean saving;
    private String knownCfg, warnedCfg;

    public SettingsPanel(Activity activity, Transport transport) {
        this.activity = activity;
        this.transport = transport;
    }

    /** Optional text button in the header (the phone uses it to pick another car). Call before build(). */
    public void setHeaderAction(String label, Runnable action) {
        headerLabel = label;
        headerAction = action;
    }

    /** Always use the dark palette (the head unit's own screens are dark day and night). Call before build(). */
    public void setDark(boolean dark) {
        forceDark = dark;
    }

    public View build(String title) {
        int night = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        c = palette(forceDark || night != Configuration.UI_MODE_NIGHT_NO);
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        // Head unit (1920x720, 1280x480 dp) and other wide landscape screens get two panes.
        wide = dm.widthPixels > dm.heightPixels && dm.widthPixels / dm.density >= 900;

        enabled = new Toggle();
        for (int k = 0; k < 3; k++) tabs[k] = card();

        suspOn = new Toggle();
        speedUp = new Stepper(0, 200, 5, 20, "km/h");
        hyst = new Stepper(3, 50, 1, 0, "km/h");
        cornerOn = new Stepper(0, 80, 1, 10, "×0.01g");
        cornerOff = new Stepper(1, 80, 1, 0, "×0.01g");
        dwell = new Stepper(1, 60, 1, 0, "초");
        maxPerMin = new Stepper(0, 20, 1, 0, "회");
        suspLinkEng = new Toggle();
        suspLinkSteer = new Toggle();
        tabBody(0, toggleRow(suspOn, "서스펜션 자동 전환", "노멀 ↔ 스포츠 · 핸들 자동과 둘 중 하나", 16), new View[] {
                stepRow(speedUp, "단단하게 바꿀 속도", "0이면 속도 규칙을 쓰지 않습니다"),
                stepRow(hyst, "내려올 때 여유", "위 속도보다 작게"),
                stepRow(cornerOn, "코너 감지 기준", "0이면 코너 규칙 끔"),
                stepRow(cornerOff, "코너 해제 기준", "감지 기준보다 작게"),
                stepRow(dwell, "바꾼 뒤 최소 유지", null),
                stepRow(maxPerMin, "분당 최대 전환", "0이면 제한 없음, 세 항목 각각"),
        }, new View[] {
                toggleRow(suspLinkEng, "엔진도 스포츠로", "서스펜션이 스포츠가 될 때", 15),
                toggleRow(suspLinkSteer, "핸들도 스포츠로", "서스펜션이 스포츠가 될 때", 15),
        });

        steerOn = new Toggle();
        steerSpeedUp = new Stepper(0, 200, 5, 20, "km/h");
        steerHyst = new Stepper(3, 50, 1, 0, "km/h");
        steerCornerOn = new Stepper(0, 80, 1, 10, "×0.01g");
        steerCornerOff = new Stepper(1, 80, 1, 0, "×0.01g");
        steerDwell = new Stepper(1, 60, 1, 0, "초");
        tabBody(1, toggleRow(steerOn, "핸들 자동 전환", "노멀 ↔ 스포츠 · 서스펜션 자동과 둘 중 하나", 16), new View[] {
                stepRow(steerSpeedUp, "스포츠로 바꿀 속도", "0이면 속도 규칙을 쓰지 않습니다"),
                stepRow(steerHyst, "내려올 때 여유", "위 속도보다 작게"),
                stepRow(steerCornerOn, "코너 감지 기준", "0이면 코너 규칙 끔"),
                stepRow(steerCornerOff, "코너 해제 기준", "감지 기준보다 작게"),
                stepRow(steerDwell, "바꾼 뒤 최소 유지", null),
        }, null);

        engOn = new Toggle();
        engNormal = new Toggle();
        engPedal = new Stepper(5, 100, 5, 0, "%");
        engHold = new Stepper(1, 60, 1, 0, "초");
        engLinkSusp = new Toggle();
        engLinkSteer = new Toggle();
        tabBody(2, toggleRow(engOn, "엔진 자동 전환", "에코·노멀 ↔ 스포츠 (파워트레인)", 16), new View[] {
                toggleRow(engNormal, "노멀 ↔ 스포츠 (가솔린 차)", "끄면 에코 ↔ 스포츠 (하이브리드)", 15),
                stepRow(engPedal, "스포츠로 바꿀 엑셀", "이만큼 밟으면 스포츠, 끝까지 = 100"),
                stepRow(engHold, "되돌아갈 시간", "덜 밟은 채 이 시간이 지나면 에코·노멀로"),
        }, new View[] {
                toggleRow(engLinkSusp, "서스펜션도 스포츠로", "엔진이 스포츠가 될 때", 15),
                toggleRow(engLinkSteer, "핸들도 스포츠로", "엔진이 스포츠가 될 때", 15),
        });

        Runnable exclusive = new Runnable() {
            @Override public void run() { applyExclusive(); }
        };
        suspOn.onFlip = exclusive;
        steerOn.onFlip = exclusive;
        suspOn.lockedText = "핸들 자동 전환이 켜져 있어 잠겨 있습니다. 핸들 자동을 끄면 켤 수 있습니다.";
        steerOn.lockedText = "서스펜션 자동 전환이 켜져 있어 잠겨 있습니다. 서스펜션 자동을 끄면 켤 수 있습니다.";
        saveButton = button("저장", true);
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        message = text("", 14, c.muted, null);
        View toolCard = tools();
        TextView note = text("차가 따르지 않는 항목은 스스로 멈춥니다. 사용 책임은 운전자에게 있습니다.", 12, c.muted, null);

        View screen;
        if (wide) {
            screen = wideLayout(toolCard, note);
            showPage(0);
        } else {
            View head = header(title);
            View live = liveBoard();
            LinearLayout master = card();
            master.addView(toggleRow(enabled, "자동 전환", "마이 드라이브 모드로 주행할 때만 바꿉니다", 17));
            View seg = segmented();
            screen = onePane(head, live, master, seg, toolCard, note);
            showTab(0);
        }
        speedRow.bind(speedUp, 1);
        latRow.bind(cornerOn, 10);
        pedalRow.bind(engPedal, 1);
        return screen;
    }

    private View onePane(View head, View live, View master, View seg, View toolCard, TextView note) {
        LinearLayout root = column(dp(14));
        root.setPadding(dp(16), dp(12), dp(16), dp(24));
        root.setBackgroundColor(c.bg);
        root.addView(head);
        root.addView(live);
        root.addView(master);
        root.addView(seg);
        for (int k = 0; k < 3; k++) root.addView(tabs[k]);
        root.addView(saveButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        message.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(message);
        root.addView(toolCard);
        note.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(note);
        return scroll(root, true);
    }

    /**
     * Head unit: a tab bar on top (main, the three items, tools) with connection, drive mode and the master
     * switch; the main tab is the live board over the whole screen, the item tabs put the settings on the left
     * and the links and the save card on the right.
     */
    private View wideLayout(View toolCard, TextView note) {
        LinearLayout root = column(dp(12));
        root.setBackgroundColor(c.bg);
        root.setFitsSystemWindows(true);
        root.setPadding(dp(16), dp(10), dp(16), dp(12));
        root.addView(topBar());

        FrameLayout body = new FrameLayout(activity);
        mainPage = mainPage();
        body.addView(mainPage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout area = new LinearLayout(activity);
        LinearLayout holder = new LinearLayout(activity);
        holder.setOrientation(LinearLayout.VERTICAL);
        for (int k = 0; k < 3; k++) holder.addView(tabs[k]);
        area.addView(scroll(holder, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        // The link cards scroll in the space left over; the save card is pinned to the bottom with a fixed
        // height, so a longer message can never push the buttons off the screen (seen on the car, 0.4.0).
        LinearLayout side = column(dp(12));
        LinearLayout links = new LinearLayout(activity);
        links.setOrientation(LinearLayout.VERTICAL);
        for (int k = 0; k < 3; k++) links.addView(linkCards[k]);
        side.addView(scroll(links, false), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        side.addView(saveCard());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.MATCH_PARENT);
        slp.leftMargin = dp(12);
        area.addView(side, slp);
        settingsArea = area;
        body.addView(area, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout tp = new LinearLayout(activity);
        tp.addView(toolCard, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout info = card();
        info.addView(sectionLabel("상태"));
        footer = text("", 14, c.muted, null);
        footer.setLineSpacing(0, 1.2f);
        info.addView(footer);
        info.addView(note);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.leftMargin = dp(12);
        tp.addView(info, ilp);
        toolsPage = scroll(tp, false);
        body.addView(toolsPage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private View topBar() {
        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout seg = new LinearLayout(activity);
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        seg.setBackgroundDrawable(round(c.line, dp(12)));
        String[] names = {"메인", "서스펜션", "핸들", "엔진", "도구"};
        for (int p = 0; p < 5; p++) {
            final int which = p;
            TextView b = text(names[p], 16, c.muted, MEDIUM);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(20), 0, dp(20), 0);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showPage(which); }
            });
            pageButtons[p] = b;
            seg.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        }
        bar.addView(seg);

        // Revert and save next to the tabs, so a change made on any tab (also a gauge handle on the main tab)
        // can be saved at once (user, 2026-11-17).
        TextView revert = button("되돌리기", false);
        revert.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { load("차에 저장된 설정으로 되돌렸습니다."); }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(104), dp(46));
        rlp.leftMargin = dp(12);
        bar.addView(revert, rlp);
        topSave = button("저장", true);
        topSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(dp(120), dp(46));
        slp2.leftMargin = dp(8);
        bar.addView(topSave, slp2);
        bar.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));

        connDot = new TextView(activity);
        connDot.setBackgroundDrawable(round(c.muted, dp(4)));
        bar.addView(connDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        connText = text("모듈 연결 중…", 14, c.muted, null);
        connText.setPadding(dp(7), 0, dp(14), 0);
        bar.addView(connText);
        modeChip = chip("모드 –", c.muted);
        modeChip.setTextSize(14);
        modeChip.setPadding(dp(12), dp(6), dp(12), dp(6));
        bar.addView(modeChip);

        LinearLayout pill = new LinearLayout(activity);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(16), dp(6), dp(6), dp(6));
        GradientDrawable pd = round(0x00000000, dp(24));
        pd.setStroke(dp(1), c.line);
        pill.setBackgroundDrawable(pd);
        pill.addView(text("자동 전환", 16, c.fg, MEDIUM));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(52), dp(30));
        tl.leftMargin = dp(12);
        pill.addView(enabled, tl);
        pill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { enabled.flip(); }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        plp.leftMargin = dp(12);
        bar.addView(pill, plp);
        return bar;
    }

    /** Main tab: speed with the reason it is in sport (left), the three gauges and the item tiles (right). */
    private View mainPage() {
        LinearLayout page = new LinearLayout(activity);
        LinearLayout sc = card();
        sc.setPadding(dp(20), dp(18), dp(20), dp(18));
        sc.addView(text("현재 속도", 14, c.muted, MEDIUM));
        speedValue = text("–", 84, c.fg, BOLD);
        speedValue.setIncludeFontPadding(false);
        sc.addView(speedValue);
        sc.addView(text("km/h", 18, c.muted, null));
        sc.addView(new View(activity), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        statusPill = chip("연결 중", c.muted);
        statusPill.setTextSize(16);
        statusPill.setTypeface(BOLD);
        statusPill.setPadding(dp(14), dp(7), dp(14), dp(7));
        sc.addView(statusPill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Fixed line counts so a long reason or message can never push text out of the card (cut text on the
        // car, 2026-11-17); save and revert moved to the top bar.
        reasonText = text("", 14, c.muted, null);
        reasonText.setLineSpacing(0, 1.15f);
        reasonText.setMaxLines(3);
        reasonText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams rtl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rtl.topMargin = dp(8);
        sc.addView(reasonText, rtl);
        mainMessage = text("", 13, c.info, null);
        mainMessage.setMaxLines(2);
        mainMessage.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mainMessage.setVisibility(View.GONE);
        LinearLayout.LayoutParams mml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mml.topMargin = dp(6);
        sc.addView(mainMessage, mml);
        page.addView(sc, new LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout right = column(dp(12));
        LinearLayout gauges = card();
        gauges.setPadding(dp(22), dp(14), dp(22), dp(14));
        speedRow = new MeterRow("스포츠 전환 속도", false);
        latRow = new MeterRow("횡G", true);
        pedalRow = new MeterRow("엑셀", true);
        for (MeterRow r : new MeterRow[] {speedRow, latRow, pedalRow}) {
            r.view.setGravity(Gravity.CENTER_VERTICAL);
            gauges.addView(r.view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        right.addView(gauges, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout items = new LinearLayout(activity);
        String[] names = {"서스펜션", "핸들", "엔진"};
        for (int k = 0; k < 3; k++) {
            LinearLayout tile = new LinearLayout(activity);
            tile.setGravity(Gravity.CENTER_VERTICAL);
            tile.setPadding(dp(16), dp(10), dp(14), dp(10));
            GradientDrawable d = round(c.surface, dp(12));
            d.setStroke(dp(1), c.line);
            tile.setBackgroundDrawable(d);
            LinearLayout labels = column(0);
            labels.addView(text(names[k], 13, c.muted, MEDIUM));
            itemState[k] = text("–", 22, c.fg, BOLD);
            itemState[k].setSingleLine(true);
            labels.addView(itemState[k]);
            tile.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            itemBadge[k] = text("–", 13, c.muted, null);
            itemBadge[k].setPadding(dp(8), dp(3), dp(8), dp(3));
            GradientDrawable bd = round(0x00000000, dp(6));
            bd.setStroke(dp(1), c.line);
            itemBadge[k].setBackgroundDrawable(bd);
            tile.addView(itemBadge[k]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (k > 0) lp.leftMargin = dp(12);
            items.addView(tile, lp);
        }
        right.addView(items);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        rp.leftMargin = dp(12);
        page.addView(right, rp);
        return page;
    }

    private View saveCard() {
        LinearLayout card = card();
        message.setLineSpacing(0, 1.15f);
        message.setTextSize(13);
        message.setMinLines(2);
        message.setMaxLines(2);
        message.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(message);
        LinearLayout row = new LinearLayout(activity);
        TextView reload = button("되돌리기", false);
        reload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { load("차에 저장된 설정으로 되돌렸습니다."); }
        });
        row.addView(reload, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, dp(48), 1.6f);
        sl.leftMargin = dp(8);
        row.addView(saveButton, sl);
        card.addView(row);
        return card;
    }

    /** Right-hand card of an item tab on the head unit: the link switches and a short note. */
    private View linkCard(int k, View[] links) {
        LinearLayout card = card();
        card.addView(sectionLabel(links != null ? "같이 바꾸기" : "안내"));
        if (links != null) for (View v : links) card.addView(v);
        String note = k == 0 ? "서스펜션 자동과 핸들 자동은 둘 중 하나만 켜집니다. 연동은 그대로 됩니다."
                : k == 1 ? "핸들 자동과 서스펜션 자동은 둘 중 하나만 켜집니다. 서스펜션·엔진 탭의 연동으로 핸들을 같이 바꿀 수 있습니다."
                : "엑셀을 기준보다 깊게 밟으면 스포츠, 덜 밟은 채 시간이 지나면 에코(가솔린 차는 노멀)로 돌아갑니다.";
        TextView t = text(note, 13, c.muted, null);
        t.setLineSpacing(0, 1.15f);
        card.addView(t);
        return card;
    }

    private ScrollView scroll(View content, boolean fitWindows) {
        ScrollView s = new ScrollView(activity);
        s.setFitsSystemWindows(fitWindows);
        s.setBackgroundColor(c.bg);
        s.setFillViewport(true);
        s.setVerticalScrollBarEnabled(false);
        s.addView(content);
        return s;
    }

    /** Main toggle, then the steppers (one column, or two side by side on wide screens), then the link toggles. */
    private void tabBody(int k, View main, View[] steps, View[] links) {
        LinearLayout t = tabs[k];
        t.addView(main);
        if (wide) {
            linkCards[k] = linkCard(k, links);
            links = null;
        }
        t.addView(divider());
        if (!wide) {
            for (View v : steps) t.addView(v);
        } else {
            LinearLayout grid = new LinearLayout(activity);
            LinearLayout l = column(dp(14)), r = column(dp(14));
            int half = (steps.length + 1) / 2;
            for (int i = 0; i < steps.length; i++) (i < half ? l : r).addView(steps[i]);
            grid.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            rp.leftMargin = dp(28);
            grid.addView(r, rp);
            t.addView(grid);
        }
        if (links == null) return;
        t.addView(sectionLabel("함께 바꾸기"));
        if (!wide) {
            for (View v : links) t.addView(v);
        } else {
            LinearLayout row = new LinearLayout(activity);
            for (int i = 0; i < links.length; i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                if (i > 0) lp.leftMargin = dp(28);
                row.addView(links[i], lp);
            }
            t.addView(row);
        }
    }

    // ---------- sections ----------

    private View header(String title) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = column(dp(2));
        left.addView(text(title, 22, c.fg, MEDIUM));
        LinearLayout conn = new LinearLayout(activity);
        conn.setGravity(Gravity.CENTER_VERTICAL);
        connDot = new TextView(activity);
        connDot.setBackgroundDrawable(round(c.muted, dp(4)));
        conn.addView(connDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        connText = text("모듈 연결 중…", 13, c.muted, null);
        connText.setPadding(dp(6), 0, 0, 0);
        conn.addView(connText);
        left.addView(conn);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (headerLabel != null) {
            TextView b = text(headerLabel, 14, c.accent, MEDIUM);
            b.setPadding(dp(12), dp(8), dp(12), dp(8));
            b.setBackgroundDrawable(pressable(0x00000000, c.surface2, dp(18)));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { headerAction.run(); }
            });
            row.addView(b);
        }
        return row;
    }

    private View liveBoard() {
        LinearLayout card = card();

        // drive mode
        LinearLayout modeLine = new LinearLayout(activity);
        modeLine.setGravity(Gravity.CENTER_VERTICAL);
        modeLine.addView(text("드라이브 모드", 15, c.muted, null), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        modeChip = chip("모드 –", c.muted);
        if (!wide) {  // on the head unit the chip sits in the header to save a line
            modeLine.addView(modeChip);
            card.addView(modeLine);
        }

        // speed: big number, and next to it the bar up to the speed that switches to sport
        LinearLayout speedLine = new LinearLayout(activity);
        speedLine.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout num = new LinearLayout(activity);
        num.setGravity(Gravity.BOTTOM);
        speedValue = text("–", 52, c.fg, BOLD);
        speedValue.setIncludeFontPadding(false);
        num.addView(speedValue);
        TextView unit = text("km/h", 16, c.muted, null);
        unit.setPadding(dp(6), 0, 0, dp(8));
        num.addView(unit);
        speedLine.addView(num);
        card.addView(speedLine);
        // full width under the number, so the caption and the value fit on a phone
        speedRow = new MeterRow("스포츠 전환 속도", false);
        card.addView(speedRow.view);

        LinearLayout why = column(dp(4));
        statusPill = chip("연결 중", c.muted);
        statusPill.setTypeface(BOLD);
        why.addView(statusPill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        reasonText = text("", 13, c.muted, null);
        why.addView(reasonText);
        card.addView(why);

        latRow = new MeterRow("횡G", true);
        card.addView(latRow.view);
        pedalRow = new MeterRow("엑셀", true);
        card.addView(pedalRow.view);

        // three items
        LinearLayout items = new LinearLayout(activity);
        items.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"서스펜션", "핸들", "엔진"};
        for (int k = 0; k < 3; k++) {
            LinearLayout tile = column(dp(4));
            int tp = dp(wide ? 8 : 10);
            tile.setPadding(dp(10), tp, dp(10), tp);
            tile.setBackgroundDrawable(round(c.surface2, dp(10)));
            tile.addView(text(names[k], 12, c.muted, null));
            itemState[k] = text("–", wide ? 16 : 18, c.fg, MEDIUM);
            itemState[k].setSingleLine(true);
            itemState[k].setEllipsize(TextUtils.TruncateAt.END);
            tile.addView(itemState[k]);
            itemBadge[k] = text("–", 12, c.muted, null);
            tile.addView(itemBadge[k]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (k > 0) lp.leftMargin = dp(8);
            items.addView(tile, lp);
        }
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = dp(4);
        card.addView(items, ip);

        footer = text("", 12, c.muted, null);
        card.addView(footer);
        return card;
    }

    private View segmented() {
        LinearLayout seg = new LinearLayout(activity);
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        seg.setBackgroundDrawable(round(c.surface2, dp(12)));
        String[] names = {"서스펜션", "핸들", "엔진"};
        for (int k = 0; k < 3; k++) {
            final int which = k;
            TextView b = text(names[k], 15, c.muted, MEDIUM);
            b.setGravity(Gravity.CENTER);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showTab(which); }
            });
            tabButtons[k] = b;
            seg.addView(b, new LinearLayout.LayoutParams(0, dp(40), 1));
        }
        return seg;
    }

    private View tools() {
        LinearLayout card = card();
        card.addView(sectionLabel("도구"));
        card.addView(toolRow("설정 다시 불러오기", "차에 저장된 값으로 되돌립니다", new Runnable() {
            @Override public void run() { load(); }
        }));
        card.addView(toolRow("정지 해제", "멈춘 항목의 자동 전환을 다시 시작합니다", new Runnable() {
            @Override public void run() { simple("RESUME", "자동 전환을 다시 시작했습니다."); }
        }));
        card.addView(toolRow("센서 보정 초기화", "센서 보정을 처음부터 다시 배웁니다", new Runnable() {
            @Override public void run() { simple("RESETCAL", "센서 보정을 처음부터 다시 배웁니다."); }
        }));
        card.addView(toolRow("USB로 로그 저장", "헤드유닛에 꽂힌 USB에 기록을 저장합니다", new Runnable() {
            @Override public void run() { exportUsb(); }
        }));
        return card;
    }

    // ---------- lifecycle ----------

    static final int POLL_MS = 200;

    public void onResume() {
        polling = true;
        if (!loaded) load();
        Thread poll = new Thread(new Runnable() {
            @Override public void run() {
                // 0.2 s (user, 2026-11-17; was 1 s). The car loop reads speed and pedal every 0.1 s.
                // On a failed request wait 1 s so a missing car module is not hammered.
                while (polling) {
                    long t0 = android.os.SystemClock.uptimeMillis();
                    final String st = request("STATUS");
                    ui.post(new Runnable() { @Override public void run() { showStatus(st); } });
                    long wait = (st == null ? 1000 : POLL_MS) - (android.os.SystemClock.uptimeMillis() - t0);
                    if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
                }
            }
        }, "status");
        poll.setDaemon(true);
        poll.start();
    }

    public void onPause() {
        polling = false;
    }

    public void onDestroy() {
        polling = false;
        transport.close();
    }

    private String request(String line) {
        synchronized (io) {
            try {
                return transport.call(line);
            } catch (IOException e) {
                transport.close();
                return null;
            }
        }
    }

    private void load() {
        load("차에 저장된 설정을 불러왔습니다.");
    }

    private void load(final String okText) {
        background(new Runnable() {
            @Override public void run() {
                final String reply = request("GET");
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (reply == null || !reply.startsWith("CFG ")) {
                            showMessage("설정을 불러오지 못했습니다. 차에 연결됐는지 확인하세요.", c.warn);
                            return;
                        }
                        knownCfg = cfgId(reply.substring(4));
                        fill(pairs(reply.substring(4)));
                        loaded = true;
                        showMessage(okText, c.muted);
                    }
                });
            }
        });
    }

    private void save() {
        if (!loaded) {
            showMessage("차의 설정을 먼저 불러와야 저장할 수 있습니다. 연결되면 자동으로 불러옵니다.", c.warn);
            load();
            return;
        }
        final String line = "SET enabled=" + enabled.bit() + " suspOn=" + suspOn.bit()
                + " speedUp=" + speedUp.value + " hyst=" + hyst.value
                + " cornerOn=" + cornerOn.value * 10 + " cornerOff=" + cornerOff.value * 10
                + " dwell=" + dwell.value * 1000 + " maxPerMin=" + maxPerMin.value
                + " steerOn=" + steerOn.bit() + " steerSpeedUp=" + steerSpeedUp.value
                + " steerHyst=" + steerHyst.value + " steerCornerOn=" + steerCornerOn.value * 10
                + " steerCornerOff=" + steerCornerOff.value * 10 + " steerDwell=" + steerDwell.value * 1000
                + " engOn=" + engOn.bit() + " engPedal=" + engPedal.value
                + " engHold=" + engHold.value * 1000
                // Older car modules (0.4.0 and before) reject unknown keys: send it only when they know it or it is on.
                + (carKnowsEngNormal || engNormal.on ? " engNormal=" + engNormal.bit() : "")
                + " suspLinkEng=" + suspLinkEng.bit() + " suspLinkSteer=" + suspLinkSteer.bit()
                + " engLinkSusp=" + engLinkSusp.bit() + " engLinkSteer=" + engLinkSteer.bit();
        saveButton.setText("저장 중…");
        if (topSave != null) topSave.setText("저장 중…");
        saving = true;
        background(new Runnable() {
            @Override public void run() {
                final String reply = request(line);
                ui.post(new Runnable() {
                    @Override public void run() {
                        saving = false;
                        if (reply != null && reply.startsWith("OK ")) {
                            knownCfg = cfgId(reply.substring(3));
                            fill(pairs(reply.substring(3)));
                            showMessage("저장되었습니다.", c.eco);
                            android.widget.Toast.makeText(activity, "저장되었습니다", android.widget.Toast.LENGTH_SHORT).show();
                        } else {
                            setDirty(true);
                            String why = "저장하지 못했습니다: " + (reply == null ? "차에 연결되지 않았습니다." : describeError(reply));
                            showMessage(why, c.sport);
                            android.widget.Toast.makeText(activity, why, android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    private void simple(final String command, final String okText) {
        background(new Runnable() {
            @Override public void run() {
                final String reply = request(command);
                ui.post(new Runnable() {
                    @Override public void run() {
                        boolean ok = reply != null && reply.startsWith("OK");
                        showMessage(ok ? okText : "실패: " + (reply == null ? "차에 연결되지 않았습니다." : reply), ok ? c.muted : c.sport);
                    }
                });
            }
        });
    }

    private void exportUsb() {
        showMessage("USB에 로그를 저장하는 중…", c.muted);
        background(new Runnable() {
            @Override public void run() {
                final String reply = request("EXPORTUSB");
                ui.post(new Runnable() {
                    @Override public void run() {
                        showMessage(describeExport(reply), reply != null && reply.startsWith("OK ") ? c.eco : c.warn);
                    }
                });
            }
        });
    }

    static String describeExport(String reply) {
        if (reply == null) return "USB 저장 실패: 모듈에 연결되지 않았습니다.";
        if (reply.startsWith("OK ")) {
            String folder = reply.substring(3).trim();
            int sp = folder.indexOf(' ');
            if (sp > 0) folder = folder.substring(0, sp);
            return "USB에 저장했습니다: " + folder + " 폴더. 이제 USB를 빼도 됩니다.";
        }
        if (reply.contains("no_usb")) return "USB가 꽂혀 있지 않습니다. USB를 꽂고 잠시 뒤 다시 누르세요.";
        if (reply.contains("usb_not_writable")) return "USB에 쓸 수 없습니다. 다른 USB(FAT32)로 해 보세요.";
        if (reply.contains("no_logs")) return "아직 저장할 기록이 없습니다.";
        if (reply.contains("unknown command")) return "모듈이 이전 버전입니다. 0.1.4 이상으로 업데이트하세요.";
        return "USB 저장 실패: " + reply;
    }

    static String describeError(String reply) {
        if (reply.contains("speedUp")) return "속도는 20~200 또는 0이어야 합니다.";
        if (reply.contains("corner")) return "코너 기준은 0 또는 10 이상이고, 해제 기준이 감지 기준보다 작아야 합니다(서스펜션·핸들).";
        if (reply.contains("rate")) return "유지 시간은 1초 이상, 분당 횟수는 0(제한 없음)~20입니다.";
        if (reply.contains("below steerSpeedUp")) return "핸들 여유는 스포츠로 바꿀 속도보다 작아야 합니다.";
        if (reply.contains("below speedUp")) return "여유는 단단하게 바꿀 속도보다 작아야 합니다.";
        if (reply.contains("engPedal")) return "엑셀 기준은 5~100% 입니다.";
        if (reply.contains("engHold")) return "엔진 유지 시간은 1~60초입니다.";
        if (reply.contains("unknown key: engNormal")) return "노멀 선택은 차량 모듈 0.4.1부터 됩니다. 차량 쪽을 먼저 올려 주세요.";
        if (reply.contains("speedHysteresis")) return "여유는 3 이상이어야 합니다.";
        return reply;
    }

    private void fill(Map<String, String> m) {
        filling = true;
        enabled.set("1".equals(m.get("enabled")));
        suspOn.set("1".equals(m.get("suspOn")));
        steerOn.set("1".equals(m.get("steerOn")));
        engOn.set("1".equals(m.get("engOn")));
        carKnowsEngNormal = m.containsKey("engNormal");
        engNormal.set("1".equals(m.get("engNormal")));
        suspLinkEng.set("1".equals(m.get("suspLinkEng")));
        suspLinkSteer.set("1".equals(m.get("suspLinkSteer")));
        engLinkSusp.set("1".equals(m.get("engLinkSusp")));
        engLinkSteer.set("1".equals(m.get("engLinkSteer")));
        speedUp.set(parse(m.get("speedUp")));
        hyst.set(parse(m.get("hyst")));
        cornerOn.set(parse(m.get("cornerOn")) / 10);
        cornerOff.set(parse(m.get("cornerOff")) / 10);
        dwell.set(parse(m.get("dwell")) / 1000);
        maxPerMin.set(parse(m.get("maxPerMin")));
        steerSpeedUp.set(parse(m.get("steerSpeedUp")));
        steerHyst.set(parse(m.get("steerHyst")));
        steerCornerOn.set(parse(m.get("steerCornerOn")) / 10);
        steerCornerOff.set(parse(m.get("steerCornerOff")) / 10);
        steerDwell.set(parse(m.get("steerDwell")) / 1000);
        engPedal.set(parse(m.get("engPedal")));
        engHold.set(parse(m.get("engHold")) / 1000);
        applyExclusive();
        filling = false;
        setDirty(false);
    }

    /** Suspension auto and steering auto are one or the other; the one that is off is locked. Links still work. */
    private void applyExclusive() {
        if (suspOn.on && steerOn.on) steerOn.set(false);
        suspOn.setLocked(steerOn.on);
        steerOn.setLocked(suspOn.on);
    }

    private void changed() {
        if (!filling) setDirty(true);
    }

    private void setDirty(boolean d) {
        dirty = d;
        if (saveButton != null) saveButton.setText(d ? "변경 내용 저장" : "저장");
        if (topSave != null) topSave.setText(d ? "변경 저장" : "저장");
    }

    private void showMessage(String text, int color) {
        message.setText(text);
        message.setTextColor(color);
        if (mainMessage != null) {
            mainMessage.setText(text);
            mainMessage.setTextColor(color);
            mainMessage.setVisibility(dirty || color != c.muted ? View.VISIBLE : View.GONE);
        }
    }

    private void showStatus(String st) {
        if (st == null || !st.startsWith("ST ")) {
            connDot.setBackgroundDrawable(round(c.sport, dp(4)));
            connText.setText("차에 연결되지 않음 · 다시 시도 중");
            if (statusPill != null) {
                styleChip(statusPill, "연결 안 됨", c.muted);
                reasonText.setText("모듈에 연결하는 중입니다.");
            }
            return;
        }
        Map<String, String> m = pairs(st.substring(3));
        followCfg(m.get("cfg"));
        boolean on = "1".equals(m.get("enabled"));
        connDot.setBackgroundDrawable(round(on ? c.eco : c.warn, dp(4)));
        connText.setText(wide ? "연결됨 · " + m.get("ver")
                : "연결됨 · " + (on ? "자동 전환 켜짐" : "자동 전환 꺼짐") + " · v" + m.get("ver"));

        speedValue.setText(String.valueOf(m.get("kmh")));
        String mode = m.get("mode");
        boolean custom = "1".equals(m.get("custom"));
        String modeName = "2".equals(mode) ? "에코" : "3".equals(mode) ? "스포츠"
                : "6".equals(mode) ? (custom ? "마이 드라이브" : "스마트")
                : "?".equals(mode) || mode == null ? "모드 모름" : "커스텀 " + mode;
        int modeColor = "2".equals(mode) ? c.eco : "3".equals(mode) ? c.sport : "6".equals(mode) && custom ? c.info : c.muted;
        styleChip(modeChip, modeName, modeColor);

        int kmh = parse(m.get("kmh"));
        int up = loaded ? speedUp.value : 0;
        speedRow.set(kmh, Math.max(160, up * 7 / 5), up, -1,
                up > 0 ? kmh + " / " + up + " km/h" : kmh + " km/h · 속도 규칙 꺼짐");
        int lat = Math.abs(parse(m.get("lat"))), peak = Math.abs(parse(m.get("peak")));
        int gOn = loaded ? cornerOn.value * 10 : 0;
        latRow.set(lat, Math.max(600, gOn * 8 / 5), gOn, peak,
                g(Integer.toString(lat)) + (gOn > 0 ? " / " + g(Integer.toString(gOn)) : " · 코너 규칙 꺼짐")
                        + "   최고 " + g(Integer.toString(peak)));
        String pedal = m.get("pedal");
        int pd = loaded ? engPedal.value : 0;
        if ("?".equals(pedal) || pedal == null) pedalRow.set(0, 100, pd, -1, "읽을 수 없음");
        else pedalRow.set(parse(pedal), 100, pd, -1, pedal + " / " + pd + " %");

        String step = m.get("step");
        item(0, "soft".equals(step) ? "노멀" : "firm".equals(step) ? "스포츠" : "–", "firm".equals(step),
                m, "suspOn", "S", "1".equals(m.get("corner")) ? "코너" : null);
        String steer = m.get("steer");
        item(1, "normal".equals(steer) ? "노멀" : "sport".equals(steer) ? "스포츠" : "–", "sport".equals(steer),
                m, "steerOn", "H", null);
        String eng = m.get("eng");
        item(2, "eco".equals(eng) ? "에코" : "normal".equals(eng) ? "노멀" : "sport".equals(eng) ? "스포츠" : "–", "sport".equals(eng),
                m, "engOn", "E", null);

        explain(m, on, "6".equals(mode) && custom, modeName, kmh, up, pd, step, steer, eng);

        String latcal = m.get("latcal");
        String calib = latcal == null ? ("learning".equals(m.get("gyroScale")) ? "학습 중 (회전 " + m.get("turns") + "/3)" : "완료")
                : "ok".equals(latcal) ? "완료" : "wait".equals(latcal) ? "정차하면 시작"
                : "방향 학습 " + latcal.replace("est", "") + "%";
        String last = String.valueOf(m.get("last")).replace('_', ' ').replace("speed ", "속도 ")
                .replace("corner ", "코너 ").replace("pedal ", "엑셀 ").replace("link ", "연동 ")
                .replace("stopped", "정차");
        footer.setText("전환 " + m.get("sent") + "회" + ("-".equals(last) || "null".equals(last) ? "" : " · 마지막 " + last)
                + " · 센서 보정 " + calib + ("ok".equals(m.get("imu")) ? "" : " · 센서 " + m.get("imu"))
                + (m.get("profile") != null ? " · 차종 프로필 " + m.get("profile") : ""));
    }

    /** The pill under the speed (driving state) and one line saying why. */
    private void explain(Map<String, String> m, boolean on, boolean myDrive, String modeName, int kmh, int up, int pd,
                         String step, String steer, String eng) {
        if (statusPill == null) return;
        String pill, why;
        int color;
        boolean s0 = "firm".equals(step), s1 = "sport".equals(steer), s2 = "sport".equals(eng);
        if (!on) {
            pill = "자동 전환 꺼짐"; color = c.muted;
            why = "자동 전환을 켜면 마이 드라이브 모드에서 바꿉니다.";
        } else if (!myDrive) {
            pill = "대기 중"; color = c.muted;
            why = "마이 드라이브 모드에서만 바꿉니다. 지금은 " + modeName + " 모드입니다.";
        } else if (s0 || s1 || s2) {
            pill = "스포츠 주행 중"; color = c.sport;
            StringBuilder cause = new StringBuilder();
            if ("1".equals(m.get("corner"))) cause.append("코너를 감지해");
            else if (up > 0 && kmh >= up) cause.append("속도 ").append(up).append(" km/h를 넘어");
            int pedal = parse(m.get("pedal"));
            if (pd > 0 && pedal >= pd) {
                if (cause.length() > 0) cause.append(", ");
                cause.append("엑셀을 ").append(pd).append("% 넘게 밟아");
            }
            StringBuilder items = new StringBuilder();
            if (s0) items.append("서스펜션");
            if (s1) items.append(items.length() > 0 ? "·" : "").append("핸들");
            if (s2) items.append(items.length() > 0 ? "·" : "").append("엔진");
            why = cause.length() > 0 ? cause + " " + items + "이 스포츠로 바뀌었습니다"
                    : items + "은 기준 아래로 내려왔고, 최소 유지 시간이 지나면 돌아갑니다";
        } else if (kmh == 0) {
            pill = "정차"; color = c.eco;
            why = "정차 중이라 노멀·에코로 돌아와 있습니다.";
        } else {
            pill = "노멀 주행 중"; color = c.eco;
            why = "게이지가 기준 손잡이를 넘으면 스포츠로 바뀝니다.";
        }
        styleChip(statusPill, "● " + pill, color);
        reasonText.setText(why);
    }

    /**
     * Settings changed elsewhere (phone, head unit app, Seogaemo card) show up here within a second.
     * Unsaved edits on this screen are kept; the user is told that saving will overwrite the other change.
     */
    private void followCfg(String cfg) {
        if (cfg == null || !loaded || saving || cfg.equals(knownCfg)) return;
        if (dirty) {
            if (!cfg.equals(warnedCfg)) {
                warnedCfg = cfg;
                showMessage("다른 곳에서 설정이 바뀌었습니다. 지금 저장하면 이 화면의 값으로 덮어씁니다.", c.warn);
            }
            return;
        }
        knownCfg = cfg;
        load("다른 곳에서 바꾼 설정을 반영했습니다.");
    }

    /** Same fingerprint as the module's status field cfg (CarMain.cfgId). */
    static String cfgId(String encoded) {
        return Integer.toHexString(encoded.hashCode());
    }

    private void item(int k, String state, boolean sport, Map<String, String> m, String key, String haltLetter, String extra) {
        itemState[k].setText(state);
        itemState[k].setTextColor("–".equals(state) ? c.muted : sport ? c.sport : c.eco);
        String badge;
        int color;
        if (String.valueOf(m.get("haltedItems")).contains(haltLetter)) { badge = "정지됨"; color = c.warn; }
        else if ("1".equals(m.get(key))) { badge = "자동"; color = wide ? c.muted : c.info; }
        else { badge = "수동"; color = c.muted; }
        itemBadge[k].setText(extra == null ? badge : badge + " · " + extra);
        itemBadge[k].setTextColor(color);
    }

    static String onText(Map<String, String> m, String key, String haltLetter) {
        if (String.valueOf(m.get("haltedItems")).contains(haltLetter)) return "  ※ 정지됨(차가 따르지 않음)";
        return "1".equals(m.get(key)) ? "  [자동]" : "  [수동]";
    }

    private void showPage(int p) {
        for (int i = 0; i < 5; i++) {
            boolean sel = i == p;
            pageButtons[i].setTextColor(sel ? c.fg : c.muted);
            pageButtons[i].setBackgroundDrawable(sel ? round(c.surface, dp(9)) : null);
        }
        mainPage.setVisibility(p == 0 ? View.VISIBLE : View.GONE);
        settingsArea.setVisibility(p >= 1 && p <= 3 ? View.VISIBLE : View.GONE);
        toolsPage.setVisibility(p == 4 ? View.VISIBLE : View.GONE);
        for (int k = 0; k < 3; k++) {
            tabs[k].setVisibility(k == p - 1 ? View.VISIBLE : View.GONE);
            linkCards[k].setVisibility(k == p - 1 ? View.VISIBLE : View.GONE);
        }
    }

    private void showTab(int which) {
        for (int k = 0; k < 3; k++) {
            boolean sel = k == which;
            tabs[k].setVisibility(sel ? View.VISIBLE : View.GONE);
            tabButtons[k].setTextColor(sel ? c.fg : c.muted);
            tabButtons[k].setBackgroundDrawable(sel ? round(c.surface, dp(9)) : null);
        }
    }

    static String g(String milli) {
        try {
            return String.format(Locale.US, "%.2fg", Integer.parseInt(milli) / 1000.0);
        } catch (Exception e) {
            return "?";
        }
    }

    static Map<String, String> pairs(String text) {
        Map<String, String> m = new HashMap<String, String>();
        for (String tok : text.trim().split("\\s+")) {
            int eq = tok.indexOf('=');
            if (eq > 0) m.put(tok.substring(0, eq), tok.substring(eq + 1));
        }
        return m;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    // ---------- building blocks ----------

    private LinearLayout column(int gap) {
        LinearLayout l = new LinearLayout(activity) {
            @Override public void addView(View child, int index, ViewGroup.LayoutParams params) {
                if (getChildCount() > 0 && params instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) params).topMargin += gapOf(this);
                }
                super.addView(child, index, params);
            }
        };
        l.setOrientation(LinearLayout.VERTICAL);
        l.setTag(gap);
        return l;
    }

    private static int gapOf(View v) {
        Object t = v.getTag();
        return t instanceof Integer ? (Integer) t : 0;
    }

    private LinearLayout card() {
        LinearLayout l = column(dp(wide ? 10 : 12));
        int p = dp(wide ? 12 : 16);
        l.setPadding(p, p, p, p);
        GradientDrawable d = round(c.surface, dp(12));
        d.setStroke(dp(1), c.line);
        l.setBackgroundDrawable(d);
        return l;
    }

    private TextView text(String s, int sp, int color, Typeface face) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        return t;
    }

    private TextView sectionLabel(String s) {
        TextView t = text(s, 12, c.muted, MEDIUM);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private View divider() {
        View v = new View(activity);
        v.setBackgroundColor(c.line);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2 + 1)));
        return v;
    }

    private TextView chip(String s, int color) {
        TextView t = text(s, 13, color, MEDIUM);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        styleChip(t, s, color);
        return t;
    }

    private void styleChip(TextView t, String s, int color) {
        t.setText(s);
        t.setTextColor(color);
        GradientDrawable d = round((color & 0x00FFFFFF) | 0x26000000, dp(14));
        t.setBackgroundDrawable(d);
    }

    private TextView button(String s, boolean primary) {
        TextView b = text(s, 16, primary ? c.onAccent : c.fg, primary ? BOLD : MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundDrawable(pressable(primary ? c.accent : c.surface2, primary ? darker(c.accent) : c.line, dp(10)));
        return b;
    }

    private View toggleRow(final Toggle t, String title, String hint, int size) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        LinearLayout labels = column(dp(2));
        labels.addView(text(title, size, c.fg, size >= 16 ? MEDIUM : null));
        if (hint != null) labels.addView(text(hint, 12, c.muted, null));
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(t, new LinearLayout.LayoutParams(dp(48), dp(28)));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { t.flip(); }
        });
        return row;
    }

    private View stepRow(Stepper s, String title, String hint) {
        LinearLayout col = column(dp(6));
        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.BOTTOM);
        LinearLayout labels = column(dp(1));
        labels.addView(text(title, 15, c.fg, MEDIUM));
        if (hint != null && !wide) labels.addView(text(hint, 12, c.muted, null));
        if (hint != null && wide) {
            LinearLayout l = new LinearLayout(activity);
            l.setGravity(Gravity.BOTTOM);
            l.addView(text(title, 15, c.fg, MEDIUM));
            TextView h = text(hint, 12, c.muted, null);
            h.setSingleLine(true);
            h.setEllipsize(TextUtils.TruncateAt.END);
            h.setPadding(dp(8), 0, 0, dp(1));
            l.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            head.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        } else {
            head.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        head.addView(s.number);
        head.addView(s.unitView);
        col.addView(head);
        col.addView(s.view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        return col;
    }

    private View toolRow(String title, String hint, final Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.setBackgroundDrawable(pressable(0x00000000, c.surface2, dp(10)));
        LinearLayout labels = column(dp(2));
        labels.addView(text(title, 15, c.fg, null));
        labels.addView(text(hint, 12, c.muted, null));
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(text("›", 22, c.muted, LIGHT));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return row;
    }

    private static GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private static StateListDrawable pressable(int normal, int pressed, int radius) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_pressed}, round(pressed, radius));
        s.addState(new int[] {}, round(normal, radius));
        return s;
    }

    private static int darker(int color) {
        int r = (color >> 16) & 0xFF, gg = (color >> 8) & 0xFF, b = color & 0xFF;
        return 0xFF000000 | (r * 4 / 5) << 16 | (gg * 4 / 5) << 8 | (b * 4 / 5);
    }

    private static void background(Runnable r) {
        Thread t = new Thread(r, "request");
        t.setDaemon(true);
        t.start();
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    // ---------- widgets ----------

    /** Pill switch drawn by hand so it looks the same on API 19 and on new phones. */
    private final class Toggle extends View {
        boolean on, locked;
        Runnable onFlip;
        String lockedText;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Toggle() {
            super(activity);
            setClickable(true);
            setFocusable(true);
            setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { flip(); }
            });
        }

        void flip() {
            if (locked) {
                if (lockedText != null) showMessage(lockedText, c.warn);
                return;
            }
            on = !on;
            invalidate();
            changed();
            if (onFlip != null) onFlip.run();
        }

        void setLocked(boolean l) {
            locked = l;
            setAlpha(l ? 0.35f : 1f);
        }
        void set(boolean v) { on = v; invalidate(); }
        int bit() { return on ? 1 : 0; }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            r.set(0, 0, w, h);
            paint.setColor(on ? c.accent : c.surface2);
            canvas.drawRoundRect(r, h / 2, h / 2, paint);
            float rad = h / 2 - dp(3);
            paint.setColor(on ? c.onAccent : c.muted);
            canvas.drawCircle(on ? w - h / 2 : h / 2, h / 2, rad, paint);
        }
    }

    /** − value + with hold-to-repeat that speeds up. A value below `floor` (but above 0) snaps to 0 or floor. */
    private final class Stepper {
        final int min, max, step, floor;
        final String unit;
        int value;
        final LinearLayout view;
        final TextView number, unitView;
        final Slider slider;
        private final boolean hundredths;  // "×0.01g" values are shown as 0.15 g

        Stepper(int min, int max, int step, int floor, String unit) {
            this.min = min; this.max = max; this.step = step; this.floor = floor; this.unit = unit;
            hundredths = unit.startsWith("×0.01");
            number = text("–", 22, c.fg, BOLD);
            number.setIncludeFontPadding(false);
            unitView = text(hundredths ? "g" : unit, 13, c.muted, MEDIUM);
            unitView.setPadding(dp(3), 0, 0, dp(1));
            view = new LinearLayout(activity);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.addView(key("−", -1), new LinearLayout.LayoutParams(dp(44), dp(44)));
            slider = new Slider(this);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(44), 1);
            sp.leftMargin = dp(10);
            sp.rightMargin = dp(10);
            view.addView(slider, sp);
            view.addView(key("+", 1), new LinearLayout.LayoutParams(dp(44), dp(44)));
        }

        void set(int v) {
            value = v;
            number.setText(hundredths ? String.format(Locale.US, "%.2f", v / 100.0) : Integer.toString(v));
            slider.invalidate();
        }

        /** Snap to the step, the floor and the range; true if the value changed. */
        boolean setFromUser(int v) {
            v = Math.round(v / (float) step) * step;
            if (floor > 0 && v > 0 && v < floor) v = v * 2 < floor ? 0 : floor;
            v = Math.max(min, Math.min(max, v));
            if (v == value) return false;
            set(v);
            changed();
            return true;
        }

        void bump(int dir) {
            int v = value + dir * step;
            if (floor > 0 && v > 0 && v < floor) v = dir > 0 ? floor : 0;
            v = Math.max(min, Math.min(max, v));
            if (v != value) { set(v); changed(); }
        }

        private View key(String label, final int dir) {
            final TextView k = text(label, 22, c.fg, MEDIUM);
            k.setGravity(Gravity.CENTER);
            k.setBackgroundDrawable(pressable(c.surface2, c.line, dp(10)));
            k.setOnTouchListener(new View.OnTouchListener() {
                private int count;
                private final Runnable repeat = new Runnable() {
                    @Override public void run() {
                        bump(dir);
                        count++;
                        ui.postDelayed(this, count > 12 ? 40 : count > 4 ? 80 : 150);
                    }
                };
                @Override public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            v.setPressed(true);
                            count = 0;
                            bump(dir);
                            ui.postDelayed(repeat, 400);
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            v.setPressed(false);
                            ui.removeCallbacks(repeat);
                            return true;
                        default:
                            return true;
                    }
                }
            });
            return k;
        }
    }

    /** Caption line (label left, value and threshold right) over a thick bar. */
    private final class MeterRow {
        final LinearLayout view;
        final TextView value;
        final Meter bar;

        MeterRow(String label, boolean captionRow) {
            view = column(dp(6));
            LinearLayout line = new LinearLayout(activity);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(text(label, wide ? 18 : 15, wide ? c.fg : c.muted, wide ? BOLD : null),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            value = text("–", wide ? 22 : 16, c.fg, BOLD);
            line.addView(value);
            view.addView(line);
            bar = new Meter();
            view.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(wide ? 32 : 26)));
        }

        /** Dragging on the bar sets this stepper (value = threshold / scale); the user saves as usual. */
        void bind(Stepper s, int scale) {
            bar.target = s;
            bar.scale = scale;
        }

        void set(int now, int max, int threshold, int peak, String text) {
            if (bar.dragging) return;  // keep the marker under the finger
            value.setText(text);
            value.setTextColor(threshold > 0 && now >= threshold ? c.sport : c.fg);
            bar.set(now, max, threshold, peak);
        }
    }

    /**
     * Thick gauge: the live value fills it in the text colour; once it reaches the user's threshold the whole fill
     * turns red (and back) with a short colour fade. The fill glides to each new value.
     */
    private final class Meter extends View {
        private int now, max = 1, threshold, peak = -1;
        private float shown;  // value currently drawn (animates toward now)
        private android.animation.ValueAnimator anim, hotAnim;
        private float hot;      // 0 = normal colour, 1 = red
        private boolean isHot;
        Stepper target;
        int scale = 1;
        boolean dragging;
        private int dragMax;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Meter() {
            super(activity);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);  // knob shadow
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (target == null || !loaded) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    dragging = true;
                    dragMax = max;  // the scale must not move while dragging
                    // fall through
                case MotionEvent.ACTION_MOVE: {
                    float f = Math.max(0f, Math.min(1f, e.getX() / Math.max(1, getWidth())));
                    int v = Math.round(f * dragMax / scale / (float) target.step) * target.step;
                    if (target.floor > 0 && v > 0 && v < target.floor) v = v * 2 < target.floor ? 0 : target.floor;
                    v = Math.max(target.min, Math.min(target.max, v));
                    if (v != target.value) {
                        target.set(v);
                        changed();
                    }
                    threshold = v * scale;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    showMessage("막대로 기준을 바꿨습니다. 저장을 눌러야 차에 적용됩니다.", c.info);
                    return true;
                default:
                    return true;
            }
        }

        void set(int now, int max, int threshold, int peak) {
            this.max = Math.max(1, max); this.threshold = threshold; this.peak = peak;
            int next = Math.max(0, now);
            if (next != this.now || anim == null) {
                this.now = next;
                if (anim != null) anim.cancel();
                anim = android.animation.ValueAnimator.ofFloat(shown, next);
                anim.setDuration(SettingsPanel.POLL_MS + 40); // glide to the next sample, about one poll long
                anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
                anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                        shown = (Float) a.getAnimatedValue();
                        invalidate();
                    }
                });
                anim.start();
            }
            boolean h = threshold > 0 && next >= threshold;
            if (h != isHot) {
                isHot = h;
                if (hotAnim != null) hotAnim.cancel();
                hotAnim = android.animation.ValueAnimator.ofFloat(hot, h ? 1f : 0f);
                hotAnim.setDuration(300);
                hotAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
                hotAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                        hot = (Float) a.getAnimatedValue();
                        invalidate();
                    }
                });
                hotAnim.start();
            }
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float bh = h * 0.62f, top = (h - bh) / 2;
            int scaleMax = dragging ? dragMax : max;
            float tx = threshold > 0 ? Math.min(1f, threshold / (float) scaleMax) * w : w;
            r.set(0, top, w, top + bh);
            paint.setColor(c.surface2);
            canvas.drawRoundRect(r, bh / 2, bh / 2, paint);
            float x = Math.min(1f, shown / (float) scaleMax) * w;
            if (x > 0.5f) {
                r.set(0, top, Math.max(bh, x), top + bh);
                paint.setColor(blend(c.fg, c.sport, hot));
                canvas.drawRoundRect(r, bh / 2, bh / 2, paint);
            }
            if (peak > 0) {
                float px = Math.min(1f, peak / (float) scaleMax) * w;
                paint.setColor(c.muted);
                canvas.drawRect(Math.max(0, px - dp(1)), top, Math.min(w, px + dp(1)), top + bh, paint);
            }
            if (threshold > 0 || target != null) {
                if (threshold <= 0) tx = 0;
                paint.setColor(c.fg);
                if (target != null) {
                    // draggable: a white knob on the threshold with a thin border and a small shadow
                    float kr = h / 2f - dp(2);
                    float cx = Math.max(kr, Math.min(w - kr, tx));
                    knob(canvas, paint, cx, h / 2f, kr, dp(2));
                } else {
                    float half = dp(2);
                    r.set(Math.max(0, tx - half), 0, Math.min(w, tx + half), h);
                    canvas.drawRoundRect(r, half, half, paint);
                }
            }
        }
    }

    /** Thin track with a white knob; dragging sets the stepper (snapped to its step). */
    private final class Slider extends View {
        private final Stepper st;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Slider(Stepper st) {
            super(activity);
            this.st = st;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        private float knobR() { return dp(11); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    // fall through
                case MotionEvent.ACTION_MOVE: {
                    float kr = knobR();
                    float f = Math.max(0f, Math.min(1f, (e.getX() - kr) / Math.max(1f, getWidth() - 2 * kr)));
                    st.setFromUser(Math.round(st.min + f * (st.max - st.min)));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return true;
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), kr = knobR();
            float th = dp(6), top = (h - th) / 2;
            float f = st.max > st.min ? (st.value - st.min) / (float) (st.max - st.min) : 0f;
            f = Math.max(0f, Math.min(1f, f));
            float cx = kr + f * (w - 2 * kr);
            r.set(kr, top, w - kr, top + th);
            paint.setColor(c.surface2);
            canvas.drawRoundRect(r, th / 2, th / 2, paint);
            if (cx > kr) {
                r.set(kr, top, cx, top + th);
                paint.setColor(c.fg);
                canvas.drawRoundRect(r, th / 2, th / 2, paint);
            }
            knob(canvas, paint, cx, h / 2f, kr, Math.max(1, dp(1) * 3 / 2));
        }
    }

    /** White knob, a thin ring in the card colour and a soft shadow (Astryx: no thick dark border). */
    private void knob(Canvas canvas, Paint paint, float cx, float cy, float r, float ring) {
        paint.setColor(c.surface);
        paint.setShadowLayer(dp(2), 0, dp(1), 0x80000000);
        canvas.drawCircle(cx, cy, r, paint);
        paint.clearShadowLayer();
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, r - ring, paint);
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8
                | Math.round(ab + (bb - ab) * t);
    }
}
