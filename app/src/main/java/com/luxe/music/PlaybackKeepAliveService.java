package com.luxe.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class PlaybackKeepAliveService extends Service {
    public static final String ACTION_PLAY_QUEUE="luxe.PLAY_QUEUE", ACTION_PLAY="luxe.PLAY", ACTION_PAUSE="luxe.PAUSE", ACTION_NEXT="luxe.NEXT", ACTION_PREVIOUS="luxe.PREVIOUS", ACTION_SEEK="luxe.SEEK", ACTION_STOP="luxe.STOP", ACTION_SET_REPEAT="luxe.SET_REPEAT", ACTION_STATE="luxe.STATE";
    private static PlaybackKeepAliveService instance;
    private final Handler handler=new Handler(); private final List<Track> queue=new ArrayList<>(); private MediaPlayer player; private MediaSession session; private PowerManager.WakeLock wakeLock; private int index=-1; private boolean repeat=false;
    private final Runnable ticker=()->{publishState();if(player!=null&&player.isPlaying())handler.postDelayed(ticker,1000);};
    @Override public void onCreate(){super.onCreate();instance=this;createChannel();session=new MediaSession(this,"LUXE Music");session.setCallback(new MediaSession.Callback(){@Override public void onPlay(){play();}@Override public void onPause(){pause();}@Override public void onSkipToNext(){next();}@Override public void onSkipToPrevious(){previous();}@Override public void onSeekTo(long p){seek(p);}@Override public void onStop(){stopPlayback(true);}});session.setActive(true);PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"LUXE:Playback");}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel("luxe_playback","LUXE Music Playback",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){try{String a=intent==null?null:intent.getAction();if(ACTION_PLAY_QUEUE.equals(a))loadQueue(intent.getStringExtra("queue"),intent.getIntExtra("index",0),intent.getBooleanExtra("repeat",false));else if(ACTION_PLAY.equals(a))play();else if(ACTION_PAUSE.equals(a))pause();else if(ACTION_NEXT.equals(a))next();else if(ACTION_PREVIOUS.equals(a))previous();else if(ACTION_SEEK.equals(a))seek(intent.getLongExtra("position",0));else if(ACTION_STOP.equals(a))stopPlayback(true);else if(ACTION_SET_REPEAT.equals(a)){repeat=intent.getBooleanExtra("repeat",false);publishState();}}catch(Exception ignored){}return START_NOT_STICKY;}
    private void loadQueue(String raw,int start,boolean rep)throws Exception{queue.clear();JSONArray a=new JSONArray(raw);for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);String url=o.optString("url","");if(!url.isEmpty())queue.add(new Track(o.optString("id"),o.optString("title","Unknown Title"),o.optString("artist","Unknown Artist"),url));}repeat=rep;index=Math.max(0,Math.min(start,queue.size()-1));play();}
    private void play(){if(queue.isEmpty())return;if(player!=null&&!player.isPlaying()&&index>=0&&player.getDuration()>0){player.start();publishState();return;}playIndex(index);}
    private void playIndex(int i){if(i<0||i>=queue.size())return;index=i;releasePlayer();Track t=queue.get(i);player=new MediaPlayer();player.setAudioAttributes(new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build());player.setDataSource(t.url);player.setOnPreparedListener(mp->{mp.start();if(!wakeLock.isHeld())wakeLock.acquire(4*60*60*1000L);startForeground(4207,notification());handler.removeCallbacks(ticker);handler.post(ticker);updateSession();});player.setOnCompletionListener(mp->{if(repeat)playIndex(index);else if(index+1<queue.size())playIndex(index+1);else publishState();});try{player.prepareAsync();}catch(Exception e){releasePlayer();}}
    private void pause(){if(player!=null&&player.isPlaying()){player.pause();if(wakeLock.isHeld())wakeLock.release();publishState();updateSession();}}
    private void next(){if(index+1<queue.size())playIndex(index+1);}
    private void previous(){if(player!=null&&player.getCurrentPosition()>3000){player.seekTo(0);publishState();}else if(index>0)playIndex(index-1);}
    private void seek(long p){if(player!=null){try{player.seekTo((int)Math.max(0,p));}catch(Exception ignored){}publishState();}}
    private void releasePlayer(){if(player!=null){try{player.reset();}catch(Exception ignored){}player.release();player=null;}handler.removeCallbacks(ticker);}
    private void stopPlayback(boolean remove){releasePlayer();if(wakeLock.isHeld())wakeLock.release();publishState();if(remove)stopForeground(true);}
    private Notification notification(){Track t=index>=0&&index<queue.size()?queue.get(index):new Track("","LUXE Music",""," ");Intent open=new Intent(this,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,1,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"luxe_playback"):new Notification.Builder(this);b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(t.title).setContentText(t.artist).setContentIntent(pi).setOngoing(player!=null&&player.isPlaying()).setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()));return b.build();}
    private void updateSession(){if(session==null)return;long pos=player==null?0:Math.max(0,player.getCurrentPosition());int state=player!=null&&player.isPlaying()?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED;session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_SEEK_TO).setState(state,pos,1f).build());session.setMetadata(new android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_TITLE,index>=0&&index<queue.size()?queue.get(index).title:"LUXE Music").putString(android.media.MediaMetadata.METADATA_KEY_ARTIST,index>=0&&index<queue.size()?queue.get(index).artist:"").build());}
    private void publishState(){Intent i=new Intent(ACTION_STATE);Track t=index>=0&&index<queue.size()?queue.get(index):null;i.putExtra("id",t==null?"":t.id).putExtra("title",t==null?"":t.title).putExtra("artist",t==null?"":t.artist).putExtra("playing",player!=null&&player.isPlaying()).putExtra("position",player==null?0:player.getCurrentPosition()).putExtra("duration",player==null?0:player.getDuration()).putExtra("queueIndex",index).putExtra("queueSize",queue.size());sendBroadcast(i);if(player!=null&&player.isPlaying())startForeground(4207,notification());}
    public static void requestState(android.content.Context c){if(instance!=null)instance.publishState();}
    @Override public void onTaskRemoved(Intent root){stopPlayback(true);stopSelf();super.onTaskRemoved(root);}
    @Override public void onDestroy(){stopPlayback(false);if(session!=null){session.setActive(false);session.release();}instance=null;super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
    private static class Track{final String id,title,artist,url;Track(String i,String t,String a,String u){id=i;title=t;artist=a;url=u;}}
}
