package de.kai_morich.simple_bluetooth_terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }

    private String deviceAddress;
    private SerialService service;

    private View sendBtn;
    private TextView receiveText;
    private TextView sendText;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private String newline = TextUtil.newline_crlf, time = "";

    private int id_messsage = 0;
    private HashSet<String> not_delivered_messages = new HashSet<>();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False) disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (service != null) service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind
    }

    @Override
    public void onStop() {
        if (service != null && !getActivity().isChangingConfigurations()) service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try {
            getActivity().unbindService(this);
        } catch (Exception ignored) {}
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
            // If you have logic for sending config changes, place it here
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

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText));
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> sendButtonClicked());
        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        MenuItem configChange = menu.add("Change E22 config");
        configChange.setOnMenuItemClickListener(menuItem -> {
            Intent intent = new Intent(getActivity().getApplicationContext(), ChangeConfig.class);
            startActivity(intent);
            return true;
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
    private HashSet<Integer> receivedMessageIds = new HashSet<>();
    private volatile int currentMessageId = -1;

    public TerminalFragment() {}

    private boolean isSending = false;
    private Thread sendingThread;

    private ArrayList<String> messageQueue = new ArrayList<>();
    private int currentMessageIndex = 0;

    private HashMap<Integer, Runnable> activeBlinkers = new HashMap<>();
    private HashMap<Integer, Handler> activeHandlers = new HashMap<>();

    private boolean isPersianFirstChar(String text) {
        if (text == null || text.isEmpty()) return false;
        char firstChar = text.charAt(0);
        return (firstChar >= '\u0600' && firstChar <= '\u06FF');
    }

    // Updated splitMessageIntoChunks to respect character boundaries:
    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private ArrayList<String> splitMessageIntoChunks(String message, int maxBytes) {
        ArrayList<String> chunks = new ArrayList<>();
        int startIndex = 0;
        int length = message.length();

        while (startIndex < length) {
            int endIndex = startIndex + 1; // at least one character
            int lastValidEndIndex = startIndex; // track the last valid breakpoint
            while (endIndex <= length) {
                String sub = message.substring(startIndex, endIndex);
                byte[] subBytes = sub.getBytes(StandardCharsets.UTF_8);
                if (subBytes.length <= maxBytes) {
                    lastValidEndIndex = endIndex; // this is good so far
                    endIndex++;
                } else {
                    break;
                }
            }

            // Use the last valid end index
            String chunk = message.substring(startIndex, lastValidEndIndex);
            chunks.add(chunk);
            startIndex = lastValidEndIndex;
        }

        return chunks;
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private void sendButtonClicked() {
        if (isSending) {
            // Cancel sending the current message
            stopSending();
            if (currentMessageId != -1) {
                markMessageAsFailed(currentMessageId);
            }
            return;
        }

        String fullMessage = sendText.getText().toString();
        if (fullMessage.isEmpty()) {
            return; // no message
        }

        // Clear previous queue
        messageQueue.clear();
        currentMessageIndex = 0;

        // Check size and split if needed
        byte[] fullMessageBytes = fullMessage.getBytes(StandardCharsets.UTF_8);
        if (fullMessageBytes.length > 90) {
            // Split into multiple chunks of up to 90 bytes
            messageQueue.addAll(splitMessageIntoChunks(fullMessage, 90));
        } else {
            // Just one chunk
            messageQueue.add(fullMessage);
        }

        // Start sending the first chunk if any
        if (!messageQueue.isEmpty()) {
            sendChunk(messageQueue.get(currentMessageIndex));
        }
    }

    private void sendChunk(String message) {
        isSending = true;

        // Create a unique message ID for this chunk
        currentMessageId = (int) (System.currentTimeMillis());
        String taggedMessage = message + "@@%%##ID= " + currentMessageId;

        sendText.setEnabled(false);
        sendBtn.setBackgroundResource(R.drawable.baseline_cancel_schedule_send_24);
        addToUi(message, currentMessageId);

        sendingThread = new Thread(() -> {
            try {
                while (isSending) {
                    if (service != null) {
                        service.write(("\"" + taggedMessage + newline).getBytes());
                    } else {
                        Log.e("TerminalFragment", "Service is null while sending message.");
                        break;
                    }
                    Thread.sleep(5000);
                }
            } catch (IOException | InterruptedException e) {
                Log.e("TerminalFragment", "Error during continuous sending", e);
            }
        });
        sendingThread.start();
    }

    private void stopSending() {
        isSending = false;
        if (sendingThread != null) {
            sendingThread.interrupt();
            sendingThread = null;
        }
        sendText.setEnabled(true);
        sendBtn.setBackgroundResource(R.drawable.ic_send_white_24dp);
    }

    private void handleAcknowledgment(int messageId) {
        if (messageId == currentMessageId) {
            stopSending();
            markMessageAsAcknowledged(messageId);

            // Move on to the next chunk if available
            currentMessageIndex++;
            if (currentMessageIndex < messageQueue.size()) {
                // Send next chunk
                sendChunk(messageQueue.get(currentMessageIndex));
            } else {
                // All chunks sent
                sendText.setText("");
            }
        }
    }

    private void addToUi(String message, int id_message) {
        boolean persian = isPersianFirstChar(message);
        String formattedMessage = message + '\n';
        getActivity().runOnUiThread(() -> {
            SpannableStringBuilder spn = new SpannableStringBuilder(formattedMessage);
            spn.setSpan(new ForegroundColorSpan(Color.WHITE), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            spn.setSpan(new AbsoluteSizeSpan(60), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (persian) {
                // Persian message sent -> align opposite
                spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                // Non-Persian message sent -> align normal
                spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            int startIndex = receiveText.getText().length();
            receiveText.append(spn);

            time = new SimpleDateFormat("HH:mm:ss").format(new Date());
            SpannableStringBuilder timeSpn = new SpannableStringBuilder(time + "\n");
            timeSpn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), 0, timeSpn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            timeSpn.setSpan(new AbsoluteSizeSpan(25), 0, timeSpn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            timeSpn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorTimeText)), 0, timeSpn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(timeSpn);

            int endIndex = receiveText.getText().length();
            messageStartIndexMap.put(id_message, startIndex);
            messageEndIndexMap.put(id_message, endIndex);

            // Start blinking
            startBlinking(id_message, startIndex, endIndex);
        });
    }

    private void startBlinking(int id_message, int startIndex, int endIndex) {
        Handler handler = new Handler();
        activeHandlers.put(id_message, handler);

        Runnable blinker = new Runnable() {
            private boolean isHighlighted = false;
            @Override
            public void run() {
                Editable editableText = receiveText.getEditableText();
                if (messageStartIndexMap.containsKey(id_message) && messageEndIndexMap.containsKey(id_message)) {
                    int start = messageStartIndexMap.get(id_message);
                    int end = messageEndIndexMap.get(id_message);

                    int color = isHighlighted ? Color.YELLOW : Color.WHITE;
                    editableText.setSpan(new ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                    isHighlighted = !isHighlighted;
                    handler.postDelayed(this, 500);
                }
            }
        };
        activeBlinkers.put(id_message, blinker);
        handler.post(blinker);
    }

    private void stopBlinking(int id_message) {
        if (activeBlinkers.containsKey(id_message)) {
            Handler handler = activeHandlers.get(id_message);
            if (handler != null) {
                handler.removeCallbacks(activeBlinkers.get(id_message));
            }
            activeBlinkers.remove(id_message);
            activeHandlers.remove(id_message);
        }
    }

    private void markMessageAsAcknowledged(int id_message) {
        getActivity().runOnUiThread(() -> {
            if (messageStartIndexMap.containsKey(id_message) && messageEndIndexMap.containsKey(id_message)) {
                int startIndex = messageStartIndexMap.get(id_message);
                int endIndex = messageEndIndexMap.get(id_message);
                Editable editableText = receiveText.getEditableText();
                editableText.setSpan(new ForegroundColorSpan(Color.GREEN), startIndex, endIndex, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                // Stop blinking
                stopBlinking(id_message);
            }
        });
    }

    private void markMessageAsFailed(int id_message) {
        getActivity().runOnUiThread(() -> {
            if (messageStartIndexMap.containsKey(id_message) && messageEndIndexMap.containsKey(id_message)) {
                int startIndex = messageStartIndexMap.get(id_message);
                int endIndex = messageEndIndexMap.get(id_message);
                Editable editableText = receiveText.getEditableText();
                editableText.setSpan(new ForegroundColorSpan(Color.RED), startIndex, endIndex, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                // Stop blinking
                stopBlinking(id_message);
            }
        });
    }

    private void receive(ArrayDeque<byte[]> datas) {
        for (byte[] data : datas) {
            String msg = new String(data);
            if (msg.startsWith("\"") && msg.endsWith("\r\n")) {
                msg = msg.replaceFirst("\"", "").trim();

                try {
                    if (msg.contains("@#ack")) {
                        // Acknowledgment received
                        String[] parts = msg.split("@@%%##ID= ");
                        if (parts.length < 2) {
                            Log.e("TerminalFragment", "Invalid acknowledgment message: " + msg);
                            continue;
                        }
                        int ackId = Integer.parseInt(parts[1].trim());
                        if (ackId == currentMessageId && isSending) {
                            handleAcknowledgment(ackId);
                        }
                    } else if (msg.contains("@@%%##ID=")) {
                        // Incoming message
                        String[] parts = msg.split("@@%%##ID= ");
                        if (parts.length < 2) {
                            Log.e("TerminalFragment", "Invalid message format: " + msg);
                            continue;
                        }
                        String realMessage = parts[0].trim();
                        int messageId = Integer.parseInt(parts[1].trim());

                        if (receivedMessageIds.contains(messageId)) {
                            continue;
                        }
                        receivedMessageIds.add(messageId);
                        boolean persian = isPersianFirstChar(realMessage);
                        String formattedMessage = realMessage + '\n';
                        getActivity().runOnUiThread(() -> {
                            SpannableStringBuilder spn = new SpannableStringBuilder(formattedMessage);
                            spn.setSpan(new ForegroundColorSpan(Color.WHITE), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            spn.setSpan(new AbsoluteSizeSpan(60), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            if (persian) {
                                // Persian message received -> align normal
                                spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            } else {
                                // Non-Persian message received -> align opposite
                                spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            }
                            receiveText.append(spn);

                            time = new SimpleDateFormat("HH:mm:ss").format(new Date());
                            SpannableStringBuilder timeSpn = new SpannableStringBuilder(time + "\n");
                            timeSpn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL), 0, timeSpn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            timeSpn.setSpan(new AbsoluteSizeSpan(25), 0, timeSpn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            timeSpn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorTimeText)), 0, timeSpn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            receiveText.append(timeSpn);
                        });

                        sendAcknowledgment(messageId);
                    }
                } catch (NumberFormatException e) {
                    Log.e("TerminalFragment", "NumberFormatException for message: " + msg, e);
                } catch (Exception e) {
                    Log.e("TerminalFragment", "Unexpected exception: " + msg, e);
                }
            }
        }
    }

    private void sendAcknowledgment(int messageId) {
        new Thread(() -> {
            for (int i = 1; i <= 3; i++) {
                try {
                    if (service != null) {
                        String ackMessage = "@#ack@@%%##ID= " + messageId;
                        service.write(("\"" + ackMessage + newline).getBytes());
                        Thread.sleep(2500);
                    }
                } catch (Exception e) {
                    Log.e("TerminalFragment", "Error sending ACK for ID: " + messageId, e);
                    break;
                }
            }
        }).start();
    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
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

    private CountDownTimer reconnectTimer;
    private boolean reconnectingInProgress = false;

    @Override
    public void onSerialConnectError(Exception e) {
        status("disconnected");
        if (!reconnectingInProgress) {
            attemptReconnectWithTimeout();
        }
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("disconnected");
        if (!reconnectingInProgress) {
            attemptReconnectWithTimeout();
        }
    }

    private void attemptReconnectWithTimeout() {
        reconnectingInProgress = true;
        final long totalTime = 30000;  // 20 seconds
        final long interval = 5000;    // 5 seconds interval

        reconnectTimer = new CountDownTimer(totalTime, interval) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (reconnect()) {
                    reconnectingInProgress = false;
                    reconnectTimer.cancel();
                }
            }

            @Override
            public void onFinish() {
                if (connected != Connected.True) {
                    reconnectingInProgress = false;
                    status("Could not connect after 20 seconds, returning...");
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> getActivity().onBackPressed());
                    }
                }
            }
        }.start();
    }

    private boolean reconnect() {
        if (connected == Connected.True) {
            connected = Connected.False;
            try {
                disconnect();
                Thread.sleep(100);
                connect();
                return true;
            } catch (Exception ex) {
                Log.e("TerminalFragment", "Reconnection failed", ex);
                return false;
            }
        }
        return false;
    }
}
