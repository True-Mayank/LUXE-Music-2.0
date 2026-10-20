
package com.luxe.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class PlaybackKeepAliveService extends Service {

    public static final String ACTION_PLAY_QUEUE = "luxe.PLAY_QUEUE";
    public static final String ACTION_PLAY = "luxe.PLAY";
    public static final String ACTION_PAUSE = "luxe.PAUSE";
    public static final String ACTION_NEXT = "luxe.NEXT";
    public static final String ACTION_PREVIOUS = "luxe.PREVIOUS";
    public static final String ACTION_SEEK = "luxe.SEEK";
    public static final String ACTION_STOP = "luxe.STOP";
    public static final String ACTION_SET_REPEAT = "luxe.SET_REPEAT";
    public static final String ACTION_STATE = "luxe.STATE";

    private static PlaybackKeepAliveService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Track> queue = new ArrayList<>();

    private MediaPlayer player;
    private MediaSession session;
    private PowerManager.WakeLock wakeLock;

    private int index = -1;
    private boolean repeat = false;

    // Use an anonymous Runnable so it can safely reschedule itself with this.
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            publishState();

            if (player != null && player.isPlaying()) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        instance = this;
        createChannel();

        session = new MediaSession(this, "LUXE Music");

        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                next();
            }

            @Override
            public void onSkipToPrevious() {
                previous();
            }

            @Override
            public void onSeekTo(long position) {
                seek(position);
            }

            @Override
            public void onStop() {
                stopPlayback(true);
            }
        });

        session.setActive(true);

        PowerManager pm =
                (PowerManager) getSystemService(POWER_SERVICE);

        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LUXE:Playback"
            );
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "luxe_playback",
                    "LUXE Music Playback",
                    NotificationManager.IMPORTANCE_LOW
            );

            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            String action = intent == null ? null : intent.getAction();

            if (ACTION_PLAY_QUEUE.equals(action)) {
                loadQueue(
                        intent.getStringExtra("queue"),
                        intent.getIntExtra("index", 0),
                        intent.getBooleanExtra("repeat", false)
                );
            } else if (ACTION_PLAY.equals(action)) {
                play();
            } else if (ACTION_PAUSE.equals(action)) {
                pause();
            } else if (ACTION_NEXT.equals(action)) {
                next();
            } else if (ACTION_PREVIOUS.equals(action)) {
                previous();
            } else if (ACTION_SEEK.equals(action)) {
                seek(intent.getLongExtra("position", 0));
            } else if (ACTION_STOP.equals(action)) {
                stopPlayback(true);
            } else if (ACTION_SET_REPEAT.equals(action)) {
                repeat = intent.getBooleanExtra("repeat", false);
                publishState();
            }
        } catch (Exception e) {
            publishState();
        }

        return START_NOT_STICKY;
    }

    private void loadQueue(String raw, int start, boolean rep)
            throws Exception {

        queue.clear();

        if (raw != null && !raw.isEmpty()) {
            JSONArray array = new JSONArray(raw);

            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                String url = object.optString("url", "");

                if (!url.isEmpty()) {
                    queue.add(new Track(
                            object.optString("id", ""),
                            object.optString("title", "Unknown Title"),
                            object.optString("artist", "Unknown Artist"),
                            url
                    ));
                }
            }
        }

        repeat = rep;

        if (queue.isEmpty()) {
            index = -1;
            releasePlayer();
            publishState();
            return;
        }

        index = Math.max(0, Math.min(start, queue.size() - 1));
        play();
    }

    private void play() {
        if (queue.isEmpty()) {
            publishState();
            return;
        }

        if (player != null && !player.isPlaying()) {
            try {
                if (player.getDuration() > 0) {
                    player.start();
                    acquireWakeLock();
                    startForeground(4207, notification());
                    updateSession();
                    handler.removeCallbacks(ticker);
                    handler.post(ticker);
                    publishState();
                    return;
                }
            } catch (IllegalStateException ignored) {
                // Recreate the player below if it is no longer usable.
            }
        }

        playIndex(index < 0 ? 0 : index);
    }

    private void playIndex(int i) {
        if (i < 0 || i >= queue.size()) {
            publishState();
            return;
        }

        index = i;
        releasePlayer();

        Track track = queue.get(i);
        player = new MediaPlayer();

        player.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
        );

        player.setOnPreparedListener(mp -> {
            try {
                mp.start();
                acquireWakeLock();
                startForeground(4207, notification());

                handler.removeCallbacks(ticker);
                handler.post(ticker);

                updateSession();
                publishState();
            } catch (Exception e) {
                releasePlayer();
                publishState();
            }
        });

        player.setOnCompletionListener(mp -> {
            if (repeat) {
                playIndex(index);
            } else if (index + 1 < queue.size()) {
                playIndex(index + 1);
            } else {
                publishState();
                updateSession();
            }
        });

        player.setOnErrorListener((mp, what, extra) -> {
            releasePlayer();
            publishState();
            updateSession();
            return true;
        });

        try {
            player.setDataSource(track.url);
            player.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
            publishState();
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(4 * 60 * 60 * 1000L);
        }
    }

    private void pause() {
        if (player != null && player.isPlaying()) {
            player.pause();

            handler.removeCallbacks(ticker);

            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }

            publishState();
            updateSession();
            startForeground(4207, notification());
        }
    }

    private void next() {
        if (index + 1 < queue.size()) {
            playIndex(index + 1);
        } else if (repeat && !queue.isEmpty()) {
            playIndex(0);
        }
    }

    private void previous() {
        if (player != null) {
            try {
                if (player.getCurrentPosition() > 3000) {
                    player.seekTo(0);
                    publishState();
                    return;
                }
            } catch (IllegalStateException ignored) {
                // Continue to the previous track.
            }
        }

        if (index > 0) {
            playIndex(index - 1);
        } else if (repeat && !queue.isEmpty()) {
            playIndex(queue.size() - 1);
        }
    }

    private void seek(long position) {
        if (player != null) {
            try {
                player.seekTo((int) Math.max(0, position));
            } catch (Exception ignored) {
                // The player may not be ready to seek.
            }

            publishState();
            updateSession();
        }
    }

    private void releasePlayer() {
        handler.removeCallbacks(ticker);

        if (player != null) {
            try {
                player.setOnPreparedListener(null);
                player.setOnCompletionListener(null);
                player.setOnErrorListener(null);
                player.reset();
            } catch (Exception ignored) {
                // Continue releasing the player.
            }

            try {
                player.release();
            } catch (Exception ignored) {
                // The player may already have been released.
            }

            player = null;
        }
    }

    private void stopPlayback(boolean remove) {
        releasePlayer();

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        publishState();
        updateSession();

        if (remove) {
            stopForeground(true);
        }
    }

    private Notification notification() {
        Track track = index >= 0 && index < queue.size()
                ? queue.get(index)
                : new Track("", "LUXE Music", "", "");

        Intent openIntent = new Intent(this, MainActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE
                        | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, "luxe_playback");
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(track.title)
                .setContentText(track.artist)
                .setContentIntent(pendingIntent)
                .setOngoing(player != null && player.isPlaying())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(
                        new Notification.MediaStyle()
                                .setMediaSession(session.getSessionToken())
                );

        return builder.build();
    }

    private void updateSession() {
        if (session == null) {
            return;
        }

        long position = 0;
        boolean playing = false;

        if (player != null) {
            try {
                position = Math.max(0, player.getCurrentPosition());
                playing = player.isPlaying();
            } catch (IllegalStateException ignored) {
                // Keep the default state.
            }
        }

        int state = playing
                ? PlaybackState.STATE_PLAYING
                : PlaybackState.STATE_PAUSED;

        session.setPlaybackState(
                new PlaybackState.Builder()
                        .setActions(
                                PlaybackState.ACTION_PLAY
                                        | PlaybackState.ACTION_PAUSE
                                        | PlaybackState.ACTION_SKIP_TO_NEXT
                                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                                        | PlaybackState.ACTION_SEEK_TO
                                        | PlaybackState.ACTION_STOP
                        )
                        .setState(state, position, playing ? 1f : 0f)
                        .build()
        );

        Track track = index >= 0 && index < queue.size()
                ? queue.get(index)
                : null;

        session.setMetadata(
                new android.media.MediaMetadata.Builder()
                        .putString(
                                android.media.MediaMetadata.METADATA_KEY_TITLE,
                                track == null ? "LUXE Music" : track.title
                        )
                        .putString(
                                android.media.MediaMetadata.METADATA_KEY_ARTIST,
                                track == null ? "" : track.artist
                        )
                        .build()
        );
    }

    private void publishState() {
        Intent intent = new Intent(ACTION_STATE);

        Track track = index >= 0 && index < queue.size()
                ? queue.get(index)
                : null;

        long position = 0;
        long duration = 0;
        boolean playing = false;

        if (player != null) {
            try {
                position = Math.max(0, player.getCurrentPosition());
                duration = Math.max(0, player.getDuration());
                playing = player.isPlaying();
            } catch (IllegalStateException ignored) {
                // Use default values while the player is preparing or released.
            }
        }

        intent.putExtra("id", track == null ? "" : track.id);
        intent.putExtra("title", track == null ? "" : track.title);
        intent.putExtra("artist", track == null ? "" : track.artist);
        intent.putExtra("playing", playing);
        intent.putExtra("position", position);
        intent.putExtra("duration", duration);
        intent.putExtra("queueIndex", index);
        intent.putExtra("queueSize", queue.size());

        sendBroadcast(intent);

        if (playing) {
            startForeground(4207, notification());
        }
    }

    public static void requestState(android.content.Context context) {
        if (instance != null) {
            instance.publishState();
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopPlayback(true);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        stopPlayback(false);

        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }

        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static class Track {
        final String id;
        final String title;
        final String artist;
        final String url;

        Track(String id, String title, String artist, String url) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.url = url;
        }
    }
}
