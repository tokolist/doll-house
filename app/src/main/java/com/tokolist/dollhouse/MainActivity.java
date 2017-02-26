package com.tokolist.dollhouse;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Bundle;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import android.os.Handler;

public class MainActivity extends AppCompatActivity {

    public final static UUID APP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    public final static int PICK_BLUETOOTH_DEVICE_REQUEST = 1;
    public final static int ENABLE_BLUETOOTH_REQUEST = 2;


    private final int BLUETOOTH_CONNECTED_MESSAGE = 1;
    private final int BLUETOOTH_CONNECT_ERROR_MESSAGE = 2;
    private final int BLUETOOTH_DISCONNECTED_MESSAGE = 3;
    private final int BLUETOOTH_READ_MESSAGE = 4;


    private static ConnectedThread connectedThread;
    private static StringBuilder bluetoothReadBuffer = new StringBuilder();
    private static Handler mHandler;

    private BluetoothAdapter mBluetoothAdapter;
    private Button connectButton;
    private List<BluetoothDevice> pairedDevices;
    private ProgressBar progressBar;

    @SuppressLint("UseSparseArrays")
    final Map<Integer,String> ledBtnIdsMap = new HashMap<Integer,String>(){{
        put(R.id.ledToggleButton1, "0");
        put(R.id.ledToggleButton2, "1");
        put(R.id.ledToggleButton3, "2");
        put(R.id.ledToggleButton4, "3");
    }};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        progressBar = (ProgressBar)findViewById(R.id.progressBar);

        connectButton = (Button)findViewById(R.id.connectButton);
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                connectButton.setEnabled(false);

                if(checkBluetooth()) {
                    if (isBluetoothConnectionAlive()) {
                        bluetoothDisconnect();
                    } else {
                        bluetoothConnect();
                    }
                } else {
                    connectButton.setEnabled(true);
                }
            }
        });


        initLedButtons();


        mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == BLUETOOTH_CONNECTED_MESSAGE) {
                    syncLedButtonsState();

                    Toast.makeText(getApplicationContext(),
                            String.format(getString(R.string.bluetooth_successfully_connected), msg.obj),
                            Toast.LENGTH_LONG).show();

                    connectButton.setText(getString(R.string.disconnect));
                    setLedButtonsEnabled(true);
                    connectButton.setEnabled(true);
                    progressBar.setVisibility(View.GONE);

                } else if (msg.what == BLUETOOTH_CONNECT_ERROR_MESSAGE) {
                    Toast.makeText(getApplicationContext(),
                            String.format(getString(R.string.bluetooth_cannot_connect), ((Throwable)msg.obj).getMessage()),
                            Toast.LENGTH_LONG).show();

                    connectButton.setEnabled(true);
                    progressBar.setVisibility(View.GONE);

                } else if (msg.what == BLUETOOTH_DISCONNECTED_MESSAGE) {
                    Toast.makeText(getApplicationContext(),
                            getString(R.string.bluetooth_successfully_disconnected),
                            Toast.LENGTH_LONG).show();

                    connectButton.setText(getString(R.string.connect));
                    setLedButtonsEnabled(false);
                    connectButton.setEnabled(true);

                } else if (msg.what == BLUETOOTH_READ_MESSAGE) {
                    String readString = new String((byte[])msg.obj, 0, msg.arg1);
                    bluetoothReadBuffer.append(readString);
                    int newLineIndex;
                    while ((newLineIndex = bluetoothReadBuffer.indexOf("\n")) > -1){
                        String command = bluetoothReadBuffer.substring(0, newLineIndex);
                        bluetoothReadBuffer.delete(0, newLineIndex+1);
                        performBluetoothCommand(command);
                    }

                }
            }
        };

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Toast.makeText(getApplicationContext(), getString(R.string.needs_bluetooth_support), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        checkBluetooth();

        if (isBluetoothConnectionAlive()) {
            connectButton.setText(getString(R.string.disconnect));
            setLedButtonsEnabled(true);
            syncLedButtonsState();
        } else {
            setLedButtonsEnabled(false);
        }
    }

    private boolean isBluetoothConnectionAlive() {
        return connectedThread != null && connectedThread.isAlive();
    }

    private void syncLedButtonsState() {
        Set<String> uniqueLedIds = new HashSet<>(ledBtnIdsMap.values());
        for (String ledId : uniqueLedIds) {
            String command = "LQS" + ledId + "\n";
            connectedThread.write(command.getBytes());
        }
    }

    private boolean checkBluetooth() {
        if (!mBluetoothAdapter.isEnabled()) {
            bluetoothDisconnect();
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST);
            return false;
        }

        return true;
    }

    private void bluetoothConnect() {
        List<String> pairedDeviceNames = new ArrayList<>();
        pairedDevices = new ArrayList<>(mBluetoothAdapter.getBondedDevices());
        for (BluetoothDevice device : pairedDevices) {
            pairedDeviceNames.add(device.getName() + " (" + device.getAddress() + ")");
        }

        Intent bluetoothDeviceIntent = new Intent(getApplicationContext(), BluetoothDeviceListActivity.class);
        bluetoothDeviceIntent.putExtra("pairedDevices", pairedDeviceNames.toArray(new String[pairedDeviceNames.size()]));
        startActivityForResult(bluetoothDeviceIntent, PICK_BLUETOOTH_DEVICE_REQUEST);
    }

    private void bluetoothDisconnect() {
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
    }

    private void performBluetoothCommand(String command) {
        //command is invalid
        if (command.length() < 3) {
            return;
        }

        String commandId = command.substring(0,3);

        if (commandId.equals("LSC") || commandId.equals("LSR")){
            //command is invalid
            if (command.length() < 6) {
                return;
            }

            String ledId = command.substring(3,4);
            String ledState = command.substring(5,6);

            for (int btnId : ledBtnIdsMap.keySet()) {
                if (ledBtnIdsMap.get(btnId).equals(ledId)) {
                    ToggleButton btn = (ToggleButton)findViewById(btnId);
                    btn.setChecked(ledState.equals("1"));
                }
            }
        }
    }

    private void initLedButtons() {
        for (Integer id : ledBtnIdsMap.keySet()) {
            ToggleButton btn = (ToggleButton)findViewById(id);

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (checkBluetooth()) {
                        ToggleButton btn = (ToggleButton)view;
                        String command = "LSS" + ledBtnIdsMap.get(btn.getId()) + "S" + (btn.isChecked() ? "1" : "0") + "\n";
                        connectedThread.write(command.getBytes());
                    }
                }
            });
        }
    }

    private void setLedButtonsEnabled(boolean enabled) {
        for (Integer id : ledBtnIdsMap.keySet()) {
            ToggleButton btn = (ToggleButton) findViewById(id);
            btn.setEnabled(enabled);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_BLUETOOTH_DEVICE_REQUEST) {
            if (resultCode == RESULT_OK) {
                progressBar.setVisibility(View.VISIBLE);
                BluetoothDevice bluetoothDevice = pairedDevices.get(data.getIntExtra("deviceIndex", 0));
                ConnectThread connectThread = new ConnectThread(bluetoothDevice);
                connectThread.start();
            } else {
                connectButton.setEnabled(true);
            }
        } else if (requestCode == ENABLE_BLUETOOTH_REQUEST) {
            if (resultCode != RESULT_OK) {
                Toast.makeText(getApplicationContext(), getString(R.string.needs_bluetooth_connection), Toast.LENGTH_LONG).show();
            }
        }
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            BluetoothSocket tmp = null;
            mmDevice = device;
            try {
                tmp = device.createRfcommSocketToServiceRecord(APP_UUID);
            } catch (IOException e) { }
            mmSocket = tmp;
        }

        @Override
        public void run() {
            try {
                mmSocket.connect();
            } catch (IOException connectException) {
                try {
                    mmSocket.close();
                } catch (IOException closeException) { }

                mHandler.obtainMessage(BLUETOOTH_CONNECT_ERROR_MESSAGE, connectException).sendToTarget();
                return;
            }

            connectedThread = new ConnectedThread(mmSocket);
            connectedThread.start();

            mHandler.obtainMessage(BLUETOOTH_CONNECTED_MESSAGE, mmDevice.getName()).sendToTarget();
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) { }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) { }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            // Keep listening to the InputStream until an exception occurs
            while (true) {
                try {
                    byte[] buffer = new byte[1024];
                    int bytes = mmInStream.read(buffer);
                    // Send the obtained bytes to the UI activity
                    mHandler.obtainMessage(BLUETOOTH_READ_MESSAGE, bytes, -1, buffer)
                            .sendToTarget();
                } catch (IOException e) {
                    break;
                }
            }
        }

        /* Call this from the main activity to send data to the remote device */
        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);
            } catch (IOException e) { }
        }

        /* Call this from the main activity to shutdown the connection */
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) { }
            mHandler.obtainMessage(BLUETOOTH_DISCONNECTED_MESSAGE).sendToTarget();
        }
    }
}
