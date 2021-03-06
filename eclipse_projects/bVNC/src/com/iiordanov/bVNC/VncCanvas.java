/**
 * Copyright (C) 2012 Iordan Iordanov
 * Copyright (C) 2010 Michael A. MacDonald
 * Copyright (C) 2004 Horizon Wimba.  All Rights Reserved.
 * Copyright (C) 2001-2003 HorizonLive.com, Inc.  All Rights Reserved.
 * Copyright (C) 2001,2002 Constantin Kaplinsky.  All Rights Reserved.
 * Copyright (C) 2000 Tridia Corporation.  All Rights Reserved.
 * Copyright (C) 1999 AT&T Laboratories Cambridge.  All Rights Reserved.
 * 
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 */

//
// VncCanvas is a subclass of android.view.SurfaceView which draws a VNC
// desktop on it.
//

package com.iiordanov.bVNC;

import java.io.IOException;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Timer;

import android.app.Activity;
import android.app.ProgressDialog;
import android.app.ActivityManager.MemoryInfo;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.text.ClipboardManager;
import android.util.AttributeSet;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.widget.ImageView;
import android.widget.Toast;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;

import com.iiordanov.android.bc.BCFactory;
import com.iiordanov.bVNC.input.RemotePointer;
import com.iiordanov.bVNC.input.RemoteKeyboard;

import com.iiordanov.tigervnc.vncviewer.CConn;

public class VncCanvas extends ImageView {
	private final static String TAG = "VncCanvas";

	AbstractScaling scaling;

	// Variable indicating that we are currently scrolling in simulated touchpad mode.
	boolean inScrolling = false;
	
	// Connection parameters
	ConnectionBean connection;
	VncDatabase database;
	private SSHConnection sshConnection;

	// VNC protocol connection
	public RfbConnectable rfbconn = null;
	public RfbProto rfb = null;
	Socket sock = null;
	public CConn cc = null;
	boolean maintainConnection = true;
	
	// RFB Decoder
	Decoder decoder = null;
	
	// The remote pointer and keyboard
	RemotePointer pointer;
	RemoteKeyboard keyboard;

	// Internal bitmap data
	private int capacity;
	public AbstractBitmapData bitmapData;
	boolean useFull = false;
	boolean compact = false;

	// Handler for the dialog that displays the x509 key signatures to the user.
	public Handler handler = new Handler() {
	    @Override
	    public void handleMessage(Message msg) {
	        switch (msg.what) {
	        case VncConstants.DIALOG_X509_CERT:
	        	final X509Certificate cert = (X509Certificate)msg.obj;

	        	if (connection.getSshHostKey().equals("")) {
	    			// Show a dialog with the key signature for approval.
	    			DialogInterface.OnClickListener signatureNo = new DialogInterface.OnClickListener() {
	    	            @Override
	    	            public void onClick(DialogInterface dialog, int which) {
	    	                // We were told not to continue, so stop the activity
	    	            	closeConnection();
	    	            	((Activity) getContext()).finish();
	    	            }
	    	        };
	    	        DialogInterface.OnClickListener signatureYes = new DialogInterface.OnClickListener() {
	    	            @Override
	    	            public void onClick(DialogInterface dialog, int which) {
	    	    			// We were told to go ahead with the connection, so save the key into the database.
	    	            	String certificate = null;
	    	            	try {
	    	            		certificate = Base64.encodeToString(cert.getEncoded(), Base64.DEFAULT);
							} catch (CertificateEncodingException e) {
								e.printStackTrace();
								showFatalMessageAndQuit("Certificate encoding could not be generated.");
							}
							connection.setSshHostKey(certificate);
			    			connection.save(database.getWritableDatabase());
			    			database.close();
			    			// Indicate the certificate was accepted.
	    	            	certificateAccepted = true;
	    	            }
	    	        };

					// Generate a sha1 signature of the certificate.
				    MessageDigest sha1;
				    MessageDigest md5;
					try {
						sha1 = MessageDigest.getInstance("SHA1");
						md5 = MessageDigest.getInstance("MD5");
			    	    sha1.update(cert.getEncoded());
		    			Utils.showYesNoPrompt(getContext(), "Continue connecting to " + connection.getAddress () + "?",
		    									"The x509 certificate signatures are:"   +
		    									"\nSHA1:  " + Utils.toHexString(sha1.digest()) +
		    									"\nMD5:  "  + Utils.toHexString(md5.digest())  + 
		    									"\nYou can ensure they are identical to the known signatures of the server certificate to prevent a man-in-the-middle attack.",
		    									signatureYes, signatureNo);
					} catch (NoSuchAlgorithmException e2) {
						e2.printStackTrace();
						showFatalMessageAndQuit("Could not generate SHA1 or MD5 signature of certificate. No SHA1/MD5 algorithm found.");
					} catch (CertificateEncodingException e) {
						e.printStackTrace();
						showFatalMessageAndQuit("Certificate encoding could not be generated.");
					}
	        	} else {
					// Compare saved with obtained certificate and quit if they don't match.
	        		try {
						if (!connection.getSshHostKey().equals(Base64.encodeToString(cert.getEncoded(), Base64.DEFAULT))) {
							showFatalMessageAndQuit("ERROR: The saved x509 certificate does not match the current server certificate! " +
									"This could be a man-in-the-middle attack. If you are aware of the key change, delete and recreate the connection.");
						} else {
							// In case we need to display information about the certificate, we can reconstruct it like this:
							//CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
							//ByteArrayInputStream in = new ByteArrayInputStream(Base64.decode(connection.getSshHostKey(), Base64.DEFAULT));
							//X509Certificate c = (X509Certificate)certFactory.generateCertificate(in);
				    	    //android.util.Log.e("  Subject ", c.getSubjectDN().toString());
				    	    //android.util.Log.e("   Issuer  ", c.getIssuerDN().toString());
							// The certificate matches, so we proceed.
	    	            	certificateAccepted = true;
						}
					} catch (CertificateEncodingException e) {
						e.printStackTrace();
						showFatalMessageAndQuit("Certificate encoding could not be generated.");
					}
	        	}
	            break;
	        }
	    }
	};

	// Used to set the contents of the clipboard.
	ClipboardManager clipboard;
	Timer clipboardMonitorTimer;
	ClipboardMonitor clipboardMonitor;
	public boolean serverJustCutText = false;
	
	private Runnable setModes;
	
	// This variable indicates whether or not the user has accepted an untrusted
	// security certificate. Used to control progress while the dialog asking the user
	// to confirm the authenticity of a certificate is displayed.
	public boolean certificateAccepted = false;
	
	/*
	 * Position of the top left portion of the <i>visible</i> part of the screen, in
	 * full-frame coordinates
	 */
	int absoluteXPosition = 0, absoluteYPosition = 0;
	
	/*
	 * How much to shift coordinates over when converting from full to view coordinates.
	 */
	float shiftX = 0, shiftY = 0;

	/*
	 * This variable holds the height of the visible rectangle of the screen. It is used to keep track
	 * of how much of the screen is hidden by the soft keyboard if any.
	 */
	int visibleHeight = -1;

	/*
	 * These variables contain the width and height of the display in pixels
	 */
	int displayWidth = 0;
	int displayHeight = 0;

	/*
	 * Variables used for BB and BB10 hacks.
	 */
	boolean bb10 = false;
	boolean bb   = false;

	/**
	 * Constructor used by the inflation apparatus
	 * 
	 * @param context
	 */
	public VncCanvas(final Context context, AttributeSet attrs) {
		super(context, attrs);

		clipboard = (ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);

		String s = android.os.Build.MODEL;
		if (s.contains("BlackBerry 10"))
			bb10 = true;
		if (s.contains("BlackBerry"))
			bb   = true;

		decoder = new Decoder (this);
	}

	/**
	 * Create a view showing a VNC connection
	 * @param context Containing context (activity)
	 * @param bean Connection settings
	 * @param setModes Callback to run on UI thread after connection is set up
	 */
	void initializeVncCanvas(ConnectionBean bean, VncDatabase db, final Runnable setModes) {

		this.setModes = setModes;
		connection = bean;
		database = db;
		decoder.setColorModel(COLORMODEL.valueOf(bean.getColorModel()));

		// Startup the RFB thread with a nifty progress dialog
		final ProgressDialog pd = ProgressDialog.show(getContext(), 
													  "Connecting...", "Establishing handshake.\nPlease wait...", true,
													  true, new DialogInterface.OnCancelListener() {
			@Override
			public void onCancel(DialogInterface dialog) {
				closeConnection();
				handler.post(new Runnable() {
					public void run() {
						Utils.showFatalErrorMessage(getContext(), "VNC connection aborted!");
					}
				});
			}
		});
		
		// Make this dialog cancellable only upon hitting the Back button and not touching outside.
		pd.setCanceledOnTouchOutside(false);

		final Display display = pd.getWindow().getWindowManager().getDefaultDisplay();
		displayWidth  = display.getWidth();
		displayHeight = display.getHeight();
		Thread t = new Thread () {
			public void run() {
			    try {
			    	if (connection.getConnectionType() < 4) {
			    		connectAndAuthenticate(connection.getUserName(),connection.getPassword());
			    		rfbconn = rfb;
			    		pointer = new RemotePointer (rfbconn, VncCanvas.this, handler);
			    		keyboard = new RemoteKeyboard (rfbconn, VncCanvas.this, handler);
			    		doProtocolInitialisation(displayWidth, displayHeight);
			    		handler.post(new Runnable() {
			    			public void run() {
			    				pd.setMessage("Downloading first frame.\nPlease wait...");
			    			}
			    		});
			    		sendUnixAuth ();
			    		if (connection.getUseLocalCursor())
			    			initializeSoftCursor();
			    		processNormalProtocol(getContext(), pd, setModes);
			    	} else {
			    		cc = new CConn(VncCanvas.this, sock, null, false, connection);
			    		rfbconn = cc;
			    		pointer = new RemotePointer (rfbconn, VncCanvas.this, handler);
			    		keyboard = new RemoteKeyboard (rfbconn, VncCanvas.this, handler);
			    		initializeBitmap(displayWidth, displayHeight);
			    		processNormalProtocolSecure(getContext(), pd, setModes);
			    	}
				} catch (Throwable e) {
					if (maintainConnection) {
						Log.e(TAG, e.toString());
						e.printStackTrace();
						// Ensure we dismiss the progress dialog
						// before we fatal error finish
						if (pd.isShowing())
							pd.dismiss();
	
						if (e instanceof OutOfMemoryError) {
							System.gc();
							showFatalMessageAndQuit("Unable to allocate sufficient memory to draw remote screen. " +
									"To fix this, it's best to restart the application. " +
									"As a last resort, you may try restarting your device.");
						} else {
							String error = "Connection failed!";
							if (e.getMessage() != null) {
								if (e.getMessage().indexOf("authentication") > -1 ||
										e.getMessage().indexOf("Unknown security result") > -1 ||
										e.getMessage().indexOf("password check failed") > -1) {
									error = "VNC authentication failed! Check VNC password (and user if applicable).";
								}
								error = error + "<br>" + e.getLocalizedMessage();
							}
							showFatalMessageAndQuit(error);
						}
					}
				}
			}
		};
		t.start();

		clipboardMonitor = new ClipboardMonitor(getContext(), this);
		if (clipboardMonitor != null) {
			clipboardMonitorTimer = new Timer ();
			clipboardMonitorTimer.schedule(clipboardMonitor, 0, 500);
		}
	}
	
	/**
	 * Sends over the unix username and password if the appropriate option is enabled, in order to automatically
	 * authenticate to x11vnc when it asks for unix credentials (-unixpw).
	 */
	void sendUnixAuth () {
		// If the type of connection is ssh-tunneled and we are told to send the unix credentials, then do so.
		// Do not send the up event if this is a bb10 device, since the up-event hack in processLocalKeyEvent takes care of that...
		if (connection.getConnectionType() == VncConstants.CONN_TYPE_SSH && connection.getAutoXUnixAuth()) {
			keyboard.processLocalKeyEvent(KeyEvent.KEYCODE_UNKNOWN, new KeyEvent(SystemClock.uptimeMillis(),
					connection.getSshUser(), 0, 0));
			keyboard.processLocalKeyEvent(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
			if (!bb10)
				keyboard.processLocalKeyEvent(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
			keyboard.processLocalKeyEvent(KeyEvent.KEYCODE_UNKNOWN, new KeyEvent(SystemClock.uptimeMillis(),
					connection.getSshPassword(), 0, 0));
			keyboard.processLocalKeyEvent(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
			if (!bb10)
				keyboard.processLocalKeyEvent(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
		}
	}
	
	void showFatalMessageAndQuit (final String error) {
		closeConnection();
		handler.post(new Runnable() {
			public void run() {
				Utils.showFatalErrorMessage(getContext(), error);
			}
		});
	}

	/** 
	 * Initializes SSH Tunnel and returns local forwarded port, or
	 * if SSH connection not needed, returns saved plain VNC port.
	 * @return
	 * @throws Exception
	 */
	int getVNCPort() throws Exception {
		if (connection.getConnectionType() == VncConstants.CONN_TYPE_SSH) {
			sshConnection = new SSHConnection(connection);
			return sshConnection.initializeSSHTunnel ();
		} else
			return connection.getPort();
	}
	
	/** 
	 * Returns localhost if using SSH tunnel or saved VNC address.
	 * @return
	 * @throws Exception
	 */
	String getVNCAddress() throws Exception {
		if (connection.getConnectionType() == VncConstants.CONN_TYPE_SSH) {
			sshConnection = new SSHConnection(connection);
			return new String("localhost");
		} else
			return connection.getAddress();
	}
	
	void connectAndAuthenticate(String us, String pw) throws Exception {
		Log.i(TAG, "Connecting to: " + connection.getAddress() + ", port: " + connection.getPort());
		String address = getVNCAddress();
		int vncPort    = getVNCPort();
		boolean anonTLS = (connection.getConnectionType() == VncConstants.CONN_TYPE_ANONTLS);
	    try {
			rfb = new RfbProto(decoder, address, vncPort);
			Log.v(TAG, "Connected to server");
			rfb.initializeAndAuthenticate(us, pw, connection.getUseRepeater(), connection.getRepeaterId(), anonTLS);
	    } catch (Exception e) {
	    	throw new Exception ("Connection to VNC server: " + address + " at port: " + vncPort + " failed.");
	    }
	}

	void doProtocolInitialisation(int dx, int dy) throws IOException {
		rfb.writeClientInit();
		rfb.readServerInit();

		initializeBitmap (dx, dy);
		decoder.setPixelFormat(rfb);
	}

	void initializeBitmap (int dx, int dy) throws IOException {
		Log.i(TAG, "Desktop name is " + rfbconn.desktopName());
		Log.i(TAG, "Desktop size is " + rfbconn.framebufferWidth() + " x " + rfbconn.framebufferHeight());
		int fbsize = rfbconn.framebufferWidth() * rfbconn.framebufferHeight();
		capacity = BCFactory.getInstance().getBCActivityManager().getMemoryClass(Utils.getActivityManager(getContext()));
		
		if (connection.getForceFull() == BitmapImplHint.AUTO) {
			if (fbsize * CompactBitmapData.CAPACITY_MULTIPLIER <= capacity*1024*1024) {
				useFull = true;
				compact = true;
			} else if (fbsize * FullBufferBitmapData.CAPACITY_MULTIPLIER <= capacity*1024*1024) {
				useFull = true;
			} else {
				useFull = false;
			}
		} else
			useFull = (connection.getForceFull() == BitmapImplHint.FULL);

		if (!useFull) {
			bitmapData=new LargeBitmapData(rfbconn, this, dx, dy, capacity);
			android.util.Log.i(TAG, "Using LargeBitmapData.");
		} else {
			try {
				// TODO: Remove this if Android 4.2 receives a fix which causes it to stop drawing
				// the bitmap in CompactBitmapData when under load (say playing a video over VNC).
		        if (!compact || android.os.Build.VERSION.SDK_INT == 17) {
					bitmapData=new FullBufferBitmapData(rfbconn, this, capacity);
		        	android.util.Log.i(TAG, "Using FullBufferBitmapData.");
		        } else {
		        	bitmapData=new CompactBitmapData(rfbconn, this);
		        	android.util.Log.i(TAG, "Using CompactBufferBitmapData.");
		        }
			} catch (Throwable e) { // If despite our efforts we fail to allocate memory, use LBBM.
				if (bitmapData != null)
					bitmapData.dispose();
				bitmapData = null;
				System.gc();
				useFull = false;
				bitmapData=new LargeBitmapData(rfbconn, this, dx, dy, capacity);
				android.util.Log.i(TAG, "Using LargeBitmapData.");
			}
		}
		
		decoder.setBitmapData(bitmapData);
	}

	public boolean isColorModel(COLORMODEL cm) {
		return (decoder.getColorModel() != null) && decoder.getColorModel().equals(cm);
	}
	
	public void setColorModel(COLORMODEL cm) {
		decoder.setColorModel(cm);
	}
	
	public boolean getMouseFollowPan() {
		return connection.getFollowPan();
	}

	public void updateFBSize () {
		try {
			bitmapData.frameBufferSizeChanged ();
		} catch (Throwable e) {
			boolean useLBBM = false;
			
			// If we've run out of memory, try using another bitmapdata type.
			if (e instanceof OutOfMemoryError) {
				if (bitmapData != null)
					bitmapData.dispose();
				bitmapData = null;
				System.gc();

				// If we were using CompactBitmapData, try FullBufferBitmapData.
				if (compact == true) {
					compact = false;
					try {
						bitmapData = new FullBufferBitmapData(rfbconn, this, capacity);
					} catch (Throwable e2) {
						useLBBM = true;
					}
				} else
					useLBBM = true;

				// Failing FullBufferBitmapData or if we weren't using CompactBitmapData, try LBBM.
				if (useLBBM) {
					if (bitmapData != null)
						bitmapData.dispose();
					bitmapData = null;
					System.gc();
					useFull = false;
					bitmapData = new LargeBitmapData(rfbconn, this, getWidth(), getHeight(), capacity);
				}
				decoder.setBitmapData(bitmapData);
			}
		}
		handler.post(drawableSetter);
		handler.post(setModes);
		handler.post(desktopInfo);		
		bitmapData.syncScroll();
	}
	
	public void processNormalProtocolSecure (final Context context, final ProgressDialog pd, final Runnable setModes) throws Exception {
		try {
    		// Initialize the protocol before we dismiss the progress dialog and request for the right
    		// modes to be set.
    		for (int i = 0; i < 6; i++)
    			cc.processMsg();
    		
    		handler.post(new Runnable() {
    			public void run() {
    				pd.setMessage("Downloading first frame.\nPlease wait...");
    			}
    		});
    		
    		for (int i = 0; i < 3; i++)
    			cc.processMsg();
    		
			// Hide progress dialog
			if (pd.isShowing())
				pd.dismiss();

			cc.processProtocol();
		} catch (Exception e) {
			throw e;
		} finally {
			Log.v(TAG, "Closing VNC Connection");
			cc.close();
		}
	}
	
	public void displayShortToastMessage (final CharSequence message) {
		screenMessage = message;
		handler.removeCallbacks(showMessage);
		handler.post(showMessage);
	}

	public void displayShortToastMessage (final int messageID) {
		screenMessage = getResources().getText(messageID);
		handler.removeCallbacks(showMessage);
		handler.post(showMessage);
	}

	public void doneWaiting () {
		bitmapData.doneWaiting();
	}
	
	public void syncScroll () {
		bitmapData.syncScroll();
	}

	public void writeFramebufferUpdateRequest (int x, int y, int w, int h, boolean incremental) throws IOException {
		bitmapData.prepareFullUpdateRequest(incremental);
		rfbconn.writeFramebufferUpdateRequest(x, y, w, h, incremental);
	}
	
	public void writeFullUpdateRequest (boolean incremental) {
		bitmapData.prepareFullUpdateRequest(incremental);
		rfbconn.writeFramebufferUpdateRequest(bitmapData.getXoffset(), bitmapData.getYoffset(),
											  bitmapData.bmWidth(),    bitmapData.bmHeight(), incremental);
	}
	
	public void processNormalProtocol(final Context context, ProgressDialog pd, final Runnable setModes) throws Exception {
		handler.post(drawableSetter);
		handler.post(setModes);
		handler.post(desktopInfo);

		// Hide progress dialog
		if (pd.isShowing())
			pd.dismiss();

		rfb.processProtocol(this, connection.getUseLocalCursor());
	}
	
	/**
	 * Set the device clipboard text with the string parameter.
	 * @param readServerCutText set the device clipboard to the text in this parameter.
	 */
	public void setClipboardText(String s) {
		if (s != null && s.length() > 0) {
			clipboard.setText(s);
		}
	}

	public void closeConnection() {
		maintainConnection = false;
		
		if (keyboard != null) {
			// Tell the server to release any meta keys.
			keyboard.clearMetaState();
			keyboard.processLocalKeyEvent(0, new KeyEvent(KeyEvent.ACTION_UP, 0));
		}
		// Close the rfb connection.
		if (rfbconn != null)
			rfbconn.close();
		
		//rfbconn = null;
		//rfb = null;
		//cc = null;
		//sock = null;
		
		// Close the SSH tunnel.
		if (sshConnection != null) {
			sshConnection.terminateSSHTunnel();
			sshConnection = null;
		}
		onDestroy();
	}

	public void onDestroy() {
		Log.v(TAG, "Cleaning up resources");
		
		removeCallbacksAndMessages();
		if (clipboardMonitorTimer != null) {
			clipboardMonitorTimer.cancel();
			clipboardMonitorTimer.purge();
			clipboardMonitorTimer = null;
		}
		clipboardMonitor = null;
		clipboard        = null;
		setModes         = null;
		decoder          = null;
		database         = null;
		connection       = null;
		scaling          = null;
		drawableSetter   = null;
		screenMessage    = null;
		desktopInfo      = null;

		if (bitmapData != null)
			bitmapData.dispose();
		bitmapData       = null;
		System.gc();
	}

	public void removeCallbacksAndMessages() {
		if (handler != null) {
			handler.removeCallbacksAndMessages(null);
		}
	}

	/*
	 * f(x,s) is a function that returns the coordinate in screen/scroll space corresponding
	 * to the coordinate x in full-frame space with scaling s.
	 * 
	 * This function returns the difference between f(x,s1) and f(x,s2)
	 * 
	 * f(x,s) = (x - i/2) * s + ((i - w)/2)) * s
	 *        = s (x - i/2 + i/2 + w/2)
	 *        = s (x + w/2)
	 * 
	 * 
	 * f(x,s) = (x - ((i - w)/2)) * s
	 * @param oldscaling
	 * @param scaling
	 * @param imageDim
	 * @param windowDim
	 * @param offset
	 * @return
	 */

	/**
	 * Computes the X and Y offset for converting coordinates from full-frame coordinates to view coordinates.
	 */
	public void computeShiftFromFullToView () {
		shiftX = (rfbconn.framebufferWidth()  - getWidth())  / 2;
		shiftY = (rfbconn.framebufferHeight() - getHeight()) / 2;
	}

	/**
	 * Change to Canvas's scroll position to match the absoluteXPosition
	 */
	void scrollToAbsolute()	{
		float scale = getScale();
		scrollTo((int)((absoluteXPosition - shiftX) * scale),
				 (int)((absoluteYPosition - shiftY) * scale));
	}

	public int getAbsoluteX () {
		return absoluteXPosition;
	}

	public int getAbsoluteY () {
		return absoluteYPosition;
	}
	
	/**
	 * Make sure mouse is visible on displayable part of screen
	 */
	public void panToMouse() {
		if (rfbconn == null)
			return;

		boolean panX = true;
		boolean panY = true;

		// Don't pan in a certain direction if dimension scaled is already less 
		// than the dimension of the visible part of the screen.
		if (rfbconn.framebufferWidth()  <= getVisibleWidth())
			panX = false;
		if (rfbconn.framebufferHeight() <= getVisibleHeight())
			panY = false;

		// We only pan if the current scaling is able to pan.
		if (scaling != null && ! scaling.isAbleToPan())
			return;

		int x = pointer.getX();
		int y = pointer.getY();
		boolean panned = false;
		int w = getVisibleWidth();
		int h = getVisibleHeight();
		int iw = getImageWidth();
		int ih = getImageHeight();
		int wthresh = 30;
		int hthresh = 30;

		int newX = absoluteXPosition;
		int newY = absoluteYPosition;

		if (x - absoluteXPosition >= w - wthresh) {
			newX = x - (w - wthresh);
			if (newX + w > iw)
				newX = iw - w;
		} else if (x < absoluteXPosition + wthresh) {
			newX = x - wthresh;
			if (newX < 0)
				newX = 0;
		}
		if ( panX && newX != absoluteXPosition ) {
			absoluteXPosition = newX;
			panned = true;
		}

		if (y - absoluteYPosition >= h - hthresh) {
			newY = y - (h - hthresh);
			if (newY + h > ih)
				newY = ih - h;
		} else if (y < absoluteYPosition + hthresh) {
			newY = y - hthresh;
			if (newY < 0)
				newY = 0;
		}
		if ( panY && newY != absoluteYPosition ) {
			absoluteYPosition = newY;
			panned = true;
		}

		if (panned) {
			//scrollBy(newX - absoluteXPosition, newY - absoluteYPosition);
			scrollToAbsolute();
		}
	}
	
	/**
	 * Pan by a number of pixels (relative pan)
	 * @param dX
	 * @param dY
	 * @return True if the pan changed the view (did not move view out of bounds); false otherwise
	 */
	boolean pan(int dX, int dY) {

		// We only pan if the current scaling is able to pan.
		if (scaling != null && ! scaling.isAbleToPan())
			return false;
		
		double scale = getScale();
		
		double sX = (double)dX / scale;
		double sY = (double)dY / scale;
		
		if (absoluteXPosition + sX < 0)
			// dX = diff to 0
			sX = -absoluteXPosition;
		if (absoluteYPosition + sY < 0)
			sY = -absoluteYPosition;

		// Prevent panning right or below desktop image
		if (absoluteXPosition + getVisibleWidth() + sX > getImageWidth())
			sX = getImageWidth() - getVisibleWidth() - absoluteXPosition;
		if (absoluteYPosition + getVisibleHeight() + sY > getImageHeight())
			sY = getImageHeight() - getVisibleHeight() - absoluteYPosition;

		absoluteXPosition += sX;
		absoluteYPosition += sY;
		if (sX != 0.0 || sY != 0.0) {
			//scrollBy((int)sX, (int)sY);
			scrollToAbsolute();
			return true;
		}
		return false;
	}

	/* (non-Javadoc)
	 * @see android.view.View#onScrollChanged(int, int, int, int)
	 */
	@Override
	protected void onScrollChanged(int l, int t, int oldl, int oldt) {
		super.onScrollChanged(l, t, oldl, oldt);
		if (bitmapData != null) {
			bitmapData.scrollChanged(absoluteXPosition, absoluteYPosition);
			pointer.mouseFollowPan();
		}
	}

	/**
	 * This runnable sets the drawable (contained in bitmapData) for the VncCanvas (ImageView).
	 */
	private Runnable drawableSetter = new Runnable() {
		public void run() {
			if (bitmapData != null)
				bitmapData.setImageDrawable(VncCanvas.this);
			}
	};

	/**
	 * This runnable displays a message on the screen.
	 */
	CharSequence screenMessage;
	private Runnable showMessage = new Runnable() {
			public void run() { Toast.makeText( getContext(), screenMessage, Toast.LENGTH_SHORT).show(); }
	};
	
	/**
	 * This runnable causes a toast with information about the current connection to be shown.
	 */
	private Runnable desktopInfo = new Runnable() {
		public void run() {
			showConnectionInfo();
		}
	};

	/**
	 * Causes a redraw of the bitmapData to happen at the indicated coordinates.
	 */
	public void reDraw(int x, int y, int w, int h) {
		float scale = getScale();
		float shiftedX = x-shiftX;
		float shiftedY = y-shiftY;
		// Make the box slightly larger to avoid artifacts due to truncation errors.
		postInvalidate ((int)((shiftedX-1)*scale),   (int)((shiftedY-1)*scale),
						(int)((shiftedX+w+1)*scale), (int)((shiftedY+h+1)*scale));
	}

	public void showConnectionInfo() {
		if (rfbconn == null)
			return;
		
		String msg = null;
		int idx = rfbconn.desktopName().indexOf("(");
		if (idx > 0) {
			// Breakup actual desktop name from IP addresses for improved
			// readability
			String dn = rfbconn.desktopName().substring(0, idx).trim();
			String ip = rfbconn.desktopName().substring(idx).trim();
			msg = dn + "\n" + ip;
		} else
			msg = rfbconn.desktopName();
		msg += "\n" + rfbconn.framebufferWidth() + "x" + rfbconn.framebufferHeight();
		String enc = rfbconn.getEncoding();
		// Encoding might not be set when we display this message
		if (decoder.getColorModel() != null) {
			if (enc != null && !enc.equals(""))
				msg += ", " + rfbconn.getEncoding() + " encoding, " + decoder.getColorModel().toString();
			else 
				msg += ", " + decoder.getColorModel().toString();
		}
		Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
	}

	/**
	 * Invalidates (to redraw) the location of the remote pointer.
	 */
	public void invalidateMousePosition() {
		if (bitmapData != null) {
			bitmapData.moveCursorRect(pointer.mouseX, pointer.mouseY);
			Rect r = bitmapData.getCursorRect();
			reDraw(r.left, r.top, r.width(), r.height());
		}
	}
	
	/**
	 * Moves soft cursor into a particular location.
	 * @param x
	 * @param y
	 */

    synchronized void softCursorMove(int x, int y) {
    	if (bitmapData.isNotInitSoftCursor()) {
    		initializeSoftCursor();
    	}
    	
    	if (!inScrolling) {
    		pointer.mouseX = x;
    		pointer.mouseY = y;
	    	Rect pCRect = new Rect(bitmapData.getCursorRect());
	    	// Move the cursor.
	    	bitmapData.moveCursorRect(x, y);
	    	// Show the cursor.
			Rect r = bitmapData.getCursorRect();
			reDraw(r.left, r.top, r.width(), r.height());
	    	reDraw(pCRect.left, pCRect.top, pCRect.width(), pCRect.height());
    	}
    }
    
    void initializeSoftCursor () {
		Bitmap bm = BitmapFactory.decodeResource(getResources(), R.drawable.cursor);
		int w = bm.getWidth();
		int h = bm.getHeight();
		int [] tempPixels = new int[w*h];
		bm.getPixels(tempPixels, 0, w, 0, 0, w, h);
    	// Set cursor rectangle as well.
    	bitmapData.setCursorRect(pointer.mouseX, pointer.mouseY, w, h, 0, 0);
    	// Set softCursor to whatever the resource is.
		bitmapData.setSoftCursor (tempPixels);
		bm.recycle();
    }
	
	public RemotePointer getPointer() {
		return pointer;
	}

	public RemoteKeyboard getKeyboard() {
		return keyboard;
	}
	
	float getScale() {
		if (scaling == null)
			return 1;
		return scaling.getScale();
	}
	
	public int getVisibleWidth() {
		return (int)((double)getWidth() / getScale() + 0.5);
	}

	public void setVisibleHeight(int newHeight) {
		visibleHeight = newHeight;
	}
	
	public int getVisibleHeight() {
		if (visibleHeight > 0)
			return (int)((double)visibleHeight / getScale() + 0.5);
		else
			return (int)((double)getHeight() / getScale() + 0.5);
	}

	public int getImageWidth() {
		return rfbconn.framebufferWidth();
	}

	public int getImageHeight() {
		return rfbconn.framebufferHeight();
	}
	
	public int getCenteredXOffset() {
		return (rfbconn.framebufferWidth() - getWidth()) / 2;
	}

	public int getCenteredYOffset() {
		return (rfbconn.framebufferHeight() - getHeight()) / 2;
	}
	
	public float getMinimumScale() {
		if (bitmapData != null) {
			return bitmapData.getMinimumScale();
		} else
			return 1.f;
	}
}