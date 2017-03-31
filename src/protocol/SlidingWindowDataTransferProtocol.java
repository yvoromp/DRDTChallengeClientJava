package protocol;

import client.Utils;
import java.util.Arrays;
//import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
//import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;

//CODE entire class
public class SlidingWindowDataTransferProtocol extends IRDTProtocol {
	static final int HEADERSIZE=1;   // number of header bytes in each packet
	static final int DATASIZE= 512;   // max. number of user data bytes in each packet
	static final int END_HEADER = 166;
	static final int ACK_HEADER = 42;
	static final int NACK_HEADER = 255;
	private int LastAckReceived = -1;
	private int SendWindowSize = 4;
	private int ReceiveWindowSize = 4;
	private int LastFrameSend = 0;
	private int LastFrameReceived = -1;
	static final int TIMEOUT = 8000;
	static final int RECEIVER_TIMEOUT = TIMEOUT/4;
	private Map<Integer, Integer[]> bufferedPackets;
	private ConcurrentHashMap<Integer, Integer[]> unAcknowledgedPackets = new ConcurrentHashMap<>();
	private ConcurrentSkipListSet<Integer> receivedAcks = new ConcurrentSkipListSet<>();

	private class receivePackets implements Runnable {

		@Override
		public void run() {
			boolean stop = false;
			while(!stop) {
				// Get packet from server
				Integer[] receivedPkt = getNetworkLayer().receivePacket();
				if (receivedPkt != null) {
					int header = receivedPkt[0];
					int ackNumber = receivedPkt[1];
					if(header == ACK_HEADER && isInSlidingWindow(
						ackNumber)) {

						System.out.println("Received ACK " + ackNumber);
						receivedAcks.add(new Integer(ackNumber));

						// Stop time out of ackNumber
						acknowledgePacket(ackNumber);
						System.out.println("Still unack: " + unAcknowledgedPackets);

					} else if(header == NACK_HEADER
							&& isInSlidingWindow(ackNumber)
							&& unAcknowledgedPackets.containsKey(new Integer(ackNumber)))
					{
						System.out.println("Received NACK " + ackNumber);
						//Resend given packet.
						TimeoutElapsed(unAcknowledgedPackets.get(new Integer(ackNumber)));

						// Stop time out of packets with sequence number < ackNumber
						stopTimeOuts(ackNumber - 1);
						System.out.println("Still unack: " + unAcknowledgedPackets);

					}
				}

				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					stop = true;
				}
			}
		}
	}

	/**
	 * Stop time out for all packets with a sequence number <= ackNumber
	 */
	public void stopTimeOuts(int ackNumber) {
		for(Integer seqNumber: unAcknowledgedPackets.keySet()) {
      if(seqNumber <= ackNumber) {
				acknowledgePacket(seqNumber);
      }
    }
	}

	/**
	 * Remove time out of given packet and remove it from the list of unAcknowledges packets.
	 * @param seqNumber
	 */
	public void acknowledgePacket(Integer seqNumber) {
		Utils.Timeout.stopTimeOut(unAcknowledgedPackets.get(seqNumber));
		unAcknowledgedPackets.remove(seqNumber);
	}

	@Override
	public void sender() {
		System.out.println("Sending...");

		// read from the input file
		Integer[] fileContents = Utils.getFileContents(getFileID());

		// keep track of where we are in the data
		int filePointer = 0;

		// start listening for acks
		(new Thread(new receivePackets())).start();

		// send packets
		while(filePointer < fileContents.length) {
			// create a new packet of appropriate size
			assert(isInSlidingWindow(LastFrameSend));
			Integer[] pkt = createPacket(filePointer, fileContents, LastFrameSend);

			// send the packet to the network layer
			getNetworkLayer().sendPacket(pkt);
			System.out.println("Sent one packet with header=" + pkt[0]);

			// Set time out and wait for ack
			unAcknowledgedPackets.put(pkt[0], pkt);
			waitForAck(pkt);

			//Update filePointer
			filePointer += DATASIZE;
			LastFrameSend++;
		}

		// Send end of file header
		sendEOF();
	}

	/**
	 * Wait until interrupted.
	 */
	public void doWait() {
		try {
			Thread.sleep(10);
		} catch (InterruptedException e) {
			return;
		}
	}


	/**
	 * Check if packet with given sequence number is in the Sliding Window
	 */
	public boolean isInSlidingWindow(int seqNumber) {
		return (seqNumber > LastAckReceived && seqNumber <= LastAckReceived + SendWindowSize);
	}

	/**
   * Wait for acknowledgment of given packet, if received stop the timeout of that
	 * given packet.
	 * @param sendPacket Packet that is send.
	 */
	public void waitForAck(Integer[] sendPacket) {
		// schedule a timer for 1000 ms into the future, just to show how that works:
		Utils.Timeout.SetTimeout(TIMEOUT, this, sendPacket);
		int seqSendPacket = sendPacket[0];

		// Check if a new ack has arrived
		if(!receivedAcks.isEmpty()) {
			updateLAR();
		}

		// Wait for new ack if we have reached the end of the sliding window
		if (isInSlidingWindow(seqSendPacket) && !isInSlidingWindow(seqSendPacket + 1)) {
			System.out.println("Wait for new ack, LAR " + LastAckReceived);
		}
		while (isInSlidingWindow(seqSendPacket) && !isInSlidingWindow(seqSendPacket + 1)) {
			doWait();
			updateLAR();
		}
	}

	/**
	 * Update LAR by checking if any acks have been received.
	 */
	public void updateLAR() {
		if(receivedAcks.isEmpty()) {
			return;
		}

		//System.out.println("Received acks: ");
		boolean updated = true;
		int oldLAR = LastAckReceived;
		while(updated) {
			updated = false;
			for (Integer ackNumber : receivedAcks) {
				System.out.println(ackNumber + ", ");
				if (ackNumber == LastAckReceived + 1) {
					LastAckReceived++;
					receivedAcks.remove(ackNumber);
					updated = true;
				}
			}
		}

		//System.out.println("Updated LAR to " + LastAckReceived);
		if(LastAckReceived > oldLAR) {
			System.out.println("New sliding window " + (LastAckReceived + 1) + " - " + (LastAckReceived
					+ SendWindowSize));
		}
	}

	/**
	 * Check if in receiver window
	 */
	public boolean isInReceiverWindow(int seqNumber) {
		return (seqNumber > LastFrameReceived && seqNumber - LastFrameReceived <= ReceiveWindowSize);
	}

	public Integer[] createPacket(int filePointer, Integer[] fileContents, int header) {
		// create a new packet of appropriate size
		int datalen = Math.min(DATASIZE, fileContents.length - filePointer);
		Integer[] pkt = new Integer[HEADERSIZE + datalen];
		// create header
		pkt[0] = header;
		// copy databytes from the input file into data part of the packet, i.e., after the header
		System.arraycopy(fileContents, filePointer, pkt, HEADERSIZE, datalen);

		return pkt;
	}

	public void sendEOF() {
		// Wait until all messages are send
		while(!unAcknowledgedPackets.isEmpty()){
			doWait();
		}

		Integer[] pkt = {END_HEADER};
		getNetworkLayer().sendPacket(pkt);
		System.out.println("Sent EOF header");

		// schedule a timer for 1000 ms into the future, just to show how that works:
		Utils.Timeout.SetTimeout(TIMEOUT, this, pkt);

		// Wait for EOF ack
		boolean stop = false;
		while (!stop) {
			// Get packet from server
			Integer[] receivedPkt = getNetworkLayer().receivePacket();

			if(receivedPkt != null && receivedPkt[0] == ACK_HEADER && receivedPkt[1] == END_HEADER) {
				System.out.println("Received EOF ack " + receivedPkt[1]);
				stop = true;
			}

			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				stop = true;
			}
		}

	}

	public void sendAck(int seqNumber) {
		Integer[] pkt = {ACK_HEADER, seqNumber};
		getNetworkLayer().sendPacket(pkt);
		System.out.println("Send ACK " + seqNumber);
	}

	public void sendNack(int seqNumber) {
		Integer[] pkt = {NACK_HEADER, seqNumber};
		getNetworkLayer().sendPacket(pkt);
		System.out.println("Send NACK " + seqNumber);
	}

	@Override
	public void TimeoutElapsed(Object tag) {
		if(tag instanceof Integer[]) {
			Integer[] pkt = (Integer[])tag;
			//resend
			getNetworkLayer().sendPacket(pkt);
			System.out.println("Resend packet with header=" + pkt[0]);

			waitForAck(pkt);
		}
	}

	@Override
	public void receiver() {
		System.out.println("Receiving...");

		// create the array that will contain the file contents
		// note: we don't know yet how large the file will be, so the easiest (but not most efficient)
		//   is to reallocate the array every time we find out there's more data
		Integer[] fileContents = new Integer[0];
		bufferedPackets = new HashMap<>();

		// loop until we are done receiving the file
		boolean stop = false;
		Date timeOut = new Date();
		timeOut.setTime(timeOut.getTime() + RECEIVER_TIMEOUT);
		while (!stop) {

			// try to receive a packet from the network layer
			Integer[] packet = getNetworkLayer().receivePacket();

			// if we indeed received a packet
			if (packet != null) {
				// Get header (seq number)
				int seqNumber = packet[0];

				// tell the user
				System.out.println("Received packet, length=" + packet.length + "  first byte=" + seqNumber);

				// Check if packet only contains the EOF header
				if (packet.length == HEADERSIZE) {
					// Send ACK back
					sendAck(seqNumber);
					break;
				}

				// Check if sequence number is expected
				if(isInReceiverWindow(seqNumber)) {
					System.out.println("Current buffer: " + bufferedPackets);

					// Check if this packet can directly be appended (eg its sequence number equals LFR + 1)
					if(seqNumber == LastFrameReceived + 1) {

						// Append this packet (and possibly some from buffer) to the file
						do {
							LastFrameReceived++;
							// Send ACK back
							sendAck(LastFrameReceived);
							fileContents = appendToFile(fileContents, packet);
							//System.out.println("Current buffer: " + bufferedPackets + ", LFR " + LastFrameReceived);
							packet = bufferedPackets.remove(LastFrameReceived + 1);
						} while(packet != null);

					} else {
						// Check if this packet is already buffered.
						if(bufferedPackets.containsKey(seqNumber)) {
							System.out.println("Already buffered packet " + seqNumber + " -> Dropped packet.");
						} else {
							System.out.println("Still need packet " + (LastFrameReceived + 1) + " hence packet " + seqNumber + " will be buffered.");
							bufferedPackets.put(seqNumber, packet);
						}
						sendAck(seqNumber);
					}
				} else {
					System.out.println(String.format("Received packet not in window [%d,%d]", LastFrameReceived + 1, LastFrameReceived + ReceiveWindowSize));
					System.out.println("Dropped packet " + seqNumber);
				}

				//Tell which file is expected next
				sendNack(LastFrameReceived+1);
				timeOut.setTime(timeOut.getTime() + RECEIVER_TIMEOUT);
			}else{
				// wait ~10ms (or however long the OS makes us wait) before trying again
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					stop = true;
				}

			}

			//Check if timer is done, if so resend NACK for LFR + 1
			if(timeOut.before(new Date())) {
				System.out.println("NACK timeout expired, send NACK " + (LastFrameReceived + 1));
				sendNack(LastFrameReceived + 1);
				timeOut.setTime(timeOut.getTime() + RECEIVER_TIMEOUT);
			}
		}

		// write to the output file
		Utils.setFileContents(fileContents, getFileID());
	}

	/**
	 * append the packet's data part (excluding the header) to the fileContents array, first making it larger
	 * @param fileContents
	 * @param packet
	 * @return
	 */
	public Integer[] appendToFile(Integer[] fileContents, Integer[] packet) {
		System.out.println("APPEND packet " + packet[0] + " to file");
		int oldlength = fileContents.length;
		int datalen = packet.length - HEADERSIZE;
		fileContents = Arrays.copyOf(fileContents, oldlength + datalen);
		System.arraycopy(packet, HEADERSIZE, fileContents, oldlength, datalen);
		return fileContents;
	}
}