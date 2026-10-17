package dev.earbuds.popup;
import android.content.*;
import android.util.Log;
public final class BootReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context context,Intent intent){
    if(context.getSharedPreferences("popup",Context.MODE_PRIVATE).getBoolean("enabled",false)){
      CompanionLink.resumeAllObservation(context);
      try{context.startForegroundService(new Intent(context,PopupService.class).setAction(PopupService.RESTORE));}
      catch(RuntimeException e){Log.w("EarbudsPopup","BOOT_RESTORE_DEFERRED",e);}
    }
  }
}
