package br.novelli.notadia;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Porta" do Instagram/TikTok: ao entrar, você escolhe 4, 6 ou 8 min; ao acabar o tempo o app
 * te manda para a tela inicial. Não usa Acessibilidade (os apps de banco recusam abrir com ela ligada):
 * vê o app da frente pelo acesso ao uso e desenha por cima com a permissão "sobrepor a outros apps".
 * Volta em até 3 min depois de sair por conta própria continua a sessão de onde parou.
 * Depois de ser expulso, reabrir exige uma espera CRESCENTE (4 s, 5 s, 6 s… a cada reentrada seguida). Ficar fora
 * do app mais que a janela de volta ao começo (5 min, vem do PC) zera a escada. Ligar/desligar a Porta e os valores
 * da espera só pelo computador (Jarvis), pelo "plano" da planilha.
 */
public class Porta extends Service {

    static final String[] ALVOS = {"com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill"};

    private static final int BG = 0xFFFBF6EA, BTNB = 0xFFD9C8A3, TXT = 0xFF2E1A0E, SUAVE = 0xFF8B6F55,
            VINHO = 0xFF8B1A4A, VERM = 0xFFB4423A, BORDA = 0xFFE3D6B8;

    private static final String CANAL = "porta";
    private static final int NOTIF_ID = 7;

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
    private long ultSaidaTs = 0;              // última vez que saiu do app (por conta, expulso ou tela apagada)
    private int degraus = 0;                  // reentradas seguidas depois de expulso (sobe a espera)
    private boolean escada = false;           // já foi expulso nesta sequência
    private int esperaAtual = 0;              // segundos que esta tela de escolha está exigindo

    // pílula no topo (últimos 2 min)
    private TextView pilula;
    private int totalDia = -1, metaDia = 0;
    private long ultLeituraMs = 0;
    private boolean lendo = false;

    private UsageStatsManager usm;
    private String fgAtual = "";       // app da frente (último ACTIVITY_RESUMED)
    private long ultConsulta = 0;
    private long ignorarAte = 0;        // logo depois de mandar para a tela inicial
    private boolean telaLigada = true;

    private final BroadcastReceiver tela = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                telaLigada = false;
                h.removeCallbacks(sonda);
                if (emAlvo) saiu(false);
                emAlvo = false;
                removerOverlay();
                removerPilula();
            } else if (Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_USER_PRESENT.equals(a)) {
                telaLigada = true;
                h.removeCallbacks(sonda);
                h.post(sonda);
            }
        }
    };

    /** Liga a Porta se estiver ligada nos ajustes e com as duas permissões; desliga se não. */
    public static void aplicarConfig(Context c) {
        try {
            // O serviço fica sempre de pé; com a Porta desligada (pelo PC) ele só não faz nada. Assim, quando o PC religa,
            // não é preciso iniciar serviço em segundo plano (o Android 12+ bloqueia).
            if (pronta(c)) c.startForegroundService(new Intent(c, Porta.class));
        } catch (Throwable e) { }
    }

    public static void iniciar(Context c) { aplicarConfig(c); }

    /** Permissões: sobrepor a outros apps + acesso ao uso. */
    public static boolean ativa(Context c) { return pronta(c); }

    private static boolean pronta(Context c) {
        try { return Settings.canDrawOverlays(c) && Sync.temAcessoAoUso(c); } catch (Throwable e) { return false; }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        try { telaLigada = ((PowerManager) getSystemService(Context.POWER_SERVICE)).isInteractive(); } catch (Throwable e) { }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try { registerReceiver(tela, f); } catch (Throwable e) { }
    }

    /** Redesenha a notificação (a resposta do Jarvis pode chegar de outra thread). */
    private final Runnable redesenhar = new Runnable() {
        @Override public void run() {
            h.post(new Runnable() { @Override public void run() { emPrimeiroPlano(); } });
            h.removeCallbacks(voltarPadrao);
            h.postDelayed(voltarPadrao, Jarvis.MOSTRA_MS + 2000);
        }
    };
    private final Runnable voltarPadrao = new Runnable() { @Override public void run() { emPrimeiroPlano(); } };

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && Jarvis.ACAO.equals(i.getAction())) {
            String t = Jarvis.textoDe(i);
            if (!t.isEmpty()) Jarvis.enviar(this, t, redesenhar);
            else emPrimeiroPlano();
            return START_STICKY;
        }
        emPrimeiroPlano();
        h.removeCallbacks(sonda);
        h.post(sonda);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(tela); } catch (Throwable e) { }
        h.removeCallbacksAndMessages(null);
        removerOverlay();
        removerPilula();
        super.onDestroy();
    }

    /** Notificação mínima (sem som nem ícone na barra) que mantém o serviço vivo. */
    private void emPrimeiroPlano() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CANAL, "Porta", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            String jv = Jarvis.linha(this);
            Notification.Builder b = new Notification.Builder(this, CANAL)
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle(jv.isEmpty() ? "Nota do dia · Porta" : "Jarvis")
                    .setContentText(jv.isEmpty() ? "Vigiando Instagram e TikTok" : jv)
                    .setOngoing(true);
            if (!jv.isEmpty()) b.setStyle(new Notification.BigTextStyle().bigText(jv));
            Jarvis.acoes(this, b);
            Notification n = b.build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIF_ID, n);
        } catch (Throwable e) { }
    }

    // ---- quem está na frente (acesso ao uso, sem Acessibilidade) ----
    private final Runnable sonda = new Runnable() {
        @Override public void run() {
            if (!telaLigada) return;
            try { lerPrimeiroPlano(); } catch (Throwable e) { }
            h.postDelayed(this, 700);
        }
    };

    private void lerPrimeiroPlano() {
        long agora = System.currentTimeMillis();
        long ini = ultConsulta == 0 ? agora - 30 * 60000L : ultConsulta - 1000;
        UsageEvents ev = usm.queryEvents(ini, agora);
        UsageEvents.Event e = new UsageEvents.Event();
        String novo = null;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED && e.getPackageName() != null) novo = e.getPackageName();
        }
        ultConsulta = agora;
        if (novo != null) fgAtual = novo;
        if (fgAtual.length() == 0 || SystemClock.elapsedRealtime() < ignorarAte) return;
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) return;   // tela bloqueada: espera destravar
        } catch (Throwable x) { }
        primeiroPlano(fgAtual);
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
        if (!Cfg.portaLigada(this) || !Settings.canDrawOverlays(this)) return;
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
            if (ultSaidaTs > 0 && agora - ultSaidaTs > Cfg.esperaResetMs(this)) { escada = false; degraus = 0; }  // ficou fora: volta ao começo
            esperaAtual = escada ? Cfg.esperaDa(this, degraus) : 0;
            mostrarEscolha(escada && esperaAtual > 0);
        }
        sincronizarSePreciso();
    }

    /** Ao abrir Instagram/TikTok, traz a ordem mais nova do PC (liga/desliga e espera), se faz mais de 5 min. */
    private void sincronizarSePreciso() {
        final Context c = getApplicationContext();
        if (System.currentTimeMillis() - Sync.prefs(c).getLong("ultimoTs", 0) < 5 * 60000L) return;
        new Thread(new Runnable() { @Override public void run() { try { Sync.enviar(c); } catch (Throwable e) { } } }).start();
    }

    private void saiu(boolean porConta) {
        h.removeCallbacks(tick);
        removerPilula();
        saiuTs = SystemClock.elapsedRealtime();
        ultSaidaTs = saiuTs;
        saiuConsciente = false;
        guardadoNaSaida = 0;
        // saída consciente: trocou de app com tempo sobrando, depois de usar pelo menos 1 min
        if (porConta && restanteMs > 0 && totalSessaoMs - restanteMs >= 60000L) {
            saiuConsciente = true;
            // o que sobrou vai para o banco de tempo (soma com o que já tinha, até o teto)
            long teto = Cfg.bancoMaxMs(this), atual = Cfg.bancoMs(this);
            long guarda = Math.min(restanteMs, Math.max(0, teto - atual));
            if (guarda > 0) { Cfg.setBancoMs(this, atual + guarda); guardadoNaSaida = guarda; }
            try { Cfg.somarDia(this, "sai"); } catch (Throwable e) { }
        }
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
        expulsoTs = SystemClock.elapsedRealtime();
        ultSaidaTs = expulsoTs;
        escada = true;   // a partir daqui, cada reentrada logo em seguida pede uma espera maior
        ignorarAte = expulsoTs + 1500;
        emAlvo = false;
        try { Cfg.somarDia(this, "exp"); } catch (Throwable e) { }
        irParaInicio(true);
        removerPilula();
    }

    /** Manda para a tela inicial. Abrir tela em segundo plano só é permitido com uma janela nossa visível. */
    private void irParaInicio(boolean ancora) {
        View a = null;
        if (ancora && pilula == null && overlay == null) {
            try {
                a = new View(this);
                wm.addView(a, new WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT));
            } catch (Throwable e) { a = null; }
        }
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable e) { }
        final View f = a;
        if (f != null) h.postDelayed(new Runnable() {
            @Override public void run() { try { wm.removeView(f); } catch (Throwable e) { } }
        }, 1500);
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
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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

        final TextView aviso = tv(espera ? "Aguarde " + esperaAtual + "s…" + (degraus > 0 ? "  (" + (degraus + 1) + "ª vez seguida)" : "") : "", 14, false, SUAVE);
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
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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

        if (espera && esperaAtual <= 0) {
            for (Button b : bs) { b.setEnabled(true); b.setAlpha(1f); }
            aviso.setText("");
        } else if (espera) {
            final int[] resta = {esperaAtual};
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (overlay != col) return;
                    resta[0]--;
                    if (resta[0] > 0) {
                        aviso.setText("Aguarde " + resta[0] + "s…" + (degraus > 0 ? "  (" + (degraus + 1) + "ª vez seguida)" : ""));
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
        if (escada && esperaAtual > 0) {   // esperou a trava e entrou mesmo assim
            try { Cfg.somarDia(this, "ree"); Cfg.maxDia(this, "espMax", esperaAtual); } catch (Throwable e) { }
            degraus++;
        }
        esperaAtual = 0;
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
        ultSaidaTs = SystemClock.elapsedRealtime();
        ignorarAte = SystemClock.elapsedRealtime() + 1500;
        emAlvo = false;
        irParaInicio(false);
        removerOverlay();
    }

    private void removerOverlay() {
        if (overlay != null) {
            try { wm.removeView(overlay); } catch (Throwable e) { }
            overlay = null;
        }
    }
}
