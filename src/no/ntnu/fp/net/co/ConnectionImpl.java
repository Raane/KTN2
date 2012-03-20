/*
 * Created on Oct 27, 2004
 */
package no.ntnu.fp.net.co;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
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
import no.ntnu.fp.net.co.AbstractConnection.State;

/**
 * Implementation of the Connection-interface. <br>
 * <br>
 * This class implements the behaviour in the methods specified in the interface
 * {@link Connection} over the unreliable, connectionless network realised in
 * {@link ClSocket}. The base class, {@link AbstractConnection} implements some
 * of the functionality, leaving message passing and error handling to this
 * implementation.
 * 
 * @author Sebjørn Birkeland and Stein Jakob Nordbø
 * @see no.ntnu.fp.net.co.Connection
 * @see no.ntnu.fp.net.cl.ClSocket
 */
public class ConnectionImpl extends AbstractConnection {

    /** Keeps track of the used ports for each server port. */
    private static Map<Integer, Boolean> usedPorts = Collections.synchronizedMap(new HashMap<Integer, Boolean>());
    private ClSocket mySocket;
    private boolean stop = true;

    /**
     * Initialise initial sequence number and setup state machine.
     * 
     * @param myPort
     *            - the local port to associate with this connection
     */
    public ConnectionImpl(int myPort) {
    	super();
    	this.myPort = myPort;
    	myAddress = getIPv4Address();
    	mySocket = new ClSocket();
    }
    
    public static ConnectionImpl setupListenConnection(int listenPort) {
    	ConnectionImpl listenConnection = new ConnectionImpl(listenPort);
    	listenConnection.state = State.LISTEN;
    	
    	return listenConnection;
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
    	
    	//TODO: Include packet checking? It seems to recommend against it in this state.
    	//Check the comments of the isValid method
    	
    	System.out.println("Trying to connect to: "+remoteAddress.getHostAddress()+" : "+remotePort);
    	
    	//Connect should only be called if the connection is closed.
    	if (state != State.CLOSED) {
            throw new IllegalStateException("Should only be used in CLOSED state.");
    	}
    	
    	this.remoteAddress = remoteAddress.getHostAddress();
    	this.remotePort = remotePort;
    	
    	//Constructs an internal packet using a convenient utility function	
    	KtnDatagram synPacket = constructInternalPacket(Flag.SYN);
    	
    	//Tries to send the packet, and sets the state accordingly
    	try {
    		System.out.println("mySocket: " + mySocket);
			mySocket.send(synPacket);
	    	this.state = State.SYN_SENT;
		} catch (ClException e) {
			e.printStackTrace();
		}
    	
    	//Now we should be in the SYN_SENT state
    	if (state != State.SYN_SENT) {
            throw new IllegalStateException("Error sending SYN packet");
    	}
    	
    	//Blocks and waits for a packet
    	KtnDatagram synAckPacket = null;
    	
    	//TODO: Make sure that the incoming package is the one you want. If not, continue receiving until timeout.
    	
    	try {
    		synAckPacket = receiveAck();
    	} catch (IOException e) {
    		System.out.println("Error receiving SYN_ACK");
    	}
    	KtnDatagram ackPacket = constructInternalPacket(Flag.ACK);
    	//Tries to send the packet, and sets the state accordingly
    	try {
    		mySocket.send(ackPacket);
    	} catch (ClException e) {
    		e.printStackTrace();
    	}
    	if (synAckPacket.getFlag() == Flag.SYN_ACK && synAckPacket.getDest_addr() == remoteAddress.getHostAddress()) {
    		this.remoteAddress = remoteAddress.getHostAddress();
    		this.remotePort = remotePort;
    	}
		state = State.ESTABLISHED;
		stop = false;
    }

    /**
     * Listen for, and accept, incoming connections.
     * 
     * @return A new ConnectionImpl-object representing the new connection.
     * @see Connection#accept()
     */
    public Connection accept() throws IOException, SocketTimeoutException {
    	//This method is used by the server using a listening connection.
    	if (state != State.LISTEN) {
            throw new IllegalStateException("Should only be used in LISTEN state.");
    	}
    	
    	
    	//Note: The listener connection should have a unique port we know will only be used for
    	//listening.
    	
    	
    	KtnDatagram connRequest = mySocket.receive(myPort);
    	    	
    	if (connRequest.getFlag() == Flag.SYN) {
    		state = State.SYN_RCVD;
    		KtnDatagram synAck = constructInternalPacket(Flag.SYN_ACK);
    		synAck.setDest_addr(connRequest.getSrc_addr());
    		synAck.setDest_port(connRequest.getSrc_port());
    		//TODO: Checksum stuff
    		
    		try {
				mySocket.send(synAck);
			} catch (ClException e) {
				e.printStackTrace();
			}
    	}
    	
    	KtnDatagram ackPacket = receiveAck();
    	
    	if (ackPacket.getFlag() == Flag.ACK) {
    		state = State.ESTABLISHED;
    		stop = false;
    	}
    	
    	return this;  //TODO put new shit here
    }

    /**
     * Send a message from the application.
     * 
     * TIPS: Vi burde forhandle fram en ny port her.
     * 
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
    	KtnDatagram datagram = new KtnDatagram();
    	datagram = constructDataPacket(msg);
    	datagram.setChecksum(datagram.calculateChecksum());
    	
    	try {
			mySocket.send(datagram);
		} catch (ClException e) {
			e.printStackTrace();
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
    	while (!stop) {
    		KtnDatagram datagram = mySocket.receive(myPort);
    		System.out.println("Received the text: "+ datagram.getPayload().toString());
    		KtnDatagram ackPacket = constructInternalPacket(Flag.ACK);
        	try {
        		mySocket.send(ackPacket);
        	} catch (ClException e) {
        		e.printStackTrace();
        	}
    		return datagram.getPayload().toString();
    	}
        throw new IOException("Can't receive. The connection is not established!");
    }

    /**
     * Close the connection.
     * 
     * @see Connection#close()
     */
    public void close() throws IOException {
        throw new NotImplementedException();
    }

    /**
     * Test a packet for transmission errors. This function should only called
     * with data or ACK packets in the ESTABLISHED state.
     * 
     * @param packet
     *            Packet to test.
     * @return true if packet is free of errors, false otherwise.
     */
    protected boolean isValid(KtnDatagram packet) {return packet.getChecksum() == packet.calculateChecksum();} //HELL YEA ONE LINER!
}