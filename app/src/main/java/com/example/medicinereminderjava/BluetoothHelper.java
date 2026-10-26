package com.example.medicinereminderjava; // change this to your app's package name

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Talks to an HC-05 module over classic Bluetooth (SPP).
 * The HC-05 must already be paired in the phone's Bluetooth settings.
 */
public class BluetoothHelper {

    public interface Listener {
        void onConnected();
        void onDisconnected();
        void onError(String message);
        void onMessage(String message); // e.g. "TAKEN:Paracetamol"
    }

    // Standard Serial Port Profile UUID used by HC-05
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private BluetoothSocket socket;
    private OutputStream outputStream;
    private volatile boolean connected = false;

    public BluetoothHelper(Listener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return connected;
    }

    /** Connects to a paired device by its name, for example "HC-05". */
    @SuppressLint("MissingPermission")
    public void connect(final String deviceName) {
        new Thread(() -> {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                postError("Turn on Bluetooth first");
                return;
            }

            BluetoothDevice target = null;
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                if (deviceName.equals(d.getName())) {
                    target = d;
                    break;
                }
            }
            if (target == null) {
                postError("Pair " + deviceName + " in phone Bluetooth settings first");
                return;
            }

            try {
                adapter.cancelDiscovery();
                socket = target.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                outputStream = socket.getOutputStream();
                connected = true;
                mainHandler.post(listener::onConnected);
                readLoop();
            } catch (IOException e) {
                close();
                postError("Could not connect: " + e.getMessage());
            }
        }).start();
    }

    /** Sends one line of text to the Arduino, for example "ADD Paracetamol 09:00". */
    public void send(final String text) {
        if (!connected || outputStream == null) {
            postError("Arduino not connected");
            return;
        }
        new Thread(() -> {
            try {
                outputStream.write((text + "\n").getBytes());
                outputStream.flush();
            } catch (IOException e) {
                postError("Send failed: " + e.getMessage());
            }
        }).start();
    }

    public void close() {
        connected = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    private void readLoop() {
        try {
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                final String msg = line.trim();
                if (!msg.isEmpty()) {
                    mainHandler.post(() -> listener.onMessage(msg));
                }
            }
        } catch (IOException ignored) {
        }
        close();
        mainHandler.post(listener::onDisconnected);
    }

    private void postError(final String message) {
        mainHandler.post(() -> listener.onError(message));
    }
}