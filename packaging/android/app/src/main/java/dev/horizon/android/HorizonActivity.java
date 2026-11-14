package dev.horizon.android;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.*;
import android.widget.*;
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
    private FrameLayout navigationDock;
    private android.webkit.WebView glassNavigation;
    private boolean glassNavigationReady;
    private TextView status, setupDescription;
    private ProgressBar setupProgress;
    private boolean needsResources = true, setupBusy, installingResources, automaticSetupAttempted;
    private String setupMessage;
    private long setupReceived, setupTotal;
    private volatile int setupAttempt;
    private volatile java.net.HttpURLConnection activeDownload;
    private volatile boolean destroyed;
    private boolean launching, visible = true;
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
                boolean missing = "needs_files".equals(phase) || !hasGameFiles();
                boolean changed = missing != needsResources;
                needsResources = missing;
                status.setText(setupBusy ? t(installingResources ? "installing_files" : "downloading") : needsResources ? t("needs_files") : message);
                if (changed && "play".equals(tab)) render();
                if (needsResources && !setupBusy && !automaticSetupAttempted
                        && !state.optBoolean("importing") && state.optJSONObject("import") == null
                        && "waiting".equals(phase)) {
                    automaticSetupAttempted = true;
                    setupMessage = t("private_download_help"); render();
                }
            });
            ui.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        current=new java.lang.ref.WeakReference<>(this);initPlatform();
        accounts = new Accounts(this);
        needsResources = !hasGameFiles();
        applyTouchPreferences();
        overlay = new FrameLayout(this);
        backdrop = new FrameLayout(this);
        GradientDrawable sky = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xff0b141c, 0xff14232d, 0xff0c151e});
        backdrop.setBackground(sky);
        addBackdropGlow(0x304d839a, Gravity.BOTTOM | Gravity.RIGHT);
        addBackdropGlow(0x1a58b29e, Gravity.TOP | Gravity.LEFT);
        overlay.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout shell = new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(20), dp(18), dp(20), dp(20));
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(overlay, (view, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            shell.setPadding(dp(20) + safe.left, dp(14) + safe.top, dp(20) + safe.right, dp(14) + safe.bottom);
            return insets;
        });
        LinearLayout header = row();
        TextView title = label(t("app_name"), 25); title.setTypeface(null, android.graphics.Typeface.BOLD);
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
            b.setTag(name); b.setTextSize(11); b.setGravity(Gravity.CENTER);
            b.setPadding(dp(8), dp(5), dp(8), dp(5));
            int icon = getResources().getIdentifier("ic_" + name, "drawable", getPackageName());
            if (icon != 0) b.setCompoundDrawablesRelativeWithIntrinsicBounds(null, getDrawable(icon), null, null);
            b.setCompoundDrawablePadding(dp(3));
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(0, dp(60), 1);
            item.setMargins(dp(3), 0, dp(3), 0); navigation.addView(b, item);
        }
        navigation.setPadding(dp(5), dp(5), dp(5), dp(5));
        navigationDock = new FrameLayout(this);
        FrameLayout.LayoutParams fallbackBounds = new FrameLayout.LayoutParams(-1, dp(70));
        fallbackBounds.gravity = Gravity.CENTER; navigationDock.addView(navigation, fallbackBounds);
        navigation.setBackground(rounded(0xff1b2d38, 0x267fa59f, 30));
        LinearLayout.LayoutParams dock = new LinearLayout.LayoutParams(-1, dp(104));
        dock.setMargins(0, dp(2), 0, 0); shell.addView(navigationDock, dock);
        installGlassNavigation();
        overlay.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        // NativeActivity owns its main window surface for Vulkan. A separate
        // window lets Android render the launcher without sharing that producer.
        launcherWindow=new Dialog(this,R.style.HorizonTheme);
        launcherWindow.setContentView(overlay);launcherWindow.setCancelable(false);
        launcherWindow.setOnKeyListener((dialog,key,event) -> {
            if(key==android.view.KeyEvent.KEYCODE_BACK&&event.getAction()==android.view.KeyEvent.ACTION_UP){onBackPressed();return true;}
            return false;
        });
        launcherWindow.show();
        launcherWindow.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
        launcherWindow.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        launcherWindow.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        androidx.core.view.ViewCompat.requestApplyInsets(overlay);
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
    /** The requested React component is bundled locally; its bridge only selects a tab. */
    private void installGlassNavigation() {
        try {
            android.webkit.WebView web = new android.webkit.WebView(this); glassNavigation = web;
            web.setBackgroundColor(Color.TRANSPARENT); web.setVisibility(View.INVISIBLE);
            web.setVerticalScrollBarEnabled(false); web.setHorizontalScrollBarEnabled(false);
            web.setOverScrollMode(View.OVER_SCROLL_NEVER); web.setContentDescription(t("navigation"));
            web.setOnLongClickListener(view -> true);
            web.setLongClickable(false);
            web.setOnTouchListener((view, event) -> {
                int action = event.getActionMasked();
                android.view.ViewParent parent = view.getParent();
                if(parent != null) parent.requestDisallowInterceptTouchEvent(action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL);
                return false; // Keep delivering the complete gesture to GlassNav.
            });
            android.webkit.WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true); // Trusted bundled code only; all network/file access is blocked.
            settings.setBlockNetworkLoads(true); settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
            settings.setDomStorageEnabled(false); settings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            web.addJavascriptInterface(new NavigationBridge(), "HorizonNav");
            web.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView view, android.webkit.WebResourceRequest request) { return true; }
                @Override public void onReceivedError(android.webkit.WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                    if(request.isForMainFrame()) nativeNavigationFallback(view);
                }
                @Override public boolean onRenderProcessGone(android.webkit.WebView view, android.webkit.RenderProcessGoneDetail detail) {
                    nativeNavigationFallback(view); return true;
                }
            });
            navigationDock.addView(web, new FrameLayout.LayoutParams(-1,-1));
            navigationDock.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> sampleNavigationBackdrop());
            String html;
            try(InputStream in = getAssets().open("glass-nav/index.html")) { html = new String(readBytes(in), java.nio.charset.StandardCharsets.UTF_8); }
            web.loadDataWithBaseURL("https://horizon.invalid/", html, "text/html", "UTF-8", null);
        } catch(RuntimeException | IOException failure) {
            if(glassNavigation != null) nativeNavigationFallback(glassNavigation);
        }
    }
    public final class NavigationBridge {
        @android.webkit.JavascriptInterface public void ready() {
            ui.post(() -> {
                if(destroyed || glassNavigation == null) return;
                glassNavigationReady = true; glassNavigation.setVisibility(View.VISIBLE); navigation.setVisibility(View.INVISIBLE);
                refreshNavigation(); sampleNavigationBackdrop();
            });
        }
        @android.webkit.JavascriptInterface public void select(int index) {
            if(index < 0 || index > 2) return;
            ui.post(() -> { if(!destroyed && glassNavigationReady) { tab = new String[]{"play","packs","settings"}[index]; render(); } });
        }
    }
    private void nativeNavigationFallback(android.webkit.WebView view) {
        glassNavigationReady = false; navigation.setVisibility(View.VISIBLE);
        navigationDock.removeView(view); view.removeJavascriptInterface("HorizonNav"); view.destroy();
        if(glassNavigation == view) glassNavigation = null;
    }
    private void sampleNavigationBackdrop() {
        if(!glassNavigationReady || glassNavigation == null || navigationDock.getWidth() == 0 || navigationDock.getHeight() == 0) return;
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(navigationDock.getWidth(), navigationDock.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        int[] dock = new int[2], source = new int[2]; navigationDock.getLocationInWindow(dock); backdrop.getLocationInWindow(source);
        canvas.translate(source[0]-dock[0], source[1]-dock[1]); backdrop.draw(canvas);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); bitmap.recycle();
        String image = "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
        glassNavigation.evaluateJavascript("window.horizonNav && window.horizonNav.backdrop(" + JSONObject.quote(image) + ")",null);
    }
    private void addBackdropGlow(int color, int gravity) {
        View glow = new View(this);
        GradientDrawable light = new GradientDrawable(); light.setShape(GradientDrawable.OVAL);
        light.setGradientType(GradientDrawable.RADIAL_GRADIENT); light.setGradientRadius(dp(230));
        light.setColors(new int[]{color, color & 0x00ffffff}); glow.setBackground(light);
        FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(dp(460), dp(460));
        bounds.gravity = gravity; backdrop.addView(glow, bounds);
    }
    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    String t(String key) {
        int id = getResources().getIdentifier(key, "string", getPackageName());
        return id == 0 ? key : getString(id);
    }
    private GradientDrawable rounded(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(dp(1), stroke); return shape;
    }
    TextView label(String value, int size) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(0xffeaf2f4);
        v.setLineSpacing(dp(2), 1f); v.setPadding(dp(2), dp(5), dp(2), dp(5)); return v;
    }
    private TextView detail(String value) { TextView v = label(value, 14); v.setTextColor(0xffa0b3bf); return v; }
    LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); return r; }
    Button button(String key, Runnable action) {
        Button b = new Button(this); b.setText(t(key)); b.setTextSize(14); b.setAllCaps(false);
        b.setTextColor(0xffc7ede5); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48));
        b.setPadding(dp(14), dp(10), dp(14), dp(10)); b.setBackgroundTintList(null);
        android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x2675dbc6), rounded(0x152d716c, 0x205a9c94, 15), rounded(Color.WHITE, 0, 15));
        b.setBackground(ripple); b.setOnClickListener(v -> action.run()); return b;
    }
    private void primary(Button button) {
        button.setTextColor(0xff092c29); button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x3380ffff), rounded(0xff7ce0c8, 0, 17), rounded(Color.WHITE, 0, 17)));
    }
    void addButton(String key, Runnable action) {
        Button b = button(key, action); LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, -2);
        bounds.setMargins(0, dp(4), 0, dp(4)); page.addView(b, bounds);
    }
    void heading(String key) {
        TextView heading = label(t(key), key.equals(tab) ? 28 : 20);
        heading.setTypeface(null, android.graphics.Typeface.BOLD); heading.setPadding(dp(2), dp(19), dp(2), dp(10)); page.addView(heading);
    }
    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(14), dp(18), dp(14)); box.setBackground(rounded(0xc21a2a35, 0x225d7e8b, 21));
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, -2); bounds.setMargins(0, dp(8), 0, dp(12));
        page.addView(box, bounds); return box;
    }
    private void refreshNavigation() {
        if(glassNavigationReady && glassNavigation != null) {
            JSONObject state = new JSONObject();
            try {
                state.put("index", tab.equals("play") ? 0 : tab.equals("packs") ? 1 : 2);
                state.put("labels", new JSONArray(new String[]{t("play"),t("packs"),t("settings")}));
                state.put("reduced",getPreferences(0).getBoolean("reduce_transparency",false));
                glassNavigation.evaluateJavascript("window.horizonNav && window.horizonNav.configure(" + state + ")",null);
            } catch(JSONException e) { throw new IllegalStateException(e); }
        }
        for(int i=0;i<navigation.getChildCount();i++) {
            Button item = (Button)navigation.getChildAt(i); boolean selected = tab.equals(item.getTag());
            item.setBackground(rounded(selected ? 0x294b9b8c : Color.TRANSPARENT, selected ? 0x205fb7a6 : 0, 24));
            item.setTextColor(selected ? 0xffb3ffea : 0xffa1b6bf);
            for(android.graphics.drawable.Drawable icon : item.getCompoundDrawablesRelative()) if(icon != null) icon.setTint(selected ? 0xffb3ffea : 0xffa1b6bf);
        }
    }
    interface Reply { void done(JSONObject value); }
    void ask(String line, Reply reply) { request(line, reply, this::error); }
    private void request(String line, Reply reply, java.util.function.Consumer<String> failed) {
        long id = submit(line);
        final long deadline = SystemClock.uptimeMillis() + (line.startsWith("import-game-files ") ? 20 * 60000L : 120000L);
        ui.post(new Runnable() { public void run() {
            if (destroyed) { abandon(id); return; }
            String result = poll(id);
            if (result == null && SystemClock.uptimeMillis() < deadline) { ui.postDelayed(this, 30); return; }
            if (result == null) { abandon(id); launching = false; failed.accept(t("timeout")); return; }
            try {
                JSONObject answer = new JSONObject(result);
                if (answer.has("error")) { failed.accept(answer.optString("error")); return; }
                reply.done(answer);
            } catch (JSONException e) { failed.accept(t("bad_reply")); }
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
        setupDescription = null; setupProgress = null;
        page.removeAllViews(); refreshNavigation(); heading(tab);
        switch (tab) {
            case "play": home(); break;
            case "packs": packs(); break;
            default: settings();
        }
    }
    private void home() {
        TextView edition = detail(t("java_edition")); edition.setTextSize(11); edition.setLetterSpacing(0.15f); page.addView(edition);
        if (needsResources || !hasGameFiles() || setupBusy || setupMessage != null) resourceSetupCard();
        Button play = button(needsResources || !hasGameFiles() ? "setup_game" : "play", () -> launch(null)); primary(play);
        play.setEnabled(!setupBusy); page.addView(play, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout actions = row();
        String[] names = {"new_world", "import_world", "add_server"};
        Runnable[] callbacks = {this::newWorld, () -> pick("world"), this::addServer};
        for(int i=0;i<names.length;i++) {
            Button action = button(names[i], callbacks[i]); action.setTextSize(12); action.setPadding(dp(7), dp(8), dp(7), dp(8));
            LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(0, -2, 1); bounds.setMargins(i==0 ? 0 : dp(5), dp(10), 0, 0);
            actions.addView(action, bounds);
        } page.addView(actions);
        heading("worlds");
        LinearLayout worlds = new LinearLayout(this); worlds.setOrientation(LinearLayout.VERTICAL); page.addView(worlds);
        ask("worlds", result -> {
            JSONArray list = result.optJSONArray("worlds");
            if (list == null || list.length() == 0) worlds.addView(detail(t("empty_worlds")));
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
            if (list == null || list.length() == 0) servers.addView(detail(t("empty_servers")));
            else for (int i = 0; i < list.length(); i++) {
                JSONObject s = list.optJSONObject(i); if (s == null) continue;
                int index = s.optInt("index", i); String address = s.optString("address");
                LinearLayout r = row();
                r.addView(button(s.optString("name", address), () -> launch("connect " + address)), new LinearLayout.LayoutParams(0, dp(56), 1));
                r.addView(button("remove", () -> confirm("remove_server", () -> ask("server-remove " + index, v -> render()))));
                servers.addView(r);
                TextView ping = label(address, 12); servers.addView(ping);
                request("server-ping " + address, p -> ping.setText(address + " · " + p.optInt("online") + "/" + p.optInt("max") + " · " + p.optInt("ms") + " ms"), failure -> ping.setText(address + " · " + t("server_unreachable")));
            }
        });
    }
    private void launch(String command) {
        if (setupBusy || launching) return;
        if (needsResources || !hasGameFiles()) { showResourceSetup(); return; }
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
                        launching = false; launchOwner = null; needsResources = true;
                        request("launcher-cancel-start " + owner, v -> showResourceSetup(), failure -> showResourceSetup());
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
        if (setupBusy) return;
        importKind = kind;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, 10);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 10 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData(); String kind = importKind;
        if (setupBusy) return;
        boolean resources = kind.equals("resources");
        int attempt = resources ? beginSetup(true) : -1;
        workers.execute(() -> {
            File destination = null;
            try {
                String name = "import-" + UUID.randomUUID() + (kind.equals("mod") ? ".hmod" : ".zip");
                File dir = new File(homeDir(), kind.equals("shader") ? "shaderpacks" : ".horizon-import");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException(t("cannot_import"));
                destination = new File(dir, name);
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(destination)) {
                    if (in == null) throw new IOException(t("cannot_import"));
                    byte[] buffer = new byte[65536]; int n; long copied = 0;
                    while ((n = in.read(buffer)) != -1) {
                        if(destroyed) throw new java.io.InterruptedIOException();
                        copied += n; if(copied > (2L << 30)) throw new IOException("Archive too large");
                        out.write(buffer, 0, n);
                    }
                }
                File staged = destination;
                ui.post(() -> {
                    if(destroyed) { staged.delete(); return; }
                    if (kind.equals("shader")) { render(); return; }
                    String line = kind.equals("mod") ? "mods-install " + object("path", staged.getAbsolutePath()) : (resources ? "import-game-files " : "import ") + staged.getAbsolutePath();
                    request(line, v -> {
                        staged.delete();
                        if (resources) finishSetup(attempt, null); else render();
                    }, failure -> {
                        staged.delete();
                        if (resources) finishSetup(attempt, failure); else error(failure);
                    });
                });
            } catch (Exception e) {
                if(destination != null) destination.delete();
                ui.post(() -> { if(resources) finishSetup(attempt, e.toString()); else error(t("cannot_import")); });
            }
        });
    }
    private void settings() {
        addButton("import_resources", () -> pick("resources"));
        addButton("download_files", this::requestDownloadSource);
        addButton("use_download_link", this::customResourceLink);
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
            try (InputStream in = getAssets().open("glass-nav/Liquid-Glass-LICENSE.txt")) {
                new AlertDialog.Builder(this).setTitle("Liquid-Glass · MIT").setMessage(new String(readBytes(in), java.nio.charset.StandardCharsets.UTF_8)).setPositiveButton(android.R.string.ok,null).show();
            } catch (IOException e) { error(e.getMessage()); }
        });
        CheckBox transparency = new CheckBox(this); transparency.setText(t("reduce_transparency")); transparency.setTextColor(Color.WHITE);
        transparency.setChecked(getPreferences(0).getBoolean("reduce_transparency",false));
        transparency.setOnCheckedChangeListener((b,on) -> { getPreferences(0).edit().putBoolean("reduce_transparency",on).apply(); refreshNavigation(); });
        page.addView(transparency);
        page.addView(label("Horizon · 0.2.95-android · ARM64",14));
    }
    static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }
    private boolean hasGameFiles() { return new File(homeDir(), "resources/horizon-resources.json").isFile(); }
    private void showResourceSetup() {
        needsResources = true; tab = "play"; render();
        if (!automaticSetupAttempted && !setupBusy) { automaticSetupAttempted = true; setupMessage = t("private_download_help"); render(); }
    }
    private void resourceSetupCard() {
        LinearLayout box = card();
        TextView title = label(t("setup_title"), 19); title.setTypeface(null, android.graphics.Typeface.BOLD); box.addView(title);
        setupDescription = detail(""); box.addView(setupDescription);
        setupProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        setupProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff7ce0c8));
        LinearLayout.LayoutParams progressBounds = new LinearLayout.LayoutParams(-1, dp(6)); progressBounds.setMargins(0, dp(10), 0, dp(14));
        box.addView(setupProgress, progressBounds);
        if (setupBusy) {
            if(!installingResources) box.addView(button("cancel", this::cancelResourceDownload));
        } else {
            Button download = button(setupMessage == null ? "download_files" : "retry_download", this::requestDownloadSource);
            Button privateDownload = button("private_download", this::openPrivateDownload); primary(privateDownload);
            box.addView(privateDownload, new LinearLayout.LayoutParams(-1, dp(49)));
            if(!getPreferences(0).getString("resource_link", "").isEmpty()) box.addView(download, new LinearLayout.LayoutParams(-1, dp(49)));
            Button choose = button("import_resources", () -> pick("resources"));
            LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, -2); bounds.setMargins(0, dp(9), 0, 0); box.addView(choose, bounds);
            box.addView(button("use_download_link", this::customResourceLink));
        }
        updateSetupProgress();
    }
    private void updateSetupProgress() {
        if(setupDescription != null) {
            String text = setupBusy ? t(installingResources ? "installing_files" : "downloading") : setupMessage == null ? t("setup_explanation") : setupMessage;
            if(setupBusy && !installingResources && setupReceived > 0) {
                text += "\n" + (setupReceived / 1048576) + " MB";
                if(setupTotal > 0) text += " / " + (setupTotal / 1048576) + " MB";
            }
            setupDescription.setText(text);
        }
        if(setupProgress != null) {
            setupProgress.setVisibility(setupBusy ? View.VISIBLE : View.GONE);
            setupProgress.setIndeterminate(installingResources || setupTotal <= 0);
            if(setupTotal > 0) { setupProgress.setMax(1000); setupProgress.setProgress((int)Math.min(1000, setupReceived * 1000 / setupTotal)); }
        }
        if(setupBusy) status.setText(t(installingResources ? "installing_files" : "downloading"));
    }
    private int beginSetup(boolean installing) {
        automaticSetupAttempted = true;
        launcherWindow.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setupBusy = true; installingResources = installing; setupMessage = null; setupReceived = 0; setupTotal = 0;
        int attempt = ++setupAttempt; render(); return attempt;
    }
    private void finishSetup(int attempt, String failure) {
        if(attempt != setupAttempt || destroyed) return;
        launcherWindow.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setupBusy = false; installingResources = false;
        if(failure != null || !hasGameFiles()) {
            android.util.Log.w("HorizonSetup", "Game-files setup failed: " + failure);
            needsResources = true;
            String reason = failure == null ? "" : failure.toLowerCase(java.util.Locale.ROOT);
            String key = reason.contains("http ") ? "download_server_error"
                : reason.contains("html") ? "download_sign_in"
                : reason.contains("space") || reason.contains("enospc") ? "download_no_space"
                : reason.contains("timeout") || reason.contains("unknownhost") || reason.contains("connect") ? "download_connection_error"
                : "setup_failed";
            setupMessage = t(key);
        } else { needsResources = false; setupMessage = null; status.setText(t("waiting")); }
        render();
    }
    private void cancelResourceDownload() {
        if(installingResources) return;
        launcherWindow.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ++setupAttempt; setupBusy = false; setupMessage = null;
        java.net.HttpURLConnection connection = activeDownload;
        activeDownload = null; if(connection != null) workers.execute(connection::disconnect);
        render();
    }
    private void customResourceLink() {
        if(setupBusy) return;
        automaticSetupAttempted = true;
        prompt("use_download_link", getPreferences(0).getString("resource_link", ""), value -> {
            try {
                java.net.URL url = new java.net.URL(value);
                if(!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()) throw new IOException();
                getPreferences(0).edit().putString("resource_link", value).apply(); download(value);
            } catch(Exception e) { setupMessage = t("https_required"); tab = "play"; render(); }
        });
    }
    private void requestDownloadSource() {
        if(setupBusy) return;
        automaticSetupAttempted = true;
        String saved = getPreferences(0).getString("resource_link", "");
        if(!saved.isEmpty()) { download(saved); return; }
        openPrivateDownload();
    }

    private void download(String address) {
        if(setupBusy) return;
        int attempt = beginSetup(false);
        workers.execute(() -> {
            File zip = new File(homeDir(), ".horizon-import/download-" + UUID.randomUUID() + ".zip");
            java.net.HttpURLConnection connection = null;
            try {
                if(attempt != setupAttempt || destroyed) return;
                java.net.URL url = new java.net.URL(address);
                if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("HTTPS required");
                if(!zip.getParentFile().isDirectory() && !zip.getParentFile().mkdirs()) throw new IOException("Cannot create import folder");
                connection = (java.net.HttpURLConnection)url.openConnection(); activeDownload = connection;
                connection.setConnectTimeout(30000); connection.setReadTimeout(30000);
                if(connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
                if(!"https".equalsIgnoreCase(connection.getURL().getProtocol())) throw new IOException("HTTPS required");
                String contentType = connection.getContentType();
                if(contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("text/html")) throw new IOException("HTML sign-in page instead of ZIP");
                long expected = connection.getContentLengthLong();
                final long limit = 2L << 30;
                if(expected > limit || (expected > 0 && expected > homeDir().getUsableSpace())) throw new IOException("Archive exceeds available space or size limit");
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(zip)) {
                    byte[] buffer = new byte[65536]; int n; long count = 0, lastUpdate = 0;
                    while ((n = in.read(buffer)) != -1) {
                        if(attempt != setupAttempt || destroyed) throw new java.io.InterruptedIOException();
                        count += n; if(count > limit) throw new IOException("Archive too large"); out.write(buffer, 0, n);
                        long now = SystemClock.uptimeMillis();
                        if(now - lastUpdate >= 200) {
                            lastUpdate = now; long received = count;
                            ui.post(() -> { if(attempt == setupAttempt) { setupReceived = received; setupTotal = expected; updateSetupProgress(); } });
                        }
                    }
                    if(expected >= 0 && count != expected) throw new IOException("Incomplete download");
                }
                ui.post(() -> {
                    if(attempt != setupAttempt || destroyed) { zip.delete(); return; }
                    installingResources = true; render();
                    request("import-game-files " + zip.getAbsolutePath(), v -> { zip.delete(); finishSetup(attempt, null); }, failure -> { zip.delete(); finishSetup(attempt, failure); });
                });
            } catch(Exception e) { zip.delete(); ui.post(() -> finishSetup(attempt, e.toString())); }
            finally { if(connection != null) { connection.disconnect(); if(activeDownload == connection) activeDownload = null; } }
        });
    }
    private void openPrivateDownload() {
        automaticSetupAttempted = true;
        setupMessage = t("private_download_help"); render();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://horizon-phone-files-olah-26-3.olahdavid066.chatgpt.site")));
        } catch(ActivityNotFoundException e) { error(t("browser_unavailable")); }
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
        if(glassNavigation != null) glassNavigation.onPause();
        for(int i=0;i<controllers.size();i++)controllerRemoved(controllers.keyAt(i));controllers.clear();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();if(glassNavigation != null) glassNavigation.onResume();ui.removeCallbacks(watch);if(overlay!=null)ui.post(watch);
    }
    @Override protected void onDestroy() { destroyed = true; if(glassNavigation != null) { glassNavigation.removeJavascriptInterface("HorizonNav"); navigationDock.removeView(glassNavigation); glassNavigation.destroy(); glassNavigation=null; } if(launcherWindow!=null)launcherWindow.dismiss(); if(inputManager!=null)inputManager.unregisterInputDeviceListener(deviceListener); ui.removeCallbacksAndMessages(null); workers.shutdownNow(); super.onDestroy(); }
}
