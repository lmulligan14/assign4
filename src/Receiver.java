import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.PriorityQueue;
import java.util.Timer;
import java.util.TimerTask;

public class Receiver {
  protected final int MAX_RETRANS = 16;
  private int MAX_PKT;
  private int BUF_SIZE;
  private int sws;
  private DatagramSocket socket;
  private FileOutputStream fileStream;
  private int seqNum;
  private int ackNum;
  private InetAddress destIp;
  private int destPort;
  private int timeout;
  private PriorityQueue<TCP> buffer;

  public Receiver(int sourcePort, int mtu, int sws, String fileName) {
    this.MAX_PKT = mtu - 54;
    this.BUF_SIZE = sws / MAX_PKT;
    this.sws = sws;
    this.seqNum = 0;
    this.timeout = 5000;
    this.buffer = new PriorityQueue<>(BUF_SIZE);

    File file = new File(fileName);
    try
    {
      fileStream = new FileOutputStream(file);
    } catch (FileNotFoundException e1)
    {
      System.out.println("Could not generate file");
      e1.printStackTrace();
      System.exit(0);
    }

    try
    {
      this.socket = new DatagramSocket(sourcePort);
    } catch (SocketException e)
    {
      System.out.println("Could not establish connection to port" + sourcePort);
      e.printStackTrace();
      System.exit(0);
    }
  }

  public void run() throws IOException
  {
    byte[] buf;
    DatagramPacket dp;
    boolean done = false;

    while (!done)
    {
      buf = new byte[MAX_PKT + TCP.HEADER_SIZE];
      dp = new DatagramPacket(buf, buf.length);
      socket.receive(dp);

      TCP tcp = new TCP();
      tcp = tcp.deserialize(buf);

      int checksum = tcp.getChecksum();
      if (checksum != tcp.computeChecksum())
        continue;

      // Do startup if SYN
      if (tcp.getFlags() == TCP.FLAG_SYN)
      {
        startup(dp);
        continue;
      }

      long time = tcp.getTimeStamp();

      if (ackNum == tcp.getSequence()) // Is next packet in sequence
      {
        if (tcp.getFlags() == (TCP.FLAG_FIN | TCP.FLAG_ACK)) // Received FIN
        {
          teardown(tcp);
          break;
        }

        ackNum += tcp.getLength();
        fileStream.write(tcp.getPayload());
        System.out.println("rcv " + tcp);

        while (!buffer.isEmpty() && ackNum == buffer.peek().getSequence())
        {
          tcp = buffer.poll();
          if (tcp.getFlags() == (TCP.FLAG_FIN | TCP.FLAG_ACK))
          {
            teardown(tcp);
            done = true;
            break;
          }
          ackNum += tcp.getLength();
          fileStream.write(tcp.getPayload());
        }
        if (done)
          break;
      }
      else // Out of order packet, store in buffer
      {
        if (!buffer.contains(tcp) && buffer.size() < BUF_SIZE)
          buffer.add(tcp);
        System.out.println("rcv " + tcp);
      }

      if (tcp.getFlags() != (TCP.FLAG_FIN | TCP.FLAG_ACK)) // Don't send ACK if received FIN, send in teardown()
      {
        tcp = new TCP(seqNum, ackNum, (byte)(TCP.FLAG_ACK));
        tcp.setTimeStamp(time);
        buf = tcp.serialize();
        dp = new DatagramPacket(buf, buf.length, destIp, destPort);
        socket.send(dp);
        System.out.println("snd " + tcp);
      }
    }

    fileStream.close();
  }

  public void startup(DatagramPacket dp) throws IOException
  {
    byte[] packet;
    TCP tcp = new TCP().deserialize(dp.getData());

    destIp = dp.getAddress();
    destPort = dp.getPort();
    ackNum = tcp.getSequence() + 1;
    long time = tcp.getTimeStamp();
    System.out.println("rcv " + tcp);

    // Send SYN + ACK
    tcp = new TCP(seqNum, ackNum, (byte)(TCP.FLAG_SYN | TCP.FLAG_ACK));
    tcp.setTimeStamp(time);
    packet = tcp.serialize();
    dp = new DatagramPacket(packet, packet.length, destIp, destPort);
    Timer retransTimer = new Timer();
    retransTimer.schedule(new Retransmit(dp), 0, timeout);
    seqNum++;

    // Wait for ACK
    packet = new byte[TCP.HEADER_SIZE];
    dp = new DatagramPacket(packet, packet.length);
    socket.receive(dp);
    tcp = new TCP().deserialize(packet);
    retransTimer.cancel();
    System.out.println("rcv " + tcp);
  }

  public void teardown(TCP tcp) throws IOException
  {
    System.out.println("rcv " + tcp);
    ackNum = tcp.getSequence() + 1;
    long time = tcp.getTimeStamp();

    // Send FIN + ACK
    tcp = new TCP(seqNum, ackNum, (byte)(TCP.FLAG_FIN | TCP.FLAG_ACK));
    tcp.setTimeStamp(time);
    byte[] packet = tcp.serialize();
    DatagramPacket dp = new DatagramPacket(packet, packet.length, destIp, destPort);
    Timer retransTimer = new Timer();
    retransTimer.schedule(new Retransmit(dp), 0, timeout);
    seqNum++;

    // Wait for ACK
    while (true)
    {
      packet = new byte[TCP.HEADER_SIZE];
      dp = new DatagramPacket(packet, packet.length);
      socket.receive(dp);
      tcp = new TCP().deserialize(packet);
      int checksum = tcp.getChecksum();
      if (tcp.getFlags() == TCP.FLAG_ACK && tcp.getAcknowledge() == seqNum && checksum == tcp.computeChecksum())
      {
        retransTimer.cancel();
        System.out.println("rcv " + tcp);
        break;
      }
    }
  }

  private class Retransmit extends TimerTask {
    private DatagramPacket dp;
    private int numRetrans;

    public Retransmit(DatagramPacket dp) {
      this.dp = dp;
      this.numRetrans = 0;
    }

    public void run()
    {
      if (numRetrans < MAX_RETRANS)
      {
        try
        {
          socket.send(dp);
          System.out.println("snd " + new TCP().deserialize(dp.getData()));
        } catch (IOException e)
        {
          e.printStackTrace();
        }
        numRetrans++;
      }
      else
      {
        System.out.println("Error: Number of retransmits exceeded " + MAX_RETRANS);
        System.exit(0);
      }
    }

  }
}
