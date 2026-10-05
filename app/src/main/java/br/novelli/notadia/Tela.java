package br.novelli.notadia;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Tela única: link da planilha, código, permissões e botão de enviar agora. */
public class Tela {
    private final Activity act;
    public Tela(Activity a) { act = a; }


    private EditText url, token;
    private TextView status;

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private TextView texto(String t, int sp, boolean negrito, int cor) {
        TextView v = new TextView(act);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(cor);
        if (negrito) v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(6), 0, dp(6));
        return v;
    }

    private Button botao(String t, View.OnClickListener l) {
        Button b = new Button(act);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    public void montar() {
        final android.content.Context ctx = act.getApplicationContext();
        final Thread.UncaughtExceptionHandler antigo = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    String tx = sw.toString();
                    Sync.prefs(ctx).edit().putString("erro", tx.length() > 900 ? tx.substring(0, 900) : tx).commit();
                } catch (Throwable x) { }
                if (antigo != null) antigo.uncaughtException(t, e);
            }
        });
        ScrollView sv = new ScrollView(act);
        sv.setBackgroundColor(Color.parseColor("#FBF6EA"));
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(28), dp(20), dp(28));
        sv.addView(col);

        int escuro = Color.parseColor("#2E1A0E");
        int suave = Color.parseColor("#8B6F55");

        col.addView(texto("Nota do dia · celular (v3)", 22, true, escuro));
        col.addView(texto("Envia o seu tempo de tela para a planilha. A Nota do computador lê de lá.", 14, false, suave));

        status = texto("", 14, true, escuro);
        col.addView(status);

        col.addView(texto("Link da planilha (termina em /exec)", 13, true, escuro));
        url = new EditText(act);
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        col.addView(url);

        col.addView(texto("Código secreto", 13, true, escuro));
        token = new EditText(act);
        token.setSingleLine(true);
        col.addView(token);

        col.addView(botao("1. Permitir acesso ao uso", new View.OnClickListener() {
            @Override public void onClick(View v) {
                act.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            }
        }));
        col.addView(botao("2. Tirar da economia de bateria", new View.OnClickListener() {
            @Override public void onClick(View v) {
                act.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }));
        col.addView(botao("3. Salvar e enviar agora", new View.OnClickListener() {
            @Override public void onClick(View v) { salvarEEnviar(); }
        }));

        col.addView(texto("No passo 1, procure \"Nota do dia\" na lista e ative. No passo 2, deixe o app como \"Não otimizado\" para o envio não ser interrompido.", 12, false, suave));

        act.setContentView(sv);

        SharedPreferences p = Sync.prefs(act);
        url.setText(p.getString("url", ""));
        token.setText(p.getString("token", ""));
        try { SyncJob.agendar(act); Sync.prefs(act).edit().remove("erro").apply(); } catch (Throwable e) { Sync.prefs(act).edit().putString("erro", "agendar: " + e).apply(); }
    }


    public void atualizarStatus() {
        if (status == null) return;
        SharedPreferences p = Sync.prefs(act);
        String acesso = Sync.temAcessoAoUso(act) ? "Acesso ao uso: liberado" : "Acesso ao uso: FALTA liberar";
        String ult = p.getString("ultimo", "Ainda não enviou.");
        String er = p.getString("erro", "");
        status.setText(acesso + "\nÚltimo envio: " + ult + (er.isEmpty() ? "" : "\n\nERRO REGISTRADO:\n" + er));
    }

    private void salvarEEnviar() {
        Sync.prefs(act).edit()
                .putString("url", url.getText().toString().trim())
                .putString("token", token.getText().toString().trim())
                .apply();
        status.setText("Enviando…");
        new Thread(new Runnable() {
            @Override public void run() {
                Sync.enviar(act.getApplicationContext());
                act.runOnUiThread(new Runnable() {
                    @Override public void run() { atualizarStatus(); }
                });
            }
        }).start();
    }
}
