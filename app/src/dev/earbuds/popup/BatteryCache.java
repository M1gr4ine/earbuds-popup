package dev.earbuds.popup;
import android.content.Context;
import android.content.SharedPreferences;
/** Last observed values only; timestamps let the renderer label stale samples honestly. */
public final class BatteryCache {
  private BatteryCache(){}
  public static BatterySnapshot load(Context c,String address){
    if(address==null||address.isEmpty())return BatterySnapshot.unknown();
    SharedPreferences p=c.getSharedPreferences("battery",Context.MODE_PRIVATE);
    String k=address.toUpperCase(java.util.Locale.ROOT)+":";
    long time=p.getLong(k+"time",0);
    if(time==0)return BatterySnapshot.unknown();
    return new BatterySnapshot(p.getInt(k+"left",-1),p.getInt(k+"right",-1),p.getInt(k+"case",-1),p.getInt(k+"overall",-1),
       p.getInt(k+"lc",-1),p.getInt(k+"rc",-1),p.getInt(k+"cc",-1),time);
  }
  public static void save(Context c,String address,BatterySnapshot s){
    if(address==null||s==null||!s.hasAny())return;
    String k=address.toUpperCase(java.util.Locale.ROOT)+":";
    c.getSharedPreferences("battery",Context.MODE_PRIVATE).edit()
      .putInt(k+"left",s.left).putInt(k+"right",s.right).putInt(k+"case",s.caseLevel).putInt(k+"overall",s.overall)
      .putInt(k+"lc",s.leftCharging).putInt(k+"rc",s.rightCharging).putInt(k+"cc",s.caseCharging)
      .putLong(k+"time",s.updatedAtMillis).apply();
  }
}
