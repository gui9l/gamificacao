package br.novelli.notadia;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

/**
 * "Cards de hoje: N · revisar" na notificação da Porta. O N vem do PC (Jarvis lê o Anki) dentro do plano da planilha;
 * o toque abre o AnkiDroid já estudando o baralho "Aulas" (o mesmo extra que o lembrete do próprio AnkiDroid usa).
 */
final class Cards {
    static final String ANKI = "com.ichi2.anki";
    static final long BARALHO_AULAS = 1795177748320L;

    private Cards() { }

    /** Cards para revisar hoje (vem do PC); -1 se o plano não traz ou é de outro dia. */
    static int pendentes(Context c) {
        JSONObject p = Cfg.plano(c);
        if (!p.has("cards")) return -1;
        String hoje = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        if (!hoje.equals(p.optString("data"))) return -1;   // plano de outro dia: o número já não vale
        return p.optInt("cards", 0);
    }

    /** Linha da notificação; vazia se o PC não mandou os cards de hoje. */
    static String linha(Context c) {
        int n = pendentes(c);
        if (n < 0) return "";
        return n > 0 ? "Cards de hoje: " + n + " · revisar" : "Cards de hoje: em dia ✓";
    }

    static Intent intent() {
        return new Intent().setComponent(new ComponentName(ANKI, ANKI + ".IntentHandler"))
                .putExtra("EXTRA_DECK_ID", BARALHO_AULAS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    static PendingIntent abrir(Context c) {
        Intent i = intent();
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(c, 80, i, fl);
    }
}
