package br.novelli.notadia;

import android.content.Context;
import android.content.SharedPreferences;

/** Configurações editáveis da Porta (guardadas no próprio app). */
final class Cfg {
    static final String OPCOES = "cfgOpcoes", GRACA = "cfgGraca", ESPERA = "cfgEspera",
            PILULA = "cfgPilula", BONUS = "cfgBonus", PORTA = "porta";

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
    static int bonus(Context c) { return p(c).getInt(BONUS, 1); }          // minutos (0 = sem bônus)
    static boolean portaLigada(Context c) { return p(c).getBoolean(PORTA, true); }

    static long gracaMs(Context c) { return graca(c) * 60000L; }
    static long pilulaMs(Context c) { return pilula(c) * 1000L; }
    static long bonusMs(Context c) { return bonus(c) * 60000L; }
}
