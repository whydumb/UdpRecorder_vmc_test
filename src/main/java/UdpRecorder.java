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
import java.nio.ByteBuffer;
import java.util.ArrayList;
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

    private record RecordEntry(long timestamp, byte[] data) {
        public void serialize(DataOutputStream output) throws IOException {
            output.writeLong(timestamp);
            output.writeInt(data.length);
            output.write(data);
        }

        public static RecordEntry deserialize(DataInputStream input) throws IOException {
            var timestamp = input.readLong();
            var length = input.readInt();
            var data = new byte[length];
            input.readFully(data);
            return new RecordEntry(timestamp, data);
        }
    }

    private static class Recorder implements AutoCloseable {
        private final DatagramSocket socket;
        private final ArrayList<RecordEntry> entries = new ArrayList<>();
        private volatile boolean isRecording = false;
        private long startTime;
        private Thread receiverThread;

        public Recorder(int port) throws SocketException {
            socket = new DatagramSocket(port);
        }

        public void start() {
            if (isRecording) {
                return;
            }
            isRecording = true;
            startTime = System.nanoTime();
            receiverThread = new Thread(() -> {
                var buffer = new byte[65536];
                var packet = new DatagramPacket(buffer, buffer.length);
                while (isRecording && !Thread.currentThread().isInterrupted()) {
                    try {
                        socket.receive(packet);
                        var timestamp = System.nanoTime() - startTime;
                        var data = new byte[packet.getLength()];
                        System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                        synchronized (entries) {
                            entries.add(new RecordEntry(timestamp, data));
                        }
                    } catch (IOException e) {
                        if (isRecording && !Thread.currentThread().isInterrupted()) {
                            Logger.getLogger(Recorder.class.getName()).log(Level.SEVERE, "Error receiving packet", e);
                        }
                    }
                }
            });
            receiverThread.setDaemon(true);
            receiverThread.start();
        }

        public void stop() {
            if (!isRecording) {
                return;
            }
            isRecording = false;
            if (receiverThread != null) {
                receiverThread.interrupt();
            }
            socket.close();
        }

        public ArrayList<RecordEntry> getEntries() {
            synchronized (entries) {
                return new ArrayList<>(entries);
            }
        }

        @Override
        public void close() {
            stop();
        }
    }

    private static class Replayer implements AutoCloseable {
        private final DatagramSocket socket;
        private final InetAddress address;
        private final int port;
        private final ArrayList<RecordEntry> entries;
        private volatile boolean isReplaying = false;
        private Thread senderThread;
        private int currentIndex = 0;

        public Replayer(int port, ArrayList<RecordEntry> entries) throws SocketException, UnknownHostException {
            this.socket = new DatagramSocket();
            this.address = InetAddress.getLocalHost();
            this.port = port;
            this.entries = entries;
        }

        public void start() {
            if (isReplaying) {
                return;
            }
            isReplaying = true;
            senderThread = new Thread(() -> {
                var baseTime = System.nanoTime();

                while (isReplaying && currentIndex < entries.size()) {
                    var currentTime = System.nanoTime() - baseTime;

                    while (currentIndex < entries.size()) {
                        var entry = entries.get(currentIndex);

                        if (entry.timestamp() > currentTime) {
                            break;
                        }

                        try {
                            var packet = new DatagramPacket(entry.data(), entry.data().length, address, port);
                            socket.send(packet);
                        } catch (IOException e) {
                            Logger.getLogger(Replayer.class.getName()).log(Level.SEVERE, "Error sending packet", e);
                        }

                        currentIndex++;

                        currentTime = System.nanoTime() - baseTime;
                    }

                    if (currentIndex < entries.size()) {
                        var nextEntry = entries.get(currentIndex);
                        var waitTime = nextEntry.timestamp() - currentTime;

                        if (waitTime > 0) {
                            if (waitTime > 2_000_000) {
                                LockSupport.parkNanos(waitTime);
                            } else {
                                var targetTime = System.nanoTime() + waitTime;
                                while (System.nanoTime() < targetTime && isReplaying);
                            }
                        }
                    }
                }
            });
            senderThread.setDaemon(true);
            senderThread.start();
        }

        public void stop() {
            isReplaying = false;
            if (senderThread != null) {
                LockSupport.unpark(senderThread);
            }
            socket.close();
        }

        @Override
        public void close() {
            stop();
        }
    }


    private static class UIFrame extends JFrame {
        private final Logger logger = Logger.getLogger("UIFrame");

        private final JButton recordButton = new JButton("Record");
        private final JButton replayButton = new JButton("Replay");
        private final JButton stopButton = new JButton("Stop");
        private final JButton loadButton = new JButton("Load");
        private final JButton saveButton = new JButton("Save");
        private final JButton exportPcapButton = new JButton("Export to Pcap");
        private final JTextField portField = new JTextField("39539");
        private final JLabel statusLabel = new JLabel("");

        private ArrayList<RecordEntry> recordEntries = null;

        private Status status = new Status.Idle();
        private sealed interface Status {
            final class Idle implements Status {}
            record Recording(long startTime, Recorder recorder) implements Status {}
            record Replaying(long startTime, Replayer replayer) implements Status {}
        }

        private void addItem(Component comp, int gridX, int gridY) {
            addItem(comp, gridX, gridY, 1, 1);
        }

        private void addItem(Component comp, int gridX, int gridY, int gridWidth, int gridHeight) {
            var constraints = new GridBagConstraints();
            constraints.gridx = gridX;
            constraints.gridy = gridY;
            constraints.gridwidth = gridWidth;
            constraints.gridheight = gridHeight;
            constraints.fill = GridBagConstraints.BOTH;
            add(comp, constraints);
        }

        public UIFrame() {
            super("UdpRecorder");
            updateStatus();
            setLayout(new GridBagLayout());

            addItem(statusLabel, 0, 0, 2, 1);
            addItem(portField, 0, 1, 2, 1);
            recordButton.addActionListener(this::startRecording);
            addItem(recordButton, 0, 2);
            replayButton.addActionListener(this::startReplaying);
            addItem(replayButton, 1, 2);
            stopButton.addActionListener(this::stop);
            addItem(stopButton, 0, 3, 2, 1);
            loadButton.addActionListener(this::load);
            addItem(loadButton, 0, 4);
            saveButton.addActionListener(this::save);
            addItem(saveButton, 1, 4);
            exportPcapButton.addActionListener(this::exportPcap);
            addItem(exportPcapButton, 0, 5, 2, 1);
        }

        private void updateStatus() {
            switch (status) {
                case Status.Idle idle -> {
                    statusLabel.setText("Idle");
                    recordButton.setEnabled(true);
                    replayButton.setEnabled(recordEntries != null);
                    stopButton.setEnabled(false);
                    loadButton.setEnabled(true);
                    saveButton.setEnabled(recordEntries != null);
                    exportPcapButton.setEnabled(recordEntries != null);
                }
                case Status.Recording(var startTime, var recorder) -> {
                    statusLabel.setText("Recording");
                    recordButton.setEnabled(false);
                    replayButton.setEnabled(false);
                    stopButton.setEnabled(true);
                    loadButton.setEnabled(false);
                    saveButton.setEnabled(false);
                    exportPcapButton.setEnabled(false);
                }
                case Status.Replaying(var startTime, var replayer) -> {
                    statusLabel.setText("Replaying");
                    recordButton.setEnabled(false);
                    replayButton.setEnabled(false);
                    stopButton.setEnabled(true);
                    loadButton.setEnabled(false);
                    saveButton.setEnabled(false);
                    exportPcapButton.setEnabled(false);
                }
            }
        }

        private int readPort() throws IllegalArgumentException {
            var port = Integer.parseInt(portField.getText());
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid port number: " + port);
            }
            return port;
        }

        private void startRecording(ActionEvent e) {
            if (!(status instanceof Status.Idle)) {
                return;
            }
            try {
                var port = readPort();
                var recorder = new Recorder(port);
                recorder.start();
                status = new Status.Recording(System.nanoTime(), recorder);
                updateStatus();
            } catch (IllegalArgumentException | SocketException ex) {
                reportError(ex);
            }
        }

        private void startReplaying(ActionEvent e) {
            if (!(status instanceof Status.Idle)) {
                return;
            }
            try {
                var port = readPort();
                var replayer = new Replayer(port, recordEntries);
                replayer.start();
                status = new Status.Replaying(System.nanoTime(), replayer);
                updateStatus();
            } catch (IllegalArgumentException | SocketException | UnknownHostException ex) {
                reportError(ex);
            }
        }

        private void stop(ActionEvent e) {
            switch (status) {
                case Status.Recording recording -> {
                    try {
                        recording.recorder().stop();
                        recordEntries = recording.recorder().getEntries();
                    } catch (Exception ex) {
                        reportError(ex);
                    }
                    status = new Status.Idle();
                    updateStatus();
                }
                case Status.Replaying replaying -> {
                    try {
                        replaying.replayer().stop();
                    } catch (Exception ex) {
                        reportError(ex);
                    }
                    status = new Status.Idle();
                    updateStatus();
                }
                default -> {
                }
            }
        }

        private void reportError(Throwable e) {
            logger.log(Level.SEVERE, "Error", e);
            JOptionPane.showMessageDialog(this, "Error: " + e.getMessage());
        }

        private JFileChooser loadChooser;
        private void load(ActionEvent e) {
            if (loadChooser == null) {
                loadChooser = new JFileChooser();
                loadChooser.setDialogTitle("Load record file");
                loadChooser.setFileFilter(new FileNameExtensionFilter("UDP Record file", "urf"));
            }
            var result = loadChooser.showOpenDialog(this);
            if (result != JFileChooser.APPROVE_OPTION) {
                return;
            }
            if (loadChooser.getSelectedFile() == null) {
                return;
            }
            try (var inputStream = new DataInputStream(new FileInputStream(loadChooser.getSelectedFile()))) {
                var length = inputStream.readInt();
                recordEntries = new ArrayList<>(length);
                for (var i = 0; i < length; i++) {
                    recordEntries.add(RecordEntry.deserialize(inputStream));
                }
                updateStatus();
            } catch (IOException ex) {
                reportError(ex);
            }
        }

        private JFileChooser saveChooser;
        private void save(ActionEvent e) {
            var entries = recordEntries;
            if (entries == null) {
                return;
            }
            if (saveChooser == null) {
                saveChooser = new JFileChooser();
                saveChooser.setDialogTitle("Save record file");
                saveChooser.setFileFilter(new FileNameExtensionFilter("UDP Record file", "urf"));
            }
            var result = saveChooser.showSaveDialog(this);
            if (result != JFileChooser.APPROVE_OPTION) {
                return;
            }
            var file = saveChooser.getSelectedFile();
            if (file == null) {
                return;
            }
            if (!file.getName().contains(".")) {
                file = new File(file.getAbsolutePath() + ".urf");
            }
            try (var outputStream = new DataOutputStream(new FileOutputStream(file))) {
                outputStream.writeInt(entries.size());
                for (var entry : entries) {
                    entry.serialize(outputStream);
                }
            } catch (IOException ex) {
                reportError(ex);
            }
        }

        private JFileChooser exportChooser;
        private void exportPcap(ActionEvent e) {
            var entries = recordEntries;
            if (entries == null) {
                return;
            }
            if (exportChooser == null) {
                exportChooser = new JFileChooser();
                exportChooser.setDialogTitle("Export to Pcap file");
                exportChooser.setFileFilter(new FileNameExtensionFilter("Pcap file", "pcap"));
            }
            var result = exportChooser.showSaveDialog(this);
            if (result != JFileChooser.APPROVE_OPTION) {
                return;
            }
            var file = exportChooser.getSelectedFile();
            if (file == null) {
                return;
            }
            if (!file.getName().toLowerCase().endsWith(".pcap")) {
                file = new File(file.getAbsolutePath() + ".pcap");
            }

            try (var outputStream = new DataOutputStream(new FileOutputStream(file))) {
                var port = readPort();

                // Pcap Global Header
                outputStream.writeInt(0xa1b2c3d4); // Magic Number (Big-endian)
                outputStream.writeShort(2);        // Version Major
                outputStream.writeShort(4);        // Version Minor
                outputStream.writeInt(0);          // Timezone
                outputStream.writeInt(0);          // Timestamp Accuracy
                outputStream.writeInt(65535);      // Snapshot Length (max)
                outputStream.writeInt(101);        // Link-layer Header Type (101 = LINKTYPE_RAW, indicating raw IP)

                for (var entry : entries) {
                    var timestampSec = (int) (entry.timestamp() / 1_000_000_000);
                    var timestampUSec = (int) ((entry.timestamp() % 1_000_000_000) / 1000);

                    // IP header (20 bytes) + UDP header (8 bytes)
                    var totalLength = 20 + 8 + entry.data().length;

                    // Pcap Packet Header
                    outputStream.writeInt(timestampSec);
                    outputStream.writeInt(timestampUSec);
                    outputStream.writeInt(totalLength);
                    outputStream.writeInt(totalLength);

                    // IP Header
                    var ipHeader = new byte[20];
                    var ipBuffer = ByteBuffer.wrap(ipHeader);
                    ipBuffer.put((byte) 0x45); // Version (4) + IHL (5)
                    ipBuffer.put((byte) 0x00); // DSCP + ECN
                    ipBuffer.putShort((short) totalLength);
                    ipBuffer.putShort((short) 0x0001); // ID
                    ipBuffer.putShort((short) 0x0000); // Flags + Fragment Offset
                    ipBuffer.put((byte) 64);   // TTL
                    ipBuffer.put((byte) 17);   // Protocol (17 = UDP)
                    ipBuffer.putShort((short) 0);   // Checksum (initially 0)
                    ipBuffer.put(new byte[] {127, 0, 0, 1}); // Source IP
                    ipBuffer.put(new byte[] {127, 0, 0, 1}); // Destination IP

                    // Recalculate IP checksum
                    ipBuffer.putShort(10, (short) 0); // Clear old checksum
                    ipBuffer.putShort(10, computeChecksum(ipHeader));
                    outputStream.write(ipHeader);

                    // UDP Header
                    var udpHeader = new byte[8];
                    var udpBuffer = ByteBuffer.wrap(udpHeader);
                    udpBuffer.putShort((short) port); // Source Port
                    udpBuffer.putShort((short) port); // Destination Port
                    udpBuffer.putShort((short) (8 + entry.data().length)); // Length
                    udpBuffer.putShort((short) 0); // Checksum (initially 0)

                    // Recalculate UDP checksum with pseudo-header
                    outputStream.write(udpHeader);

                    // Raw UDP data
                    outputStream.write(entry.data());
                }
            } catch (IllegalArgumentException | IOException ex) {
                reportError(ex);
            }
        }

        private short computeChecksum(byte[] buf) {
            var length = buf.length;
            var i = 0;
            var sum = 0L;

            while (length > 1) {
                sum += ((buf[i] & 0xFF) << 8) | (buf[i+1] & 0xFF);
                i += 2;
                length -= 2;
            }

            if (length > 0) {
                sum += (buf[i] & 0xFF) << 8;
            }

            while ((sum >> 16) > 0) {
                sum = (sum & 0xffff) + (sum >> 16);
            }

            return (short) ~sum;
        }
    }
}
