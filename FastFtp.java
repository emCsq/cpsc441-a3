import java.io.*;
import java.lang.*;
import java.net.*;
import java.util.*;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.DatagramSocket;
import java.util.concurrent.*;

import cpsc441.a3.*;

/**
 * FastFtp Class
 * 
 * FastFtp implements a basic FTP application based on UDP data transmission.
 * The main mehtod is send() which takes a file name as input argument and send the file 
 * to the specified destination host.
 * 
 */
public class FastFtp {
	
	Socket socket;
	DatagramSocket clientSocket = null;
	DataOutputStream outputstream;
	DataInputStream inputstream;
	byte responseCode;
	BufferedReader inFromUser;
	InetAddress IPAddress;
	TxQueue queue;
	TimeoutHandler timeout;
	Timer timer = null;
	
	int offset = 0;
	int ref;
	boolean reachEOF = false;
	
	int queueWindowSize = 0;
	String serverNameGL = null;
	int serverPortNum = 0;
	int timeoutInterval = 0;
	int ackNum = 0;
	int receiveDataSize;
	
	
    /**
     * Constructor to initialize the program 
     * 
     * @param windowSize	Size of the window for Go-Back_N (in segments)
     * @param rtoTimer		The time-out interval for the retransmission timer (in milli-seconds)
     */
	public FastFtp(int windowSize, int rtoTimer) {
		setQueueWindowSize(windowSize);
		setTimeoutInterval(rtoTimer);
		queue = new TxQueue(windowSize);
	}
	
	/**
	 * Set method to ensure timeout interval is set
	 * 
	 * @param rtoTimer 		The time-out interval for the retransmission timer (in milli-seconds)
	 */
	public void setTimeoutInterval (int rtoTimer) {
		timeoutInterval = rtoTimer;
	}
	
	/**
	 * Set method to ensure that the server port number is set locally
	 *
	 * @param serverPort	The port number that will be used
	 */
	public void setServerPort (int serverPort) {
		serverPortNum = serverPort;
	}
	
	/**
	 * Set method to ensure that the server name is set locally
	 *
	 * @param tmpServerName		The name of the server that'll be used throughout (e.g. localhost)
	 */
	public void setServerName (String tmpServerName) {
		serverNameGL = tmpServerName;
	}
	/**
	 * Set method to store the window size 
	 *
	 * @param windowSize 	Size of the window 
	 */
	public void setQueueWindowSize (int windowSize) {
		queueWindowSize = windowSize;
	}
	
	/**
	 * A GET method that is effective for retrieving the server port to be used in other classes
	 *
	 * @return		the local server port number
	 */
	public int getServerPort () {
		return serverPortNum;
	}
	
	/**
	 * A set method such that the receive Data size can be retrieved later
	 *
	 * @param size	the size of the data set that'll be used to receive data
	 */
	public void setReceiveDataSize (int size) {
		receiveDataSize = size;
	}
	
	/**
	 * A GET method to retrieve the previously set data size for the retrieval part
	 *
	 * @return		the size of the data array previously set
	 */
	public int getReceiveDataSize () {
		return receiveDataSize;
	}


    /**
     * Sends the specified file to the specified destination host:
     * 1. send file name and receiver server confirmation over TCP
     * 2. send file segment by segment over UDP
     * 3. send end of transmission over tcp
     * 3. clean up
     * 
     * @param serverName	Name of the remote server
     * @param serverPort	Port number of the remote server
     * @param fileName		Name of the file to be transferred to the remote server
     */
	public void send(String serverName, int serverPort, String fileName) {
		//store local variables on the global level via SET methods
		setServerPort(serverPort);
		setServerName(serverName);
		try {
			//open TCP connection
			socket = new Socket(serverName, serverPort);
			clientSocket = new DatagramSocket(socket.getLocalPort());
			
			//start the receiver thread
			ReceiverThread rcvThread = new ReceiverThread(this, clientSocket);
			rcvThread.start();
			
			//writing file name to TCP socket
			outputstream = new DataOutputStream (socket.getOutputStream());
			try {
				outputstream.writeUTF(fileName);
				outputstream.flush();
			} catch (IOException e) {
				System.out.println("Error: " + e.getMessage());
			}
			
			//reading server response from TCP socket
			inputstream = new DataInputStream(socket.getInputStream());
			try {
				responseCode = inputstream.readByte();
			} catch (IOException e) {
				System.out.println("Error: " + e.getMessage());
			}
			
			//UDP part
			try {
				FileInputStream fis = new FileInputStream(fileName);
				byte[] sendDataRAW = new byte[1000];
				int numBytes = 0;
				
				//while we haven't reached the End of File, do the following
				while (reachEOF == false) {
					//read file (via FileName) by chunks of 1000 (or less, in the case of the final segment)
					offset = 0;
					numBytes = 0;
					//read 1000 individual bytes and store in a row
					while (numBytes != 1000) {
						ref = fis.read(sendDataRAW, offset, 1);
						if (ref == -1) {
							reachEOF = true;
							break;
						}
						offset = offset+1;
						numBytes++;
					}
					int arraySize = numBytes;			
					
					//clean up raw data into a neat byte array that will be sent for processing
					byte[] sendData = new byte[numBytes];
					setReceiveDataSize(numBytes);
					System.arraycopy(sendDataRAW, 0, sendData, 0, numBytes);
					
					//make segment by providing ACK number and the data itself before incrementing the ACK number
					Segment newSeg = new Segment(ackNum, sendData);
					ackNum++;
					
					//check queue full or not, if so, stall
					while (queue.isFull() == true) {
						Thread.yield();
					}
					//send the segment created to be processed
					processSend(newSeg);
				}
				
				//check if everything has been transmitted, if not, stall until all segments have been transmitted
				while (queue.isEmpty() != true) {
					Thread.yield();
				}				
				
				//end transmission
				outputstream.writeByte(0);
				outputstream.flush();
				
				outputstream.close();
			} catch (Exception e) {
				System.out.println("Error: " + e.getMessage());
			}
	
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
		}
		
	}
	
	/**
	 * A synchronized method that processes the segment created earlier
	 *
	 * @param seg	the segment that will be processed to send through UDP
	 */
	public synchronized void processSend (Segment seg) {
		try {
			IPAddress = InetAddress.getByName(serverNameGL);
			
			//convert segment into sendable DatagramPacket
			DatagramPacket sendPacket = new DatagramPacket(seg.getBytes(), seg.getLength()+4, IPAddress, serverPortNum);
			
			//send over the final packet to the server
			clientSocket.send(sendPacket);
			
			//add the segment to the queue and start the timer if first segment on queue
			queue.add(seg);
			if (queue.size() == 1) {
				timer = new Timer(true);
				timer.schedule(new TimeoutHandler(this), timeoutInterval);
			}
			
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
		}
	}
	
	/**
	 * A synchronized method that processes acknowledgements via the received packet
	 *
	 * @param ack 	A segment that will have its acknowledgement processed
	 */
	public synchronized void processACK (Segment ack) {
		int ackSeqNum = ack.getSeqNum();
		Segment currentSeg = queue.element();
		//Segment currentSeg = new Segment(queue.element());
		if (currentSeg != null) {
			int currentSegSeqNum = currentSeg.getSeqNum();
			//if the received sequence number is greater than the head segment's sequence number, do the following
			if (ackSeqNum > currentSegSeqNum) {
				//cancel the timer
				timer.cancel();
				//remove the segments from the queue up to (but not including) N while the following holds true
				while (ackSeqNum > currentSegSeqNum) {
					try {
						//remove the head of the queue 
						queue.remove();
						//retrieve the newest head of the queue...
						currentSeg = queue.element();
						//... provided that it exists. If not, break the loop
						if (currentSeg == null) {
							break;
						} else {
							currentSegSeqNum = currentSeg.getSeqNum();
						}
					} catch (Exception e) {
						System.out.println("Error: " + e.getMessage());
					}
				}
				//if timer isn't empty by the end of this, reset the timer
				if (queue.isEmpty() == false) {
					timer = new Timer(true);
					timer.schedule(new TimeoutHandler(this), timeoutInterval);
				}
			}
		}		
	}
	
	/**
	 * A synchronized method that processes the event of a Timeout
	 *
	 */
	public synchronized void processTimeout() {
		//retrieves the objects in the current queue
		Segment[] queueArrayList = queue.toArray();
		//send all the objects to the server
		for (int i=0; i < queueArrayList.length; i++) {
			Segment reSeg = queueArrayList[i];
			DatagramPacket sendPacket = new DatagramPacket(reSeg.getBytes(), reSeg.getLength()+4, IPAddress, serverPortNum);
			try {
				clientSocket.send(sendPacket);
			} catch (Exception e) {
				System.out.println("Error: " + e.getMessage());
			}
		}
		//reset timer if queue is not empty
		if (queue.isEmpty() != true) {
			timer = new Timer(true);
			timer.schedule(new TimeoutHandler(this), timeoutInterval);
		}
	}
	
    /**
     * A simple test driver
     * 
     */
	public static void main(String[] args) {
		int windowSize = 10; //segments
		int timeout = 100; // milli-seconds
		
		String serverName = "localhost";
		String fileName = "";
		int serverPort = 0;
		
		// check for command line arguments
		if (args.length == 3) {
			// either privide 3 paramaters
			serverName = args[0];
			serverPort = Integer.parseInt(args[1]);
			fileName = args[2];
		}
		else if (args.length == 2) {
			// or just server port and file name
			serverPort = Integer.parseInt(args[0]);
			fileName = args[1];
		}
		else {
			System.out.println("wrong number of arguments, try agaon.");
			System.out.println("usage: java FastFtp server port file");
			System.exit(0);
		}

		
		FastFtp ftp = new FastFtp(windowSize, timeout);
		
		System.out.printf("sending file \'%s\' to server...\n", fileName);
		ftp.send(serverName, serverPort, fileName);
		System.out.println("file transfer completed.");
	}
	
}
