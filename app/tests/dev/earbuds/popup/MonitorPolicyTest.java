package dev.earbuds.popup;
public final class MonitorPolicyTest {
  static void check(boolean x,String m){if(!x)throw new AssertionError(m);}
  public static void main(String[] args){
    check(!MonitorPolicy.enabledAfter(false,MonitorPolicy.PREVIEW),"Preview must not activate a disabled monitor");
    check(MonitorPolicy.enabledAfter(true,MonitorPolicy.PREVIEW),"Preview must preserve an enabled monitor");
    check(MonitorPolicy.enabledAfter(false,MonitorPolicy.ENABLE),"Explicit enable must activate monitoring");
    check(!MonitorPolicy.enabledAfter(true,MonitorPolicy.STOP),"Stop must persist disabled");
    check(!MonitorPolicy.enabledAfter(false,MonitorPolicy.RESTORE),"Recovery must respect a deliberate Stop");
    check(MonitorPolicy.enabledAfter(true,MonitorPolicy.RESTORE),"Recovery must retain enabled state");
    check(!MonitorPolicy.enabledAfter(false,MonitorPolicy.REFRESH),"Battery refresh must not activate monitoring");
    System.out.println("PASS: monitor preview, stop, restore and refresh state transitions");
  }
}
