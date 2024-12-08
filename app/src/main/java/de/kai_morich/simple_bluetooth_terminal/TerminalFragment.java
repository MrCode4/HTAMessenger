package de.kai_morich.simple_bluetooth_terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.text.Editable;
import android.text.Layout;
import android.text.Layout.Alignment;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected {False, Pending, True}

    private String deviceAddress;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf, time = "";

    private int id_messsage = 0;

    // private boolean WaitingDelivery = false;

    private HashSet<String> not_delivered_messages = new HashSet<>();;
    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if (service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation")
    // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try {
            getActivity().unbindService(this);
        } catch (Exception ignored) {
        }
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
        if (MySingletonClass.getInstance().gete22Change()) {
            send("@#config>A" + String.valueOf(MySingletonClass.getInstance().getairDataRate()) + "A", false);
            MySingletonClass.getInstance().setE22change(false);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if (initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString(), true));
        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {


        MenuItem configChange = menu.add("Change E22 config");
        configChange.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                Intent intent = new Intent(getActivity().getApplicationContext(), ChangeConfig.class);
                startActivity(intent);

                return true;
            }
        });

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("connecting...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    private HashMap<Integer, Integer> messageStartIndexMap = new HashMap<>();
    private HashMap<Integer, Integer> messageEndIndexMap = new HashMap<>();
    private HashMap<Integer, String> pendingMessageMap = new HashMap<>(); // To store messages with their IDs
    private HashSet<Integer> receivedMessageIds = new HashSet<>(); // To track received message IDs

    public TerminalFragment() {
        // Start a background thread to periodically check for unacknowledged messages
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(3000); // Check every 3 seconds
                    for (Integer messageId : new HashSet<>(pendingMessageMap.keySet())) {
                        String message = pendingMessageMap.get(messageId);
                        if (message != null) {
                            String taggedMessage = message + "@@%%##ID= " + messageId;
                            try {
                                if (service != null) {
                                    service.write(("\"" + taggedMessage + newline).getBytes());
                                } else {
                                    Log.e("TerminalFragment", "Service is null while resending message ID: " + messageId);
                                }
                            } catch (IOException e) {
                                Log.e("TerminalFragment", "IOException while resending message ID: " + messageId, e);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Log.e("TerminalFragment", "Thread interrupted", e);
                } catch (Exception e) {
                    Log.e("TerminalFragment", "Unexpected exception in resend thread", e);
                }
            }
        }).start();
    }

    private void send(String message, boolean showOnUi) {
        if (connected != Connected.True) {
            Toast.makeText(getActivity(), "Not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        if (message.isEmpty()) {
            return;
        }

        try {
            String msgToSend = message + newline;
            if (showOnUi) {
                int id_messsage = (int) (System.currentTimeMillis()); // Calculate message ID based on current time
                String taggedMessage = message + "@@%%##ID= " + id_messsage;

                // Add RTL marker for proper alignment of Persian/Arabic text
                String formattedMessage = "\u200F" + message + '\n';

                // Show the message in the UI aligned to the right
                getActivity().runOnUiThread(() -> {
                    SpannableStringBuilder spn = new SpannableStringBuilder(formattedMessage);
                    spn.setSpan(new ForegroundColorSpan(Color.WHITE), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spn.setSpan(new AbsoluteSizeSpan(60), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    int startIndex = receiveText.getText().length();
                    receiveText.append(spn);

                    time = new SimpleDateFormat("HH:mm:ss").format(new Date());
                    SpannableStringBuilder timeSpn = new SpannableStringBuilder(time + "\n");
                    timeSpn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), 0, timeSpn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    timeSpn.setSpan(new AbsoluteSizeSpan(25), 0, timeSpn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    timeSpn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorTimeText)), 0, timeSpn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    receiveText.append(timeSpn);

                    int endIndex = receiveText.getText().length();
                    messageStartIndexMap.put(id_messsage, startIndex);
                    messageEndIndexMap.put(id_messsage, endIndex);
                });

                pendingMessageMap.put(id_messsage, message);
                if (service != null) {
                    service.write(("\"" + taggedMessage + newline).getBytes());
                }

                // Clear the input field
                sendText.setText("");
            } else {
                // Ensure the message is for acknowledgment before attempting to send it multiple times
                if (message.startsWith("@#ack")) {
                    new Thread(() -> {
                        for (int i = 0; i < 10; i++) {
                            try {
                                if (service != null) {
                                    service.write(("\"" + msgToSend).getBytes());
                                }
                                Thread.sleep(500);
                            } catch (Exception e) {
                                Log.e("TerminalFragment", "Error sending acknowledgment", e);
                                break;
                            }
                        }
                    }).start();
                }
            }
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private void receive(ArrayDeque<byte[]> datas) {
        for (byte[] data : datas) {
            String msg = new String(data);
            if (msg.startsWith("\"") && msg.endsWith("\r\n")) {
                msg = msg.replaceFirst("\"", "").trim();

                try {
                    if (msg.contains("@#ack")) {
                        // Handle acknowledgment message first
                        String[] parts = msg.split("@@%%##ID= ");
                        if (parts.length < 2) {
                            Log.e("TerminalFragment", "Invalid acknowledgment message: " + msg);
                            continue;
                        }
                        int ackId = Integer.parseInt(parts[1].trim());

                        // Find and highlight the acknowledged message by ID
                        if (messageStartIndexMap.containsKey(ackId) && messageEndIndexMap.containsKey(ackId)) {
                            getActivity().runOnUiThread(() -> {
                                int startIndex = messageStartIndexMap.get(ackId);
                                int endIndex = messageEndIndexMap.get(ackId);
                                Editable editableText = receiveText.getEditableText();
                                editableText.setSpan(new ForegroundColorSpan(Color.GREEN), startIndex, endIndex, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                                // Remove the acknowledged message from the maps
                                messageStartIndexMap.remove(ackId);
                                messageEndIndexMap.remove(ackId);
                                pendingMessageMap.remove(ackId);

                                // Play acknowledgment sound
                                MediaPlayer deliveredSound = MediaPlayer.create(getActivity().getApplicationContext(), R.raw.delivery);
                                deliveredSound.start();
                            });
                        }
                    } else if (msg.contains("@@%%##ID=")) {
                        String[] parts = msg.split("@@%%##ID= ");
                        if (parts.length < 2) {
                            Log.e("TerminalFragment", "Invalid message format: " + msg);
                            continue;
                        }
                        String realMessage = parts[0].trim();
                        int messageId = Integer.parseInt(parts[1].trim());

                        if (receivedMessageIds.contains(messageId)) {
                            // Message already received; send acknowledgment only
                            send("@#ack@@%%##ID= " + messageId, false);
                            continue;
                        }

                        receivedMessageIds.add(messageId); // Mark this message as received

                        // Add LTR marker for proper alignment of non-Persian/Arabic text
                        String formattedMessage = "\u200E" + realMessage + '\n';

                        // Show the received message in the UI aligned to the left
                        getActivity().runOnUiThread(() -> {
                            SpannableStringBuilder spn = new SpannableStringBuilder(formattedMessage);
                            spn.setSpan(new ForegroundColorSpan(Color.WHITE), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            spn.setSpan(new AbsoluteSizeSpan(60), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            receiveText.append(spn);

                            time = new SimpleDateFormat("HH:mm:ss").format(new Date());
                            SpannableStringBuilder timeSpn = new SpannableStringBuilder(time + "\n");
                            timeSpn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL), 0, timeSpn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            timeSpn.setSpan(new AbsoluteSizeSpan(25), 0, timeSpn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            timeSpn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorTimeText)), 0, timeSpn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            receiveText.append(timeSpn);
                        });

                        // Send acknowledgment
                        send("@#ack@@%%##ID= " + messageId, false);
                    } else if (msg.contains("@#config")) {
                        // Handle configuration message (do nothing)
                        Log.d("TerminalFragment", "Config message received: " + msg);
                    }
                } catch (NumberFormatException e) {
                    Log.e("TerminalFragment", "NumberFormatException for message: " + msg, e);
                } catch (Exception e) {
                    Log.e("TerminalFragment", "Unexpected exception while processing message: " + msg, e);
                }
            }
        }
    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
        getActivity().onBackPressed();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
        getActivity().onBackPressed();

    }

}
