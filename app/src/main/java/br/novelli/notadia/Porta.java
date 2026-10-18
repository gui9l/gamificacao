package br.novelli.notadia;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Porta" do Instagram/TikTok: ao entrar, você escolhe 4, 6 ou 8 min; ao acabar o tempo o app
 * te manda para a tela inicial. Só reage quando um app abre (sem serviço fixo, sem notificação).
 * Volta em até 3 min depois de sair por conta própria continua a sessão de onde parou.
 * Reabrir em até 3 min depois de ser expulso exige 5 s de espera antes de escolher outro tempo.
 */
public class Porta extends AccessibilityService {

    static final String[] ALVOS = {"com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill"};

    private static final int BG = 0xFFFBF6EA, BTNB = 0xFFD9C8A3, TXT = 0xFF2E1A0E, SUAVE = 0xFF8B6F55,
            VINHO = 0xFF8B1A4A, VERM = 0xFFB4423A, BORDA = 0xFFE3D6B8;

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View overlay;

    private boolean emAlvo = false;
    private long restanteMs = 0;
    private long ultTick = 0;
    private long saiuTs = 0;       // quando saiu por conta própria
    private long expulsoTs = 0;    // quando foi expulso pelo tempo (0 = não)
    private boolean saiuConsciente = false;  // saiu do app por conta própria antes do tempo acabar
    private long guardadoNaSaida = 0;         // quanto desta saída foi para o banco de tempo
    private long totalSessaoMs = 0;           // tempo total escolhido (mais bônus) nesta sessão

    // pílula no topo (últimos 2 min)
    private TextView pilula;
    private int totalDia = -1, metaDia = 0;
    private long ultLeituraMs = 0;
    private boolean lendo = false;

    private final BroadcastReceiver tela = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                if (emAlvo) saiu(false);
                emAlvo = false;
                removerOverlay();
                removerPilula();
            } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                try {
                    AccessibilityNodeInfo r = getRootInActiveWindow();
                    if (r != null && r.getPackageName() != null) primeiroPlano(r.getPackageName().toString());
                } catch (Throwable e) { }
            }
        }
    };

    public static boolean ativa(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(c.getPackageName() + "/" + Porta.class.getName());
        } catch (Throwable e) { return false; }
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try { registerReceiver(tela, f); } catch (Throwable e) { }
    }

    @Override public boolean onUnbind(Intent i) {
        try { unregisterReceiver(tela); } catch (Throwable e) { }
        h.removeCallbacksAndMessages(null);
        removerOverlay();
        removerPilula();
        return super.onUnbind(i);
    }

    @Override public void onInterrupt() { }

    @Override public void onAccessibilityEvent(AccessibilityEvent ev) {
        try {
            if (ev.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
            CharSequence p = ev.getPackageName();
            if (p == null) return;
            primeiroPlano(p.toString());
        } catch (Throwable e) { }
    }

    private boolean ignorar(String pkg) {
        return pkg.equals(getPackageName()) || pkg.equals("com.android.systemui")
                || pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg.contains("honeyboard");
    }

    private boolean alvo(String pkg) {
        for (String a : ALVOS) if (a.equals(pkg)) return true;
        return false;
    }

    private void primeiroPlano(String pkg) {
        if (ignorar(pkg)) return;
        if (!Cfg.portaLigada(this)) return;
        if (alvo(pkg)) {
            if (!emAlvo) { emAlvo = true; entrou(); }
        } else {
            if (emAlvo) { saiu(true); emAlvo = false; }
            removerOverlay();
        }
    }

    private void entrou() {
        long agora = SystemClock.elapsedRealtime();
        long graca = Cfg.gracaMs(this);
        if (restanteMs > 0 && agora - saiuTs <= graca) {
            // volta rápida: continua a sessão; o que tinha sido guardado volta para ela
            if (guardadoNaSaida > 0) {
                Cfg.setBancoMs(this, Cfg.bancoMs(this) - guardadoNaSaida);
                guardadoNaSaida = 0;
            }
            saiuConsciente = false;
            iniciarTick();
        } else {
            restanteMs = 0;
            saiuConsciente = false;
            guardadoNaSaida = 0;
            boolean espera = expulsoTs > 0 && agora - expulsoTs <= graca;
            mostrarEscolha(espera);
        }
    }

    private void saiu(boolean porConta) {
        h.removeCallbacks(tick);
        removerPilula();
        saiuTs = SystemClock.elapsedRealtime();
        saiuConsciente = false;
        guardadoNaSaida = 0;
        // saída consciente: trocou de app com tempo sobrando, depois de usar pelo menos 1 min
        if (porConta && restanteMs > 0 && totalSessaoMs - restanteMs >= 60000L) {
            saiuConsciente = true;
            // o que sobrou vai para o banco de tempo (soma com o que já tinha, até o teto)
            long teto = Cfg.bancoMaxMs(this), atual = Cfg.bancoMs(this);
            long guarda = Math.min(restanteMs, Math.max(0, teto - atual));
            if (guarda > 0) { Cfg.setBancoMs(this, atual + guarda); guardadoNaSaida = guarda; }
            try {
                SharedPreferences p = Sync.prefs(this);
                String dia = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                int n = p.getString("saiDia", "").equals(dia) ? p.getInt("saiN", 0) : 0;
                p.edit().putString("saiDia", dia).putInt("saiN", n + 1).apply();
            } catch (Throwable e) { }
        }
    }

    private void avisar(String msg) {
        try { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable e) { }
    }

    private String minTxt(long ms) {
        long s = ms / 1000, m = s / 60;
        return s % 60 == 0 || m >= 10 ? m + " min" : m + ":" + String.format(Locale.US, "%02d", s % 60) + " min";
    }

    // ---- relógio da sessão ----
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!emAlvo || overlay != null) return;
            long agora = SystemClock.elapsedRealtime();
            restanteMs -= (agora - ultTick);
            ultTick = agora;
            if (restanteMs <= 0) { expulsar(); return; }
            if (restanteMs <= Cfg.pilulaMs(Porta.this)) atualizarPilula();
            h.postDelayed(this, 1000);
        }
    };

    private void iniciarTick() {
        ultTick = SystemClock.elapsedRealtime();
        h.removeCallbacks(tick);
        h.postDelayed(tick, 1000);
    }

    private void expulsar() {
        restanteMs = 0;
        removerPilula();
        expulsoTs = SystemClock.elapsedRealtime();
        emAlvo = false;
        try {
            SharedPreferences p = Sync.prefs(this);
            String dia = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            int n = p.getString("expDia", "").equals(dia) ? p.getInt("expN", 0) : 0;
            p.edit().putString("expDia", dia).putInt("expN", n + 1).apply();
        } catch (Throwable e) { }
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    // ---- pílula do topo ----
    private void atualizarPilula() {
        try {
            long agora = SystemClock.elapsedRealtime();
            if (!lendo && (totalDia < 0 || agora - ultLeituraMs > 20000)) lerTotalDia();
            int seg = (int) Math.max(0, (restanteMs + 999) / 1000);
            String t = (seg / 60) + ":" + String.format(Locale.US, "%02d", seg % 60);
            if (totalDia >= 0) t += "  ·  " + Avisos.hm(totalDia) + (metaDia > 0 ? " / " + Avisos.hm(metaDia) : "");
            boolean vermelho = restanteMs <= 60000;
            if (pilula == null) {
                TextView v = new TextView(this);
                v.setTextSize(15);
                v.setTypeface(Typeface.DEFAULT_BOLD);
                v.setTextColor(0xFFFBF6EA);
                v.setGravity(Gravity.CENTER);
                v.setPadding(dp(16), dp(8), dp(16), dp(8));
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = alturaBarra() + dp(6);
                wm.addView(v, lp);
                pilula = v;
            }
            pilula.setBackground(forma(vermelho ? VERM : 0xEB2E1A0E, 20));
            pilula.setText(t);
        } catch (Throwable e) { }
    }

    private int alturaBarra() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Throwable e) { }
        return dp(24);
    }

    private void lerTotalDia() {
        lendo = true;
        new Thread(new Runnable() {
            @Override public void run() {
                int total = -1, meta = 0;
                try {
                    org.json.JSONArray a = new Coletor(Porta.this).coletar(1);
                    if (a.length() > 0) {
                        org.json.JSONArray apps = a.getJSONObject(a.length() - 1).optJSONArray("apps");
                        int[] ins = Sync.somar(apps, new String[]{"com.instagram.android", "com.instagram.lite"});
                        int[] tik = Sync.somar(apps, new String[]{"com.zhiliaoapp.musically", "com.ss.android.ugc.trill"});
                        total = ins[0] + tik[0];
                    }
                    String pl = Sync.prefs(Porta.this).getString("plano", "");
                    if (pl.length() > 0) meta = Avisos.metaDoDia(new JSONObject(pl));
                } catch (Throwable e) { }
                final int t = total, m = meta;
                h.post(new Runnable() {
                    @Override public void run() {
                        if (t >= 0) totalDia = t;
                        metaDia = m;
                        ultLeituraMs = SystemClock.elapsedRealtime();
                        lendo = false;
                    }
                });
            }
        }).start();
    }

    private void removerPilula() {
        if (pilula != null) {
            try { wm.removeView(pilula); } catch (Throwable e) { }
            pilula = null;
        }
        totalDia = -1;
    }

    // ---- tela de escolha ----
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable forma(int cor, int raio) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(cor); g.setCornerRadius(dp(raio));
        return g;
    }

    private TextView tv(String t, int sp, boolean neg, int cor) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextSize(sp); v.setTextColor(cor);
        v.setGravity(Gravity.CENTER);
        if (neg) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private String resumoHoje() {
        try {
            SharedPreferences p = Sync.prefs(this);
            if (p.getLong("resumoTs", 0) == 0) return "";
            int total = p.getInt("totalHoje", 0);
            int meta = 0;
            String pl = p.getString("plano", "");
            if (pl.length() > 0) meta = Avisos.metaDoDia(new JSONObject(pl));
            String s = "Hoje: " + Avisos.hm(total) + (meta > 0 ? " de " + Avisos.hm(meta) : "");
            s += "\nInstagram " + p.getInt("insAb", 0) + " aberturas · TikTok " + p.getInt("tikAb", 0);
            return s;
        } catch (Throwable e) { return ""; }
    }

    private void mostrarEscolha(final boolean espera) {
        removerOverlay();
        removerPilula();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(BG);
        col.setPadding(dp(28), dp(28), dp(28), dp(28));

        col.addView(tv(espera ? "Você acabou de ser tirado do app" : "Antes de entrar", 22, true, espera ? VERM : TXT));
        String r = resumoHoje();
        if (r.length() > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(14);
            col.addView(tv(r, 14, false, SUAVE), lp);
        }
        final long banco = Cfg.bancoMs(this);
        if (banco >= 1000) {
            LinearLayout.LayoutParams lg = new LinearLayout.LayoutParams(-1, -2);
            lg.topMargin = dp(18);
            TextView g = tv("Você guardou " + minTxt(banco) + " por sair antes do tempo", 15, true, 0xFF4E7D4A);
            col.addView(g, lg);
        }
        LinearLayout.LayoutParams lq = new LinearLayout.LayoutParams(-1, -2);
        lq.topMargin = dp(banco >= 1000 ? 14 : 28);
        col.addView(tv(banco >= 1000 ? "Quanto tempo você vai ficar? (soma o guardado)" : "Quanto tempo você vai ficar?", 16, true, TXT), lq);

        final TextView aviso = tv(espera ? "Aguarde " + Cfg.espera(this) + "s…" : "", 14, false, SUAVE);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(-1, -2);
        la.topMargin = dp(6);
        col.addView(aviso, la);

        LinearLayout linha = new LinearLayout(this);
        linha.setOrientation(LinearLayout.HORIZONTAL);
        linha.setGravity(Gravity.CENTER);
        final int[] opcoes = Cfg.opcoes(this);
        final Button[] bs = new Button[opcoes.length];
        for (int i = 0; i < opcoes.length; i++) {
            final int min = opcoes[i];
            Button b = new Button(this);
            b.setText(min + " min" + (banco >= 1000 ? "\n+ " + minTxt(banco) : ""));
            b.setAllCaps(false); b.setTextSize(16); b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setTextColor(Color.WHITE);
            b.setBackground(forma(VINHO, 12));
            b.setStateListAnimator(null);
            b.setEnabled(!espera);
            b.setAlpha(espera ? 0.35f : 1f);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { escolher(min); }
            });
            LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, dp(banco >= 1000 ? 66 : 52), 1f);
            lb.leftMargin = dp(5); lb.rightMargin = dp(5);
            linha.addView(b, lb);
            bs[i] = b;
        }
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = dp(14);
        col.addView(linha, ll);

        Button voltar = new Button(this);
        voltar.setText("Voltar");
        voltar.setAllCaps(false); voltar.setTextSize(15); voltar.setTypeface(Typeface.DEFAULT_BOLD);
        voltar.setTextColor(TXT);
        voltar.setBackground(forma(BTNB, 12));
        voltar.setStateListAnimator(null);
        voltar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { voltar(); }
        });
        LinearLayout.LayoutParams lv = new LinearLayout.LayoutParams(-1, dp(52));
        lv.topMargin = dp(22);
        col.addView(voltar, lv);

        col.setFocusableInTouchMode(true);
        col.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) {
                if (code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) { voltar(); }
                return code == KeyEvent.KEYCODE_BACK;
            }
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        try {
            wm.addView(col, lp);
            overlay = col;
            col.requestFocus();
        } catch (Throwable e) {
            overlay = null;
            // sem sobreposição possível: ao menos começa a contar com 6 min
            restanteMs = opcoes[Math.min(1, opcoes.length - 1)] * 60000L; totalSessaoMs = restanteMs; iniciarTick();
            return;
        }

        if (espera && Cfg.espera(this) <= 0) {
            for (Button b : bs) { b.setEnabled(true); b.setAlpha(1f); }
            aviso.setText("");
        } else if (espera) {
            final int[] resta = {Cfg.espera(this)};
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (overlay != col) return;
                    resta[0]--;
                    if (resta[0] > 0) {
                        aviso.setText("Aguarde " + resta[0] + "s…");
                        h.postDelayed(this, 1000);
                    } else {
                        aviso.setText("");
                        for (Button b : bs) { b.setEnabled(true); b.setAlpha(1f); }
                    }
                }
            }, 1000);
        }
    }

    private void escolher(int min) {
        removerOverlay();
        restanteMs = min * 60000L + Cfg.bancoMs(this);   // soma o tempo guardado
        Cfg.setBancoMs(this, 0);
        guardadoNaSaida = 0;
        totalSessaoMs = restanteMs;
        saiuConsciente = false;
        expulsoTs = 0;
        if (emAlvo) iniciarTick();
    }

    private void voltar() {
        removerOverlay();
        emAlvo = false;
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    private void removerOverlay() {
        if (overlay != null) {
            try { wm.removeView(overlay); } catch (Throwable e) { }
            overlay = null;
        }
    }
}
