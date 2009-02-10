// Threaddump_p.java 
// -----------------------
// part of YACY
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2004
//
// This File is contributed by Alexander Fieger
//
// $LastChangedDate: 2008-01-22 12:51:43 +0100 (Di, 22 Jan 2008) $
// $LastChangedRevision: 4374 $
// $LastChangedBy: low012 $
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

// You must compile this file with
// javac -classpath .:../Classes Blacklist_p.java
// if the shell's current path is HTROOT

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ArrayList;
import java.util.HashMap;

import de.anomic.htmlFilter.htmlFilterCharacterCoding;
import de.anomic.http.httpRequestHeader;
import de.anomic.kelondro.util.FileUtils;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;
import de.anomic.tools.nxTools;
import de.anomic.yacy.yacyVersion;

public class Threaddump_p {

    public static serverObjects respond(final httpRequestHeader header, final serverObjects post, final serverSwitch<?> env) {
    	
    	serverObjects prop = new serverObjects();
    	plasmaSwitchboard sb = (plasmaSwitchboard) env;
    	
    	final StringBuilder buffer = new StringBuilder(1000);
    	
	    final boolean plain = post.get("plain", "false").equals("true");
	    final int sleep = post.getInt("sleep", 0); // a sleep before creation of a thread dump can be used for profiling
	    if (sleep > 0) try {Thread.sleep(sleep);} catch (final InterruptedException e) {}
	    prop.put("dump", "1");
    	// Thread dump
    	final Date dt = new Date();
    	final String versionstring = yacyVersion.combined2prettyVersion(sb.getConfig("version","0.1"));
    	
    	bufferappend(buffer, plain, "************* Start Thread Dump " + dt + " *******************");
    	bufferappend(buffer, plain, "");
    	bufferappend(buffer, plain, "YaCy Version: " + versionstring);
    	bufferappend(buffer, plain, "Total Memory = " + (Runtime.getRuntime().totalMemory()));
    	bufferappend(buffer, plain, "Used&nbsp;&nbsp;Memory = " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
    	bufferappend(buffer, plain, "Free&nbsp;&nbsp;Memory = " + (Runtime.getRuntime().freeMemory()));
    	bufferappend(buffer, plain, "");
    	bufferappend(buffer, plain, "");
    	
    	int multipleCount = 1000;
        if (post != null && post.containsKey("multipleThreaddump")) {
        	multipleCount = post.getInt("count", multipleCount);
            final ArrayList<Map<Thread,StackTraceElement[]>> traces = new ArrayList<Map<Thread,StackTraceElement[]>>();
            for (int i = 0; i < multipleCount; i++) {
                traces.add(Thread.getAllStackTraces());
            }
            appendStackTraceStats(sb.getRootPath(), buffer, traces, plain, Thread.State.BLOCKED);
            appendStackTraceStats(sb.getRootPath(), buffer, traces, plain, Thread.State.RUNNABLE);
            appendStackTraceStats(sb.getRootPath(), buffer, traces, plain, Thread.State.TIMED_WAITING);
            appendStackTraceStats(sb.getRootPath(), buffer, traces, plain, Thread.State.WAITING);
            appendStackTraceStats(sb.getRootPath(), buffer, traces, plain, Thread.State.NEW);
            appendStackTraceStats(sb.getRootPath(), buffer, traces, plain, Thread.State.TERMINATED);
        } else {
            // generate a single thread dump
            final Map<Thread,StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
            appendStackTraces(sb.getRootPath(), buffer, stackTraces, plain, Thread.State.BLOCKED);
            appendStackTraces(sb.getRootPath(), buffer, stackTraces, plain, Thread.State.RUNNABLE);
            appendStackTraces(sb.getRootPath(), buffer, stackTraces, plain, Thread.State.TIMED_WAITING);
            appendStackTraces(sb.getRootPath(), buffer, stackTraces, plain, Thread.State.WAITING);
            appendStackTraces(sb.getRootPath(), buffer, stackTraces, plain, Thread.State.NEW);
            appendStackTraces(sb.getRootPath(), buffer, stackTraces, plain, Thread.State.TERMINATED);
        }
        
    	bufferappend(buffer, plain, "************* End Thread Dump " + dt + " *******************");
    
    	prop.put("plain_count", multipleCount);
    	prop.put("plain_content", buffer.toString());
    	prop.put("plain", (plain) ? 1 : 0);
    	
       	return prop;    // return from serverObjects respond()
    }    
    
    private static void appendStackTraces(final File rootPath, final StringBuilder buffer, final Map<Thread,StackTraceElement[]> stackTraces, final boolean plain, final Thread.State stateIn) {
        bufferappend(buffer, plain, "THREADS WITH STATES: " + stateIn.toString());
        bufferappend(buffer, plain, "");
        // collect single dumps
        HashMap<String, ArrayList<String>> dumps = dumpCollection(rootPath, stackTraces, plain, stateIn);
        
        // write dumps
        for (final Entry<String, ArrayList<String>> entry: dumps.entrySet()) {
            ArrayList<String> threads = entry.getValue();
            for (int i = 0; i < threads.size(); i++) bufferappend(buffer, plain, threads.get(i));
            bufferappend(buffer, plain, entry.getKey());
            bufferappend(buffer, plain, "");
        }
        bufferappend(buffer, plain, "");
    }
    
    private static void appendStackTraceStats(final File rootPath, final StringBuilder buffer, final ArrayList<Map<Thread,StackTraceElement[]>> traces, final boolean plain, final Thread.State stateIn) {
        bufferappend(buffer, plain, "THREADS WITH STATES: " + stateIn.toString());
        bufferappend(buffer, plain, "");
        // collect single dumps
        HashMap<String, Integer> dumps = dumpStatistic(rootPath, traces, plain, stateIn);
        
        // write dumps
        while (dumps.size() > 0) {
            Entry<String, Integer> e = removeMax(dumps);
            bufferappend(buffer, plain, "Occurrences: " + e.getValue());
            bufferappend(buffer, plain, e.getKey());
            bufferappend(buffer, plain, "");
        }
        bufferappend(buffer, plain, "");
    }
    
    private static Entry<String, Integer> removeMax(HashMap<String, Integer> result) {
        Entry<String, Integer> max = null;
        for (final Entry<String, Integer> e: result.entrySet()) {
            if (max == null || e.getValue().intValue() > max.getValue().intValue()) {
                max = e;
            }
        }
        result.remove(max.getKey());
        return max;
    }
    
    private static HashMap<String, Integer> dumpStatistic(final File rootPath, final ArrayList<Map<Thread,StackTraceElement[]>> stackTraces, final boolean plain, final Thread.State stateIn) {
        Map<Thread,StackTraceElement[]> trace;
        HashMap<String, Integer> result = new HashMap<String, Integer>();
        HashMap<String, ArrayList<String>> x;
        int count;
        for (int i = 0; i < stackTraces.size(); i++) {
            trace = stackTraces.get(i);
            x = dumpCollection(rootPath, trace, plain, stateIn);
            for (final Entry<String, ArrayList<String>> e: x.entrySet()) {
                Integer c = result.get(e.getKey());
                count = e.getValue().size();
                if (c == null) result.put(e.getKey(), new Integer(count));
                else {
                    c = new Integer(c.intValue() + count);
                    result.put(e.getKey(), c);
                }
            }
        }
        return result;
    }
    
    private static HashMap<String, ArrayList<String>> dumpCollection(final File rootPath, final Map<Thread,StackTraceElement[]> stackTraces, final boolean plain, final Thread.State stateIn) {
        final File classPath = new File(rootPath, "source");
  
        Thread thread;
        // collect single dumps
        HashMap<String, ArrayList<String>> dumps = new HashMap<String, ArrayList<String>>();
        for (final Entry<Thread, StackTraceElement[]> entry: stackTraces.entrySet()) {
            thread = entry.getKey();
            final StackTraceElement[] stackTraceElements = entry.getValue();
            StackTraceElement ste;
            String line;
            String tracename = "";
            File classFile;
            if ((stateIn.equals(thread.getState())) && (stackTraceElements.length > 0)) {
                StringBuilder sb = new StringBuilder(3000);
                if (plain) {
                    classFile = getClassFile(classPath, stackTraceElements[stackTraceElements.length - 1].getClassName());
                    tracename = classFile.getName();
                    if (tracename.endsWith(".java")) tracename = tracename.substring(0, tracename.length() - 5);
                    if (tracename.length() > 20) tracename = tracename.substring(0, 20);
                    while (tracename.length() < 20) tracename = tracename + "_";
                    tracename = "[" + tracename + "] ";                
                }                
                String threadtitle = tracename + "Thread= " + thread.getName() + " " + (thread.isDaemon()?"daemon":"") + " id=" + thread.getId() + " " + thread.getState().toString();
                for (int i = 0; i < stackTraceElements.length; i++) {
                    ste = stackTraceElements[i];
                    //if (ste.getClassName().startsWith("java.") || ste.getClassName().startsWith("sun.")) continue;
                    if (i == 0) {
                        line = getLine(getClassFile(classPath, ste.getClassName()), ste.getLineNumber());
                    } else {
                        line = null;
                    }
                    if ((line != null) && (line.length() > 0)) {
                        bufferappend(sb, plain, tracename + "at " + htmlFilterCharacterCoding.unicode2html(ste.toString(), true) + " [" + line.trim() + "]");
                    } else {
                        bufferappend(sb, plain, tracename + "at " + htmlFilterCharacterCoding.unicode2html(ste.toString(), true));
                    }
                }
                String threaddump = sb.toString();
                ArrayList<String> threads = dumps.get(threaddump);
                if (threads == null) threads = new ArrayList<String>();
                threads.add(threadtitle);
                dumps.put(threaddump, threads);
            }
        }
        return dumps;
    }
    
    private static File getClassFile(final File sourcePath, final String classname) {
        final String classPath = classname.replace('.', '/') + ".java";
        final File file = new File(sourcePath, classPath);
        return file;
    }
    
    private static String getLine(final File file, final int line) {
        // find class
        if (!file.exists()) return "";
        try {
            final String lineString = nxTools.line(FileUtils.read(file), line);
            if (lineString == null) return "@ERROR";
            return lineString;
        } catch (final IOException e) {
            return "@EXCEPTION: " + e.getMessage();
        }
    }
    
    private static void bufferappend(final StringBuilder buffer, final boolean plain, final String a) {
        buffer.append(a);
        if (plain) {
            buffer.append("\n");
        } else {
            buffer.append("<br />");
        }
    }

}
