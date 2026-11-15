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
    private GlassNavigationDock navigationDock;
    private android.webkit.WebView glassNavigation;
    private boolean glassNavigationReady;
    private String lastJournalPhase="";
    private int pageGeneration;
    private TextView status, setupDescription;
    private ProgressBar setupProgress;
    private boolean needsResources = true, setupBusy, installingResources, automaticSetupAttempted;
    private String setupMessage;
    private String setupStage;
    private long setupReceived, setupTotal;
    private volatile int setupAttempt;
    private volatile java.net.HttpURLConnection activeDownload;
    private volatile android.os.CancellationSignal activePartsRead;
    private volatile boolean destroyed;
    private boolean launching, visible = true, watchPending;
    private final LaunchSurfaceState launchSurface=new LaunchSurfaceState();
    private SurfaceHolder gameSurface;
    private float requestedHz;
    private boolean previousLaunchFailed;
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
            if (visible && !watchPending) {
                watchPending=true;
                request("launcher-state", state -> {
                watchPending=false;
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
                    if(!new File(homeDir(),"resources/horizon-resources.json").isFile()) requestDownloadSource();
                    else { setupMessage = resourceSetupExplanation(); render(); }
                }
            }, failure -> { watchPending=false; if(!destroyed && visible) status.setText(t("waiting_for_engine")); });
            }
            ui.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        current=new java.lang.ref.WeakReference<>(this);initPlatform();
        previousLaunchFailed=getPreferences(0).getBoolean("launch_in_progress",false);
        journal("activity created; previous startup incomplete="+previousLaunchFailed);
        applyDisplayRate();
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
        navigationDock = new GlassNavigationDock(this, this::selectNavigationIndex);
        FrameLayout.LayoutParams fallbackBounds = new FrameLayout.LayoutParams(-1, dp(70));
        fallbackBounds.gravity = Gravity.CENTER; navigationDock.addView(navigation, fallbackBounds);
        navigation.setBackground(rounded(0xff1b2d38,0x267fa59f,30));
        LinearLayout.LayoutParams dock = new LinearLayout.LayoutParams(-1, dp(104));
        dock.setMargins(0, dp(2), 0, 0); shell.addView(navigationDock, dock);
        installGlassNavigation();
        overlay.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        // NativeActivity owns its main window surface for Vulkan. A separate
        // window lets Android render the launcher without sharing that producer.
        launcherWindow=new Dialog(this,R.style.HorizonTheme);
        launcherWindow.setContentView(overlay);launcherWindow.setCancelable(false);applyDisplayRate();
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
            ui.post(() -> { if(!destroyed && glassNavigationReady) selectNavigationIndex(index); });
        }
    }
    private void selectNavigationIndex(int index) {
        if(index < 0 || index > 2) return;
        String next = new String[]{"play","packs","settings"}[index];
        if(!tab.equals(next)) { tab=next; render(); }
    }
    private void nativeNavigationFallback(android.webkit.WebView view) {
        if(view!=glassNavigation) return;
        glassNavigation=null;glassNavigationReady=false;navigation.setVisibility(View.VISIBLE);
        navigationDock.removeView(view);
        try { view.removeJavascriptInterface("HorizonNav");view.destroy(); }
        catch(RuntimeException unavailable) { journal("glass renderer teardown unavailable"); }
        journal("glass renderer fell back to native");
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
        navigationDock.select(tab.equals("play") ? 0 : tab.equals("packs") ? 1 : 2);
        if(glassNavigationReady && glassNavigation != null) {
            JSONObject state = new JSONObject();
            try {
                state.put("nativePill",true);
                state.put("index", tab.equals("play") ? 0 : tab.equals("packs") ? 1 : 2);
                state.put("labels", new JSONArray(new String[]{t("play"),t("packs"),t("settings")}));
                state.put("reduced",getPreferences(0).getBoolean("reduce_transparency",false));
                glassNavigation.evaluateJavascript("window.horizonNav && window.horizonNav.configure(" + state + ")",null);
            } catch(JSONException e) { throw new IllegalStateException(e); }
        }
        for(int i=0;i<navigation.getChildCount();i++) {
            Button item = (Button)navigation.getChildAt(i); boolean selected = tab.equals(item.getTag());
            item.setBackgroundColor(Color.TRANSPARENT);
            item.setTextColor(selected ? 0xffb3ffea : 0xffa1b6bf);
            for(android.graphics.drawable.Drawable icon : item.getCompoundDrawablesRelative()) if(icon != null) icon.setTint(selected ? 0xffb3ffea : 0xffa1b6bf);
        }
    }
    interface Reply { void done(JSONObject value); }
    void ask(String line, Reply reply) { request(line, reply, this::error); }
    void request(String line, Reply reply, java.util.function.Consumer<String> failed) {
        long id = submit(line);
        final long deadline = SystemClock.uptimeMillis() + (line.startsWith("import-game-files ") ? 20 * 60000L : 120000L);
        ui.post(new Runnable() { public void run() {
            if (destroyed) { abandon(id); return; }
            String result = poll(id);
            if (result == null && SystemClock.uptimeMillis() < deadline) { ui.postDelayed(this, 30); return; }
            if (result == null) { abandon(id); failed.accept(t("timeout")); return; }
            try {
                JSONObject answer = new JSONObject(result);
                if (answer.has("error")) { failed.accept(answer.optString("error")); return; }
                reply.done(answer);
            } catch (JSONException e) { failed.accept(t("bad_reply")); }
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
    void setLauncherVisible(boolean value) {
        cancelGlassGesture();
        visible = value;
        setRequestedOrientation(value ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        if(value) {
            overlay.setVisibility(View.VISIBLE);launcherWindow.show();
            launcherWindow.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
            render();
        } else launcherWindow.hide();
    }
    void render() {
        pageGeneration++;
        setupDescription = null; setupProgress = null;
        page.removeAllViews(); refreshNavigation(); heading(tab);
        switch (tab) {
            case "play": home(); break;
            case "packs": packs(); break;
            default: settings();
        }
    }
    private void home() {
        if(previousLaunchFailed) page.addView(detail(t("previous_launch_failed")));
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
    private boolean ownsLaunch(String owner) { return !destroyed && launching && owner.equals(launchOwner); }
    private void failLaunch(String owner,String message) {
        if(!ownsLaunch(owner)) return;
        journal("launch failed before handoff");
        launching=false; launchOwner=null;
        getPreferences(0).edit().putBoolean("launch_in_progress",false).apply();
        request("launcher-cancel-start "+owner,v -> {},ignored -> {});
        setLauncherVisible(true); error(message);
    }
    private void launch(String command) {
        if(setupBusy || launching) return;
        if(needsResources || !hasGameFiles()) { showResourceSetup(); return; }
        launching=true; launchOwner=UUID.randomUUID().toString(); String owner=launchOwner;
        journal("Play requested");
        getPreferences(0).edit().putBoolean("launch_in_progress",true).apply();
        // Rotate before requesting GPU startup, rather than resizing the newly loaded game.
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        final long deadline=SystemClock.uptimeMillis()+8000;
        ui.post(new Runnable() { public void run() {
            if(!ownsLaunch(owner)) return;
            boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            if(!launchSurface.ready(landscape,SystemClock.uptimeMillis())) {
                if(SystemClock.uptimeMillis()>=deadline) failLaunch(owner,t("landscape_not_ready"));
                else ui.postDelayed(this,50);
                return;
            }
            journal("landscape surface settled "+launchSurface.width+"x"+launchSurface.height);
            accounts.handOver(() -> {
                if(!ownsLaunch(owner)) return;
                request("launcher-start "+owner,answer -> {
                    if(!ownsLaunch(owner)) return;
                    if(!answer.optBoolean("ok")) { failLaunch(owner,t("waiting_for_engine")); return; }
                    journal("native startup accepted");
                    awaitGame(owner,command);
                },message -> failLaunch(owner,message));
            },message -> failLaunch(owner,message));
        }});
    }
    private void awaitGame(String owner,String command) {
        if(!ownsLaunch(owner)) return;
        request("launcher-state",state -> {
            if(!ownsLaunch(owner)) return;
            String p=state.optString("phase");
            if(!p.equals(lastJournalPhase)) {lastJournalPhase=p;journal("native phase="+p);}
            if(p.equals("title") || p.equals("world")) {
                Runnable finish=() -> {
                    if(!ownsLaunch(owner)) return;
                    request("launcher-commit-start "+owner,commit -> {
                        if(!ownsLaunch(owner)) return;
                        if(!commit.optBoolean("ok")) { failLaunch(owner,t("waiting_for_engine")); return; }
                        request("launcher-visible false",v -> {
                            if(!ownsLaunch(owner)) return;
                            journal("startup committed; revealing game");
                            launching=false; setLauncherVisible(false);
                            ui.postDelayed(() -> {
                                if(!destroyed && owner.equals(launchOwner)) {
                                    getPreferences(0).edit().putBoolean("launch_in_progress",false).apply(); previousLaunchFailed=false;
                                    journal("startup stable for 15 seconds");
                                }
                            },15000);
                        },message -> failLaunch(owner,message));
                    },message -> failLaunch(owner,message));
                };
                if(command==null) finish.run(); else request(command,v -> finish.run(),message -> failLaunch(owner,message));
            } else if(p.equals("needs_files")) {
                failLaunch(owner,t("needs_files")); needsResources=true; showResourceSetup();
            } else { status.setText(t("loading")); ui.postDelayed(() -> awaitGame(owner,command),300); }
        },message -> failLaunch(owner,message));
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
    private boolean currentPacks(int generation) { return !destroyed && pageGeneration==generation && tab.equals("packs"); }
    private LinearLayout packSection(String title) {
        heading(title);
        LinearLayout section=new LinearLayout(this); section.setOrientation(LinearLayout.VERTICAL);
        page.addView(section); return section;
    }
    private void packs() {
        final int generation=pageGeneration;
        addButton("import_resources", () -> pick("resources"));
        addButton("import_pack", () -> pick("pack"));
        addButton("import_shader", () -> pick("shader"));
        addButton("import_mod", () -> pick("mod"));
        LinearLayout resources=packSection("resource_packs");
        LinearLayout shaders=packSection("shader_packs");
        LinearLayout modsSection=packSection("mods");
        File[] files = new File(homeDir(), "resourcepacks").listFiles(file -> file.isDirectory() || file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip"));
        if(files==null || files.length==0) resources.addView(detail(t("no_resource_packs")));
        else request("options-get resourcePacks", result -> {
            if(!currentPacks(generation)) return;
            JSONObject option=result.optJSONObject("options");
            JSONObject description=option==null ? null : option.optJSONObject("resourcePacks");
            JSONArray selected=description==null ? null : description.optJSONArray("value");
            Set<String> active=new LinkedHashSet<>();
            if(selected!=null) for(int i=0;i<selected.length();i++) active.add(selected.optString(i));
            for(File file:files) {
                String id="file/"+file.getName();
                CheckBox check=new CheckBox(this); check.setText(file.getName()); check.setTextColor(Color.WHITE); check.setChecked(active.contains(id));
                check.setOnCheckedChangeListener((b,on) -> {
                    if(on) active.add(id); else active.remove(id);
                    ask("options-set resourcePacks "+new JSONArray(active),v -> {});
                });
                LinearLayout packRow=row(); packRow.addView(check,new LinearLayout.LayoutParams(0,-2,1));
                for(int direction:new int[]{1,-1}) packRow.addView(button(direction==1 ? "move_up" : "move_down", () -> {
                    List<String> order=new ArrayList<>(active); int index=order.indexOf(id), target=index+direction;
                    if(index>=0 && target>=0 && target<order.size()) {
                        Collections.swap(order,index,target); active.clear(); active.addAll(order);
                        ask("options-set resourcePacks "+new JSONArray(order),v -> { if(currentPacks(generation)) render(); });
                    }
                }));
                resources.addView(packRow);
            }
        }, message -> { if(currentPacks(generation)) resources.addView(detail(message)); });
        request("shader-pack list", result -> {
            if(!currentPacks(generation)) return;
            shaders.addView(button("disable_shader", () -> ask("shader-pack off",v -> { if(currentPacks(generation)) render(); })));
            File[] installed=new File(homeDir(),"shaderpacks").listFiles(file -> file.isDirectory() || file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip"));
            if(installed==null || installed.length==0) shaders.addView(detail(t("no_shader_packs")));
            else for(File shader:installed) shaders.addView(button(shader.getName(), () -> ask("shader-pack "+shader.getName(),v -> { if(currentPacks(generation)) render(); })));
        }, message -> { if(currentPacks(generation)) shaders.addView(detail(message)); });
        request("mods-list", result -> {
            if(!currentPacks(generation)) return;
            JSONArray mods=result.optJSONArray("mods");
            if(mods==null || mods.length()==0) modsSection.addView(detail(t("no_mods")));
            else modsSection.addView(detail(t("mods_next_launch")));
            if(mods!=null) for(int i=0;i<mods.length();i++) {
                JSONObject mod=mods.optJSONObject(i); if(mod==null) continue;
                CheckBox check=new CheckBox(this); check.setText(mod.optString("name",mod.optString("id"))); check.setTextColor(Color.WHITE);
                check.setChecked(mod.optBoolean("enabled"));
                String problem=mod.optString("problem","");
                boolean available=problem.isEmpty() && !mod.optBoolean("code"); check.setEnabled(available);
                boolean[] reverting={false};
                check.setOnCheckedChangeListener((b,on) -> {
                    if(reverting[0]) return;
                    check.setEnabled(false);
                    JSONObject args=object("id",mod.optString("id"));
                    try { args.put("on",on); } catch(JSONException e) { throw new IllegalStateException(e); }
                    request("mods-set "+args,v -> { if(currentPacks(generation)) render(); },message -> {
                        if(!currentPacks(generation)) return;
                        reverting[0]=true; check.setChecked(!on); reverting[0]=false; check.setEnabled(available); error(message);
                    });
                });
                modsSection.addView(check);
                if(!problem.isEmpty()) modsSection.addView(detail(problem));
            }
        }, message -> { if(currentPacks(generation)) modsSection.addView(detail(message)); });
    }
    File homeDir() { File f = getExternalFilesDir(null); return f == null ? getFilesDir() : f; }
    private void pick(String kind) {
        if (setupBusy) return;
        importKind = kind;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, 10);
    }
    private void pickDriveParts() {
        if(setupBusy) return;
        Toast.makeText(this,t("select_drive_parts"),Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        startActivityForResult(intent,11);
    }
    private static final class UriPart {
        final Uri uri; final long size;
        UriPart(Uri uri,long size) { this.uri=uri; this.size=size; }
    }
    private void installDriveParts(Intent selection) {
        if(setupBusy) return;
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = selection.getClipData();
        if(clip != null) for(int i=0;i<clip.getItemCount();i++) uris.add(clip.getItemAt(i).getUri());
        else if(selection.getData() != null) uris.add(selection.getData());
        if(uris.size() != ResourceVolumes.PARTS) { error(t("select_drive_parts")); return; }
        int attempt = beginSetup(false); setupStage="downloading_parts"; updateSetupProgress();
        workers.execute(() -> {
            File directory = null, zip = null;
            try {
                if(homeDir().getUsableSpace() < 3L*1024*1024*1024) throw new IOException("Archive parts need space");
                UriPart[] selected = new UriPart[ResourceVolumes.PARTS]; String base = null;
                long total = 0; boolean known = true;
                for(Uri uri : uris) {
                    String name = null; long size = -1;
                    if(uri == null || !"content".equals(uri.getScheme())) throw new IOException("Invalid archive parts URI");
                    try(android.database.Cursor cursor = getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE},null,null,null)) {
                        if(cursor != null && cursor.moveToFirst()) {
                            int names = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME), sizes = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                            if(names >= 0) name = cursor.getString(names);
                            if(sizes >= 0 && !cursor.isNull(sizes)) size = cursor.getLong(sizes);
                        }
                    }
                    if(name == null) throw new IOException("Archive parts have no file name");
                    int index = ResourceVolumes.partNumber(name);
                    String prefix = name.substring(0,name.length()-3);
                    if(selected[index] != null || (base != null && !base.equalsIgnoreCase(prefix))) throw new IOException("Mixed or duplicate archive parts");
                    base = prefix; selected[index] = new UriPart(uri,size);
                    if(size > ResourceVolumes.PART_LIMIT) throw new IOException("Oversized archive parts");
                    if(size <= 0) known = false; else total += size;
                }
                for(UriPart part : selected) if(part == null) throw new IOException("Missing archive parts");
                long expected = known ? total : -1;
                File staging = new File(homeDir(),".horizon-import");
                directory = new File(staging,"drive-parts-"+UUID.randomUUID());
                if(!directory.mkdirs()) throw new IOException("Cannot create archive parts folder");
                File[] parts = new File[ResourceVolumes.PARTS]; long received = 0, lastUpdate = 0;
                for(int i=0;i<parts.length;i++) {
                    if(destroyed || attempt != setupAttempt) throw new InterruptedIOException();
                    parts[i] = new File(directory,"part-"+i);
                    android.os.CancellationSignal signal = new android.os.CancellationSignal(); activePartsRead=signal;
                    if(destroyed || attempt != setupAttempt) signal.cancel();
                    try(android.content.res.AssetFileDescriptor asset = getContentResolver().openAssetFileDescriptor(selected[i].uri,"r",signal)) {
                        if(asset == null) throw new IOException("Cannot read archive parts");
                        try(InputStream in = asset.createInputStream(); OutputStream out = new FileOutputStream(parts[i])) {
                            byte[] buffer = new byte[65536]; int count; long partBytes = 0;
                            while((count = in.read(buffer)) != -1) {
                                if(destroyed || attempt != setupAttempt) throw new InterruptedIOException();
                                partBytes += count; received += count;
                                if(partBytes > ResourceVolumes.PART_LIMIT) throw new IOException("Oversized archive parts");
                                out.write(buffer,0,count);
                                long now = SystemClock.uptimeMillis();
                                if(now-lastUpdate >= 200) {
                                    lastUpdate=now; long progress=received;
                                    ui.post(() -> { if(attempt == setupAttempt) { setupReceived=progress; setupTotal=expected; updateSetupProgress(); } });
                                }
                            }
                            if(selected[i].size > 0 && partBytes != selected[i].size) throw new IOException("Incomplete archive parts download");
                        }
                    } finally { if(activePartsRead == signal) activePartsRead=null; }
                }
                ui.post(() -> { if(attempt == setupAttempt) { installingResources=true; setupStage="assembling_parts"; render(); } });
                zip = new File(staging,"import-"+UUID.randomUUID()+".zip");
                try(OutputStream out = new FileOutputStream(zip)) { ResourceVolumes.extract(parts,out); }
                if(destroyed || attempt != setupAttempt) throw new InterruptedIOException();
                installResourceArchive(zip,attempt); zip=null; // Import now owns the reconstructed ZIP.
            } catch(Exception failure) { ui.post(() -> finishSetup(attempt,"Archive parts: "+failure)); }
            finally {
                if(zip != null) zip.delete();
                if(directory != null) { File[] files=directory.listFiles(); if(files != null) for(File file : files) file.delete(); directory.delete(); }
            }
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if(request == 11 && result == RESULT_OK && data != null) { installDriveParts(data); return; }
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
                if(resources) { installResourceArchive(staged,attempt); return; }
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
        addButton("install_drive_parts", this::pickDriveParts);
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
        addButton("display_refresh_rate", this::chooseDisplayRate);
        page.addView(detail(getString(R.string.display_refresh_summary,requestedHz)));
        addButton("touch_controls", this::touchControls);
        addButton("screenshots", this::gallery);
        addButton("share_logs", this::shareDiagnostics);
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
        page.addView(label("Horizon · 0.2.103-android · ARM64",14));
    }
    static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }
    private boolean hasGameFiles() { return PreparedResources.complete(new File(homeDir(),"resources")); }
    private String resourceSetupExplanation() { return t(new File(homeDir(),"resources/horizon-resources.json").isFile() ? "incomplete_resources" : "private_download_help"); }
    private void showResourceSetup() {
        needsResources = true; tab = "play"; render();
        if (!automaticSetupAttempted && !setupBusy) { automaticSetupAttempted = true; setupMessage = resourceSetupExplanation(); render(); }
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
            primary(download);
            Button privateDownload = button("private_download", this::openPrivateDownload);
            box.addView(download, new LinearLayout.LayoutParams(-1, dp(49)));
            box.addView(button("install_drive_parts", this::pickDriveParts), new LinearLayout.LayoutParams(-1, dp(49)));
            box.addView(privateDownload, new LinearLayout.LayoutParams(-1, dp(49)));
            Button choose = button("import_resources", () -> pick("resources"));
            LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, -2); bounds.setMargins(0, dp(9), 0, 0); box.addView(choose, bounds);
            box.addView(button("use_download_link", this::customResourceLink));
        }
        updateSetupProgress();
    }
    private void updateSetupProgress() {
        if(setupDescription != null) {
            String text = setupBusy ? t(setupStage != null ? setupStage : installingResources ? "installing_files" : "downloading") : setupMessage == null ? t("setup_explanation") : setupMessage;
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
        setupBusy = true; installingResources = installing; setupMessage = null; setupStage = null; setupReceived = 0; setupTotal = 0;
        int attempt = ++setupAttempt; render(); return attempt;
    }
    private void finishSetup(int attempt, String failure) {
        if(attempt != setupAttempt || destroyed) return;
        launcherWindow.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setupBusy = false; installingResources = false; setupStage = null;
        if(failure != null || !hasGameFiles()) {
            android.util.Log.w("HorizonSetup", "Game-files setup failed: " + failure);
            needsResources = true;
            String reason = failure == null ? "" : failure.toLowerCase(java.util.Locale.ROOT);
            String key = reason.contains("incomplete resource") ? "incomplete_resources"
                : reason.contains("http ") ? "download_server_error"
                : reason.contains("html") ? "download_sign_in"
                : reason.contains("parts need space") ? "parts_no_space"
                : reason.contains("space") || reason.contains("enospc") ? "download_no_space"
                : reason.contains("archive parts") ? "parts_failed"
                : reason.contains("timeout") || reason.contains("unknownhost") || reason.contains("connect") ? "download_connection_error"
                : "setup_failed";
            setupMessage = t(key);
        } else { needsResources = false; setupMessage = null; status.setText(t("waiting")); }
        render();
    }
    private void cancelResourceDownload() {
        if(installingResources) return;
        launcherWindow.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ++setupAttempt; setupBusy = false; setupMessage = null; setupStage = null;
        android.os.CancellationSignal read = activePartsRead; if(read != null) read.cancel();
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
        int attempt = beginSetup(false);
        // Same automatic source and checked importer as SwiftUI GameFilesDownload.
        request("game-files-source", source -> {
            if(attempt != setupAttempt || destroyed) return;
            setupBusy = false;
            String address = source.optString("url");
            if(address.isEmpty()) { finishSetup(attempt,"missing download source"); return; }
            download(address);
        }, failure -> finishSetup(attempt,failure));
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
                installResourceArchive(zip,attempt);
            } catch(Exception e) { zip.delete(); ui.post(() -> finishSetup(attempt, e.toString())); }
            finally { if(connection != null) { connection.disconnect(); if(activeDownload == connection) activeDownload = null; } }
        });
    }
    /** Use the same checked native importer as iOS; never modify a published resource tree. */
    private void installResourceArchive(File zip, int attempt) {
        final PreparedResources source;
        try { source = new PreparedResources(zip); source.close(); }
        catch(Exception failure) { zip.delete(); ui.post(() -> finishSetup(attempt,failure.toString())); return; }
        if(destroyed) { closeResourceArchive(source,zip); return; }
        ui.post(() -> {
            if(destroyed || attempt != setupAttempt) { closeResourceArchive(source,zip); return; }
            installingResources = true; setupStage = null; render();
            request("import-game-files " + zip.getAbsolutePath(), result -> workers.execute(() -> {
                String failure = null;
                try {
                    int checked = source.verifyPublished(new File(homeDir(),"resources"));
                    getPreferences(0).edit().putInt("resource_verified_files",checked).apply();
                } catch(Exception error) { failure = error.toString(); }
                finally { closeResourceArchive(source,zip); }
                String outcome = failure;
                ui.post(() -> finishSetup(attempt,outcome));
            }), failure -> { closeResourceArchive(source,zip); finishSetup(attempt,failure); });
        });
    }
    private void closeResourceArchive(PreparedResources source, File zip) {
        try { source.close(); } catch(IOException ignored) { /* Read-only source handle. */ }
        zip.delete();
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
    private void chooseDisplayRate() {
        String[] labels={t("refresh_system"),t("refresh_60"),t("refresh_120")}; int[] values={0,60,120};
        int current=getPreferences(0).getInt("display_hz",120), checked=current==0 ? 0 : current==60 ? 1 : 2;
        new AlertDialog.Builder(this).setTitle(t("display_refresh_rate")).setSingleChoiceItems(labels,checked,(dialog,index) -> {
            getPreferences(0).edit().putInt("display_hz",values[index]).apply(); applyDisplayRate();
            dialog.dismiss();
            if(values[index]>0) request("options-set maxFps "+values[index],v -> render(),this::error); else render();
        }).setNegativeButton(android.R.string.cancel,null).show();
    }
    private void applyDisplayRate() {
        Display display=getWindowManager().getDefaultDisplay(); if(display==null) return;
        int target=getPreferences(0).getInt("display_hz",120), modeId=0; requestedHz=0;
        if(target>0) {
            Display.Mode current=display.getMode(); Display.Mode[] modes=display.getSupportedModes();
            int[] widths=new int[modes.length],heights=new int[modes.length];float[] rates=new float[modes.length];
            for(int i=0;i<modes.length;i++) { widths[i]=modes[i].getPhysicalWidth();heights[i]=modes[i].getPhysicalHeight();rates[i]=modes[i].getRefreshRate(); }
            int best=RefreshRatePolicy.choose(current.getPhysicalWidth(),current.getPhysicalHeight(),widths,heights,rates,target);
            if(best>=0) { modeId=modes[best].getModeId();requestedHz=rates[best]; }
        }
        for(Window window:new Window[]{getWindow(),launcherWindow==null ? null : launcherWindow.getWindow()}) if(window!=null) {
            WindowManager.LayoutParams attributes=window.getAttributes();
            attributes.preferredDisplayModeId=modeId;attributes.preferredRefreshRate=requestedHz;window.setAttributes(attributes);
        }
        applySurfaceRate();
    }
    private void applySurfaceRate() {
        if(Build.VERSION.SDK_INT>=30 && gameSurface!=null && gameSurface.getSurface().isValid()) {
            try {
                if(Build.VERSION.SDK_INT>=31) gameSurface.getSurface().setFrameRate(requestedHz,Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
                else gameSurface.getSurface().setFrameRate(requestedHz,Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            } catch(IllegalStateException | IllegalArgumentException unavailable) { android.util.Log.w("Horizon","Surface refresh hint unavailable"); }
        }
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { super.surfaceCreated(holder);journal("native surface created");gameSurface=holder;applySurfaceRate(); }
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) {
        super.surfaceChanged(holder,format,width,height);journal("native surface resized "+width+"x"+height);gameSurface=holder;launchSurface.changed(width,height,SystemClock.uptimeMillis());applySurfaceRate();
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { journal("native surface destroyed");gameSurface=null;launchSurface.destroyed();super.surfaceDestroyed(holder); }
    private void gameSettings() {
        page.removeAllViews(); heading("game_settings");
        addButton("max_performance", () -> ask("options-set graphicsPreset max_performance",v -> { Toast.makeText(this,t("max_performance_applied"),Toast.LENGTH_LONG).show(); gameSettings(); }));
        page.addView(detail(t("max_performance_description")));
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
    /** Bounded phase/dimension records only: never commands, URLs, names or account responses. */
    private synchronized void journal(String phase) {
        try {
            File file=new File(getFilesDir(),"startup-lifecycle.log");
            if(file.length()>32768) {
                File previous=new File(getFilesDir(),"startup-lifecycle.previous.log");
                if(previous.exists() && !previous.delete()) return;
                if(!file.renameTo(previous)) return;
            }
            try(OutputStream out=new FileOutputStream(file,true)) {
                out.write((System.currentTimeMillis()+" "+phase+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch(IOException unavailable) { /* Diagnostics must never prevent startup. */ }
    }
    private void shareDiagnostics() {
        workers.execute(() -> {
            try {
                File dir = new File(getCacheDir(),"diagnostics");
                if(!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create report folder");
                ArrayList<File> traces = new ArrayList<>();
                StringBuilder report = new StringBuilder("Horizon Android diagnostics\n");
                report.append("App: ").append(getPackageManager().getPackageInfo(getPackageName(),0).versionName).append('\n');
                report.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" API ").append(Build.VERSION.SDK_INT).append('\n');
                android.content.pm.PackageInfo web = android.webkit.WebView.getCurrentWebViewPackage();
                report.append("WebView: ").append(web == null ? "unavailable" : web.packageName + " " + web.versionName).append('\n');
                report.append("Navigation: ").append(glassNavigationReady ? "Liquid-Glass WebView with native pill" : "native fallback").append('\n');
                report.append("Display requested Hz: ").append(requestedHz).append("; previous startup incomplete: ").append(previousLaunchFailed).append('\n');
                report.append("Free bytes: ").append(homeDir().getUsableSpace()).append('\n');
                if(Build.VERSION.SDK_INT >= 30) {
                    report.append("\nRecent process exits:\n");
                    try {
                        ActivityManager manager = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
                        for(android.app.ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(getPackageName(),0,2)) {
                            report.append("reason=").append(exit.getReason()).append(" status=").append(exit.getStatus())
                                .append(" timestamp=").append(exit.getTimestamp()).append(" pssKiB=").append(exit.getPss())
                                .append(" rssKiB=").append(exit.getRss()).append(" description=").append(exit.getDescription()).append('\n');
                            try(InputStream trace = exit.getTraceInputStream()) {
                                if(trace == null) continue;
                                // Native tombstones are protobuf, not UTF-8 log text. Preserve their bytes.
                                String extension = exit.getReason() == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ? ".pb" : ".trace";
                                File dump = new File(dir,"exit-"+exit.getPid()+"-"+exit.getTimestamp()+extension);
                                try(OutputStream out = new FileOutputStream(dump)) {
                                    byte[] buffer = new byte[8192]; int remaining = 4*1024*1024, count;
                                    while(remaining > 0 && (count = trace.read(buffer,0,Math.min(buffer.length,remaining))) != -1) {
                                        out.write(buffer,0,count); remaining -= count;
                                    }
                                    if(remaining == 0) report.append("Trace capture limited to 4 MiB\n");
                                }
                                traces.add(dump); report.append("Trace attachment: ").append(dump.getName()).append('\n');
                            }
                        }
                    } catch(IOException | RuntimeException unavailable) { report.append("System exit trace unavailable\n"); }
                }
                for(String name:new String[]{"startup-lifecycle.previous.log","startup-lifecycle.log"}) {
                    File journal=new File(getFilesDir(),name);
                    if(journal.isFile()) try(InputStream in=new FileInputStream(journal)) {
                        report.append("\n").append(name).append(":\n").append(new String(readBytes(in),java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                report.append("Files verified after last import: ").append(getPreferences(0).getInt("resource_verified_files",-1)).append('\n');
                Properties checks = new Properties();
                try(InputStream in = getAssets().open("resource-checks.properties")) { checks.load(in); }
                report.append("\nBase resource checks (custom packs can override these files):\n");
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                TreeSet<String> paths = new TreeSet<>(checks.stringPropertyNames());
                for(String path : paths) {
                    File file = new File(homeDir(),"resources/" + path);
                    report.append(path).append(": ");
                    if(!file.isFile()) { report.append("MISSING\n"); continue; }
                    digest.reset();
                    try(InputStream in = new FileInputStream(file)) {
                        byte[] buffer = new byte[65536]; int count;
                        while((count = in.read(buffer)) != -1) digest.update(buffer,0,count);
                    }
                    StringBuilder hash = new StringBuilder();
                    for(byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT,"%02x",value & 255));
                    report.append(hash.toString().equals(checks.getProperty(path)) ? "OK" : "DIFFERENT").append(" bytes=").append(file.length()).append('\n');
                }
                File options = new File(homeDir(),"options.txt");
                if(options.isFile()) try(BufferedReader reader = new BufferedReader(new FileReader(options))) {
                    report.append("\nRendering options:\n"); String line;
                    while((line = reader.readLine()) != null) if(line.startsWith("resourcePacks:") || line.startsWith("forceUnicodeFont:") || line.startsWith("japaneseGlyphVariants:") || line.startsWith("mipmapLevels:")) report.append(line).append('\n');
                }
                for(String path : new String[]{".local/share/horizon/logs/latest.log","logs/latest.log"}) {
                    File log = new File(homeDir(),path);
                    if(!log.isFile()) continue;
                    report.append("\nEngine log:\n");
                    try(RandomAccessFile in = new RandomAccessFile(log,"r")) {
                        if(in.length() > 327680) {
                            byte[] head = new byte[65536]; in.readFully(head);
                            report.append(new String(head,java.nio.charset.StandardCharsets.UTF_8)).append("\n[... middle of log omitted ...]\n");
                        }
                        in.seek(Math.max(0,in.length()-262144)); byte[] tail = new byte[(int)(in.length()-in.getFilePointer())]; in.readFully(tail);
                        report.append(new String(tail,java.nio.charset.StandardCharsets.UTF_8));
                    }
                    break;
                }
                File output = new File(dir,"horizon-diagnostics.txt");
                try(OutputStream out = new FileOutputStream(output)) { out.write(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                if(traces.isEmpty()) ui.post(() -> { if(!destroyed) share(output,"text/plain"); });
                else {
                    File bundle = new File(dir,"horizon-diagnostics.zip");
                    try(java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(new FileOutputStream(bundle))) {
                        ArrayList<File> entries = new ArrayList<>(traces); entries.add(output);
                        for(File file : entries) {
                            out.putNextEntry(new java.util.zip.ZipEntry(file.getName()));
                            try(InputStream in = new FileInputStream(file)) {
                                byte[] buffer = new byte[8192]; int count;
                                while((count = in.read(buffer)) != -1) out.write(buffer,0,count);
                            }
                            out.closeEntry();
                        }
                    }
                    ui.post(() -> { if(!destroyed) share(bundle,"application/zip"); });
                }
            } catch(Exception e) { ui.post(() -> { if(!destroyed) error(t("file_unavailable")); }); }
        });
    }
    @Override public void onBackPressed() {
        if (launching && launchOwner != null) {
            String owner = launchOwner; launchOwner = null;
            request("launcher-cancel-start " + owner, v -> { launching = false; getPreferences(0).edit().putBoolean("launch_in_progress",false).apply(); setLauncherVisible(true); },failure -> { launching=false; setLauncherVisible(true); });
        } else if (!visible) ask("launcher-visible true", v -> setLauncherVisible(true));
        else { tab = "play"; render(); }
    }
    @Override protected void onPause() {
        ui.removeCallbacks(watch);keyboardVisible=false;
        cancelGlassGesture();
        for(int i=0;i<controllers.size();i++)controllerRemoved(controllers.keyAt(i));controllers.clear();
        if(glassNavigation!=null) glassNavigation.onPause();
        journal("activity paused");
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();applyDisplayRate();if(glassNavigation!=null) glassNavigation.onResume();journal("activity resumed");cancelGlassGesture();ui.removeCallbacks(watch);if(overlay!=null)ui.post(watch);
    }
    private void cancelGlassGesture() {
        if(navigationDock != null) navigationDock.cancelGesture();
        if(glassNavigation!=null && glassNavigationReady) glassNavigation.evaluateJavascript("window.horizonNav && window.horizonNav.suspend()",null);
    }
    @Override protected void onDestroy() { destroyed = true; if(activePartsRead != null)activePartsRead.cancel(); if(glassNavigation!=null) {android.webkit.WebView view=glassNavigation;glassNavigation=null;navigationDock.removeView(view);view.removeJavascriptInterface("HorizonNav");view.destroy();} if(launcherWindow!=null)launcherWindow.dismiss(); if(inputManager!=null)inputManager.unregisterInputDeviceListener(deviceListener); ui.removeCallbacksAndMessages(null); workers.shutdownNow(); super.onDestroy(); }
}
