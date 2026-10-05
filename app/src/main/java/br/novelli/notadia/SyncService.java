package br.novelli.notadia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Mantém o envio em dia: manda o uso a cada 5 minutos e também quando a tela
 * apaga ou é desbloqueada (fim e começo de cada sessão de uso).
 */
public class SyncService extends Service {

    private static final String CANAL = "sync";
    private static final long INTERVALO = 5 * 60 * 1000L;
    private static final long MIN_ENTRE_ENVIOS = 45 * 1000L;

    private final Object trava = new Object();
    private volatile boolean rodando;
    private boolean pedido;
    private Thread fio;
    private BroadcastReceiver rec;

    public static void iniciar(Context c) {
        Intent i = new Intent(c, SyncService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CANAL, "Sincronização", NotificationManager.IMPORTANCE_MIN));
            }
            Notification n = new Notification.Builder(this, CANAL)
                    .setContentTitle("Nota do dia")
                    .setContentText("Sincronizando o tempo de tela")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, n);
            }
        } catch (Throwable e) {
            Sync.prefs(this).edit().putString("erro", "servico: " + e).apply();
            stopSelf();
            return;
        }

        rec = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { disparar(); }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(rec, f);

        rodando = true;
        fio = new Thread(new Runnable() {
            @Override public void run() { laco(); }
        });
        fio.start();
    }

    private void disparar() {
        synchronized (trava) { pedido = true; trava.notifyAll(); }
    }

    private void laco() {
        long ultimo = 0;
        while (rodando) {
            try {
                long agora = System.currentTimeMillis();
                if (agora - ultimo >= MIN_ENTRE_ENVIOS) {
                    Sync.enviar(getApplicationContext());
                    ultimo = System.currentTimeMillis();
                }
                synchronized (trava) {
                    if (!pedido) trava.wait(INTERVALO);
                    pedido = false;
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                try { Thread.sleep(60000); } catch (InterruptedException x) { return; }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        disparar();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        rodando = false;
        try { if (rec != null) unregisterReceiver(rec); } catch (Throwable e) { }
        synchronized (trava) { trava.notifyAll(); }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
