package br.novelli.notadia;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Coleta o uso e envia para a planilha (script do Google). */
public class Sync {

    public static final String PREFS = "cfg";

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean temAcessoAoUso(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int modo = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), c.getPackageName());
            return modo == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Roda no máximo uma vez por vez; devolve uma mensagem para mostrar na tela. */
    public static synchronized String enviar(Context c) {
        SharedPreferences p = prefs(c);
        String url = p.getString("url", "").trim();
        String token = p.getString("token", "").trim();
        String msg;
        try {
            if (url.isEmpty() || token.isEmpty()) {
                msg = "Falta preencher o link e o código.";
            } else if (!temAcessoAoUso(c)) {
                msg = "Falta liberar o acesso ao uso (passo 1).";
            } else {
                JSONArray dias = new Coletor(c).coletar(7);
                JSONObject corpo = new JSONObject();
                corpo.put("token", token);
                corpo.put("days", dias);
                String resp = postar(url, corpo.toString());
                JSONObject r = new JSONObject(resp);
                if (r.optBoolean("ok")) {
                    msg = "Enviado às " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date())
                            + " (" + dias.length() + " dias).";
                } else {
                    msg = "A planilha recusou: " + r.optString("erro", resp);
                }
            }
        } catch (Exception e) {
            msg = "Erro: " + e.getClass().getSimpleName() + " " + e.getMessage();
        }
        p.edit().putString("ultimo", msg).putLong("ultimoTs", System.currentTimeMillis()).apply();
        return msg;
    }

    private static String ler(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    /** O Apps Script responde ao POST com um redirecionamento; seguimos à mão para ler o resultado. */
    private static String postar(String url, String corpo) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
        OutputStream os = c.getOutputStream();
        os.write(corpo.getBytes("UTF-8"));
        os.close();
        int code = c.getResponseCode();
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            HttpURLConnection g = (HttpURLConnection) new URL(loc).openConnection();
            g.setConnectTimeout(20000);
            g.setReadTimeout(30000);
            String txt = ler(g);
            g.disconnect();
            return txt;
        }
        String txt = ler(c);
        c.disconnect();
        return txt;
    }
}
