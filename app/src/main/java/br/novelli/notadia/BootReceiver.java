package br.novelli.notadia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Religa o envio automático depois que o celular reinicia. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SyncJob.agendar(context);
        try {
            if (Sync.prefs(context).getString("url", "").length() > 0) SyncService.iniciar(context);
        } catch (Throwable e) { }
    }
}
