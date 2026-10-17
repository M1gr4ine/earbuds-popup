package dev.earbuds.popup;
/** Monitoring preference transitions. Recovery and previews never opt the user in. */
public final class MonitorPolicy {
  public static final int ENABLE=1, PREVIEW=2, STOP=3, RESTORE=4, REFRESH=5;
  private MonitorPolicy(){}
  public static boolean enabledAfter(boolean previous,int command){
    if(command==ENABLE)return true;
    if(command==STOP)return false;
    return previous;
  }
}
