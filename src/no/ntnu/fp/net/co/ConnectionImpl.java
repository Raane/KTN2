/*
 * Created on Oct 27, 2004
 */
package no.ntnu.fp.net.co;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import no.ntnu.fp.net.admin.Log;
import no.ntnu.fp.net.cl.ClException;
import no.ntnu.fp.net.cl.ClSocket;
import no.ntnu.fp.net.cl.KtnDatagram;
import no.ntnu.fp.net.cl.KtnDatagram.Flag;

/**
 * Implementation of the Connection-interface. <br>
 * <br>
 * This class implements the behaviour in the methods specified in the interface
 * {@link Connection} over the unreliable, connectionless network realised in
 * {@link ClSocket}. The base class, {@link AbstractConnection} implements some
 * of the functionality, leaving message passing and error handling to this
 * implementation.
 * 
 * @author Sebj?rn Birkeland and Stein Jakob Nordb?
 * @see no.ntnu.fp.net.co.Connection
 * @see no.ntnu.fp.net.cl.ClSocket
 */
public class ConnectionImpl extends AbstractConnection {
	
    /** Keeps track of the used ports for each server port. */
    private static Map<Integer, Boolean> usedPorts = Collections.synchronizedMap(new HashMap<Integer, Boolean>());
    private ClSocket socket = new ClSocket();
    private final int MAXRESENDS = 5;
    private final int MAXRERECEIVES = 5;
    private int resends = 0;
    private int rereceives = 0;
    private KtnDatagram lastDatagramReceived = null;
    /**
     * Initialise initial sequence number and setup state machine.
     * 
     * @param myPort
     *            - the local port to associate with this connection
     */
    public ConnectionImpl(int myPort) {
        this.myPort = myPort;
		this.myAddress = getIPv4Address();
		state = state.CLOSED;
    }


	public ConnectionImpl(String myAddress, int myPort, String remoteAddress, int remotePort) {
		this.myAddress = myAddress;
		this.myPort = myPort;
		this.remoteAddress = remoteAddress;
		this.remotePort = remotePort;
		state = state.SYN_RCVD;
	}

	private String getIPv4Address() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        }
        catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }

    /**
     * Establish a connection to a remote location.
     * 
     * @param remoteAddress
     *            - the remote IP-address to connect to
     * @param remotePort
     *            - the remote portnumber to connect to
     * @throws IOException
     *             If there's an I/O error.
     * @throws java.net.SocketTimeoutException
     *             If timeout expires before connection is completed.
     * @see Connection#connect(InetAddress, int)
     */
    public void connect(InetAddress remoteAddress, int remotePort) throws IOException,
            SocketTimeoutException {
    	
		this.remoteAddress = remoteAddress.getHostAddress();
		this.remotePort = remotePort;
    	//Constructing and sending the syn
    	KtnDatagram syn = constructInternalPacket(Flag.SYN);
        try {
			simplySendPacket(syn);
		} catch (ClException e) {
			e.printStackTrace();
		}
		
		//Waiting for the synAck
		KtnDatagram synAck = receiveAck();
		//Sends ack
		if(synAck!=null) {
			this.remotePort = synAck.getSrc_port();
		} else {
			throw new SocketTimeoutException();
		}
		if(synAck.getFlag()==Flag.SYN_ACK) {
			state = State.ESTABLISHED;
		}
		sendAck(synAck, false);
    }

    /**
     * Listen for, and accept, incoming connections.
     * 
     * @return A new ConnectionImpl-object representing the new connection.
     * @see Connection#accept()
     */
    public Connection accept() throws IOException, SocketTimeoutException {
    	state = State.LISTEN;
        //Wait for incoming connection (syn)
    	KtnDatagram syn;
    	do {
    		syn = receivePacket(true);
    		System.out.println(syn);
    	} while (syn == null || syn.getFlag() != Flag.SYN);
    	this.remoteAddress = syn.getSrc_addr();
    	this.remotePort = syn.getSrc_port();
    	state = State.SYN_RCVD;
    	ConnectionImpl newConnection = new ConnectionImpl(this.myAddress, getNewPort(), this.remoteAddress, this.remotePort);

    	//Send syn ack (should be a try catch here (maybe))
    	newConnection.sendAck(syn, true);
    	
    	//Receive ack
    	KtnDatagram ack = newConnection.receiveAck();
    	
    	if(ack == null || ack.getFlag() != Flag.ACK) {
    		throw new SocketTimeoutException();
    	}
    	newConnection.state = State.ESTABLISHED;
    	//return the new connection that just came in
    	state = State.LISTEN;
    	return (Connection) newConnection;
    }

    /**
     * Send a message from the application.
     * 
     * @param msg
     *            - the String to be sent.
     * @throws ConnectException
     *             If no connection exists.
     * @throws IOException
     *             If no ACK was received.
     * @see AbstractConnection#sendDataPacketWithRetransmit(KtnDatagram)
     * @see no.ntnu.fp.net.co.Connection#send(String)
     */
    
    public void send(String msg) throws ConnectException, IOException {
    	//Construct the datagram
    	if(state != State.ESTABLISHED) throw new ConnectException("No established connection");
        KtnDatagram datagram = constructDataPacket(msg);
        //Send it and wait for ack
		KtnDatagram ack = sendDataPacketWithRetransmit(datagram);
		System.out.println("finished sending");
		if(ack == null) { //do this if no ack was received
			System.out.println("No ack received");
			if(resends < MAXRESENDS) {
				resends++;
				send(msg);
				resends = 0;
				return;
			} else {
				state = State.CLOSED;
				throw new ConnectException("CONNECTION LOST");
				// Connection is considered lost
			}
		} else { //Do this if an ack was received
			System.out.println("ack received");
			if(!isValid(ack)) {
				//Not a valid ack
			} else if(ack.getAck() > nextSequenceNo-1) {
				//seq number is too high
			} else if (ack.getAck() < nextSequenceNo-1) {  //Received a duplicate older ack, resending the msg
				nextSequenceNo--;
				send(msg);
				return;
			} else { //The ack was valid *do dance*
				System.out.println("Valid ACK received");
			}
		}
    }

    /**
     * Wait for incoming data.
     * 
     * @return The received data's payload as a String.
     * @see Connection#receive()
     * @see AbstractConnection#receivePacket(boolean)
     * @see AbstractConnection#sendAck(KtnDatagram, boolean)
     */
    public String receive() throws ConnectException, IOException {
        //Receiving
    	KtnDatagram datagram = null; 
    	try {
    		datagram = receivePacket(false);	
		} catch (EOFException e) { //got a FIN
			state = State.CLOSE_WAIT;
			throw new EOFException();
		}
    	// Evaluating the received msg and acking accordingly
		if(datagram == null) { // Receive timeout
			if(rereceives < MAXRERECEIVES) {
				rereceives++;
				String msg = receive();
				rereceives = 0;
				return msg;
			} else {
				state = State.CLOSED;
				throw new ConnectException();
			}
		} else { // A packet was received
			if(!isGhostPacket(datagram)) {
				System.out.println("Not a ghost packet");
				if(isValid(datagram)) {
					if(lastDatagramReceived != null && datagram.getSeq_nr()-1!=lastDatagramReceived.getSeq_nr()) {
						//making sure it's not the first packet comming in and checking if the datagram is the expected one
						System.out.println("1");
						sendAck(lastDatagramReceived, false); //sending a duplicate ack for last packet
						return receive();
					} else {
						System.out.println("2");
						sendAck(datagram, false);
						lastDatagramReceived = datagram;
						return (String) datagram.getPayload();
					}
				} else { //is not valid
 					if(lastDatagramReceived != null) {  //checking if this was the first received package
 						System.out.println("3");
 						sendAck(lastDatagramReceived, false); //Requests a resend
 						return receive();
 					}
 					return receive();
				}
			} else { //ghost package
				System.out.println("Received a ghost package");
				return receive();
			}
		}
    }

	/**
     * Close the connection.
     * 
     * @see Connection#close()
     */
    public void close() throws IOException {
		KtnDatagram ack, datagram, finAck = null;
		if(state == State.CLOSE_WAIT){
			sendAck(lastDatagramReceived, false);
			try {
				Thread.currentThread().sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			} //Wait for client to be ready to recieve FIN
			try {
				datagram = constructInternalPacket(Flag.FIN);
				simplySendPacket(datagram);
			} catch (ClException e) {
				e.printStackTrace();
			}
			ack = receiveAck();
			state = State.CLOSED;
		}
		else if(state == State.ESTABLISHED){
			try {
				datagram = constructInternalPacket(Flag.FIN);
				simplySendPacket(datagram);
			} catch (ClException e) {
				e.printStackTrace();
			}
			state = State.FIN_WAIT_1;
			ack = receiveAck();
			if(ack == null){
				if(resends < MAXRESENDS){
					reclose();
					return;	
				}
				else state = State.CLOSED;
			}
			state = State.FIN_WAIT_2;
			finAck = receiveAck();
			if(finAck == null) finAck = receiveAck();
			if(finAck != null) { 
				sendAck(finAck, false);
			}
		}
		state = State.CLOSED;
	}

    private void reclose() {
    	state = State.ESTABLISHED;
		try {
			close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	/**
     * Test a packet for transmission errors. This function should only called
     * with data or ACK packets in the ESTABLISHED state.
     * 
     * @param packet
     *            Packet to test.
     * @return true if packet is free of errors, false otherwise.
     */
    protected boolean isValid(KtnDatagram packet) {
        return packet.getChecksum()==packet.calculateChecksum();
    }
    
    private int getNewPort() { //This can be done safer, but what are the odds for an actual collition...
    	return (int) (Math.random()*30000+10000);
    }
    private boolean isGhostPacket(KtnDatagram datagram) {
    	if(datagram.getSrc_addr() != null) {
    		return !(datagram.getSrc_addr().equals(remoteAddress) && datagram.getSrc_port()==remotePort);
    	}
    	return true;
    }
}