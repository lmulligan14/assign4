public class TCPend {
    public static void main(String[] args) throws Exception {
        if (args.length == 12 && args[2].equals("-s")) {
            Sender sender = new Sender(Integer.parseInt(args[1]), args[3], Integer.parseInt(args[5]), args[7],
                    Integer.parseInt(args[9]), Integer.parseInt(args[11]));
            sender.run();
        }
        else if (args.length == 8) {
            Receiver receiver = new Receiver(Integer.parseInt(args[1]), Integer.parseInt(args[3]),
                    Integer.parseInt(args[5]), args[7]);
            receiver.run();
        }
        else {
            System.out.println("Error: missing or additional arguments");
            System.exit(0);
        }
    }
}
