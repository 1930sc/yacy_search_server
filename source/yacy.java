// yacy.java
// -----------------------
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.yacy.net
// Frankfurt, Germany, 2004, 2005
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;

import de.anomic.data.translator;
import de.anomic.http.HttpClient;
import de.anomic.http.JakartaCommonsHttpClient;
import de.anomic.http.JakartaCommonsHttpResponse;
import de.anomic.http.httpRequestHeader;
import de.anomic.http.httpd;
import de.anomic.index.indexContainer;
import de.anomic.index.indexRWIEntry;
import de.anomic.index.indexRWIRowEntry;
import de.anomic.index.indexRepositoryReference;
import de.anomic.index.indexURLReference;
import de.anomic.index.indexWord;
import de.anomic.kelondro.blob.BLOBHeap;
import de.anomic.kelondro.blob.MapDataMining;
import de.anomic.kelondro.index.RowCollection;
import de.anomic.kelondro.order.Base64Order;
import de.anomic.kelondro.order.DateFormatter;
import de.anomic.kelondro.util.MemoryControl;
import de.anomic.kelondro.util.ScoreCluster;
import de.anomic.kelondro.util.Log;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.plasma.plasmaSwitchboardConstants;
import de.anomic.plasma.plasmaWordIndex;
import de.anomic.server.serverCore;
import de.anomic.server.serverFileUtils;
import de.anomic.server.serverSemaphore;
import de.anomic.server.serverSystem;
import de.anomic.tools.enumerateFiles;
import de.anomic.tools.yFormatter;
import de.anomic.yacy.yacyClient;
import de.anomic.yacy.yacySeedDB;
import de.anomic.yacy.yacyTray;
import de.anomic.yacy.yacyURL;
import de.anomic.yacy.yacyVersion;

/**
* This is the main class of YaCy. Several threads are started from here:
* <ul>
* <li>one single instance of the plasmaSwitchboard is generated, which itself
* starts a thread with a plasmaHTMLCache object. This object simply counts
* files sizes in the cache and terminates them. It also generates a
* plasmaCrawlerLoader object, which may itself start some more httpc-calling
* threads to load web pages. They terminate automatically when a page has
* loaded.
* <li>one serverCore - thread is started, which implements a multi-threaded
* server. The process may start itself many more processes that handle
* connections.lo
* <li>finally, all idle-dependent processes are written in a queue in
* plasmaSwitchboard which are worked off inside an idle-sensitive loop of the
* main process. (here)
* </ul>
*
* On termination, the following must be done:
* <ul>
* <li>stop feeding of the crawling process because it othervise fills the
* indexing queue.
* <li>say goodbye to connected peers and disable new connections. Don't wait for
* success.
* <li>first terminate the serverCore thread. This prevents that new cache
* objects are queued.
* <li>wait that the plasmaHTMLCache terminates (it should be normal that this
* process already has terminated).
* <li>then wait for termination of all loader process of the
* plasmaCrawlerLoader.
* <li>work off the indexing and cache storage queue. These values are inside a
* RAM cache and would be lost otherwise.
* <li>write all settings.
* <li>terminate.
* </ul>
*/

public final class yacy {
    
    // static objects
    public static final String vString = "@REPL_VERSION@";
    public static double version = 0.1;
    public static boolean pro = false;
    
    public static final String vDATE   = "@REPL_DATE@";
    public static final String copyright = "[ YaCy v" + vString + ", build " + vDATE + " by Michael Christen / www.yacy.net ]";
    public static final String hline = "-------------------------------------------------------------------------------";
   
    /**
     * a reference to the {@link plasmaSwitchboard} created by the
     * {@link yacy#startup(String, long, long)} method.
     */
    private static plasmaSwitchboard sb = null;
    
    /**
     * Semaphore needed by {@link yacy#setUpdaterCallback(serverUpdaterCallback)} to block 
     * until the {@link plasmaSwitchboard }object was created.
     */
    //private static serverSemaphore sbSync = new serverSemaphore(0);
    
    /**
     * Semaphore needed by {@link yacy#waitForFinishedStartup()} to block 
     * until startup has finished
     */
    private static serverSemaphore startupFinishedSync = new serverSemaphore(0);

    /**
    * Starts up the whole application. Sets up all datastructures and starts
    * the main threads.
    *
    * @param homePath Root-path where all information is to be found.
    * @param startupFree free memory at startup time, to be used later for statistics
    */
    private static void startup(final File homePath, final long startupMemFree, final long startupMemTotal) {
        int oldRev=0;
        int newRev=0;

        try {
            // start up
            System.out.println(copyright);
            System.out.println(hline);

            // check java version
            try {
                "a".codePointAt(0); // needs at least Java 1.5
            } catch (final NoSuchMethodError e) {
                System.err.println("STARTUP: Java Version too low. You need at least Java 1.5 to run YaCy");
                Thread.sleep(3000);
                System.exit(-1);
            }
            
            // ensure that there is a DATA directory, if not, create one and if that fails warn and die
            File f = homePath;
            mkdirsIfNeseccary(f);
            f = new File(homePath, "DATA/");
            mkdirsIfNeseccary(f);
			if (!(f.exists())) { 
				System.err.println("Error creating DATA-directory in " + homePath.toString() + " . Please check your write-permission for this folder. YaCy will now terminate."); 
				System.exit(-1); 
			}
            
            // setting up logging
			f = new File(homePath, "DATA/LOG/");
            mkdirsIfNeseccary(f);
			f = new File(homePath, "DATA/LOG/yacy.logging");
			if (!f.exists()) try {
			    serverFileUtils.copy(new File(homePath, "yacy.logging"), f);
            } catch (final IOException e){
                System.out.println("could not copy yacy.logging");
            }
            try{
                Log.configureLogging(homePath, new File(homePath, "DATA/LOG/yacy.logging"));
            } catch (final IOException e) {
                System.out.println("could not find logging properties in homePath=" + homePath);
                e.printStackTrace();
            }
            Log.logConfig("STARTUP", "Java version: " + System.getProperty("java.version", "no-java-version"));
            Log.logConfig("STARTUP", "Operation system: " + System.getProperty("os.name","unknown"));
            Log.logConfig("STARTUP", "Application root-path: " + homePath);
            Log.logConfig("STARTUP", "Time zone: UTC" + DateFormatter.UTCDiffString() + "; UTC+0000 is " + System.currentTimeMillis());
            Log.logConfig("STARTUP", "Maximum file system path length: " + serverSystem.maxPathLength);
            
            f = new File(homePath, "DATA/yacy.running");
            if (f.exists()) {                // another instance running? VM crash? User will have to care about this
                Log.logSevere("STARTUP", "WARNING: the file " + f + " exists, this usually means that a YaCy instance is still running");
                delete(f);
            }
            if(!f.createNewFile())
                Log.logSevere("STARTUP", "WARNING: the file " + f + " can not be created!");
            f.deleteOnExit();
            
            pro = new File(homePath, "libx").exists();
            final String oldconf = "DATA/SETTINGS/httpProxy.conf".replace("/", File.separator);
            final String newconf = "DATA/SETTINGS/yacy.conf".replace("/", File.separator);
            final File oldconffile = new File(homePath, oldconf);
            if (oldconffile.exists()) {
            	final File newconfFile = new File(homePath, newconf);
                if(!oldconffile.renameTo(newconfFile))
                    Log.logSevere("STARTUP", "WARNING: the file " + oldconffile + " can not be renamed to "+ newconfFile +"!");
            }
            sb = new plasmaSwitchboard(homePath, "defaults/yacy.init".replace("/", File.separator), newconf, pro);
            //sbSync.V(); // signal that the sb reference was set
            
            // save information about available memory at startup time
            sb.setConfig("memoryFreeAfterStartup", startupMemFree);
            sb.setConfig("memoryTotalAfterStartup", startupMemTotal);
            
            // hardcoded, forced, temporary value-migration
            sb.setConfig("htTemplatePath", "htroot/env/templates");
            sb.setConfig("parseableExt", "html,htm,txt,php,shtml,asp");

            // if we are running an SVN version, we try to detect the used svn revision now ...
            final Properties buildProp = new Properties();
            final File buildPropFile = new File(homePath,"build.properties");
            try {
                buildProp.load(new FileInputStream(buildPropFile));
            } catch (final Exception e) {
                Log.logWarning("STARTUP", buildPropFile.toString() + " not found in settings path");
            }
            
            oldRev=Integer.parseInt(sb.getConfig("svnRevision", "0"));
            try {
                if (buildProp.containsKey("releaseNr")) {
                    // this normally looks like this: $Revision$
                    final String svnReleaseNrStr = buildProp.getProperty("releaseNr");
                    final Pattern pattern = Pattern.compile("\\$Revision:\\s(.*)\\s\\$",Pattern.DOTALL+Pattern.CASE_INSENSITIVE);
                    final Matcher matcher = pattern.matcher(svnReleaseNrStr);
                    if (matcher.find()) {
                        final String svrReleaseNr = matcher.group(1);
                        try {
                            try {version = Double.parseDouble(vString);} catch (final NumberFormatException e) {version = (float) 0.1;}
                            version = yacyVersion.versvn2combinedVersion(version, Integer.parseInt(svrReleaseNr));
                        } catch (final NumberFormatException e) {}
                        sb.setConfig("svnRevision", svrReleaseNr);
                    }
                }
                newRev=Integer.parseInt(sb.getConfig("svnRevision", "0"));
            } catch (final Exception e) {
                System.err.println("Unable to determine the currently used SVN revision number.");
            }

            sb.setConfig("version", Double.toString(version));
            sb.setConfig("vString", yacyVersion.combined2prettyVersion(Double.toString(version)));
            sb.setConfig("vdate", (vDATE.startsWith("@")) ? DateFormatter.formatShortDay() : vDATE);
            sb.setConfig("applicationRoot", homePath.toString());
            Log.logConfig("STARTUP", "YACY Version: " + version + ", Built " + sb.getConfig("vdate", "00000000"));
            yacyVersion.latestRelease = version;

            // read environment
            final int timeout = Math.max(20000, Integer.parseInt(sb.getConfig("httpdTimeout", "20000")));

            // create some directories
            final File htRootPath = new File(homePath, sb.getConfig("htRootPath", "htroot"));
            final File htDocsPath = sb.getConfigPath(plasmaSwitchboardConstants.HTDOCS_PATH, plasmaSwitchboardConstants.HTDOCS_PATH_DEFAULT);
            mkdirIfNeseccary(htDocsPath);
            //final File htTemplatePath = new File(homePath, sb.getConfig("htTemplatePath","htdocs"));

            // create default notifier picture
            //TODO: Use templates instead of copying images ...
            if (!((new File(htDocsPath, "notifier.gif")).exists())) try {
                serverFileUtils.copy(new File(htRootPath, "env/grafics/empty.gif"),
                                     new File(htDocsPath, "notifier.gif"));
            } catch (final IOException e) {}

            final File htdocsReadme = new File(htDocsPath, "readme.txt");
            if (!(htdocsReadme.exists())) try {serverFileUtils.copy((
                    "This is your root directory for individual Web Content\r\n" +
                    "\r\n" +
                    "Please place your html files into the www subdirectory.\r\n" +
                    "The URL of that path is either\r\n" +
                    "http://www.<your-peer-name>.yacy    or\r\n" +
                    "http://<your-ip>:<your-port>/www\r\n" +
                    "\r\n" +
                    "Other subdirectories may be created; they map to corresponding sub-domains.\r\n" +
                    "This directory shares it's content with the applications htroot path, so you\r\n" +
                    "may access your yacy search page with\r\n" +
                    "http://<your-peer-name>.yacy/\r\n" +
                    "\r\n").getBytes(), htdocsReadme);} catch (final IOException e) {
                        System.out.println("Error creating htdocs readme: " + e.getMessage());
                    }

            final File wwwDefaultPath = new File(htDocsPath, "www");
            mkdirIfNeseccary(wwwDefaultPath);


            final File shareDefaultPath = new File(htDocsPath, "share");
            mkdirIfNeseccary(shareDefaultPath);

            migration.migrate(sb, oldRev, newRev);
            
            // delete old release files
            final int deleteOldDownloadsAfterDays = (int) sb.getConfigLong("update.deleteOld", 30);
            yacyVersion.deleteOldDownloads(sb.releasePath, deleteOldDownloadsAfterDays );
            
            // set user-agent
            final String userAgent = "yacy/" + Double.toString(version) + " (www.yacy.net; "
                    + de.anomic.http.HttpClient.getSystemOST() + ")";
            JakartaCommonsHttpClient.setUserAgent(userAgent);
            
            // start main threads
            final String port = sb.getConfig("port", "8080");
            try {
                final httpd protocolHandler = new httpd(sb);
                final serverCore server = new serverCore(
                        timeout /*control socket timeout in milliseconds*/,
                        true /* block attacks (wrong protocol) */,
                        protocolHandler /*command class*/,
                        sb,
                        30000 /*command max length incl. GET args*/);
                server.setName("httpd:"+port);
                server.setPriority(Thread.MAX_PRIORITY);
                server.setObeyIntermission(false);
                if (server == null) {
                    Log.logSevere("STARTUP", "Failed to start server. Probably port " + port + " already in use.");
                } else {
                    // first start the server
                    sb.deployThread("10_httpd", "HTTPD Server/Proxy", "the HTTPD, used as web server and proxy", null, server, 0, 0, 0, 0);
                    //server.start();

                    // open the browser window
                    final boolean browserPopUpTrigger = sb.getConfig("browserPopUpTrigger", "true").equals("true");
                    if (browserPopUpTrigger) {
                        final String  browserPopUpPage      = sb.getConfig("browserPopUpPage", "ConfigBasic.html");
                        //boolean properPW = (sb.getConfig("adminAccount", "").length() == 0) && (sb.getConfig(httpd.ADMIN_ACCOUNT_B64MD5, "").length() > 0);
                        //if (!properPW) browserPopUpPage = "ConfigBasic.html";
                        final String  browserPopUpApplication = sb.getConfig("browserPopUpApplication", "firefox");
                        serverSystem.openBrowser((server.withSSL()?"https":"http") + "://localhost:" + serverCore.getPortNr(port) + "/" + browserPopUpPage, browserPopUpApplication);
                    }
                    
                    // unlock yacyTray browser popup
                    yacyTray.lockBrowserPopup = false;

                    // Copy the shipped locales into DATA, existing files are overwritten
                    final File locale_work   = sb.getConfigPath("locale.work", "DATA/LOCALE/locales");
                    final File locale_source = sb.getConfigPath("locale.source", "locales");
                    try{
                        final File[] locale_source_files = locale_source.listFiles();
                        mkdirsIfNeseccary(locale_work);
                        File target;
                        for (int i=0; i < locale_source_files.length; i++){
                        	target = new File(locale_work, locale_source_files[i].getName());
                            if (locale_source_files[i].getName().endsWith(".lng")) {
                            	if (target.exists()) delete(target);
                                serverFileUtils.copy(locale_source_files[i], target);
                            }
                        }
                        Log.logInfo("STARTUP", "Copied the default locales to " + locale_work.toString());
                    }catch(final NullPointerException e){
                        Log.logSevere("STARTUP", "Nullpointer Exception while copying the default Locales");
                    }

                    //regenerate Locales from Translationlist, if needed
                    final String lang = sb.getConfig("locale.language", "");
                    if (!lang.equals("") && !lang.equals("default")) { //locale is used
                        String currentRev = "";
                        try{
                            final BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(new File(sb.getConfigPath("locale.translated_html", "DATA/LOCALE/htroot"), lang+"/version" ))));
                            currentRev = br.readLine();
                            br.close();
                        }catch(final IOException e){
                            //Error
                        }

                        if (!currentRev.equals(sb.getConfig("svnRevision", ""))) try { //is this another version?!
                            final File sourceDir = new File(sb.getConfig("htRootPath", "htroot"));
                            final File destDir = new File(sb.getConfigPath("locale.translated_html", "DATA/LOCALE/htroot"), lang);
                            if (translator.translateFilesRecursive(sourceDir, destDir, new File(locale_work, lang + ".lng"), "html,template,inc", "locale")){ //translate it
                                //write the new Versionnumber
                                final BufferedWriter bw = new BufferedWriter(new PrintWriter(new FileWriter(new File(destDir, "version"))));
                                bw.write(sb.getConfig("svnRevision", "Error getting Version"));
                                bw.close();
                            }
                        } catch (final IOException e) {}
                    }
                    // initialize number formatter with this locale
                    yFormatter.setLocale(lang);
                    
                    // registering shutdown hook
                    Log.logConfig("STARTUP", "Registering Shutdown Hook");
                    final Runtime run = Runtime.getRuntime();
                    run.addShutdownHook(new shutdownHookThread(Thread.currentThread(), sb));

                    // save information about available memory after all initializations
                    //try {
                        sb.setConfig("memoryFreeAfterInitBGC", MemoryControl.free());
                        sb.setConfig("memoryTotalAfterInitBGC", MemoryControl.total());
                        System.gc();
                        sb.setConfig("memoryFreeAfterInitAGC", MemoryControl.free());
                        sb.setConfig("memoryTotalAfterInitAGC", MemoryControl.total());
                    //} catch (ConcurrentModificationException e) {}
                    
                    // signal finished startup
                    startupFinishedSync.V();
                        
                    // wait for server shutdown
                    try {
                        sb.waitForShutdown();
                    } catch (final Exception e) {
                        Log.logSevere("MAIN CONTROL LOOP", "PANIC: " + e.getMessage(),e);
                    }
                    // shut down
                    if (RowCollection.sortingthreadexecutor != null) RowCollection.sortingthreadexecutor.shutdown();
                    Log.logConfig("SHUTDOWN", "caught termination signal");
                    server.terminate(false);
                    server.interrupt();
                    server.close();
                    if (server.isAlive()) try {
                        // TODO only send request, don't read response (cause server is already down resulting in error)
                        final yacyURL u = new yacyURL((server.withSSL()?"https":"http")+"://localhost:" + serverCore.getPortNr(port), null);
                        HttpClient.wget(u.toString(), null, 10000); // kick server
                        Log.logConfig("SHUTDOWN", "sent termination signal to server socket");
                    } catch (final IOException ee) {
                        Log.logConfig("SHUTDOWN", "termination signal to server socket missed (server shutdown, ok)");
                    }
                    JakartaCommonsHttpClient.closeAllConnections();
                    MultiThreadedHttpConnectionManager.shutdownAll();
                    
                    // idle until the processes are down
                    if (server.isAlive()) {
                        //Thread.sleep(2000); // wait a while
                        server.interrupt();
                        MultiThreadedHttpConnectionManager.shutdownAll();
                    }
                    Log.logConfig("SHUTDOWN", "server has terminated");
                    sb.close();
                    MultiThreadedHttpConnectionManager.shutdownAll();
                }
            } catch (final Exception e) {
                Log.logSevere("STARTUP", "Unexpected Error: " + e.getClass().getName(),e);
                //System.exit(1);
            }
        } catch (final Exception ee) {
            Log.logSevere("STARTUP", "FATAL ERROR: " + ee.getMessage(),ee);
        } finally {
        	startupFinishedSync.V();
        }
        Log.logConfig("SHUTDOWN", "goodbye. (this is the last line)");
        try {
            System.exit(0);
        } catch (Exception e) {} // was once stopped by de.anomic.net.ftpc$sm.checkExit(ftpc.java:1790)
    }

	/**
	 * @param f
	 */
	private static void delete(File f) {
		if(!f.delete())
		    Log.logSevere("STARTUP", "WARNING: the file " + f + " can not be deleted!");
	}

	/**
	 * @see File#mkdir()
	 * @param path
	 */
	private static void mkdirIfNeseccary(final File path) {
		if (!(path.exists()))
			if(!path.mkdir())
				Log.logWarning("STARTUP", "could not create directory "+ path.toString());
	}

	/**
	 * @see File#mkdirs()
	 * @param path
	 */
	private static void mkdirsIfNeseccary(final File path) {
		if (!(path.exists()))
			if(!path.mkdirs())
				Log.logWarning("STARTUP", "could not create directories "+ path.toString());
	}

	/**
    * Loads the configuration from the data-folder.
    * FIXME: Why is this called over and over again from every method, instead
    * of setting the configurationdata once for this class in main?
    *
    * @param mes Where are we called from, so that the errormessages can be
    * more descriptive.
    * @param homePath Root-path where all the information is to be found.
    * @return Properties read from the configurationfile.
    */
    private static Properties configuration(final String mes, final File homePath) {
        Log.logConfig(mes, "Application Root Path: " + homePath.toString());

        // read data folder
        final File dataFolder = new File(homePath, "DATA");
        if (!(dataFolder.exists())) {
            Log.logSevere(mes, "Application was never started or root path wrong.");
            System.exit(-1);
        }

        final Properties config = new Properties();
        FileInputStream fis = null;
		try {
        	fis  = new FileInputStream(new File(homePath, "DATA/SETTINGS/yacy.conf"));
            config.load(fis);
        } catch (final FileNotFoundException e) {
            Log.logSevere(mes, "could not find configuration file.");
            System.exit(-1);
        } catch (final IOException e) {
            Log.logSevere(mes, "could not read configuration file.");
            System.exit(-1);
        } finally {
        	if(fis != null) {
        		try {
					fis.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
        	}
        }

        return config;
    }
    
    public static void shutdown() {
    	if (sb != null) {
    		// YaCy is running in the same runtime. we can shutdown via interrupt
    		sb.terminate();
    	} else {    	
    		final File applicationRoot = new File(System.getProperty("user.dir").replace('\\', '/'));
    		shutdown(applicationRoot);
    	}
    }
    
    /**
    * Call the shutdown-page of YaCy to tell it to shut down. This method is
    * called if you start yacy with the argument -shutdown.
    *
    * @param homePath Root-path where all the information is to be found.
    */
    static void shutdown(final File homePath) {
        // start up
        System.out.println(copyright);
        System.out.println(hline);

        final Properties config = configuration("REMOTE-SHUTDOWN", homePath);

        // read port
        final int port = serverCore.getPortNr(config.getProperty("port", "8080"));

        // read password
        String encodedPassword = (String) config.get(httpd.ADMIN_ACCOUNT_B64MD5);
        if (encodedPassword == null) encodedPassword = ""; // not defined

        // send 'wget' to web interface
        final httpRequestHeader requestHeader = new httpRequestHeader();
        requestHeader.put(httpRequestHeader.AUTHORIZATION, "realm=" + encodedPassword); // for http-authentify
        final JakartaCommonsHttpClient con = new JakartaCommonsHttpClient(10000, requestHeader);
        JakartaCommonsHttpResponse res = null;
        try {
            res = con.GET("http://localhost:"+ port +"/Steering.html?shutdown=");

            // read response
            if (res.getStatusLine().startsWith("2")) {
                Log.logConfig("REMOTE-SHUTDOWN", "YACY accepted shutdown command.");
                Log.logConfig("REMOTE-SHUTDOWN", "Stand by for termination, which may last some seconds.");
                final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try {
                    serverFileUtils.copyToStream(new BufferedInputStream(res.getDataAsStream()), new BufferedOutputStream(bos));
                } finally {
                    res.closeStream();
                }
            } else {
                Log.logSevere("REMOTE-SHUTDOWN", "error response from YACY socket: " + res.getStatusLine());
                System.exit(-1);
            }
        } catch (final IOException e) {
            Log.logSevere("REMOTE-SHUTDOWN", "could not establish connection to YACY socket: " + e.getMessage());
            System.exit(-1);
        } finally {
            // release connection
            if(res != null) {
                res.closeStream();
            }
        }

        // finished
        Log.logConfig("REMOTE-SHUTDOWN", "SUCCESSFULLY FINISHED remote-shutdown:");
        Log.logConfig("REMOTE-SHUTDOWN", "YACY will terminate after working off all enqueued tasks.");
    }

    /**
    * This method gets all found words and outputs a statistic about the score
    * of the words. The output of this method can be used to create stop-word
    * lists. This method will be called if you start yacy with the argument
    * -genwordstat.
    * FIXME: How can stop-word list be created from this output? What type of
    * score is output?
    *
    * @param homePath Root-Path where all the information is to be found.
    */
    private static void genWordstat(final File homePath) {
        // start up
        System.out.println(copyright);
        System.out.println(hline);

        final Properties config = configuration("GEN-WORDSTAT", homePath);

        // load words
        Log.logInfo("GEN-WORDSTAT", "loading words...");
        final HashMap<String, String> words = loadWordMap(new File(homePath, "yacy.words"));

        // find all hashes
        Log.logInfo("GEN-WORDSTAT", "searching all word-hash databases...");
        final File dbRoot = new File(homePath, config.getProperty("dbPath"));
        final enumerateFiles ef = new enumerateFiles(new File(dbRoot, "WORDS"), true, false, true, true);
        File f;
        String h;
        final ScoreCluster<String> hs = new ScoreCluster<String>();
        while (ef.hasMoreElements()) {
            f = ef.nextElement();
            h = f.getName().substring(0, yacySeedDB.commonHashLength);
            hs.addScore(h, (int) f.length());
        }

        // list the hashes in reverse order
        Log.logInfo("GEN-WORDSTAT", "listing words in reverse size order...");
        String w;
        final Iterator<String> i = hs.scores(false);
        while (i.hasNext()) {
            h = i.next();
            w = words.get(h);
            if (w == null) System.out.print("# " + h); else System.out.print(w);
            System.out.println(" - " + hs.getScore(h));
        }

        // finished
        Log.logConfig("GEN-WORDSTAT", "FINISHED");
    }
    
    /**
     * @param homePath path to the YaCy directory
     * @param networkName 
     */
    public static void minimizeUrlDB(final File homePath, final String networkName) {
        // run with "java -classpath classes yacy -minimizeUrlDB"
        try {Log.configureLogging(homePath, new File(homePath, "DATA/LOG/yacy.logging"));} catch (final Exception e) {}
        final File indexPrimaryRoot = new File(homePath, "DATA/INDEX");
        final File indexSecondaryRoot = new File(homePath, "DATA/INDEX");
        final File indexRoot2 = new File(homePath, "DATA/INDEX2");
        final Log log = new Log("URL-CLEANUP");
        try {
            log.logInfo("STARTING URL CLEANUP");
            
            // db containing all currently loades urls
            final indexRepositoryReference currentUrlDB = new indexRepositoryReference(new File(indexSecondaryRoot, networkName));
            
            // db used to hold all neede urls
            final indexRepositoryReference minimizedUrlDB = new indexRepositoryReference(new File(indexRoot2, networkName));
            
            final int cacheMem = (int)(MemoryControl.max() - MemoryControl.total());
            if (cacheMem < 2048000) throw new OutOfMemoryError("Not enough memory available to start clean up.");
                
            final plasmaWordIndex wordIndex = new plasmaWordIndex(networkName, log, indexPrimaryRoot, indexSecondaryRoot, 10000, false, 1, 0);
            final Iterator<indexContainer> indexContainerIterator = wordIndex.wordContainers("AAAAAAAAAAAA", false, false);
            
            long urlCounter = 0, wordCounter = 0;
            long wordChunkStart = System.currentTimeMillis(), wordChunkEnd = 0;
            String wordChunkStartHash = "AAAAAAAAAAAA", wordChunkEndHash;
            
            while (indexContainerIterator.hasNext()) {
                indexContainer wordIdxContainer = null;
                try {
                    wordCounter++;
                    wordIdxContainer = indexContainerIterator.next();
                    
                    // the combined container will fit, read the container
                    final Iterator<indexRWIRowEntry> wordIdxEntries = wordIdxContainer.entries();
                    indexRWIEntry iEntry;
                    while (wordIdxEntries.hasNext()) {
                        iEntry = wordIdxEntries.next();
                        final String urlHash = iEntry.urlHash();                    
                        if ((currentUrlDB.exists(urlHash)) && (!minimizedUrlDB.exists(urlHash))) try {
                            final indexURLReference urlEntry = currentUrlDB.load(urlHash, null, 0);                       
                            urlCounter++;
                            minimizedUrlDB.store(urlEntry);
                            if (urlCounter % 500 == 0) {
                                log.logInfo(urlCounter + " URLs found so far.");
                            }
                        } catch (final IOException e) {}
                    }
                    
                    if (wordCounter%500 == 0) {
                        wordChunkEndHash = wordIdxContainer.getWordHash();
                        wordChunkEnd = System.currentTimeMillis();
                        final long duration = wordChunkEnd - wordChunkStart;
                        log.logInfo(wordCounter + " words scanned " +
                                "[" + wordChunkStartHash + " .. " + wordChunkEndHash + "]\n" + 
                                "Duration: "+ 500*1000/duration + " words/s" +
                                " | Free memory: " + MemoryControl.free() + 
                                " | Total memory: " + MemoryControl.total());
                        wordChunkStart = wordChunkEnd;
                        wordChunkStartHash = wordChunkEndHash;
                    }
                    
                    // we have read all elements, now we can close it
                    wordIdxContainer = null;
                    
                } catch (final Exception e) {
                    log.logSevere("Exception", e);
                } finally {
                    if (wordIdxContainer != null) try { wordIdxContainer = null; } catch (final Exception e) {}
                }
            }
            log.logInfo("current LURL DB contains " + currentUrlDB.size() + " entries.");
            log.logInfo("mimimized LURL DB contains " + minimizedUrlDB.size() + " entries.");
            
            currentUrlDB.close();
            minimizedUrlDB.close();
            wordIndex.close();
            
            // TODO: rename the mimimized UrlDB to the name of the previous UrlDB            
            
            log.logInfo("FINISHED URL CLEANUP, WAIT FOR DUMP");
            log.logInfo("You can now backup your old URL DB and rename minimized/urlHash.db to urlHash.db");
            
            log.logInfo("TERMINATED URL CLEANUP");
        } catch (final Exception e) {
            log.logSevere("Exception: " + e.getMessage(), e);
        } catch (final Error e) {
            log.logSevere("Error: " + e.getMessage(), e);
        }
    }

    /**
    * Reads all words from the given file and creates a hashmap, where key is
    * the plasma word hash and value is the word itself.
    *
    * @param wordlist File where the words are stored.
    * @return HashMap with the hash-word - relation.
    */
    private static HashMap<String, String> loadWordMap(final File wordlist) {
        // returns a hash-word - Relation
        final HashMap<String, String> wordmap = new HashMap<String, String>();
        try {
            String word;
            final BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(wordlist)));
            while ((word = br.readLine()) != null) wordmap.put(indexWord.word2hash(word), word);
            br.close();
        } catch (final IOException e) {}
        return wordmap;
    }

    /**
    * Cleans a wordlist in a file according to the length of the words. The
    * file with the given filename is read and then only the words in the given
    * length-range are written back to the file.
    *
    * @param wordlist Name of the file the words are stored in.
    * @param minlength Minimal needed length for each word to be stored.
    * @param maxlength Maximal allowed length for each word to be stored.
    */
    private static void cleanwordlist(final String wordlist, final int minlength, final int maxlength) {
        // start up
        System.out.println(copyright);
        System.out.println(hline);
        Log.logConfig("CLEAN-WORDLIST", "START");

        String word;
        final TreeSet<String> wordset = new TreeSet<String>();
        int count = 0;
        try {
            final BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(wordlist)));
            final String seps = "' .,:/-&";
            while ((word = br.readLine()) != null) {
                word = word.toLowerCase().trim();
                for (int i = 0; i < seps.length(); i++) {
                    if (word.indexOf(seps.charAt(i)) >= 0) word = word.substring(0, word.indexOf(seps.charAt(i)));
                }
                if ((word.length() >= minlength) && (word.length() <= maxlength)) wordset.add(word);
                count++;
            }
            br.close();

            if (wordset.size() != count) {
                count = count - wordset.size();
                final BufferedWriter bw = new BufferedWriter(new PrintWriter(new FileWriter(wordlist)));
                while (wordset.size() > 0) {
                    word = wordset.first();
                    bw.write(word + "\n");
                    wordset.remove(word);
                }
                bw.close();
                Log.logInfo("CLEAN-WORDLIST", "shrinked wordlist by " + count + " words.");
            } else {
                Log.logInfo("CLEAN-WORDLIST", "not necessary to change wordlist");
            }
        } catch (final IOException e) {
            Log.logSevere("CLEAN-WORDLIST", "ERROR: " + e.getMessage());
            System.exit(-1);
        }

        // finished
        Log.logConfig("CLEAN-WORDLIST", "FINISHED");
    }

    private static void transferCR(final String targetaddress, final String crfile) {
        final File f = new File(crfile);
        try {
            final byte[] b = serverFileUtils.read(f);
            final String result = yacyClient.transfer(targetaddress, f.getName(), b);
            if (result == null)
                Log.logInfo("TRANSFER-CR", "transmitted file " + crfile + " to " + targetaddress + " successfully");
            else
                Log.logInfo("TRANSFER-CR", "error transmitting file " + crfile + " to " + targetaddress + ": " + result);
        } catch (final IOException e) {
            Log.logInfo("TRANSFER-CR", "could not read file " + crfile);
        }
    }
    
    private static String[] shift(final String[] args, final int pos, final int count) {
        final String[] newargs = new String[args.length - count];
        System.arraycopy(args, 0, newargs, 0, pos);
        System.arraycopy(args, pos + count, newargs, pos, args.length - pos - count);
        return newargs;
    }
    
    /**
     * Uses an Iteration over urlHash.db to detect malformed URL-Entries.
     * Damaged URL-Entries will be marked in a HashSet and removed at the end of the function.
     *
     * @param homePath Root-Path where all information is to be found.
     */
    private static void urldbcleanup(final File homePath, final String networkName) {
        final File root = homePath;
        final File indexroot = new File(root, "DATA/INDEX");
        try {Log.configureLogging(homePath, new File(homePath, "DATA/LOG/yacy.logging"));} catch (final Exception e) {}
        final indexRepositoryReference currentUrlDB = new indexRepositoryReference(new File(indexroot, networkName));
        currentUrlDB.deadlinkCleaner(null);
        currentUrlDB.close();
    }
    
    private static void RWIHashList(final File homePath, final String targetName, final String resource, final String format) {
        plasmaWordIndex WordIndex = null;
        final Log log = new Log("HASHLIST");
        final File indexPrimaryRoot = new File(homePath, "DATA/INDEX");
        final File indexSecondaryRoot = new File(homePath, "DATA/INDEX");
        final String wordChunkStartHash = "AAAAAAAAAAAA";
        try {Log.configureLogging(homePath, new File(homePath, "DATA/LOG/yacy.logging"));} catch (final Exception e) {}
        log.logInfo("STARTING CREATION OF RWI-HASHLIST");
        final File root = homePath;
        try {
            Iterator<indexContainer> indexContainerIterator = null;
            if (resource.equals("all")) {
                WordIndex = new plasmaWordIndex("freeworld", log, indexPrimaryRoot, indexSecondaryRoot, 10000, false, 1, 0);
                indexContainerIterator = WordIndex.wordContainers(wordChunkStartHash, false, false);
            }
            int counter = 0;
            indexContainer container = null;
            if (format.equals("zip")) {
                log.logInfo("Writing Hashlist to ZIP-file: " + targetName + ".zip");
                final ZipEntry zipEntry = new ZipEntry(targetName + ".txt");
                final File file = new File(root, targetName + ".zip");
                final ZipOutputStream bos = new ZipOutputStream(new FileOutputStream(file));
                bos.putNextEntry(zipEntry);
                if(indexContainerIterator != null) {
                    while (indexContainerIterator.hasNext()) {
                        counter++;
                        container = indexContainerIterator.next();
                        bos.write((container.getWordHash()).getBytes());
                        bos.write(serverCore.CRLF);
                        if (counter % 500 == 0) {
                            log.logInfo("Found " + counter + " Hashs until now. Last found Hash: " + container.getWordHash());
                        }
                    }
                }
                bos.flush();
                bos.close();
            } else {
                log.logInfo("Writing Hashlist to TXT-file: " + targetName + ".txt");
                final File file = new File(root, targetName + ".txt");
                final BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file));
                if(indexContainerIterator != null) {
                    while (indexContainerIterator.hasNext()) {
                        counter++;
                        container = indexContainerIterator.next();
                        bos.write((container.getWordHash()).getBytes());
                        bos.write(serverCore.CRLF);
                        if (counter % 500 == 0) {
                            log.logInfo("Found " + counter + " Hashs until now. Last found Hash: " + container.getWordHash());
                        }
                    }
                }
                bos.flush();
                bos.close();
            }
            log.logInfo("Total number of Hashs: " + counter + ". Last found Hash: " + (container == null ? "null" : container.getWordHash()));
        } catch (final IOException e) {
            log.logSevere("IOException", e);
        }
        if (WordIndex != null) {
            WordIndex.close();
            WordIndex = null;
        }
    }
    
    /**
     * Searching for peers affected by Bug
     * @param homePath
     */
    public static void testPeerDB(final File homePath) {
        
        try {
            final File yacyDBPath = new File(homePath, "DATA/INDEX/freeworld/NETWORK");
            
            final String[] dbFileNames = {"seed.new.db","seed.old.db","seed.pot.db"};
            for (int i=0; i < dbFileNames.length; i++) {
                final File dbFile = new File(yacyDBPath,dbFileNames[i]);
                final MapDataMining db = new MapDataMining(new BLOBHeap(dbFile, yacySeedDB.commonHashLength, Base64Order.enhancedCoder, 1024 * 512), 500, yacySeedDB.sortFields, yacySeedDB.longaccFields, yacySeedDB.doubleaccFields, null, null);
                
                MapDataMining.mapIterator it;
                it = db.maps(true, false);
                while (it.hasNext()) {
                    final Map<String, String> dna = it.next();
                    String peerHash = dna.get("key");
                    if (peerHash.length() < yacySeedDB.commonHashLength) {
                        final String peerName = dna.get("Name");
                        final String peerIP = dna.get("IP");
                        final String peerPort = dna.get("Port");
                        
                        while (peerHash.length() < yacySeedDB.commonHashLength) { peerHash = peerHash + "_"; }                        
                        System.err.println("Invalid Peer-Hash found in '" + dbFileNames[i] + "': " + peerName + ":" +  peerHash + ", http://" + peerIP + ":" + peerPort);
                    }
                }
                db.close();
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

 
    /**
     * Main-method which is started by java. Checks for special arguments or
     * starts up the application.
     * 
     * @param args
     *            Given arguments from the command line.
     */
    public static void main(String args[]) {

        // check assertion status
        //ClassLoader.getSystemClassLoader().setDefaultAssertionStatus(true);
        boolean assertionenabled = false;
        assert assertionenabled = true;
        if (assertionenabled) System.out.println("Asserts are enabled");
        
        // check memory amount
        System.gc();
        final long startupMemFree  = MemoryControl.free();
        final long startupMemTotal = MemoryControl.total();
        
        // go into headless awt mode
        System.setProperty("java.awt.headless", "true");
        
        File applicationRoot = new File(System.getProperty("user.dir").replace('\\', '/'));
        //System.out.println("args.length=" + args.length);
        //System.out.print("args=["); for (int i = 0; i < args.length; i++) System.out.print(args[i] + ", "); System.out.println("]");
        if ((args.length >= 1) && ((args[0].toLowerCase().equals("-startup")) || (args[0].equals("-start")))) {
            // normal start-up of yacy
            if (args.length == 2) applicationRoot= new File(args[1]);
            startup(applicationRoot, startupMemFree, startupMemTotal);
        } else if ((args.length >= 1) && ((args[0].toLowerCase().equals("-shutdown")) || (args[0].equals("-stop")))) {
            // normal shutdown of yacy
            if (args.length == 2) applicationRoot= new File(args[1]);
            shutdown(applicationRoot);
        } else if ((args.length >= 1) && (args[0].toLowerCase().equals("-minimizeurldb"))) {
            // migrate words from DATA/PLASMADB/WORDS path to assortment cache, if possible
            // attention: this may run long and should not be interrupted!
            if (args.length >= 3 && args[1].toLowerCase().equals("-cache")) {
                args = shift(args, 1, 2);
            }
            if (args.length == 2) applicationRoot= new File(args[1]);
            minimizeUrlDB(applicationRoot, "freeworld");
        } else if ((args.length >= 1) && (args[0].toLowerCase().equals("-testpeerdb"))) {
            if (args.length == 2) {
                applicationRoot = new File(args[1]);
            } else if (args.length > 2) {
                System.err.println("Usage: -testPeerDB [homeDbRoot]");
            }
            testPeerDB(applicationRoot);
        } else if ((args.length >= 1) && (args[0].toLowerCase().equals("-genwordstat"))) {
            // this can help to create a stop-word list
            // to use this, you need a 'yacy.words' file in the root path
            // start this with "java -classpath classes yacy -genwordstat [<rootdir>]"
            if (args.length == 2) applicationRoot= new File(args[1]);
            genWordstat(applicationRoot);
        } else if ((args.length == 4) && (args[0].toLowerCase().equals("-cleanwordlist"))) {
            // this can be used to organize and clean a word-list
            // start this with "java -classpath classes yacy -cleanwordlist <word-file> <minlength> <maxlength>"
            final int minlength = Integer.parseInt(args[2]);
            final int maxlength = Integer.parseInt(args[3]);
            cleanwordlist(args[1], minlength, maxlength);
        } else if ((args.length >= 1) && (args[0].toLowerCase().equals("-transfercr"))) {
            // transfer a single cr file to a remote peer
            final String targetaddress = args[1];
            final String crfile = args[2];
            transferCR(targetaddress, crfile);
        } else if ((args.length >= 1) && (args[0].toLowerCase().equals("-urldbcleanup"))) {
            // generate a url list and save it in a file
            if (args.length == 2) applicationRoot= new File(args[1]);
            urldbcleanup(applicationRoot, "freeworld");
        } else if ((args.length >= 1) && (args[0].toLowerCase().equals("-rwihashlist"))) {
            // generate a url list and save it in a file
            String domain = "all";
            String format = "txt";
            if (args.length >= 2) domain= args[1];
            if (args.length >= 3) format= args[2];
            if (args.length == 4) applicationRoot= new File(args[3]);
            final String outfile = "rwihashlist_" + System.currentTimeMillis();
            RWIHashList(applicationRoot, outfile, domain, format);
        } else {
            if (args.length == 1) applicationRoot= new File(args[0]);
            startup(applicationRoot, startupMemFree, startupMemTotal);
        }
    }
}

/**
* This class is a helper class whose instance is started, when the java virtual
* machine shuts down. Signals the plasmaSwitchboard to shut down.
*/
class shutdownHookThread extends Thread {
    private plasmaSwitchboard sb = null;
    private Thread mainThread = null;

    public shutdownHookThread(final Thread mainThread, final plasmaSwitchboard sb) {
        super();
        this.sb = sb;
        this.mainThread = mainThread;
    }

    public void run() {
        try {
            if (!this.sb.isTerminated()) {
                Log.logConfig("SHUTDOWN","Shutdown via shutdown hook.");

                // sending the yacy main thread a shutdown signal
                Log.logFine("SHUTDOWN","Signaling shutdown to the switchboard.");
                this.sb.terminate();

                // waiting for the yacy thread to finish execution
                Log.logFine("SHUTDOWN","Waiting for main thread to finish.");
                if (this.mainThread.isAlive() && !this.sb.isTerminated()) {
                    this.mainThread.join();
                }
            }
        } catch (final Exception e) {
            Log.logSevere("SHUTDOWN","Unexpected error. " + e.getClass().getName(),e);
        }
    }
}
