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
    static final long GRACA_MS = 3 * 60 * 1000L;
    static final int ESPERA_S = 5;
    static final int[] OPCOES_MIN = {4, 6, 8};

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
    private boolean avisou1min = false;

    // pílula no topo (últimos 2 min)
    private TextView pilula;
    private int totalDia = -1, metaDia = 0;
    private long ultLeituraMs = 0;
    private boolean lendo = false;

    private final BroadcastReceiver tela = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                if (emAlvo) saiu();
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
        if (!Sync.prefs(this).getBoolean("porta", true)) return;
        if (alvo(pkg)) {
            if (!emAlvo) { emAlvo = true; entrou(); }
        } else {
            if (emAlvo) { saiu(); emAlvo = false; }
            removerOverlay();
        }
    }

    private void entrou() {
        long agora = SystemClock.elapsedRealtime();
        if (restanteMs > 0 && agora - saiuTs <= GRACA_MS) {
            iniciarTick();              // volta rápida: continua a sessão
        } else {
            restanteMs = 0;
            boolean espera = expulsoTs > 0 && agora - expulsoTs <= GRACA_MS;
            mostrarEscolha(espera);
        }
    }

    private void saiu() {
        h.removeCallbacks(tick);
        removerPilula();
        saiuTs = SystemClock.elapsedRealtime();
    }

    // ---- relógio da sessão ----
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!emAlvo || overlay != null) return;
            long agora = SystemClock.elapsedRealtime();
            restanteMs -= (agora - ultTick);
            ultTick = agora;
            if (restanteMs <= 0) { expulsar(); return; }
            if (restanteMs <= 120000) atualizarPilula();
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
            if (totalDia >= 0) t += "  ·  hoje " + Avisos.hm(totalDia) + (metaDia > 0 ? " / " + Avisos.hm(metaDia) : "");
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
                    if (a.length() > 0) total = a.getJSONObject(a.length() - 1).optInt("total", -1);
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
        LinearLayout.LayoutParams lq = new LinearLayout.LayoutParams(-1, -2);
        lq.topMargin = dp(28);
        col.addView(tv("Quanto tempo você vai ficar?", 16, true, TXT), lq);

        final TextView aviso = tv(espera ? "Aguarde " + ESPERA_S + "s…" : "", 14, false, SUAVE);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(-1, -2);
        la.topMargin = dp(6);
        col.addView(aviso, la);

        LinearLayout linha = new LinearLayout(this);
        linha.setOrientation(LinearLayout.HORIZONTAL);
        linha.setGravity(Gravity.CENTER);
        final Button[] bs = new Button[OPCOES_MIN.length];
        for (int i = 0; i < OPCOES_MIN.length; i++) {
            final int min = OPCOES_MIN[i];
            Button b = new Button(this);
            b.setText(min + " min");
            b.setAllCaps(false); b.setTextSize(16); b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setTextColor(Color.WHITE);
            b.setBackground(forma(VINHO, 12));
            b.setStateListAnimator(null);
            b.setEnabled(!espera);
            b.setAlpha(espera ? 0.35f : 1f);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { escolher(min); }
            });
            LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, dp(52), 1f);
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
            restanteMs = 6 * 60000L; avisou1min = false; iniciarTick();
            return;
        }

        if (espera) {
            final int[] resta = {ESPERA_S};
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
        restanteMs = min * 60000L;
        expulsoTs = 0;
        avisou1min = false;
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
