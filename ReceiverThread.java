import java.io.*;
import java.lang.*;
import java.net.*;
import java.util.*;

import java.net.DatagramSocket;

public class ReceiverThread extends Thread {
	
	public FastFtp fastFTP;
	public DatagramSocket clientSocket;
	public boolean terminate = false;
	
	/**
	 * Default constructor
	 *
	 * @param ftp 	initializing object of other class
	 * @param socket	the datagram socket of the UDP from another class
	 */
	public ReceiverThread (FastFtp ftp, DatagramSocket socket) {
		fastFTP = ftp;
		clientSocket = socket;
	}
	
	public void run() {
		while (terminate == false) {
			int receiveDataSize = 10;
			//int receiveDataSize = fastFTP.getReceiveDataSize();
			byte[] receiveData = new byte[receiveDataSize];
			DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
			try {
				clientSocket.receive(receivePacket);
			} catch (Exception e) {
				System.out.println("Error: " + e.getMessage());
			}
			Segment ackseg = new Segment(receivePacket);
			fastFTP.processACK(ackseg); //read ACK from above);
		}
	}
	
}