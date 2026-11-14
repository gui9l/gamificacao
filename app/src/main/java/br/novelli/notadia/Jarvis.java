package br.novelli.notadia;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * "Falar com o Jarvis" pela notificação da Porta: o texto vai para a aba "jarvis" da planilha, o PC (Jarvis) lê,
 * resolve (lembrete, agenda...) e devolve uma resposta curta, que aparece na própria notificação por alguns minutos.
 * Se o Jarvis pedir confirmação, a notificação ganha os botões Sim / Não.
 */
public class Jarvis {

    static final String ACAO = "br.novelli.notadia.JARVIS";
    static final String CHAVE = "texto";
    static final long MOSTRA_MS = 15 * 60000L;   // a resposta fica na notificação por 15 min

    /** Linha que a notificação mostra agora (vazia = texto padrão da Porta). */
    static String linha(Context c) {
        SharedPreferences p = Sync.prefs(c);
        if (System.currentTimeMillis() - p.getLong("jvTs", 0) > MOSTRA_MS) return "";
        return p.getString("jvLinha", "");
    }

    static boolean pergunta(Context c) {
        return !linha(c).isEmpty() && Sync.prefs(c).getBoolean("jvPergunta", false);
    }

    static void mostrar(Context c, String linha, boolean pergunta) {
        Sync.prefs(c).edit().putString("jvLinha", linha).putBoolean("jvPergunta", pergunta)
                .putLong("jvTs", System.currentTimeMillis()).apply();
    }

    private static PendingIntent intencao(Context c, int req, String fixo) {
        Intent i = new Intent(c, Porta.class).setAction(ACAO);
        if (fixo != null) i.putExtra(CHAVE, fixo);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) fl |= (fixo == null ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE);
        return PendingIntent.getForegroundService(c, req, i, fl);
    }

    /** Põe na notificação o campo de resposta e, se o Jarvis estiver esperando, os botões Sim / Não. */
    static void acoes(Context c, Notification.Builder b) {
        RemoteInput ri = new RemoteInput.Builder(CHAVE).setLabel("Ex.: me lembra amanhã de…").build();
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_send, "Falar com o Jarvis", intencao(c, 70, null))
                .addRemoteInput(ri).setAllowGeneratedReplies(false).build());
        if (pergunta(c)) {
            b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_send, "Sim", intencao(c, 71, "sim")).build());
            b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_send, "Não", intencao(c, 72, "não")).build());
        }
    }

    /** Texto que chegou da notificação (campo ou botão). */
    static String textoDe(Intent i) {
        Bundle r = RemoteInput.getResultsFromIntent(i);
        CharSequence t = r != null ? r.getCharSequence(CHAVE) : null;
        if (t == null) t = i.getStringExtra(CHAVE);
        return t == null ? "" : t.toString().trim();
    }

    /** Em segundo plano: manda o texto e espera a resposta do PC (até 3 min). Chama redesenhar a cada mudança. */
    static void enviar(final Context c, final String texto, final Runnable redesenhar) {
        mostrar(c, "Enviando: " + texto, false);
        redesenhar.run();
        new Thread(new Runnable() {
            @Override public void run() {
                SharedPreferences p = Sync.prefs(c);
                String url = p.getString("url", "").trim(), token = p.getString("token", "").trim();
                String id = "m" + System.currentTimeMillis();
                try {
                    JSONObject corpo = new JSONObject();
                    corpo.put("token", token);
                    corpo.put("msg", new JSONObject().put("id", id).put("texto", texto));
                    JSONObject r = new JSONObject(Sync.postar(url, corpo.toString()));
                    if (!r.optBoolean("ok")) throw new Exception(r.optString("erro"));
                } catch (Exception e) {
                    mostrar(c, "Não consegui enviar (sem internet?). Tente de novo.", false);
                    redesenhar.run();
                    return;
                }
                mostrar(c, "Enviado ao Jarvis, aguardando… (" + texto + ")", false);
                redesenhar.run();
                for (int n = 0; n < 36; n++) {
                    try { Thread.sleep(5000); } catch (InterruptedException x) { return; }
                    try {
                        JSONObject corpo = new JSONObject();
                        corpo.put("token", token);
                        corpo.put("ver", new JSONArray().put(id));
                        JSONArray cx = new JSONObject(Sync.postar(url, corpo.toString())).optJSONArray("caixa");
                        if (cx != null && cx.length() > 0) {
                            JSONObject m = cx.getJSONObject(0);
                            mostrar(c, m.optString("r"), m.optBoolean("p"));
                            redesenhar.run();
                            return;
                        }
                    } catch (Exception ignorada) { }
                }
                mostrar(c, "Guardado. O PC parece desligado; o Jarvis resolve quando ligar.", false);
                redesenhar.run();
            }
        }).start();
    }
}
