import java.util.TimerTask;

class TimeoutHandler extends TimerTask {
	
	public FastFtp fastFTP;
	
	public TimeoutHandler(FastFtp ftp) {
		fastFTP = ftp;
	}
	
	public void run() {
		fastFTP.processTimeout();
	}

}