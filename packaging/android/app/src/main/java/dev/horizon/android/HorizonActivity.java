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
    private static native void initPlatform();
    private static java.lang.ref.WeakReference<HorizonActivity> current=new java.lang.ref.WeakReference<>(null);
    public static String readClipboard() {
        HorizonActivity activity=current.get();if(activity==null)return null;
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)activity.getSystemService(CLIPBOARD_SERVICE);
        ClipData data=clipboard.getPrimaryClip();if(data==null||data.getItemCount()==0)return "";
        CharSequence text=data.getItemAt(0).coerceToText(activity);return text==null?"":text.toString();
    }
    public static void writeClipboard(String text) {
        HorizonActivity activity=current.get();if(activity==null)return;
        activity.runOnUiThread(() -> ((android.content.ClipboardManager)activity.getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Horizon",text)));
    }
    private static native boolean isCovered();
    private static native boolean keyboardWanted();
    private static native void controllerChanged(int device,String name,float[] axes,int buttons);
    private static native void controllerRemoved(int device);
    private final android.util.SparseArray<Pad> controllers=new android.util.SparseArray<>();
    private android.hardware.input.InputManager inputManager;
    private final android.hardware.input.InputManager.InputDeviceListener deviceListener=new android.hardware.input.InputManager.InputDeviceListener(){
        public void onInputDeviceAdded(int id){}
        public void onInputDeviceChanged(int id){}
        public void onInputDeviceRemoved(int id){controllers.remove(id);controllerRemoved(id);}
    };
    private static final class Pad {final float[] axes=new float[8];int buttons;}

    private View inputBridge;
    private boolean keyboardVisible;
    private static native void setTouchPreferences(float[] values, int flags);
    private final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService workers = Executors.newCachedThreadPool();
    private FrameLayout overlay, backdrop;
    private Dialog launcherWindow;
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
            if (covered != visible) setLauncherVisible(covered);
            boolean wantKeyboard = !covered && keyboardWanted();
            if(wantKeyboard != keyboardVisible) {
                keyboardVisible = wantKeyboard;
                android.view.inputmethod.InputMethodManager ime=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                if(wantKeyboard) {inputBridge.requestFocus();ime.showSoftInput(inputBridge,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);}
                else {ime.hideSoftInputFromWindow(inputBridge.getWindowToken(),0);inputBridge.clearFocus();}
            }
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
        current=new java.lang.ref.WeakReference<>(this);initPlatform();
        accounts = new Accounts(this);
        applyTouchPreferences();
        overlay = new FrameLayout(this);
        backdrop = new FrameLayout(this);
        GradientDrawable sky = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xff102e39, 0xff183e48, 0xff10202b});
        backdrop.setBackground(sky);
        overlay.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout shell = new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(24), dp(12), dp(24), dp(12));
        LinearLayout header = row();
        TextView title = label(t("app_name"), 27); title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(button("account", () -> accounts.show()));
        shell.addView(header);
        status = label(t("starting"), 13); shell.addView(status);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(0, dp(20), 0, dp(16));
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
        // NativeActivity owns its main window surface for Vulkan. A separate
        // window lets Android render the launcher without sharing that producer.
        launcherWindow=new Dialog(this,android.R.style.Theme_Material_NoActionBar);
        launcherWindow.setContentView(overlay);launcherWindow.setCancelable(false);
        launcherWindow.setOnKeyListener((dialog,key,event) -> {
            if(key==android.view.KeyEvent.KEYCODE_BACK&&event.getAction()==android.view.KeyEvent.ACTION_UP){onBackPressed();return true;}
            return false;
        });
        launcherWindow.show();
        launcherWindow.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
        launcherWindow.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        launcherWindow.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        inputBridge = new View(this) {
            @Override public boolean onCheckIsTextEditor() {return true;}
            @Override public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo info) {
                info.inputType=android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;
                info.imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI;
                return new android.view.inputmethod.BaseInputConnection(this,false) {
                    String composing="";
                    @Override public boolean setComposingText(CharSequence text,int cursor) {composing=text.toString();return true;}
                    @Override public boolean finishComposingText() {if(!composing.isEmpty()){String text=composing;composing="";ask("type "+text,v -> {});}return true;}
                    @Override public boolean commitText(CharSequence text,int cursor) {composing="";ask("type "+text,v -> {});return true;}
                    @Override public boolean deleteSurroundingText(int before,int after) {
                        if(!composing.isEmpty()) {composing=composing.substring(0,Math.max(0,composing.length()-before));return true;}
                        for(int i=0;i<Math.min(before,100);i++)ask("type \\b",v -> {});return true;
                    }
                    @Override public boolean sendKeyEvent(android.view.KeyEvent event) {
                        if(event.getAction()!=android.view.KeyEvent.ACTION_DOWN)return true;
                        if(event.getKeyCode()==android.view.KeyEvent.KEYCODE_ENTER) {finishComposingText();ask("key Enter tap",v -> {});return true;}
                        if(event.getKeyCode()==android.view.KeyEvent.KEYCODE_DEL)return deleteSurroundingText(1,0);
                        int unicode=event.getUnicodeChar();if(unicode!=0)ask("type "+new String(Character.toChars(unicode)),v -> {});return true;
                    }
                };
            }
        };
        inputBridge.setFocusable(true);inputBridge.setFocusableInTouchMode(true);
        addContentView(inputBridge,new ViewGroup.LayoutParams(1,1));
        inputManager=(android.hardware.input.InputManager)getSystemService(INPUT_SERVICE);
        inputManager.registerInputDeviceListener(deviceListener,ui);
        render(); ui.post(watch);checkUpdates();
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
        launching = false;
        if (!destroyed) new AlertDialog.Builder(this).setTitle(t("error")).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show();
    }
    JSONObject object(String... pairs) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) o.put(pairs[i], pairs[i+1]); }
        catch (JSONException e) { throw new IllegalArgumentException(e); }
        return o;
    }
    void setLauncherVisible(boolean value) {
        visible = value;
        setRequestedOrientation(value ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        if(value) {
            overlay.setVisibility(View.VISIBLE);launcherWindow.show();
            launcherWindow.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
            render();
        } else launcherWindow.hide();
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
        LinearLayout worlds = new LinearLayout(this); worlds.setOrientation(LinearLayout.VERTICAL); page.addView(worlds);
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
        LinearLayout servers = new LinearLayout(this); servers.setOrientation(LinearLayout.VERTICAL); page.addView(servers);
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
                            ask("launcher-visible false", v -> { launching = false; setLauncherVisible(false); });
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
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20),0,dp(20),0);
        EditText name = new EditText(this); name.setHint(t("world_name")); name.setSingleLine(true); form.addView(name);
        EditText seed = new EditText(this); seed.setHint(t("seed")); seed.setSingleLine(true); form.addView(seed);
        Spinner mode = new Spinner(this); mode.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{t("survival"),t("creative"),t("hardcore")})); form.addView(mode);
        Spinner difficulty = new Spinner(this); difficulty.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{t("peaceful"),t("easy"),t("normal"),t("hard")})); difficulty.setSelection(2); form.addView(difficulty);
        String[] flags = {"commands","flat","structures","bonus_chest"};
        CheckBox[] toggles = new CheckBox[flags.length];
        for(int i=0;i<flags.length;i++) {toggles[i]=new CheckBox(this);toggles[i].setText(t(flags[i]));toggles[i].setChecked(i==2);form.addView(toggles[i]);}
        ScrollView scroll = new ScrollView(this);scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(t("new_world")).setView(scroll)
            .setPositiveButton(t("create"),null).setNegativeButton(android.R.string.cancel,null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title = name.getText().toString().trim();
            if(title.isEmpty()) {name.setError(t("world_name"));return;}
            JSONObject data = object("name",title,"seed",seed.getText().toString(),
                "mode",new String[]{"survival","creative","hardcore"}[mode.getSelectedItemPosition()],
                "difficulty",new String[]{"peaceful","easy","normal","hard"}[difficulty.getSelectedItemPosition()]);
            try {for(int i=0;i<flags.length;i++)data.put(flags[i],toggles[i].isChecked());}
            catch(JSONException e) {throw new IllegalStateException(e);}
            dialog.dismiss();launch("world-create " + data);
        }));dialog.show();
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
                });
                LinearLayout packRow=row();packRow.addView(check,new LinearLayout.LayoutParams(0,-2,1));
                packRow.addView(button("move_up",() -> {
                    String id="file/"+file.getName();List<String> order=new ArrayList<>(active);int index=order.indexOf(id);
                    if(index>=0&&index<order.size()-1){Collections.swap(order,index,index+1);active.clear();active.addAll(order);ask("options-set resourcePacks "+new JSONArray(order),v -> render());}
                }));
                packRow.addView(button("move_down",() -> {
                    String id="file/"+file.getName();List<String> order=new ArrayList<>(active);int index=order.indexOf(id);
                    if(index>0){Collections.swap(order,index,index-1);active.clear();active.addAll(order);ask("options-set resourcePacks "+new JSONArray(order),v -> render());}
                }));page.addView(packRow);
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
        addButton("offline_name", () -> prompt("offline_name", "", name -> {
            if(!name.matches("[!-~]{1,16}")) {error(t("invalid_name"));return;}
            try {
                android.util.AtomicFile file = new android.util.AtomicFile(new File(homeDir(),"horizon-player.txt"));
                FileOutputStream out = file.startWrite();
                try {out.write(("name:"+name+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));file.finishWrite(out);}
                catch(IOException e){file.failWrite(out);throw e;}
            } catch(IOException e){error(e.getLocalizedMessage());}
        }));
        addButton("game_settings", this::gameSettings);
        addButton("touch_controls", this::touchControls);
        addButton("screenshots", this::gallery);
        addButton("share_logs", () -> share(new File(homeDir(),".local/share/horizon/logs/latest.log"),"text/plain"));
        addButton("updates", () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/OlahDavidXdd/horizon-ios/releases"))));
        addButton("licenses", () -> {
            try (InputStream in = getAssets().open("AndroidLiquidGlassView-LICENSE.txt")) {
                new AlertDialog.Builder(this).setTitle("AndroidLiquidGlassView · MIT").setMessage(new String(readBytes(in), java.nio.charset.StandardCharsets.UTF_8)).setPositiveButton(android.R.string.ok,null).show();
            } catch (IOException e) { error(e.getMessage()); }
        });
        CheckBox transparency = new CheckBox(this); transparency.setText(t("reduce_transparency")); transparency.setTextColor(Color.WHITE);
        transparency.setChecked(getPreferences(0).getBoolean("reduce_transparency",false));
        transparency.setOnCheckedChangeListener((b,on) -> { getPreferences(0).edit().putBoolean("reduce_transparency",on).apply(); Toast.makeText(this, t("restart_required"), Toast.LENGTH_LONG).show(); });
        page.addView(transparency);
        page.addView(label("Horizon · 0.2.92-android · ARM64",14));
    }
    static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
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
    private float[] touchSnapshot() {
        float[] snapshot = new float[100];
        for(int i=0;i<snapshot.length;i++)snapshot[i]=getPreferences(0).getFloat("touch_"+i,Float.NaN);
        return snapshot;
    }
    private void applyTouchPreferences() {
        setTouchPreferences(touchSnapshot(),getPreferences(0).getInt("touch_flags",0));
    }
    private void touchControls() {
        page.removeAllViews();heading("touch_controls");
        String[] labels = {"hide_controls","virtual_mouse","joystick","disable_mouse_gestures","disable_hotbar_gestures","forward_lock"};
        for(int i=0;i<labels.length;i++) {
            int bit = 1<<i; CheckBox option = new CheckBox(this);option.setText(t(labels[i]));option.setTextColor(Color.WHITE);
            option.setChecked((getPreferences(0).getInt("touch_flags",0)&bit)!=0);
            option.setOnCheckedChangeListener((b,on) -> {
                int flags=getPreferences(0).getInt("touch_flags",0);
                getPreferences(0).edit().putInt("touch_flags",on?flags|bit:flags&~bit).apply();applyTouchPreferences();
            });page.addView(option);
        }
        addButton("edit_layout",this::editTouchLayout);
        addButton("reset_layout",() -> {
            android.content.SharedPreferences.Editor edit=getPreferences(0).edit();
            for(int i=0;i<100;i++)edit.remove("touch_"+i);edit.apply();applyTouchPreferences();
        });
    }
    private void editTouchLayout() {
        FrameLayout editor=new FrameLayout(this);editor.setBackgroundColor(0xff16313b);
        overlay.addView(editor,new FrameLayout.LayoutParams(-1,-1));
        String[] names={"forward","back","left","right","sneak","jump","perspective","chat","pause","inventory","left_click","right_click","joystick","sprint","middle_click","more","hide","runtime","debug","player_list"};
        float[] values=touchSnapshot();
        editor.post(() -> {
            int width=editor.getWidth(),height=editor.getHeight();
            for(int i=0;i<names.length;i++) {
                final int id=i;
                float x=Float.isNaN(values[i*2])?(0.10f+(i%5)*0.20f):values[i*2];
                float y=Float.isNaN(values[i*2+1])?(0.25f+(i/5)*0.18f):values[i*2+1];
                values[i*2]=x;values[i*2+1]=y;
                Button control=button(names[i],() -> prompt("control_size", "0.08,0.10,0.8", text -> {
                    try {
                        String[] parts=text.split(",");float w=Float.parseFloat(parts[0]),h=Float.parseFloat(parts[1]),alpha=Float.parseFloat(parts[2]);
                        if(w<=0||w>1||h<=0||h>1||alpha<0||alpha>1)throw new IllegalArgumentException();
                        values[40+id*2]=w;values[41+id*2]=h;values[80+id]=alpha;
                        controlSize(editor,id,values);
                    } catch(RuntimeException e){error(t("invalid_control_size"));}
                }));
                control.setTag(i);editor.addView(control,new FrameLayout.LayoutParams(dp(90),dp(44)));
                controlSize(editor,id,values);
                control.setX(x*width-control.getLayoutParams().width/2f);control.setY(y*height-control.getLayoutParams().height/2f);
                control.setOnTouchListener(new View.OnTouchListener(){float startX,startY,offsetX,offsetY;
                    public boolean onTouch(View v,android.view.MotionEvent event){
                        if(event.getAction()==android.view.MotionEvent.ACTION_DOWN){startX=event.getRawX();startY=event.getRawY();offsetX=v.getX();offsetY=v.getY();return true;}
                        if(event.getAction()==android.view.MotionEvent.ACTION_MOVE){
                            v.setX(Math.max(0,Math.min(width-v.getWidth(),offsetX+event.getRawX()-startX)));
                            v.setY(Math.max(0,Math.min(height-v.getHeight(),offsetY+event.getRawY()-startY)));return true;
                        }
                        if(event.getAction()==android.view.MotionEvent.ACTION_UP){
                            values[id*2]=(v.getX()+v.getWidth()/2f)/width;values[id*2+1]=(v.getY()+v.getHeight()/2f)/height;
                            if(Math.abs(event.getRawX()-startX)+Math.abs(event.getRawY()-startY)<dp(8))v.performClick();return true;
                        }return true;
                    }
                });
            }
            Button save=button("save_layout",() -> {
                android.content.SharedPreferences.Editor edit=getPreferences(0).edit();
                for(int i=0;i<values.length;i++)edit.putFloat("touch_"+i,values[i]);edit.apply();applyTouchPreferences();overlay.removeView(editor);
            });FrameLayout.LayoutParams saveLayout=new FrameLayout.LayoutParams(-2,dp(48));saveLayout.gravity=Gravity.TOP|Gravity.RIGHT;editor.addView(save,saveLayout);
            Button cancel=button("cancel",() -> overlay.removeView(editor));editor.addView(cancel,new FrameLayout.LayoutParams(-2,dp(48)));
        });
    }
    private void controlSize(FrameLayout editor,int id,float[] values) {
        View control=editor.findViewWithTag(id);if(control==null)return;
        ViewGroup.LayoutParams size=control.getLayoutParams();
        if(!Float.isNaN(values[40+id*2]))size.width=Math.max(dp(24),Math.round(values[40+id*2]*editor.getWidth()));
        if(!Float.isNaN(values[41+id*2]))size.height=Math.max(dp(24),Math.round(values[41+id*2]*editor.getHeight()));
        control.setLayoutParams(size);
        if(!Float.isNaN(values[80+id]))control.setAlpha(values[80+id]);
    }
    private void gameSettings() {
        page.removeAllViews(); heading("game_settings");
        String[] keys = {"renderDistance","simulationDistance","maxFps","fov","gamma","guiScale","particles","renderClouds","entityShadows","bobView","soundCategory_master","soundCategory_music","soundCategory_ambient","showSubtitles","mouseSensitivity","autoJump","invertYMouse"};
        for (String key : keys) ask("options-get " + key, result -> {
            JSONObject options = result.optJSONObject("options"); if (options == null) return;
            JSONObject spec = options.optJSONObject(key); if (spec == null) return;
            addButton(t(key) + ": " + spec.opt("value"), () -> prompt(key, String.valueOf(spec.opt("value")), value -> ask("options-set " + key + " " + value, v -> gameSettings())));
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
            thumbnail.setOnClickListener(v -> share(image,"image/png"));
            thumbnail.setContentDescription(image.getName()); page.addView(thumbnail,new LinearLayout.LayoutParams(-1,dp(180)));
        }
    }
    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        if(!visible && (event.getSource()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK) {
            Pad pad=controllers.get(event.getDeviceId());if(pad==null){pad=new Pad();controllers.put(event.getDeviceId(),pad);}
            int[] axes={android.view.MotionEvent.AXIS_X,android.view.MotionEvent.AXIS_Y,android.view.MotionEvent.AXIS_Z,android.view.MotionEvent.AXIS_RZ,android.view.MotionEvent.AXIS_LTRIGGER,android.view.MotionEvent.AXIS_RTRIGGER,android.view.MotionEvent.AXIS_HAT_X,android.view.MotionEvent.AXIS_HAT_Y};
            for(int i=0;i<axes.length;i++)pad.axes[i]=event.getAxisValue(axes[i]);
            InputDevice device=event.getDevice();controllerChanged(event.getDeviceId(),device==null?"Gamepad":device.getName(),pad.axes,pad.buttons);return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if(!visible && (event.getSource()&InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD) {
            int[] keys={android.view.KeyEvent.KEYCODE_BUTTON_A,android.view.KeyEvent.KEYCODE_BUTTON_B,android.view.KeyEvent.KEYCODE_BUTTON_X,android.view.KeyEvent.KEYCODE_BUTTON_Y,android.view.KeyEvent.KEYCODE_BUTTON_L1,android.view.KeyEvent.KEYCODE_BUTTON_R1,android.view.KeyEvent.KEYCODE_BUTTON_SELECT,android.view.KeyEvent.KEYCODE_BUTTON_START,android.view.KeyEvent.KEYCODE_BUTTON_THUMBL,android.view.KeyEvent.KEYCODE_BUTTON_THUMBR,android.view.KeyEvent.KEYCODE_DPAD_UP,android.view.KeyEvent.KEYCODE_DPAD_DOWN,android.view.KeyEvent.KEYCODE_DPAD_LEFT,android.view.KeyEvent.KEYCODE_DPAD_RIGHT};
            for(int i=0;i<keys.length;i++)if(event.getKeyCode()==keys[i]){
                Pad pad=controllers.get(event.getDeviceId());if(pad==null){pad=new Pad();controllers.put(event.getDeviceId(),pad);}
                if(event.getAction()==android.view.KeyEvent.ACTION_DOWN)pad.buttons|=1<<i;else if(event.getAction()==android.view.KeyEvent.ACTION_UP)pad.buttons&=~(1<<i);
                InputDevice device=event.getDevice();controllerChanged(event.getDeviceId(),device==null?"Gamepad":device.getName(),pad.axes,pad.buttons);return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    private void checkUpdates() {
        workers.execute(() -> {
            try {
                java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL("https://api.github.com/repos/OlahDavidXdd/horizon-ios/releases?per_page=20").openConnection();
                connection.setConnectTimeout(15000);connection.setReadTimeout(15000);connection.setRequestProperty("Accept","application/vnd.github+json");
                JSONArray releases;
                try(InputStream in=connection.getInputStream()){releases=new JSONArray(new String(readBytes(in),java.nio.charset.StandardCharsets.UTF_8));}
                finally{connection.disconnect();}
                int installed=getPackageManager().getPackageInfo(getPackageName(),0).versionCode;
                for(int i=0;i<releases.length();i++) {
                    JSONObject release=releases.getJSONObject(i);String tag=release.optString("tag_name");
                    if(!tag.matches("android-v[0-9.]+-build-[0-9]+"))continue;
                    int build=Integer.parseInt(tag.substring(tag.lastIndexOf('-')+1));
                    if(build>installed && !tag.equals(getPreferences(0).getString("offered_update",""))) {
                        getPreferences(0).edit().putString("offered_update",tag).apply();
                        runOnUiThread(() -> new AlertDialog.Builder(this).setTitle(t("update_available")).setMessage(tag)
                            .setPositiveButton(t("view_release"),(d,w) -> startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/OlahDavidXdd/horizon-ios/releases/tag/"+tag))))
                            .setNegativeButton(android.R.string.cancel,null).show());
                    }break;
                }
            } catch(Exception ignored) { /* Offline startup must remain usable. */ }
        });
    }
    private void share(File file,String type) {
        if(!file.isFile()){error(t("file_unavailable"));return;}
        Uri uri=androidx.core.content.FileProvider.getUriForFile(this,getPackageName()+".files",file);
        Intent intent=new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM,uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri(file.getName(),uri));
        startActivity(Intent.createChooser(intent,t("share")));
    }
    @Override public void onBackPressed() {
        if (launching && launchOwner != null) {
            String owner = launchOwner; launchOwner = null;
            ask("launcher-cancel-start " + owner, v -> { launching = false; render(); });
        } else if (!visible) ask("launcher-visible true", v -> setLauncherVisible(true));
        else { tab = "play"; render(); }
    }
    @Override protected void onPause() {
        ui.removeCallbacks(watch);keyboardVisible=false;
        for(int i=0;i<controllers.size();i++)controllerRemoved(controllers.keyAt(i));controllers.clear();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();ui.removeCallbacks(watch);if(overlay!=null)ui.post(watch);
    }
    @Override protected void onDestroy() { destroyed = true; if(launcherWindow!=null)launcherWindow.dismiss(); if(inputManager!=null)inputManager.unregisterInputDeviceListener(deviceListener); ui.removeCallbacksAndMessages(null); workers.shutdownNow(); super.onDestroy(); }
}
