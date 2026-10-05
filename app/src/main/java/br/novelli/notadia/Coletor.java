package br.novelli.notadia;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lê o registro de uso do Android (o mesmo que o Bem-estar Digital usa) e calcula,
 * por dia, o tempo de tela, o tempo por app e quantas vezes cada app foi aberto.
 * Fora o nome do app e os minutos, nada do conteúdo do celular é lido.
 */
public class Coletor {

    private final Context ctx;
    private final UsageStatsManager usm;
    private final PackageManager pm;
    private final String launcher;

    public Coletor(Context ctx) {
        this.ctx = ctx;
        this.usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        this.pm = ctx.getPackageManager();
        this.launcher = descobrirLauncher();
    }

    private String descobrirLauncher() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) return ri.activityInfo.packageName;
        } catch (Exception ignored) { }
        return "";
    }

    private boolean ignorar(String p) {
        return p == null
                || p.equals(launcher)
                || p.equals("com.android.systemui")
                || p.equals("android")
                || p.equals(ctx.getPackageName());
    }

    /** Últimos N dias (do mais antigo para o de hoje). */
    public JSONArray coletar(int dias) throws Exception {
        JSONArray out = new JSONArray();
        Calendar hoje = Calendar.getInstance();
        hoje.set(Calendar.HOUR_OF_DAY, 0);
        hoje.set(Calendar.MINUTE, 0);
        hoje.set(Calendar.SECOND, 0);
        hoje.set(Calendar.MILLISECOND, 0);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (int i = dias - 1; i >= 0; i--) {
            Calendar c = (Calendar) hoje.clone();
            c.add(Calendar.DAY_OF_MONTH, -i);
            Calendar n = (Calendar) c.clone();
            n.add(Calendar.DAY_OF_MONTH, 1);
            long ini = c.getTimeInMillis();
            long fim = Math.min(n.getTimeInMillis(), System.currentTimeMillis());
            if (fim <= ini) continue;
            out.put(dia(fmt.format(c.getTime()), ini, fim));
        }
        return out;
    }

    private void fechar(Map<String, Long> ms, String pkg, long ini, long fim) {
        long dur = fim - ini;
        if (dur <= 0 || dur > 12L * 3600_000L || ignorar(pkg)) return;
        Long atual = ms.get(pkg);
        ms.put(pkg, (atual == null ? 0L : atual) + dur);
    }

    private JSONObject dia(String data, long ini, long fim) throws Exception {
        final Map<String, Long> ms = new HashMap<>();
        Map<String, Integer> aberturas = new HashMap<>();

        UsageEvents ev = usm.queryEvents(ini, fim);
        UsageEvents.Event e = new UsageEvents.Event();
        String cur = null;
        long curIni = 0;
        String ultFechado = null;
        long ultFechadoTs = 0;

        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int t = e.getEventType();
            long ts = e.getTimeStamp();
            String p = e.getPackageName();

            if (t == 1) {                       // app veio para a frente
                if (cur != null && cur.equals(p)) continue;
                if (cur != null) fechar(ms, cur, curIni, ts);
                boolean continuacao = p.equals(ultFechado) && ts - ultFechadoTs < 2500;
                if (!continuacao && !ignorar(p)) {
                    Integer a = aberturas.get(p);
                    aberturas.put(p, (a == null ? 0 : a) + 1);
                }
                cur = p;
                curIni = ts;
            } else if (t == 2) {                // app saiu da frente
                if (cur != null && cur.equals(p)) {
                    fechar(ms, cur, curIni, ts);
                    ultFechado = cur;
                    ultFechadoTs = ts;
                    cur = null;
                }
            } else if (t == 16 || t == 17) {    // tela apagou / bloqueou
                if (cur != null) {
                    fechar(ms, cur, curIni, ts);
                    ultFechado = cur;
                    ultFechadoTs = ts;
                    cur = null;
                }
            }
        }
        if (cur != null) fechar(ms, cur, curIni, fim);

        long totalMs = 0;
        for (long v : ms.values()) totalMs += v;

        List<String> pacotes = new ArrayList<>(ms.keySet());
        for (String p : aberturas.keySet()) if (!ms.containsKey(p)) pacotes.add(p);
        Collections.sort(pacotes, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                Long x = ms.get(a), y = ms.get(b);
                return Long.compare(y == null ? 0 : y, x == null ? 0 : x);
            }
        });

        JSONArray apps = new JSONArray();
        int max = Math.min(15, pacotes.size());
        for (int i = 0; i < max; i++) {
            String p = pacotes.get(i);
            Long m = ms.get(p);
            Integer a = aberturas.get(p);
            JSONObject o = new JSONObject();
            o.put("pkg", p);
            o.put("name", rotulo(p));
            o.put("min", Math.round((m == null ? 0 : m) / 60000.0));
            o.put("opens", a == null ? 0 : a);
            apps.put(o);
        }

        JSONObject d = new JSONObject();
        d.put("date", data);
        d.put("total", Math.round(totalMs / 60000.0));
        d.put("apps", apps);
        return d;
    }

    private String rotulo(String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
