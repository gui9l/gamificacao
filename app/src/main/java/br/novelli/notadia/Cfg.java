package br.novelli.notadia;

import android.content.Context;
import android.content.SharedPreferences;

/** Configurações editáveis da Porta (guardadas no próprio app). */
final class Cfg {
    static final String OPCOES = "cfgOpcoes", GRACA = "cfgGraca", ESPERA = "cfgEspera",
            PILULA = "cfgPilula", BANCO = "cfgBanco", PORTA = "porta";

    private Cfg() { }

    private static SharedPreferences p(Context c) { return Sync.prefs(c); }

    /** Tempos oferecidos ao abrir (minutos). 1 a 4 valores entre 1 e 120. */
    static int[] opcoes(Context c) {
        int[] r = parseOpcoes(p(c).getString(OPCOES, "4,6,8"));
        return r != null ? r : new int[]{4, 6, 8};
    }

    static String opcoesTexto(Context c) {
        int[] o = opcoes(c);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < o.length; i++) { if (i > 0) s.append(", "); s.append(o[i]); }
        return s.toString();
    }

    static int[] parseOpcoes(String t) {
        if (t == null) return null;
        String[] partes = t.split("[,; ]+");
        int[] tmp = new int[partes.length];
        int n = 0;
        for (String s : partes) {
            if (s.length() == 0) continue;
            try {
                int v = Integer.parseInt(s.trim());
                if (v < 1 || v > 120 || n >= 4) return null;
                tmp[n++] = v;
            } catch (Throwable e) { return null; }
        }
        if (n == 0) return null;
        int[] r = new int[n];
        System.arraycopy(tmp, 0, r, 0, n);
        java.util.Arrays.sort(r);
        return r;
    }

    static int graca(Context c) { return p(c).getInt(GRACA, 3); }          // minutos
    static int espera(Context c) { return p(c).getInt(ESPERA, 5); }        // segundos
    static int pilula(Context c) { return p(c).getInt(PILULA, 60); }       // segundos finais com a faixa
    static int bancoMax(Context c) { return p(c).getInt(BANCO, 10); }     // minutos guardáveis (0 = não guarda)
    /** Ligada/desligada SÓ pelo computador (Jarvis): vem no "plano" da planilha. Sem a chave, fica ligada. */
    static boolean portaLigada(Context c) { return plano(c).optBoolean("porta", true); }

    /** Espera crescente após ser expulso: base + passo × reentradas seguidas (até 60 s). Vem do PC; padrão 4 s, +1 s. */
    static int esperaBase(Context c) { return Math.max(0, plano(c).optInt("espBase", 4)); }
    static int esperaPasso(Context c) { return Math.max(0, plano(c).optInt("espPasso", 1)); }
    /** Minutos fora do app para a espera voltar ao começo. Vem do PC; padrão 5. */
    static long esperaResetMs(Context c) { return Math.max(1, plano(c).optInt("espReset", 5)) * 60000L; }

    static int esperaDa(Context c, int reentradas) { return Math.min(60, esperaBase(c) + esperaPasso(c) * Math.max(0, reentradas)); }

    static org.json.JSONObject plano(Context c) {
        try {
            String s = p(c).getString("plano", "");
            return s.length() > 0 ? new org.json.JSONObject(s) : new org.json.JSONObject();
        } catch (Throwable e) { return new org.json.JSONObject(); }
    }

    /** Contadores do dia (zeram na virada): expulsões, saídas por conta e reentradas que esperaram a trava. */
    static int contDia(Context c, String nome) {
        SharedPreferences sp = p(c);
        return sp.getString(nome + "Dia", "").equals(hoje()) ? sp.getInt(nome + "N", 0) : 0;
    }

    static void somarDia(Context c, String nome) {
        p(c).edit().putString(nome + "Dia", hoje()).putInt(nome + "N", contDia(c, nome) + 1).apply();
    }

    static void maxDia(Context c, String nome, int v) {
        if (v > contDia(c, nome)) p(c).edit().putString(nome + "Dia", hoje()).putInt(nome + "N", v).apply();
    }

    static long gracaMs(Context c) { return graca(c) * 60000L; }
    static long pilulaMs(Context c) { return pilula(c) * 1000L; }
    static long bancoMaxMs(Context c) { return bancoMax(c) * 60000L; }

    private static String hoje() { return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date()); }

    /** Tempo guardado por sair antes do fim (zera na virada do dia). */
    static long bancoMs(Context c) {
        SharedPreferences sp = p(c);
        return sp.getString("bancoDia", "").equals(hoje()) ? sp.getLong("bancoMs", 0) : 0;
    }

    static void setBancoMs(Context c, long ms) {
        p(c).edit().putString("bancoDia", hoje()).putLong("bancoMs", Math.max(0, ms)).apply();
    }
}
