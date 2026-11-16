package io.github.k8ecs.phone;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.k8ecs.ui.SettingsPanel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Phone settings app. The car module makes every decision on its own; the phone only reads status
 * and changes settings over Bluetooth while it is in the car.
 */
public final class MainActivity extends Activity {
    static final UUID SERVICE = UUID.fromString("5d1c9b7e-2a4f-4e61-9c3b-7f0e8a6d2c41");
    private SettingsPanel panel;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        prefs = getSharedPreferences("car", MODE_PRIVATE);
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, 1);
            showMessage("블루투스 권한을 허용해 주세요.");
            return;
        }
        start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) start();
        else showMessage("블루투스 권한이 없으면 차량에 연결할 수 없습니다.");
    }

    private void start() {
        String address = prefs.getString("address", null);
        if (address == null) chooseCar();
        else showPanel(address);
    }

    private void showPanel(final String address) {
        if (panel != null) panel.onDestroy();
        panel = new SettingsPanel(this, new BtTransport(adapter(), address));
        panel.setHeaderAction("차량 바꾸기", new Runnable() {
            @Override public void run() { chooseCar(); }
        });
        setContentView(panel.build("K8 마이 드라이브"));
        panel.onResume();
    }

    private void chooseCar() {
        if (panel != null) { panel.onDestroy(); panel = null; }
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_NO;
        int bg = dark ? 0xFF0F1418 : 0xFFF1F4F7, surface = dark ? 0xFF172028 : 0xFFFFFFFF;
        int pressed = dark ? 0xFF202B35 : 0xFFE7EDF2, line = dark ? 0xFF2A3743 : 0xFFD3DCE3;
        int fg = dark ? 0xFFE7EDF2 : 0xFF111A21, muted = dark ? 0xFF8797A5 : 0xFF5B6976;
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(bg);
        list.setPadding(dp(16), dp(20), dp(16), dp(24));
        TextView t = new TextView(this);
        t.setText("차량 고르기");
        t.setTextSize(22);
        t.setTextColor(fg);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        list.addView(t);
        TextView hint = new TextView(this);
        hint.setText("K8 헤드유닛을 고르세요. 휴대폰과 블루투스로 페어링된 기기만 보입니다.");
        hint.setTextSize(14);
        hint.setTextColor(muted);
        hint.setPadding(0, dp(4), 0, dp(16));
        list.addView(hint);
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) {
            hint.setText("블루투스를 켠 뒤 앱을 다시 열어 주세요.");
        } else {
            for (final BluetoothDevice d : a.getBondedDevices()) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(16), dp(14), dp(16), dp(14));
                android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
                s.addState(new int[] {android.R.attr.state_pressed}, box(pressed, line));
                s.addState(new int[] {}, box(surface, line));
                row.setBackground(s);
                TextView name = new TextView(this);
                name.setText(d.getName() == null ? "이름 없는 기기" : d.getName());
                name.setTextSize(16);
                name.setTextColor(fg);
                row.addView(name);
                TextView addr = new TextView(this);
                addr.setText(d.getAddress());
                addr.setTextSize(12);
                addr.setTextColor(muted);
                row.addView(addr);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        prefs.edit().putString("address", d.getAddress()).apply();
                        showPanel(d.getAddress());
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(8);
                list.addView(row, lp);
            }
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(bg);
        scroll.setFillViewport(true);
        scroll.addView(list);
        setContentView(scroll);
    }

    private android.graphics.drawable.GradientDrawable box(int color, int stroke) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    private void showMessage(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setFitsSystemWindows(true);
        setContentView(t);
    }

    @Override protected void onResume() { super.onResume(); if (panel != null) panel.onResume(); }
    @Override protected void onPause() { if (panel != null) panel.onPause(); super.onPause(); }
    @Override protected void onDestroy() { if (panel != null) panel.onDestroy(); super.onDestroy(); }

    static final class BtTransport implements SettingsPanel.Transport {
        private final BluetoothAdapter adapter;
        private final String address;
        private BluetoothSocket socket;
        private BufferedReader in;
        private OutputStream out;
        private long nextTryMs;

        BtTransport(BluetoothAdapter adapter, String address) {
            this.adapter = adapter;
            this.address = address;
        }

        @Override
        public String call(String line) throws IOException {
            if (socket == null) {
                if (System.currentTimeMillis() < nextTryMs) throw new IOException("waiting");
                nextTryMs = System.currentTimeMillis() + 5000;
                if (adapter == null) throw new IOException("no adapter");
                try {
                    BluetoothSocket s = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(SERVICE);
                    s.connect();
                    socket = s;
                    in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                    out = s.getOutputStream();
                } catch (SecurityException e) {
                    throw new IOException("permission");
                }
            }
            out.write((line + "\n").getBytes("UTF-8"));
            out.flush();
            String reply = in.readLine();
            if (reply == null) throw new IOException("closed");
            return reply;
        }

        @Override
        public void close() {
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
            socket = null;
        }
    }
}
