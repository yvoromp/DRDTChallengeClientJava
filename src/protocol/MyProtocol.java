package protocol;

import java.util.Arrays;
import client.*;

public class MyProtocol extends IRDTProtocol {

	// change the following as you wish:
	static final int HEADERSIZE=1;   // number of header bytes in each packet
	static final int DATASIZE=512;   // max. number of user data bytes in each packet
	private Integer[] copySmallCopy;
	private int recCopy;

	@Override
	public void sender() {
		System.out.println("Sending...");

		// read from the input file
		Integer[] fileContents = Utils.getFileContents(getFileID());

		// keep track of where we are in the data
		int filePointer = 0;

		//count amount of pkt's sender has to send
		double packetCounter = 0;

		// create a new packet of appropriate size
		double datalen = Math.min(DATASIZE, (fileContents.length - filePointer));
		//		System.out.println("datalen =  " + datalen);
		double totalLength = fileContents.length; 
		//		System.out.println("TotalLength = " + totalLength);
		//Integer[] totalPkt = new Integer[HEADERSIZE + totalLength];
		packetCounter = (double)Math.ceil(((double)HEADERSIZE + (double)totalLength) / (double)datalen);
		double remainder = ((double)totalLength) % (double)datalen;
		//		System.out.println("remainder = " + remainder);
		//		System.out.println("packetCounter = " + packetCounter);
		double i=0;
		while((int)i < (int)packetCounter){

			int firstArr = ((int)packetCounter == ((int)i+1))? 111 : 123;
			datalen = (firstArr == 111) ? remainder : datalen;

			Integer [] smallCopy = (firstArr == 111) ? new Integer[(int)HEADERSIZE + (int)remainder] : new Integer[(int)HEADERSIZE + (int)datalen];  
			smallCopy[0] = firstArr;


			System.arraycopy(fileContents, filePointer, smallCopy, 1, (int)datalen);
			filePointer = filePointer+(int)datalen;


			// send the packet to the network layer
			getNetworkLayer().sendPacket(smallCopy);
			recCopy=1;
			copySmallCopy = smallCopy;
			client.Utils.Timeout.SetTimeout(5000, this, 28);

			Integer[] recPacket = getNetworkLayer().receivePacket();
			while(recPacket == null || !recPacket[0].equals(123)){
				recPacket = getNetworkLayer().receivePacket();
				try {
					Thread.sleep(10);
					
				} catch (InterruptedException e) {
					System.out.println("package recieved!!");
				}
			}
			recCopy= recPacket[0];
			System.out.println("package number recieved :  "  + i);
			System.out.println("sender recieved 0 pos :  " + recPacket[0]);
			System.out.println("Sent one packet with header="+smallCopy[0]);
			i++;
		}
		filePointer = 0;


		// schedule a timer for 1000 ms into the future, just to show how that works:
		client.Utils.Timeout.SetTimeout(5000, this, 28);

		// and loop and sleep; you may use this loop to check for incoming acks...


	}

	@Override
	public void TimeoutElapsed(Object tag) {
		int z=(Integer)tag;
		while(recCopy == 1){
			if(recCopy == 1)
			getNetworkLayer().sendPacket(copySmallCopy);
			System.out.println("Timer expired");
		}
		
	}

	@Override
	public void receiver() {
		System.out.println("Receiving...");

		// create the array that will contain the file contents
		// note: we don't know yet how large the file will be, so the easiest (but not most efficient)
		//   is to reallocate the array every time we find out there's more data
		Integer[] fileContents = new Integer[0];

		// loop until we are done receiving the file
		boolean stop = false;
		while (!stop) {

			// try to receive a packet from the network layer
			Integer[] packet = getNetworkLayer().receivePacket();

			// if we indeed received a packet
			if (packet != null) {

				// tell the user
				System.out.println("Received packet, length="+packet.length+"  first byte="+packet[0] );

				// append the packet's data part (excluding the header) to the fileContents array, first making it larger
				int oldlength=fileContents.length;
				int datalen= packet.length - HEADERSIZE;
				fileContents = Arrays.copyOf(fileContents, oldlength+datalen);
				System.arraycopy(packet, HEADERSIZE, fileContents, oldlength, datalen);
				System.out.println(packet[0]);
				Integer [] ackPacket = new Integer[1];
				System.arraycopy(packet, 0, ackPacket, 0, 1);



				//TODO
				//give ack to sender
				System.out.println("reciever sends 0 pos :  " + ackPacket[0]);
				getNetworkLayer().sendPacket(ackPacket);


				if(!packet[0].equals(123)){
					System.out.println("out of loop");
					stop=true;
				}// and let's just hope the file is now complete


			}else{
				// wait ~10ms (or however long the OS makes us wait) before trying again
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					stop = true;
				}
			}
		}

		// write to the output file
		Utils.setFileContents(fileContents, getFileID());

	}
}
