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
import android.os.Handler;
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

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener
{

    private enum Connected
    {False, Pending, True}

    private String deviceAddress;
    private SerialService service;

    private View sendBtn;
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

    private HashSet<String> not_delivered_messages = new HashSet<>();
    ;

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
    }

    @Override
    public void onDestroy()
    {
        if(connected != Connected.False) disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart()
    {
        super.onStart();
        if(service != null) service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop()
    {
        if(service != null && !getActivity().isChangingConfigurations()) service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation")
    // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity)
    {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach()
    {
        try
        {
            getActivity().unbindService(this);
        }
        catch(Exception ignored)
        {
        }
        super.onDetach();
    }

    @Override
    public void onResume()
    {
        super.onResume();
        if(initialStart && service != null)
        {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
        if(MySingletonClass.getInstance().gete22Change())
        {
//            send("@#config>A" + String.valueOf(MySingletonClass.getInstance().getairDataRate()) + "A", false);
//            MySingletonClass.getInstance().setE22change(false);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder)
    {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed())
        {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name)
    {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
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
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater)
    {


        MenuItem configChange = menu.add("Change E22 config");
        configChange.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener()
        {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem)
            {
                Intent intent = new Intent(getActivity().getApplicationContext(), ChangeConfig.class);
                startActivity(intent);

                return true;
            }
        });

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        int id = item.getItemId();
        if(id == R.id.clear)
        {
            receiveText.setText("");
            return true;
        }
        else if(id == R.id.newline)
        {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) ->
            {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        }
        else if(id == R.id.hex)
        {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        }
        else
        {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private void connect()
    {
        try
        {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("connecting...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        }
        catch(Exception e)
        {
            onSerialConnectError(e);
        }
    }

    private void disconnect()
    {
        connected = Connected.False;
        service.disconnect();
    }

    private HashMap<Integer, Integer> messageStartIndexMap = new HashMap<>();
    private HashMap<Integer, Integer> messageEndIndexMap = new HashMap<>();
    private HashSet<Integer> receivedMessageIds = new HashSet<>(); // To track received message IDs
    private volatile boolean isAcknowledged = false; // Global acknowledgment flag
    private volatile int currentMessageId = -1; // Tracks the current message ID being processed

    public TerminalFragment()
    {
    }


    private boolean isSending = false; // Track sending state
    private Thread sendingThread; // Thread for continuous sending

    private void sendButtonClicked()
    {
        if(!isSending)
        {
            // Start sending
            String message = sendText.getText().toString();
            if(!message.isEmpty())
            {
                isSending = true;
                currentMessageId = (int) (System.currentTimeMillis()); // Unique message ID
                String taggedMessage = message + "@@%%##ID= " + currentMessageId;

                sendText.setEnabled(false); // Disable input
                sendBtn.setBackgroundResource(R.drawable.baseline_cancel_schedule_send_24); // Change to "X" icon

                addToUi(message, currentMessageId); // Add message to UI

                sendingThread = new Thread(() ->
                {
                    try
                    {
                        while(isSending)
                        {
                            if(service != null)
                            {
                                service.write(("\"" + taggedMessage + newline).getBytes());
                            }
                            else
                            {
                                Log.e("TerminalFragment", "Service is null while sending message.");
                                break;
                            }

                            // Small sleep to prevent CPU overload
                            Thread.sleep(5000);
                        }
                    }
                    catch(IOException | InterruptedException e)
                    {
                        Log.e("TerminalFragment", "Error during continuous sending", e);
                    }
                });
                sendingThread.start();
            }
        }
        else
        {
            // Cancel sending
            stopSending();

            // Mark the message as failed (red color) in the UI
            if(currentMessageId != -1)
            {
                markMessageAsFailed(currentMessageId);
            }
        }
    }
    // Add a HashMap to track active blinking tasks

    private void stopSending()
    {
        isSending = false;
        if(sendingThread != null)
        {
            sendingThread.interrupt(); // Stop the thread
            sendingThread = null;
        }
        sendText.setEnabled(true); // Re-enable input
        sendBtn.setBackgroundResource(R.drawable.ic_send_white_24dp); // Change back to send icon
    }

    // Call this method when ACK is received
    private void handleAcknowledgment(int messageId)
    {
        if(messageId == currentMessageId)
        {
            stopSending(); // Stop sending upon receiving ACK
            markMessageAsAcknowledged(messageId); // Update UI
        }
    }

    private HashMap<Integer, Runnable> activeBlinkers = new HashMap<>();
    private HashMap<Integer, Handler> activeHandlers = new HashMap<>();

    private void addToUi(String message, int id_message)
    {
        // Add RTL marker for proper alignment of Persian/Arabic text
        String formattedMessage = "\u200F" + message + '\n';

        // Show the message in the UI aligned to the right
        getActivity().runOnUiThread(() ->
        {
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
            messageStartIndexMap.put(id_message, startIndex);
            messageEndIndexMap.put(id_message, endIndex);

            // Start blinking effect
            startBlinking(id_message, startIndex, endIndex);
        });
    }

    private void startBlinking(int id_message, int startIndex, int endIndex)
    {
        Handler handler = new Handler();
        activeHandlers.put(id_message, handler);

        Runnable blinker = new Runnable()
        {
            private boolean isHighlighted = false;

            @Override
            public void run()
            {
                Editable editableText = receiveText.getEditableText();
                if(messageStartIndexMap.containsKey(id_message) && messageEndIndexMap.containsKey(id_message))
                {
                    int start = messageStartIndexMap.get(id_message);
                    int end = messageEndIndexMap.get(id_message);

                    int color = isHighlighted ? Color.YELLOW : Color.WHITE; // Toggle between yellow and white
                    editableText.setSpan(new ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                    isHighlighted = !isHighlighted;
                    handler.postDelayed(this, 500); // Repeat every 500ms
                }
            }
        };
        activeBlinkers.put(id_message, blinker);
        handler.post(blinker); // Start the blinking effect
    }

    private void stopBlinking(int id_message)
    {
        if(activeBlinkers.containsKey(id_message))
        {
            Handler handler = activeHandlers.get(id_message);
            if(handler != null)
            {
                handler.removeCallbacks(activeBlinkers.get(id_message));
            }
            activeBlinkers.remove(id_message);
            activeHandlers.remove(id_message);
        }
    }

    private void markMessageAsAcknowledged(int id_message)
    {
        getActivity().runOnUiThread(() ->
        {
            if(messageStartIndexMap.containsKey(id_message) && messageEndIndexMap.containsKey(id_message))
            {
                int startIndex = messageStartIndexMap.get(id_message);
                int endIndex = messageEndIndexMap.get(id_message);
                Editable editableText = receiveText.getEditableText();
                editableText.setSpan(new ForegroundColorSpan(Color.GREEN), startIndex, endIndex, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                // Stop blinking
                stopBlinking(id_message);

                sendText.setText("");
            }
        });
    }

    private void markMessageAsFailed(int id_message)
    {
        getActivity().runOnUiThread(() ->
        {
            if(messageStartIndexMap.containsKey(id_message) && messageEndIndexMap.containsKey(id_message))
            {
                int startIndex = messageStartIndexMap.get(id_message);
                int endIndex = messageEndIndexMap.get(id_message);
                Editable editableText = receiveText.getEditableText();
                editableText.setSpan(new ForegroundColorSpan(Color.RED), startIndex, endIndex, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                // Stop blinking
                stopBlinking(id_message);
            }
        });
    }

    private void receive(ArrayDeque<byte[]> datas)
    {
        for(byte[] data : datas)
        {
            String msg = new String(data);
            if(msg.startsWith("\"") && msg.endsWith("\r\n"))
            {
                msg = msg.replaceFirst("\"", "").trim();

                try
                {
                    if(msg.contains("@#ack"))
                    {
                        // Handle acknowledgment message
                        String[] parts = msg.split("@@%%##ID= ");
                        if(parts.length < 2)
                        {
                            Log.e("TerminalFragment", "Invalid acknowledgment message: " + msg);
                            continue;
                        }
                        int ackId = Integer.parseInt(parts[1].trim());

                        if(ackId == currentMessageId && isSending)
                        {
                            handleAcknowledgment(ackId);
                        }
                    }
                    else if(msg.contains("@@%%##ID="))
                    {
                        // Handle incoming message (existing logic)
                        String[] parts = msg.split("@@%%##ID= ");
                        if(parts.length < 2)
                        {
                            Log.e("TerminalFragment", "Invalid message format: " + msg);
                            continue;
                        }
                        String realMessage = parts[0].trim();
                        int messageId = Integer.parseInt(parts[1].trim());

                        if(receivedMessageIds.contains(messageId))
                        {
                            continue; // Ignore already received messages
                        }
                        receivedMessageIds.add(messageId); // Mark this message as received

                        // Add the message to the UI
                        String formattedMessage = "\u200E" + realMessage + '\n';
                        getActivity().runOnUiThread(() ->
                        {
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
                        sendAcknowledgment(messageId);
                    }
                }
                catch(NumberFormatException e)
                {
                    Log.e("TerminalFragment", "NumberFormatException for message: " + msg, e);
                }
                catch(Exception e)
                {
                    Log.e("TerminalFragment", "Unexpected exception while processing message: " + msg, e);
                }
            }
        }
    }

    private void sendAcknowledgment(int messageId)
    {
        new Thread(() ->
        {
            for(int i = 1; i <= 3; i++)
            {
                try
                {
                    if(service != null)
                    {
                        String ackMessage = "@#ack@@%%##ID= " + messageId;
                        service.write(("\"" + ackMessage + newline).getBytes());
                        Thread.sleep(2500); // Delay of 1 second between retries
                    }
                }
                catch(Exception e)
                {
                    Log.e("TerminalFragment", "Error while sending acknowledgment for message ID: " + messageId, e);
                    break;
                }
            }
        }).start();
    }

    private void status(String str)
    {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect()
    {
        status("connected");
        connected = Connected.True;
    }

    @Override
    public void onSerialRead(byte[] data)
    {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas)
    {
        receive(datas);
    }


    private CountDownTimer reconnectTimer;
    private boolean reconnectingInProgress = false;

    @Override
    public void onSerialConnectError(Exception e)
    {
        status("Disconnected!");
        if(!reconnectingInProgress)
        {
            attemptReconnectWithTimeout();
        }
    }

    @Override
    public void onSerialIoError(Exception e)
    {
        status("disconnected");
        if(!reconnectingInProgress)
        {
            attemptReconnectWithTimeout();
        }
    }

    private void attemptReconnectWithTimeout()
    {
        reconnectingInProgress = true;
        final long totalTime = 20000;  // 20 seconds total
        final long interval = 5000;    // try every 5 seconds

        reconnectTimer = new CountDownTimer(totalTime, interval)
        {
            @Override
            public void onTick(long millisUntilFinished)
            {
                if(reconnect())
                {
                    reconnectingInProgress = false;
                    reconnectTimer.cancel();
                }

            }

            @Override
            public void onFinish()
            {
                // If we still haven't connected after 20 seconds
                if(connected != Connected.True)
                {
                    reconnectingInProgress = false;
                    status("Could not connect after 20 seconds, returning to previous screen...");
                    if(getActivity() != null)
                    {
                        getActivity().runOnUiThread(() -> getActivity().onBackPressed());
                    }
                }
            }
        }.start();
    }

    private boolean reconnect()
    {
        if(connected == Connected.True)
        {
            connected = Connected.False;
            try
            {
                disconnect(); // Ensure the current connection is cleanly closed
                Thread.sleep(100); // Small delay before reconnecting
                connect(); // Attempt to reconnect

                return true;
            }
            catch(Exception ex)
            {
                Log.e("TerminalFragment", "Reconnection failed", ex);

                return false;
            }
        }

        return false;
    }


}
