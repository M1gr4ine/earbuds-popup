package dev.earbuds.popup;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import android.view.*;
import java.util.*;

/** CDM wakes this service; the automatic display path never launches an Activity. */
public final class PopupService extends Service {
    static final String TAG="EarbudsPopup";
    public static final String START="dev.earbuds.popup.START", PREVIEW="dev.earbuds.popup.PREVIEW",
        STOP="dev.earbuds.popup.STOP", RESTORE="dev.earbuds.popup.RESTORE", REFRESH="dev.earbuds.popup.REFRESH";
    static final String CONNECTED="dev.earbuds.popup.COMPANION_CONNECTED", DISCONNECTED="dev.earbuds.popup.COMPANION_DISCONNECTED";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,BatteryClient.Query> queries=new HashMap<>();
    private final Map<String,Long> attempts=new HashMap<>();
    private ConnectionGate gate=new ConnectionGate();
    private WindowManager windows;
    private Context windowContext;
    private PopupCardView card;
    private DeviceStore.DeviceRecord shownDevice;
    private BatteryClient.Query statusObserver;
    private String observedAddress;
    private int statusGeneration,statusRetries;
    private Runnable statusRetry;
    private BluetoothAdapter adapter;
    private boolean registered,destroyed,replacing;
    private Runnable oneShotTimeout;

    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(!enabled())return;
            String action=intent.getAction();
            if(Intent.ACTION_USER_PRESENT.equals(action)){checkConnections("UNLOCK",null,false);return;}
            if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)){
                int state=intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1);
                if(state==BluetoothAdapter.STATE_OFF){gate=new ConnectionGate();cancelAll();attempts.clear();hide(false);}
                else if(state==BluetoothAdapter.STATE_ON){attempts.clear();CompanionLink.resumeAllObservation(PopupService.this);checkConnections("BLUETOOTH_ON",null,false);}
                return;
            }
            BluetoothDevice d=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if(d==null)return;
            String address;
            try{address=d.getAddress();}catch(SecurityException e){return;}
            if(DeviceStore.findByAddress(PopupService.this,address)==null)return;
            if(BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action) ||
              (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action) &&
                intent.getIntExtra(BluetoothProfile.EXTRA_STATE,-1)==BluetoothProfile.STATE_DISCONNECTED)){
                disconnected(address);return;
            }
            if(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action) &&
              intent.getIntExtra(BluetoothProfile.EXTRA_STATE,-1)==BluetoothProfile.STATE_CONNECTED){
                audioConnected(address,"A2DP",false);
            }else if(BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)){
                // A control socket can create ACL without audio: confirm A2DP before showing.
                checkConnections("ACL_AUDIO_CONFIRMED",address,false);
            }
        }
    };
    @Override public void onCreate(){
        super.onCreate();
        BluetoothManager bm=getSystemService(BluetoothManager.class);
        adapter=bm==null?null:bm.getAdapter();
        NotificationChannel ch=new NotificationChannel("connection","耳机连接提示",NotificationManager.IMPORTANCE_LOW);
        ch.setSound(null,null);ch.enableVibration(false);ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }
    private boolean enabled(){return getSharedPreferences("popup",MODE_PRIVATE).getBoolean("enabled",false);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?RESTORE:intent.getAction();
        int command=STOP.equals(action)?MonitorPolicy.STOP:START.equals(action)?MonitorPolicy.ENABLE:
            PREVIEW.equals(action)?MonitorPolicy.PREVIEW:REFRESH.equals(action)?MonitorPolicy.REFRESH:MonitorPolicy.RESTORE;
        boolean monitor=MonitorPolicy.enabledAfter(enabled(),command);
        if(command==MonitorPolicy.ENABLE||command==MonitorPolicy.STOP)
            getSharedPreferences("popup",MODE_PRIVATE).edit().putBoolean("enabled",monitor).apply();
        if(command==MonitorPolicy.STOP){
            cancelAll();hide(false);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            Log.i(TAG,"MONITOR_DISABLED");return START_NOT_STICKY;
        }
        boolean oneShot=command==MonitorPolicy.PREVIEW||command==MonitorPolicy.REFRESH;
        if(!monitor&&!oneShot){stopSelf();return START_NOT_STICKY;}
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
            Log.w(TAG,"START_BLOCKED bluetooth permission missing");stopSelf();return START_NOT_STICKY;
        }
        DeviceStore.devices(this); // Import legacy device association exactly once.
        Notification notification=notification(monitor);
        if(Build.VERSION.SDK_INT>=29)startForeground(41,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(41,notification);
        if(monitor){
            registerMonitor();if(command==MonitorPolicy.ENABLE)CompanionLink.resumeAllObservation(this);
        }
        if(command==MonitorPolicy.PREVIEW){
            DeviceStore.DeviceRecord d=DeviceStore.selected(this);
            if(d==null)d=new DeviceStore.DeviceRecord("","耳机弹窗","unknown",-1);
            show(d,true,"PREVIEW");
        }else if(command==MonitorPolicy.REFRESH){
            DeviceStore.DeviceRecord d=DeviceStore.selected(this);
            if(d!=null)checkConnections("MANUAL_REFRESH",d.address,true);
            if(!monitor){
                if(oneShotTimeout!=null)main.removeCallbacks(oneShotTimeout);
                oneShotTimeout=()->{cancelAll();finishOneShot();};
                main.postDelayed(oneShotTimeout,6000);
            }
        }else if(DISCONNECTED.equals(action)){
            disconnected(intent.getStringExtra("address"));
        }else{
            String address=intent==null?null:intent.getStringExtra("address");
            String source=CONNECTED.equals(action)?"COMPANION_BT":"A2DP_SNAPSHOT";
            checkConnections(source,address,false);
            if(CONNECTED.equals(action)){
                main.postDelayed(()->{if(enabled())checkConnections(source,address,false);},450);
                main.postDelayed(()->{if(enabled())checkConnections(source,address,false);},1200);
            }
        }
        return monitor?START_STICKY:START_NOT_STICKY;
    }
    private void registerMonitor(){
        if(registered)return;
        IntentFilter f=new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_EXPORTED);else registerReceiver(receiver,f);
        registered=true;Log.i(TAG,"MONITOR_READY devices="+DeviceStore.devices(this).size());
    }
    private Notification notification(boolean monitor){
        PendingIntent settings=PendingIntent.getActivity(this,1,new Intent(this,SetupActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,2,new Intent(this,PopupService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        int icon=getResources().getIdentifier("ic_headphones","drawable",getPackageName());
        Notification.Builder b=new Notification.Builder(this,"connection").setSmallIcon(icon)
          .setContentTitle(monitor?"耳机弹窗已开启":"耳机弹窗预览")
          .setContentText(monitor?"连接已添加的耳机时显示卡片":"本次操作不会开启后台监听")
          .setContentIntent(settings).setOnlyAlertOnce(true).setOngoing(monitor).setCategory(Notification.CATEGORY_SERVICE);
        if(monitor)b.addAction(new Notification.Action.Builder(null,"关闭监听",stop).build());
        return b.build();
    }
    private void checkConnections(String source,String onlyAddress,boolean forceRefresh){
        if(adapter==null||destroyed)return;
        try{
            adapter.getProfileProxy(this,new BluetoothProfile.ServiceListener(){
                @Override public void onServiceConnected(int profile,BluetoothProfile proxy){
                    List<String> connected=new ArrayList<>();
                    try{for(BluetoothDevice d:proxy.getConnectedDevices()){
                        String address=d.getAddress();
                        if((onlyAddress==null||onlyAddress.equalsIgnoreCase(address)) && DeviceStore.findByAddress(PopupService.this,address)!=null)
                            connected.add(address);
                    }}catch(SecurityException e){Log.w(TAG,"AUDIO_QUERY_DENIED");}
                    finally{adapter.closeProfileProxy(profile,proxy);}
                    main.post(()->{
                        if(destroyed)return;
                        for(String address:connected){
                            if(forceRefresh)queryBattery(address,true);
                            else if(enabled())audioConnected(address,source,false);
                        }
                        if(forceRefresh&&connected.isEmpty()){
                            Log.i(TAG,"BATTERY_SKIPPED no connected audio device");finishOneShot();
                        }
                    });
                }
                @Override public void onServiceDisconnected(int profile){}
            },BluetoothProfile.A2DP);
        }catch(RuntimeException e){Log.w(TAG,"AUDIO_QUERY_FAILED",e);if(forceRefresh)finishOneShot();}
    }
    private void audioConnected(String address,String source,boolean forced){
        if(!enabled()||destroyed)return;
        DeviceStore.DeviceRecord d=DeviceStore.findByAddress(this,address);if(d==null)return;
        if(canShow()&&gate.connected(address,SystemClock.elapsedRealtime()))show(d,false,source);
        else Log.i(TAG,"DUPLICATE_OR_LOCKED source="+source);
        queryBattery(address,forced);
    }
    private void disconnected(String address){
        if(address==null)return;
        gate.disconnected(address);cancelQuery(address);attempts.remove(address);
        if(shownDevice!=null&&address.equalsIgnoreCase(shownDevice.address))hide(true);
        Log.i(TAG,"TARGET_DISCONNECTED");
    }
    private void queryBattery(String address,boolean force){
        if(destroyed||queries.containsKey(address))return;
        DeviceStore.DeviceRecord d=DeviceStore.findByAddress(this,address);
        if(d==null||!"freebuds_6i".equals(d.modelId))return;
        if(statusObserver!=null&&address.equalsIgnoreCase(observedAddress))return;
        long now=SystemClock.elapsedRealtime();
        Long last=attempts.get(address);
        if(!force&&last!=null&&now-last<30000)return;
        attempts.put(address,now);
        BatteryClient.Query q=BatteryClient.query(this,address,new BatteryClient.Callback(){
            @Override public void onBattery(BatterySnapshot snapshot){
                if(destroyed)return;
                BatteryCache.save(PopupService.this,address,snapshot);
                if(card!=null&&shownDevice!=null&&address.equalsIgnoreCase(shownDevice.address))card.setBattery(snapshot);
                Log.i(TAG,"BATTERY_UPDATED left="+snapshot.left+" right="+snapshot.right+" case="+snapshot.caseLevel+" overall="+snapshot.overall);
                sendBroadcast(new Intent("dev.earbuds.popup.BATTERY_UPDATED").setPackage(getPackageName()));
            }
            @Override public void onUnavailable(String reason){
                queries.remove(address);
                if(!destroyed)Log.i(TAG,"BATTERY_UNAVAILABLE "+reason);
                finishOneShot();
            }
        });
        queries.put(address,q);
        // Also release bookkeeping after the transport's bounded deadline.
        main.postDelayed(()->{BatteryClient.Query current=queries.get(address);if(current==q){queries.remove(address);current.cancel();finishOneShot();}},5500);
    }
    private boolean canShow(){
        return Settings.canDrawOverlays(this) && getSystemService(PowerManager.class).isInteractive()
            && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    private void show(DeviceStore.DeviceRecord d,boolean preview,String source){
        if(!canShow()){finishOneShot();return;}
        replacing=true;
        hide(false);
        try{
            if(windowContext==null){
                windowContext=Build.VERSION.SDK_INT>=30?
                  createDisplayContext(getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY)).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null):this;
                windows=windowContext.getSystemService(WindowManager.class);
            }
            PopupConfig cfg=DeviceStore.loadConfig(this,d.modelId).sanitized();
            PopupCardView next=new PopupCardView(windowContext,d,cfg,preview?BatteryCache.load(this,d.address):BatterySnapshot.unknown(),preview,()->dismiss("USER_CLOSE"));
            int screenWidth=getResources().getDisplayMetrics().widthPixels;
            int screenHeight=getResources().getDisplayMetrics().heightPixels;
            int top=dp(28),bottom=dp(24),left=0,right=0;
            if(Build.VERSION.SDK_INT>=30){
                WindowMetrics metrics=windows.getMaximumWindowMetrics();
                screenWidth=metrics.getBounds().width();screenHeight=metrics.getBounds().height();
                Insets i=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                top=i.top+dp(8);bottom=i.bottom+dp(8);left=i.left;right=i.right;
            }
            int width=Math.max(dp(200),Math.round((screenWidth-left-right)*cfg.widthPercent/100f));
            width=Math.min(screenWidth-left-right,width);
            int height=Math.min(dp(next.heightDp()),screenHeight-top-bottom);
            WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
              WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.TRANSLUCENT);
            int offset=dp(cfg.offsetDp);
            if(cfg.position==0){p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.y=Math.max(top,Math.min(screenHeight-bottom-height,top+offset));}
            else if(cfg.position==1){p.gravity=Gravity.CENTER;p.y=Math.max(-(screenHeight-height)/2+top,Math.min((screenHeight-height)/2-bottom,offset));}
            else{p.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;p.y=Math.max(bottom,Math.min(screenHeight-top-height,bottom+offset));}
            if(Build.VERSION.SDK_INT>=30)p.setFitInsetsTypes(0);
            p.windowAnimations=0;p.setTitle("EarbudsPopupCard");if(Build.VERSION.SDK_INT>=28)p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            card=next;shownDevice=d;windows.addView(next,p);next.playEntrance();
            Log.i(TAG,"OVERLAY_SHOWN source="+source+" model="+d.modelId+" type=2038 activityStarted=false lifetime=event");
            statusRetries=0;
            if(!preview)observeStatus(d);
        }catch(RuntimeException e){Log.e(TAG,"OVERLAY_FAILED",e);hide(false);}
        finally{replacing=false;finishOneShot();}
    }
    private void observeStatus(DeviceStore.DeviceRecord device){
        if(!"freebuds_6i".equals(device.modelId))return;
        cancelStatusObservation();
        cancelQuery(device.address); // One control socket owns battery and status for this popup.
        final int generation=statusGeneration;
        final String address=device.address;
        final EarbudStateProtocol.Decoder decoder=new EarbudStateProtocol.Decoder();
        observedAddress=address;
        statusObserver=BatteryClient.observe(this,address,new BatteryClient.ObserverCallback(){
            private boolean current(){return !destroyed&&generation==statusGeneration;}
            @Override public void onBattery(BatterySnapshot snapshot){
                if(!current())return;
                BatteryCache.save(PopupService.this,address,snapshot);
                if(card!=null&&shownDevice!=null&&address.equalsIgnoreCase(shownDevice.address))card.setBattery(snapshot);
                Log.i(TAG,"BATTERY_UPDATED left="+snapshot.left+" right="+snapshot.right+" case="+snapshot.caseLevel+" overall="+snapshot.overall);
                sendBroadcast(new Intent("dev.earbuds.popup.BATTERY_UPDATED").setPackage(getPackageName()));
            }
            @Override public void onData(byte[] bytes){
                if(!current())return;
                for(EarbudStateProtocol.State state:decoder.feed(bytes,0,bytes.length)){
                    Log.i(TAG,"WEAR_STATE left="+state.leftWorn+" right="+state.rightWorn);
                    if(state.anyWorn()&&card!=null&&shownDevice!=null&&address.equalsIgnoreCase(shownDevice.address)){
                        dismiss("WEAR_DETECTED");
                        break;
                    }
                }
            }
            @Override public void onUnavailable(String reason){
                if(!current())return;
                statusObserver=null;observedAddress=null;
                Log.i(TAG,"STATUS_OBSERVER_UNAVAILABLE "+reason);
                // Shared SPP channels may be busy during another app's connection setup.
                // Retry briefly with backoff while this exact card remains open, then leave manual close available.
                if(card!=null&&shownDevice!=null&&address.equalsIgnoreCase(shownDevice.address)&&statusRetries<3){
                    long delay=new long[]{1500,3000,6000}[statusRetries++];
                    Log.i(TAG,"STATUS_OBSERVER_RETRY delayMs="+delay);
                    statusRetry=()->{
                        statusRetry=null;
                        if(!destroyed&&enabled()&&card!=null&&shownDevice!=null&&address.equalsIgnoreCase(shownDevice.address))observeStatus(device);
                    };
                    main.postDelayed(statusRetry,delay);
                }
                // An absent sensor event is not a dismissal event. The close button remains usable.
            }
        });
    }
    private void cancelStatusObservation(){
        if(statusRetry!=null){main.removeCallbacks(statusRetry);statusRetry=null;}
        statusGeneration++;
        BatteryClient.Query observer=statusObserver;
        statusObserver=null;observedAddress=null;
        if(observer!=null)observer.cancel();
    }
    private void dismiss(String reason){Log.i(TAG,"POPUP_DISMISS reason="+reason);hide(true);}
    private void hide(boolean animated){
        cancelStatusObservation();
        // Battery refresh belongs to the connection, not the visible card lifetime.
        // Its bounded query can still update cache after a short popup closes.
        PopupCardView old=card;
        card=null;shownDevice=null;
        if(old==null)return;
        Runnable remove=()->{
            try{if(windows!=null)windows.removeViewImmediate(old);}catch(IllegalArgumentException ignored){}
            finally{old.release();}
            Log.i(TAG,"OVERLAY_HIDDEN");finishOneShot();
        };
        if(animated)old.playExit(remove);else remove.run();
    }
    private void cancelQuery(String address){BatteryClient.Query q=queries.remove(address);if(q!=null)q.cancel();}
    private void cancelAll(){cancelStatusObservation();for(BatteryClient.Query q:new ArrayList<>(queries.values()))q.cancel();queries.clear();}
    private void finishOneShot(){
        if(!destroyed&&!replacing&&!enabled()&&card==null&&queries.isEmpty()){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    }
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    @Override public void onDestroy(){
        destroyed=true;cancelAll();
        if(registered){unregisterReceiver(receiver);registered=false;}
        hide(false);main.removeCallbacksAndMessages(null);Log.i(TAG,"MONITOR_STOPPED");super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
