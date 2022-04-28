import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.PriorityQueue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicIntegerArray;

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
  private AtomicIntegerArray stats;

  public Receiver(int sourcePort, int mtu, int sws, String fileName) {
    this.MAX_PKT = mtu - 54;
    this.BUF_SIZE = sws / MAX_PKT;
    this.sws = sws;
    this.seqNum = 0;
    this.timeout = 5000;
    this.buffer = new PriorityQueue<>(BUF_SIZE);
    this.stats = new AtomicIntegerArray(6);

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
    boolean added = true;

    while (!done)
    {
      added = true;
      buf = new byte[MAX_PKT + TCP.HEADER_SIZE];
      dp = new DatagramPacket(buf, buf.length);
      socket.receive(dp);

      TCP tcp = new TCP();
      tcp = tcp.deserialize(buf);

      if (tcp.getChecksum() != tcp.computeChecksum())
      {
        stats.incrementAndGet(3);
        continue;
      }
      stats.incrementAndGet(1);

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
        stats.addAndGet(0, tcp.getLength());
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
          stats.addAndGet(0, tcp.getLength());
          fileStream.write(tcp.getPayload());
        }
        if (done)
          break;
      }
      else // Out of order packet, store in buffer
      {
        if (!buffer.contains(tcp) && buffer.size() < BUF_SIZE)
        {
          buffer.add(tcp);
          added = false;
          System.out.println("rcv " + tcp);
        }
        else
          stats.incrementAndGet(2);
      }

      if (tcp.getFlags() != (TCP.FLAG_FIN | TCP.FLAG_ACK) && added) // Don't send ACK if received FIN, send in
                                                                    // teardown()
      {
        tcp = new TCP(seqNum, ackNum, (byte)(TCP.FLAG_ACK));
        tcp.setTimeStamp(time);
        buf = tcp.serialize();
        dp = new DatagramPacket(buf, buf.length, destIp, destPort);
        socket.send(dp);
        System.out.println("snd " + tcp);
      }
    }

    printStats();
    fileStream.close();
    System.exit(1);
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
    Retransmit retrans = new Retransmit(dp);
    new Timer().schedule(retrans, 0, timeout);
    seqNum++;

    // Wait for ACK
    while (true)
    {
      packet = new byte[TCP.HEADER_SIZE + MAX_PKT];
      dp = new DatagramPacket(packet, packet.length);
      socket.receive(dp);
      tcp = new TCP().deserialize(packet);
      if (tcp.getChecksum() == tcp.computeChecksum())
      {
        stats.incrementAndGet(1);
        if (tcp.getAcknowledge() == seqNum)
        {
          retrans.cancel();
          System.out.println("rcv " + tcp);
          break;
        }
        else if (tcp.getFlags() == TCP.FLAG_SYN)
        {
          retrans.setTime(tcp.getTimeStamp());
        }
      }
      else
        stats.incrementAndGet(3);
    }
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
    Retransmit retrans = new Retransmit(dp);
    new Timer().schedule(retrans, 0, timeout);
    seqNum++;

    // Wait for ACK
    while (true)
    {
      packet = new byte[TCP.HEADER_SIZE + MAX_PKT];
      dp = new DatagramPacket(packet, packet.length);
      socket.receive(dp);
      tcp = new TCP().deserialize(packet);
      if (tcp.getChecksum() == tcp.computeChecksum())
      {
        stats.incrementAndGet(1);
        if (tcp.getFlags() == TCP.FLAG_ACK && tcp.getAcknowledge() == seqNum)
        {
          retrans.cancel();
          System.out.println("rcv " + tcp);
          break;
        }
        else if (tcp.getFlags() == (TCP.FLAG_ACK | TCP.FLAG_FIN))
          retrans.run();
      }
      else
        stats.incrementAndGet(3);
    }
  }

  public void printStats()
  {
    System.out.println("Data received: " + stats.get(0) + "bytes");
    System.out.println("Packets received: " + stats.get(1));
    System.out.println("Out of sequence packets discarded: " + stats.get(2));
    System.out.println("Incorrect checksum: " + stats.get(3));
    System.out.println("Retransmissions: " + stats.get(4));
    System.out.println("Duplicate ACKs: N/A");
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

        if (numRetrans != 0)
          stats.incrementAndGet(4);

        numRetrans++;
      }
      else
      {
        System.out.println("Error: Number of retransmits exceeded " + MAX_RETRANS);
        System.exit(0);
      }
    }

    public void setTime(long time)
    {
      TCP tcp = new TCP().deserialize(dp.getData());
      tcp.setTimeStamp(time);
      byte[] packet = tcp.serialize();
      dp = new DatagramPacket(packet, packet.length, destIp, destPort);
    }

  }
}
