package fr.forgedesrobots;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pont entre le jeu (JavaScript) et le Bluetooth classique (RFCOMM).
 * Un téléphone héberge (serveur, jusqu'à 7 clients = 8 joueurs), les autres s'y connectent.
 * Les messages sont des lignes de texte JSON. Les événements remontent au jeu via window.__btEvent([...]).
 */
@SuppressLint("MissingPermission")
public class BtBridge {
    static final int REQ_PERMS = 41, REQ_ENABLE = 42, REQ_DISCOVERABLE = 43;
    static final UUID APP_UUID = UUID.fromString("7f3c2a90-5d1e-4b8a-9c41-0f6e2d9b8a17");
    static final int MAX_CLIENTS = 7;

    private final Activity act;
    private final WebView web;
    private final BluetoothAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
    private volatile boolean flushPosted = false;

    private volatile BluetoothServerSocket server;
    private volatile boolean hosting = false;
    private final Map<String, Conn> clients = new ConcurrentHashMap<>();
    private volatile Conn hostConn;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private BroadcastReceiver scanReceiver;

    BtBridge(Activity act, WebView web) {
        this.act = act;
        this.web = web;
        BluetoothManager bm = (BluetoothManager) act.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm != null ? bm.getAdapter() : null;
    }

    // ---------- Événements vers le jeu ----------
    private void emit(JSONObject o) {
        events.add(o.toString());
        if (!flushPosted) {
            flushPosted = true;
            ui.postDelayed(this::flush, 8);
        }
    }

    private void emit(String type, Object... kv) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
            emit(o);
        } catch (Exception ignored) { }
    }

    private void flush() {
        flushPosted = false;
        JSONArray arr = new JSONArray();
        String e;
        while ((e = events.poll()) != null) arr.put(e);
        if (arr.length() == 0) return;
        web.evaluateJavascript("window.__btEvent&&window.__btEvent(" + arr.toString() + ")", null);
    }

    // ---------- Permissions et activation ----------
    private String[] neededPermissions() {
        List<String> need = new ArrayList<>();
        String[] all = Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String p : all) if (act.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        return need.toArray(new String[0]);
    }

    @JavascriptInterface
    public boolean available() { return adapter != null; }

    /** Combat : écran en paysage ; menus : orientation libre. */
    @JavascriptInterface
    public void landscape(boolean on) {
        ui.post(() -> act.setRequestedOrientation(on
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
    }

    /** Vibration courte (coups reçus, explosions). */
    @JavascriptInterface
    @SuppressWarnings("deprecation")
    public void vibrate(int ms) {
        try {
            android.os.Vibrator v = (android.os.Vibrator) act.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            int d = Math.max(1, Math.min(400, ms));
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(d, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(d);
        } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public void ensureReady() {
        ui.post(() -> {
            if (adapter == null) { emit("ready", "ok", false, "reason", "pas de Bluetooth sur ce téléphone"); return; }
            String[] need = neededPermissions();
            if (need.length > 0) act.requestPermissions(need, REQ_PERMS);
            else afterPermissions();
        });
    }

    void onPermissions(boolean granted) {
        if (!granted) { emit("ready", "ok", false, "reason", "autorisation refusée"); return; }
        afterPermissions();
    }

    private void afterPermissions() {
        if (adapter.isEnabled()) { emit("ready", "ok", true); return; }
        try {
            act.startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE);
        } catch (Exception ex) {
            emit("ready", "ok", false, "reason", "Bluetooth désactivé");
        }
    }

    void onActivityResult(int requestCode, int resultCode) {
        if (requestCode == REQ_ENABLE) {
            boolean ok = resultCode == Activity.RESULT_OK || adapter.isEnabled();
            emit("ready", "ok", ok, "reason", "Bluetooth désactivé");
        } else if (requestCode == REQ_DISCOVERABLE) {
            emit("discoverable", "ok", resultCode != Activity.RESULT_CANCELED);
        }
    }

    // ---------- Hôte ----------
    @JavascriptInterface
    public void host() {
        stopInternal();
        try {
            server = adapter.listenUsingInsecureRfcommWithServiceRecord("ForgeDesRobots", APP_UUID);
        } catch (Exception ex) {
            emit("host_error", "reason", String.valueOf(ex.getMessage()));
            return;
        }
        hosting = true;
        new Thread(this::acceptLoop, "bt-accept").start();
        ui.post(() -> {
            try {
                Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
                act.startActivityForResult(i, REQ_DISCOVERABLE);
            } catch (Exception ignored) { }
        });
    }

    private void acceptLoop() {
        BluetoothServerSocket s = server;
        while (hosting && s != null) {
            BluetoothSocket sock;
            try {
                sock = s.accept();
            } catch (IOException ex) {
                break;
            }
            if (sock == null) continue;
            if (clients.size() >= MAX_CLIENTS) {
                try {
                    OutputStream o = sock.getOutputStream();
                    o.write("{\"t\":\"full\"}\n".getBytes(StandardCharsets.UTF_8));
                    o.flush();
                    Thread.sleep(300);
                } catch (Exception ignored) { }
                try { sock.close(); } catch (IOException ignored) { }
                continue;
            }
            String id = "p" + nextId.getAndIncrement();
            try {
                Conn c = new Conn(id, sock);
                clients.put(id, c);
                String name = "";
                try { name = sock.getRemoteDevice().getName(); } catch (Exception ignored) { }
                emit("join", "id", id, "name", name == null ? "" : name);
                c.startReading();
            } catch (IOException ex) {
                try { sock.close(); } catch (IOException ignored) { }
            }
        }
    }

    // ---------- Recherche et connexion (client) ----------
    @JavascriptInterface
    public void scan() {
        ui.post(() -> {
            stopScan();
            try {
                for (BluetoothDevice d : adapter.getBondedDevices()) emitDevice(d, true);
            } catch (Exception ignored) { }
            scanReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String a = intent.getAction();
                    if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                        BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if (d != null) emitDevice(d, d.getBondState() == BluetoothDevice.BOND_BONDED);
                    } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                        emit("scan_done");
                    }
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(BluetoothDevice.ACTION_FOUND);
            f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            if (Build.VERSION.SDK_INT >= 33) act.registerReceiver(scanReceiver, f, Context.RECEIVER_EXPORTED);
            else act.registerReceiver(scanReceiver, f);
            if (!adapter.startDiscovery()) emit("scan_done");
        });
    }

    private void emitDevice(BluetoothDevice d, boolean bonded) {
        String name = null;
        try { name = d.getName(); } catch (Exception ignored) { }
        if (name == null || name.isEmpty()) return; // les appareils sans nom ne sont pas des téléphones de joueurs
        emit("device", "address", d.getAddress(), "name", name, "bonded", bonded);
    }

    private void stopScan() {
        try { if (adapter != null) adapter.cancelDiscovery(); } catch (Exception ignored) { }
        if (scanReceiver != null) {
            try { act.unregisterReceiver(scanReceiver); } catch (Exception ignored) { }
            scanReceiver = null;
        }
    }

    @JavascriptInterface
    public void connect(String address) {
        ui.post(this::stopScan);
        new Thread(() -> {
            BluetoothSocket sock = null;
            try {
                try { adapter.cancelDiscovery(); } catch (Exception ignored) { }
                BluetoothDevice d = adapter.getRemoteDevice(address);
                try {
                    sock = d.createInsecureRfcommSocketToServiceRecord(APP_UUID);
                    sock.connect();
                } catch (IOException first) {
                    try { if (sock != null) sock.close(); } catch (IOException ignored) { }
                    sock = d.createRfcommSocketToServiceRecord(APP_UUID);
                    sock.connect();
                }
                Conn c = new Conn("host", sock);
                hostConn = c;
                emit("connected");
                c.startReading();
            } catch (Exception ex) {
                try { if (sock != null) sock.close(); } catch (IOException ignored) { }
                emit("connect_failed", "reason", String.valueOf(ex.getMessage()));
            }
        }, "bt-connect").start();
    }

    // ---------- Envoi ----------
    @JavascriptInterface
    public void send(String id, String data) {
        if (hosting) {
            if ("*".equals(id)) { for (Conn c : clients.values()) c.send(data); }
            else { Conn c = clients.get(id); if (c != null) c.send(data); }
        } else {
            Conn c = hostConn;
            if (c != null) c.send(data);
        }
    }

    @JavascriptInterface
    public void stop() { ui.post(this::stopInternal); }

    private void stopInternal() {
        stopScan();
        hosting = false;
        if (server != null) { try { server.close(); } catch (IOException ignored) { } server = null; }
        for (Conn c : clients.values()) c.close(false);
        clients.clear();
        Conn h = hostConn;
        hostConn = null;
        if (h != null) h.close(false);
    }

    // ---------- Une connexion ----------
    private class Conn {
        final String id;
        final BluetoothSocket sock;
        final OutputStream out;
        final ExecutorService writer = Executors.newSingleThreadExecutor();
        final AtomicInteger pending = new AtomicInteger();
        volatile boolean open = true;

        Conn(String id, BluetoothSocket sock) throws IOException {
            this.id = id;
            this.sock = sock;
            this.out = sock.getOutputStream();
        }

        void startReading() {
            new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (open && (line = r.readLine()) != null) {
                        if (line.length() > 64000) continue;
                        emit("msg", "id", id, "data", line);
                    }
                } catch (IOException ignored) {
                } finally {
                    close(true);
                }
            }, "bt-read-" + id).start();
        }

        void send(String line) {
            if (!open) return;
            // Liaison lente : on abandonne les vieux états plutôt que d'accumuler du retard.
            if (pending.get() > 12) return;
            pending.incrementAndGet();
            writer.execute(() -> {
                try {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ex) {
                    close(true);
                } finally {
                    pending.decrementAndGet();
                }
            });
        }

        void close(boolean notify) {
            if (!open) return;
            open = false;
            try { sock.close(); } catch (IOException ignored) { }
            writer.shutdownNow();
            if ("host".equals(id)) {
                if (hostConn == this) hostConn = null;
                if (notify) emit("disconnected");
            } else {
                clients.remove(id);
                if (notify) emit("leave", "id", id);
            }
        }
    }
}
