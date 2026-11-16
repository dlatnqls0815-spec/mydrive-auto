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
        int bg, surface, surface2, line, fg, muted, accent, onAccent, eco, sport, warn;
    }

    private static Palette palette(boolean dark) {
        Palette p = new Palette();
        if (dark) {
            p.bg = 0xFF0F1418; p.surface = 0xFF172028; p.surface2 = 0xFF202B35; p.line = 0xFF2A3743;
            p.fg = 0xFFE7EDF2; p.muted = 0xFF8797A5; p.accent = 0xFF5AB4E6; p.onAccent = 0xFF06131C;
            p.eco = 0xFF3FBF9A; p.sport = 0xFFF0644B; p.warn = 0xFFE8B04A;
        } else {
            p.bg = 0xFFF1F4F7; p.surface = 0xFFFFFFFF; p.surface2 = 0xFFE7EDF2; p.line = 0xFFD3DCE3;
            p.fg = 0xFF111A21; p.muted = 0xFF5B6976; p.accent = 0xFF1C78B6; p.onAccent = 0xFFFFFFFF;
            p.eco = 0xFF1A9070; p.sport = 0xFFD2432C; p.warn = 0xFFA87512;
        }
        return p;
    }

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private static final Typeface CONDENSED = Typeface.create("sans-serif-condensed", Typeface.NORMAL);

    private final Activity activity;
    private final Transport transport;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object io = new Object();
    private Palette c;

    private String headerLabel;
    private Runnable headerAction;

    private TextView connDot, connText, message;
    private TextView speedValue, modeChip, latValue, pedalValue, footer;
    private GBar latBar;
    private TextView[] itemState = new TextView[3], itemBadge = new TextView[3];
    private Toggle enabled, suspOn, steerOn, engOn, suspLinkEng, suspLinkSteer, engLinkSusp, engLinkSteer;
    private Stepper speedUp, hyst, cornerOn, cornerOff, dwell, maxPerMin;
    private Stepper steerSpeedUp, steerHyst, steerCornerOn, steerCornerOff, steerDwell;
    private Stepper engPedal, engHold;
    private final LinearLayout[] tabs = new LinearLayout[3];
    private final TextView[] tabButtons = new TextView[3];
    private TextView saveButton;
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

        View head = header(title);
        View live = liveBoard();
        LinearLayout master = card();
        enabled = new Toggle();
        master.addView(toggleRow(enabled, "자동 전환", "마이 드라이브 모드로 주행할 때만 바꿉니다", 17));
        View seg = segmented();

        suspOn = new Toggle();
        speedUp = new Stepper(0, 200, 5, 20, "km/h");
        hyst = new Stepper(3, 50, 1, 0, "km/h");
        cornerOn = new Stepper(0, 80, 1, 10, "×0.01g");
        cornerOff = new Stepper(1, 80, 1, 0, "×0.01g");
        dwell = new Stepper(1, 60, 1, 0, "초");
        maxPerMin = new Stepper(1, 20, 1, 0, "회");
        suspLinkEng = new Toggle();
        suspLinkSteer = new Toggle();
        tabBody(0, toggleRow(suspOn, "서스펜션 자동 전환", "에코 ↔ 스포츠", 16), new View[] {
                stepRow(speedUp, "단단하게 바꿀 속도", "0이면 속도 규칙을 쓰지 않습니다"),
                stepRow(hyst, "내려올 때 여유", "위 속도보다 작게"),
                stepRow(cornerOn, "코너 감지 기준", "25 = 0.25g, 0이면 코너 규칙 끔"),
                stepRow(cornerOff, "코너 해제 기준", "감지 기준보다 작게"),
                stepRow(dwell, "바꾼 뒤 최소 유지", null),
                stepRow(maxPerMin, "분당 최대 전환", "세 항목 각각에 적용"),
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
        tabBody(1, toggleRow(steerOn, "핸들 자동 전환", "노멀 ↔ 스포츠", 16), new View[] {
                stepRow(steerSpeedUp, "스포츠로 바꿀 속도", "0이면 속도 규칙을 쓰지 않습니다"),
                stepRow(steerHyst, "내려올 때 여유", "위 속도보다 작게"),
                stepRow(steerCornerOn, "코너 감지 기준", "0이면 코너 규칙 끔"),
                stepRow(steerCornerOff, "코너 해제 기준", "감지 기준보다 작게"),
                stepRow(steerDwell, "바꾼 뒤 최소 유지", null),
        }, null);

        engOn = new Toggle();
        engPedal = new Stepper(5, 100, 5, 0, "%");
        engHold = new Stepper(1, 60, 1, 0, "초");
        engLinkSusp = new Toggle();
        engLinkSteer = new Toggle();
        tabBody(2, toggleRow(engOn, "엔진 자동 전환", "에코 ↔ 스포츠 (파워트레인)", 16), new View[] {
                stepRow(engPedal, "스포츠로 바꿀 엑셀", "이만큼 밟으면 스포츠, 끝까지 = 100"),
                stepRow(engHold, "에코로 돌아갈 시간", "덜 밟은 채 이 시간이 지나면"),
        }, new View[] {
                toggleRow(engLinkSusp, "서스펜션도 스포츠로", "엔진이 스포츠가 될 때", 15),
                toggleRow(engLinkSteer, "핸들도 스포츠로", "엔진이 스포츠가 될 때", 15),
        });

        saveButton = button("저장", true);
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        message = text("", 14, c.muted, null);
        View toolCard = tools();
        TextView note = text("차가 따르지 않는 항목은 스스로 멈춥니다. 사용 책임은 운전자에게 있습니다.", 12, c.muted, null);

        View screen = wide ? twoPanes(head, live, master, seg, toolCard, note) : onePane(head, live, master, seg, toolCard, note);
        showTab(0);
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

    /** Left: header, live board, tools. Right: master switch and tabs, with the save bar pinned below. */
    private View twoPanes(View head, View live, View master, View seg, View toolCard, TextView note) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(c.bg);
        root.setFitsSystemWindows(true);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout left = column(dp(12));
        left.addView(head);
        left.addView(live);
        left.addView(toolCard);
        left.addView(note);
        root.addView(scroll(left, false), new LinearLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout right = column(dp(12));
        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(master, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        sp.leftMargin = dp(12);
        top.addView(seg, sp);
        right.addView(top);

        LinearLayout tabHolder = new LinearLayout(activity);
        tabHolder.setOrientation(LinearLayout.VERTICAL);
        for (int k = 0; k < 3; k++) tabHolder.addView(tabs[k]);
        right.addView(scroll(tabHolder, false), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        message.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(message, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(saveButton, new LinearLayout.LayoutParams(dp(240), dp(52)));
        right.addView(bar);

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        rp.leftMargin = dp(16);
        root.addView(right, rp);
        return root;
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
        t.addView(divider());
        if (!wide) {
            for (View v : steps) t.addView(v);
        } else {
            LinearLayout grid = new LinearLayout(activity);
            LinearLayout l = column(dp(12)), r = column(dp(12));
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

        // speed + drive mode
        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.BOTTOM);
        speedValue = text("–", 48, c.fg, LIGHT);
        speedValue.setIncludeFontPadding(false);
        top.addView(speedValue);
        TextView unit = text("km/h", 15, c.muted, null);
        unit.setPadding(dp(6), 0, 0, dp(6));
        top.addView(unit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        modeChip = chip("모드 –", c.muted);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.bottomMargin = dp(8);
        top.addView(modeChip, mp);
        card.addView(top);

        // lateral G bar and pedal
        LinearLayout gRow = new LinearLayout(activity);
        gRow.setGravity(Gravity.CENTER_VERTICAL);
        gRow.addView(text("횡G", 13, c.muted, null), new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT));
        latBar = new GBar();
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(8), 1);
        bp.rightMargin = dp(10);
        gRow.addView(latBar, bp);
        latValue = text("–", 13, c.fg, CONDENSED);
        gRow.addView(latValue, new LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(gRow);

        LinearLayout pRow = new LinearLayout(activity);
        pRow.addView(text("엑셀", 13, c.muted, null), new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT));
        pedalValue = text("–", 13, c.fg, CONDENSED);
        pRow.addView(pedalValue);
        card.addView(pRow);

        // three items
        LinearLayout items = new LinearLayout(activity);
        items.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"서스펜션", "핸들", "엔진"};
        for (int k = 0; k < 3; k++) {
            LinearLayout tile = column(dp(4));
            tile.setPadding(dp(10), dp(10), dp(10), dp(10));
            tile.setBackgroundDrawable(round(c.surface2, dp(10)));
            tile.addView(text(names[k], 12, c.muted, null));
            itemState[k] = text("–", 18, c.fg, MEDIUM);
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
            tabs[k] = card();
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

    public void onResume() {
        polling = true;
        if (!loaded) load();
        Thread poll = new Thread(new Runnable() {
            @Override public void run() {
                while (polling) {
                    final String st = request("STATUS");
                    ui.post(new Runnable() { @Override public void run() { showStatus(st); } });
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
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
                + " suspLinkEng=" + suspLinkEng.bit() + " suspLinkSteer=" + suspLinkSteer.bit()
                + " engLinkSusp=" + engLinkSusp.bit() + " engLinkSteer=" + engLinkSteer.bit();
        saveButton.setText("저장 중…");
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
        if (reply.contains("rate")) return "유지 시간은 1초 이상, 분당 횟수는 1~20입니다.";
        if (reply.contains("below steerSpeedUp")) return "핸들 여유는 스포츠로 바꿀 속도보다 작아야 합니다.";
        if (reply.contains("below speedUp")) return "여유는 단단하게 바꿀 속도보다 작아야 합니다.";
        if (reply.contains("engPedal")) return "엑셀 기준은 5~100% 입니다.";
        if (reply.contains("engHold")) return "엔진 유지 시간은 1~60초입니다.";
        if (reply.contains("speedHysteresis")) return "여유는 3 이상이어야 합니다.";
        return reply;
    }

    private void fill(Map<String, String> m) {
        filling = true;
        enabled.set("1".equals(m.get("enabled")));
        suspOn.set("1".equals(m.get("suspOn")));
        steerOn.set("1".equals(m.get("steerOn")));
        engOn.set("1".equals(m.get("engOn")));
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
        filling = false;
        setDirty(false);
    }

    private void changed() {
        if (!filling) setDirty(true);
    }

    private void setDirty(boolean d) {
        dirty = d;
        if (saveButton != null) saveButton.setText(d ? "변경 내용 저장" : "저장");
    }

    private void showMessage(String text, int color) {
        message.setText(text);
        message.setTextColor(color);
    }

    private void showStatus(String st) {
        if (st == null || !st.startsWith("ST ")) {
            connDot.setBackgroundDrawable(round(c.sport, dp(4)));
            connText.setText("차에 연결되지 않음 · 다시 시도 중");
            return;
        }
        Map<String, String> m = pairs(st.substring(3));
        followCfg(m.get("cfg"));
        boolean on = "1".equals(m.get("enabled"));
        connDot.setBackgroundDrawable(round(on ? c.eco : c.warn, dp(4)));
        connText.setText("연결됨 · " + (on ? "자동 전환 켜짐" : "자동 전환 꺼짐") + " · v" + m.get("ver"));

        speedValue.setText(String.valueOf(m.get("kmh")));
        String mode = m.get("mode");
        boolean custom = "1".equals(m.get("custom"));
        String modeName = "2".equals(mode) ? "에코" : "3".equals(mode) ? "스포츠"
                : "6".equals(mode) ? (custom ? "마이 드라이브" : "스마트")
                : "?".equals(mode) || mode == null ? "모드 모름" : "커스텀 " + mode;
        int modeColor = "2".equals(mode) ? c.eco : "3".equals(mode) ? c.sport : "6".equals(mode) && custom ? c.accent : c.muted;
        styleChip(modeChip, modeName, modeColor);

        int lat = parse(m.get("lat")), peak = parse(m.get("peak"));
        latBar.set(lat, peak, parse(m.get("cornerOn")));
        latValue.setText(g(m.get("lat")) + "  최고 " + g(m.get("peak")));
        pedalValue.setText("?".equals(m.get("pedal")) || m.get("pedal") == null ? "읽을 수 없음" : m.get("pedal") + " %");

        String step = m.get("step");
        item(0, "soft".equals(step) ? "에코" : "firm".equals(step) ? "스포츠" : "–", "firm".equals(step),
                m, "suspOn", "S", "1".equals(m.get("corner")) ? "코너" : null);
        String steer = m.get("steer");
        item(1, "normal".equals(steer) ? "노멀" : "sport".equals(steer) ? "스포츠" : "–", "sport".equals(steer),
                m, "steerOn", "H", null);
        String eng = m.get("eng");
        item(2, "eco".equals(eng) ? "에코" : "sport".equals(eng) ? "스포츠" : "–", "sport".equals(eng),
                m, "engOn", "E", null);

        String latcal = m.get("latcal");
        String calib = latcal == null ? ("learning".equals(m.get("gyroScale")) ? "학습 중 (회전 " + m.get("turns") + "/3)" : "완료")
                : "ok".equals(latcal) ? "완료" : "wait".equals(latcal) ? "정차하면 시작"
                : "방향 학습 " + latcal.replace("est", "") + "%";
        String last = String.valueOf(m.get("last")).replace('_', ' ').replace("speed ", "속도 ")
                .replace("corner ", "코너 ").replace("pedal ", "엑셀 ").replace("link ", "연동 ");
        footer.setText("전환 " + m.get("sent") + "회" + ("-".equals(last) || "null".equals(last) ? "" : " · 마지막 " + last)
                + " · 센서 보정 " + calib + ("ok".equals(m.get("imu")) ? "" : " · 센서 " + m.get("imu")));
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
        else if ("1".equals(m.get(key))) { badge = "자동"; color = c.accent; }
        else { badge = "수동"; color = c.muted; }
        itemBadge[k].setText(extra == null ? badge : badge + " · " + extra);
        itemBadge[k].setTextColor(color);
    }

    static String onText(Map<String, String> m, String key, String haltLetter) {
        if (String.valueOf(m.get("haltedItems")).contains(haltLetter)) return "  ※ 정지됨(차가 따르지 않음)";
        return "1".equals(m.get(key)) ? "  [자동]" : "  [수동]";
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
        LinearLayout l = column(dp(12));
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable d = round(c.surface, dp(16));
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
        TextView b = text(s, 16, primary ? c.onAccent : c.fg, MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundDrawable(pressable(primary ? c.accent : c.surface2, primary ? darker(c.accent) : c.line, dp(14)));
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
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = column(dp(2));
        labels.addView(text(title, 15, c.fg, null));
        if (hint != null) labels.addView(text(hint, 12, c.muted, null));
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(s.view);
        return row;
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
        private boolean on;
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

        void flip() { on = !on; invalidate(); changed(); }
        void set(boolean v) { on = v; invalidate(); }
        int bit() { return on ? 1 : 0; }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            r.set(0, 0, w, h);
            paint.setColor(on ? c.accent : c.line);
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
        final TextView number;

        Stepper(int min, int max, int step, int floor, String unit) {
            this.min = min; this.max = max; this.step = step; this.floor = floor; this.unit = unit;
            view = new LinearLayout(activity);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setBackgroundDrawable(round(c.surface2, dp(12)));
            view.addView(key("−", -1), new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout mid = new LinearLayout(activity);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setGravity(Gravity.CENTER);
            number = text("–", 18, c.fg, MEDIUM);
            number.setGravity(Gravity.CENTER);
            mid.addView(number);
            TextView u = text(unit, 10, c.muted, null);
            u.setGravity(Gravity.CENTER);
            mid.addView(u);
            view.addView(mid, new LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT));
            view.addView(key("+", 1), new LinearLayout.LayoutParams(dp(44), dp(44)));
        }

        void set(int v) { value = v; number.setText(Integer.toString(v)); }

        void bump(int dir) {
            int v = value + dir * step;
            if (floor > 0 && v > 0 && v < floor) v = dir > 0 ? floor : 0;
            v = Math.max(min, Math.min(max, v));
            if (v != value) { set(v); changed(); }
        }

        private View key(String label, final int dir) {
            final TextView k = text(label, 22, c.accent, MEDIUM);
            k.setGravity(Gravity.CENTER);
            k.setBackgroundDrawable(pressable(0x00000000, c.line, dp(12)));
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

    /** Lateral G now (bar), peak (tick) and the corner threshold (marker), scaled to 0.6 g. */
    private final class GBar extends View {
        private int now, peak, threshold;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        GBar() { super(activity); }

        void set(int nowMilli, int peakMilli, int thresholdMilli) {
            now = Math.abs(nowMilli); peak = Math.abs(peakMilli); threshold = thresholdMilli;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), full = 600f;
            r.set(0, 0, w, h);
            paint.setColor(c.surface2);
            canvas.drawRoundRect(r, h / 2, h / 2, paint);
            float x = Math.min(1f, now / full) * w;
            if (x > 0) {
                r.set(0, 0, Math.max(h, x), h);
                paint.setColor(threshold > 0 && now >= threshold ? c.sport : c.accent);
                canvas.drawRoundRect(r, h / 2, h / 2, paint);
            }
            if (peak > 0) {
                float px = Math.min(1f, peak / full) * w;
                paint.setColor(c.fg);
                canvas.drawRect(Math.max(0, px - dp(1)), 0, Math.min(w, px + dp(1)), h, paint);
            }
        }
    }
}
