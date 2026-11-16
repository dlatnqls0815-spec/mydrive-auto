package io.github.k8ecs.hu;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

import io.github.k8ecs.ui.SettingsPanel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Head unit settings screen; talks to the car module on 127.0.0.1. */
public final class HuActivity extends Activity {
    private SettingsPanel panel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        panel = new SettingsPanel(this, new TcpTransport());
        panel.setDark(true);
        setContentView(panel.build("K8 마이 드라이브"));
    }

    @Override protected void onResume() { super.onResume(); panel.onResume(); }
    @Override protected void onPause() { panel.onPause(); super.onPause(); }
    @Override protected void onDestroy() { panel.onDestroy(); super.onDestroy(); }

    /**
     * Talks to the car module: the local socket first (works even when the NEMESIS service has no inet
     * group), then TCP on 127.0.0.1 for older modules.
     */
    static final class TcpTransport implements SettingsPanel.Transport {
        private java.io.Closeable socket;
        private BufferedReader in;
        private OutputStream out;

        private void open() throws IOException {
            try {
                android.net.LocalSocket l = new android.net.LocalSocket();
                l.connect(new android.net.LocalSocketAddress("k8ecs.damping"));
                l.setSoTimeout(40000); // USB export can take a while (module gives up after 30 s)
                socket = l;
                in = new BufferedReader(new InputStreamReader(l.getInputStream(), "UTF-8"));
                out = l.getOutputStream();
                return;
            } catch (IOException e) {
                // module older than 0.3.6: TCP only
            }
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", 47231), 2000);
            s.setSoTimeout(40000);
            socket = s;
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            out = s.getOutputStream();
        }

        @Override
        public String call(String line) throws IOException {
            if (socket == null) open();
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
