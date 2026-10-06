package br.novelli.notadia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Avisa (no máximo 2 vezes por dia) quando o tempo de tela chega a 80% e a 100% da meta do dia. */
public class Avisos {

    private static final String CANAL = "avisos";

    /** Meta do dia em minutos, a partir do plano que a Nota do computador mandou; 0 se não houver. */
    static int metaDoDia(JSONObject plano) {
        if (plano == null) return 0;
        int fin = plano.optInt("final", 0);
        int base = plano.optInt("base", 0);
        int passo = plano.optInt("passo", 0);
        if (fin <= 0) return 0;
        if (base <= 0) return fin;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date ini = f.parse(plano.optString("inicio", ""));
            Calendar a = Calendar.getInstance(); a.setTime(ini);
            Calendar b = Calendar.getInstance();
            a.set(Calendar.HOUR_OF_DAY, 0); a.set(Calendar.MINUTE, 0); a.set(Calendar.SECOND, 0); a.set(Calendar.MILLISECOND, 0);
            b.set(Calendar.HOUR_OF_DAY, 0); b.set(Calendar.MINUTE, 0); b.set(Calendar.SECOND, 0); b.set(Calendar.MILLISECOND, 0);
            long dias = Math.max(0, Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86400000.0));
            return (int) Math.max(fin, base - passo * dias);
        } catch (Exception e) {
            return fin;
        }
    }

    static String hm(int min) {
        if (min < 60) return min + " min";
        int m = min % 60;
        return (min / 60) + "h" + (m > 0 ? String.format(Locale.US, "%02d", m) : "");
    }

    public static void checar(Context c, int totalHoje, JSONObject plano) {
        try {
            int meta = metaDoDia(plano);
            if (meta <= 0) return;
            String hoje = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            SharedPreferences p = Sync.prefs(c);
            if (!hoje.equals(p.getString("avisoDia", ""))) {
                p.edit().putString("avisoDia", hoje).putBoolean("aviso80", false).putBoolean("aviso100", false).apply();
            }
            if (totalHoje >= meta && !p.getBoolean("aviso100", false)) {
                mostrar(c, 2, "Meta de tela de hoje atingida",
                        "Você chegou a " + hm(totalHoje) + " (meta " + hm(meta) + "). Ligue o filtro e deixe o celular de lado.");
                p.edit().putBoolean("aviso100", true).putBoolean("aviso80", true).apply();
            } else if (totalHoje >= meta * 0.8 && !p.getBoolean("aviso80", false)) {
                mostrar(c, 1, "Você chegou a 80% da meta de tela",
                        hm(totalHoje) + " de " + hm(meta) + " hoje. Ligue o filtro de escala de cinza.");
                p.edit().putBoolean("aviso80", true).apply();
            }
        } catch (Throwable e) { }
    }

    private static void mostrar(Context c, int id, String titulo, String texto) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CANAL, "Avisos de meta", NotificationManager.IMPORTANCE_HIGH));
        Notification n = new Notification.Builder(c, CANAL)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(new Notification.BigTextStyle().bigText(texto))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build();
        nm.notify(id, n);
    }
}
