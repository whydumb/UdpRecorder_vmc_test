import com.illposed.osc.*;
import com.illposed.osc.transport.*;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

public class UdpRecorder {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            var frame = new UIFrame();
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.pack();
            frame.setVisible(true);
        });
    }

    /**
     * OSC 메시지 데이터
     */
    private static class OSCMessageData implements Serializable {
        public final String address;
        public final Object[] arguments;
        public final long timestamp;

        public OSCMessageData(String address, Object[] arguments, long timestamp) {
            this.address = address;
            this.arguments = arguments;
            this.timestamp = timestamp;
        }

        public void serialize(DataOutputStream output) throws IOException {
            output.writeLong(timestamp);
            output.writeUTF(address);
            output.writeInt(arguments.length);
            
            for (Object arg : arguments) {
                if (arg instanceof String) {
                    output.writeInt(0); // String type
                    output.writeUTF((String) arg);
                } else if (arg instanceof Integer) {
                    output.writeInt(1); // Integer type
                    output.writeInt((Integer) arg);
                } else if (arg instanceof Float) {
                    output.writeInt(2); // Float type
                    output.writeFloat((Float) arg);
                } else if (arg instanceof Double) {
                    output.writeInt(3); // Double type
                    output.writeDouble((Double) arg);
                } else {
                    output.writeInt(0); // Default to string
                    output.writeUTF(arg.toString());
                }
            }
        }

        public static OSCMessageData deserialize(DataInputStream input) throws IOException {
            long timestamp = input.readLong();
            String address = input.readUTF();
            int argCount = input.readInt();
            
            Object[] arguments = new Object[argCount];
            for (int i = 0; i < argCount; i++) {
                int type = input.readInt();
                switch (type) {
                    case 0: // String
                        arguments[i] = input.readUTF();
                        break;
                    case 1: // Integer
                        arguments[i] = input.readInt();
                        break;
                    case 2: // Float
                        arguments[i] = input.readFloat();
                        break;
                    case 3: // Double
                        arguments[i] = input.readDouble();
                        break;
                    default:
                        arguments[i] = input.readUTF();
                }
            }
            
            return new OSCMessageData(address, arguments, timestamp);
        }

        @Override
        public String toString() {
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");
            String timeStr = timeFormat.format(new Date(System.currentTimeMillis() - timestamp / 1_000_000));
            StringBuilder argsStr = new StringBuilder();
            
            for (int i = 0; i < arguments.length; i++) {
                if (i > 0) argsStr.append(", ");
                Object arg = arguments[i];
                if (arg instanceof String) {
                    argsStr.append("\"").append(arg).append("\"");
                } else {
                    argsStr.append(arg.toString());
                }
            }
            
            return "[" + timeStr + "] " + address + " -> [" + argsStr + "]";
        }
    }

    /**
     * OSC 메시지 레코더
     */
    private static class OSCMessageRecorder implements AutoCloseable {
        private OSCPortIn oscPort;
        private final ArrayList<OSCMessageData> messages = new ArrayList<>();
        private volatile boolean isRecording = false;
        private long startTime;
        private final Logger logger = Logger.getLogger(OSCMessageRecorder.class.getName());
        private final int port;

        public OSCMessageRecorder(int port) throws SocketException, IOException {
            this.port = port;
            try {
                oscPort = new OSCPortIn(port);
                oscPort.addPacketListener(new OSCPacketListener() {
                    @Override
                    public void handlePacket(OSCPacketEvent event) {
                        if (event.getPacket() instanceof OSCMessage && isRecording) {
                            OSCMessage msg = (OSCMessage) event.getPacket();
                            long timestamp = System.nanoTime() - startTime;
                            
                            OSCMessageData msgData = new OSCMessageData(
                                msg.getAddress(),
                                msg.getArguments().toArray(),
                                timestamp
                            );
                            
                            synchronized (messages) {
                                messages.add(msgData);
                            }
                            
                            // 실시간 출력 (최근 VMC 메시지만)
                            if (msg.getAddress().startsWith("/VMC/")) {
                                System.out.println(msgData);
                            }
                        }
                    }

                    @Override
                    public void handleBadData(OSCBadDataEvent event) {
                        logger.warning("Bad OSC data received");
                    }
                });
            } catch (SocketException e) {
                logger.severe("Failed to create OSC port: " + e.getMessage());
                throw e;
            } catch (IOException e) {
                logger.severe("Failed to create OSC port: " + e.getMessage());
                throw e;
            }
        }

        public void start() throws IOException {
            if (isRecording) return;
            
            isRecording = true;
            startTime = System.nanoTime();
            oscPort.startListening();
            logger.info("OSC recording started on port " + port);
        }

        public void stop() {
            if (!isRecording) return;
            
            isRecording = false;
            if (oscPort != null) {
                oscPort.stopListening();
            }
            logger.info("OSC recording stopped. Captured " + messages.size() + " messages");
        }

        public ArrayList<OSCMessageData> getMessages() {
            synchronized (messages) {
                return new ArrayList<>(messages);
            }
        }

        public void clearMessages() {
            synchronized (messages) {
                messages.clear();
            }
        }

        @Override
        public void close() {
            stop();
            if (oscPort != null) {
                try {
                    oscPort.close();
                } catch (IOException e) {
                    logger.warning("Error closing OSC port: " + e.getMessage());
                }
            }
        }
    }

    /**
     * OSC 메시지 재생기
     */
    private static class OSCReplayer implements AutoCloseable {
        private OSCPortOut oscPort;
        private final ArrayList<OSCMessageData> messages;
        private volatile boolean isReplaying = false;
        private Thread replayThread;
        private final Logger logger = Logger.getLogger(OSCReplayer.class.getName());

        public OSCReplayer(String host, int port, ArrayList<OSCMessageData> messages) 
                throws SocketException, UnknownHostException, IOException {
            this.messages = messages;
            try {
                this.oscPort = new OSCPortOut(InetAddress.getByName(host), port);
            } catch (Exception e) {
                logger.severe("Failed to create OSC output port: " + e.getMessage());
                throw e;
            }
        }

        public void start() {
            if (isReplaying || messages.isEmpty()) return;
            
            isReplaying = true;
            replayThread = new Thread(() -> {
                try {
                    oscPort.connect();
                    logger.info("OSC replay started with " + messages.size() + " messages");
                    
                    long baseTime = System.nanoTime();
                    int currentIndex = 0;

                    while (isReplaying && currentIndex < messages.size()) {
                        long currentTime = System.nanoTime() - baseTime;
                        
                        while (currentIndex < messages.size()) {
                            OSCMessageData msgData = messages.get(currentIndex);
                            
                            if (msgData.timestamp > currentTime) {
                                break;
                            }
                            
                            try {
                                // OSC 메시지 생성 및 전송
                                OSCMessage msg = new OSCMessage(msgData.address, 
                                    Arrays.asList(msgData.arguments));
                                oscPort.send(msg);
                                
                                // VMC 메시지만 콘솔 출력
                                if (msgData.address.startsWith("/VMC/")) {
                                    System.out.println("▶ " + msgData);
                                }
                                
                            } catch (Exception e) {
                                logger.warning("Error sending OSC message: " + e.getMessage());
                            }
                            
                            currentIndex++;
                            currentTime = System.nanoTime() - baseTime;
                        }
                        
                        // 다음 메시지까지 대기
                        if (currentIndex < messages.size()) {
                            OSCMessageData nextMsg = messages.get(currentIndex);
                            long waitTime = nextMsg.timestamp - currentTime;
                            
                            if (waitTime > 0) {
                                if (waitTime > 2_000_000) { // 2ms 이상이면 sleep
                                    LockSupport.parkNanos(waitTime);
                                } else { // 짧은 시간은 busy wait
                                    long targetTime = System.nanoTime() + waitTime;
                                    while (System.nanoTime() < targetTime && isReplaying);
                                }
                            }
                        }
                    }
                    
                } catch (Exception e) {
                    logger.severe("Error during OSC replay: " + e.getMessage());
                } finally {
                    try {
                        oscPort.disconnect();
                    } catch (Exception e) {
                        logger.warning("Error disconnecting OSC port: " + e.getMessage());
                    }
                    logger.info("OSC replay finished");
                }
            });
            
            replayThread.setDaemon(true);
            replayThread.start();
        }

        public void stop() {
            isReplaying = false;
            if (replayThread != null) {
                LockSupport.unpark(replayThread);
            }
        }

        @Override
        public void close() {
            stop();
            if (oscPort != null) {
                try {
                    oscPort.close();
                } catch (IOException e) {
                    logger.warning("Error closing OSC output port: " + e.getMessage());
                }
            }
        }
    }

    /**
     * UI 프레임
     */
    private static class UIFrame extends JFrame {
        private final Logger logger = Logger.getLogger("UIFrame");

        private final JButton recordButton = new JButton("Record OSC");
        private final JButton replayButton = new JButton("Replay OSC");
        private final JButton stopButton = new JButton("Stop");
        private final JButton loadButton = new JButton("Load");
        private final JButton saveButton = new JButton("Save");
        private final JTextField portField = new JTextField("39539");
        private final JTextField hostField = new JTextField("127.0.0.1");
        private final JLabel statusLabel = new JLabel("Ready");
        private final JTextArea messageArea = new JTextArea(15, 50);

        private ArrayList<OSCMessageData> recordedMessages = null;
        private Status status = new Status.Idle();

        private sealed interface Status {
            final class Idle implements Status {}
            record Recording(OSCMessageRecorder recorder) implements Status {}
            record Replaying(OSCReplayer replayer) implements Status {}
        }

        public UIFrame() {
            super("OSC Message Recorder/Player");
            setupUI();
            updateStatus();
        }

        private void setupUI() {
            setLayout(new BorderLayout());

            // 상단 상태 패널
            JPanel statusPanel = new JPanel(new FlowLayout());
            statusPanel.add(new JLabel("Status: "));
            statusPanel.add(statusLabel);
            add(statusPanel, BorderLayout.NORTH);

            // 중앙 메시지 영역
            messageArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            messageArea.setEditable(false);
            JScrollPane scrollPane = new JScrollPane(messageArea);
            scrollPane.setBorder(BorderFactory.createTitledBorder("OSC Messages"));
            add(scrollPane, BorderLayout.CENTER);

            // 하단 컨트롤 패널
            JPanel controlPanel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);

            // 포트 설정
            gbc.gridx = 0; gbc.gridy = 0;
            controlPanel.add(new JLabel("Port:"), gbc);
            gbc.gridx = 1;
            controlPanel.add(portField, gbc);

            // 호스트 설정
            gbc.gridx = 2;
            controlPanel.add(new JLabel("Host:"), gbc);
            gbc.gridx = 3;
            controlPanel.add(hostField, gbc);

            // 버튼들
            gbc.gridx = 0; gbc.gridy = 1;
            recordButton.addActionListener(this::startRecording);
            controlPanel.add(recordButton, gbc);

            gbc.gridx = 1;
            replayButton.addActionListener(this::startReplaying);
            controlPanel.add(replayButton, gbc);

            gbc.gridx = 2;
            stopButton.addActionListener(this::stop);
            controlPanel.add(stopButton, gbc);

            gbc.gridx = 3;
            JPanel filePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            loadButton.addActionListener(this::load);
            saveButton.addActionListener(this::save);
            filePanel.add(loadButton);
            filePanel.add(saveButton);
            controlPanel.add(filePanel, gbc);

            add(controlPanel, BorderLayout.SOUTH);
        }

        private void updateStatus() {
            switch (status) {
                case Status.Idle idle -> {
                    statusLabel.setText("Ready");
                    recordButton.setEnabled(true);
                    replayButton.setEnabled(recordedMessages != null && !recordedMessages.isEmpty());
                    stopButton.setEnabled(false);
                    loadButton.setEnabled(true);
                    saveButton.setEnabled(recordedMessages != null && !recordedMessages.isEmpty());
                }
                case Status.Recording(var recorder) -> {
                    statusLabel.setText("Recording OSC Messages...");
                    recordButton.setEnabled(false);
                    replayButton.setEnabled(false);
                    stopButton.setEnabled(true);
                    loadButton.setEnabled(false);
                    saveButton.setEnabled(false);
                }
                case Status.Replaying(var replayer) -> {
                    statusLabel.setText("Replaying OSC Messages...");
                    recordButton.setEnabled(false);
                    replayButton.setEnabled(false);
                    stopButton.setEnabled(true);
                    loadButton.setEnabled(false);
                    saveButton.setEnabled(false);
                }
            }
        }

        private int readPort() {
            return Integer.parseInt(portField.getText());
        }

        private void startRecording(ActionEvent e) {
            if (!(status instanceof Status.Idle)) return;

            try {
                int port = readPort();
                var recorder = new OSCMessageRecorder(port);
                recorder.start();
                status = new Status.Recording(recorder);
                updateStatus();
                messageArea.setText("🎙️ Recording OSC messages on port " + port + "...\n");
                messageArea.append("VMC messages will be displayed here:\n\n");
                
            } catch (Exception ex) {
                reportError(ex);
            }
        }

        private void startReplaying(ActionEvent e) {
            if (!(status instanceof Status.Idle) || recordedMessages == null) return;

            try {
                String host = hostField.getText();
                int port = readPort();
                var replayer = new OSCReplayer(host, port, recordedMessages);
                replayer.start();
                status = new Status.Replaying(replayer);
                updateStatus();
                messageArea.setText("▶️ Replaying " + recordedMessages.size() + " OSC messages to " + host + ":" + port + "...\n\n");
                
            } catch (Exception ex) {
                reportError(ex);
            }
        }

        private void stop(ActionEvent e) {
            switch (status) {
                case Status.Recording(var recorder) -> {
                    try {
                        recorder.stop();
                        recordedMessages = recorder.getMessages();
                        recorder.close();
                        messageArea.append("\n✅ Recording stopped. Captured " + recordedMessages.size() + " messages.\n");
                        showMessageSummary();
                    } catch (Exception ex) {
                        reportError(ex);
                    }
                    status = new Status.Idle();
                    updateStatus();
                }
                case Status.Replaying(var replayer) -> {
                    try {
                        replayer.stop();
                        replayer.close();
                        messageArea.append("\n⏹️ Replay stopped.\n");
                    } catch (Exception ex) {
                        reportError(ex);
                    }
                    status = new Status.Idle();
                    updateStatus();
                }
                default -> {}
            }
        }

        private void showMessageSummary() {
            if (recordedMessages == null || recordedMessages.isEmpty()) return;
            
            Map<String, Integer> addressCount = new HashMap<>();
            for (OSCMessageData msg : recordedMessages) {
                addressCount.merge(msg.address, 1, Integer::sum);
            }
            
            messageArea.append("\n📊 Message Summary:\n");
            addressCount.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .forEach(entry -> 
                    messageArea.append("  " + entry.getKey() + ": " + entry.getValue() + "\n"));
        }

        private void reportError(Throwable e) {
            logger.log(Level.SEVERE, "Error", e);
            JOptionPane.showMessageDialog(this, "Error: " + e.getMessage());
            messageArea.append("❌ Error: " + e.getMessage() + "\n");
        }

        private JFileChooser loadChooser;
        private void load(ActionEvent e) {
            if (loadChooser == null) {
                loadChooser = new JFileChooser();
                loadChooser.setDialogTitle("Load OSC Record File");
                loadChooser.setFileFilter(new FileNameExtensionFilter("UDP Record File (OSC)", "urf"));
                loadChooser.addActionListener($ -> {
                    if (loadChooser.getSelectedFile() == null) return;
                    
                    try (var input = new DataInputStream(new FileInputStream(loadChooser.getSelectedFile()))) {
                        int count = input.readInt();
                        recordedMessages = new ArrayList<>(count);
                        
                        for (int i = 0; i < count; i++) {
                            recordedMessages.add(OSCMessageData.deserialize(input));
                        }
                        
                        updateStatus();
                        messageArea.setText("📁 Loaded " + count + " OSC messages from .urf file.\n");
                        showMessageSummary();
                        
                    } catch (IOException ex) {
                        reportError(ex);
                    }
                });
            }
            loadChooser.showOpenDialog(this);
        }

        private JFileChooser saveChooser;
        private void save(ActionEvent e) {
            if (recordedMessages == null || recordedMessages.isEmpty()) return;
            
            if (saveChooser == null) {
                saveChooser = new JFileChooser();
                saveChooser.setDialogTitle("Save OSC Record File");
                saveChooser.setFileFilter(new FileNameExtensionFilter("UDP Record File (OSC)", "urf"));
                saveChooser.addActionListener($ -> {
                    var file = saveChooser.getSelectedFile();
                    if (file == null) return;
                    
                    if (!file.getName().contains(".")) {
                        file = new File(file.getAbsolutePath() + ".urf");
                    }
                    
                    try (var output = new DataOutputStream(new FileOutputStream(file))) {
                        output.writeInt(recordedMessages.size());
                        for (var msg : recordedMessages) {
                            msg.serialize(output);
                        }
                        messageArea.append("💾 Saved " + recordedMessages.size() + " messages to " + file.getName() + "\n");
                        
                    } catch (IOException ex) {
                        reportError(ex);
                    }
                });
            }
            saveChooser.showSaveDialog(this);
        }
    }
}
