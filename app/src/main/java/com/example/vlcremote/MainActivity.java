package com.example.vlcremote;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;

    TextView txtTime, txtNowPlaying, txtPlaylistCount;
    Button btnPlay, btnPause, btnStop, btnPrev, btnNext, btnVolUp, btnVolDown, btnFullscreen, btnRefreshPlaylist;
    SeekBar seekBar;
    ListView playlistView;

    ArrayList<String> playlistItems = new ArrayList<>();
    ArrayAdapter<String> playlistAdapter;

    private int mediaLength = 0;
    private int currentPlayingIndex = -1;
    private boolean isConnected = false;

    private ScheduledExecutorService scheduler;
    private final Handler uiHandler = new Handler();

    // Cambia qui IP e porta VLC
    private final String VLC_IP = "192.168.1.15";
    private final int VLC_PORT = 8000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        scheduler = Executors.newSingleThreadScheduledExecutor();

        // bind UI
        txtTime = findViewById(R.id.txtTime);
        txtNowPlaying = findViewById(R.id.txtNowPlaying);
        txtPlaylistCount = findViewById(R.id.txtPlaylistCount);
        btnPlay = findViewById(R.id.btnPlay);
        btnPause = findViewById(R.id.btnPause);
        btnStop = findViewById(R.id.btnStop);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        btnVolUp = findViewById(R.id.btnVolUp);
        btnVolDown = findViewById(R.id.btnVolDown);
        btnFullscreen = findViewById(R.id.btnFullscreen);
        btnRefreshPlaylist = findViewById(R.id.btnRefreshPlaylist);
        seekBar = findViewById(R.id.seekBar);
        playlistView = findViewById(R.id.playlistView);

        setButtonsEnabled(false);

        // adapter playlist
        playlistAdapter = new ArrayAdapter<String>(this, R.layout.playlist_item_light, playlistItems) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView;
                if (view == null) {
                    view = getLayoutInflater().inflate(R.layout.playlist_item_light, parent, false);
                }
                TextView text = view.findViewById(R.id.playlistItemText);
                TextView index = view.findViewById(R.id.playlistItemIndex);
                View indicator = view.findViewById(R.id.playlistItemIndicator);

                String item = getItem(position);
                text.setText(item);
                index.setText(String.valueOf(position + 1));

                if (position == currentPlayingIndex) {
                    text.setTextColor(Color.parseColor("#1976D2"));
                    text.setTypeface(null, Typeface.BOLD);
                    indicator.setVisibility(View.VISIBLE);
                    view.setBackgroundColor(Color.parseColor("#E3F2FD"));
                } else {
                    text.setTextColor(Color.parseColor("#212121"));
                    text.setTypeface(null, Typeface.NORMAL);
                    indicator.setVisibility(View.INVISIBLE);
                    view.setBackgroundColor(Color.TRANSPARENT);
                }
                return view;
            }
        };
        playlistView.setAdapter(playlistAdapter);

        // connessione a VLC
        connectToVlc();

        // listeners
        btnPlay.setOnClickListener(v -> sendCommand("play"));
        btnPause.setOnClickListener(v -> sendCommand("pause"));
        btnStop.setOnClickListener(v -> sendCommand("stop"));
        btnPrev.setOnClickListener(v -> sendCommand("prev"));
        btnNext.setOnClickListener(v -> sendCommand("next"));
        btnVolUp.setOnClickListener(v -> sendCommand("volup 5"));
        btnVolDown.setOnClickListener(v -> sendCommand("voldown 5"));
        btnFullscreen.setOnClickListener(v -> sendCommand("fullscreen"));
        btnRefreshPlaylist.setOnClickListener(v -> updatePlaylist());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && mediaLength > 0) {
                    int newTime = (progress * mediaLength) / 100;
                    sendCommand("seek " + newTime);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        playlistView.setOnItemClickListener((p, v, pos, id) -> {
            sendCommand("goto " + (pos + 1));
            currentPlayingIndex = pos;
            playlistAdapter.notifyDataSetChanged();
            uiHandler.postDelayed(this::updateTitleFromVLC, 500);
        });
    }

    private void connectToVlc() {
        new Thread(() -> {
            try {
                socket = new Socket(VLC_IP, VLC_PORT);
                socket.setSoTimeout(2000);
                writer = new PrintWriter(socket.getOutputStream(), true);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                isConnected = true;
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Connesso a VLC", Toast.LENGTH_SHORT).show();
                    setButtonsEnabled(true);
                });

                startSeekBarUpdater();
                startTitleUpdater();
                updatePlaylist();

            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Errore connessione VLC: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    setButtonsEnabled(false);
                });
            }
        }).start();
    }

    private void setButtonsEnabled(boolean enabled) {
        btnPlay.setEnabled(enabled);
        btnPause.setEnabled(enabled);
        btnStop.setEnabled(enabled);
        btnPrev.setEnabled(enabled);
        btnNext.setEnabled(enabled);
        btnVolUp.setEnabled(enabled);
        btnVolDown.setEnabled(enabled);
        btnFullscreen.setEnabled(enabled);
        btnRefreshPlaylist.setEnabled(enabled);
        seekBar.setEnabled(enabled);
    }

    // --- COMUNICAZIONE SINCRONIZZATA ---
    private synchronized String sendCommandAndReadSingle(String cmd, int timeoutMs) {
        if (writer == null || reader == null || !isConnected) return null;
        try {
            writer.print(cmd + "\n");
            writer.flush();
            socket.setSoTimeout(timeoutMs);
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("+----")) return line;
            }
        } catch (SocketTimeoutException ste) {
            return null;
        } catch (Exception ignored) {}
        return null;
    }
    private synchronized String sendCommandAndReadNumber(String cmd, int timeoutMs) {
        if (writer == null || reader == null || !isConnected) return null;
        try {
            writer.print(cmd + "\n");
            writer.flush();
            socket.setSoTimeout(timeoutMs);
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // VLC per questi comandi risponde solo con un numero
                if (line.matches("\\d+")) return line;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private synchronized ArrayList<String> sendCommandAndReadBlock(String cmd, int timeoutMs) {
        ArrayList<String> lines = new ArrayList<>();
        if (writer == null || reader == null || !isConnected) return lines;
        try {
            writer.print(cmd + "\n");
            writer.flush();
            socket.setSoTimeout(timeoutMs);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("+----")) break;
                lines.add(line);
            }
        } catch (Exception ignored) {}
        return lines;
    }

    private void sendCommand(String cmd) {
        new Thread(() -> sendCommandAndReadSingle(cmd, 500)).start();
        uiHandler.postDelayed(() -> {
            updatePlaylist();
            updateTitleFromVLC();
        }, 400);
    }

    // --- UPDATERS ---
    private void startSeekBarUpdater() {
        scheduler.scheduleAtFixedRate(() -> {
            if (!isConnected) return;
            try {
                String lenResp = sendCommandAndReadNumber("get_length", 800);
                if (lenResp != null) mediaLength = Integer.parseInt(lenResp);

                String timeResp = sendCommandAndReadNumber("get_time", 800);
                if (timeResp != null) {
                    int currentTime = Integer.parseInt(timeResp);
                    if (mediaLength > 0) {
                        int progress = (currentTime * 100) / mediaLength;
                        String timeText = formatTime(currentTime) + " / " + formatTime(mediaLength);
                        runOnUiThread(() -> {
                            seekBar.setProgress(progress);
                            txtTime.setText(timeText);
                        });
                    }
                }
            } catch (Exception ignored) {}
        }, 0, 1, TimeUnit.SECONDS);
    }


    private void startTitleUpdater() {
        scheduler.scheduleAtFixedRate(this::updateTitleFromVLC, 0, 3, TimeUnit.SECONDS);
    }

    private void updatePlaylist() {
        if (!isConnected) return;
        new Thread(() -> {
            ArrayList<String> raw = sendCommandAndReadBlock("playlist", 1500);
            ArrayList<String> newItems = new ArrayList<>();
            int newCurrent = -1;
            int idx = 0;
            Pattern currentPattern = Pattern.compile(".*(\\|>|\\*).*");
            for (String line : raw) {
                if (line.contains("-")) {
                    String cleaned = cleanPlaylistItem(line);
                    if (!cleaned.isEmpty()) {
                        if (currentPattern.matcher(line).matches()) newCurrent = idx;
                        newItems.add(cleaned);
                        idx++;
                    }
                }
            }
            int finalCurrent = newCurrent;
            runOnUiThread(() -> {
                playlistItems.clear();
                playlistItems.addAll(newItems);
                currentPlayingIndex = finalCurrent;
                playlistAdapter.notifyDataSetChanged();
                txtPlaylistCount.setText(playlistItems.size() + " elementi");
            });
        }).start();
    }

    private void updateTitleFromVLC() {
        if (!isConnected) return;
        new Thread(() -> {
            String title = sendCommandAndReadSingle("get_title", 700);
            if (title == null || title.isEmpty()) {
                title = sendCommandAndReadSingle("now_playing", 700);
            }
            if ((title == null || title.isEmpty()) && currentPlayingIndex >= 0 && currentPlayingIndex < playlistItems.size()) {
                title = playlistItems.get(currentPlayingIndex);
            }
            if (title == null || title.isEmpty()) title = "Titolo non disponibile";
            final String finalTitle = title;
            runOnUiThread(() -> txtNowPlaying.setText("🎬 " + finalTitle));
        }).start();
    }

    // --- UTILS ---
    private String cleanPlaylistItem(String item) {
        String cleaned = item.replace("|>", "")
                .replace("| ", "")
                .replace("*", "")
                .replace("- ", "")
                .trim();
        if (cleaned.matches("^\\d+\\..*")) {
            cleaned = cleaned.replaceFirst("^\\d+\\.", "").trim();
        }
        return cleaned;
    }

    private String formatTime(int seconds) {
        int min = seconds / 60;
        int sec = seconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isConnected = false;
        if (scheduler != null && !scheduler.isShutdown()) scheduler.shutdownNow();
        try {
            if (writer != null) writer.close();
            if (reader != null) reader.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (Exception ignored) {}
    }
}
