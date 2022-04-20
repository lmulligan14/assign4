import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;

public class Sender {
  protected final int MAX_RETRANS = 16;
  private int destPort;
  private InetAddress destIp;
  private int mtu;
  private int sws;
  private DatagramSocket socket;
  private FileInputStream fileStream;
  private long timeout;
  private int seqNum;
  private int ackNum;

  public Sender(int sourcePort, String destIP, int destPort, String fileName, int mtu, int sws) {
    this.destPort = destPort;
    this.mtu = mtu;
    this.sws = sws;
    this.timeout = 5000;
    this.seqNum = 0;

    try {
      fileStream = new FileInputStream(fileName);
    } catch (FileNotFoundException e2) {
      System.out.println("Could not read file");
      e2.printStackTrace();
      System.exit(0);
    }

    try {
      this.destIp = InetAddress.getByName(destIP);
    } catch (UnknownHostException e1) {
      System.out.println("Unknown IP address " + destIP);
      e1.printStackTrace();
      System.exit(0);
    }

    try {
      this.socket = new DatagramSocket(sourcePort);
    } catch (SocketException e) {
      System.out.println("Could not establish connection to port " + sourcePort);
      e.printStackTrace();
      System.exit(0);
    }
  }

  public void run() throws IOException {
    startup();

    byte[] buf = new byte[mtu];
    DatagramPacket dp;
    int numBytes;

    while (true) {
      numBytes = fileStream.read(buf);
      if (numBytes == -1)
        break;
      else if (numBytes < mtu) {
        byte[] temp = new byte[numBytes];
        System.arraycopy(buf, 0, temp, 0, numBytes);
        buf = temp;
      }

      TCP tcp = new TCP(100, 201, TCP.FLAG_SYN, buf);
      tcp.setTimeStamp();
      byte[] packet = tcp.serialize();

      dp = new DatagramPacket(packet, packet.length, destIp, destPort);
      socket.send(dp);
    }
  }

  public void startup() throws IOException {
    // Initial SYN
    TCP tcpSend = new TCP(seqNum, ackNum, TCP.FLAG_SYN);
    byte[] packet = tcpSend.serialize();
    DatagramPacket dp = new DatagramPacket(packet, packet.length, destIp, destPort);
    Timer retransTimer = new Timer();
    retransTimer.schedule(new Retransmit(dp), 0, timeout);
    seqNum++;

    // Wait for SYN + ACK
    while (true) {
      packet = new byte[TCP.HEADER_SIZE];
      dp = new DatagramPacket(packet, packet.length);

      socket.receive(dp);
      TCP tcpRec = new TCP();
      tcpRec = tcpRec.deserialize(packet);

      if (tcpRec.getFlags() == (TCP.FLAG_ACK & TCP.FLAG_SYN) && tcpRec.getAcknowledge() == seqNum) {
        retransTimer.cancel();
        ackNum = tcpRec.getSequence() + 1;
        break;
      }
    }

    // Send ACK
    tcpSend = new TCP(seqNum, ackNum, TCP.FLAG_ACK);
    packet = tcpSend.serialize();
    dp = new DatagramPacket(packet, packet.length, destIp, destPort);
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
