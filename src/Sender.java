import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Sender {
  protected final int MAX_RETRANS = 16;
  private int MAX_PKT;
  private int BUF_SIZE;
  private int destPort;
  private InetAddress destIp;
  private int sws;
  private DatagramSocket socket;
  private FileInputStream fileStream;
  private double timeout;
  private int seqNum;
  private int ackNum;
  private ConcurrentLinkedQueue<Retransmit> buffer;

  public Sender(int sourcePort, String destIP, int destPort, String fileName, int mtu, int sws) {
    this.MAX_PKT = mtu - 54;
    this.BUF_SIZE = sws / MAX_PKT;
    this.destPort = destPort;
    this.sws = sws;
    this.timeout = 5000;
    this.seqNum = 0;
    this.buffer = new ConcurrentLinkedQueue<>();

    try
    {
      fileStream = new FileInputStream(fileName);
    } catch (FileNotFoundException e2)
    {
      System.out.println("Could not read file");
      e2.printStackTrace();
      System.exit(0);
    }

    try
    {
      this.destIp = InetAddress.getByName(destIP);
    } catch (UnknownHostException e1)
    {
      System.out.println("Unknown IP address " + destIP);
      e1.printStackTrace();
      System.exit(0);
    }

    try
    {
      this.socket = new DatagramSocket(sourcePort);
    } catch (SocketException e)
    {
      System.out.println("Could not establish connection to port " + sourcePort);
      e.printStackTrace();
      System.exit(0);
    }
  }

  public void run() throws IOException
  {
    startup();

    byte[] buf = new byte[MAX_PKT];
    int numBytes;
    boolean done = false;

    ReceiveThread recThread = new ReceiveThread();
    Thread t = new Thread(recThread);
    t.start();

    while (!done)
    {
      while (buffer.size() < BUF_SIZE && !done)
      {
        numBytes = fileStream.read(buf);
        if (numBytes == -1)
        {
          done = true;
          break;
        }
        else if (numBytes < MAX_PKT)
        {
          byte[] temp = new byte[numBytes];
          System.arraycopy(buf, 0, temp, 0, numBytes);
          buf = temp;
          done = true;
        }

        TCP tcp = new TCP(seqNum, ackNum, TCP.FLAG_ACK, buf.clone());
        Retransmit retrans = new Retransmit(tcp);
        new Timer().schedule(retrans, 0, (long)timeout);
        buffer.offer(retrans);
        seqNum += numBytes;
      }
    }

    fileStream.close();
    teardown();
  }

  public void startup() throws IOException
  {
    // Initial SYN
    TCP tcpSend = new TCP(seqNum, ackNum, TCP.FLAG_SYN);
    Retransmit retrans = new Retransmit(tcpSend);
    new Timer().schedule(retrans, 0, (long)timeout);
    seqNum++;

    // Wait for SYN + ACK
    byte[] packet;
    DatagramPacket dp;
    while (true)
    {
      packet = new byte[TCP.HEADER_SIZE];
      dp = new DatagramPacket(packet, packet.length);

      socket.receive(dp);
      TCP tcpRec = new TCP();
      tcpRec = tcpRec.deserialize(packet);

      int checksum = tcpRec.getChecksum();
      if (tcpRec.getFlags() == (TCP.FLAG_ACK | TCP.FLAG_SYN) && tcpRec.getAcknowledge() == seqNum
          && checksum == tcpRec.computeChecksum())
      {
        retrans.cancel();
        ackNum = tcpRec.getSequence() + 1;
        timeout = 2 * (System.nanoTime() - tcpRec.getTimeStamp()) / 1000000.0;
        System.out.println("rcv " + tcpRec);
        break;
      }
    }

    // Send ACK
    tcpSend = new TCP(seqNum, ackNum, TCP.FLAG_ACK);
    packet = tcpSend.serialize();
    dp = new DatagramPacket(packet, packet.length, destIp, destPort);
    socket.send(dp);
    System.out.println("snd " + tcpSend);
  }

  public void teardown()
  {
    // Send FIN
    TCP tcpSend = new TCP(seqNum, ackNum, (byte)(TCP.FLAG_FIN | TCP.FLAG_ACK));
    Retransmit retrans = new Retransmit(tcpSend);
    new Timer().schedule(retrans, 0, (long)timeout);
    buffer.offer(retrans);
    seqNum++;
  }

  private class Retransmit extends TimerTask {
    private TCP tcp;
    private int numRetrans;

    public Retransmit(TCP tcp) {
      this.numRetrans = 0;
      this.tcp = tcp;
    }

    public void run()
    {
      if (numRetrans < MAX_RETRANS)
      {
        tcp.setTimeStamp();
        byte[] packet = tcp.serialize();
        DatagramPacket dp = new DatagramPacket(packet, packet.length, destIp, destPort);
        try
        {
          socket.send(dp);
          System.out.println("snd " + tcp);
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

    public int getSeq()
    { return tcp.getSequence(); }

  }

  private class ReceiveThread implements Runnable {
    public void run()
    {
      byte[] buf;
      DatagramPacket dp;
      TCP tcp;
      long eDEV = 0;
      long eRTT = (long)((timeout / 2) * 1_000_000);
      int prevAck = 0;
      int ackCnt = 0;

      while (true)
      {
        buf = new byte[TCP.HEADER_SIZE];
        dp = new DatagramPacket(buf, buf.length);

        try
        {
          socket.receive(dp);
        } catch (IOException e)
        {
          e.printStackTrace();
        }

        tcp = new TCP();
        tcp = tcp.deserialize(buf);

        // Check checksum
        int checksum = tcp.getChecksum();
        if (checksum != tcp.computeChecksum())
          continue;

        System.out.println("rcv " + tcp);

        // Receiver retransmitted SYN, send ACK
        if (tcp.getFlags() == (TCP.FLAG_ACK | TCP.FLAG_SYN))
        {
          eRTT = System.nanoTime() - tcp.getTimeStamp();
          timeout = 2 * eRTT / 1_000_000.0;
          TCP tcpSend = new TCP(seqNum, ackNum, TCP.FLAG_ACK);
          byte[] packet = tcpSend.serialize();
          DatagramPacket dpSend = new DatagramPacket(packet, packet.length, destIp, destPort);
          try
          {
            socket.send(dpSend);
          } catch (IOException e)
          {
            e.printStackTrace();
          }
          continue;
        }
        else if (tcp.getFlags() == (TCP.FLAG_ACK | TCP.FLAG_FIN)) // Received FIN
        {
          while (!buffer.isEmpty())
          {
            buffer.poll().cancel();
          }

          ackNum = tcp.getSequence() + 1;
          TCP tcpSend = new TCP(seqNum, ackNum, TCP.FLAG_ACK);
          byte[] packet = tcpSend.serialize();
          DatagramPacket dpSend = new DatagramPacket(packet, packet.length, destIp, destPort);
          try
          {
            socket.send(dpSend);
            System.out.println("snd " + tcpSend);
          } catch (IOException e)
          {
            e.printStackTrace();
          }

          break;
        }

        // Remove ACKed packets from buffer
        int tempAckNum = tcp.getAcknowledge();
        while (!buffer.isEmpty() && buffer.peek().getSeq() < tempAckNum)
        {
          buffer.poll().cancel();
        }

        // Fast retransmit
        if (prevAck == tempAckNum)
        {
          ackCnt++;
          if (ackCnt >= 3)
          {
            buffer.peek().run();
            if (buffer.peek().getSeq() != prevAck)
              System.out.println("Fast Retransmit: restransmitted wrong packet");
          }
        }
        else
        {
          prevAck = tempAckNum;
          ackCnt = 1;
        }

        // Calculate timeout
        long sRTT = System.nanoTime() - tcp.getTimeStamp();
        long sDEV = Math.abs(sRTT - eRTT);
        eRTT = (long)((.875 * eRTT) + (.125 * sRTT));
        eDEV = (long)((.75 * eDEV) + (.25 * sDEV));
        timeout = (eRTT + (4 * eDEV)) / 1_000_000.0;
      }

      System.exit(1);
    }
  }
}
