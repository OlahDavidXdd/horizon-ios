package dev.horizon.android;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import com.qmdeve.liquidglass.widget.LiquidGlassView;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** The native game and launcher share one Activity and the game's command queue. */
public final class HorizonActivity extends NativeActivity {
    static { System.loadLibrary("horizon_android"); }
    private static native long submit(String line);
    private static native String poll(long id);
    private static native void abandon(long id);
    private static native boolean isCovered();
    private final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService workers = Executors.newCachedThreadPool();
    private FrameLayout overlay, backdrop;
    private LinearLayout page, navigation;
    private TextView status;
    private boolean destroyed, launching, visible = true;
    private String tab = "play", importKind = "resources", phase = "unknown";
    private String launchOwner;
    private Accounts accounts;
    private final Runnable watch = new Runnable() {
        public void run() {
            if (destroyed) return;
            boolean covered = isCovered();
            if (covered != visible) setVisible(covered);
            if (visible) ask("launcher-state", state -> {
                phase = state.optString("phase");
                String message = t(phase);
                if (state.has("progress")) message += " " + Math.round(state.optDouble("progress") * 100) + "%";
                JSONObject importing = state.optJSONObject("import");
                if (importing != null) message = importing.optString("label") + " " + importing.optInt("files") + "/" + importing.optInt("total_files");
                status.setText(message);
            });
            ui.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        accounts = new Accounts(this);
        overlay = new FrameLayout(this);
        backdrop = new FrameLayout(this);
        GradientDrawable sky = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xff102e39, 0xff183e48, 0xff10202b});
        backdrop.setBackground(sky);
        overlay.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout shell = new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(24), dp(12), dp(24), dp(12));
        LinearLayout header = row();
        TextView title = label(t("app_name"), 27); title.setTypeface(null, 1);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(button("account", () -> accounts.show()));
        shell.addView(header);
        status = label(t("starting"), 13); shell.addView(status);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        page = new LinearLayout(this); page.setOrientation(1); page.setPadding(0, dp(20), 0, dp(16));
        scroll.addView(page); shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = row();
        for (String name : new String[]{"play", "packs", "settings"}) {
            Button b = button(name, () -> { tab = name; render(); });
            navigation.addView(b, new LinearLayout.LayoutParams(0, dp(54), 1));
        }
        FrameLayout glass = new FrameLayout(this);
        if (Build.VERSION.SDK_INT >= 33 && !getPreferences(0).getBoolean("reduce_transparency", false)) {
            LiquidGlassView material = new LiquidGlassView(this);
            material.bind(backdrop); material.setCornerRadius(dp(24)); material.setBlurRadius(dp(12));
            material.setTintAlpha(0.12f);
            glass.addView(material, new FrameLayout.LayoutParams(-1, -1));
        } else glass.setBackgroundColor(0xff203d46);
        glass.addView(navigation, new FrameLayout.LayoutParams(-1, -1));
        shell.addView(glass, new LinearLayout.LayoutParams(-1, dp(54)));
        overlay.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        addContentView(overlay, new ViewGroup.LayoutParams(-1, -1));
        render(); ui.post(watch);
    }
    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    String t(String key) {
        int id = getResources().getIdentifier(key, "string", getPackageName());
        return id == 0 ? key : getString(id);
    }
    TextView label(String value, int size) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(0xffeef9fa);
        v.setPadding(dp(6), dp(6), dp(6), dp(6)); return v;
    }
    LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); return r; }
    Button button(String key, Runnable action) {
        Button b = new Button(this); b.setText(t(key)); b.setAllCaps(false); b.setTextColor(0xffb9fff0);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x252cf0cc));
        b.setOnClickListener(v -> action.run()); return b;
    }
    void addButton(String key, Runnable action) { page.addView(button(key, action)); }
    void heading(String key) { page.addView(label(t(key), 22)); }
    interface Reply { void done(JSONObject value); }
    void ask(String line, Reply reply) {
        long id = submit(line);
        final long deadline = SystemClock.uptimeMillis() + 120000;
        ui.post(new Runnable() { public void run() {
            if (destroyed) { abandon(id); return; }
            String result = poll(id);
            if (result == null && SystemClock.uptimeMillis() < deadline) { ui.postDelayed(this, 30); return; }
            if (result == null) { abandon(id); launching = false; error(t("timeout")); return; }
            try {
                JSONObject answer = new JSONObject(result);
                if (answer.has("error")) { launching = false; error(answer.optString("error")); return; }
                reply.done(answer);
            } catch (JSONException e) { error(t("bad_reply")); }
        }});
    }
    void error(String message) {
        if (!destroyed) new AlertDialog.Builder(this).setTitle(t("error")).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show();
    }
    JSONObject object(String... pairs) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) o.put(pairs[i], pairs[i+1]); }
        catch (JSONException e) { throw new IllegalArgumentException(e); }
        return o;
    }
    void setVisible(boolean value) {
        visible = value; overlay.setVisibility(value ? View.VISIBLE : View.GONE);
        if (value) render();
    }
    void render() {
        page.removeAllViews(); heading(tab);
        switch (tab) {
            case "play": home(); break;
            case "packs": packs(); break;
            default: settings();
        }
    }
    private void home() {
        page.addView(label(t("java_edition"), 13));
        addButton("play", () -> launch(null));
        LinearLayout actions = row();
        actions.addView(button("new_world", this::newWorld));
        actions.addView(button("import_world", () -> pick("world")));
        actions.addView(button("add_server", this::addServer)); page.addView(actions);
        heading("worlds");
        LinearLayout worlds = new LinearLayout(this); worlds.setOrientation(1); page.addView(worlds);
        ask("worlds", result -> {
            JSONArray list = result.optJSONArray("worlds");
            if (list == null || list.length() == 0) worlds.addView(label(t("empty_worlds"), 16));
            else for (int i = 0; i < list.length(); i++) {
                JSONObject world = list.optJSONObject(i); if (world == null) continue;
                LinearLayout card = row();
                String folder = world.optString("folder");
                Button open = button(world.optString("name", folder), () -> launch("launcher-world " + object("action", "play", "folder", folder)));
                card.addView(open, new LinearLayout.LayoutParams(0, dp(56), 1));
                card.addView(button("more", () -> worldMenu(folder, world.optString("name", folder)))); worlds.addView(card);
                worlds.addView(label(world.optString("mode") + " · " + world.optString("version"), 12));
            }
        });
        heading("servers");
        LinearLayout servers = new LinearLayout(this); servers.setOrientation(1); page.addView(servers);
        ask("servers", result -> {
            JSONArray list = result.optJSONArray("servers");
            if (list == null || list.length() == 0) servers.addView(label(t("empty_servers"), 16));
            else for (int i = 0; i < list.length(); i++) {
                JSONObject s = list.optJSONObject(i); if (s == null) continue;
                int index = s.optInt("index", i); String address = s.optString("address");
                LinearLayout r = row();
                r.addView(button(s.optString("name", address), () -> launch("connect " + address)), new LinearLayout.LayoutParams(0, dp(56), 1));
                r.addView(button("remove", () -> confirm("remove_server", () -> ask("server-remove " + index, v -> render()))));
                servers.addView(r);
                TextView ping = label(address, 12); servers.addView(ping);
                ask("server-ping " + address, p -> ping.setText(address + " · " + p.optInt("online") + "/" + p.optInt("max") + " · " + p.optInt("ms") + " ms"));
            }
        });
    }
    private void launch(String command) {
        if (launching) return;
        launching = true; launchOwner = UUID.randomUUID().toString();
        String owner = launchOwner;
        accounts.handOver(() -> ask("launcher-start " + owner, answer -> {
            if (!answer.optBoolean("ok")) { launching = false; return; }
            ui.post(new Runnable() { public void run() {
                if (destroyed || !owner.equals(launchOwner)) return;
                ask("launcher-state", state -> {
                    String p = state.optString("phase");
                    if (p.equals("title") || p.equals("world")) {
                        Runnable finish = () -> ask("launcher-commit-start " + owner, commit -> {
                            if (!commit.optBoolean("ok")) { launching = false; return; }
                            ask("launcher-visible false", v -> { launching = false; setVisible(false); });
                        });
                        if (command == null) finish.run(); else ask(command, v -> finish.run());
                    } else if (p.equals("needs_files")) {
                        launching = false; error(state.optString("reason", t("need_files")));
                    } else { status.setText(t("loading")); ui.postDelayed(this, 300); }
                });
            }});
        }));
    }
    void confirm(String title, Runnable yes) {
        new AlertDialog.Builder(this).setTitle(t(title)).setPositiveButton(android.R.string.ok, (d,w) -> yes.run())
            .setNegativeButton(android.R.string.cancel, null).show();
    }
    void prompt(String title, String initial, java.util.function.Consumer<String> done) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(initial);
        new AlertDialog.Builder(this).setTitle(t(title)).setView(input)
            .setPositiveButton(android.R.string.ok, (d,w) -> done.accept(input.getText().toString().trim()))
            .setNegativeButton(android.R.string.cancel, null).show();
    }
    private void newWorld() {
        prompt("world_name", "", name -> {
            if (name.isEmpty()) return;
            String[] modes = {t("survival"), t("creative"), t("hardcore")};
            new AlertDialog.Builder(this).setTitle(t("game_mode")).setItems(modes, (d, mode) -> {
                JSONObject data = object("name", name, "mode", mode == 1 ? "creative" : mode == 2 ? "hardcore" : "survival");
                try { data.put("hardcore", mode == 2); } catch (JSONException e) { throw new IllegalStateException(e); }
                launch("world-create " + data);
            }).show();
        });
    }
    private void worldMenu(String folder, String name) {
        String[] labels = {t("rename"), t("backup"), t("delete")};
        new AlertDialog.Builder(this).setTitle(name).setItems(labels, (d, which) -> {
            if (which == 0) prompt("rename", name, value -> ask("launcher-world " + object("action", "rename", "folder", folder, "name", value), v -> render()));
            if (which == 1) ask("launcher-world " + object("action", "backup", "folder", folder), v -> Toast.makeText(this, t("backed_up"), Toast.LENGTH_LONG).show());
            if (which == 2) confirm("delete_world", () -> ask("launcher-world " + object("action", "delete", "folder", folder), v -> render()));
        }).show();
    }
    private void addServer() {
        prompt("server_address", "", address -> {
            if (address.isEmpty() || address.matches(".*\\s+.*")) return;
            prompt("server_name", "", name -> ask("server-add " + address + " " + (name.isEmpty() ? address : name), v -> render()));
        });
    }
    private void packs() {
        addButton("import_resources", () -> pick("resources"));
        addButton("import_pack", () -> pick("pack"));
        addButton("import_shader", () -> pick("shader"));
        addButton("import_mod", () -> pick("mod"));
        heading("resource_packs");
        File[] files = new File(homeDir(), "resourcepacks").listFiles();
        if (files != null) ask("options-get resourcePacks", result -> {
            JSONObject option = result.optJSONObject("options");
            JSONObject description = option == null ? null : option.optJSONObject("resourcePacks");
            JSONArray selected = description == null ? new JSONArray() : description.optJSONArray("value");
            Set<String> active = new LinkedHashSet<>();
            if (selected != null) for (int i=0; i<selected.length(); i++) active.add(selected.optString(i));
            for (File file : files) {
                CheckBox check = new CheckBox(this); check.setText(file.getName()); check.setTextColor(Color.WHITE);
                check.setChecked(active.contains("file/" + file.getName()));
                check.setOnCheckedChangeListener((b, on) -> {
                    if (on) active.add("file/" + file.getName()); else active.remove("file/" + file.getName());
                    ask("options-set resourcePacks " + new JSONArray(active), v -> {});
                }); page.addView(check);
            }
        });
        heading("shader_packs");
        ask("shader-pack list", result -> {
            addButton("disable_shader", () -> ask("shader-pack off", v -> render()));
            File[] shaders = new File(homeDir(), "shaderpacks").listFiles();
            if (shaders != null) for (File shader : shaders) addButton(shader.getName(), () -> ask("shader-pack " + shader.getName(), v -> render()));
        });
        heading("mods");
        ask("mods-list", result -> {
            JSONArray mods = result.optJSONArray("mods");
            if (mods != null) for (int i=0; i<mods.length(); i++) {
                JSONObject mod = mods.optJSONObject(i); if (mod == null) continue;
                CheckBox check = new CheckBox(this); check.setText(mod.optString("name", mod.optString("id"))); check.setTextColor(Color.WHITE);
                check.setChecked(mod.optBoolean("enabled"));
                check.setOnCheckedChangeListener((b,on) -> {
                    JSONObject args = object("id", mod.optString("id"));
                    try { args.put("on", on); } catch (JSONException e) { throw new IllegalStateException(e); }
                    ask("mods-set " + args, v -> {});
                }); page.addView(check);
            }
        });
    }
    File homeDir() { File f = getExternalFilesDir(null); return f == null ? getFilesDir() : f; }
    private void pick(String kind) {
        importKind = kind;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, 10);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 10 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData(); String kind = importKind;
        workers.execute(() -> {
            try {
                String name = "import-" + UUID.randomUUID() + (kind.equals("mod") ? ".hmod" : ".zip");
                File dir = new File(homeDir(), kind.equals("shader") ? "shaderpacks" : ".horizon-import");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException(t("cannot_import"));
                File destination = new File(dir, name);
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(destination)) {
                    if (in == null) throw new IOException(t("cannot_import"));
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                ui.post(() -> {
                    if (kind.equals("shader")) { render(); return; }
                    String line = kind.equals("mod") ? "mods-install " + object("path", destination.getAbsolutePath()) : "import " + destination.getAbsolutePath();
                    ask(line, v -> { destination.delete(); render(); });
                });
            } catch (Exception e) { ui.post(() -> error(e.getLocalizedMessage())); }
        });
    }
    private void settings() {
        addButton("import_resources", () -> pick("resources"));
        addButton("download_files", () -> ask("game-files-source", source -> confirm("download_files", () -> download(source.optString("url")))));
        addButton("game_settings", this::gameSettings);
        addButton("screenshots", this::gallery);
        addButton("updates", () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/OlahDavidXdd/horizon-ios/releases"))));
        addButton("licenses", () -> {
            try (InputStream in = getAssets().open("AndroidLiquidGlassView-LICENSE.txt")) {
                new AlertDialog.Builder(this).setTitle("AndroidLiquidGlassView · MIT").setMessage(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).setPositiveButton(android.R.string.ok,null).show();
            } catch (IOException e) { error(e.getMessage()); }
        });
        CheckBox transparency = new CheckBox(this); transparency.setText(t("reduce_transparency")); transparency.setTextColor(Color.WHITE);
        transparency.setChecked(getPreferences(0).getBoolean("reduce_transparency",false));
        transparency.setOnCheckedChangeListener((b,on) -> { getPreferences(0).edit().putBoolean("reduce_transparency",on).apply(); Toast.makeText(this, t("restart_required"), Toast.LENGTH_LONG).show(); });
        page.addView(transparency);
        page.addView(label("Horizon · 0.2.92-android · ARM64",14));
    }
    private void download(String address) {
        workers.execute(() -> {
            File zip = new File(homeDir(), ".horizon-import/download-" + UUID.randomUUID() + ".zip");
            try {
                java.net.URL url = new java.net.URL(address);
                if (!url.getProtocol().equals("https")) throw new IOException(t("https_required"));
                zip.getParentFile().mkdirs();
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection)url.openConnection();
                connection.setConnectTimeout(30000); connection.setReadTimeout(30000);
                if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(zip)) {
                    byte[] buffer = new byte[65536]; int n; long count = 0;
                    while ((n = in.read(buffer)) != -1) { out.write(buffer,0,n); count += n;
                        long bytes = count; ui.post(() -> status.setText(t("downloading") + " " + bytes / 1048576 + " MB"));
                    }
                } finally { connection.disconnect(); }
                ui.post(() -> ask("import " + zip.getAbsolutePath(), v -> { zip.delete(); render(); }));
            } catch (Exception e) { zip.delete(); ui.post(() -> error(e.getLocalizedMessage())); }
        });
    }
    private void gameSettings() {
        page.removeAllViews(); heading("game_settings");
        String[] keys = {"renderDistance","simulationDistance","maxFps","fov","gamma","guiScale","particles","renderClouds","entityShadows","bobView","soundCategory_master","soundCategory_music","soundCategory_ambient","showSubtitles","mouseSensitivity","autoJump","invertYMouse"};
        for (String key : keys) ask("options-get " + key, result -> {
            JSONObject options = result.optJSONObject("options"); if (options == null) return;
            JSONObject spec = options.optJSONObject(key); if (spec == null) return;
            addButton(key + ": " + spec.opt("value"), () -> prompt(key, String.valueOf(spec.opt("value")), value -> ask("options-set " + key + " " + value, v -> gameSettings())));
        });
    }
    private void gallery() {
        page.removeAllViews(); heading("screenshots");
        File[] images = new File(homeDir(),"screenshots").listFiles();
        if (images == null) return;
        Arrays.sort(images, (a,b) -> Long.compare(b.lastModified(),a.lastModified()));
        for (File image : images) if (image.getName().endsWith(".png")) {
            ImageView thumbnail = new ImageView(this); thumbnail.setAdjustViewBounds(true);
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options(); opts.inSampleSize = 4;
            thumbnail.setImageBitmap(android.graphics.BitmapFactory.decodeFile(image.getAbsolutePath(),opts));
            thumbnail.setContentDescription(image.getName()); page.addView(thumbnail,new LinearLayout.LayoutParams(-1,dp(180)));
        }
    }
    @Override public void onBackPressed() {
        if (launching && launchOwner != null) {
            String owner = launchOwner; launchOwner = null;
            ask("launcher-cancel-start " + owner, v -> { launching = false; render(); });
        } else if (!visible) ask("launcher-visible true", v -> setVisible(true));
        else { tab = "play"; render(); }
    }
    @Override protected void onDestroy() { destroyed = true; ui.removeCallbacksAndMessages(null); workers.shutdownNow(); super.onDestroy(); }
}
