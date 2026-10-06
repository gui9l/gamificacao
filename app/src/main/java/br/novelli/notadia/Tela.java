package br.novelli.notadia;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** Tela única do app: resumo do dia, passos de configuração e conexão com a planilha. */
public class Tela {
    private static final int BG = 0xFFFBF6EA, PAINEL = 0xFFF1E8D3, BORDA = 0xFFE3D6B8, BTNB = 0xFFD9C8A3;
    private static final int TXT = 0xFF2E1A0E, SUAVE = 0xFF8B6F55, OURO = 0xFFC9962F, BRILHO = 0xFF9A6B12;
    private static final int VINHO = 0xFF8B1A4A, VERDE = 0xFF4E7D4A, VERM = 0xFFB4423A;

    private final Activity act;
    public Tela(Activity a) { act = a; }

    private EditText url, token;
    private Anel anel;
    private TextView lInsta, lTik, lEnvio, lErro, lPasso1, lPasso2, lPasso3, lMeta;
    private Button bEnviar;

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable forma(int cor, int raio, int borda) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(cor);
        g.setCornerRadius(dp(raio));
        if (borda != 0) g.setStroke(dp(1), borda);
        return g;
    }

    private LinearLayout cartao(LinearLayout pai) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(forma(PAINEL, 18, BORDA));
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        pai.addView(c, lp);
        return c;
    }

    private TextView texto(String t, int sp, boolean negrito, int cor) {
        TextView v = new TextView(act);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(cor);
        if (negrito) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private Button botao(String t, boolean primario, View.OnClickListener l) {
        Button b = new Button(act);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primario ? Color.WHITE : TXT);
        b.setBackground(forma(primario ? VINHO : BTNB, 12, 0));
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    private EditText campo(String dica) {
        EditText e = new EditText(act);
        e.setSingleLine(true);
        e.setHint(dica);
        e.setTextSize(14);
        e.setTextColor(TXT);
        e.setHintTextColor(0xFFB09A7C);
        e.setBackground(forma(0xFFFFFFFF, 10, BORDA));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    private LinearLayout.LayoutParams topo(int dpTopo) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(dpTopo);
        return lp;
    }

    private LinearLayout linhaPasso(LinearLayout pai, TextView rotulo, String botaoTxt, View.OnClickListener l) {
        LinearLayout ln = new LinearLayout(act);
        ln.setOrientation(LinearLayout.HORIZONTAL);
        ln.setGravity(Gravity.CENTER_VERTICAL);
        rotulo.setTextSize(14);
        rotulo.setTextColor(TXT);
        ln.addView(rotulo, new LinearLayout.LayoutParams(0, -2, 1f));
        Button b = botao(botaoTxt, false, l);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        ln.addView(b, new LinearLayout.LayoutParams(-2, dp(38)));
        pai.addView(ln, topo(10));
        return ln;
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
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(24), dp(16), dp(28));
        sv.addView(col);

        // cabeçalho
        LinearLayout cab = new LinearLayout(act);
        cab.setOrientation(LinearLayout.HORIZONTAL);
        cab.setGravity(Gravity.CENTER_VERTICAL);
        cab.addView(texto("Nota do dia", 24, true, TXT));
        TextView pill = texto("celular", 12, true, BRILHO);
        pill.setBackground(forma(0xFFE8D29A, 20, 0));
        pill.setPadding(dp(10), dp(3), dp(10), dp(3));
        LinearLayout.LayoutParams lpp = new LinearLayout.LayoutParams(-2, -2);
        lpp.leftMargin = dp(10);
        cab.addView(pill, lpp);
        col.addView(cab);

        // cartão: hoje
        LinearLayout hoje = cartao(col);
        hoje.setGravity(Gravity.CENTER_HORIZONTAL);
        anel = new Anel(act);
        hoje.addView(anel, new LinearLayout.LayoutParams(dp(210), dp(210)));
        lMeta = texto("", 13, false, SUAVE);
        lMeta.setGravity(Gravity.CENTER);
        hoje.addView(lMeta, topo(2));
        lInsta = texto("", 14, false, TXT);
        hoje.addView(lInsta, topo(14));
        lTik = texto("", 14, false, TXT);
        hoje.addView(lTik, topo(4));
        lEnvio = texto("", 12, false, SUAVE);
        hoje.addView(lEnvio, topo(12));

        // cartão: configuração
        LinearLayout cfg = cartao(col);
        cfg.addView(texto("Configuração", 16, true, TXT));
        lPasso1 = new TextView(act);
        linhaPasso(cfg, lPasso1, "abrir", new View.OnClickListener() {
            @Override public void onClick(View v) {
                act.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            }
        });
        lPasso2 = new TextView(act);
        linhaPasso(cfg, lPasso2, "abrir", new View.OnClickListener() {
            @Override public void onClick(View v) {
                act.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });

        lPasso3 = new TextView(act);
        linhaPasso(cfg, lPasso3, "abrir", new View.OnClickListener() {
            @Override public void onClick(View v) {
                act.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        TextView dica3 = texto("Porta: ative \"Nota do dia · Porta\" em Acessibilidade. Se o Android bloquear, abra Informações do app → ⋮ → Permitir configurações restritas.", 11, false, SUAVE);
        cfg.addView(dica3, topo(6));

        // cartão: conexão
        LinearLayout con = cartao(col);
        con.addView(texto("Conexão com a planilha", 16, true, TXT));
        TextView l1 = texto("Link (termina em /exec)", 12, false, SUAVE);
        con.addView(l1, topo(10));
        url = campo("https://script.google.com/…/exec");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        con.addView(url, topo(4));
        TextView l2 = texto("Código secreto", 12, false, SUAVE);
        con.addView(l2, topo(10));
        token = campo("o mesmo do script");
        con.addView(token, topo(4));
        bEnviar = botao("Salvar e enviar agora", true, new View.OnClickListener() {
            @Override public void onClick(View v) { salvarEEnviar(); }
        });
        con.addView(bEnviar, new LinearLayout.LayoutParams(-1, dp(48)));
        ((LinearLayout.LayoutParams) bEnviar.getLayoutParams()).topMargin = dp(14);

        lErro = texto("", 11, false, VERM);
        col.addView(lErro, topo(12));
        TextView rodape = texto("Envia o uso a cada ~30 min, sem notificação fixa. A Nota do computador lê da planilha.", 12, false, SUAVE);
        col.addView(rodape, topo(12));

        act.setContentView(sv);

        SharedPreferences p = Sync.prefs(act);
        url.setText(p.getString("url", ""));
        token.setText(p.getString("token", ""));
        try { if (android.os.Build.VERSION.SDK_INT >= 33) act.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7); } catch (Throwable e) { }
        try { SyncJob.agendar(act); Sync.prefs(act).edit().remove("erro").apply(); } catch (Throwable e) { Sync.prefs(act).edit().putString("erro", "agendar: " + e).apply(); }
        atualizarStatus();
    }

    private boolean semRestricaoDeBateria() {
        try {
            PowerManager pm = (PowerManager) act.getSystemService(android.content.Context.POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(act.getPackageName());
        } catch (Throwable e) { return false; }
    }

    public void atualizarStatus() {
        if (anel == null) return;
        SharedPreferences p = Sync.prefs(act);

        boolean acesso = Sync.temAcessoAoUso(act);
        lPasso1.setText((acesso ? "✓  " : "○  ") + "Acesso ao uso");
        lPasso1.setTextColor(acesso ? VERDE : TXT);
        boolean bat = semRestricaoDeBateria();
        lPasso2.setText((bat ? "✓  " : "○  ") + "Bateria sem restrições");
        lPasso2.setTextColor(bat ? VERDE : TXT);
        boolean porta = Porta.ativa(act);
        lPasso3.setText((porta ? "✓  " : "○  ") + "Porta (Instagram/TikTok)");
        lPasso3.setTextColor(porta ? VERDE : TXT);

        long ts = p.getLong("resumoTs", 0);
        int total = p.getInt("totalHoje", 0);
        int meta = 0;
        try { String pl = p.getString("plano", ""); if (pl.length() > 0) meta = Avisos.metaDoDia(new JSONObject(pl)); } catch (Throwable e) { }

        if (ts == 0) {
            anel.progresso = 0f; anel.corAnel = OURO;
            anel.grande = "—"; anel.pequeno = "sem dados ainda";
            lMeta.setText("");
            lInsta.setText(""); lTik.setText("");
        } else {
            anel.grande = Avisos.hm(total);
            if (meta > 0) {
                float f = total / (float) meta;
                anel.progresso = f;
                anel.corAnel = f >= 1f ? VERM : (f >= 0.8f ? OURO : VERDE);
                anel.pequeno = "de " + Avisos.hm(meta);
                lMeta.setText(f >= 1f ? "Meta de hoje ultrapassada" : (f >= 0.8f ? "Perto da meta de hoje" : "Dentro da meta de hoje"));
                lMeta.setTextColor(f >= 1f ? VERM : (f >= 0.8f ? BRILHO : VERDE));
            } else {
                anel.progresso = 0f; anel.corAnel = OURO;
                anel.pequeno = "tempo de tela hoje";
                lMeta.setText("Abra a Nota no computador para enviar a meta do dia.");
                lMeta.setTextColor(SUAVE);
            }
            lInsta.setText("Instagram   " + Avisos.hm(p.getInt("insMin", 0)) + "  ·  " + p.getInt("insAb", 0) + " aberturas");
            lTik.setText("TikTok         " + Avisos.hm(p.getInt("tikMin", 0)) + "  ·  " + p.getInt("tikAb", 0) + " aberturas");
        }
        anel.invalidate();

        String ult = p.getString("ultimo", "Ainda não enviou.");
        lEnvio.setText(ult);
        long ago = System.currentTimeMillis() - p.getLong("ultimoTs", 0);
        boolean ok = ult.startsWith("Enviado");
        lEnvio.setTextColor(!ok ? VERM : (ago > 90 * 60 * 1000L ? BRILHO : VERDE));

        String er = p.getString("erro", "");
        lErro.setText(er.isEmpty() ? "" : "ERRO REGISTRADO:\n" + er);
        lErro.setVisibility(er.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void salvarEEnviar() {
        Sync.prefs(act).edit()
                .putString("url", url.getText().toString().trim())
                .putString("token", token.getText().toString().trim())
                .apply();
        lEnvio.setText("Enviando…");
        lEnvio.setTextColor(SUAVE);
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
