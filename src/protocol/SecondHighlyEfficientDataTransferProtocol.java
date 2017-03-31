package protocol;

import java.util.*;

import client.*;

public class SecondHighlyEfficientDataTransferProtocol extends IRDTProtocol {

    // change the following as you wish:
    private static final int HEADERSIZE = 1;   // number of header bytes in each packet
    private static final int DATASIZE = 512;   // max. number of user data bytes in each packet
    private static final int WINDOWSIZE = 15;
    private static final int DELAYTIME = 10000;
    private static final int NUMBEROFACKCHECKS = 100;

    @Override
    public void sender() {
        System.out.println("Sending...");

        // read from the input file
        Integer[] fileContents = Utils.getFileContents(getFileID());
        int filePointer = 0;
        Set<Integer> receivedAcks = new HashSet<>();
        int numberOfFragments = fileContents.length / DATASIZE + 1;
        System.out.println("Number of packets to send in total = " + numberOfFragments);


        while (receivedAcks.size() != numberOfFragments) {
            // keep track of where we are in the data

            int fragmentCounter;

            int lowerbound;
            int upperbound;

            //define window boundaries
            if (receivedAcks.isEmpty()) {
                lowerbound = 1;
                upperbound = WINDOWSIZE;
            } else {
                lowerbound = determineLowerbound(receivedAcks);
                upperbound = determineUpperbound(receivedAcks, numberOfFragments);
            }

            // create and send a new packet of appropriate size
            for (fragmentCounter = lowerbound; fragmentCounter <= upperbound; fragmentCounter++) {
                if (!receivedAcks.contains(fragmentCounter)) {
                    filePointer = DATASIZE * (fragmentCounter - 1);
                    Integer[] pkt = createPacket(fragmentCounter, fileContents, filePointer);
                    getNetworkLayer().sendPacket(pkt);
                    System.out.println("Sent one packet with header=" + pkt[0]);
                }
            }


            // schedule a timer for 1000 ms into the future, just to show how that works:
//        client.Utils.Timeout.SetTimeout(DELAYTIME, this, 28);

            // and loop and sleep; you may use this loop to check for incoming acks...
            receivedAcks = checkForAcks(receivedAcks);
        }
    }

    @Override
    public void receiver() {
        System.out.println("Receiving...");

        // create the array that will contain the file contents
        // note: we don't know yet how large the file will be, so the easiest (but not most efficient)
        //   is to reallocate the array every time we find out there's more data
        Integer[] fileContents = new Integer[0];
        int highestPacket = 0;
        Map<Integer, Integer[]> packetMap = new HashMap<>();
        int newPacketPosition = 0;

        // loop until we are done receiving the file
        boolean stop = false;
        while (!stop) {

            // try to receive a packet from the network layer
            Integer[] packet = getNetworkLayer().receivePacket();

            // if we indeed received a packet
            if (packet != null) {

                int packetIndex = packet[0];

                // tell the user
                System.out.println("Received packet, length="+packet.length+"  first byte="+packetIndex );

                // append the packet's data part (excluding the header) to the fileContents array, first making it larger
                if(packetIndex > highestPacket) highestPacket = packetIndex;

                if (!packetMap.keySet().contains(packetIndex)) {
                    Integer[] packetData = Arrays.copyOfRange(packet, 1, packet.length);
                    packetMap.put(packetIndex, packetData);
                    sendAck(packet);
                }

                if (packetIndex == Collections.max(packetMap.keySet()) - WINDOWSIZE) {
                    sendAck(packet);
                }

                if (!allPacketsReceived(packetMap, highestPacket) && packetMap.keySet().size() == Collections.max(packetMap.keySet())) stop = true;


            } else {
                // wait ~10ms (or however long the OS makes us wait) before trying again
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    stop = true;
                }
            }
        }

        for (int index = 1; index <= packetMap.size(); index++) {
            int datalen = packetMap.get(index).length;
            fileContents = Arrays.copyOf(fileContents, newPacketPosition + datalen);
            System.arraycopy(packetMap.get(index), 0, fileContents, newPacketPosition, datalen);
            newPacketPosition = newPacketPosition + packetMap.get(index).length;
        }

        // write to the output file
        Utils.setFileContents(fileContents, getFileID());
    }

    private boolean allPacketsReceived(Map<Integer, Integer[]> packetMap, int highestPacket) {
        boolean allPacketsReceived = true;
        for (int i = 0; i < highestPacket; i++) {
            if (!packetMap.keySet().contains(i) && packetMap.get(highestPacket).length < DATASIZE) {
                allPacketsReceived = false;
            }
        }
        return allPacketsReceived;
    }

    // create a new packet of appropriate size
    private Integer[] createPacket(int i, Integer[] fileContents, int filePointer) {
        Integer[] pkt = null;
        int datalen = Math.min(DATASIZE, fileContents.length - filePointer);
        if (datalen > -1) {
            pkt = new Integer[HEADERSIZE + datalen];
            pkt[0] = i;
            System.arraycopy(fileContents, filePointer, pkt, HEADERSIZE, datalen);
        }
        return pkt;
    }

    // create a new packet of appropriate size
    private Integer[] createEmptyPacket(int i) {
        return new Integer[]{i};
    }

    private void sendAck(Integer[] packet) {
        Integer[] ackPacket = createEmptyPacket(packet[0]);
        getNetworkLayer().sendPacket(ackPacket);
        System.out.println("Send ACK packet for fragmented packet with header = " + packet[0]);
    }

    // determine lowerbound window size
    private int determineLowerbound(Set<Integer> receivedAcks) {
        if (!receivedAcks.isEmpty()) {
            for (int a = 1; a <= Collections.max(receivedAcks); a++) {
                if (!receivedAcks.contains(a)) {
                    return a;
                }
            }
            return Collections.max(receivedAcks);
        }
        return 1;
    }

    // determine upperbound window size
    private int determineUpperbound(Set<Integer> receivedAcks, int numberOfFragments) {
        int upperbound = determineLowerbound(receivedAcks) + WINDOWSIZE;
        if (upperbound > numberOfFragments) {
            upperbound = numberOfFragments;
        }
        return upperbound;
    }

    @Override
    public void TimeoutElapsed(Object tag) {
        int z=(Integer)tag;
        // handle expiration of the timeout:
        System.out.println("Timer expired with tag="+z);
    }

    private Set<Integer> checkForAcks(Set<Integer> receivedAcks) {
        for (int i = 1; i <= NUMBEROFACKCHECKS; i++) {
            try {
                Thread.sleep(DELAYTIME / NUMBEROFACKCHECKS);
                Integer[] ackPacket = getNetworkLayer().receivePacket();
                if (ackPacket != null) {
                    System.out.println("Received ACK packet with header= " + ackPacket[0]);
                    receivedAcks.add(ackPacket[0]);
                    return receivedAcks;
                }
            } catch (InterruptedException e) {
                System.out.print("Mag niet gebeuren.");
                break;
            }
        }
        return receivedAcks;
    }
}


