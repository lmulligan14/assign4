import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Timer;
import java.util.TimerTask;

public class Receiver {
  protected final int MAX_RETRANS = 16;
  private int mtu;
  private int sws;
  private DatagramSocket socket;
  private FileOutputStream fileStream;
  private int seqNum;
  private int ackNum;
  private InetAddress destIp;
  private int destPort;
  private int timeout;

  public Receiver(int sourcePort, int mtu, int sws, String fileName) {
    this.mtu = mtu;
    this.sws = sws;
    this.seqNum = 0;
    this.timeout = 5;

    File file = new File(fileName);
    try {
      fileStream = new FileOutputStream(file);
    } catch (FileNotFoundException e1) {
      System.out.println("Could not generate file");
      e1.printStackTrace();
      System.exit(0);
    }

    try {
      this.socket = new DatagramSocket(sourcePort);
    } catch (SocketException e) {
      System.out.println("Could not establish connection to port" + sourcePort);
      e.printStackTrace();
      System.exit(0);
    }
  }

  public void run() throws IOException {
    byte[] buf;
    DatagramPacket dp;

    while (true) {
      buf = new byte[mtu + 24];
      dp = new DatagramPacket(buf, buf.length);
      socket.receive(dp);

      TCP tcp = new TCP();
      tcp = tcp.deserialize(buf);

      fileStream.write(tcp.getPayload());

      System.out.println(tcp);
    }
  }

  public void startup() throws IOException {
    byte[] packet;
    DatagramPacket dp;
    TCP tcp;

    // Wait for SYN
    while (true) {
      packet = new byte[TCP.HEADER_SIZE];
      dp = new DatagramPacket(packet, packet.length);
      socket.receive(dp);

      tcp = new TCP();
      tcp = tcp.deserialize(packet);

      if (tcp.getFlags() == TCP.FLAG_SYN) {
        destIp = dp.getAddress();
        destPort = dp.getPort();
        ackNum = tcp.getSequence() + 1;
        break;
      }
    }

    // Send SYN + ACK
    tcp = new TCP(seqNum, ackNum, (byte)(TCP.FLAG_SYN & TCP.FLAG_ACK));
    packet = tcp.serialize();
    dp = new DatagramPacket(packet, packet.length, destIp, destPort);
    Timer retransTimer = new Timer();
    retransTimer.schedule(new Retransmit(dp), 0, timeout);
    seqNum++;

    // TODO: Wait for ACK
  }

  private class Retransmit extends TimerTask {
    private DatagramPacket dp;
    private int numRetrans;

    public Retransmit(DatagramPacket dp) {
      this.dp = dp;
      this.numRetrans = 0;
    }

    public void run() {
      if (numRetrans < MAX_RETRANS) {
        try {
          socket.send(dp);
        } catch (IOException e) {
          e.printStackTrace();
        }
        numRetrans++;
      }
      else {
        System.out.println("Error: Number of retransmits exceeded " + MAX_RETRANS);
        System.exit(0);
      }
    }

  }
}
