package dev.horizon.android;

import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.security.keystore.*;
import android.util.Base64;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;

/** Device-code sign-in, encrypted credentials, and session refresh before every launch. */
final class Accounts {
    private final HorizonActivity activity;
    private final android.content.SharedPreferences prefs;
    private volatile int generation;
    private static final String CLIENT = "331e08f8-172d-43b9-835a-3108c2fcdbf3";
    Accounts(HorizonActivity a) { activity = a; prefs = a.getSharedPreferences("accounts",0); }
    private void main(Runnable r) { activity.runOnUiThread(r); }
    void show() {
        try {
            JSONObject catalog = load();
            JSONArray list = catalog.optJSONArray("accounts"); if (list == null) list = new JSONArray();
            List<String> labels = new ArrayList<>(); labels.add(activity.t("offline")); labels.add(activity.t("sign_in"));
            for (int i=0; i<list.length(); i++) labels.add(list.getJSONObject(i).getString("name"));
            JSONArray rows = list;
            new AlertDialog.Builder(activity).setTitle(activity.t("account"))
                .setItems(labels.toArray(new String[0]), (d,which) -> {
                    generation++;
                    if (which == 1) { signIn(); return; }
                    try {
                        if (which == 0) catalog.remove("selected");
                        else catalog.put("selected", rows.getJSONObject(which-2).getString("uuid"));
                        save(catalog);
                    } catch (Exception e) { activity.error(activity.t("account_storage")); }
                }).setNeutralButton(activity.t("remove_accounts"), (d,w) -> activity.confirm("remove_accounts", () -> { generation++; prefs.edit().clear().commit(); }))
                .setNegativeButton(android.R.string.cancel,null).show();
        } catch (Exception e) { activity.error(activity.t("account_storage")); }
    }
    void handOver(Runnable ready,java.util.function.Consumer<String> failed) {
        final int epoch = generation;
        activity.workers.execute(() -> {
            try {
                JSONObject catalog = load(); String chosen = catalog.optString("selected"); JSONObject account = null;
                JSONArray accounts = catalog.optJSONArray("accounts");
                if (!chosen.isEmpty() && accounts != null) for (int i=0;i<accounts.length();i++) {
                    JSONObject row=accounts.getJSONObject(i); if (chosen.equals(row.optString("uuid"))) account=row;
                }
                if (!chosen.isEmpty() && account == null) throw new IOException(activity.t("sign_in_again"));
                if (account != null && account.optLong("expires") < System.currentTimeMillis()+300000) {
                    JSONObject token = form("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", "client_id",CLIENT,"grant_type","refresh_token","refresh_token",account.getString("refresh"),"scope","XboxLive.signin offline_access");
                    String refresh = token.optString("refresh_token",account.getString("refresh"));
                    account.put("refresh",refresh); save(catalog); // persist rotation before downstream calls
                    JSONObject updated = exchange(token.getString("access_token"), refresh);
                    if (!updated.getString("uuid").equals(chosen)) throw new IOException(activity.t("sign_in_again"));
                    for(int i=0;i<accounts.length();i++) if(accounts.getJSONObject(i).getString("uuid").equals(chosen)) accounts.put(i,updated);
                    save(catalog); account=updated;
                }
                String command = account == null ? "launcher-session off" : "launcher-session " + activity.object("name",account.getString("name"),"uuid",account.getString("uuid"),"access_token",account.getString("token"),"user_type","msa");
                main(() -> {
                    if(epoch!=generation) { failed.accept(activity.t("sign_in_again")); return; }
                    activity.request(command,reply -> {
                        if(epoch==generation && reply.optBoolean("ok")) ready.run();
                        else failed.accept(activity.t("sign_in_again"));
                    },ignored -> failed.accept(activity.t("sign_in_again")));
                });
            } catch (Exception e) { main(() -> failed.accept(activity.t("sign_in_again"))); }
        });
    }
    private void signIn() {
        int epoch = ++generation;
        activity.workers.execute(() -> {
            try {
                JSONObject device = form("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode","client_id",CLIENT,"scope","XboxLive.signin offline_access");
                String code = device.getString("user_code"), url=device.getString("verification_uri");
                main(() -> new AlertDialog.Builder(activity).setTitle(activity.t("sign_in"))
                    .setMessage(activity.t("device_code") + "\n\n" + code + "\n" + url)
                    .setPositiveButton(activity.t("open_browser"),(d,w) -> activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))))
                    .setNegativeButton(android.R.string.cancel,(d,w) -> generation++).show());
                long deadline = System.currentTimeMillis()+device.getLong("expires_in")*1000;
                long interval = Math.max(5,device.optInt("interval",5))*1000L;
                while (epoch==generation && System.currentTimeMillis()<deadline) {
                    Thread.sleep(interval);
                    JSONObject token = form("https://login.microsoftonline.com/consumers/oauth2/v2.0/token","client_id",CLIENT,"grant_type","urn:ietf:params:oauth:grant-type:device_code","device_code",device.getString("device_code"));
                    String error = token.optString("error");
                    if(error.equals("authorization_pending")) continue;
                    if(error.equals("slow_down")) { interval+=5000; continue; }
                    if(!error.isEmpty()) throw new IOException();
                    JSONObject account=exchange(token.getString("access_token"),token.getString("refresh_token"));
                    if(epoch!=generation) return;
                    JSONObject catalog=load(); JSONArray rows=catalog.optJSONArray("accounts"); if(rows==null)rows=new JSONArray();
                    boolean replaced=false;
                    for(int i=0;i<rows.length();i++) if(rows.getJSONObject(i).getString("uuid").equals(account.getString("uuid"))) { rows.put(i,account); replaced=true; break; }
                    if(!replaced)rows.put(account);
                    catalog.put("accounts",rows).put("selected",account.getString("uuid")); save(catalog);
                    main(this::show); return;
                }
            } catch(Exception e) { if(epoch==generation)main(() -> activity.error(activity.t("sign_in_failed"))); }
        });
    }
    private JSONObject exchange(String microsoft, String refresh) throws Exception {
        JSONObject xbl=json("https://user.auth.xboxlive.com/user/authenticate",new JSONObject()
            .put("Properties",activity.object("AuthMethod","RPS","SiteName","user.auth.xboxlive.com","RpsTicket","d="+microsoft))
            .put("RelyingParty","http://auth.xboxlive.com").put("TokenType","JWT"));
        String hash=xbl.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs");
        JSONObject xsts=json("https://xsts.auth.xboxlive.com/xsts/authorize",new JSONObject()
            .put("Properties",new JSONObject().put("SandboxId","RETAIL").put("UserTokens",new JSONArray().put(xbl.getString("Token"))))
            .put("RelyingParty","rp://api.minecraftservices.com/").put("TokenType","JWT"));
        JSONObject minecraft=json("https://api.minecraftservices.com/authentication/login_with_xbox",activity.object("identityToken","XBL3.0 x="+hash+";"+xsts.getString("Token")));
        String token=minecraft.getString("access_token");
        JSONArray items=request("https://api.minecraftservices.com/entitlements/mcstore",null,null,token).getJSONArray("items");
        boolean owned=false; for(int i=0;i<items.length();i++) if(Arrays.asList("game_minecraft","product_minecraft").contains(items.getJSONObject(i).optString("name")))owned=true;
        if(!owned)throw new IOException();
        JSONObject profile=request("https://api.minecraftservices.com/minecraft/profile",null,null,token);
        String id=profile.getString("id").replace("-","").toLowerCase(Locale.ROOT);
        if(!id.matches("[0-9a-f]{32}") || minecraft.getLong("expires_in")<=0)throw new IOException();
        return activity.object("name",profile.getString("name"),"uuid",id,"token",token,"refresh",refresh)
            .put("expires",System.currentTimeMillis()+minecraft.getLong("expires_in")*1000);
    }
    private JSONObject form(String url,String... fields) throws Exception {
        StringJoiner body=new StringJoiner("&");
        for(int i=0;i<fields.length;i+=2)body.add(URLEncoder.encode(fields[i],"UTF-8")+"="+URLEncoder.encode(fields[i+1],"UTF-8"));
        return request(url,body.toString(),"application/x-www-form-urlencoded",null);
    }
    private JSONObject json(String url,JSONObject body)throws Exception {return request(url,body.toString(),"application/json",null);}
    private JSONObject request(String url,String body,String type,String token)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection(); c.setConnectTimeout(30000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(false);
        try {
            if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);
            if(body!=null) {c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type",type);try(OutputStream out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}}
            InputStream stream=c.getResponseCode()<400?c.getInputStream():c.getErrorStream();
            if(stream==null)throw new IOException();
            try(InputStream in=stream) {return new JSONObject(new String(HorizonActivity.readBytes(in),StandardCharsets.UTF_8));}
        } finally {c.disconnect();}
    }
    private SecretKey key()throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias("horizon-accounts")) {
            KeyGenerator gen=KeyGenerator.getInstance("AES","AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder("horizon-accounts",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();
        }
        return (SecretKey)store.getKey("horizon-accounts",null);
    }
    private synchronized JSONObject load()throws Exception {
        String data=prefs.getString("vault",null);if(data==null)return new JSONObject();
        String[] parts=data.split(":");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8));
    }
    private synchronized void save(JSONObject catalog)throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        String iv=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP);
        String data=Base64.encodeToString(cipher.doFinal(catalog.toString().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!prefs.edit().putString("vault",iv+":"+data).commit())throw new IOException();
    }
}
