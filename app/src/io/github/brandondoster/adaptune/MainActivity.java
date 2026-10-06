package io.github.brandondoster.adaptune;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * Adaptune: unofficial settings app for the Carsifi wireless Android Auto adapter.
 * Protocol (see capture/PROTOCOL.md): one JSON request per RFCOMM connection, one JSON reply.
 */
public class MainActivity extends Activity {
    static final UUID CONTROL_UUID = UUID.fromString("1d06e9a2-0392-11eb-adc1-0242ac120003");
    static final int TIMEOUT_MS = 90_000; // the adapter has taken ~61 s to acknowledge some updates
    static final int CONNECT_RETRY_MS = 30_000;
    static final int RETRY_DELAY_MS = 10_000;

    // Friendly names for the flags the official app exposes. Every other flag is listed by raw key.
    static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("mnlStrtCrsfCnct", "Manual start (off = auto connect on startup)");
        LABELS.put("prstStPsRbt", "Persist Stop/Pause after adapter reboot");
        LABELS.put("startStopBsdNthrBlth", "Start/Stop based on car Bluetooth name");
        LABELS.put("swtchCntrNmbr", "Magic button clicks: switch phones");
        LABELS.put("psRsmCntrNmbr", "Magic button clicks: pause/resume AA");
        LABELS.put("isAAMim", "Intercept AA protocol");
        LABELS.put("dpiNumber", "Screen DPI (0 = default, needs Intercept AA)");
        LABELS.put("is24ghz", "Use only 2.4 GHz Wi-Fi");
        LABELS.put("htsptChnl24ghz", "2.4 GHz channel");
        LABELS.put("htsptChnl5ghz", "5 GHz channel");
        LABELS.put("cntrCode", "Wi-Fi country code");
        LABELS.put("atUsbDtct", "Auto USB mode detect");
        LABELS.put("isAccSt", "USB mode: Accessory (off = Default)");
        LABELS.put("debuggable", "Debug mode");
    }

    interface Task { void run() throws Exception; }

    final ExecutorService io = Executors.newSingleThreadExecutor(); // one request at a time
    final Map<String, View> editors = new LinkedHashMap<>();
    LinearLayout root;
    TextView status;
    BluetoothDevice device;
    JSONObject ff = new JSONObject();
    boolean busy, loaded, resumed, askedPermission, showAdvanced; // UI thread only
    final Handler handler = new Handler(Looper.getMainLooper());
    final Runnable autoRetry = this::start;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);
        status = new TextView(this);
        TextView title = text("Adaptune");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(18);
        root.addView(title);
        root.addView(status);
        root.addView(button("Retry now", v -> start()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (!loaded) start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(autoRetry);
    }

    /** Tries to reach the adapter; until the first read succeeds it keeps retrying while the app is open. */
    void start() {
        handler.removeCallbacks(autoRetry);
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            if (!askedPermission) {
                askedPermission = true;
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            } else {
                status.setText("Bluetooth permission is required. Grant it in Settings > Apps > Adaptune.");
            }
            return;
        }
        if (device == null) pickDevice();
        else refresh();
    }

    /** Called on the UI thread when an attempt couldn't reach the adapter. */
    void retryLater(String why) {
        if (loaded) return;
        status.setText(why + "\nRetrying in 10 s…");
        if (resumed) handler.postDelayed(autoRetry, RETRY_DELAY_MS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        start();
    }

    // ---- adapter selection ----

    void pickDevice() {
        BluetoothAdapter bt = getSystemService(BluetoothManager.class).getAdapter();
        if (bt == null || !bt.isEnabled()) {
            retryLater("Bluetooth is off.");
            return;
        }
        List<BluetoothDevice> bonded = new ArrayList<>(bt.getBondedDevices());
        String saved = getPreferences(MODE_PRIVATE).getString("address", null);
        for (BluetoothDevice d : bonded) {
            if (d.getAddress().equals(saved)) {
                device = d;
                refresh();
                return;
            }
        }
        List<BluetoothDevice> carsifi = new ArrayList<>();
        for (BluetoothDevice d : bonded) if (String.valueOf(d.getName()).startsWith("Carsifi")) carsifi.add(d);
        List<BluetoothDevice> choices = carsifi.isEmpty() ? bonded : carsifi;
        if (choices.isEmpty()) {
            retryLater("No paired Bluetooth devices. Pair the adapter in Android Bluetooth settings.");
        } else if (choices.size() == 1) {
            use(choices.get(0));
        } else {
            String[] names = new String[choices.size()];
            for (int i = 0; i < names.length; i++) names[i] = choices.get(i).getName() + "  " + choices.get(i).getAddress();
            new AlertDialog.Builder(this).setTitle("Choose your Carsifi adapter")
                    .setItems(names, (d, i) -> use(choices.get(i))).setCancelable(false).show();
        }
    }

    void use(BluetoothDevice d) {
        device = d;
        getPreferences(MODE_PRIVATE).edit().putString("address", d.getAddress()).apply();
        refresh();
    }

    // ---- protocol ----

    static JSONObject cmd(String name) throws JSONException {
        return new JSONObject().put("command", name);
    }

    /**
     * Opens the control channel, retrying for up to CONNECT_RETRY_MS: the adapter drops every
     * connection for a few seconds when it switches phones or restarts its AA session.
     */
    BluetoothSocket connect() throws Exception {
        long deadline = System.currentTimeMillis() + CONNECT_RETRY_MS;
        while (true) {
            BluetoothSocket socket = device.createRfcommSocketToServiceRecord(CONTROL_UUID);
            try {
                socket.connect();
                return socket;
            } catch (IOException e) {
                socket.close();
                if (System.currentTimeMillis() > deadline) throw new IOException("adapter not reachable: " + e.getMessage());
                runOnUiThread(() -> status.setText("Adapter busy, retrying…"));
                Thread.sleep(2_000);
            }
        }
    }

    /** Sends one request on a fresh connection. Replies can span several reads, so buffer until the JSON parses. */
    JSONObject send(JSONObject request) throws Exception {
        BluetoothSocket socket = connect();
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(TIMEOUT_MS);
                socket.close();
            } catch (InterruptedException | IOException ignored) {
            }
        });
        watchdog.start();
        try {
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n; (n = in.read(chunk)) > 0; ) {
                buf.write(chunk, 0, n);
                JSONObject reply;
                try {
                    reply = (JSONObject) new JSONTokener(buf.toString("UTF-8")).nextValue();
                } catch (JSONException incomplete) {
                    continue;
                }
                if (!reply.optBoolean("status")) throw new IOException("adapter replied " + reply);
                return reply;
            }
            throw new IOException("adapter closed the connection without replying");
        } finally {
            watchdog.interrupt();
            socket.close();
        }
    }

    void run(String what, Task task) {
        if (busy) return; // one request at a time; extra taps would just queue more connections
        busy = true;
        status.setText(what + "…");
        io.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (loaded) status.setText(what + " failed: " + e.getMessage());
                    else retryLater("Can't reach the adapter: " + e.getMessage());
                });
            } finally {
                runOnUiThread(() -> busy = false);
            }
        });
    }

    void refresh() {
        run("Reading adapter", this::loadInfo);
    }

    /** Re-reads everything from the adapter and redraws. Call from the io thread. */
    void loadInfo() throws Exception {
        JSONObject value = send(cmd("info")).getJSONObject("value");
        runOnUiThread(() -> {
            try {
                render(value);
                loaded = true;
                status.setText("Up to date");
            } catch (JSONException e) {
                status.setText("Unexpected reply: " + e.getMessage());
            }
        });
    }

    // ---- UI ----

    void render(JSONObject value) throws JSONException {
        root.removeAllViews();
        editors.clear();
        JSONObject info = value.optJSONObject("info");
        if (info == null) info = new JSONObject();
        ff = value.optJSONObject("ff");
        if (ff == null) ff = new JSONObject();
        // The adapter only reports debug mode once it's been set, so until then show the last value this app set (default off).
        if (!ff.has("debuggable")) ff.put("debuggable", getPreferences(MODE_PRIVATE).getBoolean("debuggable", false));

        JSONObject versions = info.optJSONObject("versions");
        TextView title = text(device.getName() + (versions == null ? "" : "  ·  firmware " + versions.optString("software")));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(18);
        root.addView(title);
        root.addView(status);

        LinearLayout actions = row();
        actions.addView(button("Refresh", v -> refresh()));
        actions.addView(button("Save changes", v -> save()));
        root.addView(actions);
        LinearLayout actions2 = row();
        actions2.addView(button("Reset to defaults", v -> confirm(
                "Reset all adapter settings to defaults?",
                () -> run("Resetting", () -> { send(cmd("resetFF")); loadInfo(); }))));
        actions2.addView(button("Save logs", v -> saveLogs()));
        actions2.addView(button("Clear logs", v -> confirm(
                "Erase the adapter's current log? Save it first if you need it.",
                () -> run("Clearing logs", () -> {
                    JSONArray files = new JSONArray().put(new JSONObject()
                            .put("filename", "session_dmesg.log").put("isPart", false).put("index", -1));
                    send(cmd("eraseLogs").put("values", files));
                    runOnUiThread(() -> status.setText("Adapter log cleared"));
                }))));
        root.addView(actions2);

        renderWifi(info.optJSONObject("currentGateway"));
        renderPaired(value.optJSONArray("paired"));

        section("Settings");
        for (Map.Entry<String, String> e : LABELS.entrySet()) {
            if (ff.has(e.getKey())) addEditor(root, e.getKey(), e.getValue() + "  (" + e.getKey() + ")");
        }
        // Flags the official app doesn't expose: still saved with everything else, just folded away.
        LinearLayout advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.addView(text("Not in the official app. These are the adapter's raw keys, change them at your own risk."));
        int count = 0;
        for (Iterator<String> keys = ff.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!LABELS.containsKey(key)) {
                addEditor(advanced, key, key);
                count++;
            }
        }
        advanced.setVisibility(showAdvanced ? View.VISIBLE : View.GONE);
        String label = "Advanced settings (" + count + ")";
        Button toggle = button((showAdvanced ? "Hide " : "Show ") + label, null);
        toggle.setOnClickListener(v -> {
            showAdvanced = !showAdvanced;
            advanced.setVisibility(showAdvanced ? View.VISIBLE : View.GONE);
            toggle.setText((showAdvanced ? "Hide " : "Show ") + label);
        });
        root.addView(toggle);
        root.addView(advanced);
        root.addView(button("Save changes", v -> save()));
    }

    void renderWifi(JSONObject gateway) {
        section("Wi-Fi gateway");
        String type = gateway == null ? "" : gateway.optString("wifiType");
        root.addView(text("Current: " + (gateway == null ? "unknown" : gateway.optString("wifiName") + "  (" + type + ")")));

        RadioGroup group = new RadioGroup(this);
        String[] labels = {"Carsifi Wi-Fi (adapter's own hotspot)", "Phone hotspot (untested)", "Other Wi-Fi"};
        String[] types = {"device_hotspot", "mobile_hotspot", "wifi"};
        for (int i = 0; i < labels.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(i + 1);
            r.setText(labels[i]);
            group.addView(r);
            if (types[i].equals(type)) group.check(r.getId());
        }
        root.addView(group);
        EditText ssid = edit("Wi-Fi name", "device_hotspot".equals(type) || gateway == null ? "" : gateway.optString("wifiName"));
        EditText password = edit("Wi-Fi password", "");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(ssid);
        root.addView(password);
        root.addView(button("Apply Wi-Fi", v -> {
            int choice = group.getCheckedRadioButtonId();
            if (choice < 1) {
                status.setText("Pick a Wi-Fi option first");
                return;
            }
            String name = ssid.getText().toString().trim();
            String pw = password.getText().toString();
            if (choice != 1 && (name.isEmpty() || pw.isEmpty())) {
                status.setText("Enter the Wi-Fi name and password");
                return;
            }
            try {
                JSONObject g = new JSONObject()
                        .put("wifiName", choice == 1 ? JSONObject.NULL : name)
                        .put("wifiPassword", choice == 1 ? JSONObject.NULL : pw)
                        .put("wifiBssid", JSONObject.NULL)
                        .put("wifi_type", new String[]{"WIFI_TYPE.DEVICE_HOTSPOT", "WIFI_TYPE.MOBILE_HOTSPOT", "WIFI_TYPE.WIFI"}[choice - 1]);
                confirm("Switch the adapter's Wi-Fi to " + labels[choice - 1] + "?",
                        () -> run("Updating Wi-Fi", () -> { send(cmd("updateGateway").put("gateway", g)); loadInfo(); }));
            } catch (JSONException e) {
                status.setText(e.getMessage());
            }
        }));
    }

    void renderPaired(JSONArray paired) throws JSONException {
        section("Paired phones");
        if (paired == null || paired.length() == 0) {
            root.addView(text("None"));
            return;
        }
        for (int i = 0; i < paired.length(); i++) {
            JSONObject phone = paired.getJSONObject(i);
            int index = i;
            LinearLayout line = row();
            TextView label = text(phone.optString("name") + "\n" + phone.optString("address"));
            line.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            line.addView(button("Remove", v -> confirm("Remove " + phone.optString("name") + " from the adapter?", () -> {
                // The adapter has no remove command; the app sends the list of phones to keep.
                JSONArray keep = new JSONArray();
                for (int j = 0; j < paired.length(); j++) {
                    if (j != index) keep.put(paired.optJSONObject(j).optString("address"));
                }
                run("Removing phone (the adapter can take up to a minute)", () -> { send(cmd("updateOrder").put("values", keep)); loadInfo(); });
            })));
            root.addView(line);
        }
    }

    void addEditor(LinearLayout parent, String key, String label) {
        Object value = ff.opt(key);
        if (value instanceof Boolean) {
            Switch s = new Switch(this);
            s.setText(label);
            s.setChecked((Boolean) value);
            s.setPadding(0, dp(6), 0, dp(6));
            editors.put(key, s);
            parent.addView(s);
        } else if (value instanceof Number || value instanceof String) {
            parent.addView(text(label));
            EditText e = edit(key, String.valueOf(value));
            if (value instanceof Number) {
                e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                        | (value instanceof Double ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
            }
            editors.put(key, e);
            parent.addView(e);
        } else {
            parent.addView(text(label + ": " + value + "  (read-only)"));
        }
    }

    /** Sends only the flags that changed, like the official app. */
    void save() {
        JSONObject changed = new JSONObject();
        try {
            for (Map.Entry<String, View> e : editors.entrySet()) {
                Object old = ff.opt(e.getKey());
                Object now;
                if (e.getValue() instanceof Switch) {
                    now = ((Switch) e.getValue()).isChecked();
                } else {
                    String t = ((EditText) e.getValue()).getText().toString().trim();
                    if (old instanceof Double) now = Double.parseDouble(t);
                    else if (old instanceof Number) now = Long.parseLong(t);
                    else now = t;
                }
                if (!String.valueOf(now).equals(String.valueOf(old))) changed.put(e.getKey(), now);
            }
        } catch (NumberFormatException | JSONException ex) {
            status.setText("Invalid value: " + ex.getMessage());
            return;
        }
        if (changed.length() == 0) {
            status.setText("No changes to save");
            return;
        }
        confirm("Send " + changed.length() + " change(s) to the adapter?\n\n" + changed,
                () -> run("Saving (the adapter can take up to a minute)", () -> {
                    send(cmd("updateFF").put("values", changed));
                    if (changed.has("debuggable")) {
                        getPreferences(MODE_PRIVATE).edit().putBoolean("debuggable", changed.getBoolean("debuggable")).apply();
                    }
                    loadInfo();
                }));
    }

    /**
     * Downloads the adapter's logs into Downloads, one file per log. The adapter sends ~3 MB per
     * syncLogs; a reply with isPart=true means more is waiting, and the delivered part has to be
     * trimmed (eraseLogs with that part's index) before the next sync returns the rest. The last
     * (or only) part is left on the adapter; Clear logs removes it.
     */
    void saveLogs() {
        run("Downloading logs", () -> {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            Map<String, OutputStream> files = new LinkedHashMap<>();
            Set<String> done = new HashSet<>();
            String lastPart = null;
            long bytes = 0;
            int parts = 0;
            try {
                while (true) {
                    JSONArray logs = send(cmd("syncLogs")).getJSONObject("value").optJSONArray("logs");
                    if (logs == null || logs.length() == 0) break;
                    JSONArray trim = new JSONArray();
                    for (int i = 0; i < logs.length(); i++) {
                        JSONObject log = logs.getJSONObject(i);
                        String content = log.optString("content");
                        if (content.isEmpty()) continue;
                        String filename = log.optString("filename", "log.txt");
                        boolean isPart = log.optBoolean("isPart");
                        // Whole files (e.g. the rotated session_dmesg_backup0.log) come back on every sync; keep one copy.
                        if (!isPart && done.contains(filename)) continue;
                        if (!isPart) done.add(filename);
                        OutputStream out = files.get(filename);
                        if (out == null) {
                            out = newDownload("carsifi-" + stamp + "-" + filename + ".txt");
                            files.put(filename, out);
                        }
                        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(Base64.decode(content, Base64.DEFAULT)))) {
                            byte[] text = in.readAllBytes();
                            out.write(text);
                            bytes += text.length;
                        }
                        if (isPart) {
                            trim.put(new JSONObject().put("filename", filename).put("isPart", true).put("index", log.getLong("index")));
                        }
                    }
                    parts++;
                    long soFar = bytes;
                    int partsSoFar = parts;
                    runOnUiThread(() -> status.setText(String.format(Locale.US, "Downloading logs… %.1f MB, %d part(s)", soFar / 1e6, partsSoFar)));
                    if (trim.length() == 0) break;
                    if (trim.toString().equals(lastPart)) throw new IOException("adapter sent the same part twice, it didn't trim");
                    lastPart = trim.toString();
                    send(cmd("eraseLogs").put("values", trim));
                }
            } finally {
                for (OutputStream out : files.values()) out.close();
            }
            long total = bytes;
            int count = parts;
            String names = String.join("\n", files.keySet());
            runOnUiThread(() -> status.setText(files.isEmpty() ? "The adapter has no logs"
                    : String.format(Locale.US, "Saved %.1f MB in %d part(s) to Downloads:\n%s", total / 1e6, count, names)));
        });
    }

    OutputStream newDownload(String name) throws IOException {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
        cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("couldn't create " + name + " in Downloads");
        return getContentResolver().openOutputStream(uri);
    }

    // ---- small view helpers ----

    void confirm(String message, Runnable onYes) {
        new AlertDialog.Builder(this).setMessage(message)
                .setPositiveButton("OK", (d, w) -> onYes.run())
                .setNegativeButton("Cancel", null).show();
    }

    void section(String name) {
        TextView t = text(name);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(16);
        t.setPadding(0, dp(20), 0, dp(4));
        root.addView(t);
    }

    TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextIsSelectable(true);
        return t;
    }

    EditText edit(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        return e;
    }

    Button button(String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(click);
        b.setAllCaps(false);
        return b;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
