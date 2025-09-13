package com.example.vlcremote;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

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
    private String VLC_IP = "192.168.1.15";
    private int VLC_PORT = 8000;

    // Aggiungi costante per il file di configurazione
    private static final String CONFIG_FILE = "vlc_config.txt";

    Button btnInfo, btnSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Carica le impostazioni salvate all'avvio
        loadSettings();
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
        btnInfo = findViewById(R.id.btnInfo);
        btnSettings = findViewById(R.id.btnSettings);
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
        btnVolUp.setOnClickListener(v -> sendCommand("volup 3"));
        btnVolDown.setOnClickListener(v -> sendCommand("voldown 3"));
        btnFullscreen.setOnClickListener(v -> sendCommand("fullscreen"));
        btnRefreshPlaylist.setOnClickListener(v -> updatePlaylist());
        btnInfo.setOnClickListener(v -> showInfoDialog());
        btnSettings.setOnClickListener(v -> showSettingsDialog());

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
        btnInfo.setEnabled(true); // Il pulsante info è sempre abilitato
        btnSettings.setEnabled(true); //Il pulsante impostazioni è sempre abilitato
        seekBar.setEnabled(enabled);
    }


    // Aggiungi questo metodo per mostrare le informazioni
    private void showInfoDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Informazioni su VLC Remote");
        builder.setMessage("vlcremote App\n\n" +
                "Sviluppato da: 'losciuto'\n" +
                "Versione: 1.0 del settembre 2025\n\n" +
                "Un'app per controllare VLC Media Player\n" +
                "da dispositivo Android tramite rete.");
        builder.setPositiveButton("OK", null);
        builder.show();
    }

    // Aggiungi questo metodo per mostrare le impostazioni
    // Modifica il metodo showSettingsDialog per salvare le impostazioni
    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Impostazioni Server VLC");

        // Crea un layout per la finestra di dialogo
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        // Campo per l'IP
        TextView ipLabel = new TextView(this);
        ipLabel.setText("Indirizzo IP:");
        ipLabel.setTextColor(Color.WHITE);
        ipLabel.setTextSize(16);
        layout.addView(ipLabel);

        final EditText ipInput = new EditText(this);
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT);
        ipInput.setText(VLC_IP);
        ipInput.setTextColor(Color.BLACK);
        ipInput.setHint("Inserisci l'IP del server VLC");
        ipInput.setHintTextColor(Color.GRAY);
        layout.addView(ipInput);

        // Campo per la porta
        TextView portLabel = new TextView(this);
        portLabel.setText("Porta:");
        portLabel.setTextColor(Color.WHITE);
        portLabel.setTextSize(16);
        portLabel.setPadding(0, 30, 0, 0);
        layout.addView(portLabel);

        final EditText portInput = new EditText(this);
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        portInput.setText(String.valueOf(VLC_PORT));
        portInput.setTextColor(Color.BLACK);
        portInput.setHint("Inserisci la porta");
        portInput.setHintTextColor(Color.GRAY);
        layout.addView(portInput);

        builder.setView(layout);

        // Pulsanti
        builder.setPositiveButton("Salva", (dialog, which) -> {
            String newIp = ipInput.getText().toString().trim();
            String newPort = portInput.getText().toString().trim();

            if (!newIp.isEmpty() && !newPort.isEmpty()) {
                VLC_IP = newIp;
                try {
                    VLC_PORT = Integer.parseInt(newPort);
                    // Salva le nuove impostazioni
                    saveSettings();
                    // Riconnetti con le nuove impostazioni
                    reconnectWithNewSettings();
                } catch (NumberFormatException e) {
                    Toast.makeText(MainActivity.this, "Porta non valida", Toast.LENGTH_SHORT).show();
                }
            }
        });

        builder.setNegativeButton("Annulla", null);

        // Mostra i valori attuali come suggerimento
        builder.setNeutralButton("Ripristina Default", (dialog, which) -> {
            VLC_IP = "192.168.1.15";
            VLC_PORT = 8000;
            // Salva i valori di default
            saveSettings();
            reconnectWithNewSettings();
        });

        AlertDialog dialog = builder.create();
        dialog.show();

        // Personalizza i colori dei pulsanti
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#4CAF50"));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.parseColor("#F44336"));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.parseColor("#2196F3"));
    }

    // Metodo per riconnettersi con le nuove impostazioni
    private void reconnectWithNewSettings() {
        // Disconnetti prima se connesso
        if (isConnected) {
            try {
                isConnected = false;
                if (writer != null) writer.close();
                if (reader != null) reader.close();
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (Exception ignored) {}
        }

        // Riconnetti con le nuove impostazioni
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "Connessione a " + VLC_IP + ":" + VLC_PORT, Toast.LENGTH_SHORT).show();
            setButtonsEnabled(false);
            txtNowPlaying.setText("Connessione in corso...");
            playlistItems.clear();
            playlistAdapter.notifyDataSetChanged();
        });

        connectToVlc();
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
    private void saveSettings() {
        try {
            FileOutputStream fos = openFileOutput(CONFIG_FILE, MODE_PRIVATE);
            OutputStreamWriter osw = new OutputStreamWriter(fos);
            osw.write(VLC_IP + "\n");
            osw.write(VLC_PORT + "\n");
            osw.close();
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadSettings() {
        try {
            File file = new File(getFilesDir(), CONFIG_FILE);
            if (file.exists()) {
                FileInputStream fis = openFileInput(CONFIG_FILE);
                BufferedReader br = new BufferedReader(new InputStreamReader(fis));
                String ip = br.readLine();
                String port = br.readLine();
                br.close();
                fis.close();

                if (ip != null && !ip.trim().isEmpty()) {
                    VLC_IP = ip.trim();
                }
                if (port != null && !port.trim().isEmpty()) {
                    try {
                        VLC_PORT = Integer.parseInt(port.trim());
                    } catch (NumberFormatException e) {
                        // Mantieni il valore default se la porta non è valida
                        VLC_PORT = 8000;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            // In caso di errore, mantieni i valori di default
            VLC_IP = "192.168.1.15";
            VLC_PORT = 8000;
        }
    }

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
