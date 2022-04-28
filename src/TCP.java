import java.nio.ByteBuffer;

public class TCP implements Comparable {
    public static final byte FLAG_SYN = 0x4;
    public static final byte FLAG_ACK = 0x2;
    public static final byte FLAG_FIN = 0x1;
    public static final int HEADER_SIZE = 24;

    private int seqNum;
    private int ackNum;
    private long timeStamp;
    private int length;
    private byte flags;
    private short checksum;
    private byte[] payload;

    public TCP(int seq, int ack, byte flags, byte[] data) {
        this.seqNum = seq;
        this.ackNum = ack;
        this.flags = flags;
        this.payload = data;
        length = payload.length;
        timeStamp = System.nanoTime();
    }

    public TCP(int seq, int ack, byte flags) {
        this.seqNum = seq;
        this.ackNum = ack;
        this.flags = flags;
        length = 0;
        timeStamp = System.nanoTime();
    }

    public TCP() {}

    public int getSequence()
    { return this.seqNum; }

    public TCP setSequence(int seq)
    {
        this.seqNum = seq;
        return this;
    }

    public int getAcknowledge()
    { return this.ackNum; }

    public TCP setAcknowledge(int ack)
    {
        this.ackNum = ack;
        return this;
    }

    public long getTimeStamp()
    { return this.timeStamp; }

    public TCP setTimeStamp()
    {
        this.timeStamp = System.nanoTime();
        return this;
    }

    public TCP setTimeStamp(long time)
    {
        this.timeStamp = time;
        return this;
    }

    public int getLength()
    { return this.length; }

    public byte getFlags()
    { return this.flags; }

    public TCP setFlags(byte flags)
    {
        this.flags = flags;
        return this;
    }

    public byte[] getPayload()
    { return this.payload; }

    public short getChecksum()
    { return this.checksum; }

    public short computeChecksum()
    {
        byte[] data;
        checksum = 0;
        data = new byte[24 + length];
        ByteBuffer bb = ByteBuffer.wrap(data);

        bb.putInt(seqNum);
        bb.putInt(ackNum);
        bb.putLong(timeStamp);
        bb.putInt(((length << 3) | flags));
        bb.putShort((short)0);
        bb.putShort(checksum);

        if (payload != null)
            bb.put(payload);

        bb.rewind();
        int accumulation = 0;

        for (int i = 0; i < length / 2; ++i)
        {
            accumulation += 0xffff & bb.getShort();
        }

        if (length % 2 > 0)
        {
            accumulation += (bb.get() & 0xff) << 8;
        }

        accumulation = ((accumulation >> 16) & 0xffff) + (accumulation & 0xffff);
        this.checksum = (short)(~accumulation & 0xffff);

        return checksum;
    }

    public byte[] serialize()
    {
        byte[] data;
        checksum = 0;
        data = new byte[24 + length];
        ByteBuffer bb = ByteBuffer.wrap(data);

        bb.putInt(seqNum);
        bb.putInt(ackNum);
        bb.putLong(timeStamp);
        bb.putInt(((length << 3) | flags));
        bb.putShort((short)0);
        bb.putShort(checksum);

        if (payload != null)
            bb.put(payload);

        bb.rewind();
        int accumulation = 0;

        for (int i = 0; i < length / 2; ++i)
        {
            accumulation += 0xffff & bb.getShort();
        }

        if (length % 2 > 0)
        {
            accumulation += (bb.get() & 0xff) << 8;
        }

        accumulation = ((accumulation >> 16) & 0xffff) + (accumulation & 0xffff);
        this.checksum = (short)(~accumulation & 0xffff);
        bb.putShort(22, this.checksum);

        return data;
    }

    public TCP deserialize(byte[] data)
    {
        ByteBuffer bb = ByteBuffer.wrap(data);

        seqNum = bb.getInt();
        ackNum = bb.getInt();
        timeStamp = bb.getLong();
        length = bb.getInt();
        flags = (byte)(length & 0x7);
        length = (length >> 3);
        bb.getShort();
        checksum = bb.getShort();

        payload = new byte[length];
        bb.get(payload);

        return this;
    }

    public String toString()
    {
        String str = "";

        str += (System.nanoTime() / 1_000_000_000.0) + " ";
        str += ((flags & FLAG_SYN) != 0) ? "S " : "- ";
        str += ((flags & FLAG_ACK) != 0) ? "A " : "- ";
        str += ((flags & FLAG_FIN) != 0) ? "F " : "- ";
        str += (length != 0) ? "D " : "- ";
        str += seqNum + " ";
        str += length + " ";
        str += ackNum;

        return str;
    }

    @Override
    public int compareTo(Object o)
    {
        TCP otherTCP = (TCP)o;
        return this.seqNum - otherTCP.seqNum;
    }

    public boolean equals(TCP otherTCP)
    { return this.seqNum == otherTCP.seqNum; }
}
