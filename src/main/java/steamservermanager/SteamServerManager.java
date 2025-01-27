package steamservermanager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

import steamcmd.SteamCMD;
import steamcmd.SteamCMDBuilder;
import steamcmd.SteamCMDListener;
import steamservermanager.exceptions.ServerNameException;
import steamservermanager.exceptions.ServerNotRunningException;
import steamservermanager.exceptions.StartServerException;
import steamservermanager.interfaces.SteamServerManagerListener;
import steamservermanager.interfaces.UpdateMonitorListener;
import steamservermanager.interfaces.serverrunner.ServerProperties;
import steamservermanager.interfaces.serverrunner.ServerRunnerListener;
import steamservermanager.models.ServerGame;

public class SteamServerManager {

    private List<ServerGame> serverGameLibrary = null;
    private List<ServerRunner> libraryRunning = new ArrayList<>();
       
    private String localLibrary;

    private UpdateThread updateThread;
    private UpdateMonitor updateMonitor;
    private final LibraryFileHelper libraryHelper;
    private SteamCMD steamCmd;
    
    private SteamServerManagerListener listener;

    public SteamServerManager(String localLibrary) {
        this.localLibrary = localLibrary;
        libraryHelper = new LibraryFileHelper(localLibrary);
        serverGameLibrary = libraryHelper.loadLibraryFromDisk();
        this.listener = new ManagerListenerDummy();
        initializeLibraryServerRunner(serverGameLibrary, localLibrary);
        
        new Thread(){
            
            @Override
            public void run() {
                init();
            }
            
        }.start();
    }
    
    private void initializeLibraryServerRunner(List<ServerGame> serverGameLibrary, String localLibrary){
        
        for(ServerGame serverGame : serverGameLibrary){
            libraryRunning.add(new ServerRunner(serverGame, localLibrary, new ServerRunnerListenerImpl()));
        } 
    }

    private void init() {
        updateMonitor = new UpdateMonitor(new UpdateMonitorListenerImpl());
        
        SteamCMDBuilder builder = 
        		new SteamCMDBuilder().addListener(new ConsoleSteamCmdListener());
		
		steamCmd = builder.build();
		
		steamCmd.start();

        updateThread = new UpdateThread(updateMonitor, localLibrary, steamCmd);
        updateThread.start();

        for (ServerGame serverGame : serverGameLibrary) {

            if (serverGame.getStatus().equals(Status.WAITING) || serverGame.getStatus().equals(Status.UPDATING)) {
                updateServerGame(serverGame);
            } else {
                serverGame.setStatus(Status.STOPPED);
            }
        }
        
        listener.onReady();
    }

    public ServerGame newServerGame(int gameId, String serverName, String startScript) throws ServerNameException {

        for (ServerGame s : serverGameLibrary) {

            if (s.getServerName().equals(serverName)) {
                throw new ServerNameException();
            }
        }
        
        ServerGame serverGame = new ServerGame(gameId, serverName, startScript);
        
        serverGameLibrary.add(serverGame);
        libraryRunning.add(new ServerRunner(serverGame, localLibrary, new ServerRunnerListenerImpl()));
        
        updateServerGame(serverGame);
        
        return serverGame;
    }

    public void updateServerGame(ServerGame serverGame) {
        
        for (ServerRunner serverRunner : libraryRunning){
            if(serverRunner.getServerGame().equals(serverGame) && serverRunner.isRunning()){
                serverRunner.forceStop();
            }
        }
        
        updateMonitor.addUpdate(serverGame);
    }

    public ServerProperties startServer(ServerGame serverGame) throws StartServerException {
    	
    	ServerProperties serverProperties = null;
       
    	Optional<ServerRunner> serverRunnerOptional = findServerRunner(serverGame);
    	
    	if (serverRunnerOptional.isPresent()) {
    		
    		ServerRunner serverRunner = serverRunnerOptional.get();
    		
    		if (serverRunner.isRunning() && serverGame.getStatus().equals(Status.RUNNING)) {
    			serverProperties = serverRunner.getServerProperties();
    			
    		} else {
    			libraryRunning.remove(serverRunner);
    			
    			serverRunner = new ServerRunner(serverGame, localLibrary, new ServerRunnerListenerImpl());
    			
    			libraryRunning.add(serverRunner);
    			
    			serverRunner.start();
    			
    			serverProperties = serverRunner.getServerProperties();
    		}
    		
    	} else {
    		ServerRunner serverRunner = new ServerRunner(serverGame, localLibrary, new ServerRunnerListenerImpl());
    		
    		libraryRunning.add(serverRunner);
			
			serverRunner.start();
			
			serverProperties = serverRunner.getServerProperties();
    	}
    	
        if(serverProperties == null){
            throw new StartServerException();
        }
        
        listener.onUpdateServerStatus();
        
        return serverProperties;
    }
    
    private Optional<ServerRunner> findServerRunner(ServerGame serverGame) {
    	
    	for(ServerRunner serverRunner : libraryRunning) {
    		
    		if(serverRunner.getServerGame().equals(serverGame)) {
    			return Optional.of(serverRunner);
    		}
    	}
    	
    	return Optional.empty();
    }
    
    public ServerProperties startServer(String id) throws StartServerException {
        return startServer(getServerGameById(id));    
    }
    
    private ServerGame getServerGameById(String id){
        
        for(ServerGame server : serverGameLibrary){
            
            if(server.getId().equals(id)){
                return server;
            }
        }
        
        return null;   
    }
    
    public ServerProperties getServerProperties(ServerGame serverGame) throws ServerNotRunningException {
        
        for(ServerRunner serverRunner : libraryRunning){
            
            if(serverRunner.getServerGame().equals(serverGame)){
                return serverRunner.getServerProperties();     
            }    
        }
        
        throw new ServerNotRunningException();     
    }

    public List<ServerGame> getLibrary() {
        return serverGameLibrary;
    }

    public String getLocalLibrary() {
        return localLibrary;
    }

    public void setLocalLibrary(String localLibrary) {
        this.localLibrary = localLibrary;
    }
    
    public void setListener(SteamServerManagerListener listener){
        this.listener = listener;
    }
    
    class ServerRunnerListenerImpl implements ServerRunnerListener{

        @Override
        public void onServerStart(ServerGame serverGame) {
            libraryHelper.updateLibraryFile();
            listener.onUpdateServerStatus();
        }

        @Override
        public void onServerStopped(ServerGame serverGame) {
            libraryHelper.updateLibraryFile();
            listener.onUpdateServerStatus();
        }

        @Override
        public void onServerException(ServerGame serverGame) {
            libraryHelper.updateLibraryFile();
            listener.onUpdateServerStatus();
        }     
    }
    
    class UpdateMonitorListenerImpl implements UpdateMonitorListener {

        @Override
        public void onNewUpdate(ServerGame server) {
            libraryHelper.updateLibraryFile();
            listener.onUpdateServerStatus();
        }

        @Override
        public void onGetUpdateJob(ServerGame server) {
            libraryHelper.updateLibraryFile();
            listener.onUpdateServerStatus();
            listener.onUpdateServer(server);
        }

        @Override
        public void onCompleteJob(ServerGame server) {
            libraryHelper.updateLibraryFile();
            listener.onUpdateServerStatus();
            listener.onCompleteUpdateServer();
        }  
    }

    class ConsoleSteamCmdListener implements SteamCMDListener {

        @Override
        public void onStdOut(String out) {
        	
        	System.out.println(out);
        	
            listener.onSteamCMDStdOut(out);
            
            if(out.contains("verifying") || out.contains("downloading")){
                
                String[] splitOut = out.split(":");
                
                String[] pctStringSplit = splitOut[1].split(" ");
                
                String[] statusStringSplit = splitOut[0].split(" ");
                
                double pct = Double.parseDouble(pctStringSplit[1]);
                
                listener.onStatusSteamCMD(statusStringSplit[4].replace(",", ""), pct);
            }
        }

        @Override
        public String onAuthCode() {

            System.out.print("Two-factor code:");
            Scanner sc = new Scanner(System.in);

            String authCode = sc.nextLine();

            sc.close();

            return authCode;
        }

        @Override
        public void onFailedLoginCode() {
            System.err.println("FAILED login with result code Two-factor code mismatch");
        }

        @Override
        public void onInvalidPassword() {
            System.err.println("FAILED login with result code Invalid Password");
        }
    }
    
    class ManagerListenerDummy implements SteamServerManagerListener {

        @Override
        public void onUpdateServerStatus() {
            System.out.println("Status server updated");
        }

        @Override
        public void onSteamCMDStdOut(String out) {
            //System.out.println(out);
        }

        @Override
        public void onReady() {
            System.out.println("Ready!!");
        }

        @Override
        public void onStatusSteamCMD(String status, double pctUpdate) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public void onUpdateServer(ServerGame serverGame) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public void onCompleteUpdateServer() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }
    }
}
