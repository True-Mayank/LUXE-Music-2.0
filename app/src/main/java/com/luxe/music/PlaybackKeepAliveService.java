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

    private final Handler handler = new Handler();
    private final List<Track> queue = new ArrayList<>();

    private MediaPlayer player;
    private MediaSession session;
    private PowerManager.WakeLock wakeLock;

    private int index = -1;
    private boolean repeat = false;

    /*
     * Fixed ticker.
     *
     * The previous lambda version referenced "ticker" while the field
     * itself was still being initialized. Java reports that as:
     *
     * self-reference in initializer
     *
     * Using an anonymous Runnable and "this" avoids that problem.
     */
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

        wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LUXE:Playback"
        );
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel =
                    new NotificationChannel(
                            "luxe_playback",
                            "LUXE Music Playback",
                            NotificationManager.IMPORTANCE_LOW
                    );

            NotificationManager manager =
                    (NotificationManager) getSystemService(
                            NOTIFICATION_SERVICE
                    );

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        try {

            String action =
                    intent == null ? null : intent.getAction();

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

                seek(
                        intent.getLongExtra("position", 0)
                );

            } else if (ACTION_STOP.equals(action)) {

                stopPlayback(true);

            } else if (ACTION_SET_REPEAT.equals(action)) {

                repeat =
                        intent.getBooleanExtra("repeat", false);

                publishState();
            }

        } catch (Exception ignored) {
        }

        return START_NOT_STICKY;
    }

    private void loadQueue(
            String raw,
            int start,
            boolean rep
    ) throws Exception {

        queue.clear();

        JSONArray array = new JSONArray(raw);

        for (int i = 0; i < array.length(); i++) {

            JSONObject object =
                    array.getJSONObject(i);

            String url =
                    object.optString("url", "");

            if (!url.isEmpty()) {

                queue.add(
                        new Track(
                                object.optString("id"),
                                object.optString(
                                        "title",
                                        "Unknown Title"
                                ),
                                object.optString(
                                        "artist",
                                        "Unknown Artist"
                                ),
                                url
                        )
                );
            }
        }

        repeat = rep;

        if (queue.isEmpty()) {
            index = -1;
            return;
        }

        index =
                Math.max(
                        0,
                        Math.min(
                                start,
                                queue.size() - 1
                        )
                );

        play();
    }

    private void play() {

        if (queue.isEmpty()) {
            return;
        }

        if (
                player != null
                        && !player.isPlaying()
                        && index >= 0
                        && player.getDuration() > 0
        ) {

            player.start();

            publishState();
            updateSession();

            return;
        }

        playIndex(index);
    }

    private void playIndex(int i) {

        if (i < 0 || i >= queue.size()) {
            return;
        }

        index = i;

        releasePlayer();

        Track track = queue.get(i);

        player = new MediaPlayer();

        player.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(
                                AudioAttributes.CONTENT_TYPE_MUSIC
                        )
                        .setUsage(
                                AudioAttributes.USAGE_MEDIA
                        )
                        .build()
        );

        try {

            player.setDataSource(track.url);

        } catch (Exception error) {

            releasePlayer();
            return;
        }

        player.setOnPreparedListener(
                mp -> {

                    mp.start();

                    try {

                        if (!wakeLock.isHeld()) {
                            wakeLock.acquire(
                                    4 * 60 * 60 * 1000L
                            );
                        }

                    } catch (Exception ignored) {
                    }

                    startForeground(
                            4207,
                            notification()
                    );

                    handler.removeCallbacks(ticker);
                    handler.post(ticker);

                    updateSession();
                    publishState();
                }
        );

        player.setOnCompletionListener(
                mp -> {

                    if (repeat) {

                        playIndex(index);

                    } else if (index + 1 < queue.size()) {

                        playIndex(index + 1);

                    } else {

                        publishState();
                        updateSession();
                    }
                }
        );

        player.setOnErrorListener(
                (mp, what, extra) -> {

                    releasePlayer();
                    publishState();

                    return true;
                }
        );

        try {

            player.prepareAsync();

        } catch (Exception error) {

            releasePlayer();
        }
    }

    private void pause() {

        if (player != null && player.isPlaying()) {

            player.pause();

            try {

                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }

            } catch (Exception ignored) {
            }

            publishState();
            updateSession();
        }
    }

    private void next() {

        if (index + 1 < queue.size()) {
            playIndex(index + 1);
        }
    }

    private void previous() {

        if (
                player != null
                        && player.getCurrentPosition() > 3000
        ) {

            player.seekTo(0);

            publishState();

        } else if (index > 0) {

            playIndex(index - 1);
        }
    }

    private void seek(long position) {

        if (player != null) {

            try {

                player.seekTo(
                        (int) Math.max(0, position)
                );

            } catch (Exception ignored) {
            }

            publishState();
        }
    }

    private void releasePlayer() {

        if (player != null) {

            try {
                player.reset();
            } catch (Exception ignored) {
            }

            try {
                player.release();
            } catch (Exception ignored) {
            }

            player = null;
        }

        handler.removeCallbacks(ticker);
    }

    private void stopPlayback(boolean remove) {

        releasePlayer();

        try {

            if (wakeLock.isHeld()) {
                wakeLock.release();
            }

        } catch (Exception ignored) {
        }

        publishState();

        if (remove) {
            stopForeground(true);
        }
    }

    private Notification notification() {

        Track track =
                index >= 0 && index < queue.size()
                        ? queue.get(index)
                        : new Track(
                                "",
                                "LUXE Music",
                                "",
                                ""
                        );

        Intent open =
                new Intent(
                        this,
                        MainActivity.class
                );

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        1,
                        open,
                        PendingIntent.FLAG_IMMUTABLE
                                | PendingIntent.FLAG_UPDATE_CURRENT
                );

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= 26) {

            builder =
                    new Notification.Builder(
                            this,
                            "luxe_playback"
                    );

        } else {

            builder =
                    new Notification.Builder(this);
        }

        builder
                .setSmallIcon(
                        android.R.drawable.ic_media_play
                )
                .setContentTitle(track.title)
                .setContentText(track.artist)
                .setContentIntent(pendingIntent)
                .setOngoing(
                        player != null
                                && player.isPlaying()
                )
                .setStyle(
                        new Notification.MediaStyle()
                                .setMediaSession(
                                        session.getSessionToken()
                                )
                );

        return builder.build();
    }

    private void updateSession() {

        if (session == null) {
            return;
        }

        long position =
                player == null
                        ? 0
                        : Math.max(
                                0,
                                player.getCurrentPosition()
                        );

        int state =
                player != null && player.isPlaying()
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
                        )
                        .setState(
                                state,
                                position,
                                1f
                        )
                        .build()
        );

        String title =
                index >= 0 && index < queue.size()
                        ? queue.get(index).title
                        : "LUXE Music";

        String artist =
                index >= 0 && index < queue.size()
                        ? queue.get(index).artist
                        : "";

        session.setMetadata(
                new android.media.MediaMetadata.Builder()
                        .putString(
                                android.media.MediaMetadata.METADATA_KEY_TITLE,
                                title
                        )
                        .putString(
                                android.media.MediaMetadata.METADATA_KEY_ARTIST,
                                artist
                        )
                        .build()
        );
    }

    private void publishState() {

        Intent intent =
                new Intent(ACTION_STATE);

        Track track =
                index >= 0 && index < queue.size()
                        ? queue.get(index)
                        : null;

        intent
                .putExtra(
                        "id",
                        track == null ? "" : track.id
                )
                .putExtra(
                        "title",
                        track == null ? "" : track.title
                )
                .putExtra(
                        "artist",
                        track == null ? "" : track.artist
                )
                .putExtra(
                        "playing",
                        player != null && player.isPlaying()
                )
                .putExtra(
                        "position",
                        player == null
                                ? 0
                                : player.getCurrentPosition()
                )
                .putExtra(
                        "duration",
                        player == null
                                ? 0
                                : player.getDuration()
                )
                .putExtra(
                        "queueIndex",
                        index
                )
                .putExtra(
                        "queueSize",
                        queue.size()
                );

        sendBroadcast(intent);

        if (player != null && player.isPlaying()) {

            startForeground(
                    4207,
                    notification()
            );
        }
    }

    public static void requestState(
            android.content.Context context
    ) {

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

        Track(
                String id,
                String title,
                String artist,
                String url
        ) {

            this.id = id;
            this.title = title;
            this.artist = artist;
            this.url = url;
        }
    }
}
