package com.buildbrighton.badge.serial;

import gnu.io.CommPortIdentifier;
import gnu.io.SerialPort;
import gnu.io.SerialPortEvent;
import gnu.io.SerialPortEventListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Enumeration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.buildbrighton.badge.BadgeDataListener;
import com.buildbrighton.badge.Badge.Mode;

public class BadgeSerialListener implements SerialPortEventListener {
	
	Logger log = LoggerFactory.getLogger(this.getClass());
	
	SerialPort serialPort;
	/** The port we're normally going to use. */
	private static final String PORT_NAMES[] = {"/dev/tty.usbserial-A600bNdu", // Mac OSX arduino mega
			"/dev/tty.usbserial-A7006TfC",
	        "/dev/ttyUSB0", // Linux
	        "COM3", // Windows
	};

	/** Buffered input stream from the port */
	private InputStream input;
	/** The output stream to the port */
	private OutputStream output;
	/** Milliseconds to block while waiting for port open */
	private static final int TIME_OUT = 2000;
	/** Default bits per second for COM port. */
	private static final int DATA_RATE = 9600;

	private BadgeDataListener badgeListener;

	private int frameIndex;
	private ByteBuffer messageBuffer;
	private ByteBuffer invertedBuffer;

	public BadgeSerialListener() {
		initialize();
	}
	
	/** 
	 * Constructor for unit testing, allows skipping serial init
	 * @param init whether to init or not.
	 */
	public BadgeSerialListener(boolean init){
		if(init){
			initialize();
		}
	}

	public void initialize() {
		CommPortIdentifier portId = null;

		@SuppressWarnings("unchecked")
		Enumeration portEnum = CommPortIdentifier.getPortIdentifiers();

		// iterate through, looking for the port
		while (portEnum.hasMoreElements()) {
			CommPortIdentifier currPortId = (CommPortIdentifier) portEnum
			        .nextElement();
			for (String portName : PORT_NAMES) {
				if (currPortId.getName().equals(portName)) {
					portId = currPortId;
					break;
				}
			}
		}

		if (portId == null) {
			log.error("Could not find COM port.");
			return;
		}

		try {
			// open serial port, and use class name for the appName.
			serialPort = (SerialPort) portId.open(this.getClass().getName(),
			        TIME_OUT);

			// set port parameters
			serialPort.setSerialPortParams(DATA_RATE, SerialPort.DATABITS_8,
			        SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);

			// open the streams
			input = serialPort.getInputStream();
			output = serialPort.getOutputStream();

			// add event listeners
			serialPort.addEventListener(this);
			serialPort.notifyOnDataAvailable(true);
		} catch (Exception e) {
			log.error("Exception while initializing", e);
			throw new RuntimeException(e);
		}
	}

	/**
	 * This should be called when you stop using the port. This will prevent
	 * port locking on platforms like Linux.
	 */
	public synchronized void close() {
		if (serialPort != null) {
			serialPort.removeEventListener();
			serialPort.close();
		}
	}

	public synchronized void write(byte[] data) throws IOException {
		output.write(data);
	}
	
	public synchronized void sendPacket(Mode mode, byte data) {
		try {
			byte[] dataPacket = new byte[] { (byte) 0xBB, (byte) 250, mode.mode(), data };
			
			byte[] inv = new byte[dataPacket.length];
			for(int i = 0; i < dataPacket.length; i++){
				int di = dataPacket[i] & 0xFF;
				di = ~di;
				inv[i] = (byte)di;
			}
			
			write(new byte[]{0x00, 0x00, 0x00, 0x00});
			write(dataPacket);
			write(inv);
			
			log.debug("Writing data to serial:");
			log.debug(Integer.toHexString(ByteBuffer.wrap(dataPacket).getInt()));
			log.debug(Integer.toHexString(ByteBuffer.wrap(inv).getInt()));
			
		} catch (IOException e) {
			log.error("Error sending data to serial port", e);
		}
	}

	/**
	 * Handle an event on the serial port. Read the data and print it.
	 */
	public synchronized void serialEvent(SerialPortEvent oEvent) {
		if (oEvent.getEventType() == SerialPortEvent.DATA_AVAILABLE) {
			try {

				int available = input.available();
				byte chunk[] = new byte[available];
				input.read(chunk, 0, available);
				
				for (byte b : chunk) {
					log.debug("got byte: " +Byte.toString(b));
					
					if(frameIndex < 4){
						if(b == (byte)0x0){
							frameIndex++;
						}else{
							frameIndex = 0;
						}
						continue;
					}
					
					if(frameIndex == 4){
						messageBuffer = ByteBuffer.allocate(4);
						invertedBuffer = ByteBuffer.allocate(4);
					}
					
					if(frameIndex > 3 && frameIndex < 8){
						messageBuffer.put(b);
					}
					
					if(frameIndex > 7){
						invertedBuffer.put(b);
					}
					
					if(frameIndex == 11){
						messageBuffer.rewind();
						int msg = messageBuffer.getInt();
						
						log.info("Message: " + Integer.toHexString(msg));
						
						invertedBuffer.rewind();
						int inv = invertedBuffer.getInt();
						
						log.info("Inverted: " + Integer.toHexString(inv));
						
						if(msg + inv == 0xffffffff){
							messageBuffer.rewind();
							badgeListener.dataAvailable(messageBuffer.array());
							log.debug("Got message hex: " + Integer.toHexString(msg));
						}else{
							log.info("Message had bad checksum: "+ msg + " checksum: " + inv);
						}
						frameIndex = 0;
						continue;
					}
					
					frameIndex++;
					
				}

			} catch (Exception e) {
				// reset state
				frameIndex = 0;
				e.printStackTrace(System.err);
			}
		}
		// Ignore all the other eventTypes, but you should consider the other
		// ones.
	}

	public static void main(String[] args) throws Exception {
		BadgeSerialListener main = new BadgeSerialListener();
		main.initialize();
		System.out.println("Started");
	}

	public void setBadgeListener(BadgeDataListener badgeListener) {
		this.badgeListener = badgeListener;
	}

	public BadgeDataListener getBadgeListener() {
		return badgeListener;
	}
}