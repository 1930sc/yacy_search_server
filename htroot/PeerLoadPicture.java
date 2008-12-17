import java.awt.Color;
import java.awt.Image;
import java.util.HashMap;
import java.util.Iterator;

import de.anomic.http.httpRequestHeader;
import de.anomic.plasma.plasmaGrafics;
import de.anomic.plasma.plasmaSwitchboardConstants;
import de.anomic.plasma.plasmaGrafics.CircleThreadPiece;
import de.anomic.server.serverBusyThread;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;

public class PeerLoadPicture {
    
    public static Image respond(final httpRequestHeader header, final serverObjects post, final serverSwitch<?> env) {

        int width = 800;
        int height = 600;
        boolean showidle = true;
        
        if (post != null) {
            width = post.getInt("width", 800);
            height = post.getInt("height", 600);
            showidle = post.get("showidle", "true").equals("true");
        }
        
        final CircleThreadPiece idle = new CircleThreadPiece("Idle", new Color(170, 255, 170));
        final CircleThreadPiece misc = new CircleThreadPiece("Misc.", new Color(190,  50, 180));
        final HashMap<String, CircleThreadPiece> pieces = new HashMap<String, CircleThreadPiece>();
        pieces.put(null, idle);
        pieces.put(plasmaSwitchboardConstants.CRAWLSTACK0, new CircleThreadPiece("Stacking0",         new Color(115, 200, 210)));
        pieces.put(plasmaSwitchboardConstants.CRAWLSTACK1, new CircleThreadPiece("Stacking1",         new Color(115, 200, 210)));
        pieces.put(plasmaSwitchboardConstants.INDEXER,    new CircleThreadPiece("Parsing/Indexing", new Color(255, 130,   0)));
        pieces.put(plasmaSwitchboardConstants.INDEX_DIST, new CircleThreadPiece("DHT-Distribution", new Color(119, 136, 153)));
        pieces.put(plasmaSwitchboardConstants.PEER_PING,  new CircleThreadPiece("YaCy Core",        new Color(255, 230, 160)));
        
        final Iterator<String> threads = env.threadNames();
        String threadname;
        serverBusyThread thread;
        
        long busy_time = 0;
        
        //Iterate over threads
        while (threads.hasNext()) {
            threadname = threads.next();
            thread = env.getThread(threadname);
            
            //count total times
            busy_time += thread.getBlockTime();
            busy_time += thread.getExecTime();
            if (showidle) idle.addExecTime(thread.getSleepTime());
            
            //count threadgroup-specific times
            final CircleThreadPiece piece = pieces.get(threadname);
            if (piece == null) {
                misc.addExecTime(thread.getBlockTime()+thread.getExecTime());
            } else {
                piece.addExecTime(thread.getBlockTime()+thread.getExecTime());
            }
        }
        busy_time += idle.getExecTime();
        
        // set respective angles
        final Iterator<CircleThreadPiece> it = pieces.values().iterator();
        CircleThreadPiece current;
        while (it.hasNext()) {
            current = it.next();
            current.setFraction(busy_time);
            //remove unneccessary elements
            if(current.getAngle() == 0) it.remove();
        }
        misc.setFraction(busy_time);
        
        // too small values lead to an error, too big to huge CPU/memory consumption,
        // resulting in possible DOS.
        if (width < 40) width = 40;
        if (width > 1920) width = 1920;
        if (height < 30) height = 30;
        if (height > 1440) height = 1440;
        return plasmaGrafics.getPeerLoadPicture(
                5000,
                width,
                height,
                pieces.values().toArray(new CircleThreadPiece[pieces.size()]),
                misc
        );
    }
}
