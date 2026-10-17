package dev.earbuds.popup;
import android.companion.*;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
/** Only the system can bind this service; no callback launches an Activity. */
public final class EarbudCompanionService extends CompanionDeviceService {
  private boolean enabled(){return getSharedPreferences("popup",MODE_PRIVATE).getBoolean("enabled",false);}
  @Override public void onDevicePresenceEvent(DevicePresenceEvent event){
    if(Build.VERSION.SDK_INT<36||!enabled())return;
    DeviceStore.DeviceRecord record=DeviceStore.findByAssociation(this,event.getAssociationId());
    if(record==null){Log.i("EarbudsPopup","CDM_UNKNOWN_ASSOCIATION id="+event.getAssociationId());return;}
    switch(event.getEvent()){
      case DevicePresenceEvent.EVENT_BT_CONNECTED:dispatch(PopupService.CONNECTED,record);break;
      case DevicePresenceEvent.EVENT_BT_DISCONNECTED:dispatch(PopupService.DISCONNECTED,record);break;
      case DevicePresenceEvent.EVENT_BLE_APPEARED:dispatch(PopupService.RESTORE,record);break;
      default:break;
    }
  }
  @Override public void onDeviceAppeared(AssociationInfo info){
    if(!enabled()||info==null)return;
    DeviceStore.DeviceRecord r=DeviceStore.findByAssociation(this,info.getId());
    if(r==null&&info.getDeviceMacAddress()!=null)r=DeviceStore.findByAddress(this,info.getDeviceMacAddress().toString());
    if(r!=null)dispatch(PopupService.RESTORE,r);
  }
  @Override public void onDeviceAppeared(String address){
    if(!enabled())return;
    DeviceStore.DeviceRecord r=DeviceStore.findByAddress(this,address);
    if(r!=null)dispatch(PopupService.RESTORE,r);
  }
  @Override public void onDeviceDisappeared(AssociationInfo info){}
  @Override public void onDeviceDisappeared(String address){}
  private void dispatch(String action,DeviceStore.DeviceRecord r){
    if(!enabled())return;
    try{startForegroundService(new Intent(this,PopupService.class).setAction(action).putExtra("address",r.address));
      Log.i("EarbudsPopup","CDM_DISPATCH action="+action+" id="+r.associationId);
    }catch(RuntimeException e){Log.e("EarbudsPopup","CDM_DISPATCH_FAILED",e);}
  }
}
